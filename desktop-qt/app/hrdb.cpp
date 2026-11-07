#include "hrdb.h"
#include "hrcred.h"
#include "hrpaths.h"

#include <QDateTime>
#include <QDir>
#include <QSet> /* P2（A2） */
#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>
#include <QThread>
#include <QVariant>

namespace hr
{

bool Db::s_ok = false;

/* 每线程独立命名连接（QSqlDatabase 线程亲和） */
QSqlDatabase Db::conn()
{
    const QString name =
        QStringLiteral("hr_%1").arg(reinterpret_cast<quintptr>(QThread::currentThreadId()));
    if (!QSqlDatabase::contains(name))
    {
        QSqlDatabase db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), name);
        db.setDatabaseName(dbFile());
        if (db.open())
        {
            QSqlQuery q(db);
            q.exec("PRAGMA busy_timeout=5000");
        }
    }
    return QSqlDatabase::database(name);
}

bool Db::open()
{
    if (s_ok) return true;
    QSqlDatabase db = conn();
    s_ok = db.isOpen();
    if (!s_ok)
    {
        evt("db_open_fail", "err=" + db.lastError().text().left(120));
        return false;
    }
    QSqlQuery q(db);
    struct T
    {
        const char *sql;
    } ddl[] = {
        {"CREATE TABLE IF NOT EXISTS file_meta(path TEXT PRIMARY KEY, title TEXT, pages INTEGER,"
         " size INTEGER, mtime INTEGER, added_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS recent(path TEXT PRIMARY KEY, last_read_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS favorite(path TEXT PRIMARY KEY, added_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS progress(path TEXT PRIMARY KEY, page INTEGER,"
         " page_count INTEGER, viewport_w INTEGER, updated_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS scan_dirs(path TEXT PRIMARY KEY, added_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS bookmark(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT,"
         " page INTEGER, label TEXT, note TEXT, created_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS read_stats(path TEXT PRIMARY KEY, seconds INTEGER,"
         " pages INTEGER, day INTEGER, day_seconds INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS daily_stats(day INTEGER PRIMARY KEY, seconds INTEGER,"
         " pages INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS remote_source(id INTEGER PRIMARY KEY AUTOINCREMENT,"
         " protocol TEXT, name TEXT, host TEXT, port INTEGER, path TEXT, user TEXT,"
         " password TEXT, created_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS annotation(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT,"
         " page INTEGER, color INTEGER, quads TEXT, note TEXT, page_count INTEGER,"
         " created_at INTEGER)"},
        {"CREATE TABLE IF NOT EXISTS tombstone(kind TEXT, book TEXT, key TEXT, t INTEGER,"
         " PRIMARY KEY(kind, book, key))"},
    };
    for (const T &t : ddl)
        q.exec(QString::fromUtf8(t.sql));
    q.exec("ALTER TABLE scan_dirs ADD COLUMN shelf INTEGER DEFAULT 1");
    q.exec("ALTER TABLE remote_source ADD COLUMN shelf INTEGER DEFAULT 1");
    q.exec("ALTER TABLE file_meta ADD COLUMN author TEXT");
    q.exec("ALTER TABLE file_meta ADD COLUMN series TEXT");
    q.exec("ALTER TABLE file_meta ADD COLUMN series_index INTEGER");
    q.exec("ALTER TABLE file_meta ADD COLUMN tags TEXT");
    q.exec("ALTER TABLE file_meta ADD COLUMN description TEXT");
    evt("db_open", "file=howread.db");
    return true;
}

void Db::saveProgress(const QString &path, int page, int pageCount, int viewportW)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO progress(path,page,page_count,viewport_w,updated_at)"
              " VALUES(?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(pageCount);
    q.addBindValue(viewportW);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
}

bool Db::loadProgress(const QString &path, int *page, int *pageCount, int *viewportW)
{
    if (!s_ok) return false;
    QSqlQuery q(conn());
    q.prepare("SELECT page,page_count,viewport_w FROM progress WHERE path=?");
    q.addBindValue(path);
    if (!q.exec() || !q.next()) return false;
    if (page) *page = q.value(0).toInt();
    if (pageCount) *pageCount = q.value(1).toInt();
    if (viewportW) *viewportW = q.value(2).toInt();
    return true;
}

