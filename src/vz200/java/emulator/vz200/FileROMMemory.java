package emulator.vz200;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import emulator.z80.ROMMemory;

/**
 * ROM image read from a file on the host instead of a jar resource.
 */
public class FileROMMemory extends ROMMemory
{
  public FileROMMemory(final File file, final int baseAddress, final int size)
    throws IOException
  {
    super(baseAddress, load(file, size));
  }

  private static int[] load(final File file, final int size) throws IOException
  {
    final byte[] b = Files.readAllBytes(file.toPath());
    if (b.length != size) {
      throw new IOException(file + ": expected " + size + " bytes, found " +
                            b.length);
    }
    final int[] data = new int[size];
    for (int i = 0; i < size; i++) {
      data[i] = b[i] & 0xff;
    }
    return data;
  }
}
