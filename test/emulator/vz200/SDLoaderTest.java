package emulator.vz200;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Headless tests for the SD loader model.
 *
 *   java -cp build:jar/z80.jar emulator.vz200.SDLoaderTest \
 *        BASIC_ROM.bin VZDOS_TESTDIR WORKDIR
 *
 * VZDOS_TESTDIR must hold VZDOS.VZ, WT.VZ and FILE1.DAT (the
 * "VZDOS2_32_File_IO_Test" pack).
 */
public class SDLoaderTest
{
  private static int failures;

  private static void check(final boolean ok, final String what)
  {
    System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    if (!ok) failures++;
  }

  private static void out(final SDLoader.PortView p, final int port, final int v)
  { p.writeByte(port, v, 0); }

  private static int in(final SDLoader.PortView p, final int port)
  { return p.readByte(port, 0); }

  private static int xfer(final SDLoader.PortView p, final int v)
  {
    out(p, 0x39, v);
    return in(p, 0x39);
  }

  private static File copyTo(final File dir, final File src, final String name)
    throws Exception
  {
    final File d = new File(dir, name);
    Files.copy(src.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING);
    return d;
  }

  // ------------------------------------------------------------------
  static void registerTests(final File dosRom) throws Exception
  {
    System.out.println("== register / banking behaviour");
    final SDLoader l = new SDLoader(false);
    l.loadEprom(dosRom);
    final SDLoader.MemoryView m = l.new MemoryView();
    final SDLoader.PortView p = l.new PortView();

    check(m.readByte(0x4000, 0) == 0xaa && m.readByte(0x4001, 0) == 0x55 &&
          m.readByte(0x4002, 0) == 0xe7 && m.readByte(0x4003, 0) == 0x18,
          "EPROM visible at $4000 after reset (AA 55 E7 18)");
    check(m.readByte(0x6000, 0) == 0x00, "$6000-$67FF is SRAM even while the EPROM is selected (not $FF)");
    m.writeByte(0x6000, 0x3c, 0);
    check(m.readByte(0x6000, 0) == 0x3c, "$6000: write is readable back with EPROM selected");
    out(p, 0x37, 0x02);
    check(m.readByte(0x6000, 0) == 0x00, "$6000-$67FF follows the DOS SRAM bank (port 55 bit1)");
    out(p, 0x37, 0x00);
    check(m.readByte(0x6000, 0) == 0x3c, "$6000 bank 0 kept its value");
    final int eprom5fff = m.readByte(0x5fff, 0);
    m.writeByte(0x5fff, 0x99, 0);
    check(m.readByte(0x5fff, 0) == eprom5fff, "$4000-$5FFF still reads the EPROM, not the SRAM");
    m.writeByte(0x4100, 0x5a, 0);
    check(m.readByte(0x4100, 0) != 0x5a, "write with EPROM selected does not change what is read");
    out(p, 0x37, 0x01);
    check(m.readByte(0x4100, 0) == 0x5a, "port 55 bit0: SRAM selected, earlier write is there");
    out(p, 0x37, 0x03);
    check(m.readByte(0x4100, 0) == 0x00, "DOS SRAM bank 1 (port 55 bit1) is separate from bank 0");
    m.writeByte(0x4100, 0x11, 0);
    out(p, 0x37, 0x01);
    check(m.readByte(0x4100, 0) == 0x5a, "DOS SRAM bank 0 kept its value");
    out(p, 0x37, 0x00);
    m.writeByte(0x9100, 0xa5, 0);
    out(p, 0x37, 0x04);
    check(m.readByte(0x9100, 0) == 0x00, "high SRAM bank 1 (port 55 bit2) separate from bank 0");
    m.writeByte(0xffff, 0x77, 0);
    out(p, 0x37, 0x00);
    check(m.readByte(0x9100, 0) == 0xa5 && m.readByte(0xffff, 0) == 0x00,
          "high SRAM banks hold independent data up to $FFFF");
    check(m.readByte(0x8fff, 0) == 0xff && m.readByte(0x7800, 0) == 0xff,
          "loader does not answer below $9000 in VZ200 map");
    out(p, 0x3a, 0x01);
    check(m.readByte(0x9100, 0) == 0xff && m.readByte(0xb7ff, 0) == 0xff,
          "port 58 = 1 (VZ300 map): $9000-$B7FF no longer loader RAM");
    m.writeByte(0xb800, 0x42, 0);
    check(m.readByte(0xb800, 0) == 0x42, "VZ300 map: loader RAM from $B800");
    out(p, 0x3a, 0x00);
    check(m.readByte(0x9100, 0) == 0xa5, "back to VZ200 map: $9100 data intact");
    out(p, 0x3a, 0x01);
    m.writeByte(0x9200, 0x5a, 0);              // VZ300 map: stock RAM area, still hits SRAM
    m.writeByte(0x8100, 0x77, 0);              // below the loader RAM: never readable
    check(m.readByte(0x9200, 0) == 0xff, "VZ300 map: $9200 not answered by the loader");
    out(p, 0x3a, 0x00);
    check(m.readByte(0x9200, 0) == 0x5a, "every write reaches the SRAM: $9200 visible after switching to VZ200 map");
    check(m.readByte(0x8100, 0) == 0xff, "$8100 is never answered by the loader");
    check(in(p, 0x3a) == 0xff && in(p, 0x38) == 0xff && in(p, 0x37) == 0xff,
          "write-only registers read as undriven bus ($FF)");
  }

