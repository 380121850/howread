package com.foobnix.remote;

import com.artifex.mupdf.fitz.SeekableStream;
import com.artifex.mupdf.fitz.SeekableInputStream;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Local-file bridge for MuPDF's stream open entry point: lets a local txt
 * go through the same engine-chunked on-demand layout path as remote books.
 * Same thread contract as RemoteSeekableStream: the JNI layer calls
 * {@link #read(byte[])} / {@link #seek(long, int)} from arbitrary (render)
 * threads — RandomAccessFile is only used from one decode thread at a time,
 * matching how the engine drives the stream.
 */
public class LocalSeekableStream implements SeekableInputStream {

    private final RandomAccessFile raf;
    private volatile boolean closed;

    public LocalSeekableStream(java.io.File f) throws IOException {
        raf = new RandomAccessFile(f, "r");
    }

    @Override
    public int read(byte[] b) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (b == null || b.length == 0) {
            return 0;
        }
        int n = raf.read(b);
        return n <= 0 ? -1 : n;
    }

    @Override
    public long seek(long offset, int whence) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        switch (whence) {
            case SeekableStream.SEEK_SET:
                raf.seek(offset);
                break;
            case SeekableStream.SEEK_CUR:
                raf.seek(raf.getFilePointer() + offset);
                break;
            case SeekableStream.SEEK_END:
                raf.seek(raf.length() + offset);
                break;
            default:
                throw new IOException("Bad whence: " + whence);
        }
        return raf.getFilePointer();
    }

    @Override
    public long position() throws IOException {
        return raf.getFilePointer();
    }

    public void close() throws IOException {
        if (!closed) {
            closed = true;
            raf.close();
        }
    }
}
