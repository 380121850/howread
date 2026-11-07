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
    static void upsertFileMeta(const QString &path, const QString &title, int pages,
                               long long size, long long mtime,
                               const QString &author = QString(),
                               const QString &series = QString(), int seriesIdx = -1);
    static long long addBookmark(const QString &path, int page, const QString &label,
                                 const QString &note);
    static QList<BookmarkRow> listBookmarks(const QString &path);
    static void deleteBookmark(long long id);
    static void updateBookmarkNote(long long id, const QString &note); /* P1（C1）书签笔记 */

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
    static void addBookmarkRaw(const QString &path, int page, const QString &label,
                               const QString &note, long long createdAt);
    static void tombstoneAdd(const char *kind, const QString &book, const QString &key);
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
