package emulator.vz200;

public class VideoTimingTest
{
  private static void check(final boolean condition, final String message)
  {
    if (!condition) throw new AssertionError(message);
  }

  private static long clocksToNanoseconds(final int clocks)
  {
    final long time = (long)clocks * 64000L;
    return (time + Video.MC6847_CLOCKS_PER_LINE - 1) /
      Video.MC6847_CLOCKS_PER_LINE;
  }

  public static void main(final String[] args)
  {
    final long line = clocksToNanoseconds(Video.MC6847_CLOCKS_PER_LINE);
    final long syncEdge = clocksToNanoseconds(212);
    final long fieldStart = 312L * line;
    final long fsFall = 241L * line + syncEdge;
    final long fsRise = 311L * line + syncEdge;

    check(Video.fieldSyncAt(0), "field sync starts high");
    check(Video.fieldSyncAt(fsFall - 1), "field sync remains high before fall");
    check(!Video.fieldSyncAt(fsFall), "field sync falls at the MAME edge");
    check(!Video.fieldSyncAt(fsRise - 1), "field sync remains low before rise");
    check(Video.fieldSyncAt(fsRise), "field sync rises at the MAME edge");
    check(Video.fieldSyncAt(fieldStart), "field sync stays high across fields");
  }
}
