#include "hrsync.h"
#include "hrdb.h"
#include "hrmerge.h"
#include "hrai.h"
#include "hrpaths.h"
#include "hrwebdav.h"
#include "hrstats.h"
#include "readerjson.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QElapsedTimer>
#include <QFile>
#include <QFileInfo>
#include <QSettings>
#include <QHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QMutexLocker>
#include <QRegularExpression>
#include <QSet>
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

/* ---- v1.2.0 全局文件小工具（读/写/三态 max 合并） ---- */

static QJsonObject readJsonFile(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return QJsonObject();
    const QJsonDocument d = QJsonDocument::fromJson(f.readAll());
    return d.isObject() ? d.object() : QJsonObject();
}

static void writeJsonFile(const QString &path, const QJsonObject &o)
{
    QFile f(path);
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        f.write(QJsonDocument(o).toJson(QJsonDocument::Compact));
}

/* 安卓 mergeStatesMaxWins 移植（WebDavSyncer:977）：逐键 max；
 * 基线在案时带删除语义（单边改胜/双边改本地胜/键删除按三方）。 */
static QJsonObject statesMaxWins(const QJsonObject &local, const QJsonObject &remote,
                                 const QJsonObject &base)
{
    const bool hasBase = !base.isEmpty();
    if (!hasBase)
    {
        QJsonObject out = local;
        for (auto it = remote.constBegin(); it != remote.constEnd(); ++it)
        {
            const int rv = it.value().toInt(-1);
            if (rv < 0) continue;
            const int lv = out.value(it.key()).toInt(-1);
            if (lv < 0 || rv > lv) out.insert(it.key(), rv);
        }
        return out;
    }
    QSet<QString> keys;
    for (auto it = local.constBegin(); it != local.constEnd(); ++it) keys.insert(it.key());
    for (auto it = remote.constBegin(); it != remote.constEnd(); ++it) keys.insert(it.key());
    for (auto it = base.constBegin(); it != base.constEnd(); ++it) keys.insert(it.key());
    QJsonObject out;
    for (const QString &k : keys)
    {
        const bool lb = local.contains(k), rb = remote.contains(k), bb = base.contains(k);
        const int lv = local.value(k).toInt(-1), rv = remote.value(k).toInt(-1),
                  bv = base.value(k).toInt(-1);
        const bool lch = (lb != bb) || (lb && lv != bv);
        const bool rch = (rb != bb) || (rb && rv != bv);
        int keep = -1;
        if (!rch) keep = lb ? lv : -1;             /* 本地改/删胜 */
        else if (!lch) keep = rb ? rv : -1;        /* 采纳远端改/删 */
        else keep = lb ? lv : (rb ? rv : -1);      /* 双改：本地胜 */
        if (keep >= 0) out.insert(k, keep);
    }
    return out;
}

/* v1.2.0（G）：READING 覆盖读写（{文件名:1}；安卓 BookStateStore 同形态——
 * 已读/未读两态由进度派生，只有"在读"需要显式存储） */
QHash<QString, int> bookStateOverrides()
{
    QHash<QString, int> out;
    const QJsonObject o = readJsonFile(dataDir() + QString::fromLatin1("/book-states.json"));
    for (auto it = o.constBegin(); it != o.constEnd(); ++it)
        if (it.value().toInt() == 1) out.insert(it.key(), 1);
    return out;
}

void setBookStateReading(const QString &bookPath, bool reading)
{
    const QString fn = QFileInfo(bookPath).fileName();
    const QString f = dataDir() + QString::fromLatin1("/book-states.json");
    QJsonObject o = readJsonFile(f);
    if (reading) o.insert(fn, 1);
    else o.remove(fn);
    writeJsonFile(f, o);
    evt(QString::fromLatin1("bookstate_mark"),
        QString::fromLatin1("fn=%1 on=%2").arg(fn.left(80)).arg(reading ? 1 : 0));
}

/* ---- v1.3.0：全局文件 D/E/F' 合并与互转 ---- */

/* D：安卓 mergeAi 移植（顶层 key 空让位/双设服务器胜；active 服务器非空胜；
 * vendors 逐名三方：单边改胜/双边改本地胜） */
static QJsonObject aiMerge(const QJsonObject &local, const QJsonObject &remote,
                           const QJsonObject &base)
{
    const QString lk = local.value(QString::fromLatin1("apiKey")).toString();
    const QString rk = remote.value(QString::fromLatin1("apiKey")).toString();
    QJsonObject out;
    out.insert(QString::fromLatin1("apiKey"),
               lk.isEmpty() ? rk : (rk.isEmpty() ? lk : rk));
    out.insert(QString::fromLatin1("active"),
               remote.value(QString::fromLatin1("active")).toString(
                   local.value(QString::fromLatin1("active")).toString()));
    const QJsonObject lv = local.value(QString::fromLatin1("vendors")).toObject();
    const QJsonObject rv = remote.value(QString::fromLatin1("vendors")).toObject();
    const QJsonObject bv = base.value(QString::fromLatin1("vendors")).toObject();
    QSet<QString> names;
    for (auto it = lv.constBegin(); it != lv.constEnd(); ++it) names.insert(it.key());
    for (auto it = rv.constBegin(); it != rv.constEnd(); ++it) names.insert(it.key());
    for (auto it = bv.constBegin(); it != bv.constEnd(); ++it) names.insert(it.key());
    QJsonObject vendors;
    for (const QString &n : names)
    {
        const bool lb = lv.contains(n), rb = rv.contains(n), bb = bv.contains(n);
        const QJsonObject lo = lv.value(n).toObject(), ro = rv.value(n).toObject(),
                          bo = bv.value(n).toObject();
        const bool lch = (lb != bb) || (lb && !jEqVal(lo, bo));
        const bool rch = (rb != bb) || (rb && !jEqVal(ro, bo));
        if (!rch) { if (lb) vendors.insert(n, lo); }        /* 本地改/删胜 */
        else if (!lch) { if (rb) vendors.insert(n, ro); }   /* 采纳远端改/删 */
        else { if (lb) vendors.insert(n, lo); else if (rb) vendors.insert(n, ro); } /* 双改本地胜 */
    }
    out.insert(QString::fromLatin1("vendors"), vendors);
    return out;
}

/* D：合并结果 → qt-prefs ai/*（优先 active 指向的厂商，其次任一 openai 厂商） */
static void aiImportIntoPrefs(const QJsonObject &merged)
{
    const QJsonObject vendors = merged.value(QString::fromLatin1("vendors")).toObject();
    if (vendors.isEmpty()) return;
    QString name = merged.value(QString::fromLatin1("active")).toString();
    QJsonObject pick = name.isEmpty() ? QJsonObject() : vendors.value(name).toObject();
    if (pick.isEmpty())
    {
        for (auto it = vendors.constBegin(); it != vendors.constEnd(); ++it)
        {
            if (it.value().toObject().value(QString::fromLatin1("protocol")).toString() ==
                QLatin1String("openai"))
            {
                pick = it.value().toObject();
                name = it.key();
                break;
            }
        }
    }
    if (pick.isEmpty())
    {
        pick = vendors.begin().value().toObject();
        name = vendors.begin().key();
    }
    AiConfig c = AiConfig::load();
    if (!pick.value(QString::fromLatin1("baseUrl")).toString().isEmpty())
        c.base = pick.value(QString::fromLatin1("baseUrl")).toString();
    if (!pick.value(QString::fromLatin1("apiKey")).toString().isEmpty())
        c.key = pick.value(QString::fromLatin1("apiKey")).toString();
    if (!pick.value(QString::fromLatin1("model")).toString().isEmpty())
        c.model = pick.value(QString::fromLatin1("model")).toString();
    if (pick.contains(QString::fromLatin1("maxTokens")))
        c.maxTokens = pick.value(QString::fromLatin1("maxTokens")).toInt(c.maxTokens);
    if (pick.contains(QString::fromLatin1("thinking")))
        c.thinking = pick.value(QString::fromLatin1("thinking")).toBool(c.thinking);
    c.save();
    evt(QString::fromLatin1("ai_import"),
        QString::fromLatin1("name=%1 proto=%2")
            .arg(name.left(40),
                 pick.value(QString::fromLatin1("protocol")).toString(QString::fromLatin1("openai"))));
}

