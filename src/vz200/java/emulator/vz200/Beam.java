package emulator.vz200;

/**
 * Beam-synchronous model of the VZ200/VZ300's MC6847 video generator.
 *
 * The VZ clocks the 6847 from the same crystal as the Z80, so one CPU
 * T-state is one 6847 clock and a scanline is exactly 228 T-states.  All
 * video timing here is derived from the CPU's T-state counter (never from
 * nanoseconds: the CPU model rounds the clock period to whole ns, which
 * would drift by about 90 T-states per frame).
 *
 * What is modelled:
 *  - the frame: LINES lines of 228 clocks (312 = 50 Hz style, 262 = 60 Hz)
 *  - FS (6847 pin 37, which is wired to the Z80 /INT and to bit 7 of the
 *    $6800 read): low from the end of the active display until the end of
 *    vertical retrace
 *  - the latch bits that drive the 6847 (AG = bit 3, CSS = bit 4), applied
 *    with column (4 clock = 8 pixel) granularity, so a mode change made in
 *    the middle of a scanline shows up in the middle of that scanline
 *  - video RAM sampled by the beam: a write that lands after the beam has
 *    passed a column does not change that column until the next frame
 *
 * What is NOT known from documentation and therefore exposed as constants
 * (override with -Dvz.video.NAME=value): the exact clock at which FS
 * changes inside a line, the clock of the first visible pixel, and the
 * T-state within an instruction at which a latch write / FS read takes
 * effect.  Defaults follow the structure used by MAME's mc6847.cpp
 * (212, 25) with the access point at the end of the instruction.
 */
public final class Beam
{
  public static final int CLOCKS_PER_LINE = 228;
  public static final int WIDTH = 320;
  public static final int HEIGHT = 256;

  private static final int LINES_ACTIVE = 192;
  private static final int LINES_BOTTOM_BORDER = 26;
  private static final int LINES_RETRACE = 6;
  private static final int LINES_PAD_PAL = 25;
  private static final int LINES_TOP_BORDER = 25;


  // ---- colours -------------------------------------------------------------
  // Sampled from captures of a real machine (analog, so approximate).
  private static final int BLACK = 0xff000000;
  private static final int GREEN = 0xff07fe00;
  private static final int YELLOW = 0xfffcfc0c;
  private static final int BLUE = 0xff3c0cfa;
  private static final int RED = 0xffcc0c3c;
  private static final int BUFF = 0xffffffff;
  private static final int CYAN = 0xff08e19b;
  private static final int MAGENTA = 0xfffc24fc;
  private static final int ORANGE = 0xfffc840c;
  /** semigraphics-4 colours (data bits 6-4) and CG2 sets (CSS 0 / CSS 1) */
  private static final int[] SG4 = { GREEN, YELLOW, BLUE, RED, BUFF, CYAN, MAGENTA, ORANGE };
  private static final int[][] CG2 = {
    { GREEN, YELLOW, BLUE, RED },
    { BUFF, CYAN, MAGENTA, ORANGE } };
  /** alphanumeric: { dark (background), bright (foreground) } for CSS 0 / 1 */
  private static final int[][] ALPHA = {
    { 0xff013800, 0xff3ae439 },
    { 0xff6b2700, 0xffffb700 } };

  /** CG2 colour (for tests). */
  public static int cg2(final boolean css, final int index)
  {
    return CG2[css ? 1 : 0][index];
  }

  public interface FrameListener
  {
    void frameDone();
  }

  private static int intProp(final String name, final int def)
  {
    final String v = System.getProperty("vz.video." + name);
    return v == null ? def : Integer.parseInt(v);
  }

  // ---- configuration -----------------------------------------------------
  public final int lines;          // lines per frame
  public final int firstActive;    // frame line of the first active line
  public final int fsFallLine;     // FS goes low in this line ...
  public final int fsRiseLine;     // ... and high again in this line
  public final int fsEdgeClock;    // clock inside the line at which FS changes
  public final int activeClock;    // clock inside the line of the first active column
  public final int latchOffset;    // T-states added to a latch write's effective time
  public final int readOffset;     // T-states added to an FS read's effective time
  private final int visTop;        // first frame line shown in the 256-line window

