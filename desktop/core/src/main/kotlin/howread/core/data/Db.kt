package howread.core.data

import howread.core.AppPaths
import howread.core.TestLog
import howread.core.model.Book
import howread.core.model.Bookmark
import howread.core.model.ReadingProgress
import howread.core.model.RemoteSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 桌面版 sqlite 存储。表字段名与安卓 greenDAO 关键表（FileMeta/RecentBooks/Bookmark）
 * 保持语义对齐，为未来跨端同步映射铺路；桌面版独立建库，不与安卓库互拷。
 */
/** 书库统计（首页/统计页展示；时长/速度来自 read_stats 累计）。 */
data class LibStats(val books: Int, val favorites: Int, val bookmarks: Int,
    val readBooks: Int, val weekRead: Int,
    val totalSeconds: Long = 0, val todaySeconds: Long = 0, val readPages: Long = 0) {
    /** 阅读速度（页/分），无时长时 0。 */
    val speedPerPageMin: Float get() = if (totalSeconds >= 60) readPages * 60f / totalSeconds else 0f
}

/** 单日阅读累计（近 7 天图表用；day=epoch 天数）。 */
data class DayStat(val day: Long, val seconds: Int, val pages: Int)

/** 持久高亮四边形（页面坐标系，fitz 页坐标；随排版缓存于创建时页数校验）。 */
data class AnnQuad(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** 持久高亮条目（划词保存；pageCount=创建时总页数，不一致时不渲染防错位）。 */
data class AnnotationRow(val id: Long, val path: String, val page: Int, val color: Int,
    val quads: List<AnnQuad>, val note: String, val pageCount: Int, val createdAt: Long)

/** 逐月阅读累计（近12月图；month=1..12）。 */
data class MonthStat(val year: Int, val month: Int, val seconds: Int)

/** 全量进度行（WebDAV 同步导出用）。 */
data class ProgressRow(val path: String, val page: Int, val pageCount: Int, val viewportW: Int, val updatedAt: Long)

object Db {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:" + AppPaths.dbFile.absolutePath)

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS file_meta(
                path TEXT PRIMARY KEY, title TEXT, pages INTEGER, size INTEGER, mtime INTEGER, added_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS recent(
                path TEXT PRIMARY KEY, last_read_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS favorite(
                path TEXT PRIMARY KEY, added_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS progress(
                path TEXT PRIMARY KEY, page INTEGER, page_count INTEGER, viewport_w INTEGER, updated_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS scan_dirs(
                path TEXT PRIMARY KEY, added_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS bookmark(
                id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, page INTEGER, label TEXT, note TEXT, created_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS read_stats(
                path TEXT PRIMARY KEY, seconds INTEGER, pages INTEGER, day INTEGER, day_seconds INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS daily_stats(
                day INTEGER PRIMARY KEY, seconds INTEGER, pages INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS remote_source(
                id INTEGER PRIMARY KEY AUTOINCREMENT, protocol TEXT, name TEXT, host TEXT, port INTEGER,
                path TEXT, user TEXT, password TEXT, created_at INTEGER)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS annotation(
                id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, page INTEGER, color INTEGER,
                quads TEXT, note TEXT, page_count INTEGER, created_at INTEGER)""")
            runCatching { st.executeUpdate("ALTER TABLE scan_dirs ADD COLUMN shelf INTEGER DEFAULT 1") }
            runCatching { st.executeUpdate("ALTER TABLE remote_source ADD COLUMN shelf INTEGER DEFAULT 1") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN author TEXT") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN series TEXT") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN series_index INTEGER") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN meta_title TEXT") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN description TEXT") }
            runCatching { st.executeUpdate("ALTER TABLE file_meta ADD COLUMN tag TEXT") }
            // 墓碑表（v0.51.0 对齐安卓 DeletedBooks/dk）：kind=book|bm，book=内容MD5（文件已删时退化为路径）
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS tombstone(
                kind TEXT, book TEXT, key TEXT, t INTEGER, PRIMARY KEY(kind, book, key))""")
            runCatching { st.executeUpdate("DELETE FROM tombstone WHERE t < " + (System.currentTimeMillis() - 15552000000L)) }
        }
    }

    // ---- 扫描目录 ----
    /** 扫描目录行（path + 是否上架书架；v0.30.0 书库文件夹勾选）。 */
    data class ScanDirRow(val path: String, val shelf: Boolean)

    @Synchronized fun listScanDirRows(): List<ScanDirRow> =
        conn.createStatement().executeQuery("SELECT path, COALESCE(shelf,1) FROM scan_dirs ORDER BY added_at").use { rs ->
            val out = ArrayList<ScanDirRow>()
            while (rs.next()) out.add(ScanDirRow(rs.getString(1), rs.getInt(2) != 0))
            out
        }

    @Synchronized fun setScanDirShelf(path: String, on: Boolean) {
        conn.prepareStatement("UPDATE scan_dirs SET shelf=? WHERE path=?").use { ps ->
            ps.setInt(1, if (on) 1 else 0); ps.setString(2, path); ps.executeUpdate()
        }
    }

    @Synchronized fun listScanDirs(): List<String> =
        conn.createStatement().executeQuery("SELECT path FROM scan_dirs ORDER BY added_at").use { rs ->
            val out = ArrayList<String>()
            while (rs.next()) out.add(rs.getString(1))
            out
        }

    @Synchronized fun addScanDir(path: String) {
        conn.prepareStatement("INSERT OR IGNORE INTO scan_dirs(path, added_at) VALUES(?,?)").use { ps ->
            ps.setString(1, path); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate()
        }
    }

    @Synchronized fun removeScanDir(path: String) {
        conn.prepareStatement("DELETE FROM scan_dirs WHERE path=?").use { ps ->
            ps.setString(1, path); ps.executeUpdate()
        }
    }

    // ---- 书目 ----
    @Synchronized fun upsertFileMeta(path: String, title: String, size: Long, mtime: Long) {
        // 系列从文件名模式解析（v0.22.0）；冲突时一并刷新，存量书随下一次扫描自动补上
        val si = howread.core.library.SeriesParser.parse(path, title)
        conn.prepareStatement(
            """INSERT INTO file_meta(path,title,pages,size,mtime,added_at,series,series_index) VALUES(?,?, -1, ?, ?, ?, ?, ?)
               ON CONFLICT(path) DO UPDATE SET title=excluded.title, size=excluded.size, mtime=excluded.mtime,
               series=excluded.series, series_index=excluded.series_index"""
        ).use { ps ->
            ps.setString(1, path); ps.setString(2, title); ps.setLong(3, size); ps.setLong(4, mtime)
            ps.setLong(5, System.currentTimeMillis())
            if (si == null) {
                ps.setNull(6, java.sql.Types.VARCHAR); ps.setNull(7, java.sql.Types.INTEGER)
            } else {
                ps.setString(6, si.series.take(120)); ps.setInt(7, si.index)
            }
            ps.executeUpdate()
        }
    }

    @Synchronized fun updatePages(path: String, pages: Int) {
        conn.prepareStatement("UPDATE file_meta SET pages=? WHERE path=?").use { ps ->
            ps.setInt(1, pages); ps.setString(2, path); ps.executeUpdate()
        }
    }

    /** 清理已不存在的书（按当前扫描到的合法集合裁剪）。返回删除条数。 */
    @Synchronized fun pruneMissing(existing: Set<String>): Int {
        val gone = ArrayList<String>()
        conn.createStatement().executeQuery("SELECT path FROM file_meta").use { rs ->
            while (rs.next()) {
                val p = rs.getString(1)
                if (p.startsWith("remote://")) continue   // 远程登记书不受本地扫描裁剪（v0.31.0）
                if (p !in existing) gone.add(p)
            }
        }
        // 检视修复：级联删除包事务（中途崩溃不留孤儿行）
        conn.autoCommit = false
        try {
            for (p in gone) {
                for (sql in listOf("DELETE FROM file_meta WHERE path=?", "DELETE FROM recent WHERE path=?",
                    "DELETE FROM favorite WHERE path=?", "DELETE FROM progress WHERE path=?",
                    "DELETE FROM bookmark WHERE path=?", "DELETE FROM annotation WHERE path=?")) {
                    conn.prepareStatement(sql).use { ps -> ps.setString(1, p); ps.executeUpdate() }
                }
            }
            conn.commit()
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = true
        }
        return gone.size
    }

    /** 远程源定向裁剪（v0.47 检视修复）：源上已删除/改名的登记书移出书库及进度/书签/批注。 */
    @Synchronized fun pruneRemoteSource(srcId: Long, keepRel: Set<String>): Int {
        val prefix = "remote://" + srcId + "/"
        val keep = keepRel.map { it.trim('/') }.toSet()   // v0.58：双保险归一化（旧调用方可能传带斜杠 rel）
        val gone = ArrayList<String>()
        conn.createStatement().executeQuery(
            "SELECT path FROM file_meta WHERE path LIKE '" + prefix.replace("'", "''") + "%'"
        ).use { rs ->
            while (rs.next()) {
                val p = rs.getString(1)
                if (p.removePrefix(prefix).trim('/') !in keep) gone.add(p)
            }
        }
        if (gone.isEmpty()) return 0
        conn.autoCommit = false
        try {
            for (p in gone) {
                for (sql in listOf("DELETE FROM file_meta WHERE path=?", "DELETE FROM recent WHERE path=?",
                    "DELETE FROM favorite WHERE path=?", "DELETE FROM progress WHERE path=?",
                    "DELETE FROM bookmark WHERE path=?", "DELETE FROM annotation WHERE path=?")) {
                    conn.prepareStatement(sql).use { ps -> ps.setString(1, p); ps.executeUpdate() }
                }
            }
            conn.commit()
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t
        } finally {
            conn.autoCommit = true
        }
        return gone.size
    }

    @Synchronized fun allBooks(): List<Book> {
        val favs = HashSet<String>()
        conn.createStatement().executeQuery("SELECT path FROM favorite").use { rs ->
            while (rs.next()) favs.add(rs.getString(1))
        }
        val recents = HashMap<String, Long>()
        conn.createStatement().executeQuery("SELECT path, last_read_at FROM recent").use { rs ->
            while (rs.next()) recents[rs.getString(1)] = rs.getLong(2)
        }
        val out = ArrayList<Book>()
        conn.createStatement().executeQuery(
            "SELECT path, title, pages, size, mtime, author, series, series_index, tag FROM file_meta ORDER BY title COLLATE NOCASE"
        ).use { rs ->
            while (rs.next()) {
                val p = rs.getString(1)
                out.add(Book(
                    path = p, title = rs.getString(2) ?: File(p).name, pages = rs.getInt(3),
                    size = rs.getLong(4), mtime = rs.getLong(5),
                    isFavorite = p in favs, lastReadAt = recents[p] ?: 0L,
                    author = rs.getString(6) ?: "",
                    series = rs.getString(7) ?: "",
                    seriesIdx = rs.getObject(8) as? Int ?: -1,
                    tag = rs.getString(9) ?: "",
                ))
            }
        }
        return out
    }

    // ---- 最近 / 收藏 ----
    @Synchronized fun markRecent(path: String) {
        conn.prepareStatement(
            "INSERT INTO recent(path, last_read_at) VALUES(?,?) ON CONFLICT(path) DO UPDATE SET last_read_at=excluded.last_read_at"
        ).use { ps ->
            ps.setString(1, path); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate()
        }
    }

    /** 回填元数据标题（打开书籍时；显示层按"显示文件名而非元数据"开关取舍）。 */
    @Synchronized fun setMetaTitle(path: String, t: String) {
        if (t.isBlank()) return
        conn.prepareStatement("UPDATE file_meta SET meta_title=? WHERE path=?").use { ps ->
            ps.setString(1, t.take(200)); ps.setString(2, path); ps.executeUpdate()
        }
    }

    /** 回填书籍描述（打开书籍时；文件信息对话框"显示书籍描述"用）。 */
    @Synchronized fun setDescription(path: String, d: String) {
        if (d.isBlank()) return
        conn.prepareStatement("UPDATE file_meta SET description=? WHERE path=?").use { ps ->
            ps.setString(1, d.take(4000)); ps.setString(2, path); ps.executeUpdate()
        }
    }

    /** 回填作者（仅在当前为空时写入，不覆盖已有值）。 */
    @Synchronized fun setAuthor(path: String, author: String) {
        if (author.isBlank()) return
        conn.prepareStatement("UPDATE file_meta SET author=? WHERE path=? AND (author IS NULL OR author='')").use { ps ->
            ps.setString(1, author.take(120)); ps.setString(2, path); ps.executeUpdate()
        }
    }

    @Synchronized fun toggleFavorite(path: String): Boolean {
        val exists = conn.prepareStatement("SELECT 1 FROM favorite WHERE path=?").use { ps ->
            ps.setString(1, path); ps.executeQuery().use { it.next() }
        }
        if (exists) {
            conn.prepareStatement("DELETE FROM favorite WHERE path=?").use { ps ->
                ps.setString(1, path); ps.executeUpdate() }
            return false
        }
        conn.prepareStatement("INSERT INTO favorite(path, added_at) VALUES(?,?)").use { ps ->
            ps.setString(1, path); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate() }
        return true
    }

    /** 移出一本书的全部数据（不删文件本体）；v0.51.0：删除前写书/书签墓碑（preHash=删文件前算好的 MD5）。 */
    @Synchronized @JvmOverloads fun removeBook(path: String, preHash: String = "") {
        val h = preHash.ifEmpty { hashForPath(path) }
        val nowT = System.currentTimeMillis()
        tombstoneAdd("book", h, "", nowT)
        val bmKeys = ArrayList<String>()
        conn.prepareStatement("SELECT created_at FROM bookmark WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs -> while (rs.next()) bmKeys.add(rs.getLong(1).toString()) }
        }
        for (k in bmKeys) tombstoneAdd("bm", h, k, nowT)
        for (sql in listOf("DELETE FROM file_meta WHERE path=?", "DELETE FROM recent WHERE path=?",
            "DELETE FROM favorite WHERE path=?", "DELETE FROM progress WHERE path=?",
            "DELETE FROM bookmark WHERE path=?", "DELETE FROM annotation WHERE path=?")) {
            conn.prepareStatement(sql).use { ps -> ps.setString(1, path); ps.executeUpdate() }
        }
    }

    /** 设置收藏（true=加入）。 */
    @Synchronized fun setFavorite(path: String, fav: Boolean) {
        if (fav) {
            conn.prepareStatement("INSERT OR IGNORE INTO favorite(path, added_at) VALUES(?,?)").use { ps ->
                ps.setString(1, path); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate()
            }
        } else {
            conn.prepareStatement("DELETE FROM favorite WHERE path=?").use { ps ->
                ps.setString(1, path); ps.executeUpdate()
            }
        }
    }

    // ---- 阅读进度（纪元式校验：pageCount+viewportW 任一变化按百分比回落） ----
    @Synchronized fun saveProgress(path: String, page: Int, pageCount: Int, viewportW: Int) {
        conn.prepareStatement(
            """INSERT INTO progress(path,page,page_count,viewport_w,updated_at) VALUES(?,?,?,?,?)
               ON CONFLICT(path) DO UPDATE SET page=excluded.page, page_count=excluded.page_count,
               viewport_w=excluded.viewport_w, updated_at=excluded.updated_at"""
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, page); ps.setInt(3, pageCount)
            ps.setInt(4, viewportW); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate()
        }
    }

    @Synchronized fun loadProgress(path: String): ReadingProgress? =
        conn.prepareStatement("SELECT page,page_count,viewport_w,updated_at FROM progress WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                if (rs.next()) ReadingProgress(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)) else null
            }
        }

    // ---- 书签 / 笔记 ----
    @Synchronized fun listBookmarks(path: String): List<Bookmark> =
        conn.prepareStatement(
            "SELECT id, path, page, label, note, created_at FROM bookmark WHERE path=? ORDER BY page, created_at"
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                val out = ArrayList<Bookmark>()
                while (rs.next()) out.add(Bookmark(rs.getLong(1), rs.getString(2), rs.getInt(3),
                    rs.getString(4) ?: "", rs.getString(5) ?: "", rs.getLong(6)))
                out
            }
        }

    @Synchronized fun addBookmark(path: String, page: Int, label: String, note: String) {
        conn.prepareStatement(
            "INSERT INTO bookmark(path,page,label,note,created_at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, page); ps.setString(3, label)
            ps.setString(4, note); ps.setLong(5, System.currentTimeMillis()); ps.executeUpdate()
        }
    }

    @Synchronized fun updateBookmarkNote(id: Long, note: String) {
        conn.prepareStatement("UPDATE bookmark SET note=? WHERE id=?").use { ps ->
            ps.setString(1, note); ps.setLong(2, id); ps.executeUpdate()
        }
    }

    @Synchronized fun deleteBookmark(id: Long) {
        var bmPath = ""
        var createdAt = 0L
        conn.prepareStatement("SELECT path, created_at FROM bookmark WHERE id=?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) { bmPath = rs.getString(1) ?: ""; createdAt = rs.getLong(2) } }
        }
        conn.prepareStatement("DELETE FROM bookmark WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeUpdate()
        }
        if (bmPath.isNotEmpty()) tombstoneAdd("bm", hashForPath(bmPath), createdAt.toString(), System.currentTimeMillis())
    }

    // ---- 标签与墓碑（v0.51.0 对齐安卓 Tags2 / DeletedBooks+dk） ----
    @Synchronized fun setBookTag(path: String, tag: String) {
        conn.prepareStatement("UPDATE file_meta SET tag=? WHERE path=?").use { ps ->
            ps.setString(1, tag); ps.setString(2, path); ps.executeUpdate()
        }
    }

    /** 全部标签（名称→藏书数，按名称排序）。 */
    @Synchronized fun listTags(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        conn.createStatement().executeQuery(
            "SELECT tag, COUNT(*) FROM file_meta WHERE tag IS NOT NULL AND tag<>'' GROUP BY tag ORDER BY tag COLLATE NOCASE"
        ).use { rs -> while (rs.next()) out.add(rs.getString(1) to rs.getInt(2)) }
        return out
    }

    /** 书身份 hash：优先内容 MD5（须在删文件前调用；文件已删时退化为路径）。 */
    private fun hashForPath(p: String): String =
        runCatching { val f = File(p); if (f.isFile) howread.core.sync.AndroidSync.md5(f) else p }.getOrDefault(p)

    private fun tombstoneAdd(kind: String, book: String, key: String, t: Long) {
        conn.prepareStatement("INSERT OR REPLACE INTO tombstone(kind,book,key,t) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, kind); ps.setString(2, book); ps.setString(3, key); ps.setLong(4, t); ps.executeUpdate()
        }
    }

    /** 某书的书签墓碑 map（key→删除毫秒；供同步发布 dk 与下行防复活）。 */
    @Synchronized fun tombstoneBmMap(hash: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        conn.prepareStatement("SELECT key, t FROM tombstone WHERE kind='bm' AND book=?").use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> while (rs.next()) out[rs.getString(1)] = rs.getLong(2) }
        }
        return out
    }

    /** 某书"已整体移出书库"的墓碑时间（无=null）。 */
    @Synchronized fun tombstoneBookT(hash: String): Long? {
        conn.prepareStatement("SELECT t FROM tombstone WHERE kind='book' AND book=?").use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
        }
    }

    // ---- 阅读时长统计（安卓 statHours/statToday/statSpeed 同口径） ----
    /** 累计阅读秒数/翻页数（day_seconds 随 day 滚动重置）。 */
    @Synchronized fun addReadTime(path: String, seconds: Int, pages: Int) {
        if (seconds <= 0 && pages <= 0) return
        val today = java.time.LocalDate.now().toEpochDay()
        conn.prepareStatement(
            """INSERT INTO read_stats(path,seconds,pages,day,day_seconds) VALUES(?,?,?,?,?)
               ON CONFLICT(path) DO UPDATE SET seconds=seconds+excluded.seconds,
               pages=pages+excluded.pages, day=excluded.day,
               day_seconds=CASE WHEN read_stats.day=excluded.day THEN day_seconds+excluded.day_seconds
                                ELSE excluded.day_seconds END"""
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, seconds); ps.setInt(3, pages)
            ps.setLong(4, today); ps.setInt(5, seconds); ps.executeUpdate()
        }
        conn.prepareStatement(
            """INSERT INTO daily_stats(day, seconds, pages) VALUES(?,?,?)
               ON CONFLICT(day) DO UPDATE SET seconds=seconds+excluded.seconds, pages=pages+excluded.pages"""
        ).use { ps ->
            ps.setLong(1, today); ps.setInt(2, seconds); ps.setInt(3, pages); ps.executeUpdate()
        }
    }

    /** 清空阅读统计（v0.46.0：read_stats + daily_stats；进度/书签/收藏不受影响）。返回删除行数。 */
    @Synchronized fun clearReadStats(): Int {
        var n = 0
        for (sql in listOf("DELETE FROM read_stats", "DELETE FROM daily_stats")) {
            conn.createStatement().use { st -> n += st.executeUpdate(sql) }
        }
        return n
    }

    // ---- 首页聚合：跨书书签 / 统计 ----
    @Synchronized fun allBookmarks(limit: Int = 200): List<Pair<Bookmark, String>> =
        conn.createStatement().executeQuery(
            "SELECT b.id, b.path, b.page, b.label, b.note, b.created_at, f.title " +
                "FROM bookmark b LEFT JOIN file_meta f ON b.path = f.path " +
                "ORDER BY b.created_at DESC LIMIT " + limit
        ).use { rs ->
            val out = ArrayList<Pair<Bookmark, String>>()
            while (rs.next()) out.add(
                Bookmark(rs.getLong(1), rs.getString(2), rs.getInt(3),
                    rs.getString(4) ?: "", rs.getString(5) ?: "", rs.getLong(6)
                ) to (rs.getString(7) ?: File(rs.getString(2)).name)
            )
            out
        }

    /** 近 7 天每日累计（含补零，旧->今）。 */
    @Synchronized fun last7Days(): List<DayStat> {
        val today = java.time.LocalDate.now().toEpochDay()
        val map = HashMap<Long, IntArray>()
        conn.createStatement().executeQuery(
            "SELECT day, seconds, pages FROM daily_stats WHERE day >= " + (today - 6)
        ).use { rs ->
            while (rs.next()) map[rs.getLong(1)] = intArrayOf(rs.getInt(2), rs.getInt(3))
        }
        val out = ArrayList<DayStat>()
        for (d in today - 6..today) {
            val v = map[d] ?: intArrayOf(0, 0)
            out.add(DayStat(d, v[0], v[1]))
        }
        return out
    }

    @Synchronized fun stats(): LibStats {
        fun count(sql: String): Int =
            conn.createStatement().executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
        fun longOf(sql: String): Long =
            conn.createStatement().executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) }
        val today = java.time.LocalDate.now().toEpochDay()
        return LibStats(
            books = count("SELECT COUNT(*) FROM file_meta"),
            favorites = count("SELECT COUNT(*) FROM favorite"),
            bookmarks = count("SELECT COUNT(*) FROM bookmark"),
            readBooks = count("SELECT COUNT(*) FROM recent"),
            weekRead = count("SELECT COUNT(*) FROM recent WHERE last_read_at > " +
                (System.currentTimeMillis() - 7L * 24 * 3600 * 1000)),
            totalSeconds = longOf("SELECT COALESCE(SUM(seconds),0) FROM read_stats"),
            todaySeconds = longOf("SELECT COALESCE(SUM(day_seconds),0) FROM read_stats WHERE day=$today"),
            readPages = longOf("SELECT COALESCE(SUM(pages),0) FROM read_stats"),
        )
    }

    /** 最近阅读的书（recent join file_meta，含书库元信息）。 */
    @Synchronized fun recentBooks(limit: Int): List<Book> =
        conn.createStatement().executeQuery(
            "SELECT f.path, f.title, f.pages, f.size, f.mtime, r.last_read_at, f.author, f.series, f.series_index FROM file_meta f " +
                "JOIN recent r ON f.path = r.path ORDER BY r.last_read_at DESC LIMIT " + limit
        ).use { rs ->
            val out = ArrayList<Book>()
            while (rs.next()) out.add(Book(
                path = rs.getString(1), title = rs.getString(2) ?: File(rs.getString(1)).name,
                pages = rs.getInt(3), size = rs.getLong(4), mtime = rs.getLong(5),
                isFavorite = false, lastReadAt = rs.getLong(6),
                author = rs.getString(7) ?: "",
                series = rs.getString(8) ?: "",
                seriesIdx = rs.getObject(9) as? Int ?: -1,
            ))
            out
        }

    /** 近 n 天每日累计（含补零，旧->今）。 */
    @Synchronized fun lastNDays(n: Int): List<DayStat> {
        val today = java.time.LocalDate.now().toEpochDay()
        val map = HashMap<Long, IntArray>()
        conn.createStatement().executeQuery(
            "SELECT day, seconds, pages FROM daily_stats WHERE day >= " + (today - n + 1)
        ).use { rs ->
            while (rs.next()) map[rs.getLong(1)] = intArrayOf(rs.getInt(2), rs.getInt(3))
        }
        val out = ArrayList<DayStat>()
        for (d in today - n + 1..today) {
            val v = map[d] ?: intArrayOf(0, 0)
            out.add(DayStat(d, v[0], v[1]))
        }
        return out
    }

    /** 近 12 个月逐月累计（按日历月聚合，空月补零）。 */
    @Synchronized fun last12Months(): List<MonthStat> {
        val today = java.time.LocalDate.now().toEpochDay()
        val map = HashMap<Int, Int>()
        conn.createStatement().executeQuery(
            "SELECT day, seconds FROM daily_stats WHERE day >= " + (today - 370)
        ).use { rs ->
            while (rs.next()) {
                // 检视修复：LocalDate 1 基月键，与 PeriodStats.monthly 口径一致（Calendar.MONTH 是 0 基）
                val ld = java.time.LocalDate.ofEpochDay(rs.getLong(1))
                val k = ld.year * 100 + ld.monthValue
                map[k] = (map[k] ?: 0) + rs.getInt(2)
            }
        }
        val out = ArrayList<MonthStat>()
        val cal = java.util.Calendar.getInstance()
        for (i in 11 downTo 0) {
            val c = cal.clone() as java.util.Calendar
            c.add(java.util.Calendar.MONTH, -i)
            val k = c.get(java.util.Calendar.YEAR) * 100 + c.get(java.util.Calendar.MONTH)
            out.add(MonthStat(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, map[k] ?: 0))
        }
        return out
    }

    /** 全部日累计（旧->今；周/月/年聚合用）。 */
    @Synchronized fun allDays(): List<DayStat> {
        val out = ArrayList<DayStat>()
        conn.createStatement().executeQuery("SELECT day, seconds, pages FROM daily_stats ORDER BY day").use { rs ->
            while (rs.next()) out.add(DayStat(rs.getLong(1), rs.getInt(2), rs.getInt(3)))
        }
        return out
    }

    /** sqlite 一致性快照（备份用）。 */
    @Synchronized fun backupSnapshotTo(dest: File) {
        conn.createStatement().use {
            it.executeUpdate("VACUUM INTO '" + dest.absolutePath.replace("'", "''") + "'")
        }
    }

    // ---- WebDAV 同步（v1） ----
    @Synchronized fun listAllProgress(): List<ProgressRow> =
        conn.createStatement().executeQuery(
            "SELECT path, page, page_count, viewport_w, updated_at FROM progress"
        ).use { rs ->
            val out = ArrayList<ProgressRow>()
            while (rs.next()) out.add(ProgressRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)))
            out
        }

    /** 带显式时间戳的进度写入（同步合并用；本机阅读走 saveProgress=now）。 */
    /** 全量进度（安卓互通同步收集用）。 */
    @Synchronized fun allProgress(): List<ProgressRow> {
        val out = ArrayList<ProgressRow>()
        conn.createStatement().executeQuery("SELECT path, page, page_count, viewport_w, updated_at FROM progress").use { rs ->
            while (rs.next()) out.add(ProgressRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5)))
        }
        return out
    }

    @Synchronized fun saveProgressRaw(path: String, page: Int, pageCount: Int, viewportW: Int, updatedAt: Long) {
        conn.prepareStatement(
            """INSERT INTO progress(path,page,page_count,viewport_w,updated_at) VALUES(?,?,?,?,?)
               ON CONFLICT(path) DO UPDATE SET page=excluded.page, page_count=excluded.page_count,
               viewport_w=excluded.viewport_w, updated_at=excluded.updated_at"""
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, page); ps.setInt(3, pageCount)
            ps.setInt(4, viewportW); ps.setLong(5, updatedAt); ps.executeUpdate()
        }
    }

    /** 带显式创建时间的书签写入（同步补录用）。 */
    @Synchronized fun addBookmarkRaw(path: String, page: Int, label: String, note: String, createdAt: Long) {
        conn.prepareStatement("INSERT INTO bookmark(path,page,label,note,created_at) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, path); ps.setInt(2, page); ps.setString(3, label)
            ps.setString(4, note); ps.setLong(5, createdAt); ps.executeUpdate()
        }
    }

    @Synchronized fun listFavoritePaths(): List<String> =
        conn.createStatement().executeQuery("SELECT path FROM favorite").use { rs ->
            val out = ArrayList<String>()
            while (rs.next()) out.add(rs.getString(1))
            out
        }

    @Synchronized fun addFavoriteIfMissing(path: String): Boolean {
        val exists = conn.prepareStatement("SELECT 1 FROM favorite WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { it.next() }
        }
        if (exists) return false
        conn.prepareStatement("INSERT INTO favorite(path, added_at) VALUES(?,?)").use { ps ->
            ps.setString(1, path); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate()
        }
        return true
    }

    // ---- 远程服务器 ----
    @Synchronized fun listRemoteSources(): List<RemoteSource> =
        conn.createStatement().executeQuery(
            "SELECT id, protocol, name, host, port, path, user, password FROM remote_source ORDER BY id"
        ).use { rs ->
            val out = ArrayList<RemoteSource>()
            while (rs.next()) out.add(RemoteSource(
                id = rs.getLong(1), protocol = rs.getString(2) ?: "", name = rs.getString(3) ?: "",
                host = rs.getString(4) ?: "", port = rs.getInt(5), path = rs.getString(6) ?: "",
                user = rs.getString(7) ?: "", password = howread.core.Creds.unprotect(rs.getString(8) ?: ""),
            ))
            out
        }

    @Synchronized fun addRemoteSource(s: RemoteSource): Long {
        conn.prepareStatement(
            "INSERT INTO remote_source(protocol,name,host,port,path,user,password,created_at) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, s.protocol); ps.setString(2, s.name); ps.setString(3, s.host)
            ps.setInt(4, s.port); ps.setString(5, s.path); ps.setString(6, s.user)
            ps.setString(7, howread.core.Creds.protect(s.password)); ps.setLong(8, System.currentTimeMillis()); ps.executeUpdate()
        }
        conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { rs ->
            rs.next(); return rs.getLong(1)
        }
    }

    @Synchronized fun updateRemoteSource(s: RemoteSource) {
        conn.prepareStatement(
            "UPDATE remote_source SET protocol=?, name=?, host=?, port=?, path=?, user=?, password=? WHERE id=?"
        ).use { ps ->
            ps.setString(1, s.protocol); ps.setString(2, s.name); ps.setString(3, s.host)
            ps.setInt(4, s.port); ps.setString(5, s.path); ps.setString(6, s.user)
            ps.setString(7, howread.core.Creds.protect(s.password)); ps.setLong(8, s.id); ps.executeUpdate()
        }
    }

    @Synchronized fun setRemoteSourceShelf(id: Long, on: Boolean) {
        conn.prepareStatement("UPDATE remote_source SET shelf=? WHERE id=?").use { ps ->
            ps.setInt(1, if (on) 1 else 0); ps.setLong(2, id); ps.executeUpdate()
        }
    }

    @Synchronized fun listRemoteSourceRows(): List<Pair<RemoteSource, Boolean>> =
        conn.createStatement().executeQuery(
            "SELECT id, protocol, name, host, port, path, user, password, COALESCE(shelf,1) FROM remote_source ORDER BY id"
        ).use { rs ->
            val out = ArrayList<Pair<RemoteSource, Boolean>>()
            while (rs.next()) out.add(RemoteSource(
                id = rs.getLong(1), protocol = rs.getString(2) ?: "", name = rs.getString(3) ?: "",
                host = rs.getString(4) ?: "", port = rs.getInt(5), path = rs.getString(6) ?: "",
                user = rs.getString(7) ?: "", password = howread.core.Creds.unprotect(rs.getString(8) ?: ""),
            ) to (rs.getInt(9) != 0))
            out
        }

    @Synchronized fun deleteRemoteSource(id: Long) {
        conn.prepareStatement("DELETE FROM remote_source WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeUpdate()
        }
        deleteRemoteBooks(id)
    }

    /** 删除某远程源登记的全部 remote:// 书（连同进度/书签/高亮）。 */
    @Synchronized fun deleteRemoteBooks(id: Long) {
        val pre = "remote://" + id + "/"
        val gone = ArrayList<String>()
        conn.createStatement().executeQuery("SELECT path FROM file_meta WHERE path LIKE '" + pre + "%'").use { rs ->
            while (rs.next()) gone.add(rs.getString(1))
        }
        for (p in gone) {
            for (sql in listOf("DELETE FROM file_meta WHERE path=?", "DELETE FROM recent WHERE path=?",
                "DELETE FROM favorite WHERE path=?", "DELETE FROM progress WHERE path=?",
                "DELETE FROM bookmark WHERE path=?", "DELETE FROM annotation WHERE path=?")) {
                conn.prepareStatement(sql).use { ps -> ps.setString(1, p); ps.executeUpdate() }
            }
        }
        if (gone.isNotEmpty()) TestLog.evt("remote_books_removed", "src=$id n=${gone.size}")
    }

    /** 登记时写的文件大小（远程缓存完成校验用；未登记返回 -1）。 */
    @Synchronized fun bookSize(path: String): Long =
        conn.prepareStatement("SELECT size FROM file_meta WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }

    /** 登记时记录的服务器端修改时间（v0.58 远程缓存版本标记用；未登记/无时间源返回 0）。 */
    @Synchronized fun bookMtime(path: String): Long =
        conn.prepareStatement("SELECT mtime FROM file_meta WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    // ---- 持久高亮（划词保存，页坐标四边形序列化） ----
    @Synchronized
    fun addAnnotation(path: String, page: Int, color: Int, quads: List<AnnQuad>, note: String, pageCount: Int): Long {
        val sb = StringBuilder()
        for (q in quads) sb.append(q.x0).append(',').append(q.y0).append(',').append(q.x1).append(',').append(q.y1).append(';')
        conn.prepareStatement(
            "INSERT INTO annotation(path,page,color,quads,note,page_count,created_at) VALUES(?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, page); ps.setInt(3, color); ps.setString(4, sb.toString())
            ps.setString(5, note); ps.setInt(6, pageCount); ps.setLong(7, System.currentTimeMillis()); ps.executeUpdate()
        }
        conn.createStatement().executeQuery("SELECT last_insert_rowid()").use { rs ->
            rs.next(); return rs.getLong(1)
        }
    }

    private fun parseQuads(raw: String): List<AnnQuad> {
        val out = ArrayList<AnnQuad>()
        for (part in raw.split(';')) {
            if (part.isBlank()) continue
            val f = part.split(',')
            if (f.size == 4) runCatching {
                out.add(AnnQuad(f[0].toFloat(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat()))
            }
        }
        return out
    }

    @Synchronized
    fun listAnnotations(path: String, page: Int): List<AnnotationRow> =
        conn.prepareStatement(
            "SELECT id,page,color,quads,note,page_count,created_at FROM annotation WHERE path=? AND page=? ORDER BY created_at"
        ).use { ps ->
            ps.setString(1, path); ps.setInt(2, page)
            ps.executeQuery().use { rs ->
                val out = ArrayList<AnnotationRow>()
                while (rs.next()) out.add(AnnotationRow(rs.getLong(1), path, rs.getInt(2), rs.getInt(3),
                    parseQuads(rs.getString(4) ?: ""), rs.getString(5) ?: "", rs.getInt(6), rs.getLong(7)))
                out
            }
        }

    @Synchronized
    fun listAllAnnotations(path: String): List<AnnotationRow> =
        conn.prepareStatement(
            "SELECT id,page,color,quads,note,page_count,created_at FROM annotation WHERE path=? ORDER BY page, created_at"
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                val out = ArrayList<AnnotationRow>()
                while (rs.next()) out.add(AnnotationRow(rs.getLong(1), path, rs.getInt(2), rs.getInt(3),
                    parseQuads(rs.getString(4) ?: ""), rs.getString(5) ?: "", rs.getInt(6), rs.getLong(7)))
                out
            }
        }

    @Synchronized
    fun deleteAnnotation(id: Long) {
        conn.prepareStatement("DELETE FROM annotation WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeUpdate()
        }
    }
}
