/* M2 book storage access (split from hrdb.cpp). */
#include "hrdb.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QVariant>

#include "hrsync.h" /* P0：removeBookFull 的 book 墓碑需要内容 MD5（syncMd5） */
#include "hrpaths.h" /* P0：evt 打点 */
#include <QSqlError> /* P0：vacuum_fail 文本 */

namespace hr
{

QStringList Db::scanDirs()
{
    QStringList out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT path FROM scan_dirs ORDER BY added_at");
    while (q.next()) out.append(q.value(0).toString());
    return out;
}

/* P2 适配（A1）：目录勾选上架（scan_dirs.shelf，列已在 0.99.x 迁移建好） */
QVector<Db::ScanDirRow> Db::scanDirRows()
{
    QVector<ScanDirRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT path, COALESCE(shelf,1) FROM scan_dirs ORDER BY added_at");
    while (q.next())
    {
        ScanDirRow r;
        r.path = q.value(0).toString();
        r.shelf = q.value(1).toInt() != 0;
        out.append(r);
    }
    return out;
}

void Db::setScanDirShelf(const QString &path, bool on)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("UPDATE scan_dirs SET shelf=? WHERE path=?");
    q.addBindValue(on ? 1 : 0);
    q.addBindValue(path);
    q.exec();
}

void Db::addScanDir(const QString &path)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("INSERT OR REPLACE INTO scan_dirs(path,added_at) VALUES(?,?)");
    q.addBindValue(path);
    q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    q.exec();
}

void Db::removeScanDir(const QString &path)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM scan_dirs WHERE path=?");
    q.addBindValue(path);
    q.exec();
}

void Db::setFavorite(const QString &path, bool on)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    if (on)
    {
        q.prepare("INSERT OR REPLACE INTO favorite(path,added_at) VALUES(?,?)");
        q.addBindValue(path);
        q.addBindValue(QDateTime::currentMSecsSinceEpoch());
    }
    else
    {
        q.prepare("DELETE FROM favorite WHERE path=?");
        q.addBindValue(path);
    }
    q.exec();
}

bool Db::isFavorite(const QString &path)
{
    if (!s_ok) return false;
    QSqlQuery q(conn());
    q.prepare("SELECT 1 FROM favorite WHERE path=?");
    q.addBindValue(path);
    return q.exec() && q.next();
}

QList<QString> Db::listFavorites()
{
    QList<QString> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT path FROM favorite ORDER BY IFNULL(added_at,0) DESC, path");
    while (q.next()) out.append(q.value(0).toString());
    return out;
}

QList<Db::MetaRow> Db::listLibrary()
{
    QList<MetaRow> out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.exec("SELECT path,IFNULL(title,''),IFNULL(pages,0),IFNULL(size,0),IFNULL(mtime,0),"
           "IFNULL(added_at,0),IFNULL(author,''),IFNULL(series,''),IFNULL(series_index,-1),"
           "IFNULL(tags,'')"
           " FROM file_meta ORDER BY IFNULL(added_at,0) DESC, path");
    while (q.next())
    {
        MetaRow r;
        r.path = q.value(0).toString();
        r.title = q.value(1).toString();
        r.pages = q.value(2).toInt();
        r.size = q.value(3).toLongLong();
        r.mtime = q.value(4).toLongLong();
        r.addedAt = q.value(5).toLongLong();
        r.author = q.value(6).toString();
        r.series = q.value(7).toString();
        r.seriesIdx = q.value(8).toInt();
        r.tags = q.value(9).toString();
        out.append(r);
    }
    return out;
}

QStringList Db::listRecent(int limit) /* v0.83.0 W2：换书菜单数据 */
{
    QStringList out;
    if (!s_ok) return out;
    QSqlQuery q(conn());
    q.prepare("SELECT path FROM recent ORDER BY last_read_at DESC LIMIT ?");
    q.addBindValue(limit);
    q.exec();
    while (q.next())
        out.append(q.value(0).toString());
    return out;
}

