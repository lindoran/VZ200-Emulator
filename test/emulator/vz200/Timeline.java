package emulator.vz200;
import java.io.File;
public class Timeline {
  public static void main(String[] a) throws Exception {
    final BeamVZ vz = new BeamVZ(new File("BIOS/VZ200_20.bin"), new File("BIOS/VZDOS.VZ"),
      new FolderBlockDevice(new File("SD"), false, false));
    new File(a[1]).mkdirs();
    vz.runFrames(150); vz.type("LOAD\"" + a[0] + "\"\n"); vz.runFrames(300);
    int step = Integer.parseInt(a[2]);
    for (int i = 0; i < Integer.parseInt(a[3]); i++) {
      vz.runFrames(step);
      vz.savePng(String.format("%s/t%02d.png", a[1], i));
    }
  }
}
