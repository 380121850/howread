#include "hrwebdav.h"
#include "hrpaths.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QEventLoop>
#include <QHash>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QStringList>
#include <QThreadStorage>
#include <QUrl>

namespace hr
{

/* ---- HTTP Digest（RFC 2617 qop=auth；v1.1.0 移自 hropds 供两通道共用） ---- */
QString davDigestAuth(const QString &method, const QString &uri, const QString &user,
                      const QString &pass, const QString &challenge)
{
    QHash<QString, QString> ch;
    QString body = challenge.trimmed();
    if (body.startsWith("Digest", Qt::CaseInsensitive)) body = body.mid(6);
    const QStringList parts = body.split(',');
    for (const QString &part : parts)
    {
        const int i = part.indexOf('=');
        if (i <= 0) continue;
        const QString k = part.left(i).trimmed().toLower();
        QString v = part.mid(i + 1).trimmed();
        if (v.size() >= 2 && v.startsWith('"') && v.endsWith('"')) v = v.mid(1, v.size() - 2);
        ch.insert(k, v);
    }
    const QString realm = ch.value("realm"), nonce = ch.value("nonce");
    if (realm.isEmpty() || nonce.isEmpty()) return QString();
    if (!ch.value("algorithm", "MD5").toUpper().contains("MD5")) return QString();
    QString qop;
    const QStringList qs = ch.value("qop").split(',');
    for (const QString &x : qs)
        if (x.trimmed() == "auth") qop = "auth";
    const auto md5 = [](const QString &s) {
        return QString::fromLatin1(
            QCryptographicHash::hash(s.toUtf8(), QCryptographicHash::Md5).toHex());
    };
    const QString ha1 = md5(user + ":" + realm + ":" + pass);
    const QString ha2 = md5(method + ":" + uri);
    const QString cnonce =
        md5(nonce + QString::number(QDateTime::currentMSecsSinceEpoch())).left(16);
    const QString nc = "00000001";
    const QString resp = qop.isEmpty()
                             ? md5(ha1 + ":" + nonce + ":" + ha2)
                             : md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" +
                                   ha2);
    QString out = QString("Digest username=\"%1\", realm=\"%2\", nonce=\"%3\", uri=\"%4\", "
                          "response=\"%5\"")
                      .arg(user, realm, nonce, uri, resp);
    if (ch.contains("opaque")) out += QString(", opaque=\"%1\"").arg(ch.value("opaque"));
    if (!qop.isEmpty())
        out += QString(", qop=auth, nc=%1, cnonce=\"%2\", algorithm=MD5").arg(nc, cnonce);
    return out;
}

static QString basicAuth(const QString &user, const QString &pass)
{
    return "Basic " + QString::fromLatin1(
                          QByteArray(QString(user + ":" + pass).toUtf8()).toBase64());
}

WebDavClient::WebDavClient(const QString &baseUrl, const QString &user, const QString &pass)
    : m_base(baseUrl.trimmed()), m_user(user), m_pass(pass)
{
    if (!m_base.endsWith('/')) m_base += '/';
}