void Db::deleteMeta(const QString &path)
{
    if (!s_ok) return;
    QSqlQuery q(conn());
    q.prepare("DELETE FROM file_meta WHERE path=?");
    q.addBindValue(path);
    q.exec();
}

/* P0 适配：移出书库/删除文件 = 级联清理 + book 墓碑（对齐 WIN Db.removeBook：
 * progress/recent/favorite/bookmark/annotation/file_meta 全清，墓碑防同步复活） */
void Db::removeBookFull(const QString &path)
{
    if (!s_ok) return;
    QString h = path;
    if (!path.startsWith(QString::fromLatin1("remote://")))
    {
        const QString m = hr::syncMd5(path);
        if (!m.isEmpty()) h = m;
    }
    QSqlDatabase c = conn();
    c.transaction();
    QSqlQuery q(c);
    const char *tabs[] = {"progress", "recent", "favorite", "bookmark", "annotation"};
    for (const char *t : tabs)
    {
        q.prepare(QString::fromLatin1("DELETE FROM %1 WHERE path=?").arg(QLatin1String(t)));
        q.addBindValue(path);
        q.exec();
    }
    q.prepare("DELETE FROM file_meta WHERE path=?");
    q.addBindValue(path);
    q.exec();
    c.commit();
    tombstoneAdd("book", h, path);
    evt(QString::fromLatin1("book_removed"), QString::fromLatin1("path=%1").arg(path.left(80)));
}

/* P0 适配：prune——文件/目录消失后级联清理（对齐 WIN pruneMissing：
 * 任一目录不可访问则整轮跳过，防网络盘瞬时断连误删；消失的书写墓碑） */
bool Db::pruneMissing(const QStringList &dirs, QString *detail)
{
    if (!s_ok) return false;
    for (const QString &d : dirs)
        if (!QDir(d).exists()) return false;
    int n = 0;
    const QList<MetaRow> all = listLibrary();
    for (const MetaRow &r : all)
    {
        if (r.path.startsWith(QString::fromLatin1("remote://"))) continue;
        if (QFileInfo::exists(r.path)) continue;
        removeBookFull(r.path);
        n++;
    }
    if (detail && n > 0) *detail = QString::fromLatin1("pruned=%1").arg(n);
    return true;
}

/* v0.110.1：书库来源勾选治理——不在勾选目录下的本地书移出书库
 * （级联清理登记+进度+书签；源文件不动） */
bool Db::pruneOutsideDirs(const QStringList &dirs, QString *detail)
{
    if (!s_ok) return false;
    int n = 0;
    const QList<MetaRow> all = listLibrary();
    for (const MetaRow &r : all)
    {
        if (r.path.startsWith(QString::fromLatin1("remote://"))) continue;
        const QString lp = QDir::fromNativeSeparators(QDir(r.path).absolutePath());
        bool under = false;
        for (const QString &d : dirs)
        {
            const QString ld = QDir::fromNativeSeparators(QDir(d).absolutePath());
            if (lp.compare(ld, Qt::CaseInsensitive) == 0 ||
                lp.startsWith(ld + "/", Qt::CaseInsensitive))
            {
                under = true;
                break;
            }
        }
        if (under) continue;
        removeBookFull(r.path);
        n++;
    }
    if (detail) *detail = QString::fromLatin1("pruned=%1").arg(n);
    return true;
}

/* P0 适配：一致性快照（备份用；sqlite>=3.27 VACUUM INTO） */
bool Db::vacuumInto(const QString &destFile)
{
    if (!s_ok) return false;
    QSqlQuery q(conn());
    const QString sql = QString::fromLatin1("VACUUM INTO '%1'")
                            .arg(QString(destFile).replace("'", "''"));
    if (!q.exec(sql))
    {
        evt(QString::fromLatin1("vacuum_fail"), q.lastError().text().left(80));
        return false;
    }
    return true;
}

} // namespace hr
