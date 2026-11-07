#include "hrsync.h"
#include "hrdb.h"
#include "hrpaths.h"
#include "hrwebdav.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QMutexLocker>
#include <QRegularExpression>
#include <QVector>

namespace hr
{

/* ---- 内容 MD5（AndroidSync.md5 同口径：path+mtime+len 缓存） ---- */

static QMutex g_md5Mutex;
static QHash<QString, QString> g_md5Cache; /* path -> "mtime|len|hash" 拆三段存 */

QString syncMd5(const QString &path)
{
    QFileInfo fi(path);
    if (!fi.isFile()) return QString();
    const QString key = fi.absoluteFilePath();
    const QString sig = QString("%1|%2").arg(fi.lastModified().toMSecsSinceEpoch()).arg(fi.size());
    {
        QMutexLocker _(&g_md5Mutex);
        const QString c = g_md5Cache.value(key);
        if (!c.isEmpty() &&
            c.section('|', 0, 0) == QString::number(fi.lastModified().toMSecsSinceEpoch()) &&
            c.section('|', 1, 1) == QString::number(fi.size()))
            return c.section('|', 2, 2);
    }
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return QString();
    QCryptographicHash h(QCryptographicHash::Md5);
    char buf[64 * 1024];
    qint64 n;
    while ((n = f.read(buf, sizeof buf)) > 0) h.addData(buf, (int)n);
    const QString md5 = QString::fromLatin1(h.result().toHex());
    QMutexLocker _(&g_md5Mutex);
    g_md5Cache.insert(key, sig + "|" + md5);
    return md5;
}

void addBmTombstoneFor(const QString &bookPath, const QString &bmKey)
{
    const QString h = syncMd5(bookPath);
    Db::tombstoneAdd("bm", h.isEmpty() ? bookPath : h, bmKey);
}

/* ---- 同步主体（AndroidSync.sync 的 books 通道移植） ---- */

static QJsonObject collectProgressEntry(const QJsonObject &prev, const Db::ProgressRow &pr)
{
    QJsonObject e = prev;
    const int pc = pr.pageCount >= 1 ? pr.pageCount : 1;
    e.insert("p", (pr.page + 1.0) / pc);
    e.insert("pg", pr.page);
    e.insert("pgc", pr.pageCount);
    e.insert("t", (double)pr.updatedAt);
    return e;
}

SyncResult androidSync(const QString &baseUrlIn, const QString &user, const QString &pass,
                       const QString &remoteDirIn, const QString &policy)
{
    QElapsedTimer timer;
    timer.start();
    SyncResult r;
    const QString base = baseUrlIn.startsWith("http")
                             ? QString(baseUrlIn).trimmed().remove(QRegularExpression("/+$"))
                             : "http://" + baseUrlIn.trimmed();
    const QString remoteDir = remoteDirIn.trimmed().remove(QRegularExpression("^/+|/+$"));
    const QString rootDirUrl = base + "/" + (remoteDir.isEmpty() ? "HowRead" : remoteDir);
    const QString booksUrl = rootDirUrl + "/books";

    WebDavClient dav(base, user, pass);
    dav.mkcol(rootDirUrl);
    dav.mkcol(booksUrl);

    /* 本地数据收集 */
    QHash<QString, QString> nameByPath;   /* path -> 书名 */
    QHash<QString, QString> pathByName;   /* 书名 -> 本地绝对路径 */
    for (const Db::MetaRow &b : Db::listLibrary())
    {
        if (b.path.startsWith("remote://")) continue;
        if (!QFileInfo::exists(b.path)) continue;
        const QString nm = QFileInfo(b.path).fileName();
        nameByPath.insert(b.path, nm);
        pathByName.insert(nm, b.path);
    }
    QJsonObject localP; /* name -> progress entry */
    for (const Db::ProgressRow &pr : Db::allProgress())
    {
        const QString name = nameByPath.value(pr.path);
        if (name.isEmpty()) continue;
        localP.insert(name, collectProgressEntry(localP.value(name).toObject(), pr));
    }
    QJsonObject localB; /* key -> bookmark */
    for (const BookmarkRow &bm : Db::allBookmarks())
    {
        const QString key = QString::number(bm.createdAt);
        if (localB.contains(key)) continue; /* 同毫秒冲突：保留先到 */
        QJsonObject o;
        o.insert("path", bm.path);
        o.insert("text", bm.label);
        o.insert("note", bm.note);
        o.insert("p", (bm.page + 1.0) / 1.0);
        o.insert("pg", bm.page);
        o.insert("t", (double)bm.createdAt);
        localB.insert(key, o);
    }

    /* 远端逐书 info */
    QVector<DavEntry> entries;
    QString lerr;
    if (!dav.list(booksUrl, &entries, &lerr))
    {
        r.error = "network: books list fail " + lerr;
        r.durationMs = timer.elapsed();
        evt("android_sync_fail", r.error.left(120));
        return r;
    }
    QHash<QString, QJsonObject> remoteBooks; /* hash -> info */
    for (const DavEntry &e : entries)
    {
        if (e.isDir || !e.name.endsWith(".json")) continue;
        const QString hash = e.name.left(e.name.size() - 5);
        const QByteArray txt = dav.get(booksUrl + "/" + e.name);
        const QJsonDocument doc = QJsonDocument::fromJson(txt);
        if (doc.isObject()) remoteBooks.insert(hash, doc.object());
    }
    evt("android_sync_remote", QString("books=%1").arg(remoteBooks.size()));

    for (auto it = remoteBooks.constBegin(); it != remoteBooks.constEnd(); ++it)
    {
        const QString rHash = it.key();
        const QJsonObject info = it.value();
        const QString name = info.value("name").toString();
        if (name.isEmpty()) continue;
        const QString localFile = pathByName.value(name);
        const bool matched = !localFile.isEmpty() && rHash == syncMd5(localFile);
        const QJsonObject remoteDk = info.value("dk").toObject();

        /* 进度下行 */
        const QJsonObject rp = info.value("progress").toObject();
        if (!rp.isEmpty() && (matched || localFile.isEmpty()))
        {
            const long long delT = Db::tombstoneBookT(rHash);
            const bool suppress = localFile.isEmpty() && delT > 0 &&
                                  delT >= (long long)rp.value("t").toDouble();
            if (!suppress)
            {
                const QJsonObject l = localP.value(name).toObject();
                bool remoteWins = false;
                if (policy == "local") remoteWins = l.isEmpty();
                else if (policy == "server") remoteWins = true;
                else if (policy == "farther")
                {
                    /* P1 适配（E10）：更靠前的进度优先（页位比例大者胜，对齐 WIN farther） */
                    const int lpg = l.value("pg").toInt(-1);
                    const int rpg = rp.value("pg").toInt(-1);
                    const double lr =
                        lpg > 0 ? l.value("p").toInt(-1) / (double)lpg : -1.0;
                    const double rr =
                        rpg > 0 ? rp.value("p").toInt(-1) / (double)rpg : -1.0;
                    remoteWins = l.isEmpty() || rr > lr;
                }
                else remoteWins = l.isEmpty() ||
                                  (long long)rp.value("t").toDouble() > (long long)l.value("t").toDouble();
                if (remoteWins)
                {
                    localP.insert(name, rp);
                    if (!localFile.isEmpty())
                    {
                        const int pg = rp.value("pg").toInt(-1);
                        if (pg >= 0)
                        {
                            Db::saveProgressRaw(localFile, pg,
                                                rp.value("pgc").toInt() > 0 ? rp.value("pgc").toInt() : 1,
                                                0, (long long)rp.value("t").toDouble());
                            r.progressDown++;
                        }
                    }
                }
            }
        }

        /* 书签下行（union + dk/墓碑抑制） */
        const QJsonObject rbs = info.value("bookmarks").toObject();
        if (!rbs.isEmpty())
        {
            const QHash<QString, long long> localTk = Db::tombstoneBmMap(rHash);
            for (QJsonObject::const_iterator k = rbs.constBegin(); k != rbs.constEnd(); ++k)
            {
                if (localB.contains(k.key())) continue;
                const QJsonObject bm = k.value().toObject();
                const long long bt = (long long)bm.value("t").toDouble();
                const auto tk = localTk.constFind(k.key());
                if (tk != localTk.constEnd() && tk.value() >= bt) continue;
                if (remoteDk.contains(k.key()) && (long long)remoteDk.value(k.key()).toDouble() >= bt)
                    continue;
                const long long createdAt = k.key().toLongLong();
                if (createdAt == 0) continue;
                QString localPath = bm.value("path").toString();
                QString byName = pathByName.value(QFileInfo(localPath).fileName());
                if (byName.isEmpty()) byName = pathByName.value(name);
                if (byName.isEmpty() && localPath.isEmpty()) continue;
                if (!byName.isEmpty()) localPath = byName;
                Db::addBookmarkRaw(localPath, bm.value("pg").toInt(-1), bm.value("text").toString(),
                                   bm.value("note").toString(), createdAt);
                r.bookmarksDown++;
                localB.insert(k.key(), bm);
            }
        }

        /* 发布（仅 hash 确认的书维护服务器副本） */
        if (matched && !localFile.isEmpty())
        {
            QJsonObject pub;
            pub.insert("name", name);
            pub.insert("hash", rHash);
            pub.insert("t", qMax((long long)info.value("t").toDouble(),
                                 (long long)localP.value(name).toObject().value("t").toDouble()));
            if (!localP.value(name).toObject().isEmpty())
                pub.insert("progress", localP.value(name).toObject());
            QJsonObject subset;
            for (QJsonObject::const_iterator k = localB.constBegin(); k != localB.constEnd(); ++k)
            {
                const QJsonObject bm = k.value().toObject();
                if (nameByPath.value(bm.value("path").toString()) == name)
                    subset.insert(k.key(), bm);
            }
            const QJsonObject rbm = info.value("bookmarks").toObject();
            pub.insert("bookmarks", !subset.isEmpty() || rbm.isEmpty() ? subset : rbm);
            QJsonObject dkM = remoteDk;
            const QHash<QString, long long> tk = Db::tombstoneBmMap(rHash);
            for (auto t = tk.constBegin(); t != tk.constEnd(); ++t)
                dkM.insert(t.key(), (double)t.value());
            const long long bookDelT = Db::tombstoneBookT(rHash);
            if (bookDelT > 0) dkM.insert("bookDelT", (double)bookDelT);
            if (!dkM.isEmpty()) pub.insert("dk", dkM);
            if (dav.put(booksUrl + "/" + rHash + ".json",
                        QJsonDocument(pub).toJson(QJsonDocument::Compact), "application/json"))
            {
                r.booksSynced++;
                r.progressUp++;
            }
        }
    }

    /* 上传本机有而远端没有的书 */
    for (auto it = pathByName.constBegin(); it != pathByName.constEnd(); ++it)
    {
        const QString &name = it.key();
        const QString &f = it.value();
        const QString h = syncMd5(f);
        if (h.isEmpty() || remoteBooks.contains(h)) continue;
        const QJsonObject entry = localP.value(name).toObject();
        bool hasBm = false;
        for (QJsonObject::const_iterator k = localB.constBegin(); k != localB.constEnd(); ++k)
        {
            const QString bp = k.value().toObject().value("path").toString();
            if (QFileInfo(bp).fileName() == name || bp.endsWith("/" + name) ||
                bp.endsWith("\\" + name))
            {
                hasBm = true;
                break;
            }
        }
        if (entry.isEmpty() && !hasBm) continue; /* 从未打开过的书不上传（安卓同款） */
        QJsonObject pub;
        pub.insert("name", name);
        pub.insert("hash", h);
        pub.insert("t", entry.value("t").toDouble());
        if (!entry.isEmpty()) pub.insert("progress", entry);
        QJsonObject subset;
        for (QJsonObject::const_iterator k = localB.constBegin(); k != localB.constEnd(); ++k)
        {
            const QJsonObject bm = k.value().toObject();
            if (nameByPath.value(bm.value("path").toString()) == name)
                subset.insert(k.key(), bm);
        }
        pub.insert("bookmarks", subset);
        if (dav.put(booksUrl + "/" + h + ".json",
                    QJsonDocument(pub).toJson(QJsonDocument::Compact), "application/json"))
            r.booksSynced++;
    }

    /* P2 适配（E5）：global 整文件通道（对齐 WIN AndroidSync：信封 {at,data}+
     * dataDir/sync-base 基线；本地新→推、远端新→采、双新本地胜；
     * reader.json 双向；app-CSS.json/app-State.json 只拉透传（mtime 门控导入器消费）；
     * ui.json/lib.properties/sync.json 透传不读——QT 无对应文件，共存安全） */
    {
        const QString gUrl = rootDirUrl + QString::fromLatin1("/global");
        dav.mkcol(gUrl);
        QDir().mkpath(dataDir() + QString::fromLatin1("/sync-base"));
        auto baseOf = [](const QString &name) -> long long
        {
            QFile bf(dataDir() + QString::fromLatin1("/sync-base/") + name +
                     QString::fromLatin1(".at"));
            if (!bf.open(QIODevice::ReadOnly)) return 0;
            return QString::fromUtf8(bf.readAll().trimmed()).toLongLong();
        };
        auto setBase = [](const QString &name, long long v)
        {
            QFile bf(dataDir() + QString::fromLatin1("/sync-base/") + name +
                     QString::fromLatin1(".at"));
            if (bf.open(QIODevice::WriteOnly | QIODevice::Truncate))
                bf.write(QString::number(v).toUtf8());
        };
        /* reader.json 双向 */
        {
            const QString f = dataDir() + QString::fromLatin1("/reader.json");
            const QString rem = gUrl + QString::fromLatin1("/HowRead-desktop-reader.json");
            const long long base = baseOf(QString::fromLatin1("reader.json"));
            const long long lm = QFileInfo(f).exists()
                                     ? QFileInfo(f).lastModified().toMSecsSinceEpoch()
                                     : 0;
            long long at = 0;
            QString rdata;
            const QByteArray env = dav.get(rem);
            if (!env.isEmpty())
            {
                const QJsonObject o =
                    QJsonDocument::fromJson(env).object();
                at = (long long)o.value(QString::fromLatin1("at")).toDouble();
                rdata = o.value(QString::fromLatin1("data")).toString();
            }
            const bool haveRemote = !rdata.isEmpty();
            if (base == 0)
            {
                if (haveRemote)
                {
                    QFile rf(f);
                    if (rf.open(QIODevice::WriteOnly | QIODevice::Truncate))
                        rf.write(rdata.toUtf8());
                    evt(QString::fromLatin1("global_adopt"),
                        QString::fromLatin1("reader.json at=%1").arg(at));
                }
                setBase(QString::fromLatin1("reader.json"),
                        haveRemote ? at : lm);
            }
            else if (lm > base && lm >= at)
            {
                QJsonObject envO;
                envO.insert(QString::fromLatin1("at"), (double)lm);
                QFile rf(f);
                QString local;
                if (rf.open(QIODevice::ReadOnly))
                    local = QString::fromUtf8(rf.readAll());
                envO.insert(QString::fromLatin1("data"), local);
                dav.put(rem, QJsonDocument(envO).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
                setBase(QString::fromLatin1("reader.json"), lm);
                evt(QString::fromLatin1("global_push"),
                    QString::fromLatin1("reader.json at=%1").arg(lm));
            }
            else if (at > base && at > lm && haveRemote)
            {
                QFile rf(f);
                if (rf.open(QIODevice::WriteOnly | QIODevice::Truncate))
                    rf.write(rdata.toUtf8());
                setBase(QString::fromLatin1("reader.json"), at);
                evt(QString::fromLatin1("global_adopt"),
                    QString::fromLatin1("reader.json at=%1").arg(at));
            }
        }
        /* app-CSS.json / app-State.json 只拉透传 */
        for (const char *nm : {"app-CSS.json", "app-State.json"})
        {
            const QByteArray ba = dav.get(gUrl + "/" + QString::fromUtf8(nm));
            if (!ba.isEmpty())
            {
                QFile lf(dataDir() + "/" + QString::fromUtf8(nm));
                if (lf.open(QIODevice::WriteOnly | QIODevice::Truncate))
                    lf.write(ba);
            }
        }
        evt(QString::fromLatin1("global_sync"), QString::fromLatin1("done"));
    }

    r.ok = true;
    r.durationMs = timer.elapsed();
    evt("android_sync_done", QString("pDown=%1 bDown=%2 synced=%3 ms=%4")
                                 .arg(r.progressDown).arg(r.bookmarksDown)
                                 .arg(r.booksSynced).arg(r.durationMs));
    return r;
}

} // namespace hr