int WebDavClient::perform(const char *verb, const QString &url, const QByteArray &body,
                          const QString &contentType,
                          const QVector<QPair<QByteArray, QByteArray>> &extra,
                          QByteArray *outBody, QHash<QByteArray, QByteArray> *outHeaders)
{
    /* QNAM 有线程亲和：取块会从工作线程发起——每线程独立实例（QThreadStorage 线程退出自清） */
    static QThreadStorage<QNetworkAccessManager *> g_nam;
    if (!g_nam.hasLocalData()) g_nam.setLocalData(new QNetworkAccessManager());
    QNetworkAccessManager *nam = g_nam.localData();
    Q_UNUSED(m_nam);
    QUrl u(url);
    auto send = [&](const QByteArray &authHdr) -> QNetworkReply *
    {
        QNetworkRequest req(u);
        if (!authHdr.isEmpty()) req.setRawHeader("Authorization", authHdr);
        for (const auto &h : extra) req.setRawHeader(h.first, h.second);
        if (!contentType.isEmpty())
            req.setHeader(QNetworkRequest::ContentTypeHeader, contentType);
        QNetworkReply *rep = nullptr;
        if (strcmp(verb, "GET") == 0) rep = nam->get(req);
        else if (strcmp(verb, "PUT") == 0) rep = nam->put(req, body);
        else if (strcmp(verb, "PROPFIND") == 0)
            rep = nam->sendCustomRequest(req, "PROPFIND", body);
        else if (strcmp(verb, "MKCOL") == 0) rep = nam->sendCustomRequest(req, "MKCOL");
        else if (strcmp(verb, "HEAD") == 0) rep = nam->head(req);
        else if (strcmp(verb, "DELETE") == 0) rep = nam->sendCustomRequest(req, "DELETE");
        else rep = nam->sendCustomRequest(req, verb, body);
        QEventLoop loop;
        QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
        loop.exec();
        return rep;
    };
    /* Basic 预置头（快路径：常规服务器零往返） */
    const QByteArray pre =
        m_user.isEmpty() ? QByteArray() : basicAuth(m_user, m_pass).toLatin1();
    QNetworkReply *rep = send(pre);
    int code = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    /* v1.1.0（安卓 10-31 小米 12S 同款修复）：Digest-only 服务器——
     * Basic 预置头收到 401+Digest 挑战时按挑战计算并重发一次 */
    const QByteArray chal = rep->rawHeader("WWW-Authenticate");
    if (code == 401 && chal.startsWith("Digest") && !m_user.isEmpty())
    {
        const QString uri = u.path(QUrl::FullyEncoded) +
                            (u.hasQuery() ? "?" + u.query(QUrl::FullyEncoded) : QString());
        const QString da =
            davDigestAuth(verb, uri, m_user, m_pass, QString::fromUtf8(chal));
        if (!da.isEmpty())
        {
            rep->deleteLater();
            rep = send(da.toLatin1());
            code = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        }
    }
    if (outHeaders)
        for (const QByteArray &h : rep->rawHeaderList())
            outHeaders->insert(h, rep->rawHeader(h));
    if (outBody) *outBody = rep->readAll();
    rep->deleteLater();
    return code;
}

int WebDavClient::request(const char *verb, const QString &url, const QByteArray &body,
                          const QString &contentType, QByteArray *outBody, const char *depth)
{
    QVector<QPair<QByteArray, QByteArray>> extra;
    if (depth) extra.append(qMakePair(QByteArray("Depth"), QByteArray(depth)));
    return perform(verb, url, body, contentType, extra, outBody);
}

