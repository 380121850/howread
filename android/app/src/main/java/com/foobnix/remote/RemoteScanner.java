package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.ui2.AppDB;
import com.foobnix.webdav.WebDavCredentials;
import com.foobnix.webdav.WebDavItem;
import com.foobnix.webdav.WebDavServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Recursive remote-library scan (tech-spec §十二): walks a server from its
 * start directory, lists names / sizes only (no content is fetched) and
 * upserts one FileMeta per book file, keyed by its remote:// path. Scanned
 * books appear on the shelf (isSearchBook=true) and open through the normal
 * remote pipeline.
 */
public class RemoteScanner {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_DIRS = 2000;
    private static final int MAX_FILES = 5000;

    private volatile boolean cancelled;
    private int added, updated, failed;
    private TextView progressView;
    private Activity activity;

    private RemoteScanner() {
    }

    /** Entry for SMB / SFTP servers (Pro gated at the call site). */
    public static void scan(final Activity a, final RemoteServer srv) {
        final RemoteScanner scanner = new RemoteScanner();
        final AlertDialog progress = scanner.progressDialog(a);
        final Thread worker = new Thread(() -> {
            String start = srv.startDir == null ? "" : srv.startDir;
            Deque<String> dirs = new ArrayDeque<String>();
            dirs.add(start.startsWith("/") ? start : "/" + start);
            // null password → the listing clients load it from the store
            scanner.walk(a, srv.getTypeStored(), srv.id, dirs, dirs.peekFirst(), srv, null, null, false);
            scanner.finish(a, progress, srv.title);
        }, "RemoteScanner");
        scanner.start(progress, worker);
    }

    /** Entry for WebDAV servers (Pro gated at the call site). */
    public static void scanWebDav(final Activity a, final WebDavServer srv) {
        final RemoteScanner scanner = new RemoteScanner();
        final AlertDialog progress = scanner.progressDialog(a);
        final Thread worker = new Thread(() -> {
            String root = com.foobnix.webdav.WebDavStore.trimSlash(srv.url);
            Deque<String> dirs = new ArrayDeque<String>();
            dirs.add(root);
            String[] creds = WebDavCredentials.load(a, srv.url);
            scanner.walk(a, RemoteBook.TYPE_WEBDAV, RemoteSessionFactory.webdavId(srv.url),
                    dirs, root, null, creds == null ? "" : creds[0], creds == null ? "" : creds[1],
                    WebDavCredentials.isTrustAll(a, srv.url));
            scanner.finish(a, progress, srv.title);
        }, "RemoteScanner");
        scanner.start(progress, worker);
    }

    private void start(final AlertDialog progress, final Thread worker) {
        progress.setOnCancelListener(d -> cancelled = true);
        progress.show();
        worker.start();
    }

    private AlertDialog progressDialog(Activity a) {
        android.widget.LinearLayout body = new android.widget.LinearLayout(a);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Dips.dpToPx(16);
        body.setPadding(pad, pad, pad, pad);
        progressView = new TextView(a);
        progressView.setSingleLine(false);
        body.addView(progressView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        MyProgressBar bar = new MyProgressBar(a);
        body.addView(bar, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return new AlertDialog.Builder(a)
                .setTitle(R.string.remote_scan_title)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    private void finish(final Activity a, final AlertDialog progress, final String title) {
        a.runOnUiThread(() -> {
            try {
                progress.dismiss();
            } catch (Exception e) {
                LOG.w(e);
            }
            if (!cancelled) {
                Toast.makeText(a, a.getString(R.string.remote_scan_done, title, added, updated, failed),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * One BFS shared by all three protocols. SMB / SFTP {@code dir} entries
     * are server paths (remote:// path shape); WebDAV entries are full URLs
     * and {@code rootDir} is stripped (and URL-decoded) to build the
     * remote:// path. {@code rootDir} also anchors the depth limit.
     */
    private void walk(Activity a, String type, String id, Deque<String> dirs, String rootDir,
                      RemoteServer srv, String user, String password, boolean trustAll) {
        this.activity = a;
        final int rootDepth = dirDepth(rootDir);
        int scannedDirs = 0;
        while (!dirs.isEmpty() && !cancelled) {
            if (++scannedDirs > MAX_DIRS) {
                return;
            }
            final String dir = dirs.pollFirst();
            List<WebDavItem> items = list(type, dir, srv, user, password, trustAll);
            if (items == null) {
                failed++;
                continue;
            }
            showProgress(dir);
            for (final WebDavItem it : items) {
                if (cancelled) {
                    return;
                }
                if (it.isDir) {
                    if (dirDepth(dir) - rootDepth < MAX_DEPTH && !it.name.startsWith(".")) {
                        dirs.add(join(dir, it.name));
                    }
                    continue;
                }
                if (added + updated >= MAX_FILES) {
                    return;
                }
                if (!isBookFile(it.name)) {
                    continue;
                }
                String relative = webdavRelative(type, rootDir, dir, it.name);
                String remotePath = RemoteBook.build(type, id, relative);
                upsert(it, remotePath, type, id, dir);
            }
        }
    }

    /** remote:// path (after the server id) of one file. */
    private String webdavRelative(String type, String rootDir, String dir, String name) {
        if (RemoteBook.TYPE_WEBDAV.equals(type)) {
            return Uri.decode(dir.substring(rootDir.length())) + "/" + name;
        }
        return join(dir, name);
    }

    private void showProgress(final String dir) {
        if (progressView == null || activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (progressView != null) {
                progressView.setText(Uri.decode(dir));
            }
        });
    }

    private List<WebDavItem> list(String type, String dir, RemoteServer srv,
                                  String user, String password, boolean trustAll) {
        try {
            if (RemoteBook.TYPE_WEBDAV.equals(type)) {
                return com.foobnix.webdav.WebDavClient.list(dir, user, password, trustAll);
            }
            if (RemoteBook.TYPE_SFTP.equals(type)) {
                return SftpClient.list(srv, dir, password, null);
            }
            return SmbClient.list(srv, dir, password);
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    private void upsert(WebDavItem it, String remotePath, String type, String id, String dir) {
        try {
            FileMeta existing = AppDB.get().load(remotePath);
            FileMeta meta = AppDB.get().getOrCreate(remotePath);
            meta.setTitle(RemoteBookOpener.displayName(remotePath));
            if (it.size > 0) {
                meta.setSize(it.size);
            }
            String ext = RemoteBook.getExt(remotePath);
            if (TxtUtils.isNotEmpty(ext)) {
                meta.setExt(ext);
            }
            meta.setParentPath(RemoteBook.build(type, id, dir));
            meta.setIsSearchBook(true);
            AppDB.get().update(meta);
            if (existing == null) {
                added++;
            } else {
                updated++;
            }
        } catch (Exception e) {
            LOG.e(e);
            failed++;
        }
    }

    /** Book extension filter, mirroring the local library scan list. */
    private static boolean isBookFile(String name) {
        if (TxtUtils.isEmpty(name) || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String ext : ExtUtils.seachExts) {
            String e = ext.toLowerCase();
            if (lower.endsWith(e) && lower.length() > e.length()) {
                return true;
            }
        }
        return false;
    }

    private static String join(String dir, String name) {
        String d = dir == null ? "" : dir;
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d + "/" + name;
    }

    private static int dirDepth(String path) {
        if (path == null) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }
}
