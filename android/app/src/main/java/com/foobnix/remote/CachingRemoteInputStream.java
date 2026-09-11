package com.foobnix.remote;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sequential read-through InputStream over a {@link RemoteBookSession}:
 * used for the "download full book" fallback path so the downloaded bytes
 * land in the same block cache (resume on next open) before reaching the
 * local file.
 */
public class CachingRemoteInputStream extends InputStream {

    private final RemoteBookSession session;
    private long pos;
    private final byte[] one = new byte[1];
    private boolean eof;

    public CachingRemoteInputStream(RemoteBookSession session) {
        this.session = session;
    }

    public long position() {
        return pos;
    }

    public long totalSize() {
        return session.size;
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (eof) {
            return -1;
        }
        int n = session.readAt(pos, b, off, len);
        if (n <= 0) {
            eof = true;
            return -1;
        }
        pos += n;
        return n;
    }
}
