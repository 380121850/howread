package com.foobnix.remote;

import com.foobnix.android.utils.LOG;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;

/**
 * Server-side stat for a remote:// book: fetches the current file size
 * straight from the server (WebDAV depth-0 PROPFIND / SMB length / SFTP
 * stat). Used to heal shelf rows whose size was never filled in (scanned
 * before sizes were recorded, never opened since).
 */
public class RemoteStat {

    private RemoteStat() {
    }

    /** @return the file size from the server, or -1 when unknown / failed */
    public static long fetchSize(String remotePath) {
        String type = RemoteBook.getType(remotePath);
        String id = RemoteBook.getServerId(remotePath);
        String path = RemoteBook.getRemotePath(remotePath);
        try {
            if (RemoteBook.TYPE_WEBDAV.equals(type)) {
                for (com.foobnix.webdav.WebDavServer s : com.foobnix.webdav.WebDavStore.load()) {
                    if (RemoteSessionFactory.webdavId(s.url).equals(id)) {
                        String[] creds = com.foobnix.webdav.WebDavCredentials.load(
                                com.foobnix.LibreraApp.context, s.url);
                        boolean trustAll = com.foobnix.webdav.WebDavCredentials.isTrustAll(
                                com.foobnix.LibreraApp.context, s.url);
                        String root = com.foobnix.webdav.WebDavStore.trimSlash(s.url);
                        String login = creds == null ? "" : creds[0];
                        String pass = creds == null ? "" : creds[1];
                        long size = com.foobnix.webdav.WebDavClient.fileSize(root + path,
                                login, pass, trustAll);
                        if (size < 0) {
                            // legacy rows keep the path relative to the start
                            // folder: retry below the configured startDir
                            String sd = s.startDir == null ? "" : s.startDir.trim();
                            while (sd.startsWith("/")) {
                                sd = sd.substring(1);
                            }
                            while (sd.endsWith("/")) {
                                sd = sd.substring(0, sd.length() - 1);
                            }
                            if (!sd.isEmpty()) {
                                size = com.foobnix.webdav.WebDavClient.fileSize(
                                        root + "/" + sd + path, login, pass, trustAll);
                            }
                        }
                        return size;
                    }
                }
                return -1;
            }
            RemoteServer s = RemoteStore.find(type, id);
            if (s == null) {
                return -1;
            }
            if (RemoteBook.TYPE_SMB.equals(type)) {
                CIFSContext ctx = SmbClient.smbContext(s, null);
                String url = "smb://" + s.host + ":" + (s.port > 0 ? s.port : 445) + "/"
                        + (s.share.isEmpty() ? "" : s.share + "/")
                        + (path.equals("/") ? "" : path.substring(1));
                return new SmbFile(url, ctx).length();
            }
            if (RemoteBook.TYPE_SFTP.equals(type)) {
                SSHClient ssh = SftpClient.connect(s, null, null);
                try {
                    SFTPClient sftp = ssh.newSFTPClient();
                    try {
                        return sftp.stat(SftpClient.toRelative(path)).getSize();
                    } finally {
                        sftp.close();
                    }
                } finally {
                    SftpClient.disconnect(ssh);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return -1;
    }
}
