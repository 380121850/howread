package com.foobnix.webdav;

import com.foobnix.android.utils.TxtUtils;

/**
 * A configured WebDAV server. Persisted as one line in
 * {@code AppState.allWebDavLinks} with the format {@code url,title;}
 * (same shape as the OPDS catalog list, but stored in its own field).
 */
public class WebDavServer {

    public String url;
    public String title;
    /**
     * Start folder below the server root ("/books", "books/sub", "" = root).
     * Browsing and shelf scanning start there. Persisted as the optional
     * 3rd line field; older 2-field lines parse with "".
     */
    public String startDir = "";
    public String appState;

    public WebDavServer(String url, String title) {
        this.url = url;
        this.title = title;
    }

    public WebDavServer(String url, String title, String startDir) {
        this.url = url;
        this.title = title;
        this.startDir = startDir == null ? "" : startDir;
    }

    public static String buildLine(String url, String title) {
        return buildLine(url, title, "");
    }

    public static String buildLine(String url, String title, String startDir) {
        String line = url + "," + TxtUtils.fixAppState(title);
        if (TxtUtils.isNotEmpty(startDir)) {
            line += "," + TxtUtils.fixAppState(startDir);
        }
        return line + ";";
    }

    /** The URL browsing / scanning starts at: server root, or root+startDir. */
    public String startUrl() {
        String root = WebDavStore.trimSlash(url);
        String d = startDir == null ? "" : startDir.trim();
        while (d.startsWith("/")) {
            d = d.substring(1);
        }
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return TxtUtils.isEmpty(d) ? root : root + "/" + d;
    }
}
