/* M3 同步相关存取（墓碑/全量进度/原始时间戳写入；schema 与 Java Db.kt v0.51 同款）。 */
#include "hrdb.h"

#include <QDateTime>
#include <QFileInfo>
#include <QHash>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QVariant>

namespace hr
{

QList<Db::ProgressRow> Db::allProgress()
{
    QList<ProgressRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT path,page,page_count,IFNULL(viewport_w,0),IFNULL(updated_at,0) FROM progress");
    while (q.next())
    {
        ProgressRow r;
        r.path = q.value(0).toString();
        r.page = q.value(1).toInt();
        r.pageCount = q.value(2).toInt();
        r.viewportW = q.value(3).toInt();
        r.updatedAt = q.value(4).toLongLong();
        out.append(r);
    }
    return out;
}

void Db::saveProgressRaw(const QString &path, int page, int pageCount, int viewportW, long long t)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO progress(path,page,page_count,viewport_w,updated_at)"
              " VALUES(?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(pageCount);
    q.addBindValue(viewportW);
    q.addBindValue(t);
    q.exec();
}

QList<BookmarkRow> Db::allBookmarks()
{
    QList<BookmarkRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT id,path,page,label,note,created_at,sec,sr,st,pgs,isF,isAiNote,ai_answer"
           " FROM bookmark ORDER BY created_at");
    while (q.next())
    {
        BookmarkRow r;
        r.id = q.value(0).toLongLong();
        r.path = q.value(1).toString();
        r.page = q.value(2).toInt();
        r.label = q.value(3).toString();
        r.note = q.value(4).toString();
        r.createdAt = q.value(5).toLongLong();
        r.sec = q.value(6).isNull() ? -1 : q.value(6).toInt();
        r.sr = q.value(7).toDouble();
        r.st = q.value(8).toString();
        r.pgs = q.value(9).toInt();
        r.isF = q.value(10).toInt() != 0;
        r.isAiNote = q.value(11).toInt() != 0;
        r.aiAnswer = q.value(12).toString();
        out.append(r);
    }
    return out;
}

void Db::addBookmarkRaw(const QString &path, int page, const QString &label,
                        const QString &note, long long createdAt, int sec, double sr,
                        const QString &st, int pgs, int isF, int isAiNote,
                        const QString &aiAnswer)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO"
              " bookmark(path,page,label,note,created_at,sec,sr,st,pgs,isF,isAiNote,ai_answer)"
              " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(label);
    q.addBindValue(note);
    q.addBindValue(createdAt);
    q.addBindValue(sec);
    q.addBindValue(sr);
    q.addBindValue(st);
    q.addBindValue(pgs);
    q.addBindValue(isF);
    q.addBindValue(isAiNote);
    q.addBindValue(aiAnswer);
    q.exec();
}

void Db::tombstoneAdd(const char *kind, const QString &book, const QString &key)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO tombstone(kind,book,key,t) VALUES(?,?,?,?)");
    q.addBindValue(QString::fromLatin1(kind));
    q.addBindValue(book);
    q.addBindValue(key);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
}

long long Db::tombstoneBookT(const QString &book)
{
    if (!s_ok) return 0;
    QSqlQuery q(conn());
    q.prepare("SELECT t FROM tombstone WHERE kind='book' AND book=? LIMIT 1");
    q.addBindValue(book);
    if (q.exec() && q.next()) return q.value(0).toLongLong();
    return 0;
}

QHash<QString, long long> Db::tombstoneBmMap(const QString &book)
{
    return tombstoneMap("bm", book);
}

/* v1.3.0（F'）：泛化墓碑读取（ann 等复用同表） */
QHash<QString, long long> Db::tombstoneMap(const char *kind, const QString &book)
{
    QHash<QString, long long> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.prepare("SELECT key,t FROM tombstone WHERE kind=? AND book=?");
    q.addBindValue(QString::fromLatin1(kind));
    q.addBindValue(book);
    if (q.exec())
        while (q.next()) out.insert(q.value(0).toString(), q.value(1).toLongLong());
    return out;
}

