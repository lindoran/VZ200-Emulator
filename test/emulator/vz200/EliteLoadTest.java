package emulator.vz200;

import java.io.File;
import java.nio.file.Files;

/**
 * Loads game / size-test .VZ files through VZDOS LOAD"NAME" from a folder
 * card and checks RAM against the file when the DOS jumps to the start.
 *
 *   java EliteLoadTest BASIC_ROM VZDOS.VZ WORKDIR vz300|vz200 FILE.VZ...
 */
public class EliteLoadTest
{
  private static void run(final String... cmd) throws Exception
  {
    final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) throw new RuntimeException("failed: " + String.join(" ", cmd));
  }

  public static void main(final String[] a) throws Exception
  {
    final File rom = new File(a[0]);
    final File dos = new File(a[1]);
    final File work = new File(a[2]);
    final boolean vz300 = a[3].equals("vz300");
    int bad = 0;
    for (int i = 4; i < a.length; i++) {
      final File src = new File(a[i]);
      final String name = src.getName().replaceAll("\\.VZ$", "");
      final File card = new File(work, "card_" + name);
      card.mkdirs();
      Files.copy(src.toPath(), new File(card, src.getName()).toPath(),
                 java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      final byte[] f = Files.readAllBytes(src.toPath());
      final int start = (f[22] & 0xff) | ((f[23] & 0xff) << 8);
      final int len = f.length - 24;

      final BlockDevice dev;
      final String spcProp = System.getProperty("card.spc");
      if (spcProp != null) {
        // build a real FAT32 image with mkfs.fat / mcopy, given cluster size
        final File img = new File(work, name + "_spc" + spcProp + ".img");
        run("truncate", "-s", "128M", img.getPath());
        run("mkfs.fat", "-F", "32", "-s", spcProp, img.getPath());
        run("mcopy", "-i", img.getPath(), src.getPath(), "::");
        dev = new ImageBlockDevice(img, false);
      } else {
        dev = new FolderBlockDevice(card, false, false);
      }
      final HeadlessVZ vz = new HeadlessVZ(rom, dos, dev, vz300);
      vz.runFrames(250);
      vz.type("LOAD\"" + name + "\"\n");
      final boolean jumped = vz.runUntilPC(start, 4000);
      int mismatches = 0, first = -1;
      if (jumped) {
        for (int k = 0; k < len; k++) {
          if (vz.peek(start + k) != (f[24 + k] & 0xff)) {
            if (first < 0) first = k;
            mismatches++;
          }
        }
      }
      System.out.printf("%-10s %s load %5d bytes %04X-%04X: %s%n", name,
                        (System.getProperty("card.spc") != null ? "spc" + System.getProperty("card.spc") + " " : "") + (vz300 ? "[vz300 ram]" : "[vz200 ram]"), len, start,
                        start + len - 1,
                        !jumped ? "DOS never started it" :
                        mismatches == 0 ? "RAM identical to file at start" :
                        mismatches + " bytes differ, first at " +
                        String.format("%04X", start + first));
      if (!jumped || mismatches != 0) {
        bad++;
        System.out.println(vz.screen());
      } else if (name.startsWith("SDT")) {
        vz.runUntilScreenHas("OK", 600);
        vz.runUntilScreenHas("FAILED", 5);
        System.out.println("   test program says: " +
                           vz.screen().replaceAll("\\s+", " ").trim());
      }
    }
    System.exit(bad == 0 ? 0 : 1);
  }
}
