package com.foobnix.webdav;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppProfile;

import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Per-run log of the concrete config changes a WebDAV sync made: which file,
 * which field/entry, which direction (merged down / published up) and the
 * old→new values. Collected while doSync runs, then
 * <ul>
 *   <li>logged line-by-line to logcat (tag BENCH) for device debugging,</li>
 *   <li>appended to the local-only app-SyncLog.json (never uploaded, trimmed
 *   to the most recent runs) and shown in the WebDAV sync dialog.</li>
 * </ul>
 * Secret values (the AI key, anything named like a password) are masked.
 */
public class SyncChangeLog {

    private static final int MAX_RUNS = 30;
    private static final int MAX_VALUE_LEN = 90;
    private static final String MASK = "******";

    /** Items of the run currently being collected. */
    private static final List<LinkedJSONObject> ITEMS = new ArrayList<LinkedJSONObject>();

    /** True between begin() and the first commit(): without this guard an
     * exception BEFORE begin() made the catch-path commit commit the
     * PREVIOUS run's items again (duplicated log runs). */
    private static volatile boolean begun = false;

    public static void begin() {
        synchronized (ITEMS) {
            ITEMS.clear();
        }
        begun = true;
    }

    /** True when the current run recorded at least one config change. */
    public static boolean hasItems() {
        synchronized (ITEMS) {
            return !ITEMS.isEmpty();
        }
    }

    /**
     * Record one changed config item.
     *
     * @param file   short file label, e.g. "app-CSS.json"
     * @param key    field/entry key, dotted for nested ("AppSP.readingMode")
     * @param action "down" (merged from the server) or "up" (published to the server)
     * @param oldV   value before the change (null for additions)
     * @param newV   value after the change (null for deletions)
     */
    public static void add(String file, String key, String action, Object oldV, Object newV) {
        final LinkedJSONObject it = new LinkedJSONObject();
        try {
            // values under a secret-ish path (book passwords, OPDS logins,
            // API keys) are BARE strings the value-shape mask cannot see —
            // mask the whole item when the dotted path smells secret
            final boolean secret = isSecretKey(file) || isSecretKey(key);
            it.put("f", file);
            it.put("k", key);
            it.put("a", action);
            it.put("o", secret ? MASK : mask(oldV));
            it.put("n", secret ? MASK : mask(newV));
        } catch (Exception e) {
            return;
        }
        synchronized (ITEMS) {
            ITEMS.add(it);
        }
        android.util.Log.i("BENCH", "syncChange " + file + " " + key + " [" + action + "] "
                + it.optString("o") + " -> " + it.optString("n"));
    }

    /** True when the dotted file/key path mentions a secret-ish field. */
    private static boolean isSecretKey(String s) {
        if (s == null) {
            return false;
        }
        final String l = s.toLowerCase(Locale.US);
        return l.contains("password") || l.contains("passwd") || l.contains("pwd")
                || l.contains("api_key") || l.contains("apikey") || l.contains("api-key")
                || l.contains("token") || l.contains("secret")
                || l.contains("login") || l.contains("opds");
    }

    /** Persist the collected run (newest first) into app-SyncLog.json. */
    public static void commit(String summary) {
        try {
            if (AppProfile.syncLog == null) {
                return;
            }
            final LinkedJSONObject root;
            if (AppProfile.syncLog.isFile()) {
                root = IO.readJsonObject(AppProfile.syncLog);
            } else {
                root = new LinkedJSONObject();
            }
            final JSONArray runs = root.optJSONArray("runs");
            final JSONArray out = new JSONArray();
            final LinkedJSONObject run = new LinkedJSONObject();
            run.put("t", System.currentTimeMillis());
            run.put("s", summary == null ? "" : summary);
            final JSONArray arr = new JSONArray();
            synchronized (ITEMS) {
                if (begun) {
                    for (LinkedJSONObject it : ITEMS) {
                        arr.put(it);
                    }
                }
            }
            run.put("items", arr);
            out.put(run);
            if (runs != null) {
                final int keep = Math.max(0, MAX_RUNS - 1);
                for (int i = 0; i < runs.length() && i < keep; i++) {
                    out.put(runs.opt(i));
                }
            }
            root.put("runs", out);
            IO.writeObjSync(AppProfile.syncLog, root);
            begun = false;
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Human-readable text of the most recent runs, for the sync dialog. */
    public static String recentText(int maxRuns) {
        try {
            if (AppProfile.syncLog == null || !AppProfile.syncLog.isFile()) {
                return "";
            }
            final LinkedJSONObject root = IO.readJsonObject(AppProfile.syncLog);
            final JSONArray runs = root.optJSONArray("runs");
            if (runs == null || runs.length() == 0) {
                return "";
            }
            final SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            final StringBuilder sb = new StringBuilder();
            final int n = Math.min(maxRuns, runs.length());
            for (int i = 0; i < n; i++) {
                final LinkedJSONObject run = runs.optJSONObject(i);
                if (run == null) {
                    continue;
                }
                sb.append("— ").append(df.format(new Date(run.optLong("t", 0))));
                final String s = run.optString("s", "");
                if (s.length() > 0) {
                    sb.append(" · ").append(s);
                }
                sb.append('\n');
                final JSONArray items = run.optJSONArray("items");
                if (items == null || items.length() == 0) {
                    sb.append("  (无配置变更)\n");
                }
                // group by direction: what this device merged FROM the server,
                // then what it updated ON the server
                String lastAction = null;
                for (int j = 0; items != null && j < items.length(); j++) {
                    final LinkedJSONObject it = items.optJSONObject(j);
                    if (it == null) {
                        continue;
                    }
                    final String a = it.optString("a", "");
                    if (!a.equals(lastAction)) {
                        sb.append("  【").append("down".equals(a) ? "本地合入了服务器的配置" : "本地更新了服务器的配置")
                          .append("】\n");
                        lastAction = a;
                    }
                    sb.append("    ").append(it.optString("f", "?")).append(" · ")
                      .append(it.optString("k", "?")).append("：")
                      .append(it.optString("o", "")).append(" → ").append(it.optString("n", ""))
                      .append('\n');
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.e(e);
            return "";
        }
    }

    /** Truncate long values and mask secrets. */
    private static String mask(Object v) {
        if (v == null) {
            return "(删除)";
        }
        String s = String.valueOf(v);
        if (s.length() == 0) {
            return "(空)";
        }
        if (s.equalsIgnoreCase(MASK)) {
            return MASK;
        }
        s = s.replaceAll("(?i)\"?(api[_-]?key|password|passwd|pwd|login)\"?\\s*[:=]\\s*\"?[^\",}]+", "$1=" + MASK);
        if (s.length() > MAX_VALUE_LEN) {
            s = s.substring(0, MAX_VALUE_LEN) + "…";
        }
        return s;
    }
}
