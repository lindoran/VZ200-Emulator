package emulator.vz200;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw SD card image file (MBR + FAT32, i.e. a "dd" dump of a card).
 * In read-only mode, writes are kept in a RAM overlay and the image
 * file is never modified.
 */
public class ImageBlockDevice implements BlockDevice
{
  private final File file;
  private final RandomAccessFile raf;
  private final boolean readOnly;
  private final long sectors;
  private final Map<Long, byte[]> overlay = new HashMap<Long, byte[]>();

  public ImageBlockDevice(final File file, final boolean readOnly)
    throws IOException
  {
    this.file = file;
    this.readOnly = readOnly;
    raf = new RandomAccessFile(file, readOnly ? "r" : "rw");
    sectors = raf.length() / SECTOR_SIZE;
    if (sectors < 1) {
      raf.close();
      throw new IOException("SD image too small: " + file);
    }
  }

  public long getSectorCount() { return sectors; }

  public synchronized void readSector(final long lba, final byte[] buf)
    throws IOException
  {
    final byte[] o = overlay.get(lba);
    if (o != null) {
      System.arraycopy(o, 0, buf, 0, SECTOR_SIZE);
      return;
    }
    raf.seek(lba * SECTOR_SIZE);
    raf.readFully(buf, 0, SECTOR_SIZE);
  }

  public synchronized void writeSector(final long lba, final byte[] buf)
    throws IOException
  {
    if (readOnly) {
      final byte[] copy = new byte[SECTOR_SIZE];
      System.arraycopy(buf, 0, copy, 0, SECTOR_SIZE);
      overlay.put(lba, copy);
      return;
    }
    raf.seek(lba * SECTOR_SIZE);
    raf.write(buf, 0, SECTOR_SIZE);
  }

  public synchronized void flush() throws IOException
  {
    if (!readOnly) {
      raf.getFD().sync();
    }
  }

  public String describe()
  {
    return "image " + file + " (" + sectors + " sectors" +
      (readOnly ? ", writes discarded at exit" : "") + ")";
  }
}
