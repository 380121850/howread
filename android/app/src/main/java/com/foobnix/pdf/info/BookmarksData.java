package com.foobnix.pdf.info;

import android.content.Context;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Objects;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import org.ebookdroid.common.settings.books.SharedBooks;

import org.librera.LinkedJSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class BookmarksData {


    final static BookmarksData instance = new BookmarksData();

    public static BookmarksData get() {
        return instance;
    }


    public void add(AppBookmark bookmark) {
        LOG.d("BookmarksData", "add", bookmark.p, bookmark.text, bookmark.path);


        if (bookmark.p > 1) {
            bookmark.p = 0;
        }
        try {
            LinkedJSONObject obj = IO.readJsonObject(AppProfile.syncBookmarks);
            // the key is the creation timestamp in ms: two bookmarks created
            // in the same millisecond shared a key and the second silently
            // replaced the first. Nudging t keeps the key numeric, so
            // remove()/tombstones/sync keep working unchanged.
            while (obj.has("" + bookmark.t)) {
                bookmark.t += 1;
            }
            obj.put("" + bookmark.t, Objects.toJSONObject(bookmark));
            IO.writeObjSync(AppProfile.syncBookmarks, obj);
        } catch (Exception e) {
            LOG.e(e);
        }
    }


    public void remove(AppBookmark bookmark) {
        LOG.d("BookmarksData", "remove", bookmark.t, bookmark.file);

        if (bookmark.file == null) {
            // synthetic entries (e.g. the merged-notes row) have no backing
            // file — deleting them must go through their real entries
            LOG.d("BookmarksData", "remove: no backing file for", bookmark.text);
            return;
        }
        try {
            LinkedJSONObject obj = IO.readJsonObject(bookmark.file);
            if (obj.has("" + bookmark.t)) {
                obj.remove("" + bookmark.t);
            }
            IO.writeObjSync(bookmark.file, obj);
            // remember the deletion so the next WebDAV sync removes the
            // bookmark from the server instead of merging it back: the "b"
            // marker suppresses the whole-book merge for one round, and the
            // per-key tombstone drops exactly this entry from the server so a
            // partial delete (book still has progress / other notes) converges.
            if (TxtUtils.isNotEmpty(bookmark.getPath())) {
                SharedBooks.DeletedBooks.record(bookmark.getPath(), "b");
                SharedBooks.DeletedBooks.recordKey(bookmark.getPath(), bookmark.t);
                // propagate the deletion to the server promptly instead of
                // waiting for the next periodic/startup sync (which could race
                // a transient failure and let the tombstone be lost)
                com.foobnix.webdav.WebDavSyncer.notifyConfigChanged(
                        com.foobnix.LibreraApp.context);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public List<AppBookmark> getBookmarksByBook(File file) {
        if (file == null) {
            return new ArrayList<>();
        }
        return getBookmarksByBook(file.getPath());
    }

    public synchronized List<AppBookmark> getAll(Context c) {
        LOG.d("AppBookmark-get","getAll");

        final List<AppBookmark> all = getAll();
        final Iterator<AppBookmark> iterator = all.iterator();
        String fast = c.getString(R.string.fast_bookmark);
        while (iterator.hasNext()) {
            final AppBookmark next = iterator.next();

            if (AppState.get().isShowOnlyAvailabeBooks) {
                // remote books live on the server: never hide their
                // bookmarks/notes behind the local-file check
                if (!com.foobnix.remote.RemoteBook.isRemotePathLoose(next.getPath())
                        && !new File(next.getPath()).isFile()) {
                    iterator.remove();
                    continue;
                }
            }

            if (!AppState.get().isShowFastBookmarks) {
                if (fast.equals(next.text)) {
                    iterator.remove();
                }

            }

        }
        return all;
    }


    public List<AppBookmark> getAll() {

        List<AppBookmark> all = new ArrayList<>();

        try {

            List<File> allFiles = AppProfile.getAllFiles(AppProfile.APP_BOOKMARKS_JSON);
            for (File file : allFiles) {
                LinkedJSONObject obj = IO.readJsonObject(file);


                final Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    final String next = keys.next();

                    AppBookmark appBookmark = new AppBookmark();
                    appBookmark.file = file;
                    final LinkedJSONObject local = obj.getJSONObject(next);
                    Objects.loadFromJson(appBookmark, local);
                    all.add(appBookmark);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }


//        Iterator<AppBookmark> iterator = all.iterator();
//        while (iterator.hasNext()) {
//            AppBookmark next = iterator.next();
//            if (next.getText().equals(quick)) {
//                iterator.remove();
//            }
//        }

        LOG.d("getAll-size", all.size());
        Collections.sort(all, BY_TIME);
        return all;
    }


    public List<AppBookmark> getBookmarksByBook(String path) {

        List<AppBookmark> all = new ArrayList<>();


        List<File> allFiles = AppProfile.getAllFiles(AppProfile.APP_BOOKMARKS_JSON);
        for (File file : allFiles) {
            LinkedJSONObject obj = IO.readJsonObject(file);
            try {
                final Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    final String next = keys.next();

                    AppBookmark appBookmark = new AppBookmark();
                    appBookmark.file = file;
                    final LinkedJSONObject local = obj.getJSONObject(next);
                    Objects.loadFromJson(appBookmark, local);
                    String path1 = ExtUtils.getFileName(path);
                    String path2 = ExtUtils.getFileName(appBookmark.getPath());
                    if (path1.equals(path2)) {
                        appBookmark.path = path;//update path
                        all.add(appBookmark);
                    }
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }


        LOG.d("getBookmarksByBook", path, all.size());
        Collections.sort(all, BY_PERCENT);
        return all;
    }

    public boolean hasBookmark(String lastBookPath, int page, int pages) {
        final List<AppBookmark> bookmarksByBook = getBookmarksByBook(lastBookPath);
        for (AppBookmark appBookmark : bookmarksByBook) {
            if (appBookmark.getPercent() * pages == page) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes all bookmarks & AI notes grouped by book (file name) into
     * app-BookmarksByBook.json, so the backup zip carries them per book.
     */
    public synchronized void saveByBook() {
        try {
            if (AppProfile.syncBookmarksByBook == null) return;
            LinkedJSONObject byBook = new LinkedJSONObject();
            for (AppBookmark b : getAll()) {
                String book = ExtUtils.getFileName(b.getPath());
                LinkedJSONObject entries = byBook.has(book)
                        ? byBook.getJSONObject(book)
                        : new LinkedJSONObject();
                entries.put("" + b.t, Objects.toJSONObject(b));
                byBook.put(book, entries);
            }
            IO.writeObjSync(AppProfile.syncBookmarksByBook, byBook);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Merges the per-book backup file (app-BookmarksByBook.json) back into
     * app-Bookmarks.json after a restore. Entries are keyed by creation time,
     * so the merge is idempotent (existing keys are kept).
     */
    public synchronized void importByBook() {
        try {
            if (AppProfile.syncBookmarksByBook == null) return;
            LinkedJSONObject byBook = IO.readJsonObject(AppProfile.syncBookmarksByBook);
            LinkedJSONObject all = IO.readJsonObject(AppProfile.syncBookmarks);
            final Iterator<String> books = byBook.keys();
            while (books.hasNext()) {
                final String book = books.next();
                // per-book restore: one corrupt book entry must not abort the rest
                try {
                    final LinkedJSONObject entries = byBook.getJSONObject(book);
                    final Iterator<String> keys = entries.keys();
                    while (keys.hasNext()) {
                        final String key = keys.next();
                        if (!all.has(key)) {
                            all.put(key, entries.get(key));
                        }
                    }
                } catch (Exception bookError) {
                    LOG.e(bookError, "importByBook", book);
                }
            }
            IO.writeObjSync(AppProfile.syncBookmarks, all);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    static final Comparator<AppBookmark> BY_PERCENT = new Comparator<AppBookmark>() {

        @Override
        public int compare(AppBookmark o1, AppBookmark o2) {
            return Float.compare(o1.getPercent(), o2.getPercent());
        }
    };

    static final Comparator<AppBookmark> BY_TIME = new Comparator<AppBookmark>() {

        @Override
        public int compare(AppBookmark o1, AppBookmark o2) {
            return Float.compare(o2.getTime(), o1.getTime());
        }
    };


    /**
     * All bookmarks grouped by book path (used by the export-to-file/Gmail
     * features). Null paths (e.g. quick bookmarks without a book) are skipped.
     */
    public Map<String, List<AppBookmark>> getBookmarksMap() {
        Map<String, List<AppBookmark>> map = new java.util.LinkedHashMap<>();
        for (AppBookmark b : getAll()) {
            String path = b.getPath();
            if (path == null) {
                continue;
            }
            List<AppBookmark> list = map.get(path);
            if (list == null) {
                list = new ArrayList<>();
                map.put(path, list);
            }
            list.add(b);
        }
        return map;
    }


    public void cleanBookmarks() {
        // app-Bookmarks.json is a JSONObject keyed by creation time — the old
        // clearAll() call wrote an empty SimpleMeta ARRAY into it, leaving a
        // file no reader could parse. Clear properly and mark every affected
        // book so the next sync removes the bookmarks server-side too.
        for (AppBookmark b : getAll()) {
            if (TxtUtils.isNotEmpty(b.getPath())) {
                SharedBooks.DeletedBooks.record(b.getPath(), "b");
            }
        }
        for (File f : AppProfile.getAllFiles(AppProfile.APP_BOOKMARKS_JSON)) {
            IO.writeObjSync(f, new LinkedJSONObject());
        }
        // propagate the mass deletion to the server promptly
        com.foobnix.webdav.WebDavSyncer.notifyConfigChanged(
                com.foobnix.LibreraApp.context);
    }

    /**
     * Remove every bookmark / AI note of ONE book (matched by file name, like
     * {@link #getBookmarksByBook}) and tombstone the deletion so the WebDAV
     * sync removes the server copies instead of merging them back. Used when
     * the book itself is deleted — without this the remote bookmarks of the
     * deleted book keep resurrecting on every sync.
     */
    public void removeByBook(String path) {
        try {
            if (TxtUtils.isEmpty(path)) {
                return;
            }
            List<AppBookmark> mine = getBookmarksByBook(path);
            if (mine.isEmpty()) {
                return;
            }
            boolean changed = false;
            for (AppBookmark b : mine) {
                if (b.file == null) {
                    continue;
                }
                LinkedJSONObject obj = IO.readJsonObject(b.file);
                if (obj.has("" + b.t)) {
                    obj.remove("" + b.t);
                    IO.writeObjSync(b.file, obj);
                    changed = true;
                }
                if (TxtUtils.isNotEmpty(b.getPath())) {
                    SharedBooks.DeletedBooks.record(b.getPath(), "b");
                    SharedBooks.DeletedBooks.recordKey(b.getPath(), b.t);
                }
            }
            // the deleted book must not keep feeding the sync with progress /
            // dead-book entries either
            org.ebookdroid.common.settings.books.SharedBooks.deleteProgress(path);
            if (changed) {
                LOG.d("BookmarksData", "removeByBook", path, mine.size());
                com.foobnix.webdav.WebDavSyncer.notifyConfigChanged(
                        com.foobnix.LibreraApp.context);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * One-time cleanup for leftovers of books deleted BEFORE the delete flow
     * started cleaning up: bookmarks whose book file is gone (storage mounted,
     * no library entry under that name — i.e. not just moved) are removed and
     * tombstoned so the WebDAV sync deletes the server copies instead of
     * merging them back on every round. Called at the start of doSync().
     */
    public synchronized void pruneDeletedBooks() {
        try {
            // file names the library still knows (book may just have moved)
            Set<String> knownNames = new HashSet<String>();
            try {
                for (com.foobnix.dao2.FileMeta m : com.foobnix.ui2.AppDB.get().getAll()) {
                    String p = m.getPath();
                    if (TxtUtils.isEmpty(p) || p.startsWith("content:")) {
                        continue;
                    }
                    knownNames.add(ExtUtils.getFileName(p));
                }
            } catch (Exception dbError) {
                LOG.e(dbError);
            }

            Map<String, List<AppBookmark>> byPath = new java.util.LinkedHashMap<String, List<AppBookmark>>();
            for (AppBookmark b : getAll()) {
                String p = b.getPath();
                if (TxtUtils.isEmpty(p)) {
                    continue;
                }
                List<AppBookmark> list = byPath.get(p);
                if (list == null) {
                    list = new ArrayList<AppBookmark>();
                    byPath.put(p, list);
                }
                list.add(b);
            }

            boolean changed = false;
            for (Map.Entry<String, List<AppBookmark>> e : byPath.entrySet()) {
                String p = e.getKey();
                // remote book: existence is server-side, never prune here
                if (com.foobnix.remote.RemoteBook.isRemotePathLoose(p)) {
                    continue;
                }
                File f = new File(p);
                if (f.isFile() || !ExtUtils.isMounted(f)) {
                    continue; // book exists, or storage unavailable: don't guess
                }
                if (knownNames.contains(ExtUtils.getFileName(p))) {
                    continue; // library still has it under this name (moved)
                }
                for (AppBookmark b : e.getValue()) {
                    if (b.file != null) {
                        LinkedJSONObject obj = IO.readJsonObject(b.file);
                        if (obj.has("" + b.t)) {
                            obj.remove("" + b.t);
                            IO.writeObjSync(b.file, obj);
                            changed = true;
                        }
                    }
                    if (TxtUtils.isNotEmpty(b.getPath())) {
                        SharedBooks.DeletedBooks.record(b.getPath(), "b");
                        SharedBooks.DeletedBooks.recordKey(b.getPath(), b.t);
                    }
                }
                SharedBooks.deleteProgress(p);
                LOG.d("BookmarksData", "pruneDeletedBooks", p, e.getValue().size());
            }
            if (changed) {
                com.foobnix.webdav.WebDavSyncer.notifyConfigChanged(
                        com.foobnix.LibreraApp.context);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

}