bool WebDavClient::list(const QString &dirUrl, QVector<DavEntry> *out, QString *err)
{
    out->clear();
    const QByteArray propfind =
        "<?xml version=\"1.0\"?>\r\n"
        "<a:propfind xmlns:a=\"DAV:\"><a:prop>"
        "<a:displayname/><a:resourcetype/><a:getcontentlength/><a:getlastmodified/>"
        "</a:prop></a:propfind>";
    QByteArray body;
    /* 集合 PROPFIND 必须带尾斜杠：apache 对无斜杠集合返回 301，而 QNAM 不为自定义动词跟随重定向 */
    QString url = dirUrl;
    if (!url.endsWith('/')) url += '/';
    const int code = request("PROPFIND", url, propfind, "application/xml", &body, "1");
    if (code != 207)
    {
        if (err) *err = QString("PROPFIND %1").arg(code);
        return false;
    }
    const QString xml = QString::fromUtf8(body);
    /* 简化解析：<response> 块逐段；href/collection/length/lastmodified。
     * v1.1.0 根修：开标签允许带 xmlns 属性（apache/mod_dav 实际响应
     * <D:response xmlns:lp1="DAV:" ...>，旧正则要求紧跟 ">" 恒不命中——
     * 远程目录列表/扫描/同步 books 清单在真实服务器上一直解析为空） */
    const QRegularExpression rxResponse(
        "<(?:[a-zA-Z0-9]+:)?response\\b[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?response\\s*>",
        QRegularExpression::DotMatchesEverythingOption);
    const QRegularExpression rxHref(
        "<(?:[a-zA-Z0-9]+:)?href\\b[^>]*>(.*?)</(?:[a-zA-Z0-9]+:)?href\\s*>");
    const QRegularExpression rxLen("<(?:[a-zA-Z0-9]+:)?getcontentlength[^>]*>(\\d+)");
    const QRegularExpression rxCollection("<(?:[a-zA-Z0-9]+:)?collection\\b[^>]*>");
    QRegularExpressionMatchIterator it = rxResponse.globalMatch(xml);
    const QUrl base(dirUrl);
    while (it.hasNext())
    {
        const QString seg = it.next().captured(1);
        const QString href = QUrl::fromPercentEncoding(
            rxHref.match(seg).captured(1).toUtf8());
        if (href.isEmpty()) continue;
        QUrl u(href);
        if (u.isRelative()) u = base.resolved(QUrl(href));
        const QString path = u.path();
        if (QUrl::fromPercentEncoding(base.path().toUtf8()) == path) continue; /* 自身 */
        DavEntry e;
        e.name = path.endsWith('/') ? path.section('/', -2, -2) : path.section('/', -1);
        if (e.name.isEmpty()) continue;
        e.isDir = rxCollection.match(seg).hasMatch();
        e.size = rxLen.match(seg).captured(1).toLongLong();
        out->append(e);
    }
    return true;
}

QByteArray WebDavClient::get(const QString &url, int *code)
{
    QByteArray body;
    const int c = request("GET", url, QByteArray(), QString(), &body, nullptr);
    if (code) *code = c;
    return body;
}

QByteArray WebDavClient::getRange(const QString &url, long long start, int len, int *code)
{
    QVector<QPair<QByteArray, QByteArray>> extra;
    extra.append(qMakePair(
        QByteArray("Range"),
        QString("bytes=%1-%2").arg(start).arg(start + len - 1).toLatin1()));
    QByteArray body;
    const int c = perform("GET", url, QByteArray(), QString(), extra, &body);
    if (code) *code = c;
    return c == 206 ? body : QByteArray();
}

long long WebDavClient::contentLength(const QString &url)
{
    QHash<QByteArray, QByteArray> hs;
    QByteArray body;
    const int c = perform("HEAD", url, QByteArray(), QString(), {}, &body, &hs);
    const qint64 len = hs.value(QByteArray("Content-Length")).toLongLong();
    if (c != 200 && c != 206)
        evt("dav_head_fail", QString("code=%1 url=%2").arg(c).arg(url.left(80)));
    return (c == 200 || c == 206) ? len : -1;
}

bool WebDavClient::put(const QString &url, const QByteArray &data, const QString &contentType)
{
    const int c = request("PUT", url, data, contentType, nullptr, nullptr);
    return c >= 200 && c < 300;
}

bool WebDavClient::mkcol(const QString &url)
{
    const int c = request("MKCOL", url, QByteArray(), QString(), nullptr, nullptr);
    return c == 201 || c == 405; /* 405=已存在 */
}

bool WebDavClient::exists(const QString &url)
{
    const int c = request("HEAD", url, QByteArray(), QString(), nullptr, nullptr);
    return c >= 200 && c < 300;
}

bool WebDavClient::del(const QString &url)
{
    const int c = perform("DELETE", url, QByteArray(), QString(), {}, nullptr);
    return (c >= 200 && c < 300) || c == 404; /* 404=服务器上已无此件 */
}

} // namespace hr
