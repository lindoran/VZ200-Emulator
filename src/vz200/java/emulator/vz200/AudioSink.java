package emulator.vz200;

import java.io.IOException;

/**
 * Destination for finished PCM audio: 16 bit signed, little endian,
 * interleaved frames.  The production implementation wraps a Java
 * Sound SourceDataLine; tests plug in a fake one.
 */
public interface AudioSink
{
  int sampleRate();

  int channels();

  /** Size of the device buffer in frames (informational). */
  int bufferFrames();

  /** One line, human readable, e.g. "Speakers: 44100 Hz stereo". */
  String description();

  /**
   * Write whole frames.  Blocks until the device has accepted all of
   * the data; this blocking is what paces the audio thread.
   */
  void write(byte[] data, int offset, int length) throws IOException;

  /** Number of frames the device has played so far, or -1. */
  long framesPlayed();

  void close();
}
