package com.foobnix.model;

import android.content.Context;

import com.foobnix.ai.AiCredentials;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.remote.RemoteBook;
import com.foobnix.remote.RemoteServer;
import com.foobnix.remote.RemoteStore;
import com.foobnix.webdav.WebDavCredentials;
import com.foobnix.webdav.WebDavServer;
import com.foobnix.webdav.WebDavStore;

import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Read/write/merge helpers for the state files that complete the backup and
 * WebDAV sync: reading statistics (app-Stats.json, mirrors the AppSP read*
 * fields), the AI API key (app-AI.json) and union merges for the per-device
 * SimpleMeta arrays (recent / favorite).
 *
 * app-AI.json carries the plain API key so a restore on the same device (or a
 * user-trusted server) restores the working AI setup; WebDavCredentials stay
 * device-bound and are NOT exported.
 */
public class ProfileStateIO {

    private static final String K_API_KEY = "apiKey";
    private static final String K_ACTIVE = "active";
    private static final String K_VENDORS = "vendors";
    private static final String K_MONTHLY = "readMonthlyJson";
    private static final String K_DAILY = "readDailyJson";
    private static final String K_DAY_KEY = "readDayKey";
    private static final String K_DAY_MS = "readDayMs";

    private static final String SEC_APPSP = "AppSP";
    private static final String SEC_OPDS = "opds";
    private static final String SEC_PASSWORD = "PasswordState";
    private static final String SEC_POPUPS = "DraggingPopups";

    // ------------------------------------------------------------------ stats

