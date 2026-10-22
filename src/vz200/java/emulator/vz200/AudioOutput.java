package emulator.vz200;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Plays mono 16 bit samples (at the rate of the opened device) on an
 * audio device.
 *
 * Producers (see SignalVoice) push samples into a FIFO with write().
 * One daemon thread owns the device: it opens it, pulls samples out
 * of the FIFO in 10 ms chunks and hands them to the sink, whose
 * blocking write() paces the loop.  Nothing in here depends on the
 * emulator's clock.
 *
 * Keeping the FIFO near a target fill level is what makes the sound
 * hold up on real machines:
 *
 *  - playback starts only once the FIFO holds the target latency
 *    (default 80 ms), which absorbs the CPU thread working in bursts
 *    (sleep granularity, garbage collection, ...);
 *  - if the FIFO runs dry the device gets silence until it has
 *    refilled, instead of stuttering;
 *  - the host clock and the sound card clock never agree exactly, so
 *    the fill level is steered back to the target by dropping or
 *    repeating one sample every few hundred (at most 0.5 % speed
 *    change), and if it still grows beyond three times the target it
 *    is cut back at once.
 *
 * The device is opened, closed and re-opened only by the audio
 * thread; other threads just request it.  If no device can be opened
 * the samples are discarded and the emulator carries on silently,
 * retrying every few seconds.
 */
public final class AudioOutput implements AutoCloseable
{
  /** Set by the command line (--no-audio): never open any device. */
  public static volatile boolean DISABLED;

  /** Set by the command line (--audio-device): overrides saved choice. */
  public static volatile String DEVICE_OVERRIDE;

  private static final int LATENCY_MS =
    clamp(Integer.getInteger("vz200.audio.latency", 80), 20, 500);
  private static final int BUFFER_MS =
    clamp(Integer.getInteger("vz200.audio.buffer", 60), 20, 500);
  private static final int FIFO_FRAMES = 1 << 17; // power of two
  private static final long RETRY_NANOS = 3_000_000_000L;

  private final String name;
  private final AudioDevices.Opener opener;
  private final Thread thread;

  private final short[] fifo = new short[FIFO_FRAMES];
  private final Object fifoLock = new Object();
  private long readPos; // guarded by fifoLock
  private long writePos; // guarded by fifoLock

  private volatile String device;
  private volatile boolean reopenRequested = true;
  private volatile boolean enabled;
  private volatile boolean closed;
  private volatile boolean sinkOpen;
  private volatile boolean stalled;
  private volatile int rate = 44100;
  private volatile int generation;
  private volatile boolean toneRequested;
  private volatile String status = "not started";
  private volatile List<String> openLog = new ArrayList<String>();
  private final AtomicLong underruns = new AtomicLong();
  private final AtomicLong resyncs = new AtomicLong();
  private final AtomicLong droppedOnOverflow = new AtomicLong();

  public AudioOutput(final String name, final String device,
                     final boolean enabled)
  {
    this(name, device, enabled, AudioDevices.defaultOpener);
  }

  public AudioOutput(final String name, final String device,
                     final boolean enabled, final AudioDevices.Opener opener)
  {
    this.name = name;
    this.device = device != null ? device : AudioDevices.SYSTEM_DEFAULT;
    this.enabled = enabled;
    this.opener = opener;
    thread = new Thread(this::run, "audio output '" + name + "'");
    thread.setDaemon(true);
    thread.setPriority(Thread.MAX_PRIORITY);
  }

  private static int clamp(final int v, final int lo, final int hi)
  {
    return Math.max(lo, Math.min(hi, v));
  }

  public void start()
  {
    thread.start();
  }

