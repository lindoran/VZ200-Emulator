package emulator.vz200;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import emulator.z80.MemoryBus;

/**
 * Register-level model of the Benn Venn SD Loader (Mk I / Mk II).
 *
 * This is NOT a logic-level model.  It reproduces what the Z80 can see:
 *
 *   I/O ports (decimal as in the manual)
 *     55 (0x37) memory control     bit0 EPROM(0)/SRAM(1) in the DOS area
 *                                  bit1 DOS-area SRAM bank, bit2 high SRAM bank,
 *                                  bit3 LED
 *     56 (0x38) SPI config         bit1 = SD chip-select asserted
 *     57 (0x39) SPI data           write = exchange a byte, read = last byte shifted in
 *     58 (0x3A) RAM map            0 = loader SRAM from $9000 (VZ200),
 *                                  1 = from $B800 (VZ300)
 *     74 (0x4A) SNES pad enable    stored only
 *
 *   NOTE: the manual's port table lists 55 as the RAM map and 58 as the
 *   bank register.  The firmware (VZDOS) and the Elite / Mercenary ports
 *   use them the other way round, which is what is modelled here.
 *
 *   Memory
 *     $4000-$5FFF  EPROM (VZDOS firmware) while port 55 bit0 = 0, else SRAM.
 *     $6000-$67FF  Always SRAM; the EPROM does not reach this 2K.
 *     $4000-$67FF  SRAM bank chosen by port 55 bit1.
 *     $9000 or $B800 up to $FFFF
 *                  Loader SRAM, 2 banks (bit2 of port 55).
 *     Every memory write also reaches the SRAM, at the CPU address, whatever
 *     the area (the CPLD asserts SRAM chip select on any write).  It only
 *     becomes visible where the SRAM is later read, e.g. data written to
 *     $9000-$B7FF while the VZ300 map is on is there after switching back to
 *     the VZ200 map.
 *     (Decode taken from the CPLD source VZ300.vhd; the SRAM top address line
 *     is the bank bit selected by CPU A15: A15=1 -> bit 2, A15=0 -> bit 1.)
 *
 * Not modelled: the SNES pad ports, SPI timing, EPROM write-protect.
 */
public class SDLoader
{
  public static final int PORT_BANK = 0x37;
  public static final int PORT_SPI_CFG = 0x38;
  public static final int PORT_SPI_DATA = 0x39;
  public static final int PORT_RAMMAP = 0x3a;
  public static final int PORT_JOY_SELECT = 0x4a;

  public static final int DOS_START = 0x4000;
  public static final int DOS_END = 0x67ff;
  public static final int DOS_SIZE = DOS_END - DOS_START + 1;
  public static final int EPROM_SIZE = 0x2000;
  public static final int HI_START = 0x9000;
  public static final int HI_SIZE = 0x10000 - HI_START;

  private final int[] eprom = new int[EPROM_SIZE];
  private final int[][] dosSram = new int[2][DOS_SIZE];
  private final int[][] hiSram = new int[2][HI_SIZE];

  private SDCard card;
  private final boolean trace;
  private int ramMap;
  private int spiCfg;
  private int bank;
  private int joySelect = 1;
  private int spiRx = 0xff;

  public SDLoader(final boolean trace)
  {
    this.trace = trace;
    java.util.Arrays.fill(eprom, 0xff);
  }

  public void insertCard(final SDCard card)
  {
    this.card = card;
  }

  public SDCard getCard() { return card; }
  public int getRamMap() { return ramMap; }
  public int getBankRegister() { return bank; }
  public int getJoySelect() { return joySelect; }
  public boolean isLedOn() { return (bank & 0x08) != 0; }

  /**
   * Loads the loader's EPROM.  Accepts a raw VZDOS.BIN/.OBJ image or a
   * VZDOS.VZ snapshot (24 byte header is stripped).
   */
  public void loadEprom(final File f) throws IOException
  {
    byte[] d = Files.readAllBytes(f.toPath());
    int off = 0;
    if (d.length > 24 && ((d[21] & 0xff) == 0xf1 || (d[21] & 0xff) == 0xf0) &&
        !((d[0] & 0xff) == 0xaa && (d[1] & 0xff) == 0x55)) {
      off = 24;
    }
    final int n = Math.min(EPROM_SIZE, d.length - off);
    for (int i = 0; i < n; i++) {
      eprom[i] = d[off + i] & 0xff;
    }
    if (eprom[0] != 0xaa || eprom[1] != 0x55 || eprom[2] != 0xe7 ||
        eprom[3] != 0x18) {
      System.out.println("[sd] WARNING: " + f + " does not start with the " +
                         "AA 55 E7 18 cartridge signature; BASIC will not " +
                         "start it");
    }
    System.out.printf("[sd] DOS EPROM: %s (%d bytes%s)%n", f, n,
                      off != 0 ? ", .VZ header stripped" : "");
  }

