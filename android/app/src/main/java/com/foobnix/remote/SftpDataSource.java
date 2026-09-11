package com.foobnix.remote;

import com.foobnix.android.utils.LOG;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.userauth.password.PasswordUtils;

/**
 * SFTP random-access data source via sshj
 * {@link RemoteFile#read(long, byte[], int, int)}: server-side offset reads
 * over one long-lived connection per reading session.
 */
public class SftpDataSource implements RemoteDataSource {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String keyPath;
    private final String keyPass;
    private final boolean trustAll;
    private final String remotePath;

    private SSHClient ssh;
    private SFTPClient sftp;
    private RemoteFile remoteFile;
    private long size = -1;

    static {
        try {
            // see SftpClient: use the platform default JCE, not Android's
            // crippled "BC", and drop curve25519 from the KEX proposals
            net.schmizz.sshj.common.SecurityUtils.setRegisterBouncyCastle(false);
            net.schmizz.sshj.common.SecurityUtils.setSecurityProvider(null);
        } catch (Throwable t) {
            LOG.w(t);
        }
    }

    public SftpDataSource(String host, int port, String user, String password, String keyPath,
                          String keyPass, boolean trustAll, String remotePath) {
        this.host = host;
        this.port = port > 0 ? port : 22;
        this.user = user;
        this.password = password == null ? "" : password;
        this.keyPath = keyPath == null ? "" : keyPath;
        this.keyPass = keyPass == null ? "" : keyPass;
        this.trustAll = trustAll;
        this.remotePath = remotePath == null ? "" : remotePath;
    }

    @Override
    public void open() throws IOException {
        ssh = SftpClient.createClient();
        ssh.setConnectTimeout(15000);
        ssh.setTimeout(30000);
        ssh.setRemoteCharset(java.nio.charset.Charset.forName("UTF-8"));
        if (trustAll) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            try {
                ssh.loadKnownHosts();
            } catch (Exception e) {
                LOG.w(e);
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
            }
        }
        ssh.connect(host, port);
        try {
            if (!keyPath.isEmpty()) {
                PKCS8KeyFile kf = new PKCS8KeyFile();
                if (keyPass.isEmpty()) {
                    kf.init(new File(keyPath));
                } else {
                    kf.init(new File(keyPath), PasswordUtils.createOneOff(keyPass.toCharArray()));
                }
                KeyProvider provider = kf;
                ssh.authPublickey(user, provider);
            } else {
                ssh.authPassword(user, password);
            }
        } catch (Exception e) {
            disconnectQuiet();
            throw new IOException("SFTP auth failed: " + e.getMessage(), e);
        }
        sftp = ssh.newSFTPClient();
        // SFTP paths are home-relative ("/" = the login home)
        remoteFile = sftp.open(SftpClient.toRelative(remotePath), EnumSet.of(OpenMode.READ));
        net.schmizz.sshj.sftp.FileAttributes attrs = remoteFile.fetchAttributes();
        size = attrs == null ? -1 : attrs.getSize();
        if (size < 0) {
            disconnectQuiet();
            throw new IOException("SFTP: cannot stat remote file");
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int readAt(long offset, byte[] buffer, int off, int len) throws IOException {
        if (offset >= size) {
            return 0;
        }
        // RemoteFile.read issues an async SSH_FXP_READ and waits for the
        // ack; it is safe to call concurrently but we keep it serialized to
        // bound the window size.
        synchronized (this) {
            try {
                return remoteFile.read(offset, buffer, off, len);
            } catch (IOException e) {
                LOG.e(e);
                throw e;
            }
        }
    }

    @Override
    public String versionTag() {
        Long mtime = null;
        try {
            if (remoteFile != null) {
                net.schmizz.sshj.sftp.FileAttributes attrs = remoteFile.fetchAttributes();
                if (attrs != null) {
                    mtime = (long) attrs.getMtime();
                }
            }
        } catch (Exception e) {
            LOG.w(e);
        }
        return size + "-" + (mtime == null ? "0" : mtime);
    }

    @Override
    public String name() {
        return "sftp";
    }

    private void disconnectQuiet() {
        try {
            if (sftp != null) {
                sftp.close();
            }
        } catch (Exception e) {
            LOG.w(e);
        }
        try {
            if (ssh != null) {
                ssh.disconnect();
            }
        } catch (Exception e) {
            LOG.w(e);
        }
        sftp = null;
        ssh = null;
    }

    @Override
    public void close() {
        try {
            if (remoteFile != null) {
                remoteFile.close();
            }
        } catch (Exception e) {
            LOG.w(e);
        }
        remoteFile = null;
        disconnectQuiet();
    }
}
