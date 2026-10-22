package emulator.vz200;

import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Command line sound checks that need no emulator window:
 *
 *   --audio-list   shows every Java Sound mixer and what it can play
 *   --audio-test   plays a 440 Hz tone for three seconds through the
 *                  same code path the emulator's speaker uses
 */
final class AudioDiagnostics
{
  private AudioDiagnostics() {}

  static void list()
  {
    System.out.println("java " + System.getProperty("java.version") + " on " +
                       System.getProperty("os.name") + " " +
                       System.getProperty("os.arch"));
    final Mixer.Info[] infos = AudioSystem.getMixerInfo();
    if (infos.length == 0) {
      System.out.println("Java Sound reports no mixers at all.");
    }
    for (final Mixer.Info info : infos) {
      final Mixer mixer = AudioSystem.getMixer(info);
      final StringBuilder formats = new StringBuilder();
      for (final AudioFormat f : AudioDevices.candidateFormats()) {
        if (mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, f))) {
          if (formats.length() > 0) {
            formats.append(", ");
          }
          formats.append((int)f.getSampleRate()).append(" Hz ").
            append(f.getChannels() == 1 ? "mono" : "stereo");
        }
      }
      System.out.printf("%s%n    %s%n    playback: %s%n", info.getName(),
                        info.getDescription(),
                        formats.length() > 0 ? formats : "no (not usable)");
    }
    final List<AudioDevices.Device> usable = AudioDevices.list();
    System.out.println();
    System.out.println(usable.size() + " usable playback device(s).");
  }

  static int test(final String device, final int seconds)
  {
    final AudioOutput out =
      new AudioOutput("test", device, true);
    final SignalVoice voice = new SignalVoice(out, 0.5);
    out.start();
    final long deadline = System.nanoTime() + 3_000_000_000L;
    while (!out.isOpen() && System.nanoTime() < deadline) {
      sleep(20);
    }
    if (!out.isOpen()) {
      System.out.println("FAILED: " + out.getStatus());
      for (final String line : out.getOpenLog()) {
        System.out.println("  " + line);
      }
      out.close();
      return 1;
    }
    System.out.println("Playing a 440 Hz tone for " + seconds + " s...");
    // A square wave with edges at exact "emulated" times, fed the way
    // the emulated CPU feeds the speaker.
    final long halfPeriod = 1_000_000_000L / 440 / 2;
    final long start = System.nanoTime();
    long nextEdge = halfPeriod;
    double level = 1.0;
    voice.reset(0);
    while (true) {
      final long now = System.nanoTime() - start;
      if (now > seconds * 1_000_000_000L) {
        break;
      }
      while (nextEdge <= now) {
        level = -level;
        voice.set(level, nextEdge);
        nextEdge += halfPeriod;
      }
      voice.advance(now);
      sleep(1);
    }
    System.out.println(out.getStatus());
    out.close();
    System.out.println("If you heard the tone, sound output works.");
    return 0;
  }

  private static void sleep(final long ms)
  {
    try {
      Thread.sleep(ms);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
