package emulator.vz200;

/**
 * Turns a piecewise constant analog level that changes at exact
 * emulated-time instants (the VZ200's speaker pins, the cassette
 * output) into PCM samples for an AudioOutput.
 *
 * The emulated CPU stamps every change with its own time in
 * nanoseconds.  This class maps that time axis onto the sample grid
 * of the output device (time zero of the axis is the moment the voice
 * was started or last reset) and, for every output sample, computes
 * the average of the signal over that sample's 1/rate seconds.
 * Averaging instead of picking the level at the sample instant means
 * a pulse that is shorter than a sample still contributes its exact
 * share, and the harshest aliasing of plain square waves is avoided.
 *
 * A one-pole high-pass at a few hertz then removes the DC offset: the
 * speaker pins simply rest at whatever level the program left them,
 * which on real hardware is silence, not a constant push on the
 * cone.
 *
 * Threading: set(), advance() and reset() belong to the emulated CPU
 * thread.  setGain() and setMuted() may be called from anywhere.
 */
public final class SignalVoice
{
  private static final double HIGH_PASS_HZ = 12.0;
  /** More than this much emulated time between two calls is a jump. */
  private static final double MAX_GAP_SECONDS = 0.25;

  private final AudioOutput output;
  private final double fullScale;
  private volatile double gain = 1.0;
  private volatile boolean muted;

  private final short[] buffer = new short[512];
  private int fill;

  private boolean started;
  private int generation = -1;
  private int rate;
  private double highPassCoefficient;
  private long originNanos;
  private long lastNanos;
  private double position; // in samples since origin
  private long sampleIndex; // whole samples completed since origin
  private double accumulated; // level integral of the current sample
  private double level;
  private double previousInput;
  private double previousOutput;

  /**
   * @param fullScale Output amplitude (0..1) of a signal swinging
   * between -1 and +1, at gain 1.
   */
  public SignalVoice(final AudioOutput output, final double fullScale)
  {
    this.output = output;
    this.fullScale = fullScale;
  }

  public void setGain(final double gain)
  {
    this.gain = Math.max(0.0, Math.min(1.0, gain));
  }

  public void setMuted(final boolean muted)
  {
    this.muted = muted;
  }

  /** The signal changes to the given level at the given time. */
  public void set(final double newLevel, final long nanos)
  {
    if (started && newLevel == level) {
      return;
    }
    integrateTo(nanos);
    level = newLevel;
  }

  /** Time moves on without a change; hands finished samples on. */
  public void advance(final long nanos)
  {
    integrateTo(nanos);
    flush();
  }

  /**
   * The emulated clock was stopped or resumed.  Restart the time axis
   * at the given time and throw away audio that was still queued.
   */
  public void reset(final long nanos)
  {
    flush();
    output.clear();
    restart(nanos);
  }

  private void restart(final long nanos)
  {
    rate = output.sampleRate();
    generation = output.generation();
    highPassCoefficient = Math.exp(-2.0 * Math.PI * HIGH_PASS_HZ / rate);
    originNanos = nanos;
    lastNanos = nanos;
    position = 0.0;
    sampleIndex = 0;
    accumulated = 0.0;
    fill = 0;
    started = true;
  }

  private void integrateTo(final long nanos)
  {
    if (!started || generation != output.generation()) {
      restart(nanos);
      return;
    }
    if (nanos <= lastNanos) {
      return;
    }
    final double target = (nanos - originNanos) * 1e-9 * rate;
    if (target - position > MAX_GAP_SECONDS * rate) {
      flush();
      restart(nanos);
      return;
    }
    lastNanos = nanos;
    while (position < target) {
      final double boundary = sampleIndex + 1;
      if (boundary <= target) {
        accumulated += level * (boundary - position);
        emit(accumulated);
        accumulated = 0.0;
        position = boundary;
        sampleIndex++;
      } else {
        accumulated += level * (target - position);
        position = target;
      }
    }
  }

  private void emit(final double average)
  {
    final double y =
      average - previousInput + highPassCoefficient * previousOutput;
    previousInput = average;
    previousOutput = y;
    final double g = muted ? 0.0 : gain;
    final double v = y * fullScale * g * 32767.0;
    final long r = Math.round(v);
    buffer[fill++] = (short)Math.max(-32768L, Math.min(32767L, r));
    if (fill == buffer.length) {
      flush();
    }
  }

  private void flush()
  {
    if (fill > 0) {
      output.write(buffer, fill);
      fill = 0;
    }
  }
}
