package emulator.vz200;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Presents a host folder as an MBR/FAT32 SD card.
 *
 * At start-up the folder (and sub-folders) is laid out as a fresh FAT32
 * volume in memory.  Only names that already are valid 8.3 names are
 * exported; anything else is skipped with a warning (VZDOS cannot open
 * long names anyway).  When the guest writes to the card, the changed
 * volume is read back and files that are new or whose contents changed
 * are written to the host folder (never deleted).  With writeBack=false
 * the card is a throw-away RAM copy.
 */
public class FolderBlockDevice implements BlockDevice
{
  private static final int SS = SECTOR_SIZE;
  private static final int MBR_PART_START = 2048;
  private static final int RESERVED = 32;
  private static final int MIN_CLUSTERS = 65525;
  private static final long MAX_IMAGE_BYTES = 1L << 30;
  private static final Pattern NAME83 =
    Pattern.compile("[A-Z0-9_$~!#%&'()@^{}\\-]{1,8}(\\.[A-Z0-9_$~!#%&'()@^{}\\-]{1,3})?");
  private static final long WRITEBACK_DELAY_MS = 400;

  private static final class Node
  {
    String name;                // upper-case 8.3 form, e.g. "FILE1.DAT"
    File host;
    boolean dir;
    long size;
    int cluster;
    int clusterCount;
    final List<Node> kids = new ArrayList<Node>();
  }

  private final File rootDir;
  private final boolean writeBack;
  private final byte[] img;
  private final int spc;
  private final int partStart;
  private final int totalClusters;
  private final int fatSectors;
  private final int partSectors;
  private final long totalSectors;
  private int dirtyWrites;

  private final ScheduledExecutorService timer;
  private ScheduledFuture<?> pendingSync;

