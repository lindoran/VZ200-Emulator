package emulator.vz200;

/**
 * Optional "control room" monitor: lets you listen to the cassette
 * signal (what the program writes to tape, and what the emulated tape
 * deck feeds in).  Off by default; it uses an output of its own so it
 * never competes with the speaker for the sound device unless enabled.
 */
public class CassetteCtrlRoomOut
{
  private static final double FULL_SCALE = 0.5;
  /**
   * Signal level for the two cassette output bits.  Same levels the
   * WAV recorder uses (CassetteFileOut).
   */
  private static final double[] LEVEL = {-1.0, -0.9375, 0.0, +1.0};

  private final AudioOutput output;
  private final SignalVoice voice;

  public CassetteCtrlRoomOut(final AudioOutput output)
  {
    this.output = output;
    voice = new SignalVoice(output, FULL_SCALE);
  }

  public SignalVoice getVoice() { return voice; }

  public void putEvent(final int dataValue, final long wallClockTime)
  {
    if (output.isEnabled()) {
      voice.set(LEVEL[dataValue & 3], wallClockTime);
    }
  }

  public void tick(final long wallClockTime)
  {
    if (output.isEnabled()) {
      voice.advance(wallClockTime);
    }
  }

  public void resync(final long wallClockTime)
  {
    voice.reset(wallClockTime);
  }
}
