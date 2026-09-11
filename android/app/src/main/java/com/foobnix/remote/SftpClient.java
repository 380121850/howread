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
            List<WebDavItem> res = list(s, RemoteBook.getRemotePath(browseUrl), null, null);
            if (res != null) {
                for (WebDavItem it : res) {
                    it.href = browseUrl.endsWith("/") ? browseUrl + it.name : browseUrl + "/" + it.name;
                }
            }
            return res;
        } catch (Exception e) {
            android.util.Log.i("REMOTE", "sftp list failed: " + e, e);
            LOG.e(e);
            classify(e);
            return null;
        }
    }

    /**
     * Lists a server that may not be persisted yet (probe / directory
     * picker): {@code dir} is home-relative ("/" = login home). Credentials
     * come from the store unless given explicitly.
     */
    public static List<WebDavItem> list(RemoteServer s, String dir, String password, String keyPass) {
        lastError = "";
        lastErrorWasAuth = false;
        try {
            SSHClient ssh = connect(s, password, keyPass);
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
                    it.href = "";
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
            classify(e);
            return null;
        }
    }

    private static void classify(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("Auth") || msg.contains("auth") || msg.contains("permission")) {
            lastErrorWasAuth = true;
            lastError = "auth";
        } else {
            lastError = "network";
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
        return connect(s, null, null);
    }

    public static SSHClient connect(RemoteServer s, String password, String keyPass) throws Exception {
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
                if (password == null) {
                    String[] creds = com.foobnix.webdav.WebDavCredentials.load(com.foobnix.LibreraApp.context,
                            RemoteStore.credentialsKey(s.id));
                    password = creds == null ? "" : creds[1];
                }
                ssh.authPassword(s.user, password);
            } else {
            if (keyPass == null) {
                String[] kp = com.foobnix.webdav.WebDavCredentials.load(com.foobnix.LibreraApp.context,
                        RemoteStore.keyPassKey(s.id));
                keyPass = kp == null ? "" : kp[1];
            }
            // OpenSSH's own "-----BEGIN OPENSSH PRIVATE KEY-----" format needs
            // OpenSSHKeyFile; everything else (PKCS#1 / PKCS#8 PEM) PKCS8KeyFile
            net.schmizz.sshj.userauth.keyprovider.KeyProvider kf;
            try {
                String head = firstLine(s.keyPath);
                android.util.Log.i("REMOTE", "key provider: head=" + head);
                if (head != null && head.contains("OPENSSH PRIVATE KEY")) {
                    net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile okf =
                            new net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile();
                    if (keyPass.isEmpty()) {
                        okf.init(new File(s.keyPath));
                    } else {
                        okf.init(new File(s.keyPath), PasswordUtils.createOneOff(keyPass.toCharArray()));
                    }
                    kf = okf;
                } else {
                    PKCS8KeyFile p8 = new PKCS8KeyFile();
                    if (keyPass.isEmpty()) {
                        p8.init(new File(s.keyPath));
                    } else {
                        p8.init(new File(s.keyPath), PasswordUtils.createOneOff(keyPass.toCharArray()));
                    }
                    kf = p8;
                }
                android.util.Log.i("REMOTE", "key loaded: " + kf.getPublic().getAlgorithm());
            } catch (Throwable t) {
                android.util.Log.i("REMOTE", "key load failed: " + t, t);
                throw t;
            }
            try {
                ssh.authPublickey(s.user, kf);
            } catch (Throwable t) {
                android.util.Log.i("REMOTE", "pubkey auth failed: " + t, t);
                throw t;
            }
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

    /** First non-empty line of a text file, or null. */
    private static String firstLine(String path) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        return line;
                    }
                }
                return null;
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static void disconnect(SSHClient ssh) {
        try {
            ssh.disconnect();
        } catch (Exception e) {
            LOG.w(e);
        }
    }
}
