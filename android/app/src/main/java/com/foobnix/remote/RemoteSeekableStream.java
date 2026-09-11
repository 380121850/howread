package com.foobnix.remote;

import com.artifex.mupdf.fitz.SeekableStream;
import com.artifex.mupdf.fitz.SeekableInputStream;

import java.io.IOException;

/**
 * The bridge between MuPDF's native stream open and the chunk-cache session.
 * The JNI layer calls {@link #read(byte[])} / {@link #seek(long, int)} from
 * arbitrary (render) threads; the underlying session is thread-safe.
 */
public class RemoteSeekableStream implements SeekableInputStream {

    private final RemoteBookSession session;
    private long pos;
    private volatile boolean closed;
    private int readLogCount;

    public RemoteSeekableStream(RemoteBookSession session) {
        this.session = session;
    }

    @Override
    public int read(byte[] b) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (b == null || b.length == 0) {
            return 0;
        }
        int n = session.readAt(pos, b, 0, b.length);
        if (readLogCount++ < 60) {
            StringBuilder sb = new StringBuilder("stream read pos=" + pos + " want=" + b.length + " got=" + n + " head=");
            for (int i = 0; i < Math.min(12, n); i++) {
                sb.append(String.format("%02x", b[i]));
            }
            android.util.Log.i("REMOTE", sb.toString());
        }
        if (n <= 0) {
            android.util.Log.i("REMOTE", "stream read EOF at pos=" + pos + " size=" + session.size);
            return -1;
        }
        pos += n;
        return n;
    }

    @Override
    public long seek(long offset, int whence) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        switch (whence) {
            case SeekableStream.SEEK_SET:
                pos = offset;
                break;
            case SeekableStream.SEEK_CUR:
                pos += offset;
                break;
            case SeekableStream.SEEK_END:
                pos = session.size + offset;
                break;
            default:
                throw new IOException("Bad whence: " + whence);
        }
        if (pos < 0) {
            pos = 0;
        }
        android.util.Log.i("REMOTE", "stream seek whence=" + whence + " off=" + offset + " -> " + pos);
        return pos;
    }

    @Override
    public long position() {
        return pos;
    }
}