/* v1.3.0（F'）：有注解的书（去重路径） */
QList<QString> Db::listAnnotationBooks()
{
    QList<QString> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT DISTINCT path FROM annotation");
    while (q.next()) out.append(q.value(0).toString());
    return out;
}

/* v1.3.0（F'）：下行落库（保留远端创建毫秒为键） */
void Db::addAnnotationRaw(const QString &path, int page, int color, const QString &quads,
                          const QString &note, int pageCount, long long createdAt)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO"
              " annotation(path,page,color,quads,note,page_count,created_at) VALUES(?,?,?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(color);
    q.addBindValue(quads);
    q.addBindValue(note);
    q.addBindValue(pageCount);
    q.addBindValue(createdAt);
    q.exec();
}

/* v1.3.0（F'）：对端墓碑应用（按创建毫秒删行） */
void Db::deleteAnnotationByCreated(const QString &path, long long createdAt)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM annotation WHERE path=? AND created_at=?");
    q.addBindValue(path);
    q.addBindValue(createdAt);
    q.exec();
}


/* U-ann 注解（annotation 表，Compose/安卓互通同 schema） */
long long Db::addAnnotation(const QString &path, int page, int color, const QString &quads,
                            const QString &note, int pageCount)
{
    if (!s_ok) return -1;
    QSqlQuery q(conn());
    q.prepare("INSERT INTO annotation(path,page,color,quads,note,page_count,created_at) "
              "VALUES(?,?,?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(color);
    q.addBindValue(quads);
    q.addBindValue(note);
    q.addBindValue(pageCount);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    if (!q.exec()) return -1;
    QSqlQuery q2(conn());
    q2.exec("SELECT last_insert_rowid()");
    return q2.next() ? q2.value(0).toLongLong() : -1;
}

QList<Db::AnnotationRow> Db::listAnnotations(const QString &path, int page)
{
    QList<AnnotationRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    if (page >= 0)
    {
        q.prepare("SELECT id,page,color,IFNULL(quads,''),IFNULL(note,''),"
                  "IFNULL(page_count,0),IFNULL(created_at,0) FROM annotation "
                  "WHERE path=? AND page=? ORDER BY created_at");
        q.addBindValue(path);
        q.addBindValue(page);
    }
    else
    {
        q.prepare("SELECT id,page,color,IFNULL(quads,''),IFNULL(note,''),"
                  "IFNULL(page_count,0),IFNULL(created_at,0) FROM annotation "
                  "WHERE path=? ORDER BY page, created_at");
        q.addBindValue(path);
    }
    q.exec();
    while (q.next())
    {
        AnnotationRow r;
        r.id = q.value(0).toLongLong();
        r.path = path;
        r.page = q.value(1).toInt();
        r.color = q.value(2).toInt();
        r.quads = q.value(3).toString();
        r.note = q.value(4).toString();
        r.pageCount = q.value(5).toInt();
        r.createdAt = q.value(6).toLongLong();
        out.append(r);
    }
    return out;
}

void Db::deleteAnnotation(long long id)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM annotation WHERE id=?");
    q.addBindValue(id);
    q.exec();
}

/* P1 适配（C1）：书签笔记（bookmark.note 列与 WIN/安卓互通，0.61.1 建表即有此列） */
void Db::updateBookmarkNote(long long id, const QString &note)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("UPDATE bookmark SET note=? WHERE id=?");
    q.addBindValue(note);
    q.addBindValue(id);
    q.exec();
}

/* ===== v1.4.0（R/F）：最近/收藏云同步数据层（安卓 syncMetaUnion 同语义） ===== */

QVector<Db::FavRow> Db::favoriteRows()
{
    QVector<FavRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    if (!q.exec("SELECT path, added_at, sync_key FROM favorite ORDER BY added_at DESC"))
        return out;
    while (q.next())
    {
        FavRow r;
        r.path = q.value(0).toString();
        r.t = q.value(1).toLongLong();
        r.syncKey = q.value(2).toString();
        out.append(r);
    }
    return out;
}

