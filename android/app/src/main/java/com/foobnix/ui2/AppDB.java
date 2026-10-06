package com.foobnix.ui2;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.foobnix.LibreraBuildConfig;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.StringDB;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.DaoMaster;
import com.foobnix.dao2.DaoSession;
import com.foobnix.dao2.DatabaseUpgradeHelper;
import com.foobnix.dao2.DictMeta;
import com.foobnix.dao2.DictMetaDao;
import com.foobnix.dao2.FileMeta;
import com.foobnix.dao2.FileMetaDao;
import com.foobnix.model.AppData;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.ui2.adapter.FileMetaAdapter;
import com.foobnix.ui2.fragment.SearchFragment2;

import org.greenrobot.greendao.Property;
import org.greenrobot.greendao.database.Database;
import org.greenrobot.greendao.query.QueryBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppDB {

    private final static AppDB in = new AppDB();
    DatabaseUpgradeHelper helper;
    String currentDB;
    private FileMetaDao fileMetaDao;
    private DaoSession daoSession;
    private DictMetaDao dictMetaDao;


    public static AppDB get() {
        return in;
    }

    public static List<FileMeta> removeNotExist(List<FileMeta> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<FileMeta>();
        }
        Iterator<FileMeta> iterator = items.iterator();
        while (iterator.hasNext()) {
            FileMeta next = iterator.next();
            if (Clouds.isCloud(next.getPath())) {
                continue;
            }
            if (com.foobnix.remote.RemoteBook.isRemotePath(next.getPath())) {
                // remote books have no local file; they are removed through
                // their own lifecycle, never by the existence check
                continue;
            }

            if (!new File(next.getPath()).isFile()) {
                iterator.remove();
            }
        }
        return items;
    }

    public static void removeClouds(List<FileMeta> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Iterator<FileMeta> iterator = items.iterator();
        while (iterator.hasNext()) {
            FileMeta next = iterator.next();
            if (Clouds.isCloud(next.getPath())) {
                File cacheFile = Clouds.getCacheFile(next.getPath());
                if (cacheFile != null) {
                    next.setPath(cacheFile.getPath());
                } else {
                    iterator.remove();

                }
            }
        }
    }

    public FileMetaDao getDao() {
        return fileMetaDao;
    }

    public boolean isFolder(FileMeta meta) {
        return meta.getCusType() != null && meta.getCusType() == FileMetaAdapter.DISPLAY_TYPE_DIRECTORY;

    }

    /** 把 src 的非空/更有值字段并入 dst（dst 优先），用于重复路径行合并不丢状态 */
    private static void mergeMeta(final FileMeta dst, final FileMeta src) {
        if (dst == null || src == null) {
            return;
        }
        if (isBlank(dst.getTitle())) {
            dst.setTitle(src.getTitle());
        }
        if (isBlank(dst.getAuthor())) {
            dst.setAuthor(src.getAuthor());
        }
        if (isBlank(dst.getAnnotation())) {
            dst.setAnnotation(src.getAnnotation());
        }
        if (dst.getSIndex() == null) {
            dst.setSIndex(src.getSIndex());
        }
        if (dst.getCusType() == null) {
            dst.setCusType(src.getCusType());
        }
        if (isBlank(dst.getExt())) {
            dst.setExt(src.getExt());
        }
        if (dst.getSize() == null) {
            dst.setSize(src.getSize());
        }
        if (dst.getDate() == null) {
            dst.setDate(src.getDate());
        }
        if (isBlank(dst.getDateTxt())) {
            dst.setDateTxt(src.getDateTxt());
        }
        if (isBlank(dst.getSizeTxt())) {
            dst.setSizeTxt(src.getSizeTxt());
        }
        if (isBlank(dst.getPathTxt())) {
            dst.setPathTxt(src.getPathTxt());
        }
        if (!truthy(dst.getIsStar()) && truthy(src.getIsStar())) {
            dst.setIsStar(Boolean.TRUE);
            if (dst.getIsStarTime() == null || (src.getIsStarTime() != null && src.getIsStarTime() > dst.getIsStarTime())) {
                dst.setIsStarTime(src.getIsStarTime());
            }
        }
        if (!truthy(dst.getIsRecent()) && truthy(src.getIsRecent())) {
            dst.setIsRecent(Boolean.TRUE);
            if (dst.getIsRecentTime() == null || (src.getIsRecentTime() != null && src.getIsRecentTime() > dst.getIsRecentTime())) {
                dst.setIsRecentTime(src.getIsRecentTime());
            }
            if (dst.getIsRecentProgress() == null) {
                dst.setIsRecentProgress(src.getIsRecentProgress());
            }
        }
        if (!truthy(dst.getIsSearchBook()) && truthy(src.getIsSearchBook())) {
            dst.setIsSearchBook(Boolean.TRUE);
        }
        if (isBlank(dst.getLang())) {
            dst.setLang(src.getLang());
        }
        if (isBlank(dst.getTag())) {
            dst.setTag(src.getTag());
        }
        if (dst.getPages() == null || (src.getPages() != null && src.getPages() > dst.getPages())) {
            dst.setPages(src.getPages() != null && (dst.getPages() == null || src.getPages() > dst.getPages()) ? src.getPages() : dst.getPages());
        }
        if (isBlank(dst.getKeyword())) {
            dst.setKeyword(src.getKeyword());
        }
        if (dst.getYear() == null) {
            dst.setYear(src.getYear());
        }
        if (dst.getState() == null || (src.getState() != null && src.getState() > dst.getState())) {
            dst.setState(src.getState() != null && (dst.getState() == null || src.getState() > dst.getState()) ? src.getState() : dst.getState());
        }
        if (isBlank(dst.getPublisher())) {
            dst.setPublisher(src.getPublisher());
        }
        if (isBlank(dst.getIsbn())) {
            dst.setIsbn(src.getIsbn());
        }
        if (isBlank(dst.getParentPath())) {
            dst.setParentPath(src.getParentPath());
        }
    }

    private static boolean isBlank(final String s) {
        return s == null || s.trim().length() == 0;
    }

    private static boolean truthy(final Boolean b) {
        return b != null && b;
    }

    public synchronized void open(Context c, String appDB) {

        if (appDB.equals(currentDB)) {
            LOG.d("Open-DB skip", appDB);
            return;
        }
        LOG.d("Open-DB", appDB);
        currentDB = appDB;

        // 先建新连接、完成切换，再延迟关闭旧库：旧写法先 close 再建新，一旦
        // 建新失败 AppDB 会停在"已关闭"状态；且 close 若撞上还在旧连接上的
        // 查询，后台线程抛 already-closed 会杀掉整个进程
        final DatabaseUpgradeHelper newHelper = new DatabaseUpgradeHelper(c, appDB);
        final SQLiteDatabase writableDatabase = newHelper.getWritableDatabase();
        final DaoMaster daoMaster = new DaoMaster(writableDatabase);

        final DatabaseUpgradeHelper oldHelper = helper;
        helper = newHelper;

        daoSession = daoMaster.newSession();

        fileMetaDao = daoSession.getFileMetaDao();

        // 后台合并重复行：同一物理文件曾以不同路径形态（/storage/emulated/0、
        // /sdcard、internal-storage:）入库时会在书架出现两份。统一键合并为一行，
        // 代表行优先取书架原生 internal-storage: 形态。
        final FileMetaDao dedupeDao = fileMetaDao;
        new Thread("@T AppDB dedupe") {
            @Override public void run() {
                try {
                    final List<FileMeta> all = dedupeDao.loadAll();
                    final java.util.LinkedHashMap<String, FileMeta> keep = new java.util.LinkedHashMap<String, FileMeta>();
                    final List<FileMeta> remove = new ArrayList<FileMeta>();
                    for (final FileMeta m : all) {
                        final String key = MyPath.canonicalize(m.getPath());
                        if (key == null) {
                            continue;
                        }
                        final FileMeta cur = keep.get(key);
                        if (cur == null) {
                            keep.put(key, m);
                            continue;
                        }
                        mergeMeta(cur, m);
                        remove.add(m);
                    }
                    // 代表行路径统一改写成规范化形态（internal-storage:/ 前缀不是
                    // 真实文件系统路径，openFile/Dashboard 的 File.exists 都会失败）
                    boolean rewritten = false;
                    for (final String key : keep.keySet()) {
                        final FileMeta m = keep.get(key);
                        if (!key.equals(m.getPath())) {
                            final FileMeta fixed = new FileMeta(key);
                            fixed.setTitle(m.getTitle());
                            fixed.setAuthor(m.getAuthor());
                            fixed.setAnnotation(m.getAnnotation());
                            fixed.setSIndex(m.getSIndex());
                            fixed.setCusType(m.getCusType());
                            fixed.setExt(m.getExt());
                            fixed.setSize(m.getSize());
                            fixed.setDate(m.getDate());
                            fixed.setDateTxt(m.getDateTxt());
                            fixed.setSizeTxt(m.getSizeTxt());
                            fixed.setPathTxt(m.getPathTxt());
                            fixed.setIsStar(m.getIsStar());
                            fixed.setIsStarTime(m.getIsStarTime());
                            fixed.setIsRecent(m.getIsRecent());
                            fixed.setIsRecentTime(m.getIsRecentTime());
                            fixed.setIsRecentProgress(m.getIsRecentProgress());
                            fixed.setIsSearchBook(m.getIsSearchBook());
                            fixed.setLang(m.getLang());
                            fixed.setTag(m.getTag());
                            fixed.setPages(m.getPages());
                            fixed.setKeyword(m.getKeyword());
                            fixed.setYear(m.getYear());
                            fixed.setState(m.getState());
                            fixed.setPublisher(m.getPublisher());
                            fixed.setIsbn(m.getIsbn());
                            fixed.setParentPath(MyPath.canonicalize(m.getParentPath()));
                            remove.add(m);
                            keep.put(key, fixed);
                            dedupeDao.insert(fixed);
                            rewritten = true;
                        }
                    }
                    LOG.bench("AppDB dedupe total=" + all.size() + " unique=" + keep.size()
                            + " dups=" + remove.size() + (rewritten ? " (paths normalized)" : ""));
                    if (!remove.isEmpty()) {
                        for (final FileMeta m : remove) {
                            try {
                                dedupeDao.delete(m);
                            } catch (final Throwable t) {
                                LOG.w(t);
                            }
                        }
                        LOG.bench("AppDB dedupe merged " + remove.size() + " duplicate path rows");
                    }
                } catch (final Throwable t) {
                    LOG.w(t);
                }
            }
        }.start();

        if (oldHelper != null) {
            // 延迟关闭：给仍在旧连接上的 in-flight 查询留出收尾窗口
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        oldHelper.close();
                    } catch (Throwable t) {
                        LOG.w(t);
                    }
                }
            }, 5000);
        }

        if (AppsConfig.IS_LOG) {
            QueryBuilder.LOG_SQL = true;
            QueryBuilder.LOG_VALUES = true;
        }

    }

    public void openDictDB(Context c, String path) {
        DaoMaster.OpenHelper helper = new DaoMaster.OpenHelper(c, path) {
            @Override
            public void onCreate(Database db) {
                //super.onCreate(db);
            }
        };

        SQLiteDatabase readableDatabase = helper.getReadableDatabase();
        DaoMaster daoMaster = new DaoMaster(readableDatabase);
        DaoSession daoSession = daoMaster.newSession();

        dictMetaDao = daoSession.getDictMetaDao();
        LOG.d("openDictDB open", path);
    }

    public String findDict(String key) {
        key = key.toLowerCase();
        LOG.d("openDictDB findDict key", key);

        final List<DictMeta> list = dictMetaDao.queryBuilder().where(DictMetaDao.Properties.Key.eq(key)).list();
        if (TxtUtils.isListNotEmpty(list)) {
            final String value = list.get(0).getValue();
            LOG.d("openDictDB findDict value", value);
            return value;
        }
        return key;
    }


    //public void dropCreateTables(Context c) {
    //    DatabaseUpgradeHelper helper = new DatabaseUpgradeHelper(c, DB_NAME);
    //    DaoMaster.dropAllTables(helper.getWritableDb(), true);
    //    DaoMaster.createAllTables(helper.getWritableDb(), true);
    // }

    public void deleteAllData() {
        if (fileMetaDao == null) {
            return;
        }
        fileMetaDao.deleteAll();

    }

    public List<FileMeta> deleteAllSafe() {
        try {
            List<FileMeta> list = fileMetaDao.queryBuilder().whereOr(FileMetaDao.Properties.Tag.isNotNull(), FileMetaDao.Properties.IsStar.eq(1), FileMetaDao.Properties.IsRecent.eq(1)).list();
            if (list == null) {
                list = new ArrayList<FileMeta>();
            }
            fileMetaDao.deleteAll();
            return list;
        } catch (Exception e) {
            LOG.e(e);
            return new ArrayList<FileMeta>();
        }
    }

    public void delete(FileMeta meta) {
        fileMetaDao.delete(meta);
    }

    public void deleteBy(String metaByPath) {
        fileMetaDao.deleteByKey(metaByPath);
    }

    public List<FileMeta> getRecentDeprecated() {
        try {
            List<FileMeta> list = fileMetaDao.queryBuilder().where(FileMetaDao.Properties.IsRecent.eq(1)).orderDesc(FileMetaDao.Properties.IsRecentTime).list();
            return removeNotExist(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public FileMeta getRecentLastNoFolder() {
        List<FileMeta> list = fileMetaDao.queryBuilder().where(FileMetaDao.Properties.IsRecent.eq(1)).orderDesc(FileMetaDao.Properties.IsRecentTime).limit(1).list();
        removeNotExist(list);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    public void addRecent(String path) {
        // No legacy-tab gate here: the dashboard's "recent reading" section
        // reads this list even when the old Recent tab is hidden in the
        // default tab order, so recording must not depend on its visibility.
        if (!com.foobnix.remote.RemoteBook.isRemotePath(path) && !new File(path).isFile()) {
            LOG.d("Can't add to recent, it's not a file", path);
            return;
        }
        LOG.d("Add Recent", path);
        if (!path.endsWith("json") && !path.endsWith("temp.txt")) {
            FileMeta load = getOrCreate(path);
            load.setIsRecent(true);
            load.setIsRecentTime(System.currentTimeMillis());
            fileMetaDao.update(load);

            AppData.get().addRecent(new SimpleMeta(path, System.currentTimeMillis()));
        }

    }

    public void addStarFile(String path) {
        if (!new File(path).isFile()) {
            LOG.d("Can't add to recent, it's not a file", path);
            return;
        }
        LOG.d("addStarFile", path);
        FileMeta load = getOrCreate(path);
        load.setIsStar(true);
        load.setIsStarTime(System.currentTimeMillis());
        load.setCusType(FileMetaAdapter.DISPLAY_TYPE_FILE);
        fileMetaDao.update(load);
    }

    public void addStarFolder(String path) {
        if (!new File(path).isDirectory()) {
            LOG.d("Can't add to recent, it's not a file", path);
            return;
        }
        LOG.d("addStarFile", path);
        FileMeta load = getOrCreate(path);
        load.setPathTxt(ExtUtils.getFileName(path));
        load.setIsStar(true);
        load.setIsStarTime(System.currentTimeMillis());
        load.setCusType(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY);
        fileMetaDao.update(load);
    }

    public void save(FileMeta meta) {
        if (meta != null) {
            meta.setPath(MyPath.canonicalize(meta.getPath()));
        }
        fileMetaDao.save(meta);
    }

    public long getCount() {
        try {
            return fileMetaDao.queryBuilder().count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Count of library files per extension (lowercase, without dot), e.g. used
     * by the "Formats" settings dialog to show how many files each format has.
     */
    public Map<String, Long> getExtCounts() {
        Map<String, Long> result = new HashMap<String, Long>();
        try {
            String sql = "SELECT " + FileMetaDao.Properties.Ext.columnName + ", COUNT(*) FROM " + FileMetaDao.TABLENAME
                    + " WHERE " + FileMetaDao.Properties.IsSearchBook.columnName + " == 1 GROUP BY "
                    + FileMetaDao.Properties.Ext.columnName;
            Cursor c = daoSession.getDatabase().rawQuery(sql, null);
            try {
                if (c.moveToFirst()) {
                    do {
                        String ext = c.getString(0);
                        if (TxtUtils.isEmpty(ext)) {
                            continue;
                        }
                        result.put(ext.toLowerCase(Locale.US), c.getLong(1));
                    } while (c.moveToNext());
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return result;
    }

    /**
     * All library (IsSearchBook == 1) file paths, used e.g. by the "Library
     * Folders" settings dialog to count how many supported files each
     * configured folder/file has.
     */
    public List<String> getSearchBookPaths() {
        List<String> result = new ArrayList<String>();
        try {
            String sql = "SELECT " + FileMetaDao.Properties.Path.columnName + " FROM " + FileMetaDao.TABLENAME
                    + " WHERE " + FileMetaDao.Properties.IsSearchBook.columnName + " == 1";
            Cursor c = daoSession.getDatabase().rawQuery(sql, null);
            try {
                if (c.moveToFirst()) {
                    do {
                        String path = c.getString(0);
                        if (!TxtUtils.isEmpty(path)) {
                            result.add(path);
                        }
                    } while (c.moveToNext());
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return result;
    }

    public List<FileMeta> getAll() {
        try {
            return fileMetaDao.queryBuilder().list();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<FileMeta> getAllByState(int state) {
        try {
            return fileMetaDao.queryBuilder().where(FileMetaDao.Properties.State.eq(state)).list();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public void setIsSearchBook(String path, boolean value) {
        final FileMeta load = AppDB.get().load(path);
        if (load != null) {
            load.setIsSearchBook(value);
            AppDB.get().update(load);
        }
    }

    public void update(FileMeta load) {
        if (fileMetaDao != null) {
            fileMetaDao.save(load);
        }

    }
    public void updateUpdate(FileMeta load) {
        if (fileMetaDao != null) {
            fileMetaDao.update(load);
        }

    }

    public FileMeta load(String path) {
        if (fileMetaDao == null) {
            return null;
        }
        return fileMetaDao.load(MyPath.canonicalize(path));
    }

    public FileMeta getOrCreate(String path) {
        // 同一物理文件的多种路径引用形态统一成一行（见 MyPath.canonicalize）
        path = MyPath.canonicalize(path);
        if (fileMetaDao == null) {
            FileMeta fileMeta = new FileMeta(path);
            fileMeta.setPages(200);
            return fileMeta;
        }
        FileMeta load = null;
        try {
            load = fileMetaDao.load(path);


            if (load == null) {
                load = new FileMeta(path);
                fileMetaDao.insert(load);

            }
        } catch (Exception e) {
            LOG.e(e);
        }
        if (load == null) {
            load = new FileMeta(path);
            load.setPages(100);
        }

        if (load.getState() == null) {
            load.setState(FileMetaCore.STATE_NONE);
        }

        return load;
    }

    public void clearSession() {
        try {
            daoSession.clear();
            currentDB = null;
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public void saveAll(List<FileMeta> list) {
        if (fileMetaDao == null) {
            return;
        }

        // 扫描 worker 走这里批量入库：路径必须规范化，否则同一物理文件会以
        // /storage/emulated/0 与 /sdcard 两种形态插出两行（书架双份）
        for (final FileMeta m : list) {
            if (m != null) {
                m.setPath(MyPath.canonicalize(m.getPath()));
            }
        }

        long time = System.currentTimeMillis();
        LOG.d("Save all begin");
        fileMetaDao.insertOrReplaceInTx(list, true);
        long end = System.currentTimeMillis() - time;
        LOG.d("Save all end", end / 1000, list.size());
    }

    public void updateAll(List<FileMeta> list) {
        if (fileMetaDao == null) {
            return;
        }

        try {
            if (fileMetaDao != null) {
                long time = System.currentTimeMillis();
                LOG.d("udpdate all begin");
                fileMetaDao.updateInTx(list);
                long end = System.currentTimeMillis() - time;
                LOG.d("update all end", end / 1000, list.size());
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public List<String> getAll(SEARCH_IN in) {
        String SQL_DISTINCT_ENAME = "SELECT DISTINCT " + in.getProperty().columnName + " as c FROM " + FileMetaDao.TABLENAME + " WHERE " + FileMetaDao.Properties.IsSearchBook.columnName + " == 1";

        ArrayList<String> result = new ArrayList<String>();
        Cursor c = daoSession.getDatabase().rawQuery(SQL_DISTINCT_ENAME, null);
        try {
            if (c.moveToFirst()) {
                do {
                    String item = c.getString(0);
                    if (item == null || TxtUtils.isEmpty(item)) {
                        continue;
                    }
                    if (in == SEARCH_IN.TAGS) {
                        TxtUtils.addFilteredTags(item, result);
                    } else {
                        TxtUtils.addFilteredGenreSeries(item, result, false);
                    }
                } while (c.moveToNext());
            }
        } finally {
            c.close();
        }
        Collections.sort(result, String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    public List<FileMeta> getStarsFilesDeprecated() {
        QueryBuilder<FileMeta> where = fileMetaDao.queryBuilder();
        List<FileMeta> list = where.where(FileMetaDao.Properties.IsStar.eq(1), where.or(FileMetaDao.Properties.CusType.isNull(), FileMetaDao.Properties.CusType.eq(FileMetaAdapter.DISPLAY_TYPE_FILE))).orderDesc(FileMetaDao.Properties.IsStarTime).list();
        return removeNotExist(list);
    }

    public List<FileMeta> getStarsFoldersDeprecated() {
        return fileMetaDao.queryBuilder().where(FileMetaDao.Properties.IsStar.eq(1), FileMetaDao.Properties.CusType.eq(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY)).orderAsc(FileMetaDao.Properties.PathTxt).list();
    }



    public boolean isStarFolderByFiles(String path) {
        final List<FileMeta> folders = AppData.get()
                                              .getAllFavoriteFolders();
        return TxtUtils.isListNotEmpty(folders) && folders.contains(new FileMeta(path));

    }
    public boolean isStarFolder(String path) {
        try {
            FileMeta load = fileMetaDao.load(path);
            if (load == null) {
                return false;
            }
            return load != null && load.getIsStar();
        } catch (Exception e) {
            return false;
        }
    }

    public void clearAllRecent() {
        if (fileMetaDao == null) {
            return;
        }
        List<FileMeta> recent = getRecentDeprecated();
        for (FileMeta meta : recent) {
            meta.setIsRecent(false);
        }
        fileMetaDao.updateInTx(recent);

    }

    public void clearAllFavorites() {
        if (fileMetaDao == null) {
            return;
        }
        // clear STARS (IsStar), not the recent list: the copy-pasted recent
        // query left every favorite that was not read recently untouched
        // (compare clearAllStars)
        List<FileMeta> stars = fileMetaDao.queryBuilder().where(FileMetaDao.Properties.IsStar.eq(1)).list();
        for (FileMeta meta : stars) {
            meta.setIsStar(false);
        }
        fileMetaDao.updateInTx(stars);

    }

    public void clearAllStars() {
        if (fileMetaDao == null) {
            return;
        }
        List<FileMeta> stars = fileMetaDao.queryBuilder().where(FileMetaDao.Properties.IsStar.eq(1)).list();
        for (FileMeta meta : stars) {
            meta.setIsStar(false);
        }
        fileMetaDao.updateInTx(stars);
    }

    public List<FileMeta> getAllWithTag(String tagName) {
        LOG.d("getAllWithTag", tagName);
        try {
            QueryBuilder<FileMeta> where = fileMetaDao.queryBuilder();
            where = where.where(SEARCH_IN.TAGS.getProperty().like("%" + tagName + StringDB.DIVIDER + "%"), FileMetaDao.Properties.IsSearchBook.eq(1));
            //where = where.where(SEARCH_IN.TAGS.getProperty().like("%" + tagName + StringDB.DIVIDER + "%"));
            List<FileMeta> list = where.list();
            ExtUtils.removeNotFound(list);
            return list;
        } catch (Exception e) {
            return new ArrayList<FileMeta>();
        }
    }

    public List<FileMeta> getAllWithTag() {
        if (fileMetaDao == null || fileMetaDao.queryBuilder() == null) {
            return new ArrayList<FileMeta>();
        }
        QueryBuilder<FileMeta> where = fileMetaDao.queryBuilder();
        where = where.where(FileMetaDao.Properties.Tag.isNotNull(), FileMetaDao.Properties.Tag.notEq(""));
        try {
            return where.list() == null ? new ArrayList<FileMeta>() : where.list();
        } catch (Exception e) {
            return new ArrayList<FileMeta>();
        }

    }

    public List<FileMeta> getAllWithProgress() {
        if (fileMetaDao == null || fileMetaDao.queryBuilder() == null) {
            return new ArrayList<FileMeta>();
        }
        QueryBuilder<FileMeta> where = fileMetaDao.queryBuilder();
        where = where.where(FileMetaDao.Properties.IsRecentProgress.isNotNull(), FileMetaDao.Properties.IsRecentProgress.eq(1.0f));
        try {
            return where.list() == null ? new ArrayList<FileMeta>() : where.list();
        } catch (Exception e) {
            return new ArrayList<FileMeta>();
        }

    }


    public List<FileMeta> searchBy(String str, SORT_BY sortby, boolean isAsc) {
        LOG.d("searchBy", str);
        try {
            QueryBuilder<FileMeta> where = fileMetaDao.queryBuilder();
            where.preferLocalizedStringOrder();

            SEARCH_IN searchIn = null;
            for (SEARCH_IN in : SEARCH_IN.values()) {
                if (str.startsWith(in.getDotPrefix())) {
                    str = str.replace(in.getDotPrefix(), "").trim();

                    if (in == SEARCH_IN.LANGUAGES) {
                        str = str.substring(str.indexOf("(") + 1).replace(")", "").trim();
                    }

                    searchIn = in;
                    break;
                }
            }

            if (searchIn == SEARCH_IN.TAGS) {
                str = str + StringDB.DIVIDER;

            }
            LOG.d("searchBy", searchIn, str, "-");
            if (str.startsWith(SearchFragment2.EMPTY_ID)) {
                where = where.whereOr(searchIn.getProperty().like(""), searchIn.getProperty().isNull());
            } else {
                if (TxtUtils.isNotEmpty(str)) {
                    str = str.replace(" ", "%").replace("*", "%");
                    str = str.replace(StringDB.EXACTMATCHCHAR, StringDB.DIVIDER);

                    String string = "%" + str + "%";


                    LOG.d("searchBy-final", string);

                    if (searchIn != null) {
                        where = where.whereOr(searchIn.getProperty().like(string), searchIn.getProperty().like(string.toLowerCase(Locale.US)));
                    } else {
                        where = where.whereOr(//
                                FileMetaDao.Properties.PathTxt.like(string), //
                                FileMetaDao.Properties.Title.like(string), //
                                FileMetaDao.Properties.Author.like(string)//
                        );
                    }
                }
            }
            where = where.where(FileMetaDao.Properties.IsSearchBook.eq(1));

            if (sortby == SORT_BY.RECENT_TIME) {
                where = where.where(FileMetaDao.Properties.IsRecentTime.ge(1));
            }


            if (isAsc) {
                where = where.orderAsc(sortby.getProperty());
            } else {
                where = where.orderDesc(sortby.getProperty());
            }
            if (sortby == SORT_BY.SERIES) {
                where = where.orderAsc(FileMetaDao.Properties.SIndex);
            }


            if (sortby != SORT_BY.TITLE) {
                where = where.orderAsc(SORT_BY.TITLE.getProperty());
            }


            return where.list();

        } catch (Exception e) {
            LOG.e(e);
            return new ArrayList<FileMeta>();
        }
    }

    public  enum SEARCH_IN {
        //
        PATH(FileMetaDao.Properties.Path, -1), //
        SERIES(FileMetaDao.Properties.Sequence, AppState.MODE_SERIES), //
        GENRE(FileMetaDao.Properties.Genre, AppState.MODE_GENRE), //
        AUTHOR(FileMetaDao.Properties.Author, AppState.MODE_AUTHORS), //
        TAGS(FileMetaDao.Properties.Tag, AppState.MODE_USER_TAGS), //
        KEYWRODS(FileMetaDao.Properties.Keyword, AppState.MODE_KEYWORDS), //
        LANGUAGES(FileMetaDao.Properties.Lang, AppState.MODE_LANGUAGES),
        YEAR(FileMetaDao.Properties.Year, AppState.MODE_PUBLICATION_DATE),
        PUBLISHER(FileMetaDao.Properties.Publisher, AppState.MODE_PUBLISHER);
        // ANNOT(FileMetaDao.Properties.Annotation, -1); //
        // REGEX(FileMetaDao.Properties.Path, -1);//
        //
        private final Property property;
        private final int mode;

        private SEARCH_IN(Property property, int mode) {
            this.property = property;
            this.mode = mode;
        }

        public static SEARCH_IN getByMode(int index) {
            for (SEARCH_IN sortBy : values()) {
                if (sortBy.getMode() == index) {
                    return sortBy;
                }
            }
            return SEARCH_IN.AUTHOR;
        }

        public static SEARCH_IN getByPrefix(String string) {
            for (SEARCH_IN sortBy : values()) {
                if (string.startsWith(sortBy.getDotPrefix())) {
                    return sortBy;
                }
            }
            return SEARCH_IN.PATH;
        }

        public Property getProperty() {
            return property;
        }

        public String getDotPrefix() {
            return "@" + name().toLowerCase(Locale.US);
        }

        public int getMode() {
            return mode;
        }
    }

    public enum SORT_BY {
        //
        PATH(0, R.string.folder, FileMetaDao.Properties.ParentPath), //
        FILE_NAME(1, R.string.by_file_name, FileMetaDao.Properties.PathTxt), //
        SIZE(2, R.string.by_size, FileMetaDao.Properties.Size), //
        DATA(3, R.string.by_date, FileMetaDao.Properties.Date), //
        TITLE(4, R.string.by_title, FileMetaDao.Properties.Title), //
        AUTHOR(5, R.string.by_author, FileMetaDao.Properties.Author), //
        SERIES(6, R.string.by_series, FileMetaDao.Properties.Sequence), //
        SERIES_INDEX(7, R.string.by_number_in_serie, FileMetaDao.Properties.SIndex), //
        PAGES(8, R.string.by_number_of_pages, FileMetaDao.Properties.Pages), //
        EXT(9, R.string.by_extension, FileMetaDao.Properties.Ext), //
        LANGUAGE(10, R.string.language, FileMetaDao.Properties.Lang),//
        PUBLICATION_YEAR(11, R.string.publication_date, FileMetaDao.Properties.Year),//
        PUBLISHER(12, R.string.publisher, FileMetaDao.Properties.Publisher),//
        RECENT_TIME(13, R.string.recent, FileMetaDao.Properties.IsRecentTime);//


        private final int index;
        private final int resName;
        private final Property property;

        private SORT_BY(int index, int resName, Property property) {
            this.index = index;
            this.resName = resName;
            this.property = property;
        }

        public static SORT_BY getByID(int index) {
            for (SORT_BY sortBy : values()) {
                if (sortBy.getIndex() == index) {
                    return sortBy;
                }
            }
            return SORT_BY.PATH;

        }

        public int getIndex() {
            return index;
        }

        public int getResName() {
            return resName;
        }

        public Property getProperty() {
            return property;
        }

    }

}
