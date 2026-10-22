package emulator.vz200;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** A sound card stand-in: records PCM and paces write() like a device. */
class FakeSink implements AudioSink
{
  final int rate, channels;
  final double clockSkew;   // 1.0 = exact, 1.005 = card clock 0.5% fast
  final ByteArrayOutputStream data = new ByteArrayOutputStream();
  private long frames;
  private final long startNs = System.nanoTime();
  private final int bufferFrames;

  FakeSink(int rate, int channels, double clockSkew)
  {
    this.rate = rate; this.channels = channels; this.clockSkew = clockSkew;
    this.bufferFrames = rate * 60 / 1000;
  }

  public int sampleRate() { return rate; }
  public int channels() { return channels; }
  public int bufferFrames() { return bufferFrames; }
  public String description() { return "fake " + rate + "/" + channels; }
  public synchronized long framesPlayed() { return frames; }
  public void close() {}

  public void write(byte[] b, int off, int len)
  {
    synchronized (this) {
      data.write(b, off, len);
      frames += len / (2 * channels);
    }
    // a real device accepts data until its buffer is full, then blocks:
    // emulate by sleeping until played-time catches up to written-time
    // minus the device buffer
    final double writtenSec = frames / (double)rate;
    final double playedSec = (System.nanoTime() - startNs) * 1e-9 * clockSkew;
    final double wait = writtenSec - bufferFrames / (double)rate - playedSec;
    if (wait > 0) {
      try { Thread.sleep((long)(wait * 1000), (int)((wait * 1000 % 1) * 1e6)); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
  }

  /** Channel 0 samples, decoded little endian. */
  synchronized short[] samples()
  {
    byte[] d = data.toByteArray();
    int n = d.length / (2 * channels);
    short[] s = new short[n];
    for (int i = 0; i < n; i++) {
      int o = i * 2 * channels;
      s[i] = (short)((d[o] & 0xff) | (d[o + 1] << 8));
    }
    return s;
  }

  static AudioDevices.Opener opener(final FakeSink[] holder, final int rate,
                                    final int ch, final double skew)
  {
    return new AudioDevices.Opener() {
      public AudioSink open(String id, int ms, List<String> log) {
        holder[0] = new FakeSink(rate, ch, skew);
        return holder[0];
      }
    };
  }
}
