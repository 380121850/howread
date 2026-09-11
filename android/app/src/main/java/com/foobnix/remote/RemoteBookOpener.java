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
        } else {
            fetchToCacheAndOpen(a, remotePath, sizeHint);
        }
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
                if (session.versionChanged) {
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.remote_updated_title)
                            .setMessage(R.string.remote_updated_msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
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
                    // remove a partial copy so a retry starts clean
                    target.delete();
                    tagFile.delete();
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

    /** Cache-dir copy of a fetched remote book: cachePath/Remote/books/<sha256>.<ext> */
    private static File cacheBookFile(String remotePath) {
        String ext = RemoteBook.getExt(remotePath);
        String name = RemoteBook.cacheKey(remotePath) + (TxtUtils.isEmpty(ext) ? "" : "." + ext);
        return new File(new File(new File(BookCSS.get().cachePath, "Remote"), "books"), name);
    }

    /**
     * Full download (through the same block cache, so bytes already read
     * online are not fetched twice) into the downloads folder, then opens
     * the local copy. Available on every flavor — it is the fdroid path.
     */
    public static void downloadAndOpen(final Activity a, final String remotePath, final long sizeHint) {
        final File target = new File(BookCSS.get().downlodsPath, TxtUtils.fixFileName(displayName(remotePath)));
        if (target.isFile() && target.length() > 0) {
            // already downloaded once — just open it
            ExtUtils.openFile(a, AppDB.get().getOrCreate(target.getPath()));
            return;
        }
        new AsyncTask() {
            RemoteBookSession session;
            String error;
            boolean done;

            @Override
            protected Object doInBackground(Object[] objects) {
                FileOutputStream out = null;
                try {
                    session = RemoteSessionFactory.open(remotePath);
                    target.getParentFile().mkdirs();
                    out = new FileOutputStream(target);
                    CachingRemoteInputStream in = new CachingRemoteInputStream(session);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.close();
                    out = null;
                    done = true;
                } catch (Exception e) {
                    LOG.e(e);
                    error = e.getMessage();
                } finally {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (Exception ignore) {
                        LOG.w(ignore);
                    }
                    if (session != null) {
                        // open() bypasses the session pool: close directly
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
                    // remove a partial file so a retry starts clean
                    target.delete();
                    Toast.makeText(a, TxtUtils.isNotEmpty(error) ? error
                            : a.getString(R.string.remote_open_failed), Toast.LENGTH_LONG).show();
                    return;
                }
                ensureMeta(remotePath, session == null ? 0 : session.size);
                ExtUtils.openFile(a, AppDB.get().getOrCreate(target.getPath()));
            }
        }.execute();
    }

    private static void offerDownloadFallback(Activity a, String remotePath, long sizeHint, String error) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_open_failed)
                .setMessage(a.getString(R.string.remote_open_failed_msg,
                        error == null ? "" : String.valueOf(error)))
                .setPositiveButton(R.string.remote_download_and_open, (d, w) -> downloadAndOpen(a, remotePath, sizeHint))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
