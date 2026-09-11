package com.foobnix.remote;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import java.util.ArrayList;
import java.util.List;

/**
 * Read/write of the SMB / SFTP server lists, mirroring the WebDAV store
 * pattern but with one AppState field per protocol.
 */
public class RemoteStore {

    private static final Object LOCK = new Object();

    public static List<RemoteServer> load(String type) {
        synchronized (LOCK) {
            List<RemoteServer> res = new ArrayList<RemoteServer>();
            String raw = RemoteBook.TYPE_SFTP.equals(type) ? AppState.get().allSftpLinks
                    : AppState.get().allSmbLinks;
            for (String line : raw.split(";")) {
                if (TxtUtils.isEmpty(line)) {
                    continue;
                }
                RemoteServer s = RemoteServer.parse(line, type);
                if (s != null) {
                    res.add(s);
                }
            }
            return res;
        }
    }

    public static RemoteServer find(String type, String id) {
        if (TxtUtils.isEmpty(id)) {
            return null;
        }
        for (RemoteServer s : load(type)) {
            if (id.equals(s.id)) {
                return s;
            }
        }
        return null;
    }

    public static void add(RemoteServer s) {
        synchronized (LOCK) {
            if (RemoteBook.TYPE_SFTP.equals(s.getTypeStored())) {
                AppState.get().allSftpLinks = s.appState + AppState.get().allSftpLinks;
            } else {
                AppState.get().allSmbLinks = s.appState + AppState.get().allSmbLinks;
            }
        }
    }

    public static void remove(RemoteServer s) {
        synchronized (LOCK) {
            if (RemoteBook.TYPE_SFTP.equals(s.getTypeStored())) {
                AppState.get().allSftpLinks = AppState.get().allSftpLinks.replace(s.appState, "");
            } else {
                AppState.get().allSmbLinks = AppState.get().allSmbLinks.replace(s.appState, "");
            }
        }
    }

    public static String credentialsKey(String serverId) {
        return "remote://" + serverId;
    }

    public static String keyPassKey(String serverId) {
        return "remotekey://" + serverId;
    }
}
