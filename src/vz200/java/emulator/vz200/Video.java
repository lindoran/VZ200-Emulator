package emulator.vz200;

import java.io.IOException;
import javax.swing.JPanel;
import javax.swing.JFrame;

import emulator.z80.CPU;
import emulator.z80.MemoryBus;
import emulator.z80.RAMMemory;
import emulator.z80.Util;

public class Video extends JFrame
  implements MemoryBus.BusReader, MemoryBus.BusWriter
{
  private static final long serialVersionUID = 8771293905230414438L;
  private static final int DEFAULT_BASE_ADDRESS = 0x7000;

  // Preserve the emulator's established scanline cadence.
  private static final long HS_CYCLE = 64000; // [ns]
  static final int MC6847_CLOCKS_PER_LINE = 228;
  private static final int MC6847_HSYNC_OFF_CLOCK = 212;
  // MAME starts/stops field sync 212 clocks after the line edge.
  private static final int MC6847_FSYNC_EDGE_CLOCK = 212;
  private static final long MC6847_SYNC_EDGE_TIME =
    clocksToLineTime(MC6847_FSYNC_EDGE_CLOCK);
  private static final long MC6847_HSYNC_OFF_TIME =
    clocksToLineTime(MC6847_HSYNC_OFF_CLOCK);
  // MAME's PAL MC6847 field and visible-area timing.
  private static final int FIELD_LINES = 312;
  private static final int ACTIVE_LINE_START = 50;
  private static final int ACTIVE_LINE_COUNT = 192;
  private static final int FS_FALLING_LINE =
    ACTIVE_LINE_START + ACTIVE_LINE_COUNT - 1;
  private static final int FS_RISING_LINE = FIELD_LINES - 1;
  private static final long FS_FALLING_TIME =
    FS_FALLING_LINE * HS_CYCLE + MC6847_SYNC_EDGE_TIME;
  private static final long FS_RISING_TIME =
    FS_RISING_LINE * HS_CYCLE + MC6847_SYNC_EDGE_TIME;
  /*
   * MAME records 32 samples at four-clock intervals, starting 25 clocks after
   * HS rises. Scale its positions to this emulator's established line period.
   */
  private static final int MC6847_FIRST_SAMPLE_CLOCK = 25;
  private static final int MC6847_CLOCKS_PER_SAMPLE = 4;
  private static final long FS_CYCLE = FIELD_LINES * HS_CYCLE; // [ns]

  private final VideoPanel panel;
  private final RAMMemory videoRAM;
  private final int baseAddress;
  private long wallClockTime;
  private long prevHsCycleStart;
  private long prevFieldStartTime;
  private int rasterLine;
  private int rasterX;
  private boolean displayMode;
  private boolean colorMode;

  @Override
  public int readByte(final int address, final long wallClockTime)
  {
    return videoRAM.readByte(address, wallClockTime);
  }

  @Override
  public int readShort(final int address, final long wallClockTime)
  {
    return videoRAM.readShort(address, wallClockTime);
  }

  @Override
  public void writeByte(final int address, final int value,
                        final long wallClockTime)
  {
    videoRAM.writeByte(address, value, wallClockTime);
    // The timed raster compositor samples VRAM itself.  Repainting here
    // would only show the last completed field and can enqueue thousands of
    // redundant Swing events during a demo.
  }

  @Override
  public void writeShort(final int address, final int value,
                         final long wallClockTime)
  {
    videoRAM.writeShort(address, value, wallClockTime);
  }

  @Override
  public void resync(final long wallClockTime) {}

  public boolean hs()
  {
    final long lineTime = Math.floorMod(wallClockTime - prevHsCycleStart,
                                        HS_CYCLE);
    return lineTime < MC6847_HSYNC_OFF_TIME;
  }

  public boolean fs()
  {
    final long fieldTime = wallClockTime - prevFieldStartTime;
    return fieldTime < FS_FALLING_TIME || fieldTime >= FS_RISING_TIME;
  }

  static boolean fieldSyncAt(final long elapsed)
  {
    final long fieldTime = Math.floorMod(elapsed, FS_CYCLE);
    return fieldTime < FS_FALLING_TIME || fieldTime >= FS_RISING_TIME;
  }

  private static long clocksToLineTime(final int clocks)
  {
    final long lineTime = (long)clocks * HS_CYCLE;
    return (lineTime + MC6847_CLOCKS_PER_LINE - 1) /
      MC6847_CLOCKS_PER_LINE;
  }

  public boolean updateWallClock(final long wallClockCycles,
                                 final long wallClockTime)
  {
    final long previousWallClockTime = this.wallClockTime;
    this.wallClockTime = wallClockTime;
    /*
     * A paint event is far too late to reconstruct a raster effect.  Finish
     * each line in emulated time instead, using the latch state that applied
     * during that line.  The active 192 MC6847 lines map one-to-one to the
     * panel's active 192 rows, which is essential for raster bars.  Only the
     * non-visible PAL padding is compressed into the decorative frame.
     */
    while (wallClockTime - prevHsCycleStart >= HS_CYCLE) {
      renderUntil(prevHsCycleStart + HS_CYCLE);
      rasterLine++;
      rasterX = 32;
      prevHsCycleStart += HS_CYCLE;
    }
    final boolean doIrq =
      previousWallClockTime - prevFieldStartTime < FS_FALLING_TIME &&
      wallClockTime - prevFieldStartTime >= FS_FALLING_TIME;
    if (wallClockTime - prevFieldStartTime >= FS_CYCLE) {
      prevFieldStartTime += FS_CYCLE;
      rasterLine = 0;
      rasterX = 32;
      panel.presentRaster();
      panel.beginRasterField(colorMode);
    }
    return doIrq;
  }

  private int panelY()
  {
    if (rasterLine < ACTIVE_LINE_START)
      return rasterLine * 32 / ACTIVE_LINE_START;
    if (rasterLine < ACTIVE_LINE_START + ACTIVE_LINE_COUNT)
      return 32 + rasterLine - ACTIVE_LINE_START;
    return 224 + (rasterLine - ACTIVE_LINE_START - ACTIVE_LINE_COUNT) *
      32 / (FIELD_LINES - ACTIVE_LINE_START - ACTIVE_LINE_COUNT);
  }

  /** Render the current line up to an emulated-time position. */
  private void renderUntil(final long time)
  {
    final long elapsed = Math.max(0L, Math.min(HS_CYCLE,
                                                time - prevHsCycleStart));
    final long clocksSinceHsRise =
      elapsed * MC6847_CLOCKS_PER_LINE / HS_CYCLE;
    final int sample = clocksSinceHsRise <= MC6847_FIRST_SAMPLE_CLOCK ? 0 :
      Math.min(32, (int)((clocksSinceHsRise - MC6847_FIRST_SAMPLE_CLOCK) /
                        MC6847_CLOCKS_PER_SAMPLE));
    final int x = 32 + sample * 8;
    if (x > rasterX) {
      panel.renderRasterSpan(panelY(), rasterX, x, displayMode, colorMode);
      rasterX = x;
    }
  }

  public void setColorMode(final boolean colorMode, final long wallClockTime)
  {
    renderUntil(wallClockTime);
    this.colorMode = colorMode;
    panel.setColorMode(colorMode);
  }

  public void setDisplayMode(final boolean displayMode, final long wallClockTime)
  {
    renderUntil(wallClockTime);
    this.displayMode = displayMode;
    panel.setDisplayMode(displayMode);
  }

  public void setZoomFactor(final int zoomFactor)
  {
    panel.setZoomFactor(zoomFactor);
    pack();
  }

  public Video() throws IOException
  {
    this(DEFAULT_BASE_ADDRESS);
  }

  public Video(final int baseAddress) throws IOException
  {
    super("VZ200 Video Screen");
    this.baseAddress = baseAddress;
    wallClockTime = 0;
    prevHsCycleStart = 0;
    prevFieldStartTime = 0;
    rasterLine = 0;
    rasterX = 32;
    colorMode = VideoPanel.COLOR_MODE_GREEN;
    displayMode = VideoPanel.DISPLAY_MODE_TEXT;
    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    addWindowListener(ApplicationExitListener.defaultInstance);
    setJMenuBar(new VideoMenu(this));
    panel = new VideoPanel(baseAddress);
    panel.beginRasterField(colorMode);
    getContentPane().add(panel);
    videoRAM = panel.getVideoRAM();
    pack();
    WindowMemory.restoreAndTrack(this, "video");
    setVisible(true);
  }

  /**
   * Gives the screen window the keyboard focus, so typing goes to the
   * emulated machine right after start-up.  The other windows (settings,
   * on-screen keyboard) are shown while the emulator is being set up and
   * would otherwise keep the focus.
   */
  public void focusScreen()
  {
    javax.swing.SwingUtilities.invokeLater(() -> {
        toFront();
        requestFocus();
      });
  }

  public String toString()
  {
    return String.format("Video[baseAddress=%04xh, videoRAM=%s]",
                         Util.hexShortStr(baseAddress), videoRAM);
  }
}

/*
  Local Variables:
    coding:utf-8
    mode:Java
    End:
*/
