package emulator.vz200;

import java.io.File;

/** java BootCheck ROM VZDOS.VZ CARDDIR VZ300RAM -- boot the loader, fail if the DOS prints NOT FOUND / ERROR or never reaches READY. */
public class BootCheck
{
  public static void main(final String[] a) throws Exception
  {
    final HeadlessVZ vz = new HeadlessVZ(new File(a[0]), new File(a[1]),
        new FolderBlockDevice(new File(a[2]), false, false), Boolean.parseBoolean(a[3]));
    vz.runFrames(250);
    final String s = vz.screen();
    final boolean bad = s.contains("NOT FOUND") || s.contains("ERROR") || s.contains("FAIL");
    final boolean ready = s.contains("READY");
    System.out.println(s);
    System.exit(!bad && ready ? 0 : 1);
  }
}