  /**
   * @param mbr true: MBR partition table with the volume at sector 2048;
   *            false (default elsewhere): no partition table, the FAT32
   *            boot sector is sector 0 (what Windows produces for a
   *            wiped / "superfloppy" card).
   */
  public FolderBlockDevice(final File dir, final boolean writeBack,
                           final boolean mbr)
    throws IOException
  {
    this.partStart = mbr ? MBR_PART_START : 0;
    if (!dir.isDirectory()) {
      throw new IOException("not a folder: " + dir);
    }
    this.rootDir = dir;
    this.writeBack = writeBack;

    final Node root = new Node();
    root.dir = true;
    root.name = "";
    root.host = dir;
    scan(root);

    int chosen = 1;
    int needed = 0;
    for (int s = 1; s <= 64; s <<= 1) {
      chosen = s;
      needed = countClusters(root, s, true);
      if (needed * 5L / 4 + 256 <= MIN_CLUSTERS) {
        break;
      }
    }
    spc = chosen;
    totalClusters = (int)Math.max(MIN_CLUSTERS + 75,
                                  needed * 5L / 4 + 256);
    fatSectors = ((totalClusters + 2) * 4 + SS - 1) / SS;
    partSectors = RESERVED + 2 * fatSectors + totalClusters * spc;
    totalSectors = (long)partStart + partSectors;
    if (totalSectors * SS > MAX_IMAGE_BYTES) {
      throw new IOException("folder contents too large for an in-memory card");
    }
    img = new byte[(int)(totalSectors * SS)];
    build(root);

    timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(final Runnable r) {
          final Thread t = new Thread(r, "sd-writeback");
          t.setDaemon(true);
          return t;
        }
      });
    if (writeBack) {
      Runtime.getRuntime().addShutdownHook(new Thread("sd-writeback-exit") {
          public void run() {
            try { syncOut(); } catch (final Throwable t) {
              System.out.println("[sd] final write-back failed: " + t);
            }
          }
        });
    }
  }

  // ---------------------------------------------------------------- scan

  private static void scan(final Node dir)
  {
    final File[] list = dir.host.listFiles();
    if (list == null) {
      return;
    }
    Arrays.sort(list);
    final List<String> seen = new ArrayList<String>();
    for (final File f : list) {
      final String up = f.getName().toUpperCase();
      if (f.getName().startsWith(".")) {
        continue;
      }
      if (!NAME83.matcher(up).matches() || (f.isDirectory() && up.indexOf('.') >= 0)) {
        System.out.println("[sd] skipping '" + f.getName() +
                           "': not a valid 8.3 name");
        continue;
      }
      if (seen.contains(up)) {
        System.out.println("[sd] skipping '" + f.getName() +
                           "': duplicate of another name ignoring case");
        continue;
      }
      seen.add(up);
      final Node n = new Node();
      n.name = up;
      n.host = f;
      n.dir = f.isDirectory();
      if (n.dir) {
        scan(n);
      } else if (f.isFile()) {
        n.size = f.length();
      } else {
        continue;
      }
      dir.kids.add(n);
    }
  }

  private static int countClusters(final Node n, final int spc, final boolean isRoot)
  {
    final int cb = spc * SS;
    int total = 0;
    if (n.dir) {
      final int entries = n.kids.size() + (isRoot ? 0 : 2);
      total = Math.max(1, (entries * 32 + cb - 1) / cb);
      for (final Node k : n.kids) {
        total += countClusters(k, spc, false);
      }
    } else {
      total = (int)((n.size + cb - 1) / cb);
    }
    return total;
  }

  // --------------------------------------------------------------- build

  private int nextFree = 2;

  private int sectorOfCluster(final int c)
  {
    return partStart + RESERVED + 2 * fatSectors + (c - 2) * spc;
  }

  private int fatGet(final int c)
  {
    final int o = (partStart + RESERVED) * SS + c * 4;
    return (le32(img, o)) & 0x0fffffff;
  }

  private void fatSet(final int c, final int v)
  {
    for (int copy = 0; copy < 2; copy++) {
      final int o = (partStart + RESERVED + copy * fatSectors) * SS + c * 4;
      put32(img, o, v);
    }
  }

  private static int le16(final byte[] b, final int o)
  {
    return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
  }

  private static int le32(final byte[] b, final int o)
  {
    return le16(b, o) | (le16(b, o + 2) << 16);
  }

  private static void put16(final byte[] b, final int o, final int v)
  {
    b[o] = (byte)v;
    b[o + 1] = (byte)(v >> 8);
  }

  private static void put32(final byte[] b, final int o, final int v)
  {
    put16(b, o, v);
    put16(b, o + 2, v >>> 16);
  }

  private void allocate(final Node n, final boolean isRoot)
  {
    final int cb = spc * SS;
    if (n.dir) {
      final int entries = n.kids.size() + (isRoot ? 0 : 2);
      n.clusterCount = Math.max(1, (entries * 32 + cb - 1) / cb);
    } else {
      n.clusterCount = (int)((n.size + cb - 1) / cb);
    }
    if (n.clusterCount > 0) {
      n.cluster = nextFree;
      for (int i = 0; i < n.clusterCount; i++) {
        fatSet(nextFree + i, i == n.clusterCount - 1 ? 0x0fffffff : nextFree + i + 1);
      }
      nextFree += n.clusterCount;
    }
    for (final Node k : n.kids) {
      allocate(k, false);
    }
  }

  private void build(final Node root) throws IOException
  {
    // MBR (only for a partitioned card)
    final int mbr = 0;
    if (partStart != 0) {
    img[mbr + 446] = (byte)0x80;
    img[mbr + 446 + 1] = 0x00; img[mbr + 446 + 2] = 0x01; img[mbr + 446 + 3] = 0x00;
    img[mbr + 446 + 4] = 0x0c;
    img[mbr + 446 + 5] = (byte)0xfe; img[mbr + 446 + 6] = (byte)0xff; img[mbr + 446 + 7] = (byte)0xff;
    put32(img, mbr + 446 + 8, partStart);
    put32(img, mbr + 446 + 12, partSectors);
    img[510] = 0x55; img[511] = (byte)0xaa;
    }

    // Volume boot record (+ FSInfo, + backups at sector 6/7)
    final int vbr = partStart * SS;
    img[vbr] = (byte)0xeb; img[vbr + 1] = 0x58; img[vbr + 2] = (byte)0x90;
    System.arraycopy("MSWIN4.1".getBytes(), 0, img, vbr + 3, 8);
    put16(img, vbr + 11, SS);
    img[vbr + 13] = (byte)spc;
    put16(img, vbr + 14, RESERVED);
    img[vbr + 16] = 2;
    put16(img, vbr + 17, 0);
    put16(img, vbr + 19, 0);
    img[vbr + 21] = (byte)0xf8;
    put16(img, vbr + 22, 0);
    put16(img, vbr + 24, 63);
    put16(img, vbr + 26, 255);
    put32(img, vbr + 28, partStart);
    put32(img, vbr + 32, partSectors);
    put32(img, vbr + 36, fatSectors);
    put16(img, vbr + 40, 0);
    put16(img, vbr + 42, 0);
    put32(img, vbr + 44, 2);
    put16(img, vbr + 48, 1);
    put16(img, vbr + 50, 6);
    img[vbr + 64] = (byte)0x80;
    img[vbr + 66] = 0x29;
    put32(img, vbr + 67, 0x565a3230);
    System.arraycopy("NO NAME    ".getBytes(), 0, img, vbr + 71, 11);
    System.arraycopy("FAT32   ".getBytes(), 0, img, vbr + 82, 8);
    img[vbr + 510] = 0x55; img[vbr + 511] = (byte)0xaa;

    fatSet(0, 0x0ffffff8);
    fatSet(1, 0x0fffffff);
    allocate(root, true);
    if (root.cluster != 2) {
      throw new IOException("internal error: root cluster is " + root.cluster);
    }
    writeNode(root, null, true);

    final int fsi = vbr + SS;
    put32(img, fsi, 0x41615252);
    put32(img, fsi + 484, 0x61417272);
    put32(img, fsi + 488, totalClusters - (nextFree - 2));
    put32(img, fsi + 492, nextFree);
    put32(img, fsi + 508, 0xaa550000);
    System.arraycopy(img, vbr, img, vbr + 6 * SS, SS);
    System.arraycopy(img, fsi, img, vbr + 7 * SS, SS);

    System.out.printf("[sd] folder %s -> FAT32 volume: %d clusters of %d bytes, " +
                      "%d used%n", rootDir, totalClusters, spc * SS,
                      nextFree - 2);
  }

  private void writeNode(final Node n, final Node parent, final boolean isRoot)
    throws IOException
  {
    final int cb = spc * SS;
    if (n.dir) {
      final byte[] d = new byte[n.clusterCount * cb];
      int p = 0;
      if (!isRoot) {
        p = dirEntry(d, p, ".          ", 0x10, n.cluster, 0, n.host);
        p = dirEntry(d, p, "..         ", 0x10,
                     parent == null || parent.cluster == 2 && parent.name.isEmpty() ? 0 : parent.cluster,
                     0, n.host);
      }
      for (final Node k : n.kids) {
        p = dirEntry(d, p, shortName(k.name), k.dir ? 0x10 : 0x20, k.cluster,
                     k.size, k.host);
      }
      copyToClusters(n.cluster, d, d.length);
      for (final Node k : n.kids) {
        writeNode(k, n, false);
      }
    } else if (n.clusterCount > 0) {
      final byte[] d = Files.readAllBytes(n.host.toPath());
      copyToClusters(n.cluster, d, (int)Math.min(d.length, n.size));
    }
  }

  private static String shortName(final String name)
  {
    final int dot = name.indexOf('.');
    final String base = dot < 0 ? name : name.substring(0, dot);
    final String ext = dot < 0 ? "" : name.substring(dot + 1);
    final StringBuilder sb = new StringBuilder(base);
    while (sb.length() < 8) sb.append(' ');
    sb.append(ext);
    while (sb.length() < 11) sb.append(' ');
    return sb.toString();
  }

  private static int dirEntry(final byte[] d, final int p, final String name11,
                              final int attr, final int cluster,
                              final long size, final File host)
  {
    for (int i = 0; i < 11; i++) {
      d[p + i] = (byte)name11.charAt(i);
    }
    d[p + 11] = (byte)attr;
    final java.util.Calendar c = java.util.Calendar.getInstance();
    c.setTimeInMillis(host.lastModified());
    final int year = Math.max(1980, c.get(java.util.Calendar.YEAR));
    final int time = (c.get(java.util.Calendar.HOUR_OF_DAY) << 11) |
      (c.get(java.util.Calendar.MINUTE) << 5) |
      (c.get(java.util.Calendar.SECOND) / 2);
    final int date = ((year - 1980) << 9) |
      ((c.get(java.util.Calendar.MONTH) + 1) << 5) |
      c.get(java.util.Calendar.DAY_OF_MONTH);
    put16(d, p + 14, time);
    put16(d, p + 16, date);
    put16(d, p + 18, date);
    put16(d, p + 22, time);
    put16(d, p + 24, date);
    put16(d, p + 20, cluster >>> 16);
    put16(d, p + 26, cluster & 0xffff);
    put32(d, p + 28, (int)size);
    return p + 32;
  }

  private void copyToClusters(final int firstCluster, final byte[] data,
                              final int length)
  {
    int c = firstCluster;
    int off = 0;
    final int cb = spc * SS;
    while (off < length && c >= 2 && c < 0x0ffffff8) {
      final int n = Math.min(cb, length - off);
      System.arraycopy(data, off, img, sectorOfCluster(c) * SS, n);
      off += n;
      c = fatGet(c);
    }
  }

  // ------------------------------------------------------- block device

  public long getSectorCount() { return totalSectors; }

  public synchronized void readSector(final long lba, final byte[] buf)
    throws IOException
  {
    if (lba < 0 || lba >= totalSectors) {
      throw new IOException("LBA out of range: " + lba);
    }
    System.arraycopy(img, (int)(lba * SS), buf, 0, SS);
  }

  public synchronized void writeSector(final long lba, final byte[] buf)
    throws IOException
  {
    if (lba < 0 || lba >= totalSectors) {
      throw new IOException("LBA out of range: " + lba);
    }
    System.arraycopy(buf, 0, img, (int)(lba * SS), SS);
    dirtyWrites++;
    if (writeBack) {
      if (pendingSync != null) {
        pendingSync.cancel(false);
      }
      pendingSync = timer.schedule(new Runnable() {
          public void run() {
            try { syncOut(); } catch (final Throwable t) {
              System.out.println("[sd] write-back failed: " + t);
            }
          }
        }, WRITEBACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }

  public void flush() throws IOException
  {
    if (writeBack) {
      syncOut();
    }
  }

  /** Saves the current in-memory card as a raw image (e.g. to try on a real card). */
  public synchronized void saveImage(final File f) throws IOException
  {
    Files.write(f.toPath(), img);
  }

  public String describe()
  {
    return "folder " + rootDir + (partStart != 0 ? " (MBR, volume at sector " +
                                  partStart + ")" : " (no partition table)") +
      (writeBack ? " (changes written back)" :
                                  " (changes discarded)");
  }

  // ------------------------------------------------------- write-back

  /** Copies new / changed files from the in-memory volume to the folder. */
  public synchronized void syncOut() throws IOException
  {
    if (dirtyWrites == 0) {
      return;
    }
    dirtyWrites = 0;
    walkDir(2, rootDir, 0);
  }

  private byte[] readChain(final int first, final long limit)
  {
    final int cb = spc * SS;
    final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    int c = first;
    int guard = 0;
    long left = limit;
    while (c >= 2 && c < 0x0ffffff8 && c < totalClusters + 2 &&
           guard++ <= totalClusters && left > 0) {
      final int n = (int)Math.min(cb, left);
      bo.write(img, sectorOfCluster(c) * SS, n);
      left -= n;
      c = fatGet(c);
    }
    return bo.toByteArray();
  }

  private static File findIgnoreCase(final File dir, final String name)
  {
    final File[] list = dir.listFiles();
    if (list != null) {
      for (final File f : list) {
        if (f.getName().equalsIgnoreCase(name)) {
          return f;
        }
      }
    }
    return new File(dir, name);
  }

  private void walkDir(final int cluster, final File hostDir, final int depth)
    throws IOException
  {
    if (depth > 16) {
      return;
    }
    final byte[] d = readChain(cluster, Long.MAX_VALUE);
    for (int p = 0; p + 32 <= d.length; p += 32) {
      final int first = d[p] & 0xff;
      if (first == 0x00) {
        break;
      }
      final int attr = d[p + 11] & 0xff;
      if (first == 0xe5 || attr == 0x0f || (attr & 0x08) != 0) {
        continue;
      }
      final String base = new String(d, p, 8).trim();
      final String ext = new String(d, p + 8, 3).trim();
      if (base.equals(".") || base.equals("..")) {
        continue;
      }
      final String name = ext.isEmpty() ? base : base + "." + ext;
      final int cl = (le16(d, p + 20) << 16) | le16(d, p + 26);
      if ((attr & 0x10) != 0) {
        final File sub = findIgnoreCase(hostDir, name);
        if (!sub.exists() && !sub.mkdir()) {
          System.out.println("[sd] cannot create folder " + sub);
          continue;
        }
        walkDir(cl, sub, depth + 1);
      } else {
        final long size = le32(d, p + 28) & 0xffffffffL;
        final byte[] data = size == 0 ? new byte[0] : readChain(cl, size);
        final File f = findIgnoreCase(hostDir, name);
        if (f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), data)) {
          continue;
        }
        Files.write(f.toPath(), data);
        System.out.printf("[sd] wrote back %s (%d bytes)%n", f, data.length);
      }
    }
  }
}
