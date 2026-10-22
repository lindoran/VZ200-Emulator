package emulator.vz200;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

public class SpeakerControl extends JPanel
{
  private static final long serialVersionUID = -5660423523837741207L;

  private static class SpeakerPrefs implements AudioOutputControl.Prefs
  {
    private final UserPreferences p = UserPreferences.getInstance();
    public String getDevice() { return p.getSpeakerMixer(); }
    public void setDevice(final String id) { p.setSpeakerMixer(id); }
    public double getVolume() { return p.getSpeakerVolume(); }
    public void setVolume(final double v) { p.setSpeakerVolume(v); }
    public boolean getMuted() { return p.getSpeakerMuted(); }
    public void setMuted(final boolean m) { p.setSpeakerMuted(m); }
  }

  public SpeakerControl(final Speaker speaker, final AudioOutput output)
  {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    add(new AudioOutputControl("Speaker", output, speaker.getVoice(),
                               new SpeakerPrefs(), false));
  }
}
