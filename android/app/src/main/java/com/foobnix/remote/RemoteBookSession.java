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

    /** In-flight / done markers so concurrent readers of one block wait once. */
    private final ConcurrentHashMap<Long, Object> blockLocks = new ConcurrentHashMap<Long, Object>();
    private final AtomicInteger fgReads = new AtomicInteger();
    private volatile boolean cancelled;

    private final ExecutorService prefetch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RemotePrefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private Thread filler;
    private volatile long lastPrefetchFrom = -1;
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
    }

    /**
     * Opens the remote source and attaches (or creates) the block cache.
     * Network errors propagate as IOException; the caller decides between
     * retry / fallback-to-download.
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
        // peek before open(): BlockCacheStore.open wipes the dir on a
        // version mismatch, the flag must reflect the pre-open state
        String cachedTag = BlockCacheStore.peekVersionTag(cacheKey);
        RemoteBookSession session = new RemoteBookSession(remotePath, source,
                BlockCacheStore.open(cacheKey, size, versionTag), size, versionTag, cacheKey);
        session.versionChanged = cachedTag != null && !cachedTag.equals(versionTag);
        return session;
    }

    public String getCacheKey() {
        return cacheKey;
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
        try {
            int total = 0;
            while (total < len) {
                long pos = offset + total;
                long idx = pos / BlockCacheStore.BLOCK_SIZE;
                int inOff = (int) (pos % BlockCacheStore.BLOCK_SIZE);
                int want = (int) Math.min(len - total, cache.blockLen(idx) - inOff);
                if (want <= 0) {
                    break;
                }
                byte[] block = cache.getBlock(idx, versionTag);
                if (block == null) {
                    block = fetchBlock(idx);
                    if (block == null) {
                        break; // EOF / source failure
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
                schedulePrefetch((offset + total - 1) / BlockCacheStore.BLOCK_SIZE + 1);
                maybeStartFiller();
            }
            return total;
        } finally {
            fgReads.decrementAndGet();
        }
    }

    /** Fetches one full block from the remote source and caches it. */
    private byte[] fetchBlock(long idx) throws IOException {
        if (cache.hasBlock(idx)) {
            return cache.getBlock(idx, versionTag);
        }
        Object lock = blockLocks.computeIfAbsent(idx, k -> new Object());
        synchronized (lock) {
            byte[] block = cache.getBlock(idx, versionTag);
            if (block != null) {
                return block;
            }
            if (cache.cachedBytes() >= BlockCacheStore.PER_BOOK_LIMIT && !cache.hasBlock(idx)) {
                // per-book disk cap reached: serve through without persisting
                return readRawBlock(idx);
            }
            byte[] raw = readRawBlock(idx);
            if (raw == null) {
                return null;
            }
            BlockCacheStore.evict(maxCacheBytes());
            cache.putBlock(idx, raw, versionTag);
            return raw;
        }
    }

    private byte[] readRawBlock(long idx) throws IOException {
        int len = cache.blockLen(idx);
        byte[] buf = new byte[len];
        int got = 0;
        while (got < len) {
            int n = source.readAt(idx * BlockCacheStore.BLOCK_SIZE + got, buf, got, len - got);
            if (n <= 0) {
                break;
            }
            got += n;
        }
        if (got <= 0) {
            return null;
        }
        if (got < len) {
            byte[] exact = new byte[got];
            System.arraycopy(buf, 0, exact, 0, got);
            return exact;
        }
        return buf;
    }

    private static long cachedOfBook(BlockCacheStore c) {
        return c.cachedBytes();
    }

    private long maxCacheBytes() {
        int mb = AppState.get().remoteCacheMaxMB;
        return (mb <= 0 ? 500 : mb) * 1024L * 1024L;
    }

    /** P2: refill the next few blocks in the background. */
    private void schedulePrefetch(long fromIdx) {
        if (cancelled || fromIdx < 0 || fromIdx >= cache.getBlockCount()) {
            return;
        }
        if (fromIdx == lastPrefetchFrom) {
            return; // already queued for this position
        }
        lastPrefetchFrom = fromIdx;
        final long start = fromIdx;
        prefetch.execute(() -> {
            for (long i = start; i < start + 3 && i < cache.getBlockCount(); i++) {
                if (cancelled) {
                    return;
                }
                waitIfForegroundBusy();
                if (cancelled || cache.hasBlock(i)) {
                    continue;
                }
                try {
                    fetchBlock(i);
                } catch (Exception e) {
                    LOG.w(e);
                    return; // network hiccup: stop this prefetch round
                }
            }
        });
    }

    /** P3: progressive whole-book fill for small books. */
    private void maybeStartFiller() {
        if (cancelled || filler != null || cache.isFullyCached()) {
            return;
        }
        long thresholdMB = AppState.get().remoteWholeBookThresholdMB;
        if (thresholdMB <= 0 || size > thresholdMB * 1024L * 1024L) {
            return;
        }
        if (AppState.get().remotePrefetchWifiOnly && isMeteredNetwork()) {
            return;
        }
        filler = new Thread(() -> {
            for (long i = 0; i < cache.getBlockCount(); i++) {
                if (cancelled) {
                    return;
                }
                if (cache.hasBlock(i) || cache.cachedBytes() + BlockCacheStore.BLOCK_SIZE > BlockCacheStore.PER_BOOK_LIMIT) {
                    continue;
                }
                waitIfForegroundBusy();
                if (cancelled) {
                    return;
                }
                try {
                    if (!cache.hasBlock(i)) {
                        byte[] raw = readRawBlock(i);
                        if (raw == null) {
                            return; // EOF: stop
                        }
                        BlockCacheStore.evict(maxCacheBytes());
                        cache.putBlock(i, raw, versionTag);
                    }
                } catch (Exception e) {
                    LOG.w(e);
                    return;
                }
            }
            cache.setFullyCached(versionTag);
            LOG.d("RemoteFiller done", remotePath);
        }, "RemoteFiller");
        filler.setDaemon(true);
        filler.setPriority(Thread.MIN_PRIORITY);
        filler.start();
    }

    /** P3/P2 yield to foreground reads. */
    private void waitIfForegroundBusy() {
        while (fgReads.get() > 0 && !cancelled) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean isMeteredNetwork() {
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

    public void close() {
        closedFlag = true;
        cancelled = true;
        prefetch.shutdownNow();
        if (filler != null) {
            filler.interrupt();
        }
        cache.close();
        try {
            source.close();
        } catch (Exception e) {
            LOG.w(e);
        }
    }
}
