package com.foobnix.remote;

import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.model.BookCSS;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-level (memory + disk) block cache for one remote book.
 *
 * Disk layout: {@code <cachePath>/Remote/<cacheKey>/} containing
 * {@code data.bin} (blocks at index*BLOCK_SIZE), {@code blocks.bin} (one byte
 * per block: 1 = cached), {@code crc.bin} (4-byte CRC32 per block, format v2)
 * and {@code meta.json} (fmt / size / versionTag / fullyCached). A versionTag
 * or format change wipes the directory — cached bytes from a different file
 * version or cache format are never mixed into the new one.
 */
public class BlockCacheStore {

    /** On-disk cache format. Bumping wipes every existing remote cache once
     * (open() rebuilds from scratch): v2 adds per-block checksums — caches
     * written by v1 may contain truncated blocks that made books fail to
     * open forever, so they must not be trusted. */
    public static final int FORMAT_VERSION = 2;

    // Text formats (epub/fb2/txt/...) read the book mostly sequentially
    // during the first-open layout: 1MB blocks cut the per-request round
    // trips ~4x compared to 256KB, so the first screen appears much faster.
    // A block-size change wipes existing text caches once (acceptable).
    public static final int BLOCK_SIZE = 1024 * 1024;
    /** Page-based formats (PDF / CBZ / XPS) read SCATTERED small objects
     * (page tree, outline, xref) as often as sequential page data: 64KB
     * blocks keep the structural walk cheap (1MB blocks over-fetched ~16:1
     * — a 531MB book pulled 134MB just to count its pages), while the P2
     * prefetch window covers the sequential parts. A size change wipes
     * existing page-format caches once via the mismatch rebuild. */
    public static final int BLOCK_SIZE_PAGE_FORMAT = 64 * 1024;
    /** Memory LRU byte cap, tiered by device RAM (tech-spec §14 double
     * limit: blocks + bytes). Low-RAM devices keep the conservative 32MB;
     * mid-range get 64MB, large-RAM devices 128MB. */
    private static volatile long memLimitBytes = -1;