  // ------------------------------------------------------------ memory

  /** First address of the loader's high SRAM, per the RAM map port. */
  private int hiStart()
  {
    return (ramMap & 1) != 0 ? 0xb800 : HI_START;
  }

  private int memRead(final int a)
  {
    if (a >= DOS_START && a <= DOS_END) {
      final int off = a - DOS_START;
      if (off < EPROM_SIZE && (bank & 0x01) == 0) {
        return eprom[off];                       // EPROM covers $4000-$5FFF only
      }
      return dosSram[(bank >> 1) & 1][off];      // $6000-$67FF is always SRAM
    }
    if (a >= hiStart() && a <= 0xffff) {
      return hiSram[(bank >> 2) & 1][a - HI_START];
    }
    return MemoryBus.BusWriter.BYTE_UNDEFINED;
  }

  private void memWrite(final int a, final int v)
  {
    if (a >= DOS_START && a <= DOS_END) {
      dosSram[(bank >> 1) & 1][a - DOS_START] = v & 0xff;
    } else if (a >= HI_START && a <= 0xffff) {
      // Any write reaches the SRAM; with the VZ300 map $9000-$B7FF is not
      // readable from the loader, but the data is there for the VZ200 map.
      hiSram[(bank >> 2) & 1][a - HI_START] = v & 0xff;
    }
  }

  /** Bus view for the CPU's memory address space. */
  public class MemoryView implements MemoryBus.BusReader, MemoryBus.BusWriter
  {
    public int readByte(final int address, final long wallClockTime)
    {
      return memRead(address & 0xffff);
    }

    public int readShort(final int address, final long wallClockTime)
    {
      return memRead(address & 0xffff) | (memRead((address + 1) & 0xffff) << 8);
    }

    public void writeByte(final int address, final int value,
                          final long wallClockTime)
    {
      memWrite(address & 0xffff, value);
    }

    public void writeShort(final int address, final int value,
                           final long wallClockTime)
    {
      memWrite(address & 0xffff, value);
      memWrite((address + 1) & 0xffff, value >> 8);
    }

    public void resync(final long wallClockTime) {}
  }

  // ------------------------------------------------------------- ports

  /** Bus view for the CPU's I/O address space. */
  public class PortView implements MemoryBus.BusReader, MemoryBus.BusWriter
  {
    public int readByte(final int address, final long wallClockTime)
    {
      switch (address & 0xff) {
      case PORT_SPI_DATA:
        return spiRx;
      default:
        // Other loader ports: no read-back behaviour is known; behave
        // like an undriven bus.
        return MemoryBus.BusWriter.BYTE_UNDEFINED;
      }
    }

    public int readShort(final int address, final long wallClockTime)
    {
      return readByte(address, wallClockTime) |
        (readByte(address + 1, wallClockTime) << 8);
    }

    public void writeByte(final int address, final int value,
                          final long wallClockTime)
    {
      final int v = value & 0xff;
      switch (address & 0xff) {
      case PORT_RAMMAP:
        ramMap = v;
        if (trace) System.out.printf("[sd] OUT 58 (RAM map) = %02X%n", v);
        break;
      case PORT_SPI_CFG:
        spiCfg = v;
        if (card != null) {
          card.select((v & 0x02) != 0);
        }
        if (trace) System.out.printf("[sd] OUT 56 (SPI cfg) = %02X%n", v);
        break;
      case PORT_SPI_DATA:
        spiRx = card != null ? card.transfer(v) : 0xff;
        break;
      case PORT_BANK:
        bank = v;
        if (trace) System.out.printf("[sd] OUT 55 (memory control) = %02X%n", v);
        break;
      case PORT_JOY_SELECT:
        joySelect = v;
        if (trace) System.out.printf("[sd] OUT 74 (pad select) = %02X%n", v);
        break;
      default:
        break;
      }
    }

    public void writeShort(final int address, final int value,
                           final long wallClockTime)
    {
      writeByte(address, value & 0xff, wallClockTime);
      writeByte(address + 1, (value >> 8) & 0xff, wallClockTime);
    }

    public void resync(final long wallClockTime) {}
  }
}
