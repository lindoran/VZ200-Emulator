package emulator.vz200;

import java.io.File;
import java.nio.file.Files;

import emulator.z80.MemoryBus;

/**
 * Headless tests for the RC39 memory model.
 *
 *   java -cp build:jar/z80.jar emulator.vz200.RC39Test BASIC_ROM.bin WORKDIR
 */
public class RC39Test
{
  private static int failures;

  private static void check(final boolean ok, final String what)
  {
    System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    if (!ok) failures++;
  }

  private static void bank(final MemoryBus ports, final int v)
  { ports.writeByte(0x0070, v, 0); }

  public static void main(final String[] argv) throws Exception
  {
    final File basic = new File(argv[0]);
    final File work = new File(argv[1]);
    work.mkdirs();

    System.out.println("== memory map (bus level)");
    final File rom10k = new File(work, "rom10k.bin");
    final byte[] img = new byte[0x2800];
    for (int i = 0; i < img.length; i++) img[i] = (byte)(0xa5 ^ i);
    Files.write(rom10k.toPath(), img);
    final RC39 rc = new RC39(false);
    rc.loadRom10k(rom10k);
    final HeadlessVZ vz = new HeadlessVZ(basic, rc);
    final MemoryBus m = vz.mem;
    final MemoryBus p = vz.ports;

    check(m.readByte(0x0000, 0) == (Files.readAllBytes(basic.toPath())[0] & 0xff),
          "$0000 is the BASIC ROM");
    m.writeByte(0x0000, 0x55, 0);
    check(m.readByte(0x0000, 0) != 0x55 || (Files.readAllBytes(basic.toPath())[0] & 0xff) == 0x55,
          "$0000-$3FFF is ROM: writes ignored");

    check(rc.getBankRegister() == 0 && !rc.isRamInWindow(),
          "power-on: bank 0, ROM in the 10K window");
    check(m.readByte(0x4000, 0) == 0xa5 && m.readByte(0x4003, 0) == (0xa5 ^ 3),
          "10K window reads the ROM image while bit 3 = 0");
    check(m.readByte(0x67ff, 0) == ((0xa5 ^ 0x27ff) & 0xff),
          "ROM image ends at $67FF");

    // bank window: each bank is its own 16K
    for (int b = 0; b < 8; b++) {
      bank(p, b);
      m.writeByte(0xc000, 0x10 + b, 0);
      m.writeByte(0xfffe, 0x20 + b, 0);
    }
    boolean ok = true;
    for (int b = 0; b < 8; b++) {
      bank(p, b);
      ok &= m.readByte(0xc000, 0) == 0x10 + b && m.readByte(0xfffe, 0) == 0x20 + b;
    }
    check(ok, "8 independent 16K banks in $C000-$FFFF");
    check(rc.physRead(0x14000) == 0x15 && rc.physRead(0x1fffe) == 0x27,
          "bank n = physical SRAM slice n*16K");

    // fixed RAM aliasing with banks 1/2/3 (identity mapping into the low 64K)
    rc.physWrite(0x7800, 0);
    m.writeByte(0x7800, 0xab, 0);
    bank(p, 1);
    check(m.readByte(0xf800, 0) == 0xab,
          "fixed RAM $7800 is the top 2K of bank 1 ($F800 in the window)");
    m.writeByte(0xf801, 0xcd, 0);
    check(m.readByte(0x7801, 0) == 0xcd, "write via bank 1 window shows in $7801");
    m.writeByte(0x8000, 0x77, 0);
    bank(p, 2);
    check(m.readByte(0xc000, 0) == 0x77, "fixed RAM $8000 is bottom of bank 2");
    m.writeByte(0xbfff, 0x99, 0);
    check(m.readByte(0xffff, 0) == 0x99, "fixed RAM $BFFF is top of bank 2");

    // 10K window: bit 3 swaps ROM for bank-1 RAM
    bank(p, 1);
    m.writeByte(0xc000, 0x3c, 0);               // bank 1 offset 0 = phys $4000
    check(m.readByte(0x4000, 0) == 0xa5,
          "bit 3 = 0: window still reads ROM although bank 1 RAM holds $3C");
    bank(p, 0x08 | 1);
    check(m.readByte(0x4000, 0) == 0x3c,
          "bit 3 = 1: window shows RAM = bank 1 bottom 10K");
    m.writeByte(0x67ff, 0x5e, 0);
    check(m.readByte(0xe7ff, 0) == 0x5e, "window RAM $67FF = bank 1 offset $27FF");
    bank(p, 0x08 | 5);
    check(m.readByte(0x4000, 0) == 0x3c,
          "10K window is bank 1 regardless of which bank is in the C000 window");

    // ROM -> RAM copy (the trick in memory_map.md)
    bank(p, 1);                                 // ROM in window, bank 1 at C000
    for (int i = 0; i < 16; i++) m.writeByte(0xc000 + i, m.readByte(0x4000 + i, 0), 0);
    bank(p, 0x08 | 1);
    ok = true;
    for (int i = 0; i < 16; i++) ok &= m.readByte(0x4000 + i, 0) == (0xa5 ^ i);
    check(ok, "ROM copied into bank 1 via the window, visible as RAM afterwards");

    // non-banked ranges
    bank(p, 0);
    m.writeByte(0x7000, 0x41, 0);
    check(m.readByte(0x7000, 0) == 0x41, "VRAM $7000 unaffected by banking");

    // port decode
    bank(p, 2);
    p.writeByte(0x0a7f, 0x03, 0);               // high byte ignored, 0x7F in 0x70-0x7F
    check(rc.getBank() == 3, "port decode: 0x70-0x7F, address high byte ignored");
    p.writeByte(0x0069, 0x06, 0);
    check(rc.getBank() == 3, "other ports do not touch the bank register");
    p.writeByte(0x0070, 0xff, 0);
    check(rc.getBankRegister() == 0x0f, "only the low nibble is stored");
    check(p.readByte(0x0070, 0) == 0xff, "bank register is not readable (floats high)");

    System.out.println("== real Z80 + BASIC ROM");
    final HeadlessVZ boot = new HeadlessVZ(basic, new RC39(false));
    boolean up = boot.runUntilScreenHas("READY", 400);
    check(up, "BASIC reaches READY with the RC39 map");
    boot.type("PRINT 42\n");
    boot.runFrames(120);
    check(boot.screen().replace("PRINT 42","").contains("42"), "BASIC runs: PRINT 42");

    // a machine-code program that switches banks, from fixed RAM
    final HeadlessVZ cpu = new HeadlessVZ(basic, new RC39(false));
    final int[] prog = {
      0x3e, 0x02, 0xd3, 0x70,          // LD A,2 : OUT (70h),A
      0x3e, 0xa1, 0x32, 0x00, 0xc0,    // LD A,A1 : LD (C000),A   -> bank 2
      0x3e, 0x05, 0xd3, 0x70,          // LD A,5 : OUT (70h),A
      0x3e, 0xb2, 0x32, 0x00, 0xc0,    // LD A,B2 : LD (C000),A   -> bank 5
      0x76                             // HALT
    };
    for (int i = 0; i < prog.length; i++) cpu.mem.writeByte(0x8000 + i, prog[i], 0);
    cpu.cpu.getProgramCounter().setValue(0x8000);
    final boolean reached = cpu.runUntilPC(0x8000 + prog.length - 1, 5);
    check(reached && cpu.rc39.physRead(0x08000) == 0xa1 &&
          cpu.rc39.physRead(0x14000) == 0xb2,
          "Z80 OUT (70h),A switches banks; stores land in the right SRAM slices");

    System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
