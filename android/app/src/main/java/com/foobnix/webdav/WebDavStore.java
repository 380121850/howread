package com.foobnix.webdav;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import java.util.ArrayList;
import java.util.List;

/**
 * Read/write of the WebDAV server list from the app state. Self contained:
 * uses its own {@code AppState.allWebDavLinks} field, never touches OPDS.
 */
public class WebDavStore {

    /** Guards all read/modify/write of {@code AppState.allWebDavLinks}. */
    private static final Object LOCK = new Object();

    public static List<WebDavServer> load() {
        synchronized (LOCK) {
            List<WebDavServer> res = new ArrayList<WebDavServer>();
            StringBuilder repaired = new StringBuilder();
            boolean changed = false;
            String[] list = AppState.get().allWebDavLinks.split(";");
            for (String line : list) {
                if (TxtUtils.isEmpty(line)) {
                    continue;
                }
                String[] it = line.split(",");
                if (it.length == 0 || TxtUtils.isEmpty(it[0])) {
                    continue;
                }
                // normalize once: a line saved with a trailing slash must
                // match the slash-less keys used for credentials, trust flag
                // and findForUrl (appState keeps the raw line for remove/edit)
                String url = trimSlash(it[0].trim());
                String title = it.length > 1 ? it[1] : url;
                if (TxtUtils.isEmpty(title)) {
                    title = url;
                }
                String startDir = it.length > 2 ? it[2].trim() : "";
                // self-repair: an older build could persist a JSON object
                // instead of the plain "url,title,startDir" line — recover
                // the real server from it, drop the line when that fails
                if (url.indexOf('{') >= 0 || url.indexOf('"') >= 0) {
                    String[] fixed = repairJsonLine(line);
                    if (fixed == null) {
                        changed = true;
                        android.util.Log.i("WEBDAV", "dropped corrupt link line");
                        continue;
                    }
                    url = fixed[0];
                    title = fixed[1];
                    startDir = fixed[2];
                    changed = true;
                }
                // dedupe: recovered JSON lines can repeat an existing server
                boolean dup = false;
                for (WebDavServer ex : res) {
                    if (trimSlash(ex.url).equals(trimSlash(url))) {
                        dup = true;
                        break;
                    }
                }
                if (dup || com.foobnix.remote.RemoteTombstones.has("webdav:" + trimSlash(url))) {
                    changed = true;
                    continue;
                }
                WebDavServer s = new WebDavServer(url, title, startDir);
                s.appState = WebDavServer.buildLine(url, title, startDir);
                res.add(s);
                repaired.append(s.appState);
            }
            if (changed) {
                // persist the sanitized list (garbage/duplicate lines dropped
                // for good) — off the main thread, load() may run anywhere
                AppState.get().allWebDavLinks = repaired.toString();
                com.foobnix.pdf.info.AppsConfig.executorServiceSingle.execute(() ->
                        com.foobnix.model.AppProfile.save(com.foobnix.LibreraApp.context));
            }
            return res;
        }
    }

    /** url/title/startDir recovered from a JSON-encoded line, or null. */
    private static String[] repairJsonLine(String line) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(line);
            String url = o.optString("url", "");
            if (TxtUtils.isEmpty(url) || url.indexOf('{') >= 0 || url.indexOf('"') >= 0
                    || !(url.startsWith("http://") || url.startsWith("https://"))) {
                return null;
            }
            String title = o.optString("title", url);
            String startDir = o.optString("startDir", "");
            return new String[]{trimSlash(url), TxtUtils.isEmpty(title) ? url : title, startDir};
        } catch (Exception e) {
            return null;
        }
    }

    /** Find the server whose root URL is a prefix of the given (browsed) URL. */
    public static WebDavServer findForUrl(String url) {
        synchronized (LOCK) {
            for (WebDavServer s : load()) {
                if (isSameServer(s.url, url)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * True when {@code url} belongs to the server rooted at {@code serverUrl}.
     * Trailing slashes are stripped and the check uses a "/" separator so that
     * {@code http://host:80} never matches {@code http://host:8080}.
     */
    public static boolean isSameServer(String serverUrl, String url) {
        if (serverUrl == null || url == null) {
            return false;
        }
        String s = trimSlash(serverUrl);
        return url.equals(s) || url.startsWith(s + "/") || url.startsWith(s + "?");
    }

    public static String trimSlash(String url) {
        while (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static void add(WebDavServer s) {
        synchronized (LOCK) {
            AppState.get().allWebDavLinks = s.appState + AppState.get().allWebDavLinks;
        }
    }

    public static void remove(WebDavServer s) {
        synchronized (LOCK) {
            AppState.get().allWebDavLinks = AppState.get().allWebDavLinks.replace(s.appState, "");
        }
    }
}