QVector<Db::FavRow> Db::recentRows()
{
    QVector<FavRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    if (!q.exec("SELECT path, last_read_at, sync_key FROM recent ORDER BY last_read_at DESC"))
        return out;
    while (q.next())
    {
        FavRow r;
        r.path = q.value(0).toString();
        r.t = q.value(1).toLongLong();
        r.syncKey = q.value(2).toString();
        out.append(r);
    }
    return out;
}

void Db::upsertFavoriteAt(const QString &path, long long t, const QString &syncKey)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO favorite(path,added_at,sync_key) VALUES(?,?,?)");
    q.addBindValue(path);
    q.addBindValue(t);
    q.addBindValue(syncKey);
    q.exec();
}

void Db::upsertRecentAt(const QString &path, long long t, const QString &syncKey)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO recent(path,last_read_at,sync_key) VALUES(?,?,?)");
    q.addBindValue(path);
    q.addBindValue(t);
    q.addBindValue(syncKey);
    q.exec();
}

void Db::setFavoriteSync(const QString &path, bool on)
{
    if (!s_ok) return;
    /* 墓碑/待定表键口径 = 云端条目键：远端来的行用其 sync_key（远端原键），
     * 本地纯增的行用发布键（internal-storage:Books/<文件名>，安卓 getSyncPath
     * 显示期重定位口径）；重新收藏清墓碑（安卓 add 同款）。 */
    QString key;
    {
        QSqlQuery q(conn());
        q.prepare("SELECT sync_key FROM favorite WHERE path=?");
        q.addBindValue(path);
        if (q.exec() && q.next()) key = q.value(0).toString();
    }
    const QString booksKey =
        QString::fromLatin1("internal-storage:Books/") + QFileInfo(path).fileName();
    if (key.isEmpty()) key = booksKey;
    const QString noBook = QString::fromLatin1(""); /* 显式空串：QString() 绑 NULL 永不匹配 */
    if (on)
    {
        setFavorite(path, true);
        tombstoneClear("favorite", noBook, key);
        tombstoneClear("favorite", noBook, booksKey);
        metaSyncDelete("favorite", key);
    }
    else
    {
        setFavorite(path, false);
        tombstoneAdd("favorite", noBook, key);
        metaSyncDelete("favorite", key);
    }
}

QVector<Db::MetaEntry> Db::metaSyncList(const char *kind)
{
    QVector<MetaEntry> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.prepare("SELECT key, name, time FROM meta_sync WHERE kind=?");
    q.addBindValue(QString::fromUtf8(kind));
    q.exec();
    while (q.next())
    {
        MetaEntry m;
        m.key = q.value(0).toString();
        m.name = q.value(1).toString();
        m.time = q.value(2).toLongLong();
        out.append(m);
    }
    return out;
}

void Db::metaSyncUpsert(const char *kind, const QString &key, const QString &name, long long time)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO meta_sync(kind,key,name,time) VALUES(?,?,?,?)");
    q.addBindValue(QString::fromUtf8(kind));
    q.addBindValue(key);
    q.addBindValue(name);
    q.addBindValue(time);
    q.exec();
}

void Db::metaSyncSetTime(const char *kind, const QString &key, long long time)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("UPDATE meta_sync SET time=? WHERE kind=? AND key=?");
    q.addBindValue(time);
    q.addBindValue(QString::fromUtf8(kind));
    q.addBindValue(key);
    q.exec();
}

void Db::metaSyncDelete(const char *kind, const QString &key)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM meta_sync WHERE kind=? AND key=?");
    q.addBindValue(QString::fromUtf8(kind));
    q.addBindValue(key);
    q.exec();
}

void Db::tombstoneClear(const char *kind, const QString &book, const QString &key)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM tombstone WHERE kind=? AND book=? AND key=?");
    q.addBindValue(QString::fromUtf8(kind));
    q.addBindValue(book);
    q.addBindValue(key);
    q.exec();
}

} // namespace hr
