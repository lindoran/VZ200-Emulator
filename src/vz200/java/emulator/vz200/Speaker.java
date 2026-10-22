package emulator.vz200;

/**
 * The VZ200's loudspeaker.  It sits between two latch bits (bit 0 and
 * bit 5 of the I/O latch at $6800), so the cone is pushed one way or
 * the other by their difference.  The ROM's SOUND routine toggles both
 * bits together (XOR $21), i.e. swings the full -1 .. +1 range.
 */
public class Speaker
{
  private static final double FULL_SCALE = 0.8;

  private final SignalVoice voice;

  public Speaker(final AudioOutput output)
  {
    voice = new SignalVoice(output, FULL_SCALE);
  }

  public SignalVoice getVoice() { return voice; }

  /** Called by the emulated CPU for every write to the latch. */
  public void putEvent(final int plusPinValue, final int minusPinValue,
                       final long wallClockTime)
  {
    voice.set(plusPinValue - minusPinValue, wallClockTime);
  }

  public void tick(final long wallClockTime)
  {
    voice.advance(wallClockTime);
  }

  public void resync(final long wallClockTime)
  {
    voice.reset(wallClockTime);
  }
}
