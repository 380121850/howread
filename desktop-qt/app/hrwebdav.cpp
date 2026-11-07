#include "hrwebdav.h"
#include "hrpaths.h"

#include <QDateTime>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QThreadStorage>
#include <QUrl>

namespace hr
{

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

int WebDavClient::request(const char *verb, const QString &url, const QByteArray &body,
                          const QString &contentType, QByteArray *outBody, const char *depth)
{
    /* QNAM 有线程亲和：取块会从工作线程发起——每线程独立实例（QThreadStorage 线程退出自清） */
    static QThreadStorage<QNetworkAccessManager *> g_nam;
    if (!g_nam.hasLocalData()) g_nam.setLocalData(new QNetworkAccessManager());
    QNetworkAccessManager *nam = g_nam.localData();
    Q_UNUSED(m_nam);
    QUrl u(url);
    QNetworkRequest req(u);
    req.setRawHeader("Authorization", basicAuth(m_user, m_pass).toLatin1());
    if (depth) req.setRawHeader("Depth", depth);
    if (!contentType.isEmpty()) req.setHeader(QNetworkRequest::ContentTypeHeader, contentType);
    QNetworkReply *rep = nullptr;
    if (strcmp(verb, "GET") == 0) rep = nam->get(req);
    else if (strcmp(verb, "PUT") == 0) rep = nam->put(req, body);
    else if (strcmp(verb, "PROPFIND") == 0) rep = nam->sendCustomRequest(req, "PROPFIND", body);
    else if (strcmp(verb, "MKCOL") == 0) rep = nam->sendCustomRequest(req, "MKCOL");
    else if (strcmp(verb, "HEAD") == 0) rep = nam->head(req);
    else rep = nam->sendCustomRequest(req, verb, body);
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const int code = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (outBody) *outBody = rep->readAll();
    rep->deleteLater();
    return code;
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
    /* 简化解析：<response> 块逐段；href/collection/length/lastmodified */
    const QRegularExpression rxResponse("<[a-zA-Z0-9]*:?response>(.*?)</[a-zA-Z0-9]*:?response>",
                                        QRegularExpression::DotMatchesEverythingOption);
    const QRegularExpression rxHref("<[a-zA-Z0-9]*:?href>(.*?)</[a-zA-Z0-9]*:?href>");
    const QRegularExpression rxLen("<[a-zA-Z0-9]*:?getcontentlength>(\\d+)");
    const QRegularExpression rxCollection("<[a-zA-Z0-9]*:?collection\\s*/?>");
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
    static QThreadStorage<QNetworkAccessManager *> g_nam;
    if (!g_nam.hasLocalData()) g_nam.setLocalData(new QNetworkAccessManager());
    QNetworkAccessManager *nam = g_nam.localData();
    QUrl u(url);
    QNetworkRequest req(u);
    req.setRawHeader("Authorization", basicAuth(m_user, m_pass).toLatin1());
    req.setRawHeader("Range", QString("bytes=%1-%2").arg(start).arg(start + len - 1).toLatin1());
    QNetworkReply *rep = nam->get(req);
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const int c = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    QByteArray body = (c == 206) ? rep->readAll() : QByteArray();
    rep->deleteLater();
    if (code) *code = c;
    return body;
}

long long WebDavClient::contentLength(const QString &url)
{
    static QThreadStorage<QNetworkAccessManager *> g_nam;
    if (!g_nam.hasLocalData()) g_nam.setLocalData(new QNetworkAccessManager());
    QNetworkAccessManager *nam = g_nam.localData();
    QUrl u(url);
    QNetworkRequest req(u);
    req.setRawHeader("Authorization", basicAuth(m_user, m_pass).toLatin1());
    QNetworkReply *rep = nam->head(req);
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    const int c = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const qint64 len = rep->header(QNetworkRequest::ContentLengthHeader).toLongLong();
    if (c != 200 && c != 206) evt("dav_head_fail", QString("code=%1 url=%2").arg(c).arg(url.left(80)));
    rep->deleteLater();
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

} // namespace hr
