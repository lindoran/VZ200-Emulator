package emulator.vz200;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import javax.imageio.ImageIO;

import emulator.z80.CPU;
import emulator.z80.MemoryBus;
import emulator.z80.RAMMemory;
import emulator.z80.Z80;

/**
 * Headless VZ200 with the real beam model: Z80 core, BASIC ROM, SD loader,
 * the Beam video renderer, FS on port bit 7 and FS wired to /INT.  No Swing.
 */
public class BeamVZ
{
  public final Z80 cpu;
  public final MemoryBus mem = new MemoryBus();
  public final MemoryBus ports = new MemoryBus();
  public final SDLoader loader;
  public final Beam beam;
  public final RAMMemory ram;
  private final KeyboardMatrix matrix = new KeyboardMatrix();
  private final ArrayDeque<Character> typeQueue = new ArrayDeque<Character>();
  private List<KeyboardMatrix.Key> held;
  private int keyPhase;
  private long nextKeyTick;
  public int latch;
  public long latchWrites;
  public long irqAcceptedLines;

  /** optional hook called on every latch write: (clock, value) */
  public interface LatchProbe { void write(long clock, int value); }
  public LatchProbe latchProbe;
  /** optional hook called on every video RAM write: (clock, address, value) */
  public interface VramProbe { void write(long clock, int addr, int value); }
  public VramProbe vramProbe;
  /** optional hook called on every port read of bit 7: (clock, fs) */
  public interface ReadProbe { void read(long clock, boolean fs); }
  public ReadProbe readProbe;

  private long accessClock(final int adjust)
  {
    return cpu.getWallClockCycles() + cpu.getCurrentInstructionPeriods() + adjust;
  }

  private class Io implements MemoryBus.BusReader, MemoryBus.BusWriter
  {
    int adjust;
    public int readByte(final int a, final long t)
    {
      if (a < 0x6800 || a > 0x6fff) return 0xff;
      final long clk = accessClock(0) + beam.readOffset;
      final boolean fs = beam.fs(clk);
      if (readProbe != null) readProbe.read(clk, fs);
      return (matrix.read(a - 0x6800) & 0x3f) | (fs ? 0x80 : 0);
    }
    public int readShort(final int a, final long t)
    { return readByte(a, t) | (readByte(a + 1, t) << 8); }
    public void writeByte(final int a, final int v, final long t)
    {
      if (a >= 0x6800 && a <= 0x6fff) {
        latch = v & 0xff;
        latchWrites++;
        final long clk = accessClock(adjust);
        beam.latchWrite(clk, v);
        if (latchProbe != null) latchProbe.write(clk, v & 0xff);
      }
    }
    public void writeShort(final int a, final int v, final long t)
    {
      adjust = -3;
      writeByte(a, v & 0xff, t);
      adjust = 0;
      writeByte(a + 1, (v >> 8) & 0xff, t);
    }
    public void resync(final long t) {}
  }

  /** Advances the beam before video RAM changes (does not store anything). */
  private class VramHook implements MemoryBus.BusReader
  {
    public void writeByte(final int a, final int v, final long t)
    { if (a >= 0x7000 && a < 0x7800) {
        beam.beforeVramWrite(accessClock(0));
        if (vramProbe != null) vramProbe.write(accessClock(0), a, v);
      } }
    public void writeShort(final int a, final int v, final long t)
    { if (a >= 0x7000 && a < 0x7800) beam.beforeVramWrite(accessClock(-3)); }
    public void resync(final long t) {}
  }

  public BeamVZ(final File osRom, final File dosRom, final BlockDevice card)
    throws IOException
  {
    final boolean sd = dosRom != null;
    mem.addWriter(new FileROMMemory(osRom, 0, 0x4000));
    ram = new RAMMemory(0x7000, sd ? 0x9000 - 0x7000 : 0x9000);
    mem.addReader(new VramHook());       // must precede the RAM (bus 'reader' = takes writes)
    mem.addReader(ram);
    mem.addWriter(ram);
    final int[] charset = new emulator.z80.ROMMemory(
      (Class<? extends Object>)VideoPanel.class, "charset.rom", 0, 3072)
      .getByteArray();
    beam = new Beam(ram.getByteArray(), charset);
    final Io io = new Io();
    mem.addReader(io);
    mem.addWriter(io);
    if (sd) {
      loader = new SDLoader(false);
      loader.loadEprom(dosRom);
      if (card != null) loader.insertCard(new SDCard(card, false));
      final SDLoader.MemoryView mv = loader.new MemoryView();
      mem.addReader(mv);
      mem.addWriter(mv);
      final SDLoader.PortView pv = loader.new PortView();
      ports.addReader(pv);
      ports.addWriter(pv);
    } else {
      loader = null;
    }
    cpu = new Z80(mem, ports);
    nextKeyTick = 0;
  }

  private static int keyCodeOf(final char c)
  {
    if (c >= 'A' && c <= 'Z') return KeyEvent.VK_A + (c - 'A');
    if (c >= '0' && c <= '9') return KeyEvent.VK_0 + (c - '0');
    switch (c) {
    case ' ': return KeyEvent.VK_SPACE;
    case '"': return KeyEvent.VK_QUOTEDBL;
    case '.': return KeyEvent.VK_PERIOD;
    case ',': return KeyEvent.VK_COMMA;
    case '\n': return KeyEvent.VK_ENTER;
    default: throw new IllegalArgumentException("no key for '" + c + "'");
    }
  }

  public void type(final String s)
  {
    for (final char c : s.toUpperCase().toCharArray()) typeQueue.add(c);
  }

  private void keyboardTick()
  {
    if (held == null) {
      if (!typeQueue.isEmpty() && keyPhase == 0) {
        held = KeyboardMatrix.getKeysByKeyCode(keyCodeOf(typeQueue.poll()));
        for (final KeyboardMatrix.Key k : held) matrix.setSelected(k, true);
        keyPhase = 3;
      } else if (keyPhase > 0) {
        keyPhase--;
      }
    } else if (--keyPhase == 0) {
      for (final KeyboardMatrix.Key k : held) matrix.setSelected(k, false);
      held = null;
      keyPhase = 3;
    }
  }

  /** Runs for the given number of CPU T-states. */
  public void runCycles(final long n) throws Exception
  {
    final long target = cpu.getWallClockCycles() + n;
    final long frameLen = (long)beam.lines * Beam.CLOCKS_PER_LINE;
    if (nextKeyTick == 0) nextKeyTick = cpu.getWallClockCycles() + frameLen;
    while (cpu.getWallClockCycles() < target) {
      final CPU.ConcreteOperation op = cpu.fetchNextOperation();
      op.execute();
      final long c = cpu.getWallClockCycles();
      beam.advanceTo(c);
      cpu.setIRQLine(!beam.fs(c));
      if (c >= nextKeyTick) {
        keyboardTick();
        nextKeyTick += frameLen;
      }
    }
  }

  public void runFrames(final int n) throws Exception
  {
    runCycles((long)n * beam.lines * Beam.CLOCKS_PER_LINE);
  }

  public int peek(final int a) { return mem.readByte(a, 0) & 0xff; }

  public void savePng(final String path) throws IOException
  {
    final BufferedImage img = new BufferedImage(Beam.WIDTH, Beam.HEIGHT,
                                                BufferedImage.TYPE_INT_RGB);
    img.setRGB(0, 0, Beam.WIDTH, Beam.HEIGHT, beam.getPixels(), 0, Beam.WIDTH);
    ImageIO.write(img, "png", new File(path));
  }
}