void Db::touchRecent(const QString &path)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO recent(path,last_read_at) VALUES(?,?)");
    q.addBindValue(path);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
}

QString Db::lastRecent()
{
    if (!s_ok) return QString();
    QSqlQuery q(conn());
    if (!q.exec("SELECT path FROM recent WHERE path NOT LIKE 'remote://%'"
                " ORDER BY last_read_at DESC LIMIT 1") || !q.next())
        return QString();
    return q.value(0).toString();
}

void Db::upsertFileMeta(const QString &path, const QString &title, int pages, long long size,
                        long long mtime, const QString &author, const QString &series,
                        int seriesIdx)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    /* v0.82.0 W1：改真 UPSERT——原 INSERT OR REPLACE 是整行替换，重扫会把
     * tags/description/author 等扩展列连带清空；ON CONFLICT 只更新本列，
     * added_at 与扩展列保留（author 仅在新值非空时覆盖）。 */
    q.prepare("INSERT INTO file_meta(path,title,pages,size,mtime,added_at,author,series,series_index)"
              " VALUES(?,?,?,?,?,?,?,?,?)"
              " ON CONFLICT(path) DO UPDATE SET title=excluded.title, pages=excluded.pages,"
              " size=excluded.size, mtime=excluded.mtime,"
              " author=CASE WHEN IFNULL(excluded.author,'')<>'' THEN excluded.author"
              " ELSE file_meta.author END,"
              " series=excluded.series, series_index=excluded.series_index");
    q.addBindValue(path);
    q.addBindValue(title);
    q.addBindValue(pages);
    q.addBindValue(size);
    q.addBindValue(mtime);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.addBindValue(author);
    q.addBindValue(series);
    q.addBindValue(seriesIdx);
    q.exec();
}

long long Db::addBookmark(const QString &path, int page, const QString &label,
                          const QString &note)
{
    if (!s_ok) return -1;
    QSqlQuery q(conn());
    q.prepare("INSERT INTO bookmark(path,page,label,note,created_at) VALUES(?,?,?,?,?)");
    q.addBindValue(path);
    q.addBindValue(page);
    q.addBindValue(label);
    q.addBindValue(note);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    if (!q.exec()) return -1;
    return q.lastInsertId().toLongLong();
}

QList<BookmarkRow> Db::listBookmarks(const QString &path)
{
    QList<BookmarkRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.prepare("SELECT id,path,page,label,note,created_at FROM bookmark WHERE path=?"
              " ORDER BY created_at DESC, id DESC");
    q.addBindValue(path);
    if (!q.exec()) return out;
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

void Db::deleteBookmark(long long id)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM bookmark WHERE id=?");
    q.addBindValue(id);
    q.exec();
}

/* ---------- U5 远程服务器（remote_source；密码 DPAPI 落盘） ---------- */

QList<Db::RemoteSrc> Db::listRemoteSrc()
{
    QList<RemoteSrc> out;
    QSqlQuery q(conn());
    q.exec("SELECT id,protocol,name,host,port,path,user,password FROM remote_source ORDER BY id");
    while (q.next())
    {
        RemoteSrc r;
        r.id = q.value(0).toInt();
        r.protocol = q.value(1).toString();
        r.name = q.value(2).toString();
        r.host = q.value(3).toString();
        r.port = q.value(4).toInt();
        r.path = q.value(5).toString();
        r.user = q.value(6).toString();
        r.password = unprotectSecret(q.value(7).toString());
        out.append(r);
    }
    return out;
}

