package emulator.vz200;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import emulator.z80.MemoryBus;

/**
 * Register-level model of the RC39 Backplane Clone's memory system, as
 * described by RC39's memory_map.md.  Only active with --rc39; without it
 * the emulator is a plain VZ200 (or a VZ200 + SD loader), exactly as before.
 *
 * CPU view:
 *
 *   0000-3FFF  ROM                 (the BASIC ROM; handled by the stock ROM object)
 *   4000-67FF  10K window          CFG10K=1: SRAM, CFG10K=0: ROM
 *   6800-6FFF  MMIO                (stock IO class; never banked)
 *   7000-77FF  VRAM                (stock Video class; never banked)
 *   7800-BFFF  fixed RAM           SRAM, identity-mapped
 *   C000-FFFF  bank window         one of 8 x 16K SRAM banks
 *
 * The 128K SRAM is addressed by BA16..BA14 : A13..A0.  The fixed regions are
 * the identity mapping of the CPU address into the low 64K of the SRAM; only
 * the bank window substitutes the bank number for A15..A14.  That single
 * rule reproduces every overlap listed in memory_map.md: bank 1's bottom 10K
 * is the 10K window, bank 1's top 2K (F800-FFFF in the window) is fixed RAM
 * 7800-7FFF, bank 2 is fixed RAM 8000-BFFF, bank 3 is physically C000-FFFF.
 *
 * I/O port 0x70 (minimally decoded: any port whose low byte is 0x70-0x7F):
 *   bits 0-2  bank number for the C000-FFFF window
 *   bit 3     CFG10K: 1 = RAM, 0 = ROM in the 4000-67FF window
 * Power-on value is 0 (bank 0, ROM in the 10K window).
 *
 * Assumptions that memory_map.md does not settle (could NOT be confirmed
 * without real hardware):
 *   - Writes to 4000-67FF always reach the SRAM, even while CFG10K=0 selects
 *     the ROM for reads.  (This is how the "copy the 10K ROM into RAM" trick
 *     reads naturally; same assumption the SD loader model makes.)
 *   - The bank register cannot be read back; IN from 0x70-0x7F floats high.
 *   - SRAM power-on contents are zero (real SRAM is undefined).
 *   - The 10K ROM image is not in the memory map document; supply it with
 *     --rc39-rom, otherwise the window reads $FF while CFG10K=0.
 */
public class RC39
{
  public static final int PORT_BANK_BASE = 0x70;
  public static final int PORT_BANK_MASK = 0xf0;

  public static final int SRAM_SIZE = 0x20000;
  public static final int WINDOW_START = 0x4000;
  public static final int WINDOW_END = 0x67ff;
  public static final int WINDOW_SIZE = WINDOW_END - WINDOW_START + 1;
  public static final int FIXED_START = 0x7800;
  public static final int FIXED_END = 0xbfff;
  public static final int BANK_START = 0xc000;

  private final int[] sram = new int[SRAM_SIZE];
  private final int[] rom10k = new int[WINDOW_SIZE];
  private boolean haveRom10k;
  private int bankReg;
  private final boolean trace;

  public RC39(final boolean trace)
  {
    this.trace = trace;
    java.util.Arrays.fill(rom10k, 0xff);
  }

  public int getBankRegister() { return bankReg; }
  public int getBank() { return bankReg & 0x07; }
  public boolean isRamInWindow() { return (bankReg & 0x08) != 0; }

  /** Direct SRAM access by physical address (for tests / debugging). */
  public int physRead(final int phys) { return sram[phys & (SRAM_SIZE - 1)]; }
  public void physWrite(final int phys, final int v)
  { sram[phys & (SRAM_SIZE - 1)] = v & 0xff; }

  /**
   * Loads the image that appears at 4000-67FF while CFG10K=0.  Accepts a raw
   * binary (up to 10K) or a .VZ file (24 byte header is stripped).
   */
  public void loadRom10k(final File f) throws IOException
  {
    final byte[] d = Files.readAllBytes(f.toPath());
    int off = 0;
    if (d.length > 24 && d[0] == 'V' && d[1] == 'Z' && d[2] == 'F') {
      off = 24;
      final int start = (d[22] & 0xff) | ((d[23] & 0xff) << 8);
      if (start != WINDOW_START) {
        System.out.printf("[rc39] WARNING: %s has load address %04X, " +
                          "expected 4000; it is mapped at 4000 anyway%n",
                          f, start);
      }
    }
    final int n = d.length - off;
    if (n > WINDOW_SIZE) {
      throw new IOException(f + ": " + n + " bytes does not fit the 10K " +
                            "window (" + WINDOW_SIZE + " bytes)");
    }
    java.util.Arrays.fill(rom10k, 0xff);
    for (int i = 0; i < n; i++) {
      rom10k[i] = d[off + i] & 0xff;
    }
    haveRom10k = true;
    System.out.printf("[rc39] 10K window ROM: %s (%d bytes%s)%n", f, n,
                      off != 0 ? ", .VZ header stripped" : "");
  }

  public boolean hasRom10k() { return haveRom10k; }

  // ------------------------------------------------------------ memory

  private int memRead(final int a)
  {
    if (a >= WINDOW_START && a <= WINDOW_END) {
      return isRamInWindow() ? sram[a] : rom10k[a - WINDOW_START];
    }
    if (a >= FIXED_START && a <= FIXED_END) {
      return sram[a];
    }
    if (a >= BANK_START) {
      return sram[(getBank() << 14) | (a & 0x3fff)];
    }
    return MemoryBus.BusWriter.BYTE_UNDEFINED;   // ROM, MMIO, VRAM: not ours
  }

  private void memWrite(final int a, final int v)
  {
    if (a >= WINDOW_START && a <= WINDOW_END) {
      sram[a] = v & 0xff;
    } else if (a >= FIXED_START && a <= FIXED_END) {
      sram[a] = v & 0xff;
    } else if (a >= BANK_START) {
      sram[(getBank() << 14) | (a & 0x3fff)] = v & 0xff;
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
    private boolean selected(final int address)
    {
      return ((address & 0xff) & PORT_BANK_MASK) == PORT_BANK_BASE;
    }

    public int readByte(final int address, final long wallClockTime)
    {
      return MemoryBus.BusWriter.BYTE_UNDEFINED;
    }

    public int readShort(final int address, final long wallClockTime)
    {
      return 0xffff;
    }

    public void writeByte(final int address, final int value,
                          final long wallClockTime)
    {
      if (selected(address)) {
        bankReg = value & 0x0f;
        if (trace) {
          System.out.printf("[rc39] OUT %02X bank=%d 10K=%s%n", address & 0xff,
                            getBank(), isRamInWindow() ? "RAM" : "ROM");
        }
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
