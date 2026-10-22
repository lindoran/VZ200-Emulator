package emulator.vz200;

import java.awt.Frame;
import java.awt.Point;
import java.io.File;

/** Needs a display (xvfb-run).  Uses a throwaway preferences key set. */
public class WindowMemoryTest
{
  static Frame find(String title) {
    for (Frame f : Frame.getFrames()) if (f.getClass().getSimpleName().equals(title)) return f;
    return null;
  }

  public static void main(String[] args) throws Exception
  {
    UserPreferences p = UserPreferences.getInstance();
    p.setWindowLocation("video", 300, 120);
    p.setWindowLocation("keyboard", 20, 200);
    p.setWindowLocation("settings", 5000, 5000);   // off every screen: must be ignored
    AudioOutput.DISABLED = true;
    p.setKeyboardIconified(false);
    VZ200.Options o = new VZ200.Options();
    o.osRom = new File(args[0]);
    new VZ200(o);
    Thread.sleep(1500);
    Point v = find("Video").getLocation(), k = find("Keyboard").getLocation(), s = find("SettingsGUI").getLocation();
    AudioPipelineTest.check(v.equals(new Point(300, 120)), "video restored to " + v);
    AudioPipelineTest.check(k.equals(new Point(20, 200)), "keyboard restored to " + k);
    AudioPipelineTest.check(s.x < 5000 && s.y < 5000, "off-screen saved position ignored, settings at " + s);
    find("Video").setLocation(77, 66);
    Thread.sleep(1200);
    Point saved = p.getWindowLocation("video");
    AudioPipelineTest.check(new Point(77, 66).equals(saved), "moved window saved: " + saved);
    // clean up test values
    p.setWindowLocation("video", 77, 66);
    System.exit(AudioPipelineTest.failures == 0 ? 0 : 1);
  }
}