  // ---- state ----------------------------------------------------------------
  private final int[] vram;        // 6847 video RAM (index = address - base)
  private final int[] charset;     // 12 lines per character
  private final int[] pixels = new int[WIDTH * HEIGHT];
  private boolean ag;              // latch bit 3
  private boolean css;             // latch bit 4
  private long curLine;            // absolute line counter of the unit being drawn
  private int curU;                // clock of the unit being drawn (-16 .. 143, 0 = first active clock)
  private long unitEnd;            // absolute clock at which that unit is complete
  private FrameListener listener;
  private long frames;

  // The 6847's own address generation.  It has a free-running line counter
  // (0..11) and a row address that advances by 32.  The row ends after 12
  // lines in alphanumeric / semigraphics modes and after 3 lines in the
  // 128x64 graphics mode the VZ uses.  Both are reset at the top of the
  // active display; if AG changes mid-frame the counters simply carry on
  // with the other mode's rule, so the video RAM address a line fetches
  // from depends on the history of the frame, not on the line number alone.
  private int lineN;               // line counter within the row period
  private final boolean agClearsLineCounter = !"false".equals(System.getProperty("vz.video.agClears"));
  private int rowBase;             // video RAM offset of the current row

  public Beam(final int[] vram, final int[] charset)
  {
    this.vram = vram;
    this.charset = charset;
    lines = intProp("lines", 312);
    final boolean pal = lines > 288;
    firstActive = LINES_TOP_BORDER + (pal ? LINES_PAD_PAL : 0);
    fsFallLine = firstActive + LINES_ACTIVE - 1;
    final int defaultRise = firstActive + LINES_ACTIVE + LINES_BOTTOM_BORDER +
      LINES_RETRACE - 1 + (pal ? LINES_PAD_PAL : 0);
    // -Dvz.video.fsLowLines=N: number of lines FS stays low (RASTEST prints
    // the value measured on real hardware as "FIELD SYNC LOW LINES")
    final int low = intProp("fsLowLines", defaultRise - fsFallLine);
    fsRiseLine = fsFallLine + low;
    fsEdgeClock = intProp("fsEdge", 212);
    activeClock = intProp("activeClock", 25);
    latchOffset = intProp("latchOffset", 0);
    readOffset = intProp("readOffset", 0);
    visTop = firstActive - 32;
    curLine = visTop;
    curU = -16;
    unitEnd = unitEnd(curLine, curU);
    java.util.Arrays.fill(pixels, 0xff000000);
  }

  public void setFrameListener(final FrameListener l) { listener = l; }
  public int[] getPixels() { return pixels; }
  public long getFrames() { return frames; }
  public boolean isGraphics() { return ag; }
  public boolean isCss() { return css; }

  private long unitEnd(final long line, final int u)
  {
    return line * CLOCKS_PER_LINE + activeClock + (u + 1);
  }

  // ---- FS ---------------------------------------------------------------------

  /** Level of FS (and so of bit 7 of the $6800 read) at the given clock. */
  public boolean fs(final long clock)
  {
    final long frameLen = (long)lines * CLOCKS_PER_LINE;
    final long fc = clock % frameLen;
    final long fall = (long)fsFallLine * CLOCKS_PER_LINE + fsEdgeClock;
    final long rise = (long)fsRiseLine * CLOCKS_PER_LINE + fsEdgeClock;
    return !(fc >= fall && fc < rise);
  }

  // ---- latch and video RAM ------------------------------------------------------

