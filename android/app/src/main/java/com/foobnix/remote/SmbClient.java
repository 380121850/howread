package com.foobnix.remote;

import android.text.TextUtils;

import com.foobnix.android.utils.LOG;
import com.foobnix.webdav.WebDavItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * SMB directory listing for the network browser. Returns the generic
 * {@link WebDavItem} rows whose {@code href} is again a remote:// browse URL,
 * so the existing WebDAV navigation / rendering keeps working unchanged.
 */
public class SmbClient {

    public static String lastError = "";
    public static volatile boolean lastErrorWasAuth = false;

    public static List<WebDavItem> list(String browseUrl) {
        lastError = "";
        lastErrorWasAuth = false;
        List<WebDavItem> items = new ArrayList<WebDavItem>();
        try {
            String id = RemoteBook.getServerId(browseUrl);
            RemoteServer s = RemoteStore.find(RemoteBook.TYPE_SMB, id);
            if (s == null) {
                lastError = "other";
                return null;
            }
            String dir = RemoteBook.getRemotePath(browseUrl);
            CIFSContext base = smbContext(s);
            String url = "smb://" + s.host + ":" + (s.port > 0 ? s.port : 445) + "/"
                    + (s.share.isEmpty() ? "" : s.share + "/")
                    + (dir.equals("/") ? "" : dir.substring(1) + (dir.endsWith("/") ? "" : "/"));
            SmbFile dirFile = new SmbFile(url, base);
            SmbFile[] kids = dirFile.listFiles();
            if (kids == null) {
                lastError = "other";
                return null;
            }
            for (SmbFile k : kids) {
                WebDavItem it = new WebDavItem();
                it.isDir = k.isDirectory();
                it.name = k.getName();
                if (it.name.endsWith("/")) {
                    it.name = it.name.substring(0, it.name.length() - 1);
                }
                it.size = k.length();
                it.href = browseUrl + "/" + it.name;
                items.add(it);
            }
            sort(items);
            return items;
        } catch (Exception e) {
            LOG.e(e);
            classify(e);
            return null;
        }
    }

    public static CIFSContext smbContext(RemoteServer s) throws CIFSException {
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB210");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.smb.client.responseTimeout", "30000");
        p.setProperty("jcifs.smb.client.connTimeout", "15000");
        CIFSContext ctx = new BaseContext(new PropertyConfiguration(p));
        if (TextUtils.isEmpty(s.user)) {
            // empty user/password = anonymous (guest) session
            return ctx.withCredentials(new NtlmPasswordAuthenticator("", "", ""));
        }
        String[] creds = com.foobnix.webdav.WebDavCredentials.load(com.foobnix.LibreraApp.context,
                RemoteStore.credentialsKey(s.id));
        return ctx.withCredentials(new NtlmPasswordAuthenticator(s.domain, s.user,
                creds == null ? "" : creds[1]));
    }

    private static void classify(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("Access is denied") || msg.contains("Logon failure") || msg.contains("auth")) {
            lastErrorWasAuth = true;
            lastError = "auth";
        } else {
            lastError = "network";
        }
    }

    static void sort(List<WebDavItem> items) {
        Collections.sort(items, (a, b) -> {
            if (a.isDir != b.isDir) {
                return a.isDir ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }
}
