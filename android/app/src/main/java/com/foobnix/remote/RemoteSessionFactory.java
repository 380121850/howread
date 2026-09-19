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
                    String root = com.foobnix.webdav.WebDavStore.trimSlash(s.url);
                    String fileUrl = com.foobnix.webdav.WebDavClient.encodeIfNeeded(
                            root + remotePathOnServer);
                    // legacy shelf rows keep the path relative to the start
                    // folder: on a 404 the source retries below the startDir
                    String sd = s.startDir == null ? "" : s.startDir.trim();
                    while (sd.startsWith("/")) {
                        sd = sd.substring(1);
                    }
                    while (sd.endsWith("/")) {
                        sd = sd.substring(0, sd.length() - 1);
                    }
                    String fallbackUrl = sd.isEmpty() ? null
                            : com.foobnix.webdav.WebDavClient.encodeIfNeeded(
                                    root + "/" + sd + remotePathOnServer);
                    return new WebDavRangeDataSource(fileUrl, fallbackUrl,
                            creds == null ? "" : creds[0], creds == null ? "" : creds[1], trustAll);
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
     * network/auth problems — unless the book is fully cached, in which
     * case a broken network open falls back to an offline session served
     * from the block cache.
     */
    /** Marks a user-initiated open: pauses the background (fill/prefetch)
     * work of every OTHER live session — their windows were saturating the
     * link and stretched the new book's tree walk from ~2s to ~20s. */
    public static void userOpenStart(String remotePath) {
        OpenGate.userOpenPending.set(true);
        synchronized (LOCK) {
            for (RemoteBookSession s : SESSIONS.values()) {
                s.backgroundPaused = !s.remotePath.equals(remotePath);
            }
        }
    }

    public static void userOpenEnd() {
        OpenGate.userOpenPending.set(false);
        synchronized (LOCK) {
            for (RemoteBookSession s : SESSIONS.values()) {
                s.backgroundPaused = false;
            }
        }
    }

    public static RemoteBookSession obtain(String remotePath) throws IOException {
        synchronized (LOCK) {
            RemoteBookSession existing = SESSIONS.get(remotePath);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            // cache-first: a usable block cache opens the book instantly from
            // the persisted book info (size/versionTag in meta.json) — cached
            // blocks render with zero network; the source connects lazily on
            // the first miss and the stored version is verified in background
            BlockCacheStore cached = null;
            try {
                cached = BlockCacheStore.openExisting(RemoteBook.cacheKey(remotePath));
            } catch (Exception ignore) {
            }
            if (cached != null) {
                RemoteBookSession cachedSession = null;
                try {
                    cachedSession = RemoteBookSession.openCachedFirst(remotePath,
                            createSource(remotePath), cached);
                } catch (Exception e) {
                    LOG.w(e);
                    try {
                        cached.close();
                    } catch (Exception ignore2) {
                    }
                }
                if (cachedSession != null) {
                    SESSIONS.put(remotePath, cachedSession);
                    verifyVersionAsync(remotePath, cached.getVersionTag(), cached.getFileSize());
                    android.util.Log.i("REMOTE", "cache-first open: " + remotePath);
                    return cachedSession;
                }
            }
            com.foobnix.remote.RemoteBookSession.freeForOpen();
            RemoteBookSession session;
            try {
                session = open(remotePath);
            } catch (IOException e) {
                session = RemoteBookSession.openOffline(remotePath);
                if (session == null) {
                    throw e;
                }
                android.util.Log.i("REMOTE", "offline open from block cache: " + remotePath);
            }
            SESSIONS.put(remotePath, session);
            return session;
        }
    }

    /** One background version verification per path at a time. */
    private static final java.util.Set<String> verifying =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * Cache-first sessions serve possibly-stale bytes until proven otherwise:
     * probe the remote file (short-lived source, daemon thread) and when the
     * version moved, drop the session and the cache so the next open is
     * fresh. Offline / unreachable server: the cache simply stays.
     */
    private static void verifyVersionAsync(final String remotePath, final String expectTag,
                                           final long expectSize) {
        if (!verifying.add(remotePath)) {
            return;
        }
        final Thread t = new Thread(() -> {
            try {
                RemoteDataSource src = createSource(remotePath);
                try {
                    src.open();
                    String tag = src.versionTag();
                    long size = src.size();
                    if (size != expectSize || !tag.equals(expectTag)) {
                        android.util.Log.i("REMOTE", "cache-first: remote version changed, invalidating: "
                                + remotePath);
                        RemoteBookSession s;
                        synchronized (LOCK) {
                            s = SESSIONS.remove(remotePath);
                        }
                        if (s != null) {
                            try {
                                s.abort();
                            } catch (Exception ignore) {
                            }
                            try {
                                s.close();
                            } catch (Exception ignore) {
                            }
                        }
                        BlockCacheStore.clearBook(RemoteBook.cacheKey(remotePath));
                    } else {
                        android.util.Log.i("REMOTE", "cache-first: version verified: " + remotePath);
                    }
                } finally {
                    src.close();
                }
            } catch (Throwable ignore) {
                android.util.Log.i("REMOTE", "cache-first: version verify skipped (offline?): " + remotePath);
            } finally {
                verifying.remove(remotePath);
            }
        }, "@T CacheVerify");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Opens a fresh session bypassing the reuse pool. */
    public static RemoteBookSession open(String remotePath) throws IOException {
        return RemoteBookSession.open(remotePath, createSource(remotePath));
    }

    /**
     * Aborts and forgets the live session of a path (viewer closed /
     * cancelled): in-flight network reads fail immediately, freeing the
     * global native lock that the open/render path holds while reading.
     */
    public static void abortSession(String remotePath) {
        final RemoteBookSession s;
        synchronized (LOCK) {
            s = SESSIONS.remove(remotePath);
        }
        if (s != null) {
            // persist the outgoing book's page tree map while its session is
            // still alive (version info lives on the session)
            com.foobnix.sys.ImageExtractor.savePageTreeIfPossible(remotePath, s.versionTag, s.size);
            try {
                s.abort();
            } catch (Exception e) {
                LOG.w(e);
            }
            try {
                // full close: releases the cache pin and handles — an aborted
                // session left pinned kept its cache un-clearable forever
                s.close();
            } catch (Exception e) {
                LOG.w(e);
            }
        }
    }

    /** Closes every live session (cache-clear path): unpins all books so
     * clearAll can actually delete their caches. */
    public static void closeAllSessions() {
        synchronized (LOCK) {
            for (RemoteBookSession s : SESSIONS.values()) {
                com.foobnix.sys.ImageExtractor.savePageTreeIfPossible(s.remotePath, s.versionTag, s.size);
                try {
                    s.abort();
                } catch (Exception e) {
                    LOG.w(e);
                }
                try {
                    s.close();
                } catch (Exception e) {
                    LOG.w(e);
                }
            }
            SESSIONS.clear();
        }
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
