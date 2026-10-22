package emulator.vz200;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.Timer;

/**
 * Remembers where each window was left and puts it back there at the
 * next start.  Only the position is stored; window sizes follow from
 * their contents (the video window from its zoom factor).
 *
 * A saved position is ignored if no connected monitor shows it any
 * more (for example a second monitor was unplugged), so a window can
 * never come back off screen.  Some window managers (notably under
 * Wayland) refuse to let applications place their own windows; there
 * the position simply is not restored.
 */
final class WindowMemory
{
  private WindowMemory() {}

  /** Call after pack() and before setVisible(true). */
  static void restoreAndTrack(final JFrame window, final String name)
  {
    final UserPreferences prefs = UserPreferences.getInstance();
    final Point saved = prefs.getWindowLocation(name);
    if (saved != null && isOnSomeScreen(saved, window)) {
      window.setLocation(saved);
    }
    // save a moment after the last move, not on every pixel
    final Timer timer = new Timer(400, e -> save(window, name));
    timer.setRepeats(false);
    window.addComponentListener(new ComponentAdapter() {
        @Override
        public void componentMoved(final ComponentEvent e)
        {
          if (window.isShowing()) {
            timer.restart();
          }
        }
      });
    window.addWindowListener(new WindowAdapter() {
        @Override
        public void windowClosing(final WindowEvent e)
        {
          save(window, name);
        }
      });
  }

  private static void save(final JFrame window, final String name)
  {
    // a minimised window reports a meaningless position
    final boolean minimised =
      (window.getExtendedState() & java.awt.Frame.ICONIFIED) != 0;
    if (window.isShowing() && !minimised) {
      final Point p = window.getLocationOnScreen();
      UserPreferences.getInstance().setWindowLocation(name, p.x, p.y);
    }
  }

  /** True if the window's title bar area would be visible. */
  private static boolean isOnSomeScreen(final Point p, final JFrame window)
  {
    final int w = Math.min(window.getWidth(), 200);
    final Rectangle titleArea = new Rectangle(p.x, p.y, Math.max(w, 50), 40);
    for (final GraphicsDevice d :
           GraphicsEnvironment.getLocalGraphicsEnvironment().
           getScreenDevices()) {
      if (d.getDefaultConfiguration().getBounds().intersects(titleArea)) {
        return true;
      }
    }
    return false;
  }
}
