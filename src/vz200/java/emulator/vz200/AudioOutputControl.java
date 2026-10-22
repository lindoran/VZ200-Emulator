package emulator.vz200;

import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;

/**
 * Settings panel for one audio output: device selection, test tone,
 * volume, mute and a live status line that says what is really going
 * on (which device and format were opened, buffer fill, underruns,
 * and why opening failed, if it did).
 */
public class AudioOutputControl extends JPanel
{
  private static final long serialVersionUID = 4120893310347519557L;

  public interface Prefs
  {
    String getDevice();
    void setDevice(String id);
    double getVolume();
    void setVolume(double volume);
    boolean getMuted();
    void setMuted(boolean muted);
  }

  private static final class Item
  {
    final String id;
    final String label;

    Item(final String id, final String label)
    {
      this.id = id;
      this.label = label;
    }

    @Override
    public String toString() { return label; }
  }

  private final AudioOutput output;
  private final JComboBox<Item> cbDevice = new JComboBox<Item>();
  private final JTextArea taStatus = new JTextArea(4, 40);
  private boolean rebuilding;
  private boolean muted;

  /**
   * @param muteReleasesDevice If true, muting also closes the sound
   * device (used for the optional cassette monitor); otherwise the
   * device stays open and just plays silence.
   */
  public AudioOutputControl(final String title, final AudioOutput output,
                            final SignalVoice voice, final Prefs prefs,
                            final boolean muteReleasesDevice)
  {
    this.output = output;
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    final Box devicePanel = new Box(BoxLayout.Y_AXIS);
    final Box row = new Box(BoxLayout.X_AXIS);
    row.add(new JLabel("Output device: "));
    row.add(cbDevice);
    final JButton btRefresh = new JButton("Refresh");
    btRefresh.setToolTipText("Look for sound devices again");
    row.add(Box.createHorizontalStrut(5));
    row.add(btRefresh);
    devicePanel.add(row);
    devicePanel.add(Box.createVerticalStrut(5));
    final Box row2 = new Box(BoxLayout.X_AXIS);
    final JButton btTest = new JButton("Play test tone");
    btTest.setToolTipText("Plays a short 440 Hz beep through this output, " +
                          "without involving the emulator.");
    row2.add(btTest);
    row2.add(Box.createHorizontalGlue());
    devicePanel.add(row2);

    final VolumeControl volumeControl =
      new VolumeControl(title + " volume", title, prefs.getVolume(), null,
                        true, prefs.getMuted(), devicePanel);
    add(volumeControl);

    taStatus.setEditable(false);
    taStatus.setLineWrap(true);
    taStatus.setWrapStyleWord(true);
    taStatus.setOpaque(false);
    taStatus.setBorder(javax.swing.BorderFactory.createTitledBorder("Status"));
    add(taStatus);

    final String initialDevice = prefs.getDevice();
    output.setDevice(DeviceSupport.effective(initialDevice));
    rebuildDevices(initialDevice);

    cbDevice.addActionListener((final ActionEvent e) -> {
        if (!rebuilding) {
          final Item item = (Item)cbDevice.getSelectedItem();
          if (item != null) {
            prefs.setDevice(item.id);
            output.setDevice(item.id);
          }
        }
      });
    btRefresh.addActionListener((final ActionEvent e) -> {
        final Item item = (Item)cbDevice.getSelectedItem();
        rebuildDevices(item != null ? item.id : prefs.getDevice());
      });
    btTest.addActionListener((final ActionEvent e) -> output.playTestTone());

    volumeControl.addListener(new LineControlListener() {
        public void lineChanged(final SourceDataLineChangeEvent event) { }

        public void volumeChanged(final double volume)
        {
          voice.setGain(volume);
          prefs.setVolume(volume);
        }

        public void mutedChanged(final boolean m)
        {
          muted = m;
          voice.setMuted(m);
          prefs.setMuted(m);
          if (muteReleasesDevice) {
            output.setEnabled(!m);
          }
        }
      });
    if (!muteReleasesDevice) {
      output.setEnabled(true);
    }

    final Timer timer = new Timer(500, (final ActionEvent e) -> updateStatus());
    timer.start();
    updateStatus();
  }

  /** Helper kept separate so the override logic is testable. */
  static final class DeviceSupport
  {
    static String effective(final String saved)
    {
      final String override = AudioOutput.DEVICE_OVERRIDE;
      return override != null ? override : saved;
    }
  }

  private void rebuildDevices(final String selectId)
  {
    rebuilding = true;
    try {
      cbDevice.removeAllItems();
      final Item systemDefault =
        new Item(AudioDevices.SYSTEM_DEFAULT,
                 AudioDevices.SYSTEM_DEFAULT_LABEL);
      cbDevice.addItem(systemDefault);
      Item selected = systemDefault;
      for (final AudioDevices.Device d : AudioDevices.list()) {
        final Item item = new Item(d.id, d.id);
        cbDevice.addItem(item);
        if (d.id.equals(selectId) || d.info.toString().equals(selectId)) {
          selected = item;
        }
      }
      cbDevice.setSelectedItem(selected);
    } finally {
      rebuilding = false;
    }
  }

  private void updateStatus()
  {
    final StringBuilder sb = new StringBuilder();
    if (muted) {
      sb.append("MUTED. ");
    }
    sb.append(output.getStatus());
    if (!output.isOpen() && output.isEnabled()) {
      final List<String> log = output.getOpenLog();
      for (final String line : log) {
        sb.append('\n').append(line);
      }
    }
    final String text = sb.toString();
    if (!text.equals(taStatus.getText())) {
      taStatus.setText(text);
    }
  }
}