    private static long memLimitBytes() {
        long v = memLimitBytes;
        if (v >= 0) {
            return v;
        }
        synchronized (BlockCacheStore.class) {
            if (memLimitBytes >= 0) {
                return memLimitBytes;
            }
            long mb = 32;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        com.foobnix.LibreraApp.context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (am.isLowRamDevice() || mi.totalMem <= 2L * 1024 * 1024 * 1024) {
                        mb = 32;
                    } else if (mi.totalMem <= 4L * 1024 * 1024 * 1024) {
                        mb = 64;
                    } else {
                        mb = 128;
                    }
                }
            } catch (Exception e) {
                LOG.w(e);
            }
            android.util.Log.i("REMOTE", "block cache RAM limit: " + mb + "MB");
            memLimitBytes = mb * 1024L * 1024L;
            return memLimitBytes;
        }
    }

    /** Block-count cap derived from the byte cap (256KB base blocks; for
     * 1MB page-format blocks the byte cap binds first). */
    private static int memLimitBlocks() {
        return (int) (memLimitBytes() / BLOCK_SIZE);
    }
    /** Per-book disk cap for the progressive whole-book filler. */
    // per-book disk cap: 512MB matches the 500MB default total cache budget,
    // so a 200-300MB book fully caches and its whole-book layout / background
    // fill never falls into read-through (which re-downloaded blocks on every
    // scattered small read and made the layout crawl)
    public static final long PER_BOOK_LIMIT = 512L * 1024 * 1024;

    private final File dir;
    private final long fileSize;
    private final int blockCount;
    private final int blockSize;
    private final RandomAccessFile data;
    /** Per-block CRC32 (4 bytes per block); null when crc.bin is unusable. */
    private final RandomAccessFile crc;
    private final byte[] bitmap;
    private final Object lock = new Object();
    /** Approximate sum of the materialized block arrays held in {@link #mem}. */
    private long memBytes;

    /** Access-order LRU of materialized blocks (tail blocks are exact-size arrays). */
    private final LinkedHashMap<Long, byte[]> mem = new LinkedHashMap<Long, byte[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            // Evicted blocks stay on disk; only the RAM copy is dropped.
            if (size() > memLimitBlocks() || memBytes > memLimitBytes()) {
                memBytes -= eldest.getValue().length;
                return true;
            }
            return false;
        }
    };

    private String versionTag = "";
    private boolean fullyCached;
    private boolean closed;

    private BlockCacheStore(File dir, long fileSize, RandomAccessFile data, byte[] bitmap, boolean fullyCached,
                            int blockSize, String versionTag) {
        this.dir = dir;
        this.fileSize = fileSize;
        this.blockCount = (int) ((fileSize + blockSize - 1) / blockSize);
        this.blockSize = blockSize;
        this.data = data;
        this.bitmap = bitmap;
        this.fullyCached = fullyCached;
        this.versionTag = versionTag == null ? "" : versionTag;
        RandomAccessFile crcF = null;
        try {
            crcF = new RandomAccessFile(new File(dir, "crc.bin"), "rw");
        } catch (Exception e) {
            LOG.w(e);
        }
        this.crc = crcF;
    }

    public static File rootDir() {
        return new File(BookCSS.get().cachePath, "Remote");
    }

    /** Small in-memory LRU for blocks served through past the per-book cap:
     * scattered small reads of a non-persisted block must not re-download
     * the whole block every time. ~12 blocks (12MB at 1MB blocks). */
    // per-book instance (NOT static): the key is the bare block index, so a
    // static map let two books past their per-book cap serve each other's
    // blocks (silent cross-book data corruption)
    private final java.util.LinkedHashMap<Long, byte[]> READ_THROUGH =
            new java.util.LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, byte[]> eldest) {
                    return size() > 12;
                }
            };

    public byte[] getReadThrough(long idx) {
        synchronized (READ_THROUGH) {
            return READ_THROUGH.get(idx);
        }
    }

    public void putReadThrough(long idx, byte[] block) {
        synchronized (READ_THROUGH) {
            READ_THROUGH.put(idx, block);
        }
    }

    /**
     * Cache keys of sessions currently open: book-level LRU eviction must
     * never delete the cache of a book being read (its data.bin handle would
     * keep writing into an unlinked file and the session's cache would
     * silently vanish). */
    private static final java.util.Set<String> pinnedKeys =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    public static void pinKey(String cacheKey) {
        if (cacheKey != null) {
            pinnedKeys.add(cacheKey);
        }
    }

    public static void unpinKey(String cacheKey) {
        if (cacheKey != null) {
            pinnedKeys.remove(cacheKey);
        }
    }

    /**
     * Opens (or creates) the cache for one book version. When the stored
     * versionTag differs from {@code versionTag} — or the stored block size
     * differs from {@code blockSize} — the old directory is wiped.
     */
    public static BlockCacheStore open(String cacheKey, long fileSize, String versionTag, int blockSize)
            throws java.io.IOException {
        File dir = new File(rootDir(), cacheKey);
        File dataF = new File(dir, "data.bin");
        File bitmapF = new File(dir, "blocks.bin");
        File metaF = new File(dir, "meta.json");
        boolean fullyCached = false;
        int blockCount = (int) ((fileSize + blockSize - 1) / blockSize);

        if (dir.exists() && metaF.isFile()) {
            try {
                JSONObject m = new JSONObject(com.foobnix.android.utils.IO.readString(metaF));
                String storedTag = m.optString("versionTag");
                long storedSize = m.optLong("size", -1);
                int storedBlockSize = m.optInt("blockSize", BLOCK_SIZE);
                if (m.optInt("fmt", 1) == FORMAT_VERSION
                        && storedTag.equals(versionTag) && storedSize == fileSize && storedBlockSize == blockSize
                        && bitmapF.isFile() && bitmapF.length() >= blockCount
                        && dataF.isFile() && dataF.length() == fileSize) {
                    fullyCached = m.optBoolean("fullyCached", false);
                    RandomAccessFile data = new RandomAccessFile(dataF, "rw");
                    byte[] bitmap = new byte[blockCount];
                    FileInputStream in = new FileInputStream(bitmapF);
                    int read = in.read(bitmap);
                    in.close();
                    if (read < blockCount) {
                        data.close();
                        throw new IllegalStateException("bitmap truncated");
                    }
                    dir.setLastModified(System.currentTimeMillis()); // fresh LRU signal on open
                    return new BlockCacheStore(dir, fileSize, data, bitmap, fullyCached, blockSize,
                            storedTag);
                }
            } catch (Exception e) {
                LOG.w(e);
            }
            // version / block size changed or corrupted cache: wipe and start over
            com.foobnix.ext.CacheZipUtils.deleteDir(dir);
        }
        dir.mkdirs();
        dir.setLastModified(System.currentTimeMillis()); // fresh LRU signal on open
        RandomAccessFile data = new RandomAccessFile(dataF, "rw");
        data.setLength(fileSize);
        byte[] bitmap = new byte[blockCount];
        BlockCacheStore store = new BlockCacheStore(dir, fileSize, data, bitmap, false, blockSize, versionTag);
        store.persistMeta(versionTag);
        return store;
    }

    /**
     * Reopens an existing cache from disk without any network round-trip.
     * Returns null when the cache is absent or inconsistent (meta missing,
     * bitmap truncated, data file gone, or a fullyCached flag not backed by
     * a complete bitmap) — the caller falls back to the network path.
     */
    public static BlockCacheStore openExisting(String cacheKey) {
        File dir = new File(rootDir(), cacheKey);
        File dataF = new File(dir, "data.bin");
        File bitmapF = new File(dir, "blocks.bin");
        File metaF = new File(dir, "meta.json");
        if (!dir.isDirectory() || !metaF.isFile() || !dataF.isFile() || !bitmapF.isFile()) {
            return null;
        }
        try {
            JSONObject m = new JSONObject(com.foobnix.android.utils.IO.readString(metaF));
            long size = m.optLong("size", -1);
            int blockSize = m.optInt("blockSize", BLOCK_SIZE);
            boolean fullyCached = m.optBoolean("fullyCached", false);
            if (size <= 0 || blockSize <= 0) {
                return null;
            }
            if (m.optInt("fmt", 1) != FORMAT_VERSION) {
                return null; // old format: let open() rebuild it
            }
            int blockCount = (int) ((size + blockSize - 1) / blockSize);
            if (bitmapF.length() < blockCount) {
                return null;
            }
            byte[] bitmap = new byte[blockCount];
            FileInputStream in = new FileInputStream(bitmapF);
            int read = in.read(bitmap);
            in.close();
            if (read < blockCount) {
                return null;
            }
            int cached = 0;
            for (byte b : bitmap) {
                if (b == 1) {
                    cached++;
                }
            }
            // a "complete" flag must be backed by a complete bitmap, else the
            // offline open would hit a hole mid-book
            if (fullyCached && cached < blockCount) {
                return null;
            }
            dir.setLastModified(System.currentTimeMillis()); // fresh LRU signal on open
            RandomAccessFile data = new RandomAccessFile(dataF, "r");
            return new BlockCacheStore(dir, size, data, bitmap, fullyCached, blockSize,
                    m.optString("versionTag", ""));
        } catch (Exception e) {
            LOG.w(e);
            return null;
        }
    }

    /**
     * Cached share of a remote book, 0..100, for the shelf badge. -1 when
     * the book has no block cache at all (never opened online). Reads only
     * the tiny meta.json + blocks.bin, never data.bin.
     */
    public static int cachedPercent(String remotePath) {
        File dir = new File(rootDir(), RemoteBook.cacheKey(remotePath));
        File metaF = new File(dir, "meta.json");
        File bitmapF = new File(dir, "blocks.bin");
        if (!metaF.isFile()) {
            return -1;
        }
        try {
            JSONObject m = new JSONObject(com.foobnix.android.utils.IO.readString(metaF));
            if (m.optBoolean("fullyCached", false)) {
                return 100;
            }
            long size = m.optLong("size", -1);
            int blockSize = m.optInt("blockSize", BLOCK_SIZE);
            if (size <= 0 || blockSize <= 0 || !bitmapF.isFile()) {
                return -1;
            }
            int blockCount = (int) ((size + blockSize - 1) / blockSize);
            byte[] bitmap = new byte[blockCount];
            FileInputStream in = new FileInputStream(bitmapF);
            int read = in.read(bitmap);
            in.close();
            long cached = 0;
            for (int i = 0; i < read; i++) {
                if (bitmap[i] == 1) {
                    cached += Math.min(blockSize, size - (long) i * blockSize);
                }
            }
            return (int) (cached * 100 / size);
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return the versionTag stored in the book's meta.json, or null when absent. */
    public static String peekVersionTag(String cacheKey) {
        try {
            File metaF = new File(new File(rootDir(), cacheKey), "meta.json");
            if (!metaF.isFile()) {
                return null;
            }
            return new JSONObject(com.foobnix.android.utils.IO.readString(metaF)).optString("versionTag", null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stored book size from meta.json (never touches data.bin), or -1 when
     * the book has no block cache. Used by the info dialog for remote books
     * whose DB row was created before any successful open.
     */
    public static long peekSize(String remotePath) {
        try {
            File metaF = new File(new File(rootDir(), RemoteBook.cacheKey(remotePath)), "meta.json");
            if (!metaF.isFile()) {
                return -1;
            }
            return new JSONObject(com.foobnix.android.utils.IO.readString(metaF)).optLong("size", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private void persistMeta(String versionTag) {
        this.versionTag = versionTag == null ? "" : versionTag;
        try {
            JSONObject m = new JSONObject();
            m.put("fmt", FORMAT_VERSION);
            m.put("size", fileSize);
            m.put("versionTag", versionTag == null ? "" : versionTag);
            m.put("fullyCached", fullyCached);
            m.put("blockSize", blockSize);
            FileOutputStream out = new FileOutputStream(new File(dir, "meta.json"));
            out.write(m.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public boolean hasBlock(long index) {
        synchronized (lock) {
            return index >= 0 && index < blockCount && bitmap[(int) index] == 1;
        }
    }

    public int getBlockCount() {
        return blockCount;
    }

    /** Total size of the book (from meta.json when reopened offline). */
    public long getFileSize() {
        return fileSize;
    }

    /** Version fingerprint this cache belongs to (meta.json value). */
    public String getVersionTag() {
        return versionTag;
    }

    public boolean isFullyCached() {
        synchronized (lock) {
            return fullyCached;
        }
    }

    /**
     * @return the block bytes, or null when the block is not cached. The
     * returned array is exactly {@code blockLen(index)} long for tail blocks.
     */
    public byte[] getBlock(long index, String versionTag) {
        synchronized (lock) {
            if (index < 0 || index >= blockCount || bitmap[(int) index] != 1) {
                return null;
            }
            byte[] hit = mem.get(index);
            if (hit != null || mem.containsKey(index)) {
                return hit;
            }
            try {
                int len = blockLen(index);
                byte[] buf = new byte[len];
                synchronized (data) {
                    data.seek(index * blockSize);
                    data.readFully(buf);
                }
                if (!crcMatches(index, buf)) {
                    // the stored bytes fail their checksum (e.g. a truncated
                    // write): mark the block uncached so it is fetched again
                    // instead of feeding the reader corrupt data forever
                    bitmap[(int) index] = 0;
                    mem.remove(index);
                    persistBitmap();
                    android.util.Log.i("REMOTE", "block checksum mismatch, refetch idx="
                            + index + " book=" + dir.getName());
                    return null;
                }
                remember(index, buf);
                touchDir();
                return buf;
            } catch (Exception e) {
                LOG.e(e);
                return null;
            }
        }
    }

    /** Writes a fetched block into the cache. */
    public void putBlock(long index, byte[] blockData, String versionTag) {
        synchronized (lock) {
            if (closed || index < 0 || index >= blockCount) {
                return;
            }
            try {
                synchronized (data) {
                    data.seek(index * blockSize);
                    data.write(blockData, 0, blockData.length);
                }
                bitmap[(int) index] = 1;
                remember(index, blockData);
                writeCrc(index, blockData);
                persistBitmap();
                touchDir();
            } catch (Exception e) {
                LOG.e(e);
            }
        }
    }

    /** Rewrites blocks.bin (the durable cached/not-cached bitmap). */
    private void persistBitmap() {
        try {
            FileOutputStream out = new FileOutputStream(new File(dir, "blocks.bin"));
            out.write(bitmap);
            out.close();
        } catch (Exception e) {
            LOG.w(e);
        }
    }

    private void writeCrc(long index, byte[] blockData) {
        if (crc == null) {
            return;
        }
        try {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            c.update(blockData, 0, blockData.length);
            synchronized (crc) {
                crc.seek(index * 4L);
                crc.writeInt((int) c.getValue());
            }
        } catch (Exception e) {
            LOG.w(e);
        }
    }

    /** False when the stored checksum disagrees with the bytes on disk. */
    private boolean crcMatches(long index, byte[] buf) {
        if (crc == null) {
            return true;
        }
        try {
            synchronized (crc) {
                if (crc.length() < (index + 1) * 4L) {
                    return true; // no checksum recorded (must not happen in v2)
                }
                crc.seek(index * 4L);
                int stored = crc.readInt();
                java.util.zip.CRC32 c = new java.util.zip.CRC32();
                c.update(buf, 0, buf.length);
                return stored == (int) c.getValue();
            }
        } catch (Exception e) {
            return true; // checksum unreadable: trust the block
        }
    }

    /** Puts a block into the RAM LRU keeping the byte accounting exact. */
    private void remember(long index, byte[] blockData) {
        byte[] old = mem.put(index, blockData);
        if (old == null) {
            memBytes += blockData.length;
        } else {
            memBytes += blockData.length - old.length;
        }
    }

    private void touchDir() {
        try {
            dir.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
            // best effort LRU signal for book-level eviction
        }
    }

    public int getBlockSize() {
        return blockSize;
    }

    public int blockLen(long index) {
        long from = index * blockSize;
        return (int) Math.min(blockSize, fileSize - from);
    }

    public long cachedBytes() {
        synchronized (lock) {
            long total = 0;
            for (int i = 0; i < blockCount; i++) {
                if (bitmap[i] == 1) {
                    total += blockLen(i);
                }
            }
            return total;
        }
    }

    public void setFullyCached(String versionTag) {
        synchronized (lock) {
            fullyCached = true;
        }
        persistMeta(versionTag);
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            mem.clear();
            try {
                data.close();
            } catch (Exception e) {
                LOG.w(e);
            }
            try {
                if (crc != null) {
                    crc.close();
                }
            } catch (Exception e) {
                LOG.w(e);
            }
        }
    }

    public String dirPath() {
        return dir.getPath();
    }

    // ---------------- book-level management ----------------

    /** Deletes the whole remote-book cache (all protocols, all books).
     * Books with an open session are skipped: their data.bin handles would
     * keep writing into an unlinked file and the running reader would lose
     * its cache silently. */
    public static void clearAll() {
        File root = rootDir();
        File[] books = root.listFiles();
        if (books != null) {
            for (File book : books) {
                if (pinnedKeys.contains(book.getName())) {
                    continue;
                }
                com.foobnix.ext.CacheZipUtils.deleteDir(book);
            }
        }
        root.mkdirs();
    }

    /** Deletes one book's cache by key. */
    public static void clearBook(String cacheKey) {
        com.foobnix.ext.CacheZipUtils.deleteDir(new File(rootDir(), cacheKey));
    }

    /** Total bytes currently occupied by the remote-book cache. Counts the
     * REAL cached bytes: data.bin is preallocated to the full remote file
     * size (a 609MB book counts 609MB even with 8MB cached), which made
     * the evictor wipe freshly cached books minutes after creation. */
    public static long totalBytes() {
        File[] books = rootDir().listFiles();
        if (books == null) {
            return 0;
        }
        long total = 0;
        for (File book : books) {
            total += cachedBytes(book);
        }
        return total;
    }

    /** Real cached bytes of one book dir: cached block count from the
     * blocks.bin bitmap times the book's block size (every cached block is
     * a full block except possibly the file tail), plus the small sidecar
     * files. data.bin is deliberately not counted. */
    private static long cachedBytes(File bookDir) {
        if (bookDir == null || !bookDir.isDirectory()) {
            return 0;
        }
        long total = 0;
        try {
            File metaF = new File(bookDir, "meta.json");
            long blockSize = BLOCK_SIZE;
            if (metaF.isFile()) {
                try {
                    JSONObject m = new JSONObject(com.foobnix.android.utils.IO.readString(metaF));
                    blockSize = m.optLong("blockSize", BLOCK_SIZE);
                } catch (Exception e) {
                    LOG.w(e);
                }
                total += metaF.length();
            }
            File bitmapF = new File(bookDir, "blocks.bin");
            if (bitmapF.isFile()) {
                byte[] all = readFileBytes(bitmapF);
                int count = 0;
                for (byte b : all) {
                    if (b != 0) {
                        count++;
                    }
                }
                total += (long) count * blockSize;
            }
            File pm = new File(bookDir, "pagemap.bin");
            if (pm.isFile()) {
                total += pm.length();
            }
        } catch (Throwable t) {
            LOG.w(t);
        }
        return total;
    }

    private static byte[] readFileBytes(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] out = new byte[(int) f.length()];
            int got = 0;
            while (got < out.length) {
                int n = in.read(out, got, out.length - got);
                if (n <= 0) {
                    break;
                }
                got += n;
            }
            return out;
        } finally {
            in.close();
        }
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                total += dirSize(k);
            }
        }
        return total;
    }

    /**
     * Evicts least-recently-used books (oldest directory mtime first:
     * newest kept, oldest dropped) until the total cache fits
     * {@code maxBytes}. No time-based expiry — cached books live as long
     * as there is room. Called before a new block write burst.
     */
    public static synchronized void evict(long maxBytes) {
        try {
            File root = rootDir();
            File[] books = root.listFiles();
            if (books == null) {
                return;
            }
            if (totalBytes() <= maxBytes) {
                return;
            }
            books = root.listFiles();
            if (books == null) {
                return;
            }
            java.util.Arrays.sort(books, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File book : books) {
                if (totalBytes() <= maxBytes) {
                    break;
                }
                if (pinnedKeys.contains(book.getName())) {
                    continue; // never evict the book being read
                }
                long before = totalBytes();
                com.foobnix.ext.CacheZipUtils.deleteDir(book);
                android.util.Log.i("REMOTE", "evicted LRU book cache " + book.getName()
                        + ", freed " + ((before - totalBytes()) / (1024 * 1024)) + "MB");
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}
