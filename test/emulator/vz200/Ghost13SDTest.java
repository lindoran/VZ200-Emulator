package emulator.vz200;
import java.io.File;
import java.nio.file.Files;
/** java Ghost13SDTest ROM EPROM_DOS CARDDIR vz300 extraFrames [mbr]
 *  LOAD"13GHOSTS" from a folder card through the real VZDOS (EPROM image = EPROM_DOS, plus VZDOS.VZ on the
 *  card if present) and compare RAM at E420 with 13GHOST1..3.DAT at 4000 / 7AE8 / E420. */
public class Ghost13SDTest {
  public static void main(String[] a) throws Exception {
    final HeadlessVZ vz = new HeadlessVZ(new File(a[0]), new File(a[1]),
        new FolderBlockDevice(new File(a[2]), false, a.length > 5 && Boolean.parseBoolean(a[5])), Boolean.parseBoolean(a[3]));
    vz.runFrames(250);
    System.out.printf("after boot: bank=%02X ramMap=%d%n", vz.loader.getBankRegister(), vz.loader.getRamMap());
    vz.type("LOAD\"13GHOSTS\"\n");
    boolean hit = vz.runUntilPC(0xE420, 3000);
    System.out.printf("reached E420: %b pc=%04X bank=%02X ramMap=%d%n", hit,
        vz.cpu.getProgramCounter().getValue(), vz.loader.getBankRegister(), vz.loader.getRamMap());
    if (!hit) System.exit(1);
    int[] addr = {0x4000, 0x7AE8, 0xE420}; int bad = 0;
    for (int n = 1; n <= 3; n++) {
      byte[] d = Files.readAllBytes(new File(a[2], "13GHOST" + n + ".DAT").toPath());
      int mism = 0;
      for (int i = 0; i < d.length; i++) if ((d[i] & 0xff) != (vz.peek(addr[n-1] + i) & 0xff)) mism++;
      System.out.printf("13GHOST%d.DAT -> %04X len %d: %s%n", n, addr[n-1], d.length, mism == 0 ? "MATCH" : "MISMATCH " + mism);
      bad += mism;
    }
    int ex = Integer.parseInt(a[4]);
    if (ex > 0) { vz.runFrames(ex);
      int nz = 0; for (int i = 0x7000; i < 0x7800; i++) if (vz.peek(i) != 0x20 && vz.peek(i) != 0) nz++;
      System.out.printf("after +%d frames: pc=%04X bank=%02X vram non-blank=%d%n", ex, vz.cpu.getProgramCounter().getValue(), vz.loader.getBankRegister(), nz); }
    System.exit(bad == 0 ? 0 : 2);
  }
}
