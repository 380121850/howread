package com.foobnix.remote;

import com.foobnix.android.utils.TxtUtils;

/**
 * Remote-book URI scheme shared by the three online protocols (WebDAV /
 * SMB / SFTP).
 *
 * A remote book is identified by a stable string used as the FileMeta path
 * (and therefore as the reading-progress key):
 *
 * <pre>remote://&lt;type&gt;/&lt;serverId&gt;/&lt;path&gt;</pre>
 *
 * e.g. {@code remote://sftp/a1b2c3d4/books/test.epub}. The last segment is
 * always the book file name so progress / bookmarks keyed by file name keep
 * working unchanged.
 */
public class RemoteBook {

    public static final String PREFIX = "remote://";
    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_SMB = "smb";
    public static final String TYPE_SFTP = "sftp";

    private RemoteBook() {
    }

    public static boolean isRemotePath(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    public static String build(String type, String serverId, String remotePath) {
        String p = remotePath == null ? "" : remotePath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return PREFIX + type + "/" + serverId + "/" + p;
    }

    /** @return "smb" / "sftp" / "webdav" or "" when not a remote path */
    public static String getType(String remotePath) {
        if (!isRemotePath(remotePath)) {
            return "";
        }
        String rest = remotePath.substring(PREFIX.length());
        int i = rest.indexOf('/');
        return i <= 0 ? "" : rest.substring(0, i);
    }

    public static String getServerId(String remotePath) {
        if (!isRemotePath(remotePath)) {
            return "";
        }
        String rest = remotePath.substring(PREFIX.length());
        int i = rest.indexOf('/');
        if (i <= 0) {
            return "";
        }
        rest = rest.substring(i + 1);
        int j = rest.indexOf('/');
        return j <= 0 ? rest : rest.substring(0, j);
    }

    /** The path on the remote server (after the server id), always with a leading "/". */
    public static String getRemotePath(String remotePath) {
        if (!isRemotePath(remotePath)) {
            return "";
        }
        String rest = remotePath.substring(PREFIX.length());
        int i = rest.indexOf('/');
        if (i <= 0) {
            return "/";
        }
        rest = rest.substring(i + 1);
        int j = rest.indexOf('/');
        if (j < 0) {
            return "/";
        }
        return "/" + rest.substring(j + 1);
    }

    /** The browsing URL used by the network page: remote://&lt;type&gt;/&lt;id&gt;[&lt;dir&gt;] */
    public static String browseRoot(String type, String serverId) {
        return PREFIX + type + "/" + serverId;
    }

    /**
     * Formats whose engines are random-access friendly (ZIP central directory
     * at the tail / PDF xref) and can be fed to MuPDF through the chunk-cache
     * stream without any local pre-conversion.
     */
    public static boolean isDirectOpen(String path) {
        String ext = getExt(path);
        return "pdf".equals(ext) || "epub".equals(ext) || "cbz".equals(ext) || "xps".equals(ext)
                || "oxps".equals(ext);
    }

    /**
     * MuPDF format hint for the stream open entry point. The stream is
     * seekable, so MuPDF sniffs the actual content first and only falls back
     * to the extension of this "magic" string — passing the real file name
     * is the most robust choice.
     */
    public static String magicFor(String path) {
        return com.foobnix.pdf.info.ExtUtils.getFileName(path);
    }

    public static String getExt(String path) {
        String name = com.foobnix.pdf.info.ExtUtils.getFileName(path);
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    /** Stable per-book cache key: sha256(whole URI). The versionTag lives in
     * the cache meta.json and a change wipes the directory. */
    public static String cacheKey(String remotePath) {
        return sha256(remotePath);
    }

    public static String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
