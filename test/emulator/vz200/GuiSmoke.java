package emulator.vz200;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Needs a display (xvfb-run). Boots the real GUI emulator, loads a demo, screenshots the panel. */
public class GuiSmoke {
  static void shot(Video v, String path) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try {
        Component c = v.getContentPane().getComponent(0);
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        c.paint(img.getGraphics());
        ImageIO.write(img, "png", new File(path));
      } catch (Exception e) { throw new RuntimeException(e); }
    });
  }
  static void key(Video v, int code) throws Exception {
    KeyListener[] ls = v.getKeyListeners();
    for (int i = 0; i < 2; i++) {
      KeyEvent e = new KeyEvent(v, i == 0 ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
        System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED);
      for (KeyListener l : ls) { if (i == 0) l.keyPressed(e); else l.keyReleased(e); }
      Thread.sleep(120);
    }
  }
  public static void main(String[] a) throws Exception {
    String demo = a.length > 0 ? a[0] : "VZMEGA";
    String out = a.length > 1 ? a[1] : "/tmp/gui";
    new File(out).mkdirs();
    AudioOutput.DISABLED = true;
    VZ200.Options o = new VZ200.Options();
    o.osRom = new File("BIOS/VZ200_20.bin");
    o.dosRom = new File("BIOS/VZDOS.VZ");
    o.sdDir = new File("SD");
    final VZ200 vz = new VZ200(o);
    Method run = VZ200.class.getDeclaredMethod("run"); run.setAccessible(true);
    Thread t = new Thread(() -> { try { run.invoke(vz); } catch (Exception e) { e.printStackTrace(); } });
    t.setDaemon(true); t.start();
    Field f = VZ200.class.getDeclaredField("io"); f.setAccessible(true);
    Video video = ((IO) f.get(vz)).getVideo();
    System.out.println("beam mode: " + video.isBeamMode());
    Thread.sleep(9000);
    shot(video, out + "/boot.png");
    String cmd = "LOAD\"" + demo + "\"";
    for (char c : cmd.toCharArray()) {
      int code = c == '"' ? KeyEvent.VK_QUOTEDBL : (int) c;
      key(video, code);
    }
    key(video, KeyEvent.VK_ENTER);
    for (int i = 0; i < 4; i++) { Thread.sleep(8000); shot(video, out + "/t" + i + ".png"); }
    System.out.println("done");
    System.exit(0);
  }
}
