package emulator.vz200;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** java GhostLoadTest ROM VZDOS.VZ CARDDIR NAME START(hex) FRAMES  -- LOAD"NAME", report where it ends up. */
public class GhostLoadTest
{
  public static void main(final String[] a) throws Exception
  {
    final HeadlessVZ vz = new HeadlessVZ(new File(a[0]), new File(a[1]),
        new FolderBlockDevice(new File(a[2]), false, false), Boolean.parseBoolean(a.length > 6 ? a[6] : "true"));
    final int entry = Integer.parseInt(a[4], 16);
    vz.runFrames(250);
    vz.type("LOAD\"" + a[3] + "\"\n");
    final boolean hit = vz.runUntilPC(entry, Integer.parseInt(a[5]));
    System.out.println("reached entry " + a[4] + ": " + hit + "  frames=" + vz.frames
        + " pc=" + Integer.toHexString(vz.cpu.getProgramCounter().getValue()));
    if (!hit) System.out.println(vz.screen());
    if (hit) {
      final StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 0x10000; i++) sb.append(String.format("%02x", vz.peek(i)));
      Files.writeString(new File(a[2] + "/../ram_at_entry.hex").toPath(), sb.toString());
    }
    if (hit && a.length > 7) {
      final int extra = Integer.parseInt(a[7]);
      vz.runFrames(extra);
      final StringBuilder v = new StringBuilder();
      for (int i = 0x7000; i < 0x7800; i++) v.append(String.format("%02x", vz.peek(i)));
      Files.writeString(new File(a[2] + "/../vram.hex").toPath(), v.toString());
      System.out.printf("after +%d frames pc=%04X%n", extra, vz.cpu.getProgramCounter().getValue());
    }
    System.exit(hit ? 0 : 1);
  }
}