  /** A write to the $6800 latch took effect at the given clock. */
  public void latchWrite(final long clock, final int value)
  {
    advanceTo(clock + latchOffset);
    final boolean newAg = (value & 0x08) != 0;
    // Hypothesis (see BEAM_VIDEO.md): a change of A/G clears the line
    // counter, so a program that toggles AG on every line never lets the
    // row end and the 6847 keeps fetching the same row.
    if (newAg != ag && agClearsLineCounter) lineN = 0;
    ag = newAg;
    css = (value & 0x10) != 0;
  }

  /** Must be called (with the effective clock) before video RAM changes. */
  public void beforeVramWrite(final long clock)
  {
    advanceTo(clock);
  }

  // ---- beam ----------------------------------------------------------------------

  /** Draws every unit (8 pixel column) that is completely behind the beam. */
  public void advanceTo(final long clock)
  {
    while (unitEnd <= clock) {
      drawUnit(curLine, curU);
      curU++;
      if (curU == 144) {
        curU = -16;
        curLine++;
        int f = (int)(curLine % lines);
        if (f == visTop + HEIGHT) {
          // beam left the visible window: frame complete
          frames++;
          if (listener != null) listener.frameDone();
          curLine += lines - f + visTop;     // skip to next frame's window
        }
      }
      unitEnd = unitEnd(curLine, curU);
    }
  }

  /**
   * MC6847 border: black in alphanumeric mode; in graphics mode green
   * (CSS = 0) or buff (CSS = 1) - as seen on the real machine.
   */
  private int borderRGB()
  {
    if (!ag) return BLACK;
    return css ? BUFF : GREEN;
  }

  /**
   * Draws one clock (2 pixels) of the beam.  AG and CSS are applied per
   * clock, so a latch write in the middle of a character cell changes the
   * pixels that follow it, not the whole cell.
   */
  /** Called when the beam starts a line: runs the row/line counters. */
  private void lineStart(final int y)
  {
    if (y == 0) {
      lineN = 0;
      rowBase = 0;
    } else if (y > 0 && y < LINES_ACTIVE) {
      // end of the previous line: does the row end, in the mode now set?
      final boolean rowEnd = ag ? (lineN % 3 == 2) : (lineN == 11);
      if (rowEnd) rowBase = (rowBase + 32) & 0x7ff;
      lineN = (lineN + 1) % 12;
    }
  }

  private void drawUnit(final long line, final int clk)
  {
    final int f = (int)(line % lines);
    final int row = f - visTop;
    if (clk == -16) lineStart(f - firstActive);
    final int base = row * WIDTH + (clk + 16) * 2;
    final int y = f - firstActive;
    final int u = clk >> 2;            // character cell (floor)
    final int k = clk & 3;             // clock inside the cell
    if (y < 0 || y >= LINES_ACTIVE || u < 0 || u >= 32) {
      final int c = borderRGB();
      pixels[base] = c;
      pixels[base + 1] = c;
      return;
    }
    if (!ag) {
      final int d = vram[(rowBase + u) & 0x7ff] & 0xff;
      // The character ROM already holds inverse-video glyphs for the codes
      // that need them (and the 2x2 block patterns for codes >= $80), so a
      // set bit is "foreground" in every case.
      final int bits = charset[d * 12 + lineN];
      final int fg, bg;
      if ((d & 0x80) != 0) {
        // semigraphics 4: colour from bits 6-4, black background
        fg = SG4[(d >> 4) & 7];
        bg = BLACK;
      } else {
        final int[] a = ALPHA[css ? 1 : 0];
        fg = a[1];
        bg = a[0];
      }
      pixels[base] = ((bits >> (2 * k)) & 1) != 0 ? fg : bg;
      pixels[base + 1] = ((bits >> (2 * k + 1)) & 1) != 0 ? fg : bg;
    } else {
      final int b = vram[(rowBase + u) & 0x7ff] & 0xff;
      final int c = CG2[css ? 1 : 0][(b >> (6 - 2 * k)) & 3];
      pixels[base] = c;
      pixels[base + 1] = c;
    }
  }
}