  @Override
  public void close()
  {
    closed = true;
    LockSupport.unpark(thread);
    try {
      thread.join(1000);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------
  // Control, callable from any thread

  public void setDevice(final String id)
  {
    device = id != null ? id : AudioDevices.SYSTEM_DEFAULT;
    reopenRequested = true;
    LockSupport.unpark(thread);
  }

  public String getDevice() { return device; }

  public void setEnabled(final boolean enabled)
  {
    if (enabled && !this.enabled) {
      reopenRequested = true;
    }
    this.enabled = enabled;
    LockSupport.unpark(thread);
  }

  public boolean isEnabled() { return enabled; }

  /** True while a device is open and being fed. */
  public boolean isOpen() { return sinkOpen; }

  /** Sample rate producers must deliver; changes with the device. */
  public int sampleRate() { return rate; }

  /**
   * Incremented whenever the device (and so possibly the rate) has
   * changed or the FIFO was flushed.  Producers restart their
   * timeline when they see it change.
   */
  public int generation() { return generation; }

  /** Play a 0.7 s 440 Hz beep, independent of the emulator. */
  public void playTestTone()
  {
    toneRequested = true;
    if (!sinkOpen) {
      reopenRequested = true;
    }
    LockSupport.unpark(thread);
  }

  public List<String> getOpenLog() { return openLog; }

  public String getStatus()
  {
    if (!sinkOpen) {
      return status;
    }
    final String s = String.format("%s; buffered %d ms, underruns %d, " +
                                   "rate corrections %d", status,
                                   available() * 1000 / rate,
                                   underruns.get(), resyncs.get());
    return stalled ? s + "; WARNING: the device is not consuming audio" : s;
  }

  // ---------------------------------------------------------------
  // FIFO

  /** Append mono samples.  The oldest are dropped on overflow. */
  public void write(final short[] samples, final int count)
  {
    synchronized (fifoLock) {
      int n = count;
      int src = 0;
      if (n > FIFO_FRAMES) {
        src = n - FIFO_FRAMES;
        n = FIFO_FRAMES;
      }
      final int start = (int)(writePos & (FIFO_FRAMES - 1));
      final int first = Math.min(n, FIFO_FRAMES - start);
      System.arraycopy(samples, src, fifo, start, first);
      if (first < n) {
        System.arraycopy(samples, src + first, fifo, 0, n - first);
      }
      writePos += n;
      if (writePos - readPos > FIFO_FRAMES) {
        droppedOnOverflow.incrementAndGet();
        readPos = writePos - FIFO_FRAMES;
      }
    }
  }

  /** Discard everything buffered (producer timeline restarted). */
  public void clear()
  {
    synchronized (fifoLock) {
      readPos = writePos;
    }
  }

  private int available()
  {
    synchronized (fifoLock) {
      return (int)(writePos - readPos);
    }
  }

  private void discard(final int frames)
  {
    synchronized (fifoLock) {
      readPos = Math.min(writePos, readPos + frames);
    }
  }

  private void read(final short[] dst, final int count)
  {
    synchronized (fifoLock) {
      final int start = (int)(readPos & (FIFO_FRAMES - 1));
      final int first = Math.min(count, FIFO_FRAMES - start);
      System.arraycopy(fifo, start, dst, 0, first);
      if (first < count) {
        System.arraycopy(fifo, 0, dst, first, count - first);
      }
      readPos += count;
    }
  }

  // ---------------------------------------------------------------
  // The audio thread

  private static void sleepMillis(final long ms)
  {
    LockSupport.parkNanos(ms * 1_000_000L);
  }

  private AudioSink openSink()
  {
    final List<String> log = new ArrayList<String>();
    AudioSink sink = null;
    try {
      sink = opener.open(device, BUFFER_MS, log);
    } catch (final Throwable t) {
      log.add("unexpected error while opening audio: " + t);
    }
    final boolean firstReport = openLog.isEmpty() || sink != null;
    openLog = log;
    if (sink == null) {
      status = "no audio output available";
      if (firstReport) {
        System.err.println("AudioOutput '" + name + "': " + status);
        for (final String line : log) {
          System.err.println("  " + line);
        }
      }
      return null;
    }
    rate = sink.sampleRate();
    generation++;
    clear();
    status = "playing on " + sink.description();
    System.out.println("AudioOutput '" + name + "': " + status);
    for (final String line : log) {
      System.out.println("  (note) " + line);
    }
    return sink;
  }

  private void closeSink(final AudioSink sink)
  {
    sinkOpen = false;
    stalled = false;
    try {
      sink.close();
    } catch (final Throwable ignored) {
      // nothing to do
    }
  }

  private void run()
  {
    AudioSink sink = null;
    long lastAttempt = 0;
    boolean attempted = false;
    int channels = 1;
    int chunk = 0;
    short[] mono = null;
    short[] tmp = null;
    byte[] bytes = null;
    boolean primed = false;
    int toneLeft = 0;
    double tonePhase = 0.0;
    long watchTime = 0;
    long watchPos = 0;
    long writtenFrames = 0;

    while (!closed) {
      final boolean wantOpen = enabled && !DISABLED;
      if (sink != null && (!wantOpen || reopenRequested)) {
        closeSink(sink);
        sink = null;
      }
      if (sink == null) {
        discard(Integer.MAX_VALUE);
        if (!wantOpen) {
          status = DISABLED ? "audio disabled" : "off";
          sleepMillis(50);
          continue;
        }
        final long now = System.nanoTime();
        if (reopenRequested || !attempted || now - lastAttempt > RETRY_NANOS) {
          reopenRequested = false;
          attempted = true;
          lastAttempt = now;
          sink = openSink();
        }
        if (sink == null) {
          sleepMillis(50);
          continue;
        }
        channels = sink.channels();
        chunk = Math.max(64, rate / 100);
        mono = new short[chunk];
        tmp = new short[chunk + chunk / 50 + 8];
        bytes = new byte[chunk * channels * 2];
        primed = false;
        writtenFrames = 0;
        watchTime = System.nanoTime();
        watchPos = 0;
        sinkOpen = true;
      }

      // --- decide what to play for this chunk
      final int target = rate * LATENCY_MS / 1000;
      int avail = available();
      if (!primed && avail >= target) {
        primed = true;
      }
      boolean haveData = false;
      if (primed) {
        if (avail > 3 * target) {
          discard(avail - target);
          droppedOnOverflow.incrementAndGet();
          avail = target;
        }
        final int tolerance = rate * 25 / 1000;
        final int step = Math.max(1, chunk / 200);
        int d = 0;
        if (avail - target > tolerance) {
          d = step;
        } else if (target - avail > tolerance) {
          d = -step;
        }
        if (d > 0 && avail < chunk + d) {
          d = 0;
        }
        if (avail < chunk) {
          underruns.incrementAndGet();
          primed = false;
        } else {
          final int need = chunk + d;
          if (d == 0) {
            read(mono, chunk);
          } else {
            read(tmp, need);
            for (int i = 0; i < chunk; i++) {
              mono[i] = tmp[(int)((long)i * need / chunk)];
            }
            resyncs.incrementAndGet();
          }
          haveData = true;
        }
      }
      if (!haveData) {
        java.util.Arrays.fill(mono, (short)0);
      }

      // --- test tone overlay (independent of the emulator)
      if (toneRequested) {
        toneRequested = false;
        toneLeft = rate * 7 / 10;
        tonePhase = 0.0;
      }
      if (toneLeft > 0) {
        final int total = rate * 7 / 10;
        final int fade = rate / 50;
        final double inc = 2.0 * Math.PI * 440.0 / rate;
        for (int i = 0; i < chunk && toneLeft > 0; i++, toneLeft--) {
          final int pos = total - toneLeft;
          final double env = Math.min(1.0, Math.min(pos, toneLeft) /
                                      (double)fade);
          final int v = mono[i] +
            (int)(Math.sin(tonePhase) * 0.3 * 32767.0 * env);
          mono[i] = (short)Math.max(-32768, Math.min(32767, v));
          tonePhase += inc;
        }
      }

      // --- to bytes, little endian, duplicated over the channels
      int b = 0;
      for (int i = 0; i < chunk; i++) {
        final byte lo = (byte)mono[i];
        final byte hi = (byte)(mono[i] >> 8);
        for (int c = 0; c < channels; c++) {
          bytes[b++] = lo;
          bytes[b++] = hi;
        }
      }
      try {
        sink.write(bytes, 0, bytes.length);
      } catch (final IOException | RuntimeException e) {
        status = "audio output failed: " + e.getMessage() + " (retrying)";
        System.err.println("AudioOutput '" + name + "': " + status);
        closeSink(sink);
        sink = null;
        lastAttempt = System.nanoTime();
        attempted = true;
        continue;
      }
      writtenFrames += chunk;

      // --- watchdog: is the device actually consuming what we write?
      final long nowNs = System.nanoTime();
      if (nowNs - watchTime > 1_500_000_000L) {
        final long pos = sink.framesPlayed();
        stalled = pos >= 0 && pos == watchPos && writtenFrames > rate;
        watchPos = pos;
        watchTime = nowNs;
      }
    }
    if (sink != null) {
      closeSink(sink);
    }
  }
}
