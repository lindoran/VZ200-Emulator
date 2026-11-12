package emulator.vz200;

import java.io.File;

/**
 * Self-checking tests of the beam model (no window needed):
 *
 *   java -cp build:test/build:jar/z80.jar emulator.vz200.BeamTimingTest [--quick]
 *
 *  1. FS as measured by Z80 code polling bit 7 of $6800 (what RASTEST does)
 *  2. /INT is held while FS is low and only then
 *  3. a latch write inside a scanline changes mode at the right 8 pixel column
 *  4. (unless --quick) VZMEGA's raster picture is locked to the display: the
 *     same pixels land in the same place frame after frame
 */
public class BeamTimingTest
{
  static int failures;

  static void check(final boolean ok, final String what)
  {
    System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    if (!ok) failures++;
  }

  static BeamVZ plain() throws Exception
  {
    return new BeamVZ(new File("BIOS/VZ200_20.bin"), null, null);
  }

  static void poke(final BeamVZ vz, final int addr, final int... bytes)
  {
    for (int i = 0; i < bytes.length; i++)
      vz.mem.writeByte(addr + i, bytes[i], 0);
  }

  static void jump(final BeamVZ vz, final int pc)
  {
    vz.cpu.getProgramCounter().setValue(pc);
  }

  static void fsMeasuredByCode() throws Exception
  {
    System.out.println("== FS as seen by Z80 code");
    final BeamVZ vz = plain();
    // di; ld hl,6800h; w1: bit 7,(hl); jr nz,w1; w2: bit 7,(hl); jr z,w2;
    // ld de,0; c1: inc de; bit 7,(hl); jr nz,c1; ld (8f00h),de;
    // ld de,0; c2: inc de; bit 7,(hl); jr z,c2; ld (8f02h),de; halt-loop
    poke(vz, 0x8000,
      0xf3, 0x21, 0x00, 0x68,
      0xcb, 0x7e, 0x20, 0xfc,            // w1
      0xcb, 0x7e, 0x28, 0xfc,            // w2
      0x11, 0x00, 0x00,
      0x13, 0xcb, 0x7e, 0x20, 0xfb,      // c1
      0xed, 0x53, 0x00, 0x8f,
      0x11, 0x00, 0x00,
      0x13, 0xcb, 0x7e, 0x28, 0xfb,      // c2
      0xed, 0x53, 0x02, 0x8f,
      0x18, 0xfe);
    jump(vz, 0x8000);
    vz.runFrames(3);
    final int high = vz.peek(0x8f00) | (vz.peek(0x8f01) << 8);
    final int low = vz.peek(0x8f02) | (vz.peek(0x8f03) << 8);
    // 30 T-states per loop pass
    final double highLines = high * 30.0 / 228, lowLines = low * 30.0 / 228;
    System.out.printf("  FS high %.1f lines, low %.1f lines%n", highLines, lowLines);
    check(Math.abs(highLines - (vz.beam.lines - 57)) < 1.0, "FS high for lines-57 lines");
    check(Math.abs(lowLines - 57) < 1.0, "FS low for 57 lines");
  }

  static void intHeldWhileFsLow() throws Exception
  {
    System.out.println("== /INT = FS");
    final BeamVZ vz = plain();
    // IM 2, vector table at 8e00 -> isr at 8f40.  The isr counts its entries
    // (8f80) and how many of them see FS already high (8f81): an entry taken
    // in the last few T-states of the low period may legitimately read FS
    // high, so allow one such entry per frame.
    poke(vz, 0x8e00, 0x40, 0x8f);
    poke(vz, 0x8f40,
      0xf5,                               // push af
      0x3a, 0x00, 0x68, 0x17,             // ld a,(6800h); rla   (carry = FS)
      0x30, 0x07,                         // jr nc,low
      0x3a, 0x81, 0x8f, 0x3c, 0x32, 0x81, 0x8f,   // inc (8f81h)
      0x3a, 0x80, 0x8f, 0x3c, 0x32, 0x80, 0x8f,   // low: inc (8f80h)
      0xf1, 0xfb, 0xed, 0x4d);            // pop af; ei; reti
    poke(vz, 0x8000, 0xf3, 0x3e, 0x8e, 0xed, 0x47, 0xed, 0x5e, 0xfb, 0x18, 0xfe);
    jump(vz, 0x8000);
    vz.runFrames(1);                      // one FS-low window; keeps the byte counter from wrapping
    final int n = vz.peek(0x8f80);
    final int high = vz.peek(0x8f81);
    System.out.printf("  %d interrupt entries in 1 frame, %d read FS high%n", n, high);
    check(n >= 100, "interrupts are taken repeatedly while FS is low (level /INT)");
    check(high <= 1, "no interrupts are taken while FS is high");
  }

