package com.foobnix.remote;

import android.content.SharedPreferences;

import com.foobnix.LibreraApp;

import java.security.MessageDigest;
import java.security.PublicKey;

/**
 * Trust-On-First-Use host-key store for SFTP (tech-spec §11.3): the first
 * connection to a server records the host-key fingerprint, later
 * connections must match it or the handshake is refused. Replaces the
 * promiscuous "accept everything" default.
 */
public class SshHostKeys {

    private static final String PREFS = "remote_ssh_hostkeys";

    private SshHostKeys() {
    }

    private static SharedPreferences prefs() {
        return LibreraApp.context.getSharedPreferences(PREFS, 0);
    }

    private static String keyOf(String host, int port) {
        return host + ":" + port;
    }

    /** TOFU check: first use records the fingerprint, later uses compare. */
    public static boolean verify(String host, int port, PublicKey key) {
        String fp = fingerprint(key);
        if (fp == null) {
            return false;
        }
        SharedPreferences p = prefs();
        String stored = p.getString(keyOf(host, port), null);
        if (stored == null) {
            p.edit().putString(keyOf(host, port), fp).apply();
            android.util.Log.i("REMOTE", "ssh host key recorded (TOFU) " + keyOf(host, port) + " " + fp);
            return true;
        }
        boolean ok = stored.equals(fp);
        if (!ok) {
            android.util.Log.i("REMOTE", "ssh host key MISMATCH for " + keyOf(host, port)
                    + ": stored=" + stored + " got=" + fp);
        }
        return ok;
    }

    /** Forgets the stored fingerprint (e.g. after the server was rebuilt). */
    public static void forget(String host, int port) {
        prefs().edit().remove(keyOf(host, port)).apply();
    }

    private static String fingerprint(PublicKey key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getEncoded());
            StringBuilder sb = new StringBuilder("SHA256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
