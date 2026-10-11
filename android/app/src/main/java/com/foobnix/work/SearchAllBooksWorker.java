package com.foobnix.work;

import static com.foobnix.pdf.info.AppsConfig.SEARCH_FRAGMENT_WORKER_NAME;
import static com.foobnix.pdf.info.AppsConfig.WORKER_POLICY;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.EbookMeta;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.SimpleMeta;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.Prefs;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import org.ebookdroid.common.settings.books.SharedBooks;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.List;


public class SearchAllBooksWorker extends MessageWorker {

    Handler handler;
    List<FileMeta> itemsMeta;

    public SearchAllBooksWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        handler = new Handler(Looper.getMainLooper());

    }

    public static void run(Context context) {


        OneTimeWorkRequest workRequest = new OneTimeWorkRequest
                .Builder(SearchAllBooksWorker.class).build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(SEARCH_FRAGMENT_WORKER_NAME, WORKER_POLICY, workRequest);
    }


    public boolean doWorkInner() {
        LOG.d("worker-starts","SearchAllBooksWorker");
        String errorID = AppProfile.getCurrent();
        Prefs.get().put(errorID, 0);
        try {
            Tags2.migration();
            itemsMeta = new LinkedList<FileMeta>();

            AppProfile.init(getApplicationContext());

            ImageExtractor.clearErrors();
            IMG.clearDiscCache();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    IMG.clearMemoryCache();
                }
            });


            // snapshot the library books added from OUTSIDE the scan roots
            // (manually via "add to library" or by opening them with
            // isAutoAddToLibrary): deleteAllData below wipes the whole table
            // and the scan never walks their folders, so without this they
            // would vanish from the 书库 on every refresh
            final List<String> rescuedBooks = new LinkedList<String>();
            // 用户策略：书库文件夹删除/取消后，其书籍在刷新时移出书架——
            // 只有仍位于当前书库文件夹之下的本地书才被保留（网络/云书不受影响）
            // 只扫描勾选的书库文件夹（localFolderSel，缺省勾选；未勾选的书随搜索移出）
            final List<String> scanRoots = activeScanRoots();
            try {
                for (String p : AppDB.get().getSearchBookPaths()) {
                    if (com.foobnix.remote.RemoteBook.isRemotePathLoose(p) || Clouds.isCloud(p)) {
                        continue;
                    }
                    if (new File(p).isFile() && isUnderScanRoot(p, scanRoots)) {
                        rescuedBooks.add(p);
                    }
                }
            } catch (Exception e) {
                LOG.e(e);
            }

            AppDB.get().deleteAllData();


            itemsMeta.clear();

            handler.post(timer);
            LOG.d("SearchAllBooksWorker","searchPaths-all", 3, BookCSS.get().searchPathsJson);
            for (final String path : scanRoots) {
                if (path != null) {
                    final File root = new File(path);
                    if (root.exists()) {
                        LOG.d("Search in: " + root.getPath());
                        SearchCore.search(itemsMeta, root, ExtUtils.seachExts);
                        if (isStopped()) {
                            return false;
                        }
                    }
                }
            }
            if(itemsMeta.isEmpty()) {
                File downloadsDir = AppSP.get().getTempDownloadBooks(getApplicationContext());
                downloadsDir.mkdirs();

                try {
                    String[] books = getApplicationContext().getAssets().list("books");
                    for(String book:books) {
                        File outFile = new File(downloadsDir, book);
                        FileOutputStream out = new FileOutputStream(outFile);
                        IOUtils.copyClose(getApplicationContext().getAssets().open("books/"+book), out);
                        LOG.d("copyBook", book,outFile );
                    }
                }catch (Exception e){
                    LOG.e(e);
                }

                SearchCore.search(itemsMeta, downloadsDir, ExtUtils.seachExts);
            }

            if(AppState.get().isExperimental) {
                if (itemsMeta.isEmpty()) {
                    File path = AppProfile.DOWNLOADS_DIR;
                    // ADD the fallback folder, never REPLACE the list: the old
                    // JsonDB.set reduced a multi-folder 书库文件夹 list to just
                    // Downloads after one transiently empty scan
                    BookCSS.get().searchPathsJson = JsonDB.add(BookCSS.get().searchPathsJson, path.getPath());
                    // background re-add: also clear any deletion marker
                    com.foobnix.remote.RemoteTombstones.clear("folder:" + path.getPath());
                    SearchCore.search(itemsMeta, AppProfile.DOWNLOADS_DIR, ExtUtils.seachExts);
                    LOG.d("SearchAllBooksWorker", "Files-emtpy", "DOWNLOADS_DIR");
                }
            }


            for (FileMeta meta : itemsMeta) {
                meta.setIsSearchBook(true);
            }

            final List<SimpleMeta> allExcluded = AppData.get().getAllExcluded();

            if (TxtUtils.isListNotEmpty(allExcluded)) {
                for (FileMeta meta : itemsMeta) {
                    if (isStopped()) {
                        return false;
                    }
                    if (allExcluded.contains(SimpleMeta.SyncSimpleMeta(meta.getPath()))) {
                        meta.setIsSearchBook(false);
                    }
                }
            }

            final List<FileMeta> allSyncBooks = AppData.get().getAllSyncBooks();
            if (TxtUtils.isListNotEmpty(allSyncBooks)) {
                for (FileMeta meta : itemsMeta) {
                    for (FileMeta sync : allSyncBooks) {
                        if (isStopped()) {
                            return false;
                        }
                        if (meta.getTitle().equals(sync.getTitle()) && !meta.getPath().equals(sync.getPath())) {
                            meta.setIsSearchBook(false);
                            LOG.d("Worker", "remove-dublicate", meta.getPath());
                        }
                    }

                }
            }


            // favorites whose file is gone (deleted locally or on the
            // server) are not re-added: the shelf must not list dead books.
            // 本地收藏同样受书库文件夹约束：文件夹删除后不再回填书架
            // （网络/云收藏不随本地文件夹变化）
            for (FileMeta fav : AppData.get().getAllFavoriteFiles(false)) {
                final String fp = fav.getPath();
                if (com.foobnix.remote.RemoteBook.isRemotePathLoose(fp) || Clouds.isCloud(fp)) {
                    itemsMeta.add(fav);
                    continue;
                }
                if (new File(fp).exists() && isUnderScanRoot(fp, scanRoots)) {
                    itemsMeta.add(fav);
                }
            }
            itemsMeta.addAll(AppData.get().getAllFavoriteFolders());


            AppDB.get().saveAll(itemsMeta);

            // re-insert the rescued out-of-root library books (their rows are
            // gone after the wipe; metadata is (re)extracted by the
            // STATE_NONE pass further down)
            for (String p : rescuedBooks) {
                if (isStopped()) {
                    return false;
                }
                if (AppDB.get().load(p) == null) {
                    FileMeta meta = AppDB.get().getOrCreate(p);
                    meta.setIsSearchBook(true);
                    AppDB.get().update(meta);
                }
            }

            // 书库刷新后同步清理: 最近阅读/我的珍藏 entries not in the rebuilt
            // library are purged (strict semantics per user request; remote /
            // cloud / folder entries are exempt inside purgeNonLibrary)
            try {
                AppData.get().purgeNonLibrary(
                        new java.util.HashSet<String>(AppDB.get().getSearchBookPaths()));
            } catch (Exception e) {
                LOG.e(e);
            }

            handler.removeCallbacks(timer);

            sendFinishMessage();

            handler.post(refreshTimer);

            for (FileMeta meta : itemsMeta) {
                if (isStopped()) {
                    return false;
                }
                if (com.foobnix.remote.RemoteBook.isRemotePathLoose(meta.getPath())) {
                    // remote rows keep their scan metadata: a local File on a
                    // remote:// path reads length 0 and zeroed the size (the
                    // "0 B" file info on never-opened remote books)
                    continue;
                }
                File file = new File(meta.getPath());
                FileMetaCore.get().upadteBasicMeta(meta, file);
            }

            AppDB.get().updateAll(itemsMeta);
            sendFinishMessage();


            for (FileMeta meta : itemsMeta) {
                if (isStopped()) {
                    return false;
                }
                if (com.foobnix.remote.RemoteBook.isRemotePathLoose(meta.getPath())) {
                    continue; // no local file to extract meta from
                }
                //if(FileMetaCore.isSafeToExtactBook(meta.getPath())) {
                EbookMeta ebookMeta = FileMetaCore.get().getEbookMeta(meta.getPath(), CacheZipUtils.CacheDir.ZipService, true);
                FileMetaCore.get().udpateFullMeta(meta, ebookMeta);
                //}
            }

            SharedBooks.updateProgress(itemsMeta, true, -1);
            AppDB.get().updateAll(itemsMeta);


            itemsMeta.clear();

            handler.removeCallbacks(refreshTimer);
            sendFinishMessage();
            CacheZipUtils.CacheDir.ZipService.removeCacheContent();

            if (isStopped()) {
                return false;
            }

            Clouds.get().syncronizeGet();

            if (isStopped()) {
                return false;
            }

            //TagData.restoreTags();
            Tags2.updateTagsDB();


            List<FileMeta> allNone = AppDB.get().getAllByState(FileMetaCore.STATE_NONE);
            for (FileMeta m : allNone) {
                if (isStopped()) {
                    return false;
                }
                LOG.d("BooksService-createMetaIfNeedSafe-service", m.getTitle(), m.getPath(), m.getTitle());
                FileMetaCore.createMetaIfNeedSafe(m.getPath(), false);
            }

            if (isStopped()) {
                return false;
            }
            updateBookAnnotations();
        } finally {
            Prefs.get().remove(errorID, 0);
            handler.removeCallbacksAndMessages(null);
        }
        return true;


    }

    public void updateBookAnnotations() {

        if (AppState.get().isDisplayAnnotation) {
            sendBuildingLibrary();
            LOG.d("updateBookAnnotations begin");
            List<FileMeta> itemsMeta = AppDB.get().getAll();
            for (FileMeta meta : itemsMeta) {
                if (TxtUtils.isEmpty(meta.getAnnotation())) {
                    String bookOverview = FileMetaCore.getBookOverview(meta.getPath());
                    meta.setAnnotation(bookOverview);
                }
            }
            AppDB.get().updateAll(itemsMeta);
            sendFinishMessage();
            LOG.d("updateBookAnnotations end");
        }

    }

    Runnable timer = new Runnable() {

        @Override
        public void run() {
            LOG.d("timer 2");
            sendProggressMessage(itemsMeta);
            handler.postDelayed(timer, 250);
        }
    };


    Runnable refreshTimer = new Runnable() {

        @Override
        public void run() {
            LOG.d("timer2");
            sendBuildingLibrary();
            handler.postDelayed(refreshTimer, 500);
        }
    };



    /** 用户策略：书架本地书籍只来自当前书库文件夹。判断 path（归一化后）
     * 是否位于任一扫描根之下（根也可以是单文件）。 */
    private static boolean isUnderScanRoot(final String path, final List<String> roots) {
        if (path == null || roots == null || roots.isEmpty()) {
            return false;
        }
        final String np = org.ebookdroid.core.codec.AbstractCodecContext.normalizeStoragePath(path);
        for (final String root : roots) {
            if (root == null) {
                continue;
            }
            final String nr = org.ebookdroid.core.codec.AbstractCodecContext.normalizeStoragePath(root);
            if (np.equals(nr) || np.startsWith(nr.endsWith("/") ? nr : nr + "/")) {
                return true;
            }
        }
        return false;
    }


    /** 当前勾选的书库文件夹（localFolderSel，缺省勾选）。未勾选的文件夹
     * 其书籍随搜索移出书架（用户策略）。 */
    private static java.util.List<String> activeScanRoots() {
        final java.util.List<String> roots = new LinkedList<String>();
        final android.content.SharedPreferences sel = com.foobnix.LibreraApp.context
                .getSharedPreferences("localFolderSel", android.content.Context.MODE_PRIVATE);
        for (final String rp : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (rp == null || rp.trim().length() == 0) {
                continue;
            }
            if (sel.getBoolean("local:" + rp, true)) {
                roots.add(rp);
            }
        }
        return roots;
    }

}
