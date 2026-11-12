package emulator.vz200;

import java.io.File;

/**
 *   java -cp build:test/build:jar/z80.jar [-Dvz.video.X=..] emulator.vz200.BeamDemoTest NAME OUTDIR [FRAMES_AFTER_LOAD]
 * Boots, LOADs NAME from ./SD, then writes PNG snapshots of the beam renderer.
 */
public class BeamDemoTest {
  public static void main(String[] a) throws Exception {
    final String name = a.length > 0 ? a[0] : "VZMEGA";
    final String out = a.length > 1 ? a[1] : "/tmp/beam";
    final int after = a.length > 2 ? Integer.parseInt(a[2]) : 600;
    new File(out).mkdirs();
    final BeamVZ vz = new BeamVZ(new File("BIOS/VZ200_20.bin"),
      new File("BIOS/VZDOS.VZ"), new FolderBlockDevice(new File("SD"), false, false));
    vz.runFrames(150);
    vz.savePng(out + "/boot.png");
    System.out.printf("boot: PC=%04X writes=%d frames=%d lines=%d%n",
      vz.cpu.getProgramCounter().getValue(), vz.latchWrites, vz.beam.getFrames(), vz.beam.lines);
    vz.type("LOAD\"" + name + "\"\n");
    vz.runFrames(300);
    vz.savePng(out + "/loading.png");
    System.out.printf("loading: PC=%04X bank=%02X writes=%d%n",
      vz.cpu.getProgramCounter().getValue(), vz.loader.getBankRegister(), vz.latchWrites);
    for (int i = 0; i < 6; i++) {
      vz.runFrames(after / 6);
      vz.savePng(String.format("%s/f%d.png", out, i));
      System.out.printf("t+%d: PC=%04X bank=%02X writes=%d%n", (i + 1) * (after / 6),
        vz.cpu.getProgramCounter().getValue(), vz.loader.getBankRegister(), vz.latchWrites);
    }
  }
}
