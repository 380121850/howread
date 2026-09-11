package com.foobnix.remote;

import java.io.Closeable;
import java.io.IOException;

/**
 * A random-access reader of one remote file. Every implementation must
 * provide true seek semantics (jcifs-ng SmbRandomAccessFile / sshj
 * RemoteFile.read(offset) / HTTP Range), so the block cache can fetch any
 * region on demand — the foundation of the online reading pipeline.
 */
public interface RemoteDataSource extends Closeable {

    /** Open / probe the file. Throws IOException on connect/auth failure. */
    void open() throws IOException;

    /** Total file size in bytes. Valid after {@link #open()}. */
    long size() throws IOException;

    /**
     * Read up to {@code len} bytes at absolute {@code offset} into
     * {@code buffer} at {@code off}. Returns the number of bytes read
     * (0 = EOF). Must be safe to call from multiple threads (implementations
     * serialize internally when the protocol requires it).
     */
    int readAt(long offset, byte[] buffer, int off, int len) throws IOException;

    /**
     * Version fingerprint of the remote file (ETag, or size+mtime). When it
     * changes, cached blocks for the book must be invalidated.
     */
    String versionTag() throws IOException;

    /** Human-readable protocol name for logs ("smb"/"sftp"/"webdav"). */
    String name();

    /**
     * Whether random reads are backed by real range requests. False means
     * the caller must degrade to a full fetch (tech-spec §6.5) instead of
     * faking seek by skipping through full responses.
     */
    default boolean supportsRange() {
        return true;
    }
}
