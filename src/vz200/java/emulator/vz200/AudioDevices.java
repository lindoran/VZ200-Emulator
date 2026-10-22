package emulator.vz200;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Everything that touches javax.sound: finding output devices and
 * opening one.  Opening is deliberately defensive, because Java Sound
 * behaves differently on every platform:
 *
 *  - Only 16 bit little endian PCM is requested, at 44.1 or 48 kHz,
 *    stereo first and then mono (some devices refuse mono, some
 *    refuse 48 kHz); the first combination the device accepts wins.
 *  - "Port" mixers (volume/mute controls, no playback) are never
 *    offered; only mixers that can really play are listed.
 *  - If the requested device cannot be opened, the system default is
 *    tried, then every other device, and every failure is logged so
 *    that it can be shown to the user.
 */
public final class AudioDevices
{
  /** Device id meaning "whatever the system default is". */
  public static final String SYSTEM_DEFAULT = "";
  public static final String SYSTEM_DEFAULT_LABEL = "System default";

  public interface Opener
  {
    /**
     * @return an opened and started sink, or null.  Every attempt and
     * failure is appended to the log.
     */
    AudioSink open(String deviceId, int bufferMs, List<String> log);
  }

  public static final Opener JAVA_SOUND = AudioDevices::openJavaSound;

  /** What new outputs use; tests replace it with a fake device. */
  static volatile Opener defaultOpener = JAVA_SOUND;

  public static final class Device
  {
    public final String id;
    public final Mixer.Info info;

    Device(final Mixer.Info info)
    {
      this.id = info.getName();
      this.info = info;
    }

    @Override
    public String toString() { return id; }
  }

  private AudioDevices() {}