int Db::addRemoteSrc(const RemoteSrc &r)
{
    QSqlQuery q(conn());
    q.prepare("INSERT INTO remote_source(protocol,name,host,port,path,user,password,created_at)"
              " VALUES(?,?,?,?,?,?,?,?)");
    q.addBindValue(r.protocol);
    q.addBindValue(r.name);
    q.addBindValue(r.host);
    q.addBindValue(r.port);
    q.addBindValue(r.path);
    q.addBindValue(r.user);
    q.addBindValue(protectSecret(r.password));
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
    QSqlQuery q2(conn());
    q2.exec("SELECT last_insert_rowid()");
    return q2.next() ? q2.value(0).toInt() : -1;
}

bool Db::updateRemoteSrc(const RemoteSrc &r)
{
    QSqlQuery q(conn());
    q.prepare("UPDATE remote_source SET protocol=?,name=?,host=?,port=?,path=?,user=?,password=?"
              " WHERE id=?");
    q.addBindValue(r.protocol);
    q.addBindValue(r.name);
    q.addBindValue(r.host);
    q.addBindValue(r.port);
    q.addBindValue(r.path);
    q.addBindValue(r.user);
    q.addBindValue(protectSecret(r.password));
    q.addBindValue(r.id);
    return q.exec();
}

bool Db::deleteRemoteSrc(int id)
{
    QSqlQuery q(conn());
    q.prepare("DELETE FROM remote_source WHERE id=?");
    q.addBindValue(id);
    const bool ok = q.exec();
    /* P1 适配（E11）：删除远程源级联清 remote:// 书目与进度/书签/注解
     * （对齐 WIN deleteRemoteSource→deleteRemoteBooks，防孤儿条目） */
    if (ok)
    {
        const QString pfx = QString::fromLatin1("remote://%1/").arg(id);
        QSqlQuery q2(conn());
        const char *tabs[] = {"file_meta", "progress", "recent",
                              "favorite", "bookmark", "annotation"};
        for (const char *t : tabs)
            q2.exec(QString::fromLatin1("DELETE FROM %1 WHERE path LIKE '%2%'")
                        .arg(QLatin1String(t), pfx));
    }
    return ok;
}

/* P2 适配（A2）：远程源裁剪（对齐 WIN pruneRemoteSource：完整扫描后删失效登记；
 * 只删 file_meta——已开过的书进度/书签路径仍在 keep 中不受影响） */
void Db::pruneRemoteSource(int id, const QSet<QString> &keep)
{
    const QString pfx = QString::fromLatin1("remote://%1/").arg(id);
    QSqlQuery q(conn());
    q.exec(QString::fromLatin1("SELECT path FROM file_meta WHERE path LIKE '%1%'")
               .arg(pfx));
    QStringList doomed;
    while (q.next())
    {
        const QString p = q.value(0).toString();
        if (!keep.contains(p)) doomed.append(p);
    }
    for (const QString &p : doomed) deleteMeta(p);
    if (!doomed.isEmpty())
        evt(QString::fromLatin1("remote_prune"),
            QString::fromLatin1("id=%1 n=%2").arg(id).arg(doomed.size()));
}

/* ---------- U6 标签 / AI 简介（file_meta 扩展列） ---------- */

void Db::setTags(const QString &path, const QString &tags)
{
    QSqlQuery q(conn());
    q.prepare("UPDATE file_meta SET tags=? WHERE path=?");
    q.addBindValue(tags);
    q.addBindValue(path);
    q.exec();
}

QString Db::tagsOf(const QString &path)
{
    QSqlQuery q(conn());
    q.prepare("SELECT tags FROM file_meta WHERE path=?");
    q.addBindValue(path);
    q.exec();
    return q.next() ? q.value(0).toString() : QString();
}

void Db::setDescription(const QString &path, const QString &desc)
{
    QSqlQuery q(conn());
    q.prepare("UPDATE file_meta SET description=? WHERE path=?");
    q.addBindValue(desc);
    q.addBindValue(path);
    q.exec();
}

QString Db::descriptionOf(const QString &path)
{
    QSqlQuery q(conn());
    q.prepare("SELECT description FROM file_meta WHERE path=?");
    q.addBindValue(path);
    q.exec();
    return q.next() ? q.value(0).toString() : QString();
}

} // namespace hr
