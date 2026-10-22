package emulator.vz200;

import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;

import emulator.z80.CPU;
import emulator.z80.MemoryBus;
import emulator.z80.RAMMemory;
import emulator.z80.Z80;

/**
 * Headless VZ200 for tests: real Z80 core, real BASIC ROM, plain RAM,
 * a keyboard-matrix / vertical-sync stub, and (optionally) the SD loader.
 * No Swing windows, no sound, no cassette.
 */
public class HeadlessVZ
{
  private static final int CYCLES_PER_FRAME = 70000;     // 3.5 MHz / 50 Hz

  public final Z80 cpu;
  public final MemoryBus mem = new MemoryBus();
  public final MemoryBus ports = new MemoryBus();
  public final SDLoader loader;
  private final KeyboardMatrix matrix = new KeyboardMatrix();
  private final ArrayDeque<Character> typeQueue = new ArrayDeque<Character>();
  private List<KeyboardMatrix.Key> held;
  private int keyPhase;
  private long cycles;
  private int hsToggle;
  public long frames;
  public int latch;                                  // last write to $6800-$6FFF

  private class IOStub implements MemoryBus.BusReader, MemoryBus.BusWriter
  {
    public int readByte(final int a, final long t)
    {
      if (a < 0x6800 || a > 0x6fff) return 0xff;
      hsToggle ^= 1;
      return (matrix.read(a - 0x6800) & 0x3f) | (hsToggle << 7);
    }
    public int readShort(final int a, final long t)
    { return readByte(a, t) | (readByte(a + 1, t) << 8); }
    public void writeByte(final int a, final int v, final long t)
    { if (a >= 0x6800 && a <= 0x6fff) latch = v & 0xff; }
    public void writeShort(final int a, final int v, final long t)
    { writeByte(a, v, t); }
    public void resync(final long t) {}
  }

  public HeadlessVZ(final File osRom, final File dosRom, final BlockDevice card)
    throws IOException
  {
    this(osRom, dosRom, card, false);
  }

  /** @param vz300ram 18 KB of internal RAM ($7800-$B7FF) instead of 6 KB */
  public HeadlessVZ(final File osRom, final File dosRom, final BlockDevice card,
                    final boolean vz300ram)
    throws IOException
  {
    final boolean sd = dosRom != null;
    mem.addWriter(new FileROMMemory(osRom, 0, 0x4000));
    final RAMMemory ram = new RAMMemory(0x7000, sd ? (vz300ram ? 0xb800 : 0x9000) - 0x7000 : 0x9000);
    mem.addReader(ram);
    mem.addWriter(ram);
    final IOStub io = new IOStub();
    mem.addReader(io);
    mem.addWriter(io);
    if (sd) {
      loader = new SDLoader(Boolean.getBoolean("sd.trace"));
      loader.loadEprom(dosRom);
      if (card != null) loader.insertCard(new SDCard(card, Boolean.getBoolean("sd.trace")));
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
    // 3 frames down, 3 frames up
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

  public boolean idle() { return typeQueue.isEmpty() && held == null; }

  public void runFrames(final int n) throws Exception
  {
    for (int f = 0; f < n; f++) {
      final long target = cycles + CYCLES_PER_FRAME;
      while (cycles < target) {
        final CPU.ConcreteOperation op = cpu.fetchNextOperation();
        op.execute();
        cycles += op.getClockPeriods();
      }
      frames++;
      keyboardTick();
      cpu.requestIRQ();
    }
  }

  /** Runs until the CPU is about to execute {@code pc}; false after maxFrames. */
  public boolean runUntilPC(final int pc, final int maxFrames) throws Exception
  {
    for (int f = 0; f < maxFrames; f++) {
      final long target = cycles + CYCLES_PER_FRAME;
      while (cycles < target) {
        final CPU.ConcreteOperation op = cpu.fetchNextOperation();
        op.execute();
        cycles += op.getClockPeriods();
        if (cpu.getProgramCounter().getValue() == pc) {
          return true;
        }
      }
      frames++;
      keyboardTick();
      cpu.requestIRQ();
    }
    return false;
  }

  /** Runs until {@code needle} shows on screen, or fails after maxFrames. */
  public boolean runUntilScreenHas(final String needle, final int maxFrames)
    throws Exception
  {
    for (int f = 0; f < maxFrames; f += 5) {
      runFrames(5);
      if (screen().contains(needle)) return true;
    }
    return false;
  }

  public int peek(final int a) { return mem.readByte(a, 0) & 0xff; }

  public String screen()
  {
    final StringBuilder sb = new StringBuilder();
    for (int row = 0; row < 16; row++) {
      final StringBuilder line = new StringBuilder();
      for (int col = 0; col < 32; col++) {
        final int c = peek(0x7000 + row * 32 + col);
        final int v = c & 0x3f;
        line.append(v < 0x20 ? (char)(0x40 + v) : (char)v);
      }
      sb.append(line.toString().replaceAll(" +$", "")).append('\n');
    }
    return sb.toString();
  }
}
