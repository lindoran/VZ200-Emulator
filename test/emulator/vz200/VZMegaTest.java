package emulator.vz200;

import java.io.File;

/**
 * Headless test for VZMEGA and related graphics demos.
 *
 *   java -cp build:test/build:jar/z80.jar emulator.vz200.VZMegaTest [DEMONAME...]
 */
public class VZMegaTest {
  static boolean testDemo(String name) throws Exception {
    File osRom = new File("BIOS/VZ200_20.bin");
    File dosRom = new File("BIOS/VZDOS.VZ");
    File sdDir = new File("SD");

    System.out.println("========================================");
    System.out.println("Testing " + name);
    HeadlessVZ vz = new HeadlessVZ(osRom, dosRom, new FolderBlockDevice(sdDir, false, false), false);
    vz.runFrames(250);

    vz.type("LOAD\"" + name + "\"\n");
    vz.runFrames(500);

    System.out.printf("After loading %s (frame %d): PC=%04X bank=%02X%n",
      name, vz.frames, vz.cpu.getProgramCounter().getValue(), vz.loader.getBankRegister());

    vz.runFrames(500);
    int nonBlank = 0;
    for (int i = 0x7000; i < 0x7800; i++) {
      int b = vz.peek(i);
      if (b != 0x20 && b != 0x00) nonBlank++;
    }
    System.out.printf("%s at frame %d: PC=%04X bank=%02X VRAM non-blank=%d%n",
      name, vz.frames, vz.cpu.getProgramCounter().getValue(),
      vz.loader.getBankRegister(), nonBlank);
    System.out.println("Screen:\n" + vz.screen());
    return nonBlank > 0;
  }

  public static void main(String[] args) throws Exception {
    String[] demos = args.length > 0 ? args : new String[]{"VZMEGA"};
    int failed = 0;
    for (String demo : demos) {
      try {
        if (!testDemo(demo)) {
          System.err.println("FAILED (blank screen): " + demo);
          failed++;
        }
      } catch (Throwable t) {
        System.err.println("FAILED on demo: " + demo);
        t.printStackTrace();
        failed++;
      }
    }
    if (failed > 0) System.exit(1);
  }
}