/* E：remote_source 表 → 五段中的 webdav/smb/sftp 三段（opds/folders 透传不动） */
static void nsBuildLocalSections(QJsonObject &root)
{
    QJsonArray webdav, smb, sftp;
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
    {
        if (s.protocol == QLatin1String("webdav"))
        {
            QString scheme = QString::fromLatin1("http");
            QString h = s.host;
            if (h.startsWith(QString::fromLatin1("https://")))
            {
                scheme = QString::fromLatin1("https");
                h = h.mid(8);
            }
            else if (h.startsWith(QString::fromLatin1("http://")))
            {
                h = h.mid(7);
            }
            while (h.endsWith('/')) h.chop(1);
            QJsonObject it;
            it.insert(QString::fromLatin1("url"),
                      QString::fromLatin1("%1://%2:%3")
                          .arg(scheme, h,
                               QString::number(s.port > 0 ? s.port : 8765)));
            it.insert(QString::fromLatin1("title"), s.name);
            it.insert(QString::fromLatin1("startDir"), s.path);
            it.insert(QString::fromLatin1("login"), s.user);
            it.insert(QString::fromLatin1("password"), s.password); /* 读出即解密，明文上行同口径 */
            it.insert(QString::fromLatin1("trustAll"), false);
            webdav.append(it);
        }
        else if (s.protocol == QLatin1String("smb") || s.protocol == QLatin1String("sftp"))
        {
            QJsonObject it;
            it.insert(QString::fromLatin1("id"), QString::number(s.id));
            it.insert(QString::fromLatin1("title"), s.name);
            it.insert(QString::fromLatin1("host"), s.host);
            it.insert(QString::fromLatin1("port"), s.port);
            it.insert(QString::fromLatin1("user"), s.user);
            it.insert(QString::fromLatin1("domain"), QString());
            it.insert(QString::fromLatin1("share"),
                      s.protocol == QLatin1String("smb") ? s.path : QString());
            it.insert(QString::fromLatin1("keyPath"), QString());
            it.insert(QString::fromLatin1("trustAll"), false);
            it.insert(QString::fromLatin1("startDir"),
                      s.protocol == QLatin1String("sftp") ? s.path : QString());
            it.insert(QString::fromLatin1("password"), s.password);
            if (s.protocol == QLatin1String("sftp"))
                it.insert(QString::fromLatin1("keyPass"), QString());
            (s.protocol == QLatin1String("smb") ? smb : sftp).append(it);
        }
        /* opds：Qt 行格式与安卓线格式未核实——不发布（透传） */
    }
    root.insert(QString::fromLatin1("webdav"), webdav);
    root.insert(QString::fromLatin1("smb"), smb);
    root.insert(QString::fromLatin1("sftp"), sftp);
}

/* E：连接身份（安卓 remoteConnKey 同构造：host|port|user|domain|share|keyPath） */
static QString nsConnKeyOf(const QJsonObject &o)
{
    auto f = [&o](const char *k) -> QString
    { return o.value(QLatin1String(k)).toString(); };
    return f("host") + QString::fromLatin1("|") + QString::number(o.value(QLatin1String("port")).toInt()) +
           QString::fromLatin1("|") + f("user") + QString::fromLatin1("|") + f("domain") +
           QString::fromLatin1("|") + f("share") + QString::fromLatin1("|") + f("keyPath");
}

/* E：webdav/smb/sftp 逐条目三方（安卓 mergeWebDavItems/mergeRemoteGroups 组级语义；
 * smb/sftp 以连接身份为键） */
static QJsonArray nsMergeItems(const QJsonArray &la, const QJsonArray &ra,
                               const QJsonArray &ba, bool byUrl)
{
    auto keyOf = [byUrl](const QJsonObject &o) -> QString
    {
        if (byUrl)
            return o.value(QString::fromLatin1("url")).toString().trimmed()
                .remove(QRegularExpression("/+$"));
        return nsConnKeyOf(o);
    };
    QHash<QString, QJsonObject> lm, rm, bm;
    for (const QJsonValue &v : la)
    {
        const QJsonObject o = v.toObject();
        if (!o.isEmpty()) lm.insert(keyOf(o), o);
    }
    for (const QJsonValue &v : ra)
    {
        const QJsonObject o = v.toObject();
        if (!o.isEmpty()) rm.insert(keyOf(o), o);
    }
    for (const QJsonValue &v : ba)
    {
        const QJsonObject o = v.toObject();
        if (!o.isEmpty()) bm.insert(keyOf(o), o);
    }
    QSet<QString> keys;
    for (auto it = lm.constBegin(); it != lm.constEnd(); ++it) keys.insert(it.key());
    for (auto it = rm.constBegin(); it != rm.constEnd(); ++it) keys.insert(it.key());
    for (auto it = bm.constBegin(); it != bm.constEnd(); ++it) keys.insert(it.key());
    QJsonArray out;
    for (const QString &k : keys)
    {
        const bool lh = lm.contains(k), rh = rm.contains(k), bh = bm.contains(k);
        const QJsonObject l = lm.value(k), r = rm.value(k), b = bm.value(k);
        QJsonObject keep;
        if (!lh)
        {
            /* 本地无：真远端新增恢复；本地删且远端未改 → 保持删除 */
            if (bh && rh && jEqVal(r, b)) keep = QJsonObject();
            else keep = r;
        }
        else if (!rh || jEqVal(r, l))
            keep = l; /* 删除未传播/已相等 */
        else if (!bh || jEqVal(r, b))
            keep = l; /* 无基线或远端未改：本地改胜 */
        else if (jEqVal(l, b))
            keep = r; /* 本地未改：采纳远端改 */
        else
            keep = jMergeItemFields(l, r, b); /* 双改：逐字段，本地胜平局 */
        if (!keep.isEmpty()) out.append(keep);
    }
    return out;
}

/* E：opds/folders 透传（Qt 不发布；本地未改则采纳远端改动） */
static QJsonValue nsPassthrough(const QJsonValue &lv, const QJsonValue &rv,
                                const QJsonValue &bv)
{
    const bool lch = (lv.isUndefined() != bv.isUndefined()) ||
                     (!lv.isUndefined() && !jEqVal(lv, bv));
    const bool rch = (rv.isUndefined() != bv.isUndefined()) ||
                     (!rv.isUndefined() && !jEqVal(rv, bv));
    if (!lch && rch) return rv;
    return lv;
}

