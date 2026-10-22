package emulator.vz200;

public class AudioPipelineTest
{
  static int failures;

  static void check(boolean ok, String what)
  {
    System.out.println((ok ? "PASS  " : "FAIL  ") + what);
    if (!ok) failures++;
  }

  static double freq(short[] s, int from, int to, int rate)
  {
    int crossings = 0; int first = -1, last = -1;
    for (int i = from + 1; i < to; i++) {
      if (s[i - 1] <= 0 && s[i] > 0) {
        crossings++; if (first < 0) first = i; last = i;
      }
    }
    return crossings < 2 ? 0 : (crossings - 1) * (double)rate / (last - first);
  }

  static int peak(short[] s, int from, int to)
  {
    int p = 0; for (int i = from; i < to; i++) p = Math.max(p, Math.abs(s[i])); return p;
  }

  static double mean(short[] s, int from, int to)
  {
    double m = 0; for (int i = from; i < to; i++) m += s[i]; return m / (to - from);
  }

  /** Feeds a square wave of the given frequency in real time. */
  static void feed(SignalVoice v, double hz, double swingLow, double swingHigh,
                   double seconds, int burstMs)
  {
    long half = (long)(1e9 / hz / 2);
    long start = System.nanoTime(), next = half;
    double level = swingHigh;
    v.reset(0);
    while (true) {
      long now = System.nanoTime() - start;
      if (now > seconds * 1e9) break;
      while (next <= now) {
        level = level == swingHigh ? swingLow : swingHigh;
        v.set(level, next); next += half;
      }
      v.advance(now);
      try { Thread.sleep(burstMs); } catch (InterruptedException e) {}
    }
  }

  static void run(String label, int rate, int ch, double skew, double hz,
                  double lo, double hi, double seconds, int burstMs,
                  double expectPeak)
    throws Exception
  {
    FakeSink[] h = new FakeSink[1];
    AudioOutput out = new AudioOutput("t", "", true, FakeSink.opener(h, rate, ch, skew));
    SignalVoice v = new SignalVoice(out, 0.8);
    out.start();
    while (!out.isOpen()) Thread.sleep(5);
    feed(v, hz, lo, hi, seconds, burstMs);
    String status = out.getStatus();
    out.close();
    short[] s = h[0].samples();
    int a = rate, b = s.length - rate / 2;   // skip start-up
    double f = freq(s, a, b, rate);
    int p = peak(s, a, b);
    double m = mean(s, a, b);
    System.out.printf("      [%s] %s%n", label, status);
    check(Math.abs(f - hz) / hz < 0.012, String.format("%s: frequency %.1f Hz (want %.0f, skew %.3f)", label, f, hz, skew));
    check(Math.abs(p - expectPeak * 32767) < 0.12 * 32767, String.format("%s: peak %d (want ~%d)", label, p, (int)(expectPeak * 32767)));
    check(Math.abs(m) < 0.03 * 32767, String.format("%s: DC offset %.0f", label, m));
    // silence gaps: count runs of 441 zero samples after start-up
    int zeroRun = 0, maxRun = 0;
    for (int i = a; i < b; i++) { if (s[i] == 0) { zeroRun++; maxRun = Math.max(maxRun, zeroRun); } else zeroRun = 0; }
    check(maxRun < rate / 50, String.format("%s: longest silent gap %d samples", label, maxRun));
  }

  public static void main(String[] args) throws Exception
  {
    // exact byte-level check of endianness and sign
    {
      FakeSink[] h = new FakeSink[1];
      AudioOutput out = new AudioOutput("t", "", true, FakeSink.opener(h, 44100, 2, 1.0));
      SignalVoice v = new SignalVoice(out, 1.0);
      out.start(); while (!out.isOpen()) Thread.sleep(5);
      feed(v, 1000, -1, +1, 1.5, 2);
      out.close();
      short[] s = h[0].samples();
      byte[] raw = h[0].data.toByteArray();
      boolean stereoEqual = true;
      for (int i = 0; i < 20000; i++) {
        int o = i * 4;
        if (raw[o] != raw[o + 2] || raw[o + 1] != raw[o + 3]) stereoEqual = false;
      }
      int pk = peak(s, 44100, s.length - 1000);
      check(pk > 28000 && pk <= 32768, "little-endian 16 bit, full scale at gain 1 reached: peak " + pk);
      check(stereoEqual, "both stereo channels carry identical samples");
    }
    // typical VZ SOUND swing (-1 <-> +1), 1 kHz, stereo 44.1k, exact clock
    run("44.1k stereo exact", 44100, 2, 1.000, 1000, -1, 1, 6, 1, 0.8);
    // other speaker pattern: only one pin toggles (0 <-> +1) -> half amplitude
    run("one pin 0/+1", 44100, 1, 1.000, 523, 0, 1, 5, 1, 0.4);
    // Windows-like 15 ms bursts, card clock 0.5% fast and slow
    run("48k mono, 15ms bursts, card +0.5%", 48000, 1, 1.005, 440, -1, 1, 20, 15, 0.8);
    run("44.1k stereo, 15ms bursts, card -0.5%", 44100, 2, 0.995, 440, -1, 1, 20, 15, 0.8);
    System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
    System.exit(failures == 0 ? 0 : 1);
  }
}
