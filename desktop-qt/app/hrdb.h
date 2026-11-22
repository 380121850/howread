/* sqlite（Qt SQL/QSQLITE）：与 Java 版 desktop/core/data/Db.kt 同 schema 同库文件 howread.db。 */
#ifndef HRDB_H
#define HRDB_H

#include <QList>
#include <QString>
#include <QSqlDatabase>

namespace hr
{

struct BookmarkRow
{
    long long id = 0;
    QString path;
    int page = 0;
    QString label, note;
    long long createdAt = 0;
    /* v1.1.0：章节锚（安卓 1.3.21 sec/sr/st 互通）+ 捕获时排版纪元总页数 */
    int sec = -1;
    double sr = 0;
    QString st;
    int pgs = 0;
    /* v1.2.0（B2/B3）：安卓书签键面补齐——收藏标 / AI 笔记（Qt 仅消费展示） */
    int isF = 0;
    int isAiNote = 0;
    QString aiAnswer;
};

class Db
{
public:
    static bool open(); /* 幂等；建表同 Db.kt init 块 */
    static bool ok() { return s_ok; }

    static void saveProgress(const QString &path, int page, int pageCount, int viewportW);
    static bool loadProgress(const QString &path, int *page, int *pageCount, int *viewportW);
    static void touchRecent(const QString &path);
    static QString lastRecent(); /* 最近打开的一本（排除 remote://；无则空串） */
    static QStringList listRecent(int limit = 10); /* v0.83.0 W2：最近书单（换书菜单） */
    /* v1.4.0（R/F）：最近/收藏云同步（安卓 syncMetaUnion 同语义） */
    struct FavRow { QString path, syncKey; long long t = 0; };
    static QVector<FavRow> favoriteRows(); /* (path, added_at, sync_key) added_at 降序 */
    static QVector<FavRow> recentRows();   /* (path, last_read_at, sync_key) 降序 */
    static void upsertFavoriteAt(const QString &path, long long t, const QString &syncKey);
    static void upsertRecentAt(const QString &path, long long t, const QString &syncKey);
    static void removeRecentSync(const QString &path); /* v1.14.0：最近列表移除（行删+recent 墓碑+待定清理；书/进度保留） */
    static void setFavoriteSync(const QString &path, bool on); /* UI 钩子：收藏增删带墓碑/待定表 */
    static void upsertFileMeta(const QString &path, const QString &title, int pages,
                               long long size, long long mtime,
                               const QString &author = QString(),
                               const QString &series = QString(), int seriesIdx = -1);
    static long long addBookmark(const QString &path, int page, const QString &label,
                                 const QString &note, int sec = -1, double sr = 0,
                                 const QString &st = QString(), int pgs = 0);
    static QList<BookmarkRow> listBookmarks(const QString &path);
    static void deleteBookmark(long long id);
    static void updateBookmarkNote(long long id, const QString &note); /* P1（C1）书签笔记 */
    static void setBookmarkFav(long long id, bool on); /* B2：收藏标（安卓 AppBookmark.isF） */
    static void deleteProgress(const QString &path);   /* G：标记未读=清进度行 */

    /* M2 书库 */
    struct MetaRow
    {
        QString path, title;
        int pages = 0;
        long long size = 0, mtime = 0, addedAt = 0;
        QString author, series; /* v0.82.0 W1：作者=引擎元数据；系列=文件名解析（Compose 同口径） */
        int seriesIdx = -1;
        QString tags; /* v0.84.0 W3 D4：标签（逗号分隔），侧栏标签区聚合用 */
    };
    struct ScanDirRow
    {
        QString path;
        bool shelf = true;
    };
    static QStringList scanDirs();
    static QVector<ScanDirRow> scanDirRows(); /* P2（A1）：含 shelf 勾选 */
    static void setScanDirShelf(const QString &path, bool on); /* P2（A1） */
    static void addScanDir(const QString &path);
    static void removeScanDir(const QString &path);
    static void setFavorite(const QString &path, bool on);
    static bool isFavorite(const QString &path);
    static QList<QString> listFavorites(); /* U7：珍藏页/首页区块（added_at 降序） */
    /* v1.4.0（R/F）：远端列表条目待定区（未匹配本地书的下行条目持久留存，
     * 书后到位下一轮自动解析——安卓"列表常驻+显示期过滤"同款容忍） */
    struct MetaEntry { QString key, name; long long time = 0; };
    static QVector<MetaEntry> metaSyncList(const char *kind);
    static void metaSyncUpsert(const char *kind, const QString &key, const QString &name, long long time);
    static void metaSyncSetTime(const char *kind, const QString &key, long long time);
    static void metaSyncDelete(const char *kind, const QString &key);
    static QList<MetaRow> listLibrary();       /* file_meta 全表，added_at 降序 */
    static void deleteMeta(const QString &path);
    /* U6 标签 / AI 简介（file_meta 扩展列） */
    static void setTags(const QString &path, const QString &tags);
    static QString tagsOf(const QString &path);
    static void setDescription(const QString &path, const QString &desc);
    static QString descriptionOf(const QString &path);

