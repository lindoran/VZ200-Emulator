package emulator.vz200;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import javax.swing.ImageIcon;

import emulator.z80.CPU;
import emulator.z80.CPUControl;
import emulator.z80.MemoryBus;
import emulator.z80.Monitor;
import emulator.z80.RAMMemory;
import emulator.z80.ROMMemory;
import emulator.z80.Z80;

public class VZ200
{
  private static final int RAM_START = 0x7800;
  private static final int RAM_LENGTH = 0x8800;
  private static final String OS_RESOURCENAME = "os.rom";
  private static final int OS_START = 0x0000;
  private static final int OS_LENGTH = 0x4000;

  private final CPUControl cpuControl;
  private final MemoryBus portMemoryBus;
  private final MemoryBus mainMemoryBus;
  private final IO io;
  private final Monitor monitor;

  public static ImageIcon createIcon(final String imageFileName,
                                     final String altText)
  {
    // plain name, relative to this class: a "./" prefix is not resolved
    // inside jar files by current Java versions
    final URL imageURL = VZ200.class.getResource(imageFileName);
    if (imageURL != null) {
      return new ImageIcon(imageURL, altText);
    }
    return null;
  }

  /**
   * Optional host-side configuration (command line).  All fields are
   * optional; with none set the machine is a plain VZ200.
   */
  public static class Options
  {
    public File osRom;        // BASIC ROM, instead of the bundled os.rom
    public File dosRom;       // Benn Venn SD Loader firmware (VZDOS.BIN/.VZ)
    public File sdImage;      // raw MBR/FAT32 card image
    public File sdDir;        // host folder presented as a FAT32 card
    public boolean sdReadOnly;
    public boolean sdTrace;
    public boolean vz300Ram;  // 18 KB internal RAM ($7800-$B7FF) like a VZ300
    public boolean sdMbr;     // --sd-dir: partitioned (MBR) card layout
    public boolean sdLoader;  // enable loader hardware even without a card

    public boolean useSDLoader()
    {
      return sdLoader || dosRom != null || sdImage != null || sdDir != null;
    }
  }

  public VZ200() throws IOException
  {
    this(new Options());
  }

  public VZ200(final Options options) throws IOException
  {
    final ROMMemory rom = options.osRom != null ?
      new FileROMMemory(options.osRom, OS_START, OS_LENGTH) :
      new ROMMemory((Class<? extends Object>)VZ200.class,
                    OS_RESOURCENAME, OS_START, OS_LENGTH);
    portMemoryBus = new MemoryBus();
    mainMemoryBus = new MemoryBus();
    final Z80 z80 = new Z80(mainMemoryBus, portMemoryBus);
    cpuControl = new CPUControl(z80);
    cpuControl.addResourceLocation(VZ200.class);
    final boolean sd = options.useSDLoader();
    // With the SD loader fitted, the loader's (bank switched) SRAM
    // supplies the top of memory (from $9000, or $B800 in VZ300 map mode);
    // the machine itself only has 6 KB ($7800-$8FFF), or 18 KB with
    // --vz300-ram.
    final RAMMemory ram =
      new RAMMemory(RAM_START, !sd ? RAM_LENGTH :
                    (options.vz300Ram ? 0xb800 : SDLoader.HI_START) - RAM_START);
    io = new IO(cpuControl, z80, z80.getWallClockTime());
    z80.addWallClockListener(io);
    final Video video = io.getVideo();
    mainMemoryBus.addReader(ram);
    mainMemoryBus.addWriter(ram);
    mainMemoryBus.addWriter(rom);
    mainMemoryBus.addReader(io);
    mainMemoryBus.addWriter(io);
    mainMemoryBus.addReader(video);
    mainMemoryBus.addWriter(video);
    if (sd) {
      final SDLoader loader = new SDLoader(options.sdTrace);
      if (options.dosRom != null) {
        loader.loadEprom(options.dosRom);
      } else {
        System.out.println("[sd] WARNING: no --dos-rom given; the DOS area " +
                           "is empty and BASIC will not find the loader");
      }
      if (options.sdImage != null || options.sdDir != null) {
        final BlockDevice dev = options.sdImage != null ?
          new ImageBlockDevice(options.sdImage, options.sdReadOnly) :
          new FolderBlockDevice(options.sdDir, !options.sdReadOnly,
                                options.sdMbr);
        System.out.println("[sd] card: " + dev.describe());
        loader.insertCard(new SDCard(dev, options.sdTrace));
      }
      final SDLoader.MemoryView mem = loader.new MemoryView();
      mainMemoryBus.addReader(mem);
      mainMemoryBus.addWriter(mem);
      final SDLoader.PortView ports = loader.new PortView();
      portMemoryBus.addReader(ports);
      portMemoryBus.addWriter(ports);
    }
    monitor = new Monitor(cpuControl);
  }

