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
    // identity prefixes (the store is local-only; MAX bounds its size —
    // recent/favorite clear-all can write many markers at once)
    public static final String TOMB_RECENT = "recent:";
    public static final String TOMB_FAVORITE = "favorite:";
    public static final String TOMB_AI = "ai:";
    private static final int MAX = 512;

    /** Identity keys: "webdav:<trimmed url>", "smb:<id>", "sftp:<id>",
     * "opds:<line>", "folder:<path>". */
    public static void add(String identity) {
        try {
            identity = normalizeOpds(identity);
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
            identity = normalizeOpds(identity);
            prefs().edit().remove(identity).apply();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Entry.appState carries a trailing ';' while the identities checked by
     * {@code rewriteLines} are the bare ';'-split segments — call sites that
     * passed a whole appState wrote keys the filter never matched, so a
     * deleted catalog came back on every sync. Normalized in one place. */
    private static String normalizeOpds(String identity) {
        if (identity != null && identity.startsWith("opds:") && identity.endsWith(";")) {
            return identity.substring(0, identity.length() - 1);
        }
        return identity;
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
            // OPDS catalogs and library folders are plain unions in the sync
            // merge (no per-item tombstone there): without this filter a
            // deleted catalog/folder came back on every sync
            String opds = rewriteLines(AppState.get().allOPDSLinks, "opds:");
            if (opds != null) {
                AppState.get().allOPDSLinks = opds;
            }
            String folders = rewriteJsonArray(com.foobnix.pdf.info.model.BookCSS.get().searchPathsJson, "folder:");
            if (folders != null) {
                com.foobnix.pdf.info.model.BookCSS.get().searchPathsJson = folders;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** null when nothing was tombstoned. Filters a ';'-separated line list
     * (OPDS catalogs) by the "opds:<line>" identities. */
    private static String rewriteLines(String raw, String prefix) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String line : raw.split(";")) {
            if (TxtUtils.isEmpty(line)) {
                continue;
            }
            if (has(prefix + line)) {
                changed = true;
                continue;
            }
            sb.append(line).append(';');
        }
        return changed ? sb.toString() : null;
    }

    /** null when nothing was tombstoned. Filters the JSON array of library
     * folder paths by the "folder:<path>" identities. */
    private static String rewriteJsonArray(String json, String prefix) {
        try {
            org.librera.JSONArray in = new org.librera.JSONArray(
                    json == null || json.trim().isEmpty() ? "[]" : json);
            org.librera.JSONArray out = new org.librera.JSONArray();
            boolean changed = false;
            for (int i = 0; i < in.length(); i++) {
                String p = in.optString(i);
                if (TxtUtils.isEmpty(p)) {
                    continue;
                }
                if (has(prefix + p)) {
                    changed = true;
                    continue;
                }
                out.put(p);
            }
            return changed ? out.toString() : null;
        } catch (Exception e) {
            LOG.e(e);
            return null;
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