    /** Mirror the AppSP reading statistics into app-Stats.json (before export / sync). */
    public static void exportStats() {
        try {
            if (AppProfile.syncStats == null) {
                return;
            }
            AppSP sp = AppSP.get();
            LinkedJSONObject o = new LinkedJSONObject();
            o.put("readTimeMs", sp.readTimeMs);
            o.put(K_DAY_KEY, sp.readDayKey);
            o.put(K_DAY_MS, sp.readDayMs);
            o.put("readPages", sp.readPages);
            o.put(K_MONTHLY, TxtUtils.isEmpty(sp.readMonthlyJson) ? "{}" : sp.readMonthlyJson);
            o.put(K_DAILY, TxtUtils.isEmpty(sp.readDailyJson) ? "{}" : sp.readDailyJson);
            writeIfChanged(AppProfile.syncStats, o.toString());
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Restore reading statistics from app-Stats.json into AppSP (after import / sync). */
    public static void importStats(Context c) {
        try {
            if (AppProfile.syncStats == null || !AppProfile.syncStats.isFile()) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncStats);
            if (o.length() == 0) {
                return;
            }
            applyStats(o);
            if (c != null) {
                AppSP.get().save();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Local calendar day key shared with {@link ReadingStats} ("yyyy-MM-dd"). */
    private static String todayKey() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    /** Write one stats object into the AppSP fields (max of both sides). */
    static void applyStats(LinkedJSONObject o) {
        AppSP sp = AppSP.get();
        sp.readTimeMs = Math.max(sp.readTimeMs, o.optLong("readTimeMs", 0));
        sp.readPages = Math.max(sp.readPages, o.optLong("readPages", 0));
        // "today" only merges when the source device's day key matches THIS
        // device's calendar day — a stale key would otherwise put a historic
        // total under 今日阅读 (the per-day buckets below stay the source of
        // truth for history)
        if (todayKey().equals(o.optString(K_DAY_KEY, "")) && o.optLong(K_DAY_MS, 0) > sp.readDayMs) {
            sp.readDayMs = o.optLong(K_DAY_MS, 0);
            sp.readDayKey = o.optString(K_DAY_KEY, sp.readDayKey);
        }
        sp.readMonthlyJson = mergeBuckets(sp.readMonthlyJson, o.optString(K_MONTHLY, "{}"));
        sp.readDailyJson = mergeBuckets(sp.readDailyJson, o.optString(K_DAILY, "{}"));
    }

    /**
     * Merge two stats objects: numeric fields take the larger value, the
     * month/day bucket strings are parsed and merged per key (larger wins).
     */
    public static LinkedJSONObject mergeStats(LinkedJSONObject local, LinkedJSONObject remote) {
        try {
            applyStats(remote);
            LinkedJSONObject out = new LinkedJSONObject();
            out.put("readTimeMs", AppSP.get().readTimeMs);
            out.put(K_DAY_KEY, AppSP.get().readDayKey);
            out.put(K_DAY_MS, AppSP.get().readDayMs);
            out.put("readPages", AppSP.get().readPages);
            out.put(K_MONTHLY, TxtUtils.isEmpty(AppSP.get().readMonthlyJson) ? "{}" : AppSP.get().readMonthlyJson);
            out.put(K_DAILY, TxtUtils.isEmpty(AppSP.get().readDailyJson) ? "{}" : AppSP.get().readDailyJson);
            return out;
        } catch (Exception e) {
            LOG.e(e);
            return local;
        }
    }

    /** Per-key max of two JSON-in-string bucket maps ({"2026-08": ms, ...}). */
    static String mergeBuckets(String a, String b) {
        try {
            LinkedJSONObject ja = new LinkedJSONObject(TxtUtils.isEmpty(a) ? "{}" : a);
            LinkedJSONObject jb = new LinkedJSONObject(TxtUtils.isEmpty(b) ? "{}" : b);
            LinkedJSONObject out = new LinkedJSONObject(ja.toString());
            Iterator<String> keys = jb.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, Math.max(ja.optLong(k, 0), jb.optLong(k, 0)));
            }
            return out.toString();
        } catch (Exception e) {
            return TxtUtils.isEmpty(a) ? b : a;
        }
    }

    // ------------------------------------------------------------------ misc (remaining configurable state)

    /**
     * Snapshot of the remaining configurable state into app-Misc.json: the
     * whole AppSP object (last book, reading mode, sync flags, statistics
     * fields…), OPDS server logins, the app/book passwords and the reader
     * button-layout cache. Written before export / sync, restored after.
     */
    /** Root of app-Misc.json exactly as exportMisc wrote it this round (the
     * round-start snapshot). importMisc compares against it so only fields
     * the merge actually changed are applied back — a whole-object restore
     * used to revert everything (reading stats, config edits) changed on this
     * device while the network round was running. */
    private static volatile String lastExportedMisc;

    public static void exportMisc(Context c) {
        try {
            if (AppProfile.syncMisc == null) {
                return;
            }
            LinkedJSONObject root = new LinkedJSONObject();
            root.put(SEC_APPSP, com.foobnix.android.utils.Objects.toJSONObject(AppSP.get()));
            root.put(SEC_OPDS, snapshotPrefs(c, SEC_OPDS));
            root.put(SEC_PASSWORD, snapshotPrefs(c, SEC_PASSWORD));
            root.put(SEC_POPUPS, snapshotPrefs(c, SEC_POPUPS));
            writeIfChanged(AppProfile.syncMisc, root.toString());
            lastExportedMisc = root.toString();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Restore the app-Misc.json sections into AppSP / SharedPreferences. */
    public static void importMisc(Context c) {
        try {
            if (AppProfile.syncMisc == null || !AppProfile.syncMisc.isFile() || c == null) {
                return;
            }
            LinkedJSONObject root = IO.readJsonObject(AppProfile.syncMisc);
            if (root.length() == 0) {
                return;
            }
            // snapshot of what THIS device exported at the start of the round;
            // absent (e.g. a manual backup restore) → full apply as before
            LinkedJSONObject exportRoot = lastExportedMisc == null
                    ? new LinkedJSONObject() : new LinkedJSONObject(lastExportedMisc);

            LinkedJSONObject sp = root.optJSONObject(SEC_APPSP);
            LinkedJSONObject spExp = exportRoot.optJSONObject(SEC_APPSP);
            if (sp != null && sp.length() > 0) {
                // apply only the fields the merge actually changed this round
                final LinkedJSONObject apply = changedFields(sp, spExp);
                if (apply.length() > 0) {
                    // the AppSP snapshot carries the source device's identity:
                    // its storage root, profile and last-read book never migrate
                    final AppSP app = AppSP.get();
                    final String rootPath = app.rootPath1;
                    final String profile = app.currentProfile;
                    final String syncRoot = app.syncRootID;
                    final String lastBook = app.lastBookPath;
                    final int lastPage = app.lastBookPage;
                    com.foobnix.android.utils.Objects.loadFromJson(app, apply);
                    app.rootPath1 = rootPath;
                    app.currentProfile = profile;
                    app.syncRootID = syncRoot;
                    app.lastBookPath = lastBook;
                    app.lastBookPage = lastPage;
                    app.save();
                }
            }
            restorePrefsDiff(c, SEC_OPDS, root.optJSONObject(SEC_OPDS), exportRoot.optJSONObject(SEC_OPDS));
            restorePrefsDiff(c, SEC_PASSWORD, root.optJSONObject(SEC_PASSWORD), exportRoot.optJSONObject(SEC_PASSWORD));
            restorePrefsDiff(c, SEC_POPUPS, root.optJSONObject(SEC_POPUPS), exportRoot.optJSONObject(SEC_POPUPS));
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Keys of {@code file} whose value differs from the round-start
     * snapshot {@code exported} (i.e. the merge changed them). */
    private static LinkedJSONObject changedFields(LinkedJSONObject file, LinkedJSONObject exported) {
        final LinkedJSONObject out = new LinkedJSONObject();
        try {
            for (Iterator<String> it = file.keys(); it.hasNext();) {
                final String k = it.next();
                final Object fv = file.opt(k);
                final Object ev = exported == null ? null : exported.opt(k);
                if (exported == null || !String.valueOf(fv).equals(String.valueOf(ev))) {
                    out.put(k, fv);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return out;
    }

    /** restorePrefs, but only for keys the merge changed relative to the
     * round-start snapshot (full apply when the snapshot is absent). */
    private static void restorePrefsDiff(Context c, String prefsName, LinkedJSONObject data, LinkedJSONObject exported) {
        try {
            if (data == null || data.length() == 0) {
                return;
            }
            if (exported == null || exported.length() == 0) {
                restorePrefs(c, prefsName, data);
                return;
            }
            restorePrefs(c, prefsName, changedFields(data, exported));
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Every key-value pair of one SharedPreferences file, as JSON. */
    public static LinkedJSONObject snapshotPrefs(Context c, String prefsName) {
        LinkedJSONObject out = new LinkedJSONObject();
        try {
            if (c == null) {
                return out;
            }
            android.content.SharedPreferences sp = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                Object v = e.getValue();
                if (v instanceof java.util.Set) {
                    out.put(e.getKey(), new JSONArray((java.util.Set<?>) v));
                } else if (v != null) {
                    out.put(e.getKey(), v);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return out;
    }

    /** Write every entry of the JSON object back into one SharedPreferences file. */
    public static void restorePrefs(Context c, String prefsName, LinkedJSONObject data) {
        try {
            if (c == null || data == null || data.length() == 0) {
                return;
            }
            android.content.SharedPreferences sp = c.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor edit = sp.edit();
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = data.get(k);
                if (v instanceof Boolean) {
                    edit.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    edit.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    edit.putLong(k, (Long) v);
                } else if (v instanceof Float) {
                    edit.putFloat(k, (Float) v);
                } else if (v instanceof Double) {
                    // numbers without a decimal point parse as int/long upstream
                    edit.putFloat(k, (float) (double) (Double) v);
                } else if (v instanceof JSONArray) {
                    java.util.Set<String> set = new java.util.HashSet<>();
                    JSONArray arr = (JSONArray) v;
                    for (int i = 0; i < arr.length(); i++) {
                        set.add(arr.optString(i));
                    }
                    edit.putStringSet(k, set);
                } else {
                    edit.putString(k, v == null ? null : v.toString());
                }
            }
            edit.commit();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Section-wise union for the WebDAV sync: a section present on this device
     * wins; sections missing locally are taken from the remote file.
     */
    public static LinkedJSONObject mergeMisc(LinkedJSONObject local, LinkedJSONObject remote) {
        try {
            LinkedJSONObject out = new LinkedJSONObject();
            String[] sections = {SEC_APPSP, SEC_OPDS, SEC_PASSWORD, SEC_POPUPS};
            for (String section : sections) {
                LinkedJSONObject l = local.optJSONObject(section);
                LinkedJSONObject r = remote.optJSONObject(section);
                LinkedJSONObject pick = l != null && l.length() > 0 ? l : r;
                if (pick != null && pick.length() > 0) {
                    out.put(section, pick);
                }
            }
            return out;
        } catch (Exception e) {
            return local;
        }
    }

    // ------------------------------------------------------------------ AI

    /**
     * Mirror the AI config into app-AI.json (before export / sync): the
     * active key (legacy field) plus the PER-VENDOR backup — every saved
     * vendor profile as one config item keyed by its name, restored per
     * vendor on the other devices.
     */
    public static void exportAi(Context c) {
        try {
            if (AppProfile.syncAI == null || c == null) {
                return;
            }
            LinkedJSONObject o = new LinkedJSONObject();
            o.put(K_API_KEY, AiCredentials.load(c));
            o.put(K_ACTIVE, AppState.get().aiConfigName == null ? "" : AppState.get().aiConfigName);
            LinkedJSONObject vendors = new LinkedJSONObject();
            JSONArray arr = parseAiProfiles(AppState.get().aiConfigs);
            for (int i = 0; i < arr.length(); i++) {
                LinkedJSONObject p = asLinked(arr.opt(i));
                if (p != null && TxtUtils.isNotEmpty(p.optString("name"))) {
                    vendors.put(p.optString("name"), p);
                }
            }
            o.put(K_VENDORS, vendors);
            writeIfChanged(AppProfile.syncAI, o.toString());
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Apply the merged app-AI.json key to the local encrypted store (after
     * import / sync). The file holds the post-merge value, so this fills a
     * missing key (restore) and also converges a conflict to the server copy
     * (mergeAi resolves "both set, different" to the server). Safe to apply
     * whenever it differs from the store: exportAi re-mirrors the store into
     * the file at the start of every sync, so a key just saved in the AI
     * dialog is already in the file before the merge and is never clobbered
     * by a stale value.
     */
    public static void importAi(Context c) {
        try {
            if (AppProfile.syncAI == null || !AppProfile.syncAI.isFile() || c == null) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncAI);
            String fileKey = o.optString(K_API_KEY, "");
            if (!fileKey.equals(AiCredentials.load(c))) {
                AiCredentials.save(c, fileKey);
            }
            // per-vendor restore: every backed-up vendor is upserted into
            // the saved list by name — a vendor added on another device
            // appears here with ALL of its fields (key included); local
            // vendors the server never saw stay untouched
            LinkedJSONObject vendors = o.optJSONObject(K_VENDORS);
            if (vendors != null && vendors.length() > 0) {
                JSONArray cur = parseAiProfiles(AppState.get().aiConfigs);
                boolean changed = false;
                Iterator<String> names = vendors.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    LinkedJSONObject v = asLinked(vendors.opt(name));
                    if (v == null || TxtUtils.isEmpty(name)) {
                        continue;
                    }
                    int hit = aiProfileIndex(cur, name);
                    if (hit < 0) {
                        cur.put(v);
                        changed = true;
                        android.util.Log.i("BENCH", "ai restore: vendor added " + name);
                    } else {
                        LinkedJSONObject lp = asLinked(cur.opt(hit));
                        if (lp == null || !lp.toString().equals(v.toString())) {
                            cur.put(hit, v);
                            changed = true;
                            android.util.Log.i("BENCH", "ai restore: vendor updated " + name);
                        }
                    }
                }
                if (changed) {
                    AppState.get().aiConfigs = cur.toString();
                }
            }
            // a device without an active profile adopts the backed-up one
            String active = o.optString(K_ACTIVE, "");
            if (TxtUtils.isNotEmpty(active) && TxtUtils.isEmpty(AppState.get().aiConfigName)) {
                LinkedJSONObject p = asLinked(vendors == null ? null : vendors.opt(active));
                if (p != null) {
                    adoptAiProfile(p, active, c);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Parsed saved AI vendor profiles of the app-state string (never null). */
    private static JSONArray parseAiProfiles(String json) {
        try {
            return new JSONArray(json == null || json.trim().isEmpty() ? "[]" : json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Wrapped parse of one JSON value into an ordered object (null-safe). */
    private static LinkedJSONObject asLinked(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new LinkedJSONObject(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static int aiProfileIndex(JSONArray arr, String name) {
        for (int i = 0; i < arr.length(); i++) {
            LinkedJSONObject p = asLinked(arr.opt(i));
            if (p != null && name.equals(p.optString("name"))) {
                return i;
            }
        }
        return -1;
    }

    /** Mirror a vendor profile into the active config fields + runtime key. */
    private static void adoptAiProfile(LinkedJSONObject p, String name, Context c) {
        AppState.get().aiConfigName = name;
        AppState.get().aiProtocol = p.optString("protocol", AppState.get().aiProtocol);
        AppState.get().aiBaseUrl = p.optString("baseUrl", AppState.get().aiBaseUrl);
        AppState.get().aiModel = p.optString("model", AppState.get().aiModel);
        int tokens = p.optInt("maxTokens", AppState.get().aiMaxTokens);
        if (tokens > 0) {
            AppState.get().aiMaxTokens = tokens;
        }
        AppState.get().aiThinking = p.optBoolean("thinking", AppState.get().aiThinking);
        String k = p.optString("apiKey", "");
        if (TxtUtils.isNotEmpty(k)) {
            AiCredentials.save(c, k);
        }
        android.util.Log.i("BENCH", "ai restore: active adopted " + name);
    }

    /**
     * Merge of the AI key file for WebDavSyncer.syncMergedObjectFile: a set key
     * beats an unset one, and a real conflict (both sides set, different)
     * resolves to the server copy. A freshly reset device (empty local key)
     * therefore recovers the server key instead of its just-exported empty
     * file winning a whole-file mtime race and clobbering the server.
     */
    public static LinkedJSONObject mergeAi(LinkedJSONObject local, LinkedJSONObject remote) {
        try {
            String localKey = local == null ? "" : local.optString(K_API_KEY, "");
            String remoteKey = remote == null ? "" : remote.optString(K_API_KEY, "");
            LinkedJSONObject out = new LinkedJSONObject();
            if (TxtUtils.isEmpty(localKey)) {
                out.put(K_API_KEY, remoteKey);
            } else if (TxtUtils.isEmpty(remoteKey)) {
                out.put(K_API_KEY, localKey);
            } else {
                out.put(K_API_KEY, remoteKey); // both set: the server copy wins
            }
            // active profile name: the server copy wins when it has one
            String localActive = local == null ? "" : local.optString(K_ACTIVE, "");
            String remoteActive = remote == null ? "" : remote.optString(K_ACTIVE, "");
            out.put(K_ACTIVE, TxtUtils.isEmpty(remoteActive) ? localActive : remoteActive);
            // per-vendor union keyed by the profile name: a vendor present
            // on only one side is kept (restore); the same name with
            // different content resolves to the server copy
            LinkedJSONObject vendors = new LinkedJSONObject();
            LinkedJSONObject lv = local == null ? null : local.optJSONObject(K_VENDORS);
            if (lv != null) {
                Iterator<String> it = lv.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    LinkedJSONObject v = asLinked(lv.opt(k));
                    if (v != null) {
                        vendors.put(k, v);
                    }
                }
            }
            LinkedJSONObject rv = remote == null ? null : remote.optJSONObject(K_VENDORS);
            if (rv != null) {
                Iterator<String> it = rv.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    LinkedJSONObject v = asLinked(rv.opt(k));
                    if (v == null) {
                        continue;
                    }
                    LinkedJSONObject cur = asLinked(vendors.opt(k));
                    if (cur == null || !cur.toString().equals(v.toString())) {
                        vendors.put(k, v);
                    }
                }
            }
            out.put(K_VENDORS, vendors);
            return out;
        } catch (Exception e) {
            return remote;
        }
    }

    // ---------------------------------------------------------- AI model config (inside app-State.json)

    private static final String[] AI_STATE_FIELDS = {"aiProtocol", "aiBaseUrl", "aiModel", "aiMaxTokens", "aiThinking"};
    /** AppState.aiMaxTokens default — a field equal to it counts as "not set". */
    private static final int AI_MAX_TOKENS_DEFAULT = 4096;

    /**
     * Union merge of the AI model config inside app-State.json so the config
     * survives a device reset: after a reset the freshly created local state
     * file is always "newer" (its mtime is now), and plain newer-wins would
     * clobber the server copy with empty fields. A set value beats an unset
     * one; on a real conflict the newer side wins.
     */
    public static LinkedJSONObject mergeAiState(LinkedJSONObject local, LinkedJSONObject remote, boolean remoteNewer) {
        try {
            LinkedJSONObject newer = remoteNewer ? remote : local;
            LinkedJSONObject out = new LinkedJSONObject(newer.toString());
            for (String k : AI_STATE_FIELDS) {
                Object lv = local.opt(k);
                Object rv = remote.opt(k);
                boolean lSet = aiFieldSet(lv);
                boolean rSet = aiFieldSet(rv);
                if (lSet && rSet) {
                    continue; // real conflict — the newer side (base) keeps it
                }
                if (lSet) {
                    out.put(k, lv);
                } else if (rSet) {
                    out.put(k, rv);
                }
            }
            return out;
        } catch (Exception e) {
            LOG.e(e);
            return remoteNewer ? remote : local;
        }
    }

    /** "Set" per field type: strings non-empty, tokens ≠ default, thinking always. */
    static boolean aiFieldSet(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Integer) {
            return ((Integer) v) != AI_MAX_TOKENS_DEFAULT;
        }
        if (v instanceof Boolean) {
            return true;
        }
        return TxtUtils.isNotEmpty(String.valueOf(v));
    }

    /**
     * Re-read the synced app-State.json into the live AppState (in place), so
     * settings synced from the server — the AI model config above included —
     * apply immediately instead of waiting for an app restart.
     */
    public static void importAppState() {
        try {
            if (AppProfile.syncState == null || !AppProfile.syncState.isFile()) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncState);
            if (o.length() == 0) {
                return;
            }
            com.foobnix.android.utils.Objects.loadFromJson(AppState.get(), o);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Re-read the synced app-CSS.json into the live BookCSS (in place), so the
     * styling merged from the server applies to the running app — and is not
     * clobbered by the stale in-memory copy when AppProfile.save() persists
     * the profile right after the sync (which would then publish the stale
     * values to the server on the second CSS sync of the same round). The
     * merged file keeps the local device-bound path fields, so loading it
     * wholesale is safe. Must run BEFORE importNetworkSources(): the dedicated
     * network-source file wins for searchPathsJson (书库文件夹).
     */
    public static void importCss() {
        try {
            if (AppProfile.syncCSS == null || !AppProfile.syncCSS.isFile()) {
                return;
            }
            LinkedJSONObject o = IO.readJsonObject(AppProfile.syncCSS);
            if (o.length() == 0) {
                return;
            }
            com.foobnix.android.utils.Objects.loadFromJson(BookCSS.get(), o);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    // ------------------------------------------------- network sources (OPDS / WebDAV / 书库文件夹)

    private static final String SEC_NET_OPDS = "opds";
    private static final String SEC_NET_WEBDAV = "webdav";
    private static final String SEC_NET_SMB = "smb";
    private static final String SEC_NET_SFTP = "sftp";
    private static final String SEC_NET_FOLDERS = "folders";

    /**
     * Snapshot the user-configured "My files" sources into
     * app-NetworkSources.json as ONE dedicated config file where every
     * added entry is its own sub-item carrying ALL of its fields: OPDS
     * catalog lines, WebDAV servers (incl. stored login/password), SMB and
     * SFTP servers (incl. stored password / key passphrase) and the
     * 书库文件夹 paths. The file is merged PER ITEM (see mergeNetworkSources3),
     * so N backed-up SFTP entries restore as those same N entries. Written
     * only when the content changed — an unconditional write would restamp
     * the file on every sync and block incoming changes.
     */
    public static void exportNetworkSources(Context c) {
        try {
            if (AppProfile.syncNetworkSources == null) {
                return;
            }
            LinkedJSONObject root = new LinkedJSONObject();
            root.put(SEC_NET_OPDS, rawLines(AppState.get().allOPDSLinks));
            JSONArray webdav = new JSONArray();
            for (WebDavServer srv : WebDavStore.load()) {
                LinkedJSONObject item = new LinkedJSONObject();
                item.put("url", srv.url);
                item.put("title", srv.title == null ? "" : srv.title);
                item.put("startDir", srv.startDir == null ? "" : srv.startDir);
                if (c != null) {
                    String[] creds = WebDavCredentials.load(c, srv.url);
                    item.put("login", creds != null ? creds[0] : "");
                    item.put("password", creds != null ? creds[1] : "");
                    item.put("trustAll", WebDavCredentials.isTrustAll(c, srv.url));
                }
                webdav.put(item);
            }
            root.put(SEC_NET_WEBDAV, webdav);
            root.put(SEC_NET_SMB, remoteItems(RemoteBook.TYPE_SMB, c));
            root.put(SEC_NET_SFTP, remoteItems(RemoteBook.TYPE_SFTP, c));
            JSONArray folders = new JSONArray();
            for (String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
                if (TxtUtils.isNotEmpty(path)) {
                    folders.put(path);
                }
            }
            root.put(SEC_NET_FOLDERS, folders);
            writeIfChanged(AppProfile.syncNetworkSources, root.toString());
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** One SMB/SFTP server as a backup item with ALL of its fields. */
    private static JSONArray remoteItems(String type, Context c) {
        JSONArray arr = new JSONArray();
        for (RemoteServer srv : RemoteStore.load(type)) {
            LinkedJSONObject item = new LinkedJSONObject();
            item.put("id", srv.id);
            item.put("title", srv.title == null ? "" : srv.title);
            item.put("host", srv.host == null ? "" : srv.host);
            item.put("port", srv.port);
            item.put("user", srv.user == null ? "" : srv.user);
            item.put("domain", srv.domain == null ? "" : srv.domain);
            item.put("share", srv.share == null ? "" : srv.share);
            item.put("keyPath", srv.keyPath == null ? "" : srv.keyPath);
            item.put("trustAll", srv.trustAll);
            item.put("startDir", srv.startDir == null ? "" : srv.startDir);
            if (c != null) {
                String[] creds = WebDavCredentials.load(c, RemoteStore.credentialsKey(srv.id));
                item.put("password", creds != null ? creds[1] : "");
                if (RemoteBook.TYPE_SFTP.equals(type)) {
                    String[] kp = WebDavCredentials.load(c, RemoteStore.keyPassKey(srv.id));
                    item.put("keyPass", kp != null ? kp[1] : "");
                }
            }
            arr.put(item);
        }
        return arr;
    }

    /**
     * Whole-file sync compares modification times: only touch the file when
     * the serialized content actually changed, preserving the mtime otherwise.
     */
    private static void writeIfChanged(File f, String text) {
        try {
            if (f.isFile()) {
                final String current = IO.readString(f);
                if (normalizeJson(current).equals(normalizeJson(text))) {
                    return;
                }
            }
            IO.writeObjSync(f, text);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Canonical text of a JSON document for content comparison. */
    private static String normalizeJson(String s) {
        try {
            return new LinkedJSONObject(s).toString();
        } catch (Exception e) {
            return s == null ? "" : s.trim();
        }
    }

    /** Non-empty ';'-separated segments of one app-state link string. */
    private static JSONArray rawLines(String links) {
        JSONArray out = new JSONArray();
        for (String line : (links == null ? "" : links).split(";")) {
            if (TxtUtils.isNotEmpty(line)) {
                out.put(line);
            }
        }
        return out;
    }

    /**
     * Apply the merged network-source snapshot PER ITEM: every entry
     * missing locally is added back (restore), an entry that exists is
     * updated from the merged copy when it differs, and local-only
     * entries stay untouched. The caller persists AppState / BookCSS
     * afterwards.
     */
    public static void importNetworkSources(Context c) {
        try {
            if (AppProfile.syncNetworkSources == null || !AppProfile.syncNetworkSources.isFile()) {
                return;
            }
            LinkedJSONObject root = IO.readJsonObject(AppProfile.syncNetworkSources);
            if (root.length() == 0) {
                return;
            }
            importOpdsLines(root);
            importWebDavItems(c, root);
            importRemoteItems(c, root, SEC_NET_SMB);
            importRemoteItems(c, root, SEC_NET_SFTP);
            importFolders(root);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** OPDS entries: restore every catalog line missing locally. */
    private static void importOpdsLines(LinkedJSONObject root) {
        JSONArray arr = root.optJSONArray(SEC_NET_OPDS);
        if (arr == null || arr.length() == 0) {
            return;
        }
        Set<String> known = new HashSet<String>();
        String cur = AppState.get().allOPDSLinks == null ? "" : AppState.get().allOPDSLinks;
        for (String line : cur.split(";")) {
            if (TxtUtils.isNotEmpty(line)) {
                known.add(line);
            }
        }
        StringBuilder sb = new StringBuilder(cur);
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            String line = arr.optString(i);
            if (TxtUtils.isNotEmpty(line) && known.add(line)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') {
                    sb.append(';');
                }
                sb.append(line).append(';');
                added++;
            }
        }
        if (added > 0) {
            AppState.get().allOPDSLinks = sb.toString();
            android.util.Log.i("BENCH", "net restore: opds +" + added);
        }
    }

    /**
     * WebDAV entries: restore every server missing locally (with its
     * stored credentials) and refresh an existing server's title/
     * startDir/credentials when the merged copy differs. Legacy v1 raw
     * line elements are handled too.
     */
    private static void importWebDavItems(Context c, LinkedJSONObject root) {
        JSONArray arr = root.optJSONArray(SEC_NET_WEBDAV);
        if (arr == null || arr.length() == 0 || c == null) {
            return;
        }
        List<WebDavServer> locals = WebDavStore.load();
        int added = 0, updated = 0;
        for (int i = 0; i < arr.length(); i++) {
            LinkedJSONObject item = asLinked(arr.opt(i));
            String url = item == null ? "" : WebDavStore.trimSlash(item.optString("url"));
            if (item == null || TxtUtils.isEmpty(url) || isCorruptWebDavUrl(url)) {
                // legacy v1 element: the raw "url,title,startDir" line
                String line = arr.optString(i);
                String[] it = TxtUtils.isEmpty(line) ? new String[0] : line.split(",");
                if (it.length == 0 || TxtUtils.isEmpty(it[0])) {
                    continue;
                }
                url = WebDavStore.trimSlash(it[0].trim());
                if (TxtUtils.isEmpty(url) || webdavKnown(locals, url)) {
                    continue;
                }
                WebDavServer ns = new WebDavServer(url, it.length > 1 && TxtUtils.isNotEmpty(it[1]) ? it[1] : url,
                        it.length > 2 ? it[2].trim() : "");
                ns.appState = WebDavServer.buildLine(ns.url, ns.title, ns.startDir);
                WebDavStore.add(ns);
                locals.add(ns);
                added++;
                continue;
            }
            WebDavServer hit = findWebDav(locals, url);
            if (hit == null) {
                WebDavServer ns = new WebDavServer(url, item.optString("title", url),
                        item.optString("startDir", ""));
                ns.appState = WebDavServer.buildLine(ns.url, ns.title, ns.startDir);
                WebDavStore.add(ns);
                locals.add(ns);
                added++;
                android.util.Log.i("BENCH", "net restore: webdav +" + url);
            } else if (applyWebDavUpdate(hit, item)) {
                updated++;
            }
            // credentials: restore / refresh — an empty backed-up value
            // never wipes the local one
            String[] cur = WebDavCredentials.load(c, url);
            String curLogin = cur != null ? cur[0] : "";
            String curPass = cur != null ? cur[1] : "";
            String login = item.optString("login", curLogin);
            String password = item.optString("password", curPass);
            if ((!TxtUtils.isEmpty(login) && !login.equals(curLogin))
                    || (!TxtUtils.isEmpty(password) && !password.equals(curPass))) {
                WebDavCredentials.save(c, url, login, password);
            }
            if (item.has("trustAll")) {
                WebDavCredentials.saveTrust(c, url, item.optBoolean("trustAll", false));
            }
        }
        if (added > 0 || updated > 0) {
            android.util.Log.i("BENCH", "net restore: webdav +" + added + " ~" + updated);
        }
    }

    /** A legacy bug serialized JSON objects into the link lines; such
     * entries never re-enter the sync copy (the store sanitizes itself). */
    private static boolean isCorruptWebDavUrl(String url) {
        return url.indexOf('{') >= 0 || url.indexOf('"') >= 0
                || !(url.startsWith("http://") || url.startsWith("https://"));
    }

    private static boolean webdavKnown(List<WebDavServer> list, String url) {
        return findWebDav(list, url) != null;
    }

    private static WebDavServer findWebDav(List<WebDavServer> list, String url) {
        for (WebDavServer srv : list) {
            if (WebDavStore.trimSlash(srv.url).equals(url)) {
                return srv;
            }
        }
        return null;
    }

    /** Server-wins field update of an existing WebDAV entry; true when changed. */
    private static boolean applyWebDavUpdate(WebDavServer srv, LinkedJSONObject item) {
        String title = item.optString("title", "");
        String startDir = item.optString("startDir", "");
        String newTitle = TxtUtils.isNotEmpty(title) ? title : srv.title;
        String newStart = item.has("startDir") ? startDir : (srv.startDir == null ? "" : srv.startDir);
        String oldStart = srv.startDir == null ? "" : srv.startDir;
        if (newTitle.equals(srv.title) && newStart.equals(oldStart)) {
            return false;
        }
        String newline = WebDavServer.buildLine(srv.url, newTitle, newStart);
        AppState.get().allWebDavLinks =
                AppState.get().allWebDavLinks.replace(srv.appState, newline);
        srv.title = newTitle;
        srv.startDir = newStart;
        srv.appState = newline;
        return true;
    }

    /**
     * SMB / SFTP entries: restore every server missing locally — the
     * backed-up id is kept so remote:// links and the credential keys
     * stay stable across devices — and refresh an existing server's
     * fields / password when the merged copy differs.
     */
    private static void importRemoteItems(Context c, LinkedJSONObject root, String section) {
        JSONArray arr = root.optJSONArray(section);
        if (arr == null || arr.length() == 0 || c == null) {
            return;
        }
        String type = SEC_NET_SFTP.equals(section) ? RemoteBook.TYPE_SFTP : RemoteBook.TYPE_SMB;
        List<RemoteServer> locals = RemoteStore.load(type);
        int added = 0, updated = 0;
        for (int i = 0; i < arr.length(); i++) {
            LinkedJSONObject item = asLinked(arr.opt(i));
            if (item == null || TxtUtils.isEmpty(item.optString("host"))) {
                continue;
            }
            // stable connection identity: an edited 子目录/title must hit
            // the existing entry (applyRemoteUpdate updates it in place), not
            // re-add the merged copy as a second entry
            String key = remoteConnKey(item.optString("host"), item.optInt("port", 0),
                    item.optString("user"), item.optString("domain"),
                    item.optString("share"), item.optString("keyPath"));
            RemoteServer hit = null;
            for (RemoteServer srv : locals) {
                if (key.equals(remoteConnKeyOf(srv))) {
                    hit = srv;
                    break;
                }
            }
            if (hit == null) {
                int port = item.optInt("port", RemoteBook.TYPE_SFTP.equals(type) ? 22 : 445);
                String line = safeField(item.optString("id")) + "|" + safeField(item.optString("title"))
                        + "|" + safeField(item.optString("host")) + "|" + port
                        + "|" + safeField(item.optString("user")) + "|" + safeField(item.optString("domain"))
                        + "|" + safeField(item.optString("share")) + "|" + safeField(item.optString("keyPath"))
                        + "|" + (item.optBoolean("trustAll", true) ? "1" : "0")
                        + "|" + safeField(item.optString("startDir")) + ";";
                RemoteServer ns = RemoteServer.parse(line, type);
                if (ns == null) {
                    continue;
                }
                RemoteStore.add(ns);
                locals.add(ns);
                saveRemoteCreds(c, ns, item);
                added++;
                android.util.Log.i("BENCH", "net restore: " + type + " +" + ns.host);
            } else {
                boolean changed = applyRemoteUpdate(hit, item);
                if (saveRemoteCreds(c, hit, item)) {
                    changed = true;
                }
                if (changed) {
                    updated++;
                }
            }
        }
        if (added > 0 || updated > 0) {
            android.util.Log.i("BENCH", "net restore: " + type + " +" + added + " ~" + updated);
        }
    }

    /**
     * STABLE connection identity of an SMB/SFTP entry: everything except the
     * random per-creation id, the secrets and the freely editable payload
     * fields (title, start folder, trust flag). Editing the 子目录 must
     * UPDATE this identity — the previous key included startDir/title, so an
     * edit looked like "remove old + add new" and the pre-edit entry kept
     * surviving the merge as a ghost duplicate with the old folder.
     */
    private static String remoteConnKey(String host, int port, String user, String domain,
            String share, String keyPath) {
        return (host == null ? "" : host) + "|" + port + "|" + (user == null ? "" : user)
                + "|" + (domain == null ? "" : domain) + "|" + (share == null ? "" : share)
                + "|" + (keyPath == null ? "" : keyPath);
    }

    private static String remoteConnKeyOf(RemoteServer srv) {
        return remoteConnKey(srv.host, srv.port, srv.user, srv.domain, srv.share, srv.keyPath);
    }

    private static String remoteConnKeyOfItem(LinkedJSONObject item) {
        return remoteConnKey(item.optString("host"), item.optInt("port", 0),
                item.optString("user"), item.optString("domain"),
                item.optString("share"), item.optString("keyPath"));
    }

    /** Same connection identity, different fields → the merged copy wins. */
    private static boolean applyRemoteUpdate(RemoteServer hit, LinkedJSONObject item) {
        String newTitle = TxtUtils.isNotEmpty(item.optString("title")) ? item.optString("title") : hit.title;
        String newStart = item.has("startDir") ? item.optString("startDir")
                : (hit.startDir == null ? "" : hit.startDir);
        boolean newTrust = item.optBoolean("trustAll", hit.trustAll);
        String oldStart = hit.startDir == null ? "" : hit.startDir;
        if (newTitle.equals(hit.title) && newStart.equals(oldStart) && newTrust == hit.trustAll) {
            return false;
        }
        RemoteStore.remove(hit);
        hit.title = newTitle;
        hit.startDir = newStart;
        hit.trustAll = newTrust;
        hit.appState = RemoteServer.buildLine(hit);
        RemoteStore.add(hit);
        return true;
    }

    /** Restore the backed-up credentials; an empty backed-up value never wipes a local one. */
    private static boolean saveRemoteCreds(Context c, RemoteServer srv, LinkedJSONObject item) {
        boolean changed = false;
        String credKey = RemoteStore.credentialsKey(srv.id);
        String[] cur = WebDavCredentials.load(c, credKey);
        String curLogin = cur != null ? cur[0] : "";
        String curPass = cur != null ? cur[1] : "";
        String login = item.optString("user", curLogin);
        String password = item.optString("password", curPass);
        if (!TxtUtils.isEmpty(password) && !password.equals(curPass)) {
            WebDavCredentials.save(c, credKey, login, password);
            changed = true;
        }
        if (RemoteBook.TYPE_SFTP.equals(srv.getTypeStored())) {
            String kpKey = RemoteStore.keyPassKey(srv.id);
            String[] kp = WebDavCredentials.load(c, kpKey);
            String curKp = kp != null ? kp[1] : "";
            String keyPass = item.optString("keyPass", curKp);
            if (!TxtUtils.isEmpty(keyPass) && !keyPass.equals(curKp)) {
                WebDavCredentials.save(c, kpKey, "", keyPass);
                changed = true;
            }
        }
        if (item.has("trustAll")) {
            WebDavCredentials.saveTrust(c, credKey, item.optBoolean("trustAll", srv.trustAll));
        }
        return changed;
    }

    private static String safeField(String v) {
        return (v == null ? "" : v).replace("|", " ").replace(";", " ").trim();
    }

    /** 书库文件夹: restore every path missing locally. */
    private static void importFolders(LinkedJSONObject root) {
        JSONArray arr = root.optJSONArray(SEC_NET_FOLDERS);
        if (arr == null || arr.length() == 0) {
            return;
        }
        List<String> paths = new ArrayList<String>();
        for (String p : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (TxtUtils.isNotEmpty(p) && !paths.contains(p)) {
                paths.add(p);
            }
        }
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            String p = arr.optString(i);
            if (TxtUtils.isNotEmpty(p) && !paths.contains(p)) {
                paths.add(p);
                added++;
            }
        }
        if (added > 0) {
            JSONArray out = new JSONArray();
            for (String p : paths) {
                out.put(p);
            }
            BookCSS.get().searchPathsJson = out.toString();
            android.util.Log.i("BENCH", "net restore: folders +" + added);
        }
    }

    /**
     * Per-item merge of the network-source file: entries are keyed by
     * their stable identity (the OPDS line / server url / the SMB·SFTP
     * connection fields / the folder path) and unioned — an entry backed
     * up on one device restores on every other device, and the same
     * identity with different content resolves to the server copy.
     * Unlike the previous whole-file scheme a per-item merge cannot
     * propagate deletions: an entry removed on one device is re-added
     * from the other side's list.
     */
    /**
     * Per-item THREE-way merge of the network-source file: base = the last
     * merged result this device wrote (app-NetworkSources.json.base, the
     * same local-only convention as the app-State three-way). A field changed
     * on only one side since the base wins — an edit saved on THIS device (a
     * new 子目录) beats the stale server copy instead of being reverted by
     * it, and a change made on another device still arrives. Both sides
     * changed → local wins (the next round converges the other device);
     * without a base (first run after the upgrade) conflicts also keep the
     * local copy, so an edit made before the upgrade survives. Identities:
     * WebDAV items by server url; SMB/SFTP items grouped by the stable
     * connection fields (title/startDir/trustAll are payload, not identity);
     * OPDS lines and 书库文件夹 stay additive unions.
     */
    public static LinkedJSONObject mergeNetworkSources3(LinkedJSONObject local, LinkedJSONObject remote,
            LinkedJSONObject base) {
        try {
            LinkedJSONObject out = new LinkedJSONObject();
            out.put(SEC_NET_OPDS, unionStrings(local == null ? null : local.optJSONArray(SEC_NET_OPDS),
                    remote == null ? null : remote.optJSONArray(SEC_NET_OPDS)));
            out.put(SEC_NET_WEBDAV, mergeWebDavItems(
                    local == null ? null : local.optJSONArray(SEC_NET_WEBDAV),
                    remote == null ? null : remote.optJSONArray(SEC_NET_WEBDAV),
                    base == null ? null : base.optJSONArray(SEC_NET_WEBDAV)));
            out.put(SEC_NET_SMB, mergeRemoteGroups(
                    local == null ? null : local.optJSONArray(SEC_NET_SMB),
                    remote == null ? null : remote.optJSONArray(SEC_NET_SMB),
                    base == null ? null : base.optJSONArray(SEC_NET_SMB)));
            out.put(SEC_NET_SFTP, mergeRemoteGroups(
                    local == null ? null : local.optJSONArray(SEC_NET_SFTP),
                    remote == null ? null : remote.optJSONArray(SEC_NET_SFTP),
                    base == null ? null : base.optJSONArray(SEC_NET_SFTP)));
            out.put(SEC_NET_FOLDERS, unionStrings(local == null ? null : local.optJSONArray(SEC_NET_FOLDERS),
                    remote == null ? null : remote.optJSONArray(SEC_NET_FOLDERS)));
            return out;
        } catch (Exception e) {
            LOG.e(e);
            return remote;
        }
    }

    /** Union of string items (OPDS lines / folder paths); identity = the string. */
    private static JSONArray unionStrings(JSONArray la, JSONArray ra) {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        if (la != null) {
            for (int i = 0; i < la.length(); i++) {
                String v = la.optString(i);
                if (TxtUtils.isNotEmpty(v) && seen.add(v)) {
                    out.put(v);
                }
            }
        }
        if (ra != null) {
            for (int i = 0; i < ra.length(); i++) {
                String v = ra.optString(i);
                if (TxtUtils.isNotEmpty(v) && seen.add(v)) {
                    out.put(v);
                }
            }
        }
        return out;
    }

    /** url -> item for one WebDAV array (legacy v1 lines parsed into items). */
    private static LinkedHashMap<String, LinkedJSONObject> webdavMap(JSONArray arr) {
        LinkedHashMap<String, LinkedJSONObject> m = new LinkedHashMap<String, LinkedJSONObject>();
        if (arr == null) {
            return m;
        }
        for (int i = 0; i < arr.length(); i++) {
            LinkedJSONObject item = asLinked(arr.opt(i));
            String url;
            if (item != null) {
                url = WebDavStore.trimSlash(item.optString("url"));
                if (TxtUtils.isEmpty(url) || isCorruptWebDavUrl(url)) {
                    continue;
                }
                item.put("url", url);
            } else {
                // legacy v1 element: the raw "url,title,startDir" line
                String line = arr.optString(i);
                String[] it = TxtUtils.isEmpty(line) ? new String[0] : line.split(",");
                if (it.length == 0 || TxtUtils.isEmpty(it[0])) {
                    continue;
                }
                url = WebDavStore.trimSlash(it[0].trim());
                if (TxtUtils.isEmpty(url) || isCorruptWebDavUrl(url)) {
                    continue;
                }
                item = new LinkedJSONObject();
                item.put("url", url);
                item.put("title", it.length > 1 ? it[1] : url);
                item.put("startDir", it.length > 2 ? it[2].trim() : "");
            }
            m.put(url, item);
        }
        return m;
    }

    /** Field-level three-way of the WebDAV items keyed by server url. */
    private static JSONArray mergeWebDavItems(JSONArray la, JSONArray ra, JSONArray ba) {
        LinkedHashMap<String, LinkedJSONObject> lm = webdavMap(la);
        LinkedHashMap<String, LinkedJSONObject> rm = webdavMap(ra);
        LinkedHashMap<String, LinkedJSONObject> bm = webdavMap(ba);
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(lm.keySet());
        keys.addAll(rm.keySet());
        keys.addAll(bm.keySet());
        JSONArray out = new JSONArray();
        for (String url : keys) {
            LinkedJSONObject l = lm.get(url), r = rm.get(url), b = bm.get(url);
            LinkedJSONObject keep;
            if (l == null) {
                keep = r;                 // restore a remote addition / other device's entry
            } else if (r == null || r.toString().equals(l.toString())) {
                keep = l;                 // deletion not propagated / already equal
            } else if (b == null || r.toString().equals(b.toString())) {
                keep = l;                 // no base, or remote unchanged: keep the local edit
            } else if (l.toString().equals(b.toString())) {
                keep = r;                 // locally unchanged: accept the remote edit
            } else {
                keep = mergeItemFields(l, r, b); // both changed: per field, local wins ties
            }
            if (keep != null) {
                out.put(keep);
            }
        }
        return out;
    }

    /**
     * Field-level three-way of one config item: a field the local side did
     * not change since the base adopts the remote value; a field the local
     * side changed (or both sides changed) keeps the local value.
     */
    private static LinkedJSONObject mergeItemFields(LinkedJSONObject l, LinkedJSONObject r,
            LinkedJSONObject b) {
        LinkedJSONObject out = new LinkedJSONObject();
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
        for (java.util.Iterator<String> it = l.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (java.util.Iterator<String> it = r.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (java.util.Iterator<String> it = b.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (String k : keys) {
            boolean lh = l.has(k), rh = r.has(k), bh = b.has(k);
            Object lv = lh ? l.opt(k) : null, rv = rh ? r.opt(k) : null, bv = bh ? b.opt(k) : null;
            boolean localChanged = lh != bh || (lh && !String.valueOf(lv).equals(String.valueOf(bv)));
            boolean remoteChanged = rh != bh || (rh && !String.valueOf(rv).equals(String.valueOf(bv)));
            if (localChanged) {
                if (lh) {
                    out.put(k, lv);
                }
            } else if (remoteChanged) {
                if (rh) {
                    out.put(k, rv);
                }
            } else {
                if (lh) {
                    out.put(k, lv);
                }
            }
        }
        return out;
    }

    /** Order-insensitive content fingerprint of one connection group. */
    private static String groupFinger(java.util.List<LinkedJSONObject> g) {
        java.util.List<String> parts = new ArrayList<String>();
        for (LinkedJSONObject it : g) {
            parts.add(it.toString());
        }
        java.util.Collections.sort(parts);
        return parts.toString();
    }

    /** connection identity -> items (one side of the merge). */
    private static LinkedHashMap<String, java.util.List<LinkedJSONObject>> remoteGroups(JSONArray arr) {
        LinkedHashMap<String, java.util.List<LinkedJSONObject>> m =
                new LinkedHashMap<String, java.util.List<LinkedJSONObject>>();
        if (arr == null) {
            return m;
        }
        for (int i = 0; i < arr.length(); i++) {
            LinkedJSONObject item = asLinked(arr.opt(i));
            if (item == null || TxtUtils.isEmpty(item.optString("host"))) {
                continue;
            }
            String key = remoteConnKeyOfItem(item);
            java.util.List<LinkedJSONObject> list = m.get(key);
            if (list == null) {
                list = new ArrayList<LinkedJSONObject>();
                m.put(key, list);
            }
            list.add(item);
        }
        return m;
    }

    /**
     * SMB/SFTP items grouped by the STABLE connection identity; the whole
     * group resolves three-way. Locally unchanged since the base → the
     * remote group arrives; remotely unchanged → the local group (with its
     * edited 子目录) wins and the pre-edit ghost entry the server still
     * carries is dropped; both changed → local wins. Deliberately distinct
     * entries (same server saved twice under two names) form one group and
     * survive as long as no side edits it.
     */
    private static JSONArray mergeRemoteGroups(JSONArray la, JSONArray ra, JSONArray ba) {
        LinkedHashMap<String, java.util.List<LinkedJSONObject>> lm = remoteGroups(la);
        LinkedHashMap<String, java.util.List<LinkedJSONObject>> rm = remoteGroups(ra);
        LinkedHashMap<String, java.util.List<LinkedJSONObject>> bm = remoteGroups(ba);
        java.util.Set<String> keys = new java.util.LinkedHashSet<String>(lm.keySet());
        keys.addAll(rm.keySet());
        keys.addAll(bm.keySet());
        JSONArray out = new JSONArray();
        for (String key : keys) {
            java.util.List<LinkedJSONObject> l = lm.get(key), r = rm.get(key), b = bm.get(key);
            java.util.List<LinkedJSONObject> keep;
            String lf = l == null ? null : groupFinger(l);
            String rf = r == null ? null : groupFinger(r);
            String bf = b == null ? null : groupFinger(b);
            if (l == null) {
                keep = r;
            } else if (r == null || lf.equals(rf)) {
                keep = l;
            } else if (b == null || rf.equals(bf)) {
                keep = l;
            } else if (lf.equals(bf)) {
                keep = r;
            } else {
                keep = l;
            }
            if (keep != null) {
                for (LinkedJSONObject item : keep) {
                    out.put(item);
                }
            }
        }
        return out;
    }

    /**
     * After a backup-zip restore the global config files (app-State /
     * app-CSS / app-Misc) may only exist under the source device's
     * directory: they are read from device.&lt;model&gt;/ of the current
     * model only, so a restore on a different device would silently skip
     * every setting. Adopt the most recent copy of each missing file from
     * the other device directories (a local copy always wins).
     */
    public static void adoptForeignDeviceConfigs() {
        try {
            final File profileDir = AppProfile.SYNC_FOLDER_PROFILE;
            final File deviceDir = AppProfile.SYNC_FOLDER_DEVICE_PROFILE;
            if (profileDir == null || deviceDir == null || !profileDir.isDirectory()) {
                return;
            }
            final String[] names = { AppProfile.APP_STATE_JSON, AppProfile.APP_CSS_JSON, AppProfile.APP_MISC_JSON };
            for (final String name : names) {
                if (new File(deviceDir, name).isFile()) {
                    continue;
                }
                File best = null;
                final File[] dirs = profileDir.listFiles();
                if (dirs == null) {
                    continue;
                }
                for (final File dir : dirs) {
                    if (!dir.isDirectory() || !dir.getName().startsWith(AppProfile.DEVICE_PREFIX)
                            || dir.getName().equals(AppProfile.DEVICE_MODEL)) {
                        continue;
                    }
                    final File candidate = new File(dir, name);
                    if (candidate.isFile() && (best == null || candidate.lastModified() > best.lastModified())) {
                        best = candidate;
                    }
                }
                if (best != null) {
                    final File target = new File(deviceDir, name);
                    IO.copyFile(best, target);
                    // keep the three-way base snapshot in sync: with the old
                    // (own) base next to the adopted file, every personal
                    // field looked "locally changed" and was force-published
                    // over the server on the next sync — clobbering whatever
                    // the other devices had converged on since
                    try {
                        final File base = new File(target.getParentFile(), target.getName() + ".base");
                        IO.copyFile(target, base);
                    } catch (Exception baseError) {
                        LOG.e(baseError);
                    }
                    LOG.d("ProfileStateIO", "adopted", name, "from", best.getParent());
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}
