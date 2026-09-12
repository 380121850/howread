package com.foobnix.remote;

import java.io.IOException;

/**
 * Offline data source: serves reads purely from an existing
 * {@link BlockCacheStore} (a fully-cached book), never touching the
 * network. Used by {@link RemoteBookSession#openOffline} when the network
 * is unreachable — the session then behaves like an online one, so the
 * reading stack, progress and bookmarks keep working unchanged.
 */
class LocalBlockDataSource implements RemoteDataSource {

    private final BlockCacheStore cache;

    LocalBlockDataSource(BlockCacheStore cache) {
        this.cache = cache;
    }

    @Override
    public void open() throws IOException {
        // nothing to connect to
    }

    @Override
    public long size() {
        return cache.getFileSize();
    }

    @Override
    public int readAt(long offset, byte[] buffer, int off, int len) throws IOException {
        // fully-cached books are served block-by-block in
        // RemoteBookSession.readAt; this only covers stray direct reads
        int total = 0;
        final int bs = cache.getBlockSize();
        while (total < len) {
            long pos = offset + total;
            if (pos >= cache.getFileSize()) {
                break;
            }
            byte[] block = cache.getBlock(pos / bs, cache.getVersionTag());
            if (block == null) {
                throw new IOException("Offline cache miss at block " + pos / bs);
            }
            int inOff = (int) (pos % bs);
            int n = (int) Math.min(Math.min(len - total, bs - inOff), block.length - inOff);
            System.arraycopy(block, inOff, buffer, off + total, n);
            total += n;
        }
        return total;
    }

    @Override
    public String versionTag() {
        return cache.getVersionTag();
    }

    @Override
    public String name() {
        return "offline";
    }

    @Override
    public void close() throws IOException {
        // the block cache is closed by the owning session
    }
}