  // ------------------------------------------------------------------
  static void protocolTests(final File folder) throws Exception
  {
    System.out.println("== SD protocol through the port registers (folder card)");
    final FolderBlockDevice dev = new FolderBlockDevice(folder, true, false);
    final SDLoader l = new SDLoader(false);
    l.insertCard(new SDCard(dev, false));
    final SDLoader.PortView p = l.new PortView();

    out(p, 0x38, 1);
    for (int i = 0; i < 11; i++) xfer(p, 0xff);
    out(p, 0x38, 3);
    int r1 = -1;
    for (final int b : new int[] {0x40, 0, 0, 0, 0, 0x95}) xfer(p, b);
    for (int i = 0; i < 8 && r1 != 0x01; i++) r1 = xfer(p, 0xff);
    check(r1 == 0x01, "CMD0 -> R1 = 01 (idle)");

    out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
    for (final int b : new int[] {0x48, 0, 0, 1, 0xaa, 0x87}) xfer(p, b);
    final int[] r7 = new int[5];
    for (int i = 0; i < 5; i++) r7[i] = xfer(p, 0xff);
    check(Arrays.equals(r7, new int[] {1, 0, 0, 1, 0xaa}), "CMD8 -> 01 00 00 01 AA");

    int ready = -1;
    for (int tries = 0; tries < 10 && ready != 0; tries++) {
      out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
      for (final int b : new int[] {0x77, 0, 0, 0, 0, 0x65}) xfer(p, b);
      xfer(p, 0xff);
      out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
      for (final int b : new int[] {0x69, 0x40, 0, 0, 0, 0x77}) xfer(p, b);
      ready = xfer(p, 0xff);
    }
    check(ready == 0, "CMD55+ACMD41 leaves idle state");

    out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
    for (final int b : new int[] {0x7a, 0x40, 0, 0, 0, 0xff}) xfer(p, b);
    xfer(p, 0xff);
    final int ocr0 = xfer(p, 0xff);
    check((ocr0 & 0x40) != 0, "CMD58 OCR reports high capacity (block addressing)");

    // read MBR
    out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
    for (final int b : new int[] {0x51, 0, 0, 0, 0, 0x01}) xfer(p, b);
    int tok = 0;
    for (int i = 0; i < 20 && tok != 0xfe; i++) tok = xfer(p, 0xff);
    check(tok == 0xfe, "CMD17 -> data token FE");
    final int[] sec = new int[512];
    for (int i = 0; i < 512; i++) sec[i] = xfer(p, 0xff);
    check(sec[510] == 0x55 && sec[511] == 0xaa, "sector 0 has boot signature 55 AA");

    // write a sector and read it back (use a free sector near the end)
    final long lba = dev.getSectorCount() - 3;
    out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
    for (final int b : new int[] {0x58, (int)(lba >> 24), (int)(lba >> 16) & 0xff,
                                  (int)(lba >> 8) & 0xff, (int)lba & 0xff, 0x01}) xfer(p, b);
    int w = -1;
    for (int i = 0; i < 8 && w != 0; i++) w = xfer(p, 0xff);
    check(w == 0, "CMD24 -> R1 = 00");
    xfer(p, 0xfe);
    for (int i = 0; i < 512; i++) xfer(p, (i * 7 + 3) & 0xff);
    xfer(p, 0xff); xfer(p, 0xff);
    int resp = 0xff;
    for (int i = 0; i < 8; i++) { final int v = xfer(p, 0xff); if (v != 0xff) { resp = v; break; } }
    check((resp & 0x1f) == 0x05, "write data response = accepted (xxx00101)");
    int busy = 0;
    while (xfer(p, 0xff) != 0xff && busy++ < 100) { }
    check(busy < 100, "card leaves busy state");
    out(p, 0x38, 1); xfer(p, 0xff); out(p, 0x38, 3);
    for (final int b : new int[] {0x51, (int)(lba >> 24), (int)(lba >> 16) & 0xff,
                                  (int)(lba >> 8) & 0xff, (int)lba & 0xff, 0x01}) xfer(p, b);
    tok = 0;
    for (int i = 0; i < 20 && tok != 0xfe; i++) tok = xfer(p, 0xff);
    boolean same = tok == 0xfe;
    for (int i = 0; i < 512; i++) same &= xfer(p, 0xff) == ((i * 7 + 3) & 0xff);
    check(same, "block written is read back identically");
    dev.flush();
  }

