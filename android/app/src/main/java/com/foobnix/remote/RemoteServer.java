package com.foobnix.remote;

import com.foobnix.android.utils.TxtUtils;

/**
 * A configured remote file server (SMB or SFTP). Persisted as one line in
 * {@code AppState.allSmbLinks} / {@code AppState.allSftpLinks} with the
 * format
 *
 * <pre>id|title|host|port|user|domain|share|keyPath|trustAll;</pre>
 *
 * Passwords never enter the line: they are stored encrypted via
 * {@link com.foobnix.webdav.WebDavCredentials} keyed by
 * {@code remote://<id>} (SFTP key passphrases under {@code remotekey://<id>}).
 */
public class RemoteServer {

    public String id;
    public String title;
    public String host;
    public int port;
    public String user = "";
    /** SMB workgroup / domain, "" when unused. */
    public String domain = "";
    /** SMB share name, "" when unused. */
    public String share = "";
    /** SFTP private key file path, "" for password auth. */
    public String keyPath = "";
    /** Accept any host key (SFTP). SMB is unaffected. */
    public boolean trustAll = true;
    /**
     * Browse start path: SFTP — home-relative dir; SMB — path inside the
     * share (the share name itself lives in {@link #share}). "" = server
     * root. Persisted as the 10th line field; older lines parse fine.
     */
    public String startDir = "";

    /** The raw persisted line, used by {@link RemoteStore#remove(RemoteServer)}. */
    public String appState;

    /** "smb" or "sftp", recovered from the store field the line was loaded from. */
    private String typeStored = RemoteBook.TYPE_SMB;

    public RemoteServer() {
    }

    public RemoteServer(String type, String title, String host, int port) {
        this.id = RemoteBook.sha256(type + "|" + host + "|" + port + "|" + System.nanoTime())
                .substring(0, 12);
        this.title = title;
        this.host = host;
        this.port = port;
        this.typeStored = type;
    }

    public String getTypeStored() {
        return typeStored;
    }

    public void setTypeStored(String typeStored) {
        this.typeStored = typeStored;
    }

    /** @return the browse-URL root of this server:
     * remote://&lt;type&gt;/&lt;id&gt;[/&lt;startDir&gt;] */
    public String browseRoot() {
        if (TxtUtils.isEmpty(startDir)) {
            return RemoteBook.browseRoot(typeStored, id);
        }
        return RemoteBook.build(typeStored, id, startDir);
    }

    public static String buildLine(RemoteServer s) {
        return s.id + "|" + safe(s.title) + "|" + safe(s.host) + "|" + s.port + "|" + safe(s.user)
                + "|" + safe(s.domain) + "|" + safe(s.share) + "|" + safe(s.keyPath) + "|"
                + (s.trustAll ? "1" : "0") + "|" + safe(s.startDir) + ";";
    }

    public static RemoteServer parse(String line, String type) {
        String[] it = line.replace(";", "").split("\\|");
        if (it.length < 4 || TxtUtils.isEmpty(it[0])) {
            return null;
        }
        RemoteServer s = new RemoteServer();
        s.id = it[0].trim();
        s.title = it.length > 1 && TxtUtils.isNotEmpty(it[1]) ? it[1] : s.id;
        s.host = it[2].trim();
        try {
            s.port = Integer.parseInt(it[3].trim());
        } catch (Exception e) {
            s.port = RemoteBook.TYPE_SFTP.equals(type) ? 22 : 445;
        }
        s.user = it.length > 4 ? it[4] : "";
        s.domain = it.length > 5 ? it[5] : "";
        s.share = it.length > 6 ? it[6] : "";
        s.keyPath = it.length > 7 ? it[7] : "";
        s.trustAll = it.length > 8 && "1".equals(it[8].trim());
        s.startDir = it.length > 9 ? it[9].trim() : "";
        s.appState = line.contains(";") ? line : line + ";";
        s.typeStored = type;
        return s;
    }

    private static String safe(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("|", " ").replace(";", " ").trim();
    }
}
