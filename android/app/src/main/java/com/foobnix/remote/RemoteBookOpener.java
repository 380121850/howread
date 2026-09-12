package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.fragment.PrefFragment2;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Central entry for remote books: online open (chunk-cached stream into
 * MuPDF) or full download fallback. All Pro gating funnels through here.
 */
public class RemoteBookOpener {

    /**
     * True when the click should go the online route. Behaviour is driven by
     * the "online reading first" switch; with Pro not unlocked the switch is
     * locked off, so clicks fall through to download.
     */
    public static boolean canOnlineOpen(String remotePath) {
        return RemoteBook.isRemotePath(remotePath)
                && AppState.get().remoteOnlineFirst
                && AppsConfig.isProFeaturesEnabled();
    }

    /** The click handler used by the network pages. */
    public static void openOrDownload(final Activity a, final String remotePath, final long sizeHint) {
        if (!canOnlineOpen(remotePath)) {
            downloadAndOpen(a, remotePath, sizeHint);
            return;
        }
        if (RemoteBook.isDirectOpen(remotePath)) {
            openOnline(a, remotePath, sizeHint);
            return;
        }
        String ext = RemoteBook.getExt(remotePath);
        if (isHeavyFormat(ext)) {
            // formats that need the whole book before opening (mobi family,
            // djvu, cbr, doc): the user decides between download and cancel
            confirmUnsupportedFetch(a, remotePath, sizeHint);
        } else {
            // simple formats (TXT / FB2 / RTF / HTML): silent fetch
            fetchToCacheAndOpen(a, remotePath, sizeHint);
        }
    }

    /** MOBI / AZW / AZW3 / PRC: binary containers, not streamable. */
    private static boolean isMobiFamily(String ext) {
        return "mobi".equals(ext) || "azw".equals(ext) || "azw3".equals(ext) || "prc".equals(ext);
    }

    /** High-cost formats (tech-spec §3.4): parseable, but only after a full fetch. */
    private static boolean isHeavyFormat(String ext) {
        return isMobiFamily(ext) || "djvu".equals(ext) || "cbr".equals(ext) || "doc".equals(ext);
    }

