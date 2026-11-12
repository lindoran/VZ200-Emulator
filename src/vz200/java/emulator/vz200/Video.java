package emulator.vz200;

import java.io.IOException;
import javax.swing.JPanel;
import javax.swing.JFrame;

import emulator.z80.CPU;
import emulator.z80.MemoryBus;
import emulator.z80.RAMMemory;
import emulator.z80.Util;
import emulator.z80.Z80;

public class Video extends JFrame
  implements MemoryBus.BusReader, MemoryBus.BusWriter
{
  private static final long serialVersionUID = 8771293905230414438L;
  private static final int DEFAULT_BASE_ADDRESS = 0x7000;

  // horizontal sync
  private static final long HS_CYCLE = 64000; // [ns]
  private static final long HS_CYCLE_LOW = 4800; // [ns]

  // field sync
  // FS_CYCLE must be a multiple of HS_CYCLE to keep FS and HS sync'd
  private static final long FS_CYCLE = 40960000; // [ns]
  private static final long FS_CYCLE_LOW = 2480000; // [ns]

  private final VideoPanel panel;
  private final RAMMemory videoRAM;
  private final int baseAddress;
  private long wallClockTime;
  private long prevHsCycleLowStart;
  private long prevFsCycleLowStart;
  private Beam beam;   // null in legacy mode (-Dvz.video.legacy=true)
  private Z80 z80;     // source of the T-state counter for accesses

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
    final int previousValue = videoRAM.readByte(address, wallClockTime);
    if (beam != null) beam.beforeVramWrite(accessClock(0));
    videoRAM.writeByte(address, value, wallClockTime);
    if (value != previousValue) {
      panel.invalidate(address);
    }
  }

  @Override
  public void writeShort(final int address, final int value,
                         final long wallClockTime)
  {
    final int previousValue = videoRAM.readShort(address, wallClockTime);
    if (beam != null) {
      // the low byte is written one M-cycle (3 T) before the instruction ends
      beam.beforeVramWrite(accessClock(-3));
    }
    videoRAM.writeShort(address, value, wallClockTime);
    if (value != previousValue) {
      panel.invalidate(address);
      panel.invalidate(address + 1);
    }
  }

  @Override
  public void resync(final long wallClockTime) {}


  /**
   * Connects the CPU whose T-state counter drives the beam.  Without it
   * (or in legacy mode) the old free-running sync model is used.
   */
  public void attachCpu(final Z80 z80)
  {
    this.z80 = z80;
  }

  public boolean isBeamMode()
  {
    return beam != null;
  }

  /**
   * T-state clock at which the memory/port access of the instruction that
   * is executing right now takes effect: the end of the instruction (the
   * access is its last machine cycle) plus {@code adjust}.
   */
  private long accessClock(final int adjust)
  {
    return z80.getWallClockCycles() + z80.getCurrentInstructionPeriods() +
      adjust;
  }

  /** A write to the $6800 latch (adjust: see accessClock). */
  public void latchWrite(final int value, final int adjust)
  {
    beam.latchWrite(accessClock(adjust), value);
  }

  /** FS (= bit 7 of the $6800 read) as the executing instruction sees it. */
  public boolean fsRead()
  {
    return beam.fs(accessClock(0) + beam.readOffset);
  }

  /** Called after every instruction: draws what the beam has passed. */
  public void beamTick(final long wallClockCycles)
  {
    beam.advanceTo(wallClockCycles);
  }

  /** Level of the /INT line (FS inverted) after the last instruction. */
  public boolean intLine(final long wallClockCycles)
  {
    return !beam.fs(wallClockCycles);
  }

  public boolean hs()
  {
    return wallClockTime - prevHsCycleLowStart >= HS_CYCLE_LOW;
  }

  public boolean fs()
  {
    return wallClockTime - prevFsCycleLowStart >= FS_CYCLE_LOW;
  }

  public boolean updateWallClock(final long wallClockCycles,
                                 final long wallClockTime)
  {
    this.wallClockTime = wallClockTime;
    if (wallClockTime - prevHsCycleLowStart >= HS_CYCLE) {
      prevHsCycleLowStart += HS_CYCLE;
    }
    final boolean doIrq;
    if (wallClockTime - prevFsCycleLowStart >= FS_CYCLE) {
      prevFsCycleLowStart += FS_CYCLE;
      doIrq = true;
    } else {
      doIrq = false;
    }
    return doIrq;
  }

  public void setColorMode(final boolean colorMode)
  {
    panel.setColorMode(colorMode);
  }

  public void setDisplayMode(final boolean displayMode)
  {
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
    prevHsCycleLowStart = 0;
    prevFsCycleLowStart = 0;
    setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    addWindowListener(ApplicationExitListener.defaultInstance);
    setJMenuBar(new VideoMenu(this));
    panel = new VideoPanel(baseAddress);
    getContentPane().add(panel);
    videoRAM = panel.getVideoRAM();
    beam = panel.getBeam();
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
