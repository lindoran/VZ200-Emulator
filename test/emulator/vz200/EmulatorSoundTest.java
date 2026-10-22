package emulator.vz200;

import java.io.File;
import java.lang.reflect.Field;
import emulator.z80.CPUControl;
import emulator.z80.MemoryBus;

/** Real emulator (needs a display, e.g. xvfb-run) -> speaker -> fake card. */
public class EmulatorSoundTest
{
  public static void main(String[] args) throws Exception
  {
    final FakeSink[] sinks = new FakeSink[8];
    final int[] n = {0};
    AudioDevices.defaultOpener = (id, ms, log) -> {
      FakeSink s = new FakeSink(44100, 2, 1.0); sinks[n[0]++] = s; return s; };
    VZ200.Options o = new VZ200.Options();
    o.osRom = new File(args[0]);
    VZ200 vz = new VZ200(o);
    Field f = VZ200.class.getDeclaredField("mainMemoryBus"); f.setAccessible(true);
    MemoryBus bus = (MemoryBus)f.get(vz);
    f = VZ200.class.getDeclaredField("cpuControl"); f.setAccessible(true);
    CPUControl cpu = (CPUControl)f.get(vz);
    // loop: LD A,$20 / LD ($6800),A / LD B,50 / DJNZ $ / LD A,$01 / LD ($6800),A / LD B,50 / DJNZ $ / JR loop
    int[] code = {0x3e,0x20, 0x32,0x00,0x68, 0x06,50, 0x10,0xfe,
                  0x3e,0x01, 0x32,0x00,0x68, 0x06,50, 0x10,0xfe, 0x18,0xec};
    for (int i = 0; i < code.length; i++) bus.writeByte(0x7a00 + i, code[i], 0);
    cpu.setPCValue(0x7a00);
    long tpc = cpu.getTimePerClockCycle();
    int cycles = 2 * (7 + 13 + 7 + 13 * 49 + 8) + 12;
    double expect = 1e9 / (cycles * (double)tpc);
    // execute() can miss its own RUNNING notification (pre-existing
    // race in CPUControlAutomaton), so start it from a helper thread
    Thread t = new Thread(cpu::execute); t.setDaemon(true); t.start();
    Thread.sleep(9000);
    FakeSink s = sinks[0];
    short[] d = s.samples();
    double fr = AudioPipelineTest.freq(d, 44100, d.length - 4000, 44100);
    int pk = AudioPipelineTest.peak(d, 44100, d.length - 4000);
    System.out.printf("samples=%d expected %.1f Hz measured %.1f Hz, peak %d%n", d.length, expect, fr, pk);
    AudioPipelineTest.check(Math.abs(fr - expect) / expect < 0.01, "emulated speaker pitch matches Z80 cycle count");
    AudioPipelineTest.check(pk > 20000, "audible amplitude");
    AudioPipelineTest.check(d.length > 44100 * 5, "continuous stream while CPU runs");
    System.exit(AudioPipelineTest.failures == 0 ? 0 : 1);
  }
}