  static AudioFormat format(final int rate, final int channels)
  {
    return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16,
                           channels, 2 * channels, rate, false);
  }

  static List<AudioFormat> candidateFormats()
  {
    final List<AudioFormat> formats = new ArrayList<AudioFormat>();
    for (final int rate : new int[] {44100, 48000}) {
      for (final int channels : new int[] {2, 1}) {
        formats.add(format(rate, channels));
      }
    }
    return formats;
  }

  static boolean supportsOutput(final Mixer mixer)
  {
    for (final AudioFormat f : candidateFormats()) {
      if (mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, f))) {
        return true;
      }
    }
    return false;
  }

  /** All mixers that can play 16 bit PCM. */
  public static List<Device> list()
  {
    final List<Device> devices = new ArrayList<Device>();
    final Mixer.Info[] infos;
    try {
      infos = AudioSystem.getMixerInfo();
    } catch (final Throwable t) {
      return devices;
    }
    for (final Mixer.Info info : infos) {
      try {
        if (supportsOutput(AudioSystem.getMixer(info))) {
          devices.add(new Device(info));
        }
      } catch (final Throwable ignored) {
        // unusable mixer, skip it
      }
    }
    return devices;
  }

  /**
   * Find a device by the id stored in preferences.  Also understands
   * the older "name, version x" form that earlier versions stored.
   */
  static Mixer.Info find(final String id)
  {
    if (id == null || id.isEmpty()) {
      return null;
    }
    for (final Device d : list()) {
      if (d.id.equals(id) || d.info.toString().equals(id)) {
        return d.info;
      }
    }
    return null;
  }

  private static final class JavaSoundSink implements AudioSink
  {
    private final SourceDataLine line;
    private final AudioFormat format;
    private final String label;
    /** Never keep more than this queued in the device (0 = no limit). */
    private final int limitBytes;

    JavaSoundSink(final SourceDataLine line, final AudioFormat format,
                  final String label, final int limitMs)
    {
      this.line = line;
      this.format = format;
      this.label = label;
      final int limit =
        (int)(format.getSampleRate() * limitMs / 1000.0) *
        format.getFrameSize();
      // some drivers ignore the requested buffer size and give half a
      // second; then keep the queue short ourselves
      limitBytes = line.getBufferSize() > 2 * limit ? limit : 0;
    }

    public int sampleRate() { return (int)format.getSampleRate(); }
    public int channels() { return format.getChannels(); }

    public int bufferFrames()
    {
      return line.getBufferSize() / format.getFrameSize();
    }

    public String description()
    {
      return String.format("%s, %d Hz %s, %d ms device buffer", label,
                           sampleRate(), channels() == 1 ? "mono" : "stereo",
                           bufferFrames() * 1000 / sampleRate());
    }

    public void write(final byte[] data, final int offset, final int length)
      throws IOException
    {
      int done = 0;
      int stalls = 0;
      if (limitBytes > 0) {
        int spins = 0;
        while (line.getBufferSize() - line.available() > limitBytes &&
               line.isOpen() && ++spins < 2000) {
          try {
            Thread.sleep(1);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
          }
        }
      }
      while (done < length) {
        final int n = line.write(data, offset + done, length - done);
        if (n > 0) {
          done += n;
          stalls = 0;
        } else {
          if (!line.isOpen()) {
            throw new IOException("audio line was closed");
          }
          if (++stalls > 100) {
            throw new IOException("audio line does not accept data");
          }
          try {
            Thread.sleep(2);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
          }
        }
      }
    }

    public long framesPlayed()
    {
      return line.getLongFramePosition();
    }

    public void close()
    {
      try { line.stop(); } catch (final Throwable ignored) { }
      try { line.flush(); } catch (final Throwable ignored) { }
      try { line.close(); } catch (final Throwable ignored) { }
    }
  }

  private static AudioSink openLine(final SourceDataLine line,
                                    final AudioFormat format,
                                    final int bufferMs,
                                    final List<String> log,
                                    final String label)
  {
    boolean opened = false;
    for (final int ms : new int[] {bufferMs, bufferMs * 2, bufferMs * 4}) {
      final int frames =
        Math.max(1024, (int)(format.getSampleRate() * ms / 1000.0));
      try {
        line.open(format, frames * format.getFrameSize());
        opened = true;
        break;
      } catch (final Throwable t) {
        log.add(label + ": " + ms + " ms buffer refused (" + t + ")");
      }
    }
    if (!opened) {
      try {
        line.open(format);
      } catch (final Throwable t2) {
        log.add(label + ": open failed: " + t2);
        return null;
      }
    }
    try {
      line.start();
    } catch (final Throwable t) {
      log.add(label + ": start failed: " + t);
      try { line.close(); } catch (final Throwable ignored) { }
      return null;
    }
    return new JavaSoundSink(line, format, label, bufferMs);
  }

  private static AudioSink tryMixer(final Mixer.Info info, final int bufferMs,
                                    final List<String> log)
  {
    final Mixer mixer;
    try {
      mixer = AudioSystem.getMixer(info);
    } catch (final Throwable t) {
      log.add(info.getName() + ": cannot access mixer: " + t);
      return null;
    }
    for (final AudioFormat f : candidateFormats()) {
      final DataLine.Info lineInfo =
        new DataLine.Info(SourceDataLine.class, f);
      try {
        if (!mixer.isLineSupported(lineInfo)) {
          continue;
        }
        final SourceDataLine line = (SourceDataLine)mixer.getLine(lineInfo);
        final AudioSink sink = openLine(line, f, bufferMs, log,
                                        info.getName());
        if (sink != null) {
          return sink;
        }
      } catch (final Throwable t) {
        log.add(info.getName() + ": " + f.getSampleRate() + " Hz, " +
                f.getChannels() + " ch: " + t);
      }
    }
    return null;
  }

  private static boolean looksLikeDisplayAudio(final Mixer.Info info)
  {
    final String text =
      (info.getName() + " " + info.getDescription()).toLowerCase();
    return text.contains("hdmi") || text.contains("displayport");
  }

  /**
   * Java has no notion of the desktop's default device and simply lists
   * the hardware.  On many PCs the first entry is the HDMI output of
   * the graphics card, which is rarely where the sound should go, so
   * for "System default" prefer a device that is not display audio.
   */
  private static AudioSink tryDefault(final int bufferMs,
                                      final List<String> log,
                                      final List<Mixer.Info> tried)
  {
    final List<Mixer.Info> order = new ArrayList<Mixer.Info>();
    for (final Device d : list()) {
      if (!looksLikeDisplayAudio(d.info)) {
        order.add(d.info);
      }
    }
    for (final Device d : list()) {
      if (looksLikeDisplayAudio(d.info)) {
        order.add(d.info);
      }
    }
    for (final Mixer.Info info : order) {
      if (!tried.contains(info)) {
        tried.add(info);
        final AudioSink sink = tryMixer(info, bufferMs, log);
        if (sink != null) {
          return sink;
        }
      }
    }
    return null;
  }

  static AudioSink openJavaSound(final String deviceId, final int bufferMs,
                                 final List<String> log)
  {
    final List<Mixer.Info> tried = new ArrayList<Mixer.Info>();
    if (deviceId != null && !deviceId.isEmpty()) {
      final Mixer.Info info = find(deviceId);
      if (info == null) {
        log.add("device \"" + deviceId + "\" not found, using the default");
      } else {
        tried.add(info);
        final AudioSink sink = tryMixer(info, bufferMs, log);
        if (sink != null) {
          return sink;
        }
        log.add("device \"" + deviceId + "\" could not be opened, " +
                "falling back to the default");
      }
    }
    AudioSink sink = tryDefault(bufferMs, log, tried);
    if (sink != null) {
      return sink;
    }
    for (final Device d : list()) {
      if (!tried.contains(d.info)) {
        sink = tryMixer(d.info, bufferMs, log);
        if (sink != null) {
          return sink;
        }
      }
    }
    if (list().isEmpty()) {
      log.add("Java Sound reports no usable playback device");
    }
    return null;
  }
}