    /* U5 远程服务器（remote_source 表；密码 DPAPI 落盘，读出即解密） */
    struct RemoteSrc
    {
        int id = 0;
        QString protocol, name, host, path, user, password;
        int port = 0;
    };
    static QList<RemoteSrc> listRemoteSrc();
    static int addRemoteSrc(const RemoteSrc &r);  /* 返回新 id */
    static bool updateRemoteSrc(const RemoteSrc &r);
    static bool deleteRemoteSrc(int id);
    static void pruneRemoteSource(int id, const QSet<QString> &keep); /* P2（A2）源裁剪 */

    /* M3 同步 */
    struct ProgressRow
    {
        QString path;
        int page = 0, pageCount = 0, viewportW = 0;
        long long updatedAt = 0;
    };
    static QList<ProgressRow> allProgress();
    static void saveProgressRaw(const QString &path, int page, int pageCount, int viewportW,
                                long long t);
    static QList<BookmarkRow> allBookmarks();

    /* U-ann 注解/持久高亮（annotation 表；与 Compose Db.kt 同 schema/序列化 "x0,y0,x1,y1;"） */
    struct AnnotationRow
    {
        long long id = 0;
        QString path;
        int page = 0, color = 0, pageCount = 0;
        QString quads, note;
        long long createdAt = 0;
    };
    static long long addAnnotation(const QString &path, int page, int color,
                                   const QString &quads, const QString &note, int pageCount);
    static QList<AnnotationRow> listAnnotations(const QString &path, int page); /* -1=全本 */
    static void deleteAnnotation(long long id);
    /* v1.3.0（F'）：注解 Qt 命名空间侧车（global/HowRead-desktop-annotations/<MD5>.json） */
    static QList<QString> listAnnotationBooks(); /* 有注解的书路径（去重） */
    static void addAnnotationRaw(const QString &path, int page, int color, const QString &quads,
                                 const QString &note, int pageCount, long long createdAt); /* 下行落库保原键 */
    static void deleteAnnotationByCreated(const QString &path, long long createdAt); /* 对端墓碑应用 */
    static QHash<QString, long long> tombstoneMap(const char *kind, const QString &book); /* 泛化墓碑读取 */
    static void addBookmarkRaw(const QString &path, int page, const QString &label,
                               const QString &note, long long createdAt,
                               int sec = -1, double sr = 0,
                               const QString &st = QString(), int pgs = 0,
                               int isF = 0, int isAiNote = 0,
                               const QString &aiAnswer = QString());
    static void tombstoneAdd(const char *kind, const QString &book, const QString &key);
    static void tombstoneClear(const char *kind, const QString &book, const QString &key); /* v1.4.0：重新收藏清墓碑（安卓 RemoteTombstones.clear 同款） */
    static long long tombstoneBookT(const QString &book);
    static QHash<QString, long long> tombstoneBmMap(const QString &book);

    /* P0 适配 2026-11-04（对齐 WIN Db.kt pruneMissing/removeBook） */
    static bool pruneMissing(const QStringList &dirs, QString *detail = nullptr);
    static bool pruneOutsideDirs(const QStringList &dirs, QString *detail = nullptr); /* v0.110.1：未勾选目录的书移出书库（源文件不动） */
    static void removeBookFull(const QString &path); /* 级联 6 表 + book 墓碑（同步防复活） */
    static bool vacuumInto(const QString &destFile); /* VACUUM INTO 一致性快照（备份用） */

private:
    static bool s_ok;
    static QSqlDatabase conn(); /* 每线程独立命名连接（QSqlDatabase 线程亲和） */
};

} // namespace hr

#endif /* HRDB_H */
