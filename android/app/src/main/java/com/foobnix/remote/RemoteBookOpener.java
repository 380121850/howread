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

    /** True when the book can be opened online right now (format + Pro). */
    public static boolean canOnlineOpen(String remotePath) {
        return RemoteBook.isRemotePath(remotePath)
                && RemoteBook.isDirectOpen(remotePath)
                && AppsConfig.isProFeaturesEnabled();
    }

    /** The click handler used by the network pages. */
    public static void openOrDownload(final Activity a, final String remotePath, final long sizeHint) {
        if (canOnlineOpen(remotePath) && AppState.get().remoteOnlineFirst) {
            openOnline(a, remotePath, sizeHint);
        } else {
            downloadAndOpen(a, remotePath, sizeHint);
        }
    }

    /** Online open through the chunk cache (Pro + direct-open formats). */
    public static void openOnline(final Activity a, final String remotePath, final long sizeHint) {
        if (!AppsConfig.isProFeaturesEnabled()) {
            PrefFragment2.proLockedToast(a);
            return;
        }
        if (!RemoteBook.isDirectOpen(remotePath)) {
            // heavy formats (FB2/MOBI/DOC/...) need a local conversion pass:
            // fetch the whole file first, then open the local copy
            downloadAndOpen(a, remotePath, sizeHint);
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
                        RemoteSessionFactory.closeSession(remotePath);
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
