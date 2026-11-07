/* M3 同步相关存取（墓碑/全量进度/原始时间戳写入；schema 与 Java Db.kt v0.51 同款）。 */
#include "hrdb.h"

#include <QDateTime>
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
    q.exec("SELECT id,path,page,label,note,created_at FROM bookmark ORDER BY created_at");
    while (q.next())
    {
        BookmarkRow r;
        r.id = q.value(0).toLongLong();
        r.path = q.value(1).toString();
        r.page = q.value(2).toInt();
        r.label = q.value(3).toString();
        r.note = q.value(4).toString();
        r.createdAt = q.value(5).toLongLong();
        out.append(r);
    }
    return out;
}

void Db::addBookmarkRaw(const QString &path, int page, const QString &label,
                        const QString &note, long long createdAt)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO bookmark(path,page,label,note,created_at)"
              " VALUES(?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(label);
    q.addBindValue(note);
    q.addBindValue(createdAt);
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
    QHash<QString, long long> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.prepare("SELECT key,t FROM tombstone WHERE kind='bm' AND book=?");
    q.addBindValue(book);
    if (q.exec())
        while (q.next()) out.insert(q.value(0).toString(), q.value(1).toLongLong());
    return out;
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

} // namespace hr
