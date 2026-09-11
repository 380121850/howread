package com.foobnix.remote;

import android.content.Context;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.webdav.WebDavCredentials;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link RemoteBookSession}s for a remote:// path and keeps a small
 * LRU of live sessions (MuPDF renders lazily, so the source must outlive the
 * open call; reopening the same book reuses the warm connection + cache).
 */
public class RemoteSessionFactory {

    private static final Object LOCK = new Object();
    private static final Map<String, RemoteBookSession> SESSIONS = new LinkedHashMap<String, RemoteBookSession>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RemoteBookSession> eldest) {
            if (size() <= 3) {
                return false;
            }
            try {
                eldest.getValue().close();
            } catch (Exception e) {
                LOG.w(e);
            }
            return true;
        }
    };

    private RemoteSessionFactory() {
    }

    /** Creates (but does not open) the protocol data source for a path. */
    public static RemoteDataSource createSource(String remotePath) throws IOException {
        String type = RemoteBook.getType(remotePath);
        String id = RemoteBook.getServerId(remotePath);
        String remotePathOnServer = RemoteBook.getRemotePath(remotePath);
        if (RemoteBook.TYPE_WEBDAV.equals(type)) {
            // WebDAV servers are identified by the hash of their root URL
            for (com.foobnix.webdav.WebDavServer s : com.foobnix.webdav.WebDavStore.load()) {
                if (webdavId(s.url).equals(id)) {
                    Context c = LibreraApp.context;
                    String[] creds = WebDavCredentials.load(c, s.url);
                    boolean trustAll = com.foobnix.webdav.WebDavCredentials.isTrustAll(c, s.url);
                    String fileUrl = com.foobnix.webdav.WebDavStore.trimSlash(s.url) + remotePathOnServer;
                    return new WebDavRangeDataSource(fileUrl, creds == null ? "" : creds[0],
                            creds == null ? "" : creds[1], trustAll);
                }
            }
            throw new IOException("WebDAV server not found for remote book: " + remotePath);
        }
        RemoteServer s = RemoteStore.find(type, id);
        if (s == null) {
            throw new IOException("Remote server not found: " + remotePath);
        }
        Context c = LibreraApp.context;
        String[] creds = WebDavCredentials.load(c, RemoteStore.credentialsKey(s.id));
        String password = creds == null ? "" : creds[1];
        if (RemoteBook.TYPE_SFTP.equals(type)) {
            String keyPass = "";
            String[] kp = WebDavCredentials.load(c, RemoteStore.keyPassKey(s.id));
            if (kp != null) {
                keyPass = kp[1];
            }
            return new SftpDataSource(s.host, s.port, s.user, password, s.keyPath, keyPass, s.trustAll,
                    remotePathOnServer);
        }
        return new SmbDataSource(s, remotePathOnServer);
    }

    /** Stable id of a WebDAV server URL for remote:// paths. */
    public static String webdavId(String url) {
        return RemoteBook.sha256(com.foobnix.webdav.WebDavStore.trimSlash(url)).substring(0, 12);
    }

    /**
     * Opens (or reuses) the session for a path. Throws IOException on
     * network/auth problems.
     */
    public static RemoteBookSession obtain(String remotePath) throws IOException {
        synchronized (LOCK) {
            RemoteBookSession existing = SESSIONS.get(remotePath);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            RemoteBookSession session = open(remotePath);
            SESSIONS.put(remotePath, session);
            return session;
        }
    }

    /** Opens a fresh session bypassing the reuse pool. */
    public static RemoteBookSession open(String remotePath) throws IOException {
        return RemoteBookSession.open(remotePath, createSource(remotePath));
    }

    public static void closeSession(String remotePath) {
        synchronized (LOCK) {
            RemoteBookSession s = SESSIONS.remove(remotePath);
            if (s != null) {
                try {
                    s.close();
                } catch (Exception e) {
                    LOG.w(e);
                }
            }
        }
    }

    public static List<RemoteBookSession> liveSessions() {
        synchronized (LOCK) {
            return new ArrayList<RemoteBookSession>(SESSIONS.values());
        }
    }
}