  static void midLineSwitch()
  {
    System.out.println("== mode change inside a scanline");
    final int[] vram = new int[0x800];
    java.util.Arrays.fill(vram, 0xff);
    final int[] charset = new int[3072];
    final Beam b = new Beam(vram, charset);
    final int line = b.firstActive + 20;
    final long base = (long)line * Beam.CLOCKS_PER_LINE;
    final int[] writeAt = { 65, 66, 68, 69 };      // clocks into the line
    for (int i = 0; i < writeAt.length; i++) {
      final Beam bb = new Beam(vram, charset);
      final long frame = (long)bb.lines * Beam.CLOCKS_PER_LINE * (i + 1);
      bb.latchWrite(frame + (long)line * Beam.CLOCKS_PER_LINE + writeAt[i], 0x08);
      bb.advanceTo(frame + (long)(line + 1) * Beam.CLOCKS_PER_LINE);
      final int row = line - (bb.firstActive - 32);
      final int[] px = bb.getPixels();
      final int red = Beam.cg2(false, 3);
      int firstX = -1;
      for (int x = 0; x < Beam.WIDTH; x++)
        if (px[row * Beam.WIDTH + x] == red) { firstX = x; break; }
      // a write at clock w changes every clock from w on; clock c is at
      // x = (c - activeClock + 16) * 2
      final int expect = (writeAt[i] - bb.activeClock + 16) * 2;
      check(firstX == expect,
            "write at clock " + writeAt[i] + " -> graphics from pixel " + expect
            + " (got " + firstX + ")");
    }
  }

  /** Colour of the first pixel of active line y in the finished frame. */
  static int pixelAt(final Beam b, final int y)
  {
    return b.getPixels()[(y + 32) * Beam.WIDTH + (0 + 4) * 8];
  }

  static void rowCounters()
  {
    System.out.println("== 6847 row/line counters");
    // video RAM row r holds colour index r % 4 in its first pixel
    final int[] vram = new int[0x800];
    for (int r = 0; r < 64; r++) vram[r * 32] = (r % 4) << 6;
    final int[] charset = new int[3072];
    final int[] colours = new int[4];
    for (int i = 0; i < 4; i++) colours[i] = Beam.cg2(false, i);

    // (a) steady graphics mode: a new row every 3 lines
    Beam b = new Beam(vram, charset);
    long frame = (long)b.lines * Beam.CLOCKS_PER_LINE;
    b.latchWrite(frame + 100, 0x08);
    b.advanceTo(frame * 2);
    check(pixelAt(b, 0) == colours[0] && pixelAt(b, 3) == colours[1]
          && pixelAt(b, 30) == colours[10 % 4],
          "steady graphics: row advances every 3 lines");

    // (b) AG toggled on every line: the row never ends, row 0 is repeated
    b = new Beam(vram, charset);
    final long f0 = (long)b.lines * Beam.CLOCKS_PER_LINE;
    b.latchWrite(f0 + 100, 0x08);
    for (int line = 0; line < b.lines; line++) {
      final long base = f0 + (long)line * Beam.CLOCKS_PER_LINE;
      b.latchWrite(base + 2, 0x00);
      b.latchWrite(base + 5, 0x08);
    }
    b.advanceTo(f0 * 2);
    check(pixelAt(b, 20) == colours[0] && pixelAt(b, 100) == colours[0],
          "AG toggling every line holds the same row (the Kefren/VZMEGA trick)");
  }

  static int[] bbox(final int[] px)
  {
    int x0 = 1 << 20, x1 = -1, n = 0;
    for (int y = 40; y < 216; y++)
      for (int x = 40; x < 288; x++)   // x < 40: edge column that streaming can touch
        if ((px[y * Beam.WIDTH + x] & 0xffffff) != 0) {
          n++;
          if (x < x0) x0 = x;
          if (x > x1) x1 = x;
        }
    return new int[] { x0, x1, n };
  }

  static void vzmegaLocked() throws Exception
  {
    System.out.println("== VZMEGA raster picture is locked to the display");
    final BeamVZ vz = new BeamVZ(new File("BIOS/VZ200_20.bin"),
      new File("BIOS/VZDOS.VZ"), new FolderBlockDevice(new File("SD"), false, false));
    vz.runFrames(150);
    vz.type("LOAD\"VZMEGA\"\n");
    vz.runFrames(1000);
    final long w0 = vz.latchWrites;
    vz.runFrames(10);
    final long perFrame = (vz.latchWrites - w0) / 10;
    check(perFrame == 588, "588 latch writes per frame (294 raster lines x 2), got " + perFrame);
    int[] ref = null;
    boolean same = true;
    for (int i = 0; i < 6; i++) {
      vz.runFrames(1);
      final int[] bb = bbox(vz.beam.getPixels());
      if (ref == null) ref = bb;
      else if (bb[0] != ref[0] || bb[1] != ref[1]) same = false;
    }
    System.out.printf("  picture spans x=%d..%d, %d lit pixels%n", ref[0], ref[1], ref[2]);
    check(ref[2] > 2000, "a picture is drawn");
    check(same, "picture does not move horizontally between frames");
  }

  public static void main(final String[] a) throws Exception
  {
    fsMeasuredByCode();
    intHeldWhileFsLow();
    midLineSwitch();
    rowCounters();
    if (a.length == 0 || !a[0].equals("--quick")) vzmegaLocked();
    System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
