package com.foobnix.remote;

import android.content.Context;
import android.content.SharedPreferences;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;
import com.foobnix.webdav.WebDavStore;

/**
 * Local-only delete markers for remote servers. A server deleted in
 * 我的文件 leaves a tombstone so a later config sync (which restores the
 * stored server list) cannot resurrect it on THIS device. Markers never
 * sync — every device owns its deletions; adding the same server again
 * clears the marker.
 */
public class RemoteTombstones {

    private static final String PREF = "netTombstones";
    private static final int MAX = 64;

    /** Identity keys: "webdav:<trimmed url>", "smb:<id>", "sftp:<id>". */
    public static void add(String identity) {
        try {
            prefs().edit().putLong(identity, System.currentTimeMillis()).apply();
            trim();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public static boolean has(String identity) {
        try {
            return prefs().getLong(identity, 0L) != 0L;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clear(String identity) {
        try {
            prefs().edit().remove(identity).apply();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Strips tombstoned servers from the stored link lists — runs after a
     * config sync import, which may have re-added a server this device has
     * deleted.
     */
    public static void apply() {
        try {
            StringBuilder webdav = new StringBuilder();
            boolean changed = false;
            for (String line : AppState.get().allWebDavLinks.split(";")) {
                if (TxtUtils.isEmpty(line)) {
                    continue;
                }
                String[] it = line.split(",");
                String url = WebDavStore.trimSlash(it.length > 0 ? it[0].trim() : "");
                if (has("webdav:" + url)) {
                    changed = true;
                    continue;
                }
                webdav.append(line).append(';');
            }
            if (changed) {
                AppState.get().allWebDavLinks = webdav.toString();
            }
            String smb = rewrite(AppState.get().allSmbLinks, RemoteBook.TYPE_SMB);
            if (smb != null) {
                AppState.get().allSmbLinks = smb;
            }
            String sftp = rewrite(AppState.get().allSftpLinks, RemoteBook.TYPE_SFTP);
            if (sftp != null) {
                AppState.get().allSftpLinks = sftp;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** null when nothing was tombstoned. */
    private static String rewrite(String raw, String type) {
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String line : raw.split(";")) {
            if (TxtUtils.isEmpty(line)) {
                continue;
            }
            String id = line.split("\\|")[0];
            if (has(type + ":" + id)) {
                changed = true;
                continue;
            }
            sb.append(line).append(';');
        }
        return changed ? sb.toString() : null;
    }

    private static SharedPreferences prefs() {
        return LibreraApp.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void trim() {
        try {
            SharedPreferences p = prefs();
            java.util.Map<String, ?> all = p.getAll();
            while (all.size() > MAX) {
                String oldest = null;
                long oldestAt = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                    long at = e.getValue() instanceof Long ? (Long) e.getValue() : 0L;
                    if (at <= oldestAt) {
                        oldestAt = at;
                        oldest = e.getKey();
                    }
                }
                if (oldest == null) {
                    return;
                }
                p.edit().remove(oldest).apply();
                all = p.getAll();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}
