package com.foobnix.remote;

import android.content.Context;

import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.pdf.search.activity.msg.UpdateAllFragments;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;

import org.greenrobot.eventbus.EventBus;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the shelf in step with the configured remote servers: books of a
 * server deleted in 我的文件 are removed with it, a finished remote scan
 * drops books that disappeared from the server listing, and a full library
 * 搜索 purges books whose server is gone altogether. The book's block
 * cache is cleared along with the shelf row.
 */
public class RemoteLibraryCleaner {

    /** Deletes every shelf book of one remote server (its remote:// prefix). */
    public static void purgeServer(Context c, String type, String serverId) {
        purgePrefix(RemoteBook.PREFIX + type + "/" + serverId + "/");
    }

    /** Removes shelf books whose server is no longer configured at all. */
    public static void purgeUnconfigured(Context c) {
        try {
            Set<String> alive = new HashSet<String>();
            for (com.foobnix.webdav.WebDavServer s : com.foobnix.webdav.WebDavStore.load()) {
                alive.add(RemoteBook.TYPE_WEBDAV + ":" + RemoteSessionFactory.webdavId(s.url));
            }
            for (RemoteServer s : RemoteStore.load(RemoteBook.TYPE_SMB)) {
                alive.add(RemoteBook.TYPE_SMB + ":" + s.id);
            }
            for (RemoteServer s : RemoteStore.load(RemoteBook.TYPE_SFTP)) {
                alive.add(RemoteBook.TYPE_SFTP + ":" + s.id);
            }
            int removed = 0;
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (!RemoteBook.isRemotePath(p)) {
                    continue;
                }
                String rest = p.substring(RemoteBook.PREFIX.length());
                int slash = rest.indexOf('/');
                int second = slash < 0 ? -1 : rest.indexOf('/', slash + 1);
                if (slash <= 0 || second <= 0) {
                    continue;
                }
                String type = rest.substring(0, slash);
                String id = rest.substring(slash + 1, second);
                if (!alive.contains(type + ":" + id)) {
                    deleteRow(m);
                    removed++;
                }
            }
            android.util.Log.i("REMOTE", "purgeUnconfigured removed=" + removed);
            if (removed > 0) {
                refresh();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * After a COMPLETED scan of one server: drop its books missing from the
     * listing (deleted on the server while the app was offline).
     */
    public static void pruneSeen(Context c, String type, String serverId, Set<String> seen) {
        try {
            String prefix = RemoteBook.PREFIX + type + "/" + serverId + "/";
            int removed = 0;
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (p != null && p.startsWith(prefix) && !seen.contains(p)) {
                    deleteRow(m);
                    removed++;
                }
            }
            android.util.Log.i("REMOTE", "pruneSeen " + type + "/" + serverId
                    + " seen=" + seen.size() + " removed=" + removed);
            if (removed > 0) {
                refresh();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private static void purgePrefix(String prefix) {
        try {
            int removed = 0;
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (p != null && p.startsWith(prefix)) {
                    deleteRow(m);
                    removed++;
                }
            }
            android.util.Log.i("REMOTE", "purgeServer " + prefix + " removed=" + removed);
            if (removed > 0) {
                refresh();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private static void deleteRow(FileMeta m) {
        try {
            AppDB.get().deleteBy(m.getPath());
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            BlockCacheStore.clearBook(RemoteBook.cacheKey(m.getPath()));
        } catch (Exception e) {
            LOG.w(e);
        }
    }

    private static void refresh() {
        TempHolder.listHash++;
        EventBus.getDefault().post(new UpdateAllFragments());
    }
}
