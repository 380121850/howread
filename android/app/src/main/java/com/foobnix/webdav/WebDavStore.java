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
            String[] list = AppState.get().allWebDavLinks.split(";");
            for (String line : list) {
                if (TxtUtils.isEmpty(line)) {
                    continue;
                }
                String[] it = line.split(",");
                if (it.length == 0 || TxtUtils.isEmpty(it[0])) {
                    continue;
                }
                String url = it[0].trim();
                String title = it.length > 1 ? it[1] : url;
                if (TxtUtils.isEmpty(title)) {
                    title = url;
                }
                WebDavServer s = new WebDavServer(url, title, it.length > 2 ? it[2].trim() : "");
                s.appState = line + ";";
                res.add(s);
            }
            return res;
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
