package com.foobnix.model;

import android.content.Context;

import com.foobnix.ai.AiCredentials;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.model.BookCSS;

import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

    /** Mirror the AI API key into app-AI.json (before export / sync). */
    public static void exportAi(Context c) {
        try {
            if (AppProfile.syncAI == null || c == null) {
                return;
            }
            LinkedJSONObject o = new LinkedJSONObject();
            o.put(K_API_KEY, AiCredentials.load(c));
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
        } catch (Exception e) {
            LOG.e(e);
        }
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
    private static final String SEC_NET_FOLDERS = "folders";

    /**
     * Snapshot the user-configured network sources into app-NetworkSources.json:
     * the raw OPDS catalog entries, the raw WebDAV server entries and the
     * 书库文件夹 paths. The file is synced WHOLE (newer mtime wins), so it is
     * only written when the content actually changed — an unconditional write
     * would stamp a fresh mtime on every sync, make the local copy always
     * "newer" and block incoming changes from other devices. Passwords are not
     * included here — OPDS logins ride with app-Misc.json and WebDAV passwords
     * stay device-bound.
     */
    public static void exportNetworkSources() {
        try {
            if (AppProfile.syncNetworkSources == null) {
                return;
            }
            LinkedJSONObject root = new LinkedJSONObject();
            root.put(SEC_NET_OPDS, rawLines(AppState.get().allOPDSLinks));
            root.put(SEC_NET_WEBDAV, rawLines(AppState.get().allWebDavLinks));
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
     * Apply the (whole-file) network-source snapshot into the live AppState /
     * BookCSS: the winning device's lists replace the local ones wholesale —
     * that is how deletions propagate under the whole-file scheme. The caller
     * persists them afterwards.
     */
    public static void importNetworkSources() {
        try {
            if (AppProfile.syncNetworkSources == null || !AppProfile.syncNetworkSources.isFile()) {
                return;
            }
            LinkedJSONObject root = IO.readJsonObject(AppProfile.syncNetworkSources);
            if (root.length() == 0) {
                return;
            }
            if (root.has(SEC_NET_OPDS)) {
                AppState.get().allOPDSLinks = joinLines(root.optJSONArray(SEC_NET_OPDS));
            }
            if (root.has(SEC_NET_WEBDAV)) {
                AppState.get().allWebDavLinks = joinLines(root.optJSONArray(SEC_NET_WEBDAV));
            }
            if (root.has(SEC_NET_FOLDERS)) {
                JSONArray folders = root.optJSONArray(SEC_NET_FOLDERS);
                JSONArray paths = new JSONArray();
                for (int i = 0; i < folders.length(); i++) {
                    String path = folders.optString(i);
                    if (TxtUtils.isNotEmpty(path)) {
                        paths.put(path);
                    }
                }
                BookCSS.get().searchPathsJson = paths.toString();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Rebuild one ';'-joined app-state string from a merged snapshot array. */
    private static String joinLines(JSONArray arr) {
        if (arr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String line = arr.optString(i);
            if (TxtUtils.isNotEmpty(line)) {
                sb.append(line).append(";");
            }
        }
        return sb.toString();
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
