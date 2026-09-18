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

    /** A no-Range server forces a full fetch: from this size the user is
     * asked before the download starts — a silent multi-hundred-MB fetch
     * before anything opens reads like a hang. */
    private static final long CONFIRM_FULL_FETCH_BYTES = 50L * 1024 * 1024;

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

    /** The click handler used by the network pages (no start position). */
    public static void openOrDownload(final Activity a, final String remotePath, final long sizeHint) {
        openOrDownload(a, remotePath, sizeHint, 0f);
    }

    /** Callback receiving the fetched whole-book cache copy. */
    public interface FileReady {
        void onReady(File copy);
    }

    /**
     * @param startPercent 0..1 position to land on after the open (bookmark
     *                     jump); 0 keeps the default last-position restore
     */
    public static void openOrDownload(final Activity a, final String remotePath, final long sizeHint,
                                      final float startPercent) {
        if (!canOnlineOpen(remotePath)) {
            downloadAndOpen(a, remotePath, sizeHint, startPercent);
            return;
        }
        if (RemoteBook.isDirectOpen(remotePath)) {
            openOnline(a, remotePath, sizeHint, startPercent);
            return;
        }
        String ext = RemoteBook.getExt(remotePath);
        if ("docx".equals(ext)) {
            // Phase 2: restricted online reading — text parts only, media
            // never downloaded; unsuitable books degrade to the full fetch
            openDocxRestricted(a, remotePath, sizeHint, startPercent);
            return;
        }
        if (isHeavyFormat(ext)) {
            // formats that need the whole book before opening (mobi family,
            // djvu, cbr, doc): the user decides between download and cancel
            confirmUnsupportedFetch(a, remotePath, sizeHint, startPercent);
        } else {
            // simple formats (TXT / FB2 / RTF / HTML): silent fetch
            fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
        }
    }

    /**
     * Restricted online reading for DOCX (Phase 2): extract the text parts
     * through the block cache into a small "lite" copy (images skipped) and
     * open it through the regular mammoth chain. An existing current local
     * copy (lite or a previous full download) reopens with zero network.
     * Anything the restriction cannot serve — giant document.xml, ZIP64,
     * encryption, odd methods — degrades silently to the proven whole-book
     * download, i.e. exactly the pre-Phase-2 behaviour.
     */
    private static void openDocxRestricted(final Activity a, final String remotePath,
                                           final long sizeHint, final float startPercent) {
        final File target = cacheBookFile(remotePath);
        final File tagFile = new File(target.getPath() + ".tag");
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        final android.app.AlertDialog[] progress = new android.app.AlertDialog[1];
        progress[0] = new android.app.AlertDialog.Builder(a)
                .setTitle(R.string.remote_docx_online_title)
                .setMessage(R.string.remote_docx_online_msg)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        progress[0].show();
        new AsyncTask() {
            RemoteBookSession session;
            boolean done;
            boolean degrade;

            @Override
            protected Object doInBackground(Object[] objects) {
                try {
                    session = RemoteSessionFactory.open(remotePath);
                    String tag = session.versionTag == null ? "" : session.versionTag;
                    if (target.isFile() && target.length() > 0 && tagFile.isFile()
                            && tag.equals(com.foobnix.android.utils.IO.readString(tagFile).trim())) {
                        done = true; // current local copy (lite or full): no network
                        return null;
                    }
                    if (isNetworkOffline() && target.isFile() && target.length() > 0
                            && tagFile.isFile()) {
                        done = true; // offline with a complete earlier copy
                        return null;
                    }
                    if (cancelled.get()) {
                        return null;
                    }
                    File part = RemoteDocxLite.extract(session,
                            new File(target.getPath() + ".part"), cancelled);
                    if (part == null) {
                        degrade = true; // restriction cannot apply: proven path
                        return null;
                    }
                    if (cancelled.get()) {
                        part.delete();
                        return null;
                    }
                    target.getParentFile().mkdirs();
                    if (!part.renameTo(target)) {
                        part.delete();
                        degrade = true;
                        return null;
                    }
                    java.io.FileWriter tw = new java.io.FileWriter(tagFile);
                    tw.write(tag);
                    tw.close();
                    done = true;
                } catch (Exception e) {
                    LOG.e(e);
                    android.util.Log.i("REMOTE", "docx restricted failed: " + e);
                    new File(target.getPath() + ".part").delete();
                    degrade = true;
                } finally {
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
                if (progress[0] != null) {
                    try {
                        progress[0].dismiss();
                    } catch (Exception ignore) {
                    }
                }
                if (cancelled.get()) {
                    android.util.Log.i("REMOTE", "docx restricted cancelled by user");
                    return;
                }
                if (!done || degrade) {
                    android.util.Log.i("REMOTE", "docx restricted -> whole-book download: "
                            + remotePath);
                    fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
                    return;
                }
                android.util.Log.i("REMOTE", "docx restricted ok: " + target);
                ensureMeta(remotePath, session == null ? 0 : session.size);
                if (startPercent > 0f) {
                    ExtUtils.showDocumentWithoutDialog2(a, Uri.fromFile(target), startPercent, null);
                } else {
                    ExtUtils.openFile(a, AppDB.get().getOrCreate(target.getPath()));
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
    private static void confirmUnsupportedFetch(final Activity a, final String remotePath, final long sizeHint,
                                                final float startPercent) {
        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_unsupported_title)
                .setMessage(R.string.remote_unsupported_msg)
                .setPositiveButton(R.string.remote_download,
                        (d, w) -> downloadAndOpen(a, remotePath, sizeHint, startPercent))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Online open through the chunk cache (Pro + direct-open formats). */
    public static void openOnline(final Activity a, final String remotePath, final long sizeHint,
                                  final float startPercent) {
        if (!AppsConfig.isProFeaturesEnabled()) {
            PrefFragment2.proLockedToast(a);
            return;
        }
        if (!RemoteBook.isDirectOpen(remotePath)) {
            // formats whose converters need a local file: fetch to the cache
            // dir and open the local copy
            fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
            return;
        }
        new AsyncTask() {
            RemoteBookSession session;
            String error;
            RemoteVariantDetector.Verdict verdict;

            @Override
            protected Object doInBackground(Object[] objects) {
                android.util.Log.i("REMOTE", "openOnline probe " + remotePath);
                try {
                    session = RemoteSessionFactory.obtain(remotePath);
                    android.util.Log.i("REMOTE", "openOnline probe ok size=" + session.size
                            + " range=" + session.isRangeSupported());
                } catch (Exception e) {
                    LOG.e(e);
                    error = e.getMessage();
                    android.util.Log.i("REMOTE", "openOnline probe failed: " + error);
                }
                if (session != null) {
                    // round 12: packaging-variant gate — DRM / giant-XHTML /
                    // engine-hostile encodings degrade before the reader opens
                    try {
                        verdict = RemoteVariantDetector.check(session, RemoteBook.getExt(remotePath));
                    } catch (Throwable t) {
                        LOG.e(t);
                        verdict = null;
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Object o) {
                if (a.isFinishing() || a.isDestroyed()) {
                    // the user left while the probe was running: showing a
                    // dialog on a dead activity token crashes (BadToken)
                    android.util.Log.i("REMOTE", "openOnline finished: activity gone, drop verdict");
                    return;
                }
                if (session == null) {
                    android.util.Log.i("REMOTE", "openOnline session null, offer download fallback");
                    offerDownloadFallback(a, remotePath, sizeHint, error);
                    return;
                }
                if (verdict != null && verdict.action == RemoteVariantDetector.UNSUPPORTED) {
                    android.util.Log.i("REMOTE", "variant unsupported: " + verdict.detail);
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.remote_variant_drm_title)
                            .setMessage(R.string.remote_variant_drm_msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                if (verdict != null && verdict.action == RemoteVariantDetector.DOWNLOAD) {
                    android.util.Log.i("REMOTE", "variant download: " + verdict.detail);
                    if (verdict.prompt) {
                        new AlertDialog.Builder(a)
                                .setTitle(R.string.remote_variant_full_title)
                                .setMessage(a.getString(R.string.remote_variant_full_msg,
                                        fmtMB(session.size)))
                                .setPositiveButton(R.string.remote_download,
                                        (d, w) -> fetchToCacheAndOpen(a, remotePath, sizeHint,
                                                startPercent))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        // engine-hostile variant (encoding / wrapper / size):
                        // silently take the proven conversion path
                        fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
                    }
                    return;
                }
                if (!session.isRangeSupported()) {
                    // server ignores Range headers: no real random access —
                    // degrade to a full fetch instead of skipping (§6.5).
                    // Huge books ask first: a silent 200-300MB download with
                    // nothing opening reads like a hang
                    android.util.Log.i("REMOTE", "openOnline range unsupported, full fetch size="
                            + session.size);
                    if (session.size >= CONFIRM_FULL_FETCH_BYTES) {
                        new AlertDialog.Builder(a)
                                .setTitle(R.string.remote_norange_title)
                                .setMessage(a.getString(R.string.remote_norange_msg,
                                        fmtMB(session.size)))
                                .setPositiveButton(R.string.remote_download,
                                        (d, w) -> fetchToCacheAndOpen(a, remotePath, sizeHint,
                                                startPercent))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
                    return;
                }
                if (session.versionChanged) {
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.remote_updated_title)
                            .setMessage(R.string.remote_updated_msg)
                            .setPositiveButton(R.string.remote_reload,
                                    (d, w) -> {
                                        // consume the flag: obtain() reuses the
                                        // pooled session and the dialog would
                                        // pop again on every open
                                        session.versionChanged = false;
                                        ensureMeta(remotePath, session.size);
                                        ExtUtils.showDocumentWithoutDialog2(a, Uri.parse(remotePath), 0, null);
                                    })
                            .setNegativeButton(R.string.remote_download_and_open,
                                    (d, w) -> downloadAndOpen(a, remotePath, sizeHint))
                            .show();
                    return;
                }
                android.util.Log.i("REMOTE", "openOnline ok, launching viewer: " + remotePath);
                // PDF stores its xref/trailer at the tail: warm it before the
                // viewer opens so the document open does not wait on tail
                // network reads (the pain point for 200-300MB books)
                session.warmTail();
                ensureMeta(remotePath, session.size);
                ExtUtils.showDocumentWithoutDialog2(a, Uri.parse(remotePath), startPercent, null);
            }
}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Formats whose converters need a real local file (FB2 / MOBI / DOC /
     * DjVu / CBR / ...): fetch the whole book through the block cache into
     * the app cache dir (never the user-visible downloads folder) and open
     * the local copy. A cached copy whose remote versionTag still matches
     * reopens with zero network.
     */
    public static void fetchToCacheAndOpen(final Activity a, final String remotePath, final long sizeHint) {
        fetchToCacheAndOpen(a, remotePath, sizeHint, 0f);
    }

    public static void fetchToCacheAndOpen(final Activity a, final String remotePath, final long sizeHint,
                                           final float startPercent) {
        fetchToCache(a, remotePath, sizeHint, new FileReady() {
            @Override
            public void onReady(File target) {
                if (startPercent > 0f) {
                    // bookmark jump: open at the marked position instead of
                    // the last-read one
                    ExtUtils.showDocumentWithoutDialog2(a, Uri.fromFile(target), startPercent, null);
                } else {
                    ExtUtils.openFile(a, AppDB.get().getOrCreate(target.getPath()));
                }
            }
        });
    }

    /**
     * Fetches the whole-book cache copy WITHOUT opening it and hands it to
     * {@code onReady} on the UI thread (download failures toast instead).
     * Powers menu actions that need a real local file on remote books
     * (share / open with / edit).
     */
    public static void fetchToCache(final Activity a, final String remotePath, final long sizeHint,
                                    final FileReady onReady) {
        final File target = cacheBookFile(remotePath);
        final File tagFile = new File(target.getPath() + ".tag");
        // live progress (bytes fetched / total) + user cancel: 200-300MB
        // whole-book fetches used to run in total silence
        final java.util.concurrent.atomic.AtomicLong pos = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong(-1);
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final android.app.AlertDialog[] progress = new android.app.AlertDialog[1];
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] ticker = new Runnable[1];
        ticker[0] = new Runnable() {
            @Override public void run() {
                android.app.AlertDialog d = progress[0];
                if (d == null || !d.isShowing()) {
                    return; // dialog gone: the poller stops itself
                }
                long t = total.get();
                if (t > 0) {
                    long p = pos.get();
                    int pct = (int) Math.min(100, p * 100 / t);
                    d.setMessage(a.getString(R.string.remote_fetch_progress, pct,
                            fmtMB(p), fmtMB(t)));
                }
                ui.postDelayed(ticker[0], 500);
            }
        };
        android.app.AlertDialog.Builder pb = new android.app.AlertDialog.Builder(a);
        pb.setTitle(R.string.remote_download);
        pb.setMessage(a.getString(R.string.remote_fetch_progress, 0, fmtMB(0), "…"));
        pb.setNegativeButton(R.string.cancel, (d, w) -> cancelled.set(true));
        pb.setCancelable(false);
        progress[0] = pb.create();
        progress[0].show();
        ui.postDelayed(ticker[0], 500);
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
                    total.set(session.size);
                    android.util.Log.i("REMOTE", "fetchToCache start " + remotePath + " size=" + session.size);
                    if (isCopyCurrent(target, tagFile, session)) {
                        done = true;
                        return null;
                    }
                    // copy into a temp file first: the previous complete copy
                    // must never be truncated by a failed re-download (a
                    // stale .tag would then "resurrect" the partial file)
                    File part = new File(target.getPath() + ".part");
                    target.getParentFile().mkdirs();
                    FileOutputStream out = null;
                    try {
                        out = new FileOutputStream(part);
                        CachingRemoteInputStream in = new CachingRemoteInputStream(session);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (cancelled.get()) {
                                throw new java.io.IOException("cancelled");
                            }
                            out.write(buf, 0, n);
                            pos.addAndGet(n);
                        }
                    } finally {
                        if (out != null) {
                            out.close();
                        }
                    }
                    java.io.FileWriter tw = new java.io.FileWriter(tagFile);
                    tw.write(session.versionTag == null ? "" : session.versionTag);
                    tw.close();
                    if (!part.renameTo(target)) {
                        part.delete();
                        throw new java.io.IOException("cannot move the downloaded copy into place");
                    }
                    done = true;
                } catch (Exception e) {
                    LOG.e(e);
                    error = e.getMessage();
                    android.util.Log.i("REMOTE", "fetchToCache failed: " + error);
                    new File(target.getPath() + ".part").delete();
                    if (cancelled.get()) {
                        // an explicit user cancel must not fall through to
                        // opening the stale copy below
                    } else if (target.isFile() && target.length() > 0 && tagFile.isFile()) {
                        // server unreachable but an older complete copy
                        // exists (untouched: this attempt wrote to .part):
                        // open it instead of reporting failure
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
                if (progress[0] != null) {
                    try {
                        progress[0].dismiss();
                    } catch (Exception ignore) {
                    }
                }
                if (!done) {
                    if (cancelled.get()) {
                        android.util.Log.i("REMOTE", "fetchToCache cancelled by user");
                        return;
                    }
                    android.util.Log.i("REMOTE", "fetchToCache failed toast: " + error);
                    Toast.makeText(a, TxtUtils.isNotEmpty(error) ? error
                            : a.getString(R.string.remote_open_failed), Toast.LENGTH_LONG).show();
                    return;
                }
                android.util.Log.i("REMOTE", "fetchToCache done: " + target);
                ensureMeta(remotePath, session == null ? 0 : session.size);
                if (onReady != null) {
                    onReady.onReady(target);
                }
            }
}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
        downloadAndOpen(a, remotePath, sizeHint, 0f);
    }

    public static void downloadAndOpen(final Activity a, final String remotePath, final long sizeHint,
                                       final float startPercent) {
        fetchToCacheAndOpen(a, remotePath, sizeHint, startPercent);
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

    /**
     * Binds a live progress ticker onto the reader's loading dialog for a
     * remote book: cached share of the book + elapsed seconds. Without it a
     * 200-300MB book shows only a static "please wait" while its first pages
     * are fetched. Self-stops once the dialog is dismissed.
     */
    public static void startLoadingProgress(final android.app.AlertDialog dialog, final String remotePath) {
        if (dialog == null || !RemoteBook.isRemotePath(remotePath)) {
            return;
        }
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return; // dialog gone: the poller stops itself
                }
                try {
                    final android.widget.TextView msg =
                            (android.widget.TextView) dialog.findViewById(R.id.text1);
                    if (msg != null) {
                        final int pct = BlockCacheStore.cachedPercent(remotePath);
                        final long sec = (android.os.SystemClock.elapsedRealtime() - t0) / 1000;
                        String text;
                        if (pct >= 0) {
                            final long size = BlockCacheStore.peekSize(remotePath);
                            text = size > 0
                                    ? com.foobnix.LibreraApp.context.getString(
                                            R.string.remote_open_progress, pct,
                                            fmtMB(size * pct / 100), fmtMB(size))
                                    : com.foobnix.LibreraApp.context.getString(
                                            R.string.remote_open_progress_nosize, pct);
                        } else {
                            text = com.foobnix.LibreraApp.context.getString(
                                    R.string.remote_open_waiting, sec);
                        }
                        if (pct < 100 && sec > 30) {
                            text += "\n" + com.foobnix.LibreraApp.context.getString(
                                    R.string.remote_loading_slow);
                        }
                        msg.setText(text);
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
                ui.postDelayed(this, 600);
            }
        };
        ui.postDelayed(tick, 600);
    }

    /** Creates/updates the FileMeta record of a remote book (path-keyed). */
    public static void ensureMeta(String remotePath, long size) {
        try {
            FileMeta meta = AppDB.get().getOrCreate(remotePath);
            if (TxtUtils.isEmpty(meta.getTitle())) {
                meta.setTitle(displayName(remotePath));
            }
            if (size > 0) {
                if (meta.getSize() == null || meta.getSize() != size) {
                    meta.setSize(size);
                }
                // heal rows whose size text stayed "0 B" while the byte size
                // was already right (an old full scan zeroed it)
                String cur = meta.getSizeTxt();
                if (TxtUtils.isEmpty(cur) || "0 B".equals(cur) || "0B".equals(cur)) {
                    meta.setSizeTxt(ExtUtils.readableFileSize(size));
                }
            }
            AppDB.get().update(meta);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** "12.3 MB"-style size for the remote progress dialogs. */
    public static String fmtMB(long bytes) {
        if (bytes < 0) {
            return "…";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