    /**
     * Formats that cannot be streamed online (mobi family / djvu / cbr /
     * doc): one uniform dialog — 取消 or 下载. DRM-protected books end up
     * here too: their only exit is a download anyway, so no head probe.
     */
    private static void confirmUnsupportedFetch(final Activity a, final String remotePath, final long sizeHint) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_unsupported_title)
                .setMessage(R.string.remote_unsupported_msg)
                .setPositiveButton(R.string.remote_download,
                        (d, w) -> downloadAndOpen(a, remotePath, sizeHint))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Online open through the chunk cache (Pro + direct-open formats). */
    public static void openOnline(final Activity a, final String remotePath, final long sizeHint) {
        if (!AppsConfig.isProFeaturesEnabled()) {
            PrefFragment2.proLockedToast(a);
            return;
        }
        if (!RemoteBook.isDirectOpen(remotePath)) {
            // formats whose converters need a local file: fetch to the cache
            // dir and open the local copy
            fetchToCacheAndOpen(a, remotePath, sizeHint);
            return;
        }
        new AsyncTask() {
            RemoteBookSession session;
            String error;

            @Override
            protected Object doInBackground(Object[] objects) {
                try {
                    session = RemoteSessionFactory.obtain(remotePath);
                } catch (Exception e) {
                    LOG.e(e);
                    error = e.getMessage();
                }
                return null;
            }

            @Override
            protected void onPostExecute(Object o) {
                if (session == null) {
                    offerDownloadFallback(a, remotePath, sizeHint, error);
                    return;
                }
                if (!session.isRangeSupported()) {
                    // server ignores Range headers: no real random access —
                    // degrade to a full fetch instead of skipping (§6.5)
                    fetchToCacheAndOpen(a, remotePath, sizeHint);
                    return;
                }
                if (session.versionChanged) {
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.remote_updated_title)
                            .setMessage(R.string.remote_updated_msg)
                            .setPositiveButton(R.string.remote_reload,
                                    (d, w) -> {
                                        ensureMeta(remotePath, session.size);
                                        ExtUtils.showDocumentWithoutDialog2(a, Uri.parse(remotePath), 0, null);
                                    })
                            .setNegativeButton(R.string.remote_download_and_open,
                                    (d, w) -> downloadAndOpen(a, remotePath, sizeHint))
                            .show();
                    return;
                }
                ensureMeta(remotePath, session.size);
                ExtUtils.showDocumentWithoutDialog2(a, Uri.parse(remotePath), 0, null);
            }
        }.execute();
    }

    /**
     * Formats whose converters need a real local file (FB2 / MOBI / DOC /
     * DjVu / CBR / ...): fetch the whole book through the block cache into
     * the app cache dir (never the user-visible downloads folder) and open
     * the local copy. A cached copy whose remote versionTag still matches
     * reopens with zero network.
     */
    public static void fetchToCacheAndOpen(final Activity a, final String remotePath, final long sizeHint) {
        final File target = cacheBookFile(remotePath);
        final File tagFile = new File(target.getPath() + ".tag");
        new AsyncTask() {
            RemoteBookSession session;
            String error;
            boolean done;

            @Override
            protected Object doInBackground(Object[] objects) {
                try {
                    // offline (or the whole copy already fetched earlier):
                    // the .tag is only written after a complete copy, so an
                    // existing tag means the copy is usable without a
                    // version re-check
                    if (target.isFile() && target.length() > 0 && tagFile.isFile()
                            && isNetworkOffline()) {
                        done = true;
                        return null;
                    }
                    session = RemoteSessionFactory.open(remotePath);
                    if (isCopyCurrent(target, tagFile, session)) {
                        done = true;
                        return null;
                    }
                    target.getParentFile().mkdirs();
                    FileOutputStream out = null;
                    try {
                        out = new FileOutputStream(target);
                        CachingRemoteInputStream in = new CachingRemoteInputStream(session);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }
                    java.io.FileWriter tw = new java.io.FileWriter(tagFile);
                    tw.write(session.versionTag == null ? "" : session.versionTag);
                    tw.close();
                    done = true;
                } catch (Exception e) {
                    LOG.e(e);
                    error = e.getMessage();
                    if (target.isFile() && target.length() > 0 && tagFile.isFile()) {
                        // server unreachable but an older complete copy
                        // exists: open it instead of reporting failure
                        done = true;
                    } else {
                        // remove a partial copy so a retry starts clean
                        target.delete();
                        tagFile.delete();
                    }
                } finally {
                    // open() bypasses the session pool: close directly
                    if (session != null) {
                        try {
                            session.close();
                        } catch (Exception ignore) {
                            LOG.w(ignore);
                        }
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Object o) {
                if (!done) {
                    Toast.makeText(a, TxtUtils.isNotEmpty(error) ? error
                            : a.getString(R.string.remote_open_failed), Toast.LENGTH_LONG).show();
                    return;
                }
                ensureMeta(remotePath, session == null ? 0 : session.size);
                ExtUtils.openFile(a, AppDB.get().getOrCreate(target.getPath()));
            }
        }.execute();
    }

    /** True when the cache-dir copy matches the remote size and versionTag. */
    private static boolean isCopyCurrent(File target, File tagFile, RemoteBookSession session) {
        if (!target.isFile() || target.length() <= 0 || target.length() != session.size) {
            return false;
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(tagFile);
            try {
                byte[] b = new byte[(int) tagFile.length()];
                in.read(b);
                String cached = new String(b, "UTF-8");
                String tag = session.versionTag == null ? "" : session.versionTag;
                return cached.equals(tag);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cache-dir copy of a fetched remote book:
     * cachePath/Remote/books/<sha256>/<original file name>. The copy keeps
     * the remote file's own name so reading progress / bookmarks (keyed by
     * file name) match the shelf entry.
     */
    private static File cacheBookFile(String remotePath) {
        String name = displayName(remotePath);
        if (TxtUtils.isEmpty(name)) {
            name = RemoteBook.cacheKey(remotePath);
        }
        // a remote (Linux-side) name may carry characters the app storage rejects
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return new File(new File(new File(new File(BookCSS.get().cachePath, "Remote"), "books"),
                RemoteBook.cacheKey(remotePath)), name);
    }

    /**
     * Full download (through the same block cache, so bytes already read
     * online are not fetched twice), then opens the local copy. Available
     * on every flavor — it is the fdroid path. The copy lands in the
     * unified cache dir (cachePath/Remote/books, cleared with the cache),
     * NOT the user-visible downloads folder — every remote copy is
     * cache-managed since 1.3.2.
     */
    public static void downloadAndOpen(final Activity a, final String remotePath, final long sizeHint) {
        fetchToCacheAndOpen(a, remotePath, sizeHint);
    }

    /** True when no usable network connection is available right now. */
    private static boolean isNetworkOffline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    com.foobnix.LibreraApp.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni == null || !ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static void offerDownloadFallback(Activity a, String remotePath, long sizeHint, String error) {
        if (isMissingMessage(error)) {
            // the remote file is gone — a download cannot fix that
            new AlertDialog.Builder(a)
                    .setTitle(R.string.remote_missing_title)
                    .setMessage(a.getString(R.string.remote_missing_msg, displayName(remotePath)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_open_failed)
                .setMessage(a.getString(R.string.remote_open_failed_msg,
                        error == null ? "" : String.valueOf(error)))
                .setPositiveButton(R.string.remote_download_and_open, (d, w) -> downloadAndOpen(a, remotePath, sizeHint))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** True when the open error means the remote file no longer exists. */
    private static boolean isMissingMessage(String error) {
        if (error == null) {
            return false;
        }
        String m = error.toLowerCase();
        return m.contains("404") || m.contains("not found") || m.contains("no such file")
                || m.contains("cannot stat") || m.contains("410");
    }

    /** Creates/updates the FileMeta record of a remote book (path-keyed). */
    public static void ensureMeta(String remotePath, long size) {
        try {
            FileMeta meta = AppDB.get().getOrCreate(remotePath);
            if (TxtUtils.isEmpty(meta.getTitle())) {
                meta.setTitle(displayName(remotePath));
            }
            if (size > 0 && (meta.getSize() == null || meta.getSize() != size)) {
                meta.setSize(size);
            }
            AppDB.get().update(meta);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Human-readable book name of a remote:// URI (URL-decoded). */
    public static String displayName(String remotePath) {
        try {
            return Uri.decode(com.foobnix.pdf.info.ExtUtils.getFileName(remotePath));
        } catch (Exception e) {
            return com.foobnix.pdf.info.ExtUtils.getFileName(remotePath);
        }
    }
}
