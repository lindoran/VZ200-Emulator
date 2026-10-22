package emulator.vz200;

import java.io.IOException;

/**
 * Minimal SD card in SPI mode, just enough for the Benn Venn SD
 * Loader's DOS (VZDOS) to initialise it and read / write 512-byte
 * blocks.  Behaviour is byte-transfer level: no timing, no CRC
 * checking, no multi-block commands.  Always presents itself as an
 * SDHC card (block addressing).
 */
public class SDCard
{
  private static final int ST_CMD = 0;
  private static final int ST_WRITE_TOKEN = 1;
  private static final int ST_WRITE_DATA = 2;

  private final BlockDevice dev;
  private final boolean trace;

  private boolean selected;
  private boolean idle = true;        // between CMD0 and ACMD41 success
  private boolean appCmd;             // CMD55 seen, next command is ACMD
  private int acmd41Calls;
  private int state = ST_CMD;

  private final int[] cmd = new int[6];
  private int cmdLen;

  private final int[] out = new int[1024];
  private int outHead;
  private int outCount;

  private final byte[] sector = new byte[BlockDevice.SECTOR_SIZE];
  private final byte[] wbuf = new byte[BlockDevice.SECTOR_SIZE + 2];
  private int wcount;
  private long wlba;

  private long sectorReads;
  private long sectorWrites;

  public SDCard(final BlockDevice dev, final boolean trace)
  {
    this.dev = dev;
    this.trace = trace;
  }

  public BlockDevice getDevice() { return dev; }
  public long getSectorReads() { return sectorReads; }
  public long getSectorWrites() { return sectorWrites; }
  public boolean isIdle() { return idle; }

  private void push(final int b)
  {
    out[(outHead + outCount) & (out.length - 1)] = b & 0xff;
    outCount++;
  }

  private int pop()
  {
    final int b = out[outHead];
    outHead = (outHead + 1) & (out.length - 1);
    outCount--;
    return b;
  }

  private void clearOutput()
  {
    outHead = 0;
    outCount = 0;
  }

  /** Chip select; a de-assert aborts any command or transfer in flight. */
  public void select(final boolean sel)
  {
    if (selected && !sel) {
      clearOutput();
      cmdLen = 0;
      state = ST_CMD;
    }
    selected = sel;
  }

  /**
   * One full-duplex byte exchange; returns the byte shifted in while
   * {@code tx} was shifted out.
   */
  public int transfer(final int tx)
  {
    if (!selected) {
      return 0xff;
    }
    final int rx = outCount > 0 ? pop() : 0xff;
    switch (state) {
    case ST_WRITE_TOKEN:
      if (tx == 0xfe) {
        state = ST_WRITE_DATA;
        wcount = 0;
      }
      break;
    case ST_WRITE_DATA:
      wbuf[wcount++] = (byte)tx;
      if (wcount == wbuf.length) {
        commitWrite();
      }
      break;
    default:
      if (cmdLen == 0) {
        if ((tx & 0xc0) == 0x40) {
          clearOutput();
          cmd[cmdLen++] = tx;
        }
      } else {
        cmd[cmdLen++] = tx;
        if (cmdLen == cmd.length) {
          cmdLen = 0;
          execute();
        }
      }
      break;
    }
    return rx;
  }

  private void execute()
  {
    final int c = cmd[0] & 0x3f;
    final long arg = ((long)cmd[1] << 24) | (cmd[2] << 16) | (cmd[3] << 8) |
      cmd[4];
    final boolean wasApp = appCmd;
    appCmd = false;
    if (trace) {
      System.out.printf("[sd] %sCMD%d arg=%08X%n", wasApp ? "A" : "", c, arg);
    }
    final int r1Idle = idle ? 0x01 : 0x00;
    if (wasApp && c == 41) {
      acmd41Calls++;
      if (acmd41Calls >= 2) {
        idle = false;
        push(0x00);
      } else {
        push(0x01);
      }
      return;
    }
    switch (c) {
    case 0:
      idle = true;
      acmd41Calls = 0;
      push(0x01);
      break;
    case 8:
      push(r1Idle);
      push(0x00); push(0x00); push((int)((arg >> 8) & 0x0f)); push((int)(arg & 0xff));
      break;
    case 55:
      appCmd = true;
      push(r1Idle);
      break;
    case 58:
      push(r1Idle);
      push(idle ? 0x40 : 0xc0); push(0xff); push(0x80); push(0x00);
      break;
    case 16:
    case 12:
    case 13:
      push(r1Idle);
      break;
    case 17:
      readBlock(arg);
      break;
    case 24:
      startWrite(arg);
      break;
    default:
      push(r1Idle | 0x04);              // illegal command
      break;
    }
  }

  private void readBlock(final long lba)
  {
    if (idle) {
      push(0x05);
      return;
    }
    if (lba >= dev.getSectorCount()) {
      push(0x40);                       // parameter error
      return;
    }
    try {
      dev.readSector(lba, sector);
    } catch (final IOException e) {
      System.out.println("[sd] read error at LBA " + lba + ": " + e);
      push(0x00);
      push(0x01);                       // data error token
      return;
    }
    sectorReads++;
    push(0x00);                         // R1
    push(0xfe);                         // data token
    for (int i = 0; i < sector.length; i++) {
      push(sector[i]);
    }
    push(0xff); push(0xff);             // CRC (not computed)
  }

  private void startWrite(final long lba)
  {
    if (idle) {
      push(0x05);
      return;
    }
    if (lba >= dev.getSectorCount()) {
      push(0x40);
      return;
    }
    wlba = lba;
    push(0x00);
    state = ST_WRITE_TOKEN;
  }

  private void commitWrite()
  {
    state = ST_CMD;
    final byte[] data = new byte[BlockDevice.SECTOR_SIZE];
    System.arraycopy(wbuf, 0, data, 0, data.length);
    try {
      dev.writeSector(wlba, data);
      sectorWrites++;
      push(0x05);                       // data accepted
    } catch (final IOException e) {
      System.out.println("[sd] write error at LBA " + wlba + ": " + e);
      push(0x0d);                       // write error
    }
    push(0x00); push(0x00);             // brief "busy"
  }
}
