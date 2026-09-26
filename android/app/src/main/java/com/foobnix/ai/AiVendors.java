package com.foobnix.ai;

import android.content.Context;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Single source of truth for the AI vendor configuration (2026-09-26): the
 * saved list AppState.aiConfigs (one entry per vendor: name, protocol,
 * baseUrl, apiKey PLAINTEXT, model, maxTokens, thinking) plus the active
 * pointer AppState.aiConfigName. Requests resolve url/key/model through the
 * pointer at call time — there is no separate flat copy that can drift out
 * of sync. The pre-0926 design mirrored the active vendor into flat fields
 * plus a Keystore-bound global key store: a pm clear / reinstall destroyed
 * the Keystore while the list on /sdcard survived, silently blanking every
 * inactive vendor's key.
 *
 * apiKey is stored PLAINTEXT since 0926 (the dialog masks it on screen);
 * the sync export strips keys before anything leaves the device. A legacy
 * enc:v1: value is decrypted with THIS device's Keystore when still
 * readable and re-persisted as plain on the next save of that vendor.
 */
public class AiVendors {

    public static class Entry {
        public boolean found = false;
        public String name = "", protocol = AiClient.PROTOCOL_OPENAI;
        public String baseUrl = "", apiKey = "", model = "";
        public int maxTokens = 4096;
        public boolean thinking = false;
    }

    private static JSONArray parse(String json) {
        try {
            return new JSONArray(json == null || json.trim().isEmpty() ? "[]" : json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static String plainKey(String stored) {
        if (stored == null || !stored.startsWith(AiCredentials.ENC_PREFIX)) {
            return stored == null ? "" : stored;
        }
        return AiCredentials.decryptFromPrefixed(stored);
    }

    public static Entry fromJson(JSONObject e) {
        Entry out = new Entry();
        if (e == null) {
            return out;
        }
        out.found = true;
        out.name = e.optString("name", "");
        out.protocol = e.optString("protocol", AiClient.PROTOCOL_OPENAI);
        out.baseUrl = e.optString("baseUrl", "");
        out.apiKey = plainKey(e.optString("apiKey", ""));
        out.model = e.optString("model", "");
        out.maxTokens = e.optInt("maxTokens", 4096);
        if (out.maxTokens <= 0) {
            out.maxTokens = 4096;
        }
        out.thinking = e.optBoolean("thinking", false);
        return out;
    }

    public static Entry find(String name) {
        Entry out = new Entry();
        if (name == null || name.isEmpty()) {
            return out;
        }
        JSONArray arr = parse(AppState.get().aiConfigs);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && name.equals(e.optString("name"))) {
                Entry r = fromJson(e);
                r.name = name;
                return r;
            }
        }
        return out;
    }

    /**
     * The active vendor resolved by aiConfigName. Legacy migration: setups
     * from before 0926 kept the active values in the flat fields with the
     * key in the Keystore-backed global store — synthesize a proper entry
     * from them once so nothing configured is lost.
     */
    public static Entry active(Context c) {
        String name = AppState.get().aiConfigName;
        Entry e = find(name);
        if (e.found) {
            return e;
        }
        if (TxtUtils.isNotEmpty(AppState.get().aiBaseUrl)
                && TxtUtils.isNotEmpty(AppState.get().aiModel)) {
            Entry m = new Entry();
            m.found = true;
            m.name = TxtUtils.isNotEmpty(name) ? name : "default";
            m.protocol = TxtUtils.isEmpty(AppState.get().aiProtocol)
                    ? AiClient.PROTOCOL_OPENAI : AppState.get().aiProtocol;
            m.baseUrl = AppState.get().aiBaseUrl;
            m.model = AppState.get().aiModel;
            m.apiKey = c == null ? "" : AiCredentials.load(c);
            m.maxTokens = AppState.get().aiMaxTokens > 0 ? AppState.get().aiMaxTokens : 4096;
            m.thinking = AppState.get().aiThinking;
            upsert(m);
            AppState.get().aiConfigName = m.name;
            return m;
        }
        return e;
    }

    public static void setActive(String name) {
        AppState.get().aiConfigName = name == null ? "" : name;
    }

    public static void upsert(Entry e) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("protocol", e.protocol);
            o.put("baseUrl", e.baseUrl);
            o.put("apiKey", e.apiKey == null ? "" : e.apiKey);
            o.put("model", e.model);
            o.put("maxTokens", e.maxTokens);
            o.put("thinking", e.thinking);
            JSONArray arr = parse(AppState.get().aiConfigs);
            JSONArray out = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject cur = arr.optJSONObject(i);
                if (cur == null) {
                    continue;
                }
                if (e.name.equals(cur.optString("name"))) {
                    out.put(o);
                    replaced = true;
                } else {
                    out.put(cur);
                }
            }
            if (!replaced) {
                out.put(o);
            }
            AppState.get().aiConfigs = out.toString();
        } catch (Exception ignored) {
        }
    }

    public static void remove(String name) {
        try {
            JSONArray arr = parse(AppState.get().aiConfigs);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject cur = arr.optJSONObject(i);
                if (cur != null && !name.equals(cur.optString("name"))) {
                    out.put(cur);
                }
            }
            AppState.get().aiConfigs = out.toString();
        } catch (Exception ignored) {
        }
    }

    /** True when a vendor entry with url/key/model is set and active. */
    public static boolean isConfigured(Context c) {
        Entry e = active(c);
        return e.found && TxtUtils.isNotEmpty(e.baseUrl)
                && TxtUtils.isNotEmpty(e.model) && TxtUtils.isNotEmpty(e.apiKey);
    }
}
