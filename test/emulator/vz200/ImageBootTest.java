package emulator.vz200;

import java.io.File;

/** java ImageBootTest BASIC_ROM VZDOS.VZ card.img : boots, DIR, LOAD"WT". */
public class ImageBootTest
{
  public static void main(final String[] a) throws Exception
  {
    final ImageBlockDevice dev = new ImageBlockDevice(new File(a[2]), false);
    final HeadlessVZ vz = new HeadlessVZ(new File(a[0]), new File(a[1]), dev);
    vz.runFrames(500);
    System.out.println(vz.screen());
    vz.type("DIR\n");
    vz.runFrames(400);
    System.out.println(vz.screen());
    vz.type("LOAD\"WT\"\n");
    final boolean ok = vz.runUntilScreenHas("ALL DONE", 1500);
    System.out.println(vz.screen());
    dev.flush();
    System.out.println(ok ? "RESULT: WT ran to completion" : "RESULT: WT did NOT complete");
    System.exit(ok ? 0 : 1);
  }
}
