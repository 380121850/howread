package com.foobnix.remote;

import com.foobnix.android.utils.LOG;

import java.io.IOException;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * SMB2/3 random-access data source via jcifs-ng
 * {@link SmbRandomAccessFile}: true seek semantics, long-lived handle kept
 * for the whole reading session (no per-read reconnect).
 */
public class SmbDataSource implements RemoteDataSource {

    private final RemoteServer server;
    private final String pathOnShare;

    private SmbFile file;
    private SmbRandomAccessFile raf;
    private long size = -1;

    /** Serializes seek+read on the single random-access handle. */
    private final Object readLock = new Object();

    public SmbDataSource(RemoteServer server, String pathOnShare) {
        this.server = server;
        this.pathOnShare = pathOnShare == null ? "" : pathOnShare;
    }

    @Override
    public void open() throws IOException {
        try {
            jcifs.CIFSContext ctx = SmbClient.smbContext(server);
            String dir = pathOnShare.startsWith("/") ? pathOnShare : "/" + pathOnShare;
            String url = "smb://" + server.host + ":" + (server.port > 0 ? server.port : 445) + "/"
                    + (server.share.isEmpty() ? "" : server.share)
                    + (dir.equals("/") ? "" : dir);
            file = new SmbFile(url, ctx);
            if (!file.exists() || !file.isFile()) {
                throw new IOException("SMB file not found: " + url);
            }
            raf = new SmbRandomAccessFile(file, "r");
            size = raf.length();
        } catch (jcifs.CIFSException e) {
            LOG.e(e);
            throw new IOException("SMB open failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int readAt(long offset, byte[] buffer, int off, int len) throws IOException {
        synchronized (readLock) {
            try {
                if (offset >= size) {
                    return 0;
                }
                raf.seek(offset);
                int n = raf.read(buffer, off, len);
                return n < 0 ? 0 : n;
            } catch (jcifs.CIFSException e) {
                LOG.e(e);
                // broken handle / dropped connection: one reopen + retry
                // (reconnects are not counted against retry_count)
                if (reopenQuiet()) {
                    try {
                        raf.seek(offset);
                        int n = raf.read(buffer, off, len);
                        return n < 0 ? 0 : n;
                    } catch (jcifs.CIFSException e2) {
                        LOG.e(e2);
                        throw new IOException("SMB read failed: " + e2.getMessage(), e2);
                    }
                }
                throw new IOException("SMB read failed: " + e.getMessage(), e);
            }
        }
    }

    /** Closes and re-opens the SMB handle after a read failure. */
    private boolean reopenQuiet() {
        android.util.Log.i("REMOTE", "smb reopen attempt after read failure");
        try {
            close();
            open();
            return true;
        } catch (Exception e) {
            LOG.e(e);
            return false;
        }
    }

    @Override
    public String versionTag() {
        try {
            return size + "-" + file.lastModified();
        } catch (Exception e) {
            return String.valueOf(size);
        }
    }

    @Override
    public String name() {
        return "smb";
    }

    @Override
    public void close() {
        synchronized (readLock) {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Exception e) {
                LOG.w(e);
            }
            raf = null;
        }
    }
}