  // ------------------------------------------------------------------
  static void bootTests(final File rom, final File pack, final File work)
    throws Exception
  {
    final boolean[][] combos = { {false, false}, {true, false}, {false, true} };
    for (final boolean[] combo : combos) {
      final boolean withVzdos = combo[0];
      final boolean MBR = combo[1];
      final String tag = (withVzdos ? "with" : "without") + (MBR ? "_mbr" : "");
      System.out.println("== boot BASIC + VZDOS, folder card " +
                         (MBR ? "(MBR layout) " : "(no partition table) ") +
                         (withVzdos ? "WITH" : "without") + " VZDOS.VZ on it");
      final File card = new File(work, "card_" + tag);
      card.mkdirs();
      copyTo(card, new File(pack, "WT.VZ"), "WT.VZ");
      copyTo(card, new File(pack, "FILE1.DAT"), "FILE1.DAT");
      if (withVzdos) copyTo(card, new File(pack, "VZDOS.VZ"), "VZDOS.VZ");
      final byte[] original = Files.readAllBytes(new File(card, "FILE1.DAT").toPath());

      final FolderBlockDevice dev = new FolderBlockDevice(card, true, MBR);
      final HeadlessVZ vz = new HeadlessVZ(rom, new File(pack, "VZDOS.VZ"), dev);
      vz.runFrames(400);
      System.out.println("---- screen after boot ----\n" + vz.screen() + "---------------------------");
      final String boot = vz.screen();
      check(!boot.contains("NO RESPONSE"), "DOS found the SD card");
      check(dev.getClass() != null && vz.loader.getCard().getSectorReads() > 0,
            "DOS read sectors from the card (" + vz.loader.getCard().getSectorReads() + ")");

      vz.type("DIR\n");
      vz.runFrames(400);
      final String dir = vz.screen();
      System.out.println("---- screen after DIR ----\n" + dir + "--------------------------");
      check(dir.contains("WT") && dir.contains("FILE1"), "DIR lists WT and FILE1");

      vz.type("LOAD\"WT\"\n");
      final boolean done = vz.runUntilScreenHas("ALL DONE", 1500);
      final String wt = vz.screen();
      System.out.println("---- screen after LOAD\"WT\" ----\n" + wt + "---------------------------------");
      check(wt.contains("FILE OPENED"), "WT.VZ: OpenFile succeeded");
      check(done, "WT.VZ ran to completion (SD_WriteSector returned)");
      Thread.sleep(1200);                                 // let write-back run
      final byte[] now = Files.readAllBytes(new File(card, "FILE1.DAT").toPath());
      final String expect = "DATA WE'LL WRITE BACK TO FILE1.DAT ON THE SD";
      check(now.length == original.length, "FILE1.DAT size unchanged (" + now.length + ")");
      check(new String(now, 0, expect.length()).equals(expect),
            "host FILE1.DAT now starts with the text WT.VZ wrote");
      check(Arrays.equals(Arrays.copyOfRange(now, expect.length() + 1, now.length),
                          Arrays.copyOfRange(original, expect.length() + 1, original.length)),
            "rest of FILE1.DAT is untouched");
      dev.saveImage(new File(work, "card_" + tag + ".img"));
    }
  }

  public static void main(final String[] a) throws Exception
  {
    final File rom = new File(a[0]);
    final File pack = new File(a[1]);
    final File work = new File(a[2]);
    work.mkdirs();
    final File proto = new File(work, "proto");
    proto.mkdirs();
    copyTo(proto, new File(pack, "FILE1.DAT"), "FILE1.DAT");

    registerTests(new File(pack, "VZDOS.VZ"));
    protocolTests(proto);
    bootTests(rom, pack, work);
    System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILURE(S)");
    System.exit(failures == 0 ? 0 : 1);
  }
}
