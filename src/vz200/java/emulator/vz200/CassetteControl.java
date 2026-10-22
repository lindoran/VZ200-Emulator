package emulator.vz200;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import emulator.z80.WallClockProvider;

public class CassetteControl extends JPanel
{
  private static final long serialVersionUID = 7805962032130043825L;

  private static class CassetteOutPrefs implements AudioOutputControl.Prefs
  {
    private final UserPreferences p = UserPreferences.getInstance();
    public String getDevice() { return p.getCassetteOutMixer(); }
    public void setDevice(final String id) { p.setCassetteOutMixer(id); }
    public double getVolume() { return p.getCassetteOutVolume(); }
    public void setVolume(final double v) { p.setCassetteOutVolume(v); }
    public boolean getMuted() { return p.getCassetteOutMuted(); }
    public void setMuted(final boolean m) { p.setCassetteOutMuted(m); }
  }

  private final CassetteTransportControl transportControl;

  public CassetteControl(final CassetteCtrlRoomOut cassetteOut,
                         final AudioOutput cassetteOutput,
                         final WallClockProvider wallClockProvider,
                         final JFrame owner)
  {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    final UserPreferences userPreferences = UserPreferences.getInstance();

    final JTabbedPane tpSettings = new JTabbedPane();
    add(tpSettings);

    final JPanel cassetteInControl = new JPanel();
    cassetteInControl.setLayout(new BoxLayout(cassetteInControl,
                                              BoxLayout.Y_AXIS));
    tpSettings.addTab("Host File → Cass In", null,
                      cassetteInControl, "Configure Cassette Input");
    final CommonFileSampleControl commonFileSampleControl =
      new CommonFileSampleControl();
    cassetteInControl.add(commonFileSampleControl);

    final AudioFileSampleControl audioFileSampleControl =
      new AudioFileSampleControl();
    cassetteInControl.add(audioFileSampleControl);

    final VZFileSampleControl vzFileSampleControl =
      new VZFileSampleControl();
    cassetteInControl.add(vzFileSampleControl);


    final JPanel cassetteOutControl = new JPanel();
    cassetteOutControl.setLayout(new BoxLayout(cassetteOutControl,
                                               BoxLayout.Y_AXIS));
    tpSettings.addTab("Cass Out → Host File", null,
                      cassetteOutControl, "Configure Cassette Output");

    cassetteOutControl.add(new AudioOutputControl("Cassette monitor",
                                                  cassetteOutput,
                                                  cassetteOut.getVoice(),
                                                  new CassetteOutPrefs(),
                                                  true));

    transportControl = new CassetteTransportControl(wallClockProvider);
    add(transportControl);
  }

  public CassetteTransportControl getTransportControl()
  {
    return transportControl;
  }
}

/*
  Local Variables:
    coding:utf-8
    mode:Java
    End:
*/