  private void run()
  {
    monitor.run("n+=annotations.xml\n" +
                "n+=annotations-math.xml\n" +
                "n+=annotations-rt.xml\n" +
                "g0");
  }

  private static void usage()
  {
    System.err.println(
      "usage: VZ200 [options]\n" +
      "  --os-rom FILE    16 KB BASIC ROM to use instead of the bundled os.rom\n" +
      "  --dos-rom FILE   Benn Venn SD Loader firmware (VZDOS.BIN / VZDOS.VZ)\n" +
      "  --sd-image FILE  raw SD card image (MBR + FAT32)\n" +
      "  --sd-dir DIR     host folder presented to the loader as a FAT32 card\n" +
      "  --sd-mbr         with --sd-dir: lay the card out with an MBR partition table\n" +
      "                   (default: none; see SD_LOADER.md for why)\n" +
      "  --sd-ro          never modify the image / folder (writes are discarded)\n" +
      "  --vz300-ram      with the loader: 18 KB internal RAM ($7800-$B7FF), as a VZ300\n" +
      "  --sd-trace       print SD commands and loader register writes\n" +
      "  --sd-loader      fit the loader hardware even without a card\n" +
      "Any --dos-rom / --sd-* option enables the SD loader.\n" +
      "\n" +
      "sound:\n" +
      "  --audio-list     list the sound devices Java can see, then exit\n" +
      "  --audio-test     play a 3 second test tone, then exit\n" +
      "  --audio-device N use the sound device named N (see --audio-list)\n" +
      "  --no-audio       never open a sound device");
  }

  public static void main(final String argv[]) throws IOException
  {
    final Options options = new Options();
    boolean audioList = false;
    boolean audioTest = false;
    for (int i = 0; i < argv.length; i++) {
      final String a = argv[i];
      final boolean hasValue = i + 1 < argv.length;
      if (a.equals("--audio-list")) {
        audioList = true;
      } else if (a.equals("--audio-test")) {
        audioTest = true;
      } else if (a.equals("--audio-device") && hasValue) {
        AudioOutput.DEVICE_OVERRIDE = argv[++i];
      } else if (a.equals("--no-audio")) {
        AudioOutput.DISABLED = true;
      } else if (a.equals("--os-rom") && hasValue) {
        options.osRom = new File(argv[++i]);
      } else if (a.equals("--dos-rom") && hasValue) {
        options.dosRom = new File(argv[++i]);
      } else if (a.equals("--sd-image") && hasValue) {
        options.sdImage = new File(argv[++i]);
      } else if (a.equals("--sd-dir") && hasValue) {
        options.sdDir = new File(argv[++i]);
      } else if (a.equals("--vz300-ram")) {
        options.vz300Ram = true;
      } else if (a.equals("--sd-mbr")) {
        options.sdMbr = true;
      } else if (a.equals("--sd-ro")) {
        options.sdReadOnly = true;
      } else if (a.equals("--sd-trace")) {
        options.sdTrace = true;
      } else if (a.equals("--sd-loader")) {
        options.sdLoader = true;
      } else {
        usage();
        System.exit(2);
      }
    }
    if (audioList) {
      AudioDiagnostics.list();
      return;
    }
    if (audioTest) {
      System.exit(AudioDiagnostics.test(AudioOutput.DEVICE_OVERRIDE, 3));
    }
    if (options.sdImage != null && options.sdDir != null) {
      System.err.println("use either --sd-image or --sd-dir, not both");
      System.exit(2);
    }
    new VZ200(options).run();
  }
}

/*
  Local Variables:
    coding:utf-8
    mode:Java
    End:
*/
