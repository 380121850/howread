package com.foobnix.remote;

import android.text.TextUtils;

import com.foobnix.android.utils.LOG;
import com.foobnix.webdav.WebDavItem;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.userauth.password.PasswordUtils;

/**
 * SFTP directory listing for the network browser (one-shot connect per
 * listing; browsing is infrequent, reading sessions use the dedicated
 * {@link SftpDataSource}).
 */
public class SftpClient {

    public static String lastError = "";
    public static volatile boolean lastErrorWasAuth = false;

    static {
        try {
            // Android's built-in "BC" provider is crippled (Android 9 removed
            // SHA-2 digests etc.) and lacks X25519; sshj's default setup both
            // registers it and proposes curve25519 KEX. Use the platform
            // default JCE (Conscrypt: SHA-2, EC, ECDH, RSA) instead and drop
            // curve25519 from the KEX proposals (see createClient()).
            net.schmizz.sshj.common.SecurityUtils.setRegisterBouncyCastle(false);
            net.schmizz.sshj.common.SecurityUtils.setSecurityProvider(null);
        } catch (Throwable t) {
            LOG.w(t);
        }
    }

    public static List<WebDavItem> list(String browseUrl) {
        lastError = "";
        lastErrorWasAuth = false;
        try {
            String id = RemoteBook.getServerId(browseUrl);
            RemoteServer s = RemoteStore.find(RemoteBook.TYPE_SFTP, id);
            if (s == null) {
                lastError = "other";
                android.util.Log.i("REMOTE", "sftp list: server not found for " + browseUrl);
                return null;
            }
            String dir = RemoteBook.getRemotePath(browseUrl);
            SSHClient ssh = connect(s);
            try {
                SFTPClient sftp = ssh.newSFTPClient();
                // SFTP paths are home-relative ("/" = the login home)
                List<RemoteResourceInfo> kids = sftp.ls(toRelative(dir));
                List<WebDavItem> items = new ArrayList<WebDavItem>();
                for (RemoteResourceInfo k : kids) {
                    WebDavItem it = new WebDavItem();
                    it.isDir = k.isDirectory();
                    it.name = k.getName();
                    it.size = k.getAttributes().getSize();
                    it.href = browseUrl.endsWith("/") ? browseUrl + it.name : browseUrl + "/" + it.name;
                    items.add(it);
                }
                SmbClient.sort(items);
                android.util.Log.i("REMOTE", "sftp list ok dir=" + dir + " count=" + items.size());
                return items;
            } finally {
                disconnect(ssh);
            }
        } catch (Exception e) {
            android.util.Log.i("REMOTE", "sftp list failed: " + e, e);
            LOG.e(e);
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("Auth") || msg.contains("auth") || msg.contains("permission")) {
                lastErrorWasAuth = true;
                lastError = "auth";
            } else {
                lastError = "network";
            }
            return null;
        }
    }

    /**
     * Android's built-in BouncyCastle lacks the X25519 KeyPairGenerator, so
     * sshj's default curve25519-sha256 KEX proposal always crashes the
     * handshake ("no such algorithm: X25519 for provider BC"). Drop it and
     * let the negotiation fall back to ECDH / DH groups, which work.
     */
    public static SSHClient createClient() {
        net.schmizz.sshj.Config config = new net.schmizz.sshj.DefaultConfig() {
            @Override
            protected void initKeyExchangeFactories() {
                super.initKeyExchangeFactories();
                java.util.List<net.schmizz.sshj.common.Factory.Named<net.schmizz.sshj.transport.kex.KeyExchange>> kept =
                        new java.util.ArrayList<net.schmizz.sshj.common.Factory.Named<net.schmizz.sshj.transport.kex.KeyExchange>>();
                for (net.schmizz.sshj.common.Factory.Named<net.schmizz.sshj.transport.kex.KeyExchange> f
                        : getKeyExchangeFactories()) {
                    if (!f.getName().contains("curve25519")) {
                        kept.add(f);
                    }
                }
                setKeyExchangeFactories(kept);
            }
        };
        return new SSHClient(config);
    }

    /** Strips the leading "/" so paths resolve against the SFTP home. */
    public static String toRelative(String path) {
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() ? "." : p;
    }

    public static SSHClient connect(RemoteServer s) throws Exception {
        SSHClient ssh = createClient();
        ssh.setConnectTimeout(15000);
        ssh.setTimeout(30000);
        ssh.setRemoteCharset(java.nio.charset.Charset.forName("UTF-8"));
        if (s.trustAll) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            ssh.loadKnownHosts();
        }
        android.util.Log.i("REMOTE", "sftp connect host=" + s.host + ":" + s.port
                + " user=" + s.user + " keyPath=" + s.keyPath);
        ssh.connect(s.host, s.port > 0 ? s.port : 22);
        try {
            if (TextUtils.isEmpty(s.keyPath)) {
                String[] creds = com.foobnix.webdav.WebDavCredentials.load(com.foobnix.LibreraApp.context,
                        RemoteStore.credentialsKey(s.id));
                ssh.authPassword(s.user, creds == null ? "" : creds[1]);
            } else {
                String[] kp = com.foobnix.webdav.WebDavCredentials.load(com.foobnix.LibreraApp.context,
                        RemoteStore.keyPassKey(s.id));
                PKCS8KeyFile kf = new PKCS8KeyFile();
                if (kp == null || kp[1].isEmpty()) {
                    kf.init(new File(s.keyPath));
                } else {
                    kf.init(new File(s.keyPath), PasswordUtils.createOneOff(kp[1].toCharArray()));
                }
                ssh.authPublickey(s.user, kf);
            }
        } catch (Exception e) {
            try {
                ssh.disconnect();
            } catch (Exception ignore) {
                LOG.w(ignore);
            }
            throw e;
        }
        return ssh;
    }

    public static void disconnect(SSHClient ssh) {
        try {
            ssh.disconnect();
        } catch (Exception e) {
            LOG.w(e);
        }
    }
}
