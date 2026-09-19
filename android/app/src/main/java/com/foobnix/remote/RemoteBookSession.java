package com.foobnix.remote;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One open remote book: data source + block cache + prefetch / progressive
 * whole-book fill.
 *
 * Request priorities (implementation of the four-level model):
 * - P0/P1 (foreground reads, {@link #readAt}): blocking, always served first;
 * - P2 (prefetch next blocks): background executor, yields while any
 *   foreground read is in flight;
 * - P3 (whole-book filler for small books): same yielding rule, cancellable,
 *   marks the cache fullyCached when done.
 */
public class RemoteBookSession {

    public final String remotePath;
    public final long size;
    public final String versionTag;
    public final BlockCacheStore cache;

    private final RemoteDataSource source;
    private final String cacheKey;
    /** True once the network source has been opened; cache-first sessions
     * defer the open until the first cache miss actually needs the network. */
    private volatile boolean sourceOpened = true;

    /** In-flight / done markers so concurrent readers of one block wait once. */
    private final ConcurrentHashMap<Long, Object> blockLocks = new ConcurrentHashMap<Long, Object>();
    private final AtomicInteger fgReads = new AtomicInteger();
    private volatile boolean cancelled;

    /** Session open time; the filler waits for a foreground-quiet window
     * after this so the first screen owns the link. */
    private final long openMs = android.os.SystemClock.elapsedRealtime();
    private final java.util.concurrent.atomic.AtomicLong lastFgMs =
            new java.util.concurrent.atomic.AtomicLong();
    /** First block fetched by a foreground read: the filler starts here. */
    private volatile long firstFgBlock = -1;
    /** Block covering the most recent foreground read (the reading pos). */
    private volatile long fgPosBlock = -1;
    /** Sequential bulk reads coalesce into one request of this many blocks. */
    private static final int COALESCE_BLOCKS = 16;
    private volatile long lastFetchedBlock = -1;

    /** GLOBAL foreground activity across ALL sessions: a filler of any
     * book must yield while the user is reading/opening any other book
     * (cover/preview opens start several fillers that otherwise saturated
     * the link and starved the book the user actually tapped). */
    private static final java.util.concurrent.atomic.AtomicInteger fgReadsGlobal =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong lastFgGlobal =
            new java.util.concurrent.atomic.AtomicLong();

    /** Device-visible block-fetch statistics (see noteBlockStat). */
    private final java.util.concurrent.atomic.AtomicLong statBlocks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statFail = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong statNanos = new java.util.concurrent.atomic.AtomicLong();
    private volatile long statMaxMs;
    private volatile long statLastLog;

    private final ExecutorService prefetch = Executors.newFixedThreadPool(PREFETCH_LANES, r -> {
        Thread t = new Thread(r, "RemotePrefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private Thread filler;
    /** Set while another book is being opened by the user: fill/prefetch
     * lanes of THIS session pause so the opening book owns the link. */
    public volatile boolean backgroundPaused = false;

    private void waitWhileBackgroundPaused() {
        while (backgroundPaused && !cancelled) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
    private volatile long prefetchWindowStart = -1;
    private volatile long prefetchWindowEnd = -1;
    /** True when the cached copy belongs to an older version of the file. */
    public boolean versionChanged;

    private RemoteBookSession(String remotePath, RemoteDataSource source, BlockCacheStore cache,
                              long size, String versionTag, String cacheKey) {
        this.remotePath = remotePath;
        this.source = source;
        this.cache = cache;
        this.size = size;
        this.versionTag = versionTag;
        this.cacheKey = cacheKey;
        // book-level cache eviction must never delete this session's cache
        // while the book is open
        BlockCacheStore.pinKey(cacheKey);
        lastFgMs.set(openMs);
    }

    /**
     * Opens the remote source and attaches (or creates) the block cache.
     * Network errors propagate as IOException; the caller decides between
     * retry / fallback-to-download. Page-based formats (PDF / CBZ / XPS)
     * use 1MB blocks, everything else 256KB (tech-spec §7.1).
     */
    public static RemoteBookSession open(String remotePath, RemoteDataSource source) throws IOException {
        source.open();
        long size = source.size();
        if (size <= 0) {
            source.close();
            throw new IOException("Remote file is empty or size unknown");
        }
        String versionTag = source.versionTag();
        String cacheKey = RemoteBook.cacheKey(remotePath);
        int blockSize = isPageFormat(RemoteBook.getExt(remotePath))
                ? BlockCacheStore.BLOCK_SIZE_PAGE_FORMAT : BlockCacheStore.BLOCK_SIZE;
        // peek before open(): BlockCacheStore.open wipes the dir on a
        // version mismatch, the flag must reflect the pre-open state
        String cachedTag = BlockCacheStore.peekVersionTag(cacheKey);
        RemoteBookSession session = new RemoteBookSession(remotePath, source,
                BlockCacheStore.open(cacheKey, size, versionTag, blockSize), size, versionTag, cacheKey);
        session.versionChanged = cachedTag != null && !cachedTag.equals(versionTag);
        return session;
    }

    /**
     * Opens a book without touching the network (offline reading), from the
     * block cache alone. A FULLY cached book reads exactly like an online
     * one; a PARTIAL cache also opens in degraded mode — cached pages serve
     * normally and uncached ones fail their render (blank) instead of the
     * whole open being refused (books over the per-book cap can never reach
     * fullyCached, so demanding it made them unopenable offline forever).
     * Returns null when no usable cache exists for the path — the caller
     * then falls back to the network / error path.
     */
    public static RemoteBookSession openOffline(String remotePath) {
        try {
            String cacheKey = RemoteBook.cacheKey(remotePath);
            BlockCacheStore cache = BlockCacheStore.openExisting(cacheKey);
            if (cache == null) {
                return null;
            }
            boolean full = cache.isFullyCached();
            long cachedMB = cache.cachedBytes() / (1024 * 1024);
            LOG.d("RemoteOffline open", remotePath, "full=" + full);
            android.util.Log.i("REMOTE", "offline open " + remotePath
                    + " full=" + full + " cached=" + cachedMB + "MB");
            return new RemoteBookSession(remotePath, new LocalBlockDataSource(cache), cache,
                    cache.getFileSize(), cache.getVersionTag(), cacheKey);
        } catch (Exception e) {
            LOG.w(e);
            return null;
        }
    }

    /**
     * Cache-first session: the block cache already exists, so the session
     * starts serving from it immediately — size/versionTag come from the
     * persisted cache meta (the book's key info) and the network source
     * stays UNOPENED until a missing block actually needs fetching.
     * Reopening a cached book no longer waits on the network probe before
     * its first cached page can render.
     */
    public static RemoteBookSession openCachedFirst(String remotePath, RemoteDataSource source,
                                                    BlockCacheStore cache) {
        String cacheKey = RemoteBook.cacheKey(remotePath);
        RemoteBookSession session = new RemoteBookSession(remotePath, source, cache,
                cache.getFileSize(), cache.getVersionTag(), cacheKey);
        session.sourceOpened = false;
        return session;
    }

    /** Page-based formats read large contiguous runs → bigger blocks/window. */
    public static boolean isPageFormat(String ext) {
        return "pdf".equals(ext) || "cbz".equals(ext) || "xps".equals(ext)
                || "oxps".equals(ext) || "djvu".equals(ext);
    }

    public String getCacheKey() {
        return cacheKey;
    }

    /** False only for WebDAV servers that ignore Range headers (open probe). */
    public boolean isRangeSupported() {
        return source.supportsRange();
    }

    /** Foreground random read (P0/P1). Returns 0 at EOF. */
    public int readAt(long offset, byte[] buffer, int off, int len) throws IOException {
        if (cancelled) {
            throw new IOException("Session closed");
        }
        if (offset >= size) {
            return 0;
        }
        len = (int) Math.min(len, size - offset);
        fgReads.incrementAndGet();
        fgReadsGlobal.incrementAndGet();
        long now = android.os.SystemClock.elapsedRealtime();
        lastFgMs.set(now);
        lastFgGlobal.set(now);
        if (firstFgBlock < 0) {
            firstFgBlock = offset / cache.getBlockSize();
        }
        try {
            int total = 0;
            final int bs = cache.getBlockSize();
            while (total < len) {
                long pos = offset + total;
                long idx = pos / bs;
                int inOff = (int) (pos % bs);
                int want = (int) Math.min(len - total, cache.blockLen(idx) - inOff);
                if (want <= 0) {
                    break;
                }
                byte[] block = cache.getBlock(idx, versionTag);
                if (block == null) {
                    block = fetchBlock(idx);
                    if (block == null) {
                        // a null block is a source failure, not EOF: report it
                        // instead of feeding MuPDF a silently truncated document
                        android.util.Log.i("REMOTE", "readAt block fetch failed offset="
                                + pos + " idx=" + idx);
                        throw new IOException("Remote block read failed at offset " + pos);
                    }
                }
                int n = Math.min(want, block.length - inOff);
                if (n <= 0) {
                    break;
                }
                System.arraycopy(block, inOff, buffer, off + total, n);
                total += n;
            }
            if (total > 0) {
                fgPosBlock = (offset + total - 1) / cache.getBlockSize();
                schedulePrefetch((offset + total - 1) / cache.getBlockSize() + 1);
                maybeStartFiller();
            }
            return total;
        } finally {
            fgReads.decrementAndGet();
            fgReadsGlobal.decrementAndGet();
        }
    }

    /** Cache-first sessions defer source.open() until the first fetch. */
    private void ensureSourceOpen() throws IOException {
        if (!sourceOpened) {
            synchronized (this) {
                if (!sourceOpened) {
                    source.open();
                    sourceOpened = true;
                }
            }
        }
    }

    /** Fetches one full block from the remote source and caches it. */
    private byte[] fetchBlock(long idx) throws IOException {
        if (cache.hasBlock(idx)) {
            return cache.getBlock(idx, versionTag);
        }
        final int n = 1; /* placeholder to keep the guard below readable */
        Object lock = blockLocks.computeIfAbsent(idx, k -> new Object());
        synchronized (lock) {
            byte[] block = cache.getBlock(idx, versionTag);
            if (block != null) {
                return block;
            }
            if (cache.cachedBytes() >= perBookLimit() && !cache.hasBlock(idx)) {
                // per-book disk cap reached: serve through without persisting;
                // keep a small in-memory LRU so scattered small reads of the
                // same block don't re-download it on every access
                byte[] mem = cache.getReadThrough(idx);
                if (mem != null) {
                    return mem;
                }
                byte[] raw = readRawBlock(idx);
                if (raw != null) {
                    cache.putReadThrough(idx, raw);
                }
                return raw;
            }
            // sequential continuation of a bulk read: pull a coalesced
            // range (ONE request) so 64KB blocks never make sequential
            // content reads request-bound; scattered structural reads stay
            // at 64KB
            final boolean sequential = lastFetchedBlock == idx - 1;
            lastFetchedBlock = idx;
            final long left = cache.getBlockCount() - idx;
            if (sequential && left >= COALESCE_BLOCKS && source.supportsRange()) {
                byte[][] blocks = readCoalesced(idx, COALESCE_BLOCKS);
                if (blocks[0] != null) {
                    for (int i = 0; i < COALESCE_BLOCKS && blocks[i] != null; i++) {
                        if (!cache.hasBlock(idx + i)) {
                            BlockCacheStore.evict(evictTarget());
                            cache.putBlock(idx + i, blocks[i], versionTag);
                        }
                    }
                    return blocks[0];
                }
            }
            byte[] raw = readRawBlock(idx);
            if (raw == null) {
                return null;
            }
            BlockCacheStore.evict(evictTarget());
            cache.putBlock(idx, raw, versionTag);
            return raw;
        }
    }

    /**
     * Reads {@code count} consecutive blocks in one shot and splits them
     * per block. Only full blocks are returned; a {@code null} entry means
     * "not enough data arrived" (the caller falls back / fails).
     */
    private byte[][] readCoalesced(long firstIdx, int count) throws IOException {
        byte[][] out = new byte[count][];
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += cache.blockLen(firstIdx + i);
        }
        byte[] big = new byte[total];
        int got = 0;
        while (got < total) {
            if (cancelled) {
                throw new IOException("Session closed");
            }
            ensureSourceOpen();
            final long absOff = firstIdx * (long) cache.getBlockSize() + got;
            final int want = total - got;
            final int dstOff = got;
            final Integer n;
            try {
                n = RemoteRetry.execute(() ->
                        source.readAt(absOff, big, dstOff, want));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (Exception e) {
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            if (n == null || n <= 0) {
                break;
            }
            got += n;
        }
        int pos = 0;
        for (int i = 0; i < count && pos < got; i++) {
            int len = cache.blockLen(firstIdx + i);
            if (pos + len > got) {
                break; // partial block: leave it out, never cache short data
            }
            out[i] = new byte[len];
            System.arraycopy(big, pos, out[i], 0, len);
            pos += len;
        }
        return out;
    }

    private byte[] readRawBlock(long idx) throws IOException {
        final long statT0 = android.os.SystemClock.elapsedRealtime();
        boolean statOk = false;
        try {
            byte[] res = readRawBlock0(idx);
            statOk = res != null;
            return res;
        } finally {
            noteBlockStat(android.os.SystemClock.elapsedRealtime() - statT0, statOk);
        }
    }

    /** Device-visible aggregate of block fetches (tag REMOTE, <=1 line/10s). */
    private void noteBlockStat(long ms, boolean ok) {
        statBlocks.incrementAndGet();
        if (!ok) {
            statFail.incrementAndGet();
        }
        statNanos.addAndGet(ms * 1000000L);
        if (ms > statMaxMs) {
            statMaxMs = ms;
        }
        RemoteTimeline.markOnce("firstBlock", "first cache block received ("
                + (cache.cachedBytes() / 1024) + "KB cached)");
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - statLastLog >= 10000 && statBlocks.get() > 0) {
            statLastLog = now;
            final long cnt = statBlocks.getAndSet(0);
            final long nanos = statNanos.getAndSet(0);
            final long max = statMaxMs;
            statMaxMs = 0;
            final long fails = statFail.getAndSet(0);
            RemoteTimeline.mark("blocks received: +" + cnt + " blocks fail=" + fails
                    + " totalCached=" + (cache.cachedBytes() / (1024 * 1024)) + "MB"
                    + " avg=" + (nanos / 1000000L / Math.max(1, cnt)) + "ms max=" + max + "ms");
        }
    }

    private byte[] readRawBlock0(long idx) throws IOException {
        int len = cache.blockLen(idx);
        byte[] buf = new byte[len];
        int got = 0;
        final long blockStart = idx * cache.getBlockSize();
        while (got < len) {
            if (cancelled) {
                throw new IOException("Session closed");
            }
            // transient network failures: retry with exponential backoff;
            // the data sources additionally reconnect a broken session
            // themselves (see SftpDataSource / SmbDataSource)
            final int off = got;
            final Integer n;
            try {
                n = RemoteRetry.execute(() -> {
                    if (cancelled) {
                        // fail fast instead of starting one more network call
                        // after abort(): a cancel must not wait a full timeout
                        throw new IOException("Session closed");
                    }
                    ensureSourceOpen();
                    return source.readAt(blockStart + off, buf, off, len - off);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } catch (Exception e) {
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException(e);
            }
            if (n == null || n <= 0) {
                break;
            }
            got += n;
        }
        if (got <= 0) {
            return null;
        }
        if (got < len) {
            // a short block is a broken transfer, never data: caching it fed
            // the reader a silently truncated document that never healed (a
            // truncated tail block = epub central directory -> "Document is
            // corrupted" on every later open). Report a failure instead.
            android.util.Log.i("REMOTE", "short block read idx=" + idx + " offset="
                    + blockStart + ": got " + got + " of " + len);
            return null;
        }
        return buf;
    }

    private static long cachedOfBook(BlockCacheStore c) {
        return c.cachedBytes();
    }

    private static long maxCacheBytes() {
        int mb = AppState.get().remoteCacheMaxMB;
        return (mb <= 0 ? 500 : mb) * 1024L * 1024L;
    }

    /**
     * Frees room BEFORE opening/reading ("keep new, drop old"): start
     * dropping the oldest books' caches while the total is still inside a
     * 128MB margin of the budget, so a new/continued book never has to fail
     * or thrash at the hard limit. Book granularity keeps each book's meta
     * consistent (dir + blocks bitmap + meta.json go together).
     */
    public static void freeForOpen() {
        BlockCacheStore.evict(Math.max(0, maxCacheBytes() - 128L * 1024 * 1024));
    }

    private long evictTarget() {
        return Math.max(0, maxCacheBytes() - 128L * 1024 * 1024);
    }

    /** Per-book disk cap: the hard 512MB ceiling or the user's total cache
     * budget, whichever is smaller. The open book is pinned against
     * book-level LRU eviction, so its own cap must respect the configured
     * budget — otherwise one big book could push the total cache past the
     * setting while open. */
    private long perBookLimit() {
        return Math.min(BlockCacheStore.PER_BOOK_LIMIT, maxCacheBytes());
    }

    /**
     * P2: refill the next blocks in the background. Page-based formats get
     * a wider sequential window (tech-spec §7.2: PDF 8–16MB), reflowable
     * formats the cheap 3-block lookahead.
     */
    private static final int PREFETCH_DEPTH_DEFAULT = 3;
    private static final int PREFETCH_DEPTH_PAGE_FORMAT = 64; // 4MB @ 64KB blocks
    /** Parallel read-ahead lanes: the page-tree walk consumes scattered page
     * objects serially (one network round trip each — 796 pages ≈ 21s on a
     * 27ms link); parallel lanes turn the walk into bulk transfer. */
    private static final int PREFETCH_LANES = 4;

    private int prefetchDepth() {
        if (!isPageFormat(RemoteBook.getExt(remotePath))) {
            return PREFETCH_DEPTH_DEFAULT;
        }
        if (AppState.get().remotePrefetchWifiOnly && isNetworkMetered()) {
            return PREFETCH_DEPTH_DEFAULT;
        }
        return PREFETCH_DEPTH_PAGE_FORMAT;
    }

    private void schedulePrefetch(long fromIdx) {
        if (cancelled || fromIdx < 0 || fromIdx >= cache.getBlockCount()) {
            return;
        }
        if (com.foobnix.remote.OpenGate.isProbe()) {
            return; // cover probes must not trigger the wide read-ahead
        }
        final int depth = prefetchDepth();
        // Skip only when the trigger point sits INSIDE the currently
        // covered window (the walk fires a read per page object). A window
        // triggered from a far region (e.g. the tail xref) must not
        // suppress read-ahead for the walk's own region.
        if (fromIdx >= prefetchWindowStart && fromIdx < prefetchWindowEnd) {
            return;
        }
        prefetchWindowStart = fromIdx;
        prefetchWindowEnd = fromIdx + depth;
        final long start = fromIdx;
        // NO foreground-yield here: this read-ahead IS part of the critical
        // path (the tree walk stalls on exactly these blocks). The whole-
        // book filler keeps its own yield rule.
        final long per = Math.max(1, depth / PREFETCH_LANES);
        android.util.Log.i("REMOTE", "prefetch window [" + start + ","
                + (start + depth) + ") x" + PREFETCH_LANES + " lanes");
        for (int c = 0; c < PREFETCH_LANES; c++) {
            final long cs = start + c * per;
            final long ce = Math.min(start + depth, cs + per);
            if (cs >= ce) {
                break;
            }
            prefetch.execute(() -> {
                long i = cs;
                while (i < ce && i < cache.getBlockCount()) {
                    if (cancelled) {
                        return;
                    }
                    waitWhileBackgroundPaused();
                    if (cancelled) {
                        return;
                    }
                    if (cache.hasBlock(i)) {
                        i++;
                        continue;
                    }
                    try {
                        long left = Math.min(ce, cache.getBlockCount()) - i;
                        int n = (int) Math.min(COALESCE_BLOCKS, left);
                        boolean filled = false;
                        if (n > 1 && source.supportsRange()) {
                            byte[][] blocks = readCoalesced(i, n);
                            for (int j = 0; j < n && blocks[j] != null; j++) {
                                if (!cache.hasBlock(i + j)) {
                                    BlockCacheStore.evict(evictTarget());
                                    cache.putBlock(i + j, blocks[j], versionTag);
                                }
                                filled = true;
                            }
                        }
                        if (!filled) {
                            fetchBlock(i);
                        }
                        i += Math.max(1, n);
                    } catch (Exception e) {
                        LOG.w(e);
                        return; // network hiccup: stop this lane
                    }
                }
            });
        }
    }

    /**
     * Pre-fetches the last two blocks in the background: PDF keeps its xref /
     * trailer at the file tail, so without this every (re)open of a big book
     * waited on tail network reads before the first page could appear.
     */
    public void warmTail() {
        final int n = cache.getBlockCount();
        if (n <= 0) {
            return;
        }
        prefetch.execute(() -> {
            for (long i = n - 1; i >= Math.max(0, n - 2); i--) {
                if (cancelled) {
                    return;
                }
                try {
                    if (!cache.hasBlock(i)) {
                        fetchBlock(i);
                    }
                } catch (Exception e) {
                    LOG.w(e);
                    return;
                }
            }
        });
    }

    /**
     * P3: progressive whole-book fill (tech-spec §5.3; threshold in MB,
     * 0 = off). Metered networks run it only when the WiFi-only rule is
     * off — the same single switch that gates the prefetch depth.
     */
    private synchronized void maybeStartFiller() {
        // synchronized: readAt runs on multiple MuPDF threads — without the
        // monitor two filler threads could start and duplicate the download
        if (cancelled || filler != null || cache.isFullyCached()) {
            return;
        }
        // small books (below the threshold) cache COMPLETELY on any network;
        // bigger books cache only a window around the reading position —
        // metered networks a fixed 10%/20%, WiFi the configured before/after
        // window — and only while the book is open (close cancels the fill)
        long thresholdMB = AppState.get().remoteWholeBookThresholdMB;
        if (thresholdMB <= 0) {
            return;
        }
        final boolean metered = isNetworkMetered();
        final boolean smallBook = size <= thresholdMB * 1024L * 1024L;
        final long fFrom;
        final long fTo;
        final boolean fWhole;
        final String fMode;
        if (smallBook) {
            fFrom = 0;
            fTo = cache.getBlockCount();
            fWhole = true;
            fMode = "whole (small book)";
        } else {
            if (fgPosBlock < 0) {
                return; // no reading position yet: the next read restarts us
            }
            int beforePct = metered ? 10 : clampPct(AppState.get().remoteWindowBeforePct, 20);
            int afterPct = metered ? 20 : clampPct(AppState.get().remoteWindowAfterPct, 30);
            fFrom = Math.max(0, fgPosBlock - cache.getBlockCount() * beforePct / 100L);
            fTo = Math.min(cache.getBlockCount(), fgPosBlock + cache.getBlockCount() * afterPct / 100L);
            if (fTo <= fFrom) {
                return;
            }
            fWhole = false;
            fMode = "window " + beforePct + "/" + afterPct + "%";
        }
        filler = new Thread(() -> {
            boolean capped = false;
            // let the first screen own the link: bulk download waits for a
            // foreground-quiet window (5s idle) — contending with it
            // stretched the first paint from ~2s to ~10s on loaded links
            while (!cancelled
                    && android.os.SystemClock.elapsedRealtime() - lastFgGlobal.get() < 5000) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            android.util.Log.i("REMOTE", "filler start " + remotePath
                    + " " + fMode + " range=[" + fFrom + "," + fTo + ")");
            for (long i = fFrom; i < fTo; i++) {
                if (cancelled) {
                    return;
                }
                if (cache.hasBlock(i)) {
                    continue;
                }
                if (cache.cachedBytes() + cache.getBlockSize() > perBookLimit()
                        || BlockCacheStore.totalBytes() + cache.getBlockSize() > evictTarget()) {
                    // per-book cap reached, or the total cache is at its
                    // budget: stop WITHOUT marking fullyCached — a fake 100%
                    // badge made offline opens fail with a network error
                    capped = true;
                    break;
                }
                waitIfForegroundBusy();
                waitWhileBackgroundPaused();
                if (cancelled) {
                    return;
                }
                try {
                    if (!cache.hasBlock(i)) {
                        long left = cache.getBlockCount() - i;
                        int n = (int) Math.min(COALESCE_BLOCKS, left);
                        boolean filled = false;
                        if (n > 1) {
                            byte[][] blocks = readCoalesced(i, n);
                            for (int j = 0; j < n && blocks[j] != null; j++) {
                                // no evict here: the fill must stop at the
                                // budget instead of wiping other books
                                cache.putBlock(i + j, blocks[j], versionTag);
                                filled = true;
                            }
                        }
                        if (!filled) {
                            byte[] raw = readRawBlock(i);
                            if (raw == null) {
                                return; // EOF: stop
                            }
                            cache.putBlock(i, raw, versionTag);
                        }
                    }
                    if (i % 32 == 0) {
                        android.util.Log.i("REMOTE", "filler " + remotePath + ": "
                                + (cache.cachedBytes() * 100 / Math.max(1, cache.getFileSize()))
                                + "% (" + (cache.cachedBytes() / (1024 * 1024)) + "MB)");
                    }
                } catch (Exception e) {
                    LOG.w(e);
                    android.util.Log.i("REMOTE", "filler failed at block " + i + ": " + e);
                    return;
                }
            }
            if (fWhole && !capped) {
                cache.setFullyCached(versionTag);
            }
            LOG.d("RemoteFiller done", remotePath, "capped=" + capped);
            android.util.Log.i("REMOTE", "filler done " + remotePath + " whole=" + fWhole
                    + " capped=" + capped + " cached=" + (cache.cachedBytes() / (1024 * 1024)) + "MB");
            synchronized (RemoteBookSession.this) {
                if (filler == Thread.currentThread()) {
                    filler = null; // window filled: a later readAt restarts us
                }
            }
        }, "RemoteFiller");
        filler.setDaemon(true);
        filler.setPriority(Thread.MIN_PRIORITY);
        filler.start();
    }

    private static int clampPct(int v, int def) {
        if (v <= 0) {
            return def;
        }
        return Math.min(90, v);
    }

    /** P3/P2 yield to foreground reads (any session's). */
    private void waitIfForegroundBusy() {
        while (fgReadsGlobal.get() > 0 && !cancelled) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static boolean isNetworkMetered() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    com.foobnix.LibreraApp.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the whole file is already on disk (zero-network reopen). */
    public boolean isFullyCached() {
        return cache.isFullyCached();
    }

    private volatile boolean closedFlag;

    public boolean isClosed() {
        return closedFlag;
    }

    /**
     * Cancels all in-flight and future reads (viewer closed / user cancelled):
     * prefetch and filler stop, the next readAt throws at once and the data
     * source interrupts its in-flight network call. Without this a stuck
     * WebDAV read held the global native lock until its (formerly unbounded)
     * timeout, and closing the reader blocked the UI thread on that lock.
     */
    public void abort() {
        if (cancelled) {
            return;
        }
        android.util.Log.i("REMOTE", "session abort " + remotePath);
        cancelled = true;
        try {
            source.abort();
        } catch (Exception e) {
            LOG.w(e);
        }
    }

    /** True when this session serves from a local full cache (no network). */
    public boolean isOffline() {
        return source instanceof LocalBlockDataSource;
    }

    /** Drops the whole block cache of this book (open-failure self-heal). */
    public void invalidateCache() {
        try {
            android.util.Log.i("REMOTE", "invalidate block cache " + remotePath);
            BlockCacheStore.clearBook(cacheKey);
        } catch (Exception e) {
            LOG.w(e);
        }
    }

    public void close() {
        closedFlag = true;
        cancelled = true;
        prefetch.shutdownNow();
        if (filler != null) {
            filler.interrupt();
        }
        BlockCacheStore.unpinKey(cacheKey);
        cache.close();
        try {
            source.close();
        } catch (Exception e) {
            LOG.w(e);
        }
    }
}
