package com.foobnix.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.WorkerParameters;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.TagData;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.Prefs;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

public class CheckDeletedBooksWorker extends MessageWorker {

    public CheckDeletedBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @Override public boolean doWorkInner() {
        String errorID = AppProfile.getCurrent();
        try {
            Prefs.get().put(errorID, 0);
            return doWork1();
        }finally {
            Prefs.get().remove(errorID, 0);
        }
    }
     private boolean doWork1() {
        LOG.d("worker-starts", "CheckDeletedBooksWorker start");
        Tags2.migration();

        List<FileMeta> all = AppDB.get()
                                  .getAll();

        for (FileMeta meta : all) {
            if (meta == null) {
                continue;
            }

            if (Clouds.isCloud(meta.getPath())) {
                continue;
            }

            File bookFile = new File(meta.getPath());
            if (ExtUtils.isMounted(bookFile)) {
                if (!bookFile.exists()) {
                    AppDB.get()
                         .delete(meta);
                    // the file is gone for real (storage is mounted): drop its
                    // bookmarks/notes & progress and tombstone them so the
                    // WebDAV sync stops resurrecting them
                    try {
                        com.foobnix.pdf.info.BookmarksData.get().removeByBook(meta.getPath());
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                    LOG.d("BooksService", "Delete-setIsSearchBook", meta.getPath());
                }
            }

        }

        // the same strict purge as the full scan: books deleted above also
        // leave 最近阅读/我的珍藏 (library membership = current rows)
        try {
            AppData.get().purgeNonLibrary(
                    new java.util.HashSet<String>(AppDB.get().getSearchBookPaths()));
        } catch (Exception e) {
            LOG.e(e);
        }

        List<FileMeta> localMeta = new LinkedList<FileMeta>();
        if (JsonDB.isEmpty(BookCSS.get().searchPathsJson)) {
            sendFinishMessage();
            return true;
        }
        if (isStopped()) {
            return false;
        }
        for (final String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (path != null && path.trim()
                                    .length() > 0) {
                final File root = new File(path);
                if (root.exists()) {
                    LOG.d("Worker", "Search in " + root.getPath());
                    SearchCore.search(localMeta, root, ExtUtils.seachExts);
                }
            }
        }
        if (isStopped()) {
            return false;
        }

        boolean notifyResults = false;
        final LinkedList<String> newBooks = new LinkedList<String>();

        for (FileMeta meta : localMeta) {
            if (isStopped()) {
                return false;
            }
            if (!all.contains(meta)) {
                FileMetaCore.createMetaIfNeedSafe(meta.getPath(), true);
                newBooks.add(meta.getPath());
                LOG.d("CheckDeletedBooksWorker", "Add book", meta.getPath());
                notifyResults = true;
            }
        }

        List<FileMeta> allNone = AppDB.get()
                                      .getAllByState(FileMetaCore.STATE_NONE);
        for (FileMeta m : allNone) {
            if (isStopped()) {
                return false;
            }
            LOG.d("CheckDeletedBooksWorker", "STATE_NONE", m.getTitle(), m.getPath(), m.getTitle());
            FileMetaCore.createMetaIfNeedSafe(m.getPath(), false);
            notifyResults = true;
        }

        if (isStopped()) {
            return false;
        }
        Clouds.get()
              .syncronizeGet();
        if (isStopped()) {
            return false;
        }
        LOG.d("CheckDeletedBooksWorker", notifyResults);

        //TagData.restoreTags();
        Tags2.updateTagsDB();

        // Newly discovered books: persist their MuPDF accelerator in the
        // background so the first open doesn't pay for the full layout.
        if (!newBooks.isEmpty()) {
            com.foobnix.pdf.info.BookWarmer.warmAsync(newBooks);
        }

        return true;

    }
}
