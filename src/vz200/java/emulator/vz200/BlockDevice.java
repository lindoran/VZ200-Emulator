package emulator.vz200;

import java.io.IOException;

/**
 * A 512-byte-sector block device backing the emulated SD card.
 */
public interface BlockDevice
{
  int SECTOR_SIZE = 512;

  long getSectorCount();

  /** Reads sector {@code lba} into {@code buf} (exactly 512 bytes). */
  void readSector(long lba, byte[] buf) throws IOException;

  /** Writes {@code buf} (exactly 512 bytes) to sector {@code lba}. */
  void writeSector(long lba, byte[] buf) throws IOException;

  /** Pushes any pending changes to the backing store. */
  void flush() throws IOException;

  String describe();
}