/* E：合并结果 → remote_source 表（webdav 按 url、smb/sftp 按连接身份） */
static void nsImportIntoDb(const QJsonObject &merged, const QJsonObject &base)
{
    const QList<Db::RemoteSrc> cur = Db::listRemoteSrc();
    auto connKeyOfSrc = [](const Db::RemoteSrc &s) -> QString
    {
        return s.host + QString::fromLatin1("|") + QString::number(s.port) +
               QString::fromLatin1("|") + s.user + QString::fromLatin1("||") +
               (s.protocol == QLatin1String("smb") ? s.path : QString()) +
               QString::fromLatin1("|");
    };
    /* webdav：url → 行 */
    {
        QHash<QString, QJsonObject> bm;
        for (const QJsonValue &v : base.value(QString::fromLatin1("webdav")).toArray())
        {
            const QJsonObject o = v.toObject();
            const QString u = o.value(QString::fromLatin1("url")).toString().trimmed()
                                  .remove(QRegularExpression("/+$"));
            if (!u.isEmpty()) bm.insert(u, o);
        }
        QSet<QString> mergedUrls;
        for (const QJsonValue &v : merged.value(QString::fromLatin1("webdav")).toArray())
        {
            const QJsonObject it = v.toObject();
            const QString url = it.value(QString::fromLatin1("url")).toString().trimmed()
                                    .remove(QRegularExpression("/+$"));
            if (url.isEmpty()) continue;
            mergedUrls.insert(url);
            Db::RemoteSrc *hit = nullptr;
            for (const Db::RemoteSrc &s : cur)
            {
                if (s.protocol != QLatin1String("webdav")) continue;
                QString scheme = QString::fromLatin1("http");
                QString h = s.host;
                if (h.startsWith(QString::fromLatin1("https://")))
                {
                    scheme = QString::fromLatin1("https");
                    h = h.mid(8);
                }
                else if (h.startsWith(QString::fromLatin1("http://")))
                {
                    h = h.mid(7);
                }
                while (h.endsWith('/')) h.chop(1);
                const QString u = QString::fromLatin1("%1://%2:%3")
                                      .arg(scheme, h,
                                           QString::number(s.port > 0 ? s.port : 8765));
                if (u == url) { hit = const_cast<Db::RemoteSrc *>(&s); break; }
            }
            if (!hit)
            {
                if (bm.contains(url) && !mergedUrls.isEmpty() &&
                    jEqVal(bm.value(url), it) == false)
                {
                    /* base 有、内容也带回：仍视为恢复（安卓同款"真远端新增恢复"） */
                }
                Db::RemoteSrc n;
                n.protocol = QString::fromLatin1("webdav");
                const QString url1 = it.value(QString::fromLatin1("url")).toString();
                const bool isHttps = url1.startsWith(QString::fromLatin1("https://"));
                const QString rest =
                    isHttps ? url1.mid(8) : QString(url1).remove(QRegularExpression("^http://"));
                const QString hostPort = rest.section('/', 0, 0);
                n.host = isHttps
                             ? QString::fromLatin1("https://") + hostPort.section(':', 0, 0)
                             : hostPort.section(':', 0, 0);
                n.port = hostPort.section(':', 1).toInt();
                if (n.port <= 0) n.port = 8765;
                n.path = rest.section('/', 1);
                n.name = it.value(QString::fromLatin1("title")).toString(n.host);
                n.user = it.value(QString::fromLatin1("login")).toString();
                n.password = it.value(QString::fromLatin1("password")).toString();
                Db::addRemoteSrc(n);
                evt(QString::fromLatin1("net_import_add"),
                    QString::fromLatin1("url=%1").arg(url.left(60)));
            }
            else
            {
                bool dirty = false;
                const QString t = it.value(QString::fromLatin1("title")).toString();
                if (!t.isEmpty() && t != hit->name) { hit->name = t; dirty = true; }
                const QString sd = it.value(QString::fromLatin1("startDir")).toString();
                if (sd != hit->path) { hit->path = sd; dirty = true; }
                const QString u = it.value(QString::fromLatin1("login")).toString();
                if (!u.isEmpty() && u != hit->user) { hit->user = u; dirty = true; }
                const QString pw = it.value(QString::fromLatin1("password")).toString();
                if (!pw.isEmpty() && pw != hit->password) { hit->password = pw; dirty = true; }
                if (dirty)
                {
                    Db::updateRemoteSrc(*hit);
                    evt(QString::fromLatin1("net_import_upd"),
                        QString::fromLatin1("url=%1").arg(url.left(60)));
                }
            }
        }
        /* 删除：base 有而 merged 无 → 删本地行（本机未改时已随合并保持删） */
        for (auto it = bm.constBegin(); it != bm.constEnd(); ++it)
        {
            if (mergedUrls.contains(it.key())) continue;
            for (const Db::RemoteSrc &s : cur)
            {
                if (s.protocol != QLatin1String("webdav")) continue;
                QString scheme = QString::fromLatin1("http");
                QString h = s.host;
                if (h.startsWith(QString::fromLatin1("https://")))
                {
                    scheme = QString::fromLatin1("https");
                    h = h.mid(8);
                }
                else if (h.startsWith(QString::fromLatin1("http://")))
                {
                    h = h.mid(7);
                }
                while (h.endsWith('/')) h.chop(1);
                const QString u = QString::fromLatin1("%1://%2:%3")
                                      .arg(scheme, h,
                                           QString::number(s.port > 0 ? s.port : 8765));
                if (u == it.key())
                {
                    Db::deleteRemoteSrc(s.id);
                    evt(QString::fromLatin1("net_import_del"),
                        QString::fromLatin1("url=%1").arg(it.key().left(60)));
                    break;
                }
            }
        }
    }
    /* smb/sftp：连接身份 → 行 */
    for (const char *sec : {"smb", "sftp"})
    {
        const QString k = QString::fromUtf8(sec);
        const QJsonArray mm = merged.value(k).toArray();
        const QJsonArray bmArr = base.value(k).toArray();
        QSet<QString> mergedKeys;
        QHash<QString, QJsonObject> bm;
        for (const QJsonValue &v : bmArr)
        {
            const QJsonObject o = v.toObject();
            if (!o.isEmpty()) bm.insert(nsConnKeyOf(o), o);
        }
        for (const QJsonValue &v : mm)
        {
            const QJsonObject it = v.toObject();
            if (it.isEmpty()) continue;
            const QString ck = nsConnKeyOf(it);
            mergedKeys.insert(ck);
            Db::RemoteSrc *hit = nullptr;
            for (const Db::RemoteSrc &s : cur)
            {
                if (s.protocol != k) continue;
                if (connKeyOfSrc(s) == ck) { hit = const_cast<Db::RemoteSrc *>(&s); break; }
            }
            const bool isSmb = (k == QLatin1String("smb"));
            if (!hit)
            {
                Db::RemoteSrc n;
                n.protocol = k;
                n.name = it.value(QString::fromLatin1("title")).toString(
                    it.value(QString::fromLatin1("host")).toString());
                n.host = it.value(QString::fromLatin1("host")).toString();
                n.port = it.value(QString::fromLatin1("port")).toInt(isSmb ? 445 : 22);
                n.user = it.value(QString::fromLatin1("user")).toString();
                n.path = isSmb ? it.value(QString::fromLatin1("share")).toString()
                               : it.value(QString::fromLatin1("startDir")).toString();
                n.password = it.value(QString::fromLatin1("password")).toString();
                Db::addRemoteSrc(n);
                evt(QString::fromLatin1("net_import_add"),
                    QString::fromLatin1("%1 host=%2").arg(k, n.host.left(50)));
            }
            else
            {
                bool dirty = false;
                const QString t = it.value(QString::fromLatin1("title")).toString();
                if (!t.isEmpty() && t != hit->name) { hit->name = t; dirty = true; }
                const QString sd = isSmb ? it.value(QString::fromLatin1("share")).toString()
                                         : it.value(QString::fromLatin1("startDir")).toString();
                if (!sd.isEmpty() && sd != hit->path) { hit->path = sd; dirty = true; }
                const QString pw = it.value(QString::fromLatin1("password")).toString();
                if (!pw.isEmpty() && pw != hit->password) { hit->password = pw; dirty = true; }
                if (dirty)
                {
                    Db::updateRemoteSrc(*hit);
                    evt(QString::fromLatin1("net_import_upd"),
                        QString::fromLatin1("%1 host=%2").arg(k, hit->host.left(50)));
                }
            }
        }
        for (auto it = bm.constBegin(); it != bm.constEnd(); ++it)
        {
            if (mergedKeys.contains(it.key())) continue;
            for (const Db::RemoteSrc &s : cur)
            {
                if (s.protocol != k) continue;
                if (connKeyOfSrc(s) == it.key())
                {
                    Db::deleteRemoteSrc(s.id);
                    evt(QString::fromLatin1("net_import_del"),
                        QString::fromLatin1("%1 host=%2").arg(k, s.host.left(50)));
                    break;
                }
            }
        }
    }
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
    /* v1.3.0（F'）：books 通道本就算好的全库内容 MD5 → 注解侧车首轮发现远端复用 */
    QHash<QString, QString> annMd5ByHash;
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
        /* v1.2.0（B2/B3）：安卓键面补齐——收藏标 / AI 笔记（Qt 消费展示，生产端在安卓） */
        if (bm.isF) o.insert("isF", true);
        if (bm.isAiNote)
        {
            o.insert("isAiNote", true);
            if (!bm.aiAnswer.isEmpty()) o.insert("aiAnswer", bm.aiAnswer);
        }
        /* v1.1.0（安卓 1.3.21 互通）：章节锚 sec/sr/st + 捕获时全书页数 pgs。
         * p=全书比例（此前误写 page+1，安卓端会当比例错落）；pgs 未知时不发布 p。
         * v1.2.0（B1）：pgs 与 p 同门控（pgs>0 即发布，不再只随 sec>=0 走——
         * 安卓恒发布，无锚书签也应有绝对页锚） */
        o.insert("pg", bm.page);
        o.insert("t", (double)bm.createdAt);
        if (bm.pgs > 0)
        {
            o.insert("p", (bm.page + 1.0) / bm.pgs);
            o.insert("pgs", bm.pgs);
        }
        if (bm.sec >= 0)
        {
            o.insert("sec", bm.sec);
            o.insert("sr", bm.sr);
            if (!bm.st.isEmpty()) o.insert("st", bm.st);
        }
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
        if (matched) annMd5ByHash.insert(rHash, localFile);
        const QJsonObject remoteDk = info.value("dk").toObject();

        /* v1.1.0（安卓 1.3.15 同语义）：本机已删（book 墓碑在案）→ 清服务器副本，
         * 孤儿记录不再每轮被重复消费/回灌 */
        {
            const long long bookDelT0 = Db::tombstoneBookT(rHash);
            if (bookDelT0 > 0 && !matched &&
                (long long)info.value("t").toDouble() <= bookDelT0)
            {
                if (dav.del(booksUrl + "/" + rHash + ".json"))
                {
                    r.booksSynced++;
                    evt("sync_server_clean", rHash);
                }
                continue;
            }
        }

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
                    /* P1 适配（E10）+ v1.1.0 修复：读得更远优先——p 本就是全书比例
                     *（0..1），此前 toInt 截断恒为 0/1 导致远端永远不胜 */
                    const double lr = l.isEmpty() ? -1.0 : l.value("p").toDouble(-1.0);
                    const double rr = rp.value("p").toDouble(-1.0);
                    remoteWins = l.isEmpty() || rr > lr + 1e-9;
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
                Db::addBookmarkRaw(localPath, bm.value("pg").toInt(-1),
                                   bm.value("text").toString(),
                                   bm.value("note").toString(), createdAt,
                                   bm.value("sec").toInt(-1), bm.value("sr").toDouble(),
                                   bm.value("st").toString(), bm.value("pgs").toInt(),
                                   bm.value("isF").toBool(false) ? 1 : 0,
                                   bm.value("isAiNote").toBool(false) ? 1 : 0,
                                   bm.value("aiAnswer").toString());
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
        annMd5ByHash.insert(h, f);
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
        /* v1.4.0（S）：app-State.json 升双向——安卓 syncThreeWayFile(state) 同语义
         * （WebDavSyncer:442/502/1194-1330）：全量对象进出，基线=sync-base/app-State.json
         * 上次合并结果快照；keepLocal=STATE_DEVICE_FIELDS(30 设备绑定字段)+SYNC_CONFIG_FIELDS
         * (4 同步接线字段)+APPSP_IDENTITY_FIELDS(5 身份字段)（安卓清单照抄）；上行剥 device
         * 字段（安卓 mergedCmp=withoutFields 同款）。Qt 发布映射子集：alwaysTwoPages↔
         * reader/twoPage、ttsSpeed↔tts/rate（线性 (speed-1)*10，往返幂等）、appTheme↔
         * ui/dark（0 亮 1 暗）；下行改动即时写 qt-prefs（下次启动生效；alwaysTwoPages
         * 另有导入器 mtime 门控兜底）。 */
        {
            static const char *stDev[] = {"displayPath", "installationDate",
                "statusBarTextSizeAdv", "statusBarTextSizeEasy", "progressLineHeight",
                "tapzoneSize", "coverBigSize", "isReverseKeys", "pageQuality", "isCutRTL",
                "isRTLByDefault", "selectingByLetters", "fileToDelete", "myAutoCompleteDb",
                "bgImageDayPath", "bgImageNightPath", "proxyEnable", "proxyServer",
                "proxyPort", "proxyUser", "proxyPassword", "selectedText", "searchQuery",
                "isAutoScroll", "hashCode", "webdavLastSyncTime", "webdavLastSyncInfo",
                "aiConfigs", "aiConfigName", "aiBaseUrl", "aiModel", "aiProtocol",
                "aiMaxTokens", "aiThinking", "allWebDavLinks", "allSmbLinks", "allSftpLinks"};
            static const char *stCfg[] = {"webdavSyncEnabled", "webdavSyncServer",
                "webdavSyncRemoteDir", "webdavSyncIntervalMin"};
            static const char *stId[] = {"rootPath1", "currentProfile", "syncRootID",
                "lastBookPath", "lastBookPage"};
            QSet<QString> keep, devSet;
            for (const char *s : stDev)
            {
                keep.insert(QLatin1String(s));
                devSet.insert(QLatin1String(s));
            }
            for (const char *s : stCfg) keep.insert(QLatin1String(s));
            for (const char *s : stId) keep.insert(QLatin1String(s));
            auto stripDev = [&devSet](const QJsonObject &o)
            {
                QJsonObject c = o;
                for (const QString &k : devSet) c.remove(k);
                return c;
            };
            const QString stF = dataDir() + QString::fromLatin1("/app-State.json");
            const QString stBaseP = dataDir() + QString::fromLatin1("/sync-base/app-State.json");
            const QString stUrl = gUrl + QString::fromLatin1("/app-State.json");
            QJsonObject localO = readJsonFile(stF);
            {
                QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"),
                             QSettings::IniFormat);
                localO.insert(QLatin1String("alwaysTwoPages"),
                              st.value(QLatin1String("reader/twoPage"), false).toBool());
                const int rate =
                    qBound(-10, st.value(QLatin1String("tts/rate"), 0).toInt(), 10);
                localO.insert(QLatin1String("ttsSpeed"), 1.0 + rate / 10.0);
                localO.insert(QLatin1String("appTheme"),
                              st.value(QLatin1String("ui/dark"), false).toBool() ? 1 : 0);
            }
            const QJsonObject baseO = readJsonFile(stBaseP);
            const QByteArray stRem = dav.get(stUrl);
            QJsonObject stRemote;
            if (!stRem.isEmpty())
            {
                const QJsonDocument sd = QJsonDocument::fromJson(stRem);
                if (sd.isObject()) stRemote = sd.object();
            }
            if (stRemote.isEmpty() && !QFileInfo::exists(stF))
            {
                evt(QString::fromLatin1("state_sync"), QString::fromLatin1("idle=1"));
            }
            else if (stRemote.isEmpty())
            {
                /* 服务器文件消失（404/空）：以本地回种（安卓同款，绝不视为全删） */
                dav.put(stUrl,
                        QJsonDocument(stripDev(localO)).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
                writeJsonFile(stBaseP, localO);
                evt(QString::fromLatin1("state_sync"), QString::fromLatin1("seed=1"));
            }
            else
            {
                QJsonObject merged;
                if (baseO.isEmpty())
                {
                    /* 首装：远端为准全量采纳（安卓 firstSyncMerge 服务器胜同款）。
                     * 不用 jFirstSyncMerge(local,remote,keep)：Qt 无本地文件时
                     * keepLocal 键（同步接线/身份字段）本地缺失会被整键丢弃，
                     * 上行将清掉其它设备的 webdavSync 与 rootPath1——必须原样保留
                     * 服务器副本；device/cfg 字段本地有则覆盖（保本地语义）；
                     * Qt 映射键仅远端缺失时补发（双设服务器胜）。 */
                    merged = stRemote;
                    for (const QString &k : keep)
                        if (localO.contains(k))
                            merged.insert(k, localO.value(k));
                    static const char *stMapped[] = {"alwaysTwoPages", "ttsSpeed", "appTheme"};
                    for (const char *k : stMapped)
                        if (!merged.contains(QLatin1String(k)))
                            merged.insert(QLatin1String(k), localO.value(QLatin1String(k)));
                    evt(QString::fromLatin1("state_sync"), QString::fromLatin1("adopt=1"));
                }
                else
                {
                    merged = hr::jMerge3(baseO, localO, stRemote, keep);
                }
                /* 下行映射键 → qt-prefs（有变才写；首次采纳远端口径） */
                {
                    QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"),
                                 QSettings::IniFormat);
                    const bool tp = merged.value(QLatin1String("alwaysTwoPages")).toBool(false);
                    const QVariant curTp = st.value(QLatin1String("reader/twoPage"));
                    if (!curTp.isValid() || curTp.toBool() != tp)
                    {
                        st.setValue(QLatin1String("reader/twoPage"), tp);
                        evt(QString::fromLatin1("state_import"),
                            QString::fromLatin1("twoPage=%1").arg(tp ? 1 : 0));
                    }
                    const double sp = merged.value(QLatin1String("ttsSpeed")).toDouble(1.0);
                    const int rate = qBound(-10, qRound((sp - 1.0) * 10.0), 10);
                    const QVariant curRate = st.value(QLatin1String("tts/rate"));
                    if (!curRate.isValid() || curRate.toInt() != rate)
                    {
                        st.setValue(QLatin1String("tts/rate"), rate);
                        evt(QString::fromLatin1("state_import"),
                            QString::fromLatin1("ttsRate=%1").arg(rate));
                    }
                    const bool dark = merged.value(QLatin1String("appTheme")).toInt(0) == 1;
                    const QVariant curDark = st.value(QLatin1String("ui/dark"));
                    if (!curDark.isValid() || curDark.toBool() != dark)
                    {
                        st.setValue(QLatin1String("ui/dark"), dark);
                        evt(QString::fromLatin1("state_import"),
                            QString::fromLatin1("dark=%1").arg(dark ? 1 : 0));
                    }
                }
                if (merged != localO)
                    writeJsonFile(stF, merged);
                const QJsonObject up = stripDev(merged);
                /* 上行比较同为剥离口径（安卓 mergedCmp vs remoteCmp 同款），
                 * 否则服务器带 device 字段时每轮都会多 PUT */
                if (!up.isEmpty() && up != stripDev(stRemote))
                    dav.put(stUrl, QJsonDocument(up).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                if (merged != baseO)
                    writeJsonFile(stBaseP, merged);
            }
        }
        /* v1.2.0（G）：app-BookStates.json 三态收口——安卓仅存 READING 覆盖
         * （{文件名:1}；已读/未读走 progress 已随 books 通道互通）。
         * merge=mergeStatesMaxWins 同款（基线=上次合并结果快照）；
         * 下行喂书架过滤（bookStateOverrides），上行"标记在读"经 setBookStateReading。 */
        {
            const QString bsF = dataDir() + QString::fromLatin1("/book-states.json");
            const QString bsBasePath = dataDir() + QString::fromLatin1("/sync-base/book-states.json");
            const QJsonObject bsLocal = readJsonFile(bsF);
            const QJsonObject bsBase = readJsonFile(bsBasePath);
            const QByteArray bsRem = dav.get(gUrl + QString::fromLatin1("/app-BookStates.json"));
            QJsonObject bsRemote;
            if (!bsRem.isEmpty())
            {
                const QJsonDocument bd = QJsonDocument::fromJson(bsRem);
                if (bd.isObject()) bsRemote = bd.object();
            }
            const QJsonObject bsMerged = statesMaxWins(bsLocal, bsRemote, bsBase);
            if (bsMerged != bsLocal)
            {
                writeJsonFile(bsF, bsMerged);
                evt(QString::fromLatin1("bookstates_down"),
                    QString::fromLatin1("keys=%1").arg(bsMerged.size()));
            }
            if (!bsMerged.isEmpty() && bsMerged != bsRemote)
                dav.put(gUrl + QString::fromLatin1("/app-BookStates.json"),
                        QJsonDocument(bsMerged).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
            if (bsMerged != bsBase)
                writeJsonFile(bsBasePath, bsMerged);
        }
        /* v1.2.0（C）：app-Stats.json 上云——安卓 mergeStats 同语义（标量 max +
         * 桶逐键 max + dayKey 同日才并；桶单位毫秒，monthly 留 13/daily 留 40），
         * 合并结果回灌本机按日行（统计页跨端收敛）。 */
        {
            const QByteArray stRem = dav.get(gUrl + QString::fromLatin1("/app-Stats.json"));
            QJsonObject stRemote;
            if (!stRem.isEmpty())
            {
                const QJsonDocument sd = QJsonDocument::fromJson(stRem);
                if (sd.isObject()) stRemote = sd.object();
            }
            const QJsonObject stMerged = Stats::mergeSix(Stats::exportSix(), stRemote);
            Stats::importSix(stMerged);
            const bool stUp = !stMerged.isEmpty() && stMerged != stRemote;
            if (stUp)
                dav.put(gUrl + QString::fromLatin1("/app-Stats.json"),
                        QJsonDocument(stMerged).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
            evt(QString::fromLatin1("stats_sync"),
                QString::fromLatin1("up=%1 timeMs=%2 pages=%3")
                    .arg(stUp ? 1 : 0)
                    .arg((long long)stMerged.value(QString::fromLatin1("readTimeMs")).toDouble())
                    .arg((long long)stMerged.value(QString::fromLatin1("readPages")).toDouble()));
        }
        /* v1.3.0（A1）：app-CSS.json 升级为字段级双向参与——安卓 syncThreeWayFile(css)
         * 同语义（基线=sync-base/app-CSS.json 上次合并结果快照）。Qt 只写 9 个映射键
         * （reader.json 真源镜像），其余键全量透传+基线追踪；首装采纳走
         * firstSyncMerge（双设服务器胜，保护安卓个性化不被桌面默认覆盖）。 */
        {
            const QString cssF = dataDir() + QString::fromLatin1("/app-CSS.json");
            const QString cssBaseP = dataDir() + QString::fromLatin1("/sync-base/app-CSS.json");
            const QString cssUrl = gUrl + QString::fromLatin1("/app-CSS.json");
            const QByteArray cssRem = dav.get(cssUrl);
            if (cssRem.trimmed().isEmpty() && !QFileInfo::exists(cssF))
            {
                /* 服务器无文件且本机无副本：不种子（安卓设备先种；Qt 首装采纳兜底） */
                evt(QString::fromLatin1("css_sync"), QString::fromLatin1("skip=absent"));
            }
            else if (!QFileInfo::exists(cssF))
            {
                /* 首装采纳：远端就绪 → 落盘（句柄先出作用域再回读，Windows 同句柄
                 * 未关时二次打开会读空）+ 立即映射进 reader.json；基线=采纳件 */
                {
                    QFile rf(cssF);
                    if (rf.open(QIODevice::WriteOnly | QIODevice::Truncate))
                        rf.write(cssRem);
                }
                const QJsonObject o = readJsonFile(cssF);
                if (!o.isEmpty())
                {
                    applyAndroidCssKeys(o);
                    evt(QString::fromLatin1("css_sync"), QString::fromLatin1("adopt"));
                }
                writeJsonFile(cssBaseP, o);
            }
            else
            {
                /* 稳态：镜像 Qt 真源 → merge3 → 落盘/PUT/基线 → 映射回 reader.json */
                mirrorReaderToAndroidCss();
                const QJsonObject localO = readJsonFile(cssF);
                const QJsonObject baseO = readJsonFile(cssBaseP);
                QJsonObject remoteO;
                if (!cssRem.trimmed().isEmpty())
                {
                    const QJsonDocument d = QJsonDocument::fromJson(cssRem);
                    if (d.isObject()) remoteO = d.object();
                }
                QJsonObject merged;
                if (remoteO.isEmpty())
                {
                    merged = localO; /* 服务器副本丢失：从本地补种（安卓同规则） */
                    evt(QString::fromLatin1("css_sync"), QString::fromLatin1("reseed"));
                }
                else if (baseO.isEmpty())
                {
                    merged = jFirstSyncMerge(localO, remoteO);
                }
                else
                {
                    merged = jMerge3(baseO, localO, remoteO);
                }
                if (merged != localO)
                {
                    writeJsonFile(cssF, merged);
                    applyAndroidCssKeys(merged); /* 远端改动的映射键即时生效 */
                }
                if (!merged.isEmpty() && merged != remoteO)
                    dav.put(cssUrl, QJsonDocument(merged).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                if (merged != baseO)
                    writeJsonFile(cssBaseP, merged);
                evt(QString::fromLatin1("css_sync"), QString::fromLatin1("ok"));
            }
        }
        /* v1.3.0（D）：app-AI.json 上云——安卓 mergeAi 同语义。Qt 单协议 OpenAI 映射为
         * vendors["Desktop-OpenAI"]，下行回填 qt-prefs ai/*（凭据明文与安卓/鸿蒙同口径，
         * 用户 2026-11-18 已确认）。 */
        {
            const QString aiF = dataDir() + QString::fromLatin1("/app-AI.json");
            const QString aiBaseP = dataDir() + QString::fromLatin1("/sync-base/app-AI.json");
            const QString aiUrl = gUrl + QString::fromLatin1("/app-AI.json");
            const QByteArray aiRem = dav.get(aiUrl);
            QJsonObject aiRemote;
            if (!aiRem.trimmed().isEmpty())
            {
                const QJsonDocument d = QJsonDocument::fromJson(aiRem);
                if (d.isObject()) aiRemote = d.object();
            }
            if (!aiRemote.isEmpty() || QFileInfo::exists(aiF))
            {
                QJsonObject aiLocal = readJsonFile(aiF);
                {
                    const AiConfig c = AiConfig::load();
                    QJsonObject v;
                    v.insert(QString::fromLatin1("name"), QString::fromLatin1("Desktop-OpenAI"));
                    v.insert(QString::fromLatin1("protocol"), QString::fromLatin1("openai"));
                    v.insert(QString::fromLatin1("baseUrl"), c.base);
                    v.insert(QString::fromLatin1("apiKey"), c.key);
                    v.insert(QString::fromLatin1("model"), c.model);
                    v.insert(QString::fromLatin1("maxTokens"), c.maxTokens);
                    v.insert(QString::fromLatin1("thinking"), c.thinking);
                    QJsonObject vendors = aiLocal.value(QString::fromLatin1("vendors")).toObject();
                    vendors.insert(QString::fromLatin1("Desktop-OpenAI"), v);
                    aiLocal.insert(QString::fromLatin1("vendors"), vendors);
                    if (aiLocal.value(QString::fromLatin1("active")).toString().isEmpty())
                        aiLocal.insert(QString::fromLatin1("active"),
                                       QString::fromLatin1("Desktop-OpenAI"));
                    if (aiLocal.value(QString::fromLatin1("apiKey")).toString().isEmpty())
                        aiLocal.insert(QString::fromLatin1("apiKey"), c.key);
                }
                const QJsonObject aiBaseO = readJsonFile(aiBaseP);
                const QJsonObject merged = aiMerge(aiLocal, aiRemote, aiBaseO);
                if (merged != aiLocal)
                {
                    writeJsonFile(aiF, merged);
                    aiImportIntoPrefs(merged); /* 下行回填（含首轮基线缺席时的采纳） */
                }
                if (!merged.isEmpty() && merged != aiRemote)
                    dav.put(aiUrl, QJsonDocument(merged).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                if (merged != aiBaseO)
                    writeJsonFile(aiBaseP, merged);
                evt(QString::fromLatin1("ai_sync"),
                    QString::fromLatin1("vendors=%1")
                        .arg(merged.value(QString::fromLatin1("vendors")).toObject().size()));
            }
        }
        /* v1.3.0（E）：app-NetworkSources.json 上云——安卓五段 schema；Qt 发布
         * webdav/smb/sftp 三段（密码明文同口径），opds/folders 透传不发布；
         * 逐条目三方合并（安卓 mergeWebDavItems/mergeRemoteGroups 同语义），
         * 下行回填 remote_source 表（含删除传播）。 */
        {
            const QString nsF = dataDir() + QString::fromLatin1("/app-NetworkSources.json");
            const QString nsBaseP = dataDir() + QString::fromLatin1("/sync-base/app-NetworkSources.json");
            const QString nsUrl = gUrl + QString::fromLatin1("/app-NetworkSources.json");
            const QByteArray nsRem = dav.get(nsUrl);
            QJsonObject nsRemote;
            if (!nsRem.trimmed().isEmpty())
            {
                const QJsonDocument d = QJsonDocument::fromJson(nsRem);
                if (d.isObject()) nsRemote = d.object();
            }
            if (!nsRemote.isEmpty() || QFileInfo::exists(nsF))
            {
                QJsonObject nsLocal = readJsonFile(nsF);
                nsBuildLocalSections(nsLocal);
                const QJsonObject nsBaseO = readJsonFile(nsBaseP);
                QJsonObject merged;
                merged.insert(QString::fromLatin1("webdav"),
                              nsMergeItems(nsLocal.value(QString::fromLatin1("webdav")).toArray(),
                                           nsRemote.value(QString::fromLatin1("webdav")).toArray(),
                                           nsBaseO.value(QString::fromLatin1("webdav")).toArray(), true));
                merged.insert(QString::fromLatin1("smb"),
                              nsMergeItems(nsLocal.value(QString::fromLatin1("smb")).toArray(),
                                           nsRemote.value(QString::fromLatin1("smb")).toArray(),
                                           nsBaseO.value(QString::fromLatin1("smb")).toArray(), false));
                merged.insert(QString::fromLatin1("sftp"),
                              nsMergeItems(nsLocal.value(QString::fromLatin1("sftp")).toArray(),
                                           nsRemote.value(QString::fromLatin1("sftp")).toArray(),
                                           nsBaseO.value(QString::fromLatin1("sftp")).toArray(), false));
                for (const char *sec : {"opds", "folders"})
                {
                    const QString k = QString::fromUtf8(sec);
                    merged.insert(k, nsPassthrough(nsLocal.value(k), nsRemote.value(k),
                                                   nsBaseO.value(k)));
                }
                if (merged != nsLocal)
                {
                    writeJsonFile(nsF, merged);
                    nsImportIntoDb(merged, nsBaseO);
                }
                if (!merged.isEmpty() && merged != nsRemote)
                    dav.put(nsUrl, QJsonDocument(merged).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                if (merged != nsBaseO)
                    writeJsonFile(nsBaseP, merged);
                evt(QString::fromLatin1("net_sync"),
                    QString::fromLatin1("webdav=%1 smb=%2 sftp=%3")
                        .arg(merged.value(QString::fromLatin1("webdav")).toArray().size())
                        .arg(merged.value(QString::fromLatin1("smb")).toArray().size())
                        .arg(merged.value(QString::fromLatin1("sftp")).toArray().size()));
            }
        }
        /* v1.4.0（R/F）：app-Recent/app-Favorite 列表 union——安卓 syncMetaUnion 同语义
         * （WebDavSyncer:450-455/1407-1530）：条目 {name,path,time}、键=path、time 新者胜、
         * 本地墓碑（favorite:/recent: 前缀+条目键）命中则远端条目跳过且不再发布；发布=
         * 本地 ∪ 远端(去墓碑) 全列表，语义等价免 PUT。本地行发布键 = internal-storage:
         * Books/<文件名>（安卓 getSyncPath 显示期重定位口径，手机端按文件名自动归位）；
         * 下行条目按 basename 与本地书匹配（books 通道 pathByName 复用）落库（行带
         * sync_key=远端原键），未匹配条目持久留存 meta_sync 表——书后到位下一轮自动解析
         * （安卓显示期过滤同款容忍）。 */
        for (int pass = 0; pass < 2; ++pass)
        {
            const bool isFav = (pass == 0);
            const char *kind = isFav ? "favorite" : "recent";
            const QString mUrl = gUrl + (isFav ? QString::fromLatin1("/app-Favorite.json")
                                               : QString::fromLatin1("/app-Recent.json"));
            const QHash<QString, long long> tomb =
                Db::tombstoneMap(kind, QString::fromLatin1("")); /* 显式空串：QString() 绑 NULL 永不匹配 */
            QJsonObject merged; /* 条目键 -> 条目 */
            const QVector<Db::FavRow> rows = isFav ? Db::favoriteRows() : Db::recentRows();
            for (const Db::FavRow &r : rows)
            {
                if (r.path.startsWith(QString::fromLatin1("remote://"))) continue;
                const QString key = r.syncKey.isEmpty()
                                        ? QString::fromLatin1("internal-storage:Books/") +
                                              QFileInfo(r.path).fileName()
                                        : r.syncKey;
                if (tomb.contains(key)) continue;
                QJsonObject e;
                e.insert(QLatin1String("name"), QFileInfo(r.path).fileName());
                e.insert(QLatin1String("path"), key);
                e.insert(QLatin1String("time"), (double)r.t);
                merged.insert(key, e);
            }
            for (const Db::MetaEntry &m : Db::metaSyncList(kind))
            {
                if (tomb.contains(m.key))
                {
                    Db::metaSyncDelete(kind, m.key);
                    continue;
                }
                if (!merged.contains(m.key))
                {
                    QJsonObject e;
                    e.insert(QLatin1String("name"), m.name);
                    e.insert(QLatin1String("path"), m.key);
                    e.insert(QLatin1String("time"), (double)m.time);
                    merged.insert(m.key, e);
                }
            }
            const QByteArray rem = dav.get(mUrl);
            QJsonArray remArr;
            if (!rem.isEmpty())
            {
                const QJsonDocument rd = QJsonDocument::fromJson(rem);
                if (rd.isArray()) remArr = rd.array();
            }
            int down = 0, upd = 0, skipTomb = 0;
            for (const QJsonValue &v : remArr)
            {
                const QJsonObject e = v.toObject();
                const QString k = e.value(QLatin1String("path")).toString();
                if (k.isEmpty()) continue;
                if (tomb.contains(k))
                {
                    skipTomb++;
                    continue;
                }
                const double rt = e.value(QLatin1String("time")).toDouble();
                QJsonObject mine = merged.value(k).toObject();
                if (mine.isEmpty())
                {
                    merged.insert(k, e);
                    down++;
                    Db::metaSyncUpsert(kind, k,
                                       e.value(QLatin1String("name")).toString(),
                                       (long long)rt);
                    /* 立即解析：basename 与本地书匹配（books 通道同名基建复用） */
                    const QString lf = pathByName.value(QFileInfo(k).fileName());
                    if (!lf.isEmpty())
                    {
                        Db::metaSyncDelete(kind, k);
                        if (isFav)
                            Db::upsertFavoriteAt(lf, (long long)rt, k);
                        else
                            Db::upsertRecentAt(lf, (long long)rt, k);
                        evt(QString::fromLatin1(isFav ? "fav_import" : "recent_import"),
                            QString::fromLatin1("key=%1 local=%2")
                                .arg(k.left(60), QFileInfo(lf).fileName().left(60)));
                    }
                }
                else if (rt > mine.value(QLatin1String("time")).toDouble())
                {
                    mine.insert(QLatin1String("time"), rt);
                    merged.insert(k, mine);
                    upd++;
                    Db::metaSyncSetTime(kind, k, (long long)rt);
                }
            }
            /* 发布：语义比较（条目集与 name/time 全等才免 PUT——规避数组序噪声） */
            QJsonArray out;
            for (auto it = merged.constBegin(); it != merged.constEnd(); ++it)
                out.append(it.value());
            bool same = (out.size() == remArr.size());
            if (same)
            {
                for (const QJsonValue &v : remArr)
                {
                    const QJsonObject e = v.toObject();
                    const QJsonObject mine =
                        merged.value(e.value(QLatin1String("path")).toString()).toObject();
                    if (mine.isEmpty() ||
                        !hr::jEqVal(mine.value(QLatin1String("time")),
                                    e.value(QLatin1String("time"))) ||
                        !hr::jEqVal(mine.value(QLatin1String("name")),
                                    e.value(QLatin1String("name"))))
                    {
                        same = false;
                        break;
                    }
                }
            }
            if (!same)
                dav.put(mUrl, QJsonDocument(out).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
            evt(QString::fromLatin1(isFav ? "fav_sync" : "recent_sync"),
                QString::fromLatin1("down=%1 upd=%2 tomb=%3 n=%4")
                    .arg(down)
                    .arg(upd)
                    .arg(skipTomb)
                    .arg((int)out.size()));
        }
        /* v1.5.0（M1）：app-Misc.json 参与——安卓 syncThreeWayFile(misc)+importMisc 同语义
         * （WebDavSyncer:466-469/1194-1330 + ProfileStateIO:158-262）。内容四段 {AppSP 整对象,
         * OPDS 登录, 应用/书密码, POPUPS} 透传合并（Qt 无对应消费面，远端键全保留）；
         * keepLocal=APPSP_IDENTITY_FIELDS(rootPath1/currentProfile/syncRootID/lastBookPath/
         * lastBookPage，安卓 importMisc 同款不迁移；嵌套在 AppSP 段内，jMerge3 递归传递)。
         * 首装与 S 同款：远端全量采纳，Qt 映射键仅远端缺失时补发（防 keepLocal 键本地
         * 缺失被整键丢弃、上行清掉其它设备身份字段）。Qt 发布映射子集：AppSP.readingMode↔
         * reader/scrollMode（安卓 READING_MODE_SCROLL=1↔true / BOOK=2↔false，其余模式归一
         * false 侧）。 */
        {
            static const char *msId[] = {"rootPath1", "currentProfile", "syncRootID",
                "lastBookPath", "lastBookPage"};
            QSet<QString> msKeep;
            for (const char *s : msId) msKeep.insert(QLatin1String(s));
            const QString msF = dataDir() + QString::fromLatin1("/app-Misc.json");
            const QString msBaseP = dataDir() + QString::fromLatin1("/sync-base/app-Misc.json");
            const QString msUrl = gUrl + QString::fromLatin1("/app-Misc.json");
            const int curMode = []()
            {
                QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"),
                             QSettings::IniFormat);
                return st.value(QLatin1String("reader/scrollMode"), true).toBool() ? 1 : 2;
            }();
            QJsonObject localO = readJsonFile(msF);
            {
                QJsonObject sp = localO.value(QLatin1String("AppSP")).toObject();
                sp.insert(QLatin1String("readingMode"), curMode);
                localO.insert(QLatin1String("AppSP"), sp);
            }
            const QJsonObject baseO = readJsonFile(msBaseP);
            const QByteArray msRem = dav.get(msUrl);
            QJsonObject msRemote;
            if (!msRem.isEmpty())
            {
                const QJsonDocument md = QJsonDocument::fromJson(msRem);
                if (md.isObject()) msRemote = md.object();
            }
            QJsonObject merged;
            if (msRemote.isEmpty() && !QFileInfo::exists(msF))
            {
                evt(QString::fromLatin1("misc_sync"), QString::fromLatin1("idle=1"));
            }
            else if (msRemote.isEmpty())
            {
                /* 服务器文件消失（404/空）：以本地回种（安卓 mergedFull=localObj 同款） */
                merged = localO;
                if (merged != baseO)
                    writeJsonFile(msBaseP, merged);
                dav.put(msUrl, QJsonDocument(merged).toJson(QJsonDocument::Compact),
                        QString::fromLatin1("application/json"));
                evt(QString::fromLatin1("misc_sync"), QString::fromLatin1("seed=1"));
            }
            else
            {
                if (baseO.isEmpty())
                {
                    /* 首装：远端全量采纳（S 同款防丢键），映射键仅远端缺失时补发 */
                    merged = msRemote;
                    for (const QString &k : msKeep)
                        if (localO.contains(k))
                            merged.insert(k, localO.value(k));
                    QJsonObject sp = merged.value(QLatin1String("AppSP")).toObject();
                    if (!sp.contains(QLatin1String("readingMode")))
                        sp.insert(QLatin1String("readingMode"), curMode);
                    merged.insert(QLatin1String("AppSP"), sp);
                    evt(QString::fromLatin1("misc_sync"), QString::fromLatin1("adopt=1"));
                }
                else
                {
                    merged = hr::jMerge3(baseO, localO, msRemote, msKeep);
                }
                /* 下行 readingMode → reader/scrollMode（1=滚动 true / 其余=书页 false） */
                {
                    QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"),
                                 QSettings::IniFormat);
                    const int rm = merged.value(QLatin1String("AppSP"))
                                       .toObject()
                                       .value(QLatin1String("readingMode"))
                                       .toInt(1);
                    const bool sm = (rm == 1);
                    const QVariant cur = st.value(QLatin1String("reader/scrollMode"));
                    if (!cur.isValid() || cur.toBool() != sm)
                    {
                        st.setValue(QLatin1String("reader/scrollMode"), sm);
                        evt(QString::fromLatin1("misc_import"),
                            QString::fromLatin1("readingMode=%1").arg(rm));
                    }
                }
                if (merged != localO)
                    writeJsonFile(msF, merged);
                if (!merged.isEmpty() && merged != msRemote)
                    dav.put(msUrl, QJsonDocument(merged).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                if (merged != baseO)
                    writeJsonFile(msBaseP, merged);
            }
        }
        /* v1.3.0（F'）：注解 Qt 命名空间侧车——global/HowRead-desktop-annotations/<内容MD5>.json
         * （HowRead-desktop- 前缀命名空间纪律；安卓不消费）。Qt↔Qt 多机互通：
         * 载荷 {t, ann:{创建毫秒:{p,c,q,n,pc}}, tk:{创建毫秒:删除毫秒}}，union+双向墓碑抑制。 */
        {
            const QString annRoot = gUrl + QString::fromLatin1("/HowRead-desktop-annotations");
            dav.mkcol(annRoot);
            const QString cacheF = dataDir() + QString::fromLatin1("/ann-sync.json");
            QJsonObject cache = readJsonFile(cacheF); /* {内容MD5: 本地路径} 跨会话身份记忆 */
            QStringList paths;
            for (const QString &p : Db::listAnnotationBooks()) paths << p;
            for (auto it = annMd5ByHash.constBegin(); it != annMd5ByHash.constEnd(); ++it)
            {
                const QString p = it.value();
                if (QFileInfo::exists(p) && !paths.contains(p)) paths << p;
            }
            for (auto it = cache.constBegin(); it != cache.constEnd(); ++it)
            {
                const QString p = it.value().toString();
                if (QFileInfo::exists(p) && !paths.contains(p)) paths << p;
            }
            QJsonObject newCache;
            int annUp = 0, annDown = 0;
            for (const QString &p : paths)
            {
                const QString h = syncMd5(p);
                if (h.isEmpty()) continue;
                newCache.insert(h, p);
                QJsonObject ann;
                long long maxT = 0;
                for (const Db::AnnotationRow &a : Db::listAnnotations(p, -1))
                {
                    QJsonObject e;
                    e.insert(QString::fromLatin1("p"), a.page);
                    e.insert(QString::fromLatin1("c"), (double)a.color);
                    e.insert(QString::fromLatin1("q"), a.quads);
                    e.insert(QString::fromLatin1("n"), a.note);
                    e.insert(QString::fromLatin1("pc"), a.pageCount);
                    ann.insert(QString::number(a.createdAt), e);
                    maxT = qMax(maxT, a.createdAt);
                }
                const QHash<QString, long long> tk = Db::tombstoneMap("ann", h);
                QJsonObject tkO;
                for (auto t = tk.constBegin(); t != tk.constEnd(); ++t)
                    tkO.insert(t.key(), (double)t.value());
                const QByteArray remRaw = dav.get(annRoot + QString::fromLatin1("/") + h +
                                                  QString::fromLatin1(".json"));
                QJsonObject remote;
                if (!remRaw.trimmed().isEmpty())
                {
                    const QJsonDocument d = QJsonDocument::fromJson(remRaw);
                    if (d.isObject()) remote = d.object();
                }
                const QJsonObject rAnn = remote.value(QString::fromLatin1("ann")).toObject();
                const QJsonObject rTk = remote.value(QString::fromLatin1("tk")).toObject();
                if (remote.isEmpty() && ann.isEmpty() && tk.isEmpty()) continue;
                /* 墓碑并集：远端新墓碑 → 本地删行+落墓碑（双向删除传播） */
                QJsonObject tkM = tkO;
                for (auto it = rTk.constBegin(); it != rTk.constEnd(); ++it)
                {
                    const long long rt = (long long)it.value().toDouble();
                    const long long lt = tk.contains(it.key()) ? tk.value(it.key()) : 0;
                    if (rt > lt) tkM.insert(it.key(), (double)rt);
                    if (!tk.contains(it.key()) && ann.contains(it.key()))
                    {
                        Db::deleteAnnotationByCreated(p, (long long)it.key().toDouble());
                        Db::tombstoneAdd("ann", h, it.key());
                        ann.remove(it.key());
                    }
                }
                /* 注解并集（扣墓碑）；远端新增 → 落库 */
                QJsonObject mergedAnn = ann;
                for (auto it = rAnn.constBegin(); it != rAnn.constEnd(); ++it)
                {
                    if (tkM.contains(it.key())) continue;
                    if (!mergedAnn.contains(it.key()))
                    {
                        const QJsonObject e = it.value().toObject();
                        Db::addAnnotationRaw(p, e.value(QString::fromLatin1("p")).toInt(),
                                             (int)e.value(QString::fromLatin1("c")).toDouble(),
                                             e.value(QString::fromLatin1("q")).toString(),
                                             e.value(QString::fromLatin1("n")).toString(),
                                             e.value(QString::fromLatin1("pc")).toInt(),
                                             (long long)it.key().toDouble());
                        mergedAnn.insert(it.key(), it.value());
                        annDown++;
                    }
                }
                QJsonObject payload;
                payload.insert(QString::fromLatin1("t"), (double)maxT);
                payload.insert(QString::fromLatin1("ann"), mergedAnn);
                payload.insert(QString::fromLatin1("tk"), tkM);
                if (payload != remote)
                {
                    dav.put(annRoot + QString::fromLatin1("/") + h + QString::fromLatin1(".json"),
                            QJsonDocument(payload).toJson(QJsonDocument::Compact),
                            QString::fromLatin1("application/json"));
                    annUp++;
                }
            }
            if (newCache != cache) writeJsonFile(cacheF, newCache);
            evt(QString::fromLatin1("ann_sync"),
                QString::fromLatin1("books=%1 up=%2 down=%3")
                    .arg(newCache.size()).arg(annUp).arg(annDown));
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
