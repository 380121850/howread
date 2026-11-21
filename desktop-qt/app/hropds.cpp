#include "hropds.h"
#include "hrwebdav.h" /* v1.1.0：Digest 实现移至 hrwebdav 共享 */

#include <QCryptographicHash>
#include <QDateTime>
#include <QEventLoop>
#include <QHash>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QStringList>
#include <QTimer>
#include <QUrl>
#include <QXmlStreamReader>

namespace hr
{

OpdsClient::OpdsClient(const QString &rootUrl, const QString &user, const QString &pass)
    : m_nam(new QNetworkAccessManager()), m_root(rootUrl), m_user(user), m_pass(pass)
{
}

OpdsClient::~OpdsClient() { delete m_nam; }

static QString stripHtml(const QString &s)
{
    QString t = s;
    t.remove(QRegularExpression("<[^>]*>"));
    t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'");
    return t.simplified();
}

/* RFC2617 Digest（MD5）最小实现（HttpDigest.kt 移植）；algorithm 非 MD5 返回空。 */
/* 同步单请求：返回 body（2xx）；非 2xx/网络错误返回空并带码/认证头 */
static QByteArray opdsSyncReq(QNetworkAccessManager *nam, const QNetworkRequest &req, int *codeOut,
                              QString *chOut, QString *err)
{
    QNetworkReply *rep = nam->get(req);
    QEventLoop loop;
    QTimer t;
    t.setSingleShot(true);
    t.start(30000);
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    QObject::connect(&t, &QTimer::timeout, &loop, &QEventLoop::quit);
    loop.exec();
    if (!rep->isFinished())
    {
        rep->abort();
        rep->deleteLater();
        if (codeOut) *codeOut = 0;
        if (err) *err = "timeout";
        return QByteArray();
    }
    const int code = rep->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QString ch = QString::fromLatin1(rep->rawHeader("WWW-Authenticate"));
    const QByteArray body = rep->readAll();
    rep->deleteLater();
    if (codeOut) *codeOut = code;
    if (chOut) *chOut = ch;
    if (code < 200 || code >= 300)
    {
        if (err) *err = code == 0 ? "network" : "HTTP " + QString::number(code);
        return QByteArray();
    }
    return body;
}

QByteArray OpdsClient::get(const QString &url, QString *err)
{
    const QByteArray basic =
        m_user.trimmed().isEmpty()
            ? QByteArray()
            : "Basic " + QByteArray((m_user + ":" + m_pass).toUtf8()).toBase64();
    auto req1 = [this, &basic, &url](const QString &auth) {
        QUrl reqUrl(url);
        QNetworkRequest req(reqUrl);
        req.setRawHeader("User-Agent", "HowReadQt");
        if (!auth.isEmpty())
            req.setRawHeader("Authorization", auth.toLatin1());
        else if (!basic.isEmpty())
            req.setRawHeader("Authorization", basic);
        return req;
    };
    int code = 0;
    QString ch, e1;
    QByteArray body = opdsSyncReq(m_nam, req1(QString()), &code, &ch, &e1);
    if (body.isEmpty() && code == 401 && ch.trimmed().startsWith("Digest", Qt::CaseInsensitive))
    {
        const QUrl u(url);
        const QString uri = u.path(QUrl::FullyEncoded) +
                            (u.hasQuery() ? "?" + u.query(QUrl::FullyEncoded) : QString());
        const QString auth = davDigestAuth("GET", uri, m_user, m_pass, ch);
        if (!auth.isEmpty())
        {
            body = opdsSyncReq(m_nam, req1(auth), &code, &ch, &e1);
            if (body.isEmpty() && err) *err = e1 + " (digest)";
        }
        else if (err)
            *err = "HTTP 401 (digest unsupported)";
    }
    else if (body.isEmpty() && err)
        *err = e1;
    return body;
}

/* OpenSearch 模板转绝对 URL（{searchTerms} 花括号必须手工拼——URI.resolve 同款约束） */
static QString resolveTemplate(const QString &descUrl, const QString &tpl)
{
    if (tpl.startsWith("http", Qt::CaseInsensitive)) return tpl;
    if (tpl.startsWith("/"))
    {
        const QUrl u(descUrl);
        return u.scheme() + "://" + u.authority(QUrl::FullyEncoded) + tpl;
    }
    return QUrl(descUrl).resolved(QUrl(QString(tpl).replace("{", "%7B").replace("}", "%7D")))
               .toString()
               .replace("%7B", "{")
               .replace("%7D", "}");
}

bool OpdsClient::parseFeedData(const QByteArray &xml, const QString &baseUrl, QList<OpdsEntry> *out,
                               QString *err)
{
    out->clear();
    QString nextHref, searchDesc;
    QXmlStreamReader r(xml);
    bool inEntry = false;
    QString capture, entryTitle, entrySummary;
    struct Lk
    {
        QString rel, href, type;
    };
    QList<Lk> links;
    QString text;
    while (!r.atEnd())
    {
        const auto tok = r.readNext();
        const QString name = r.name().toString();
        if (tok == QXmlStreamReader::StartElement)
        {
            if (name == "entry")
            {
                inEntry = true;
                links.clear();
                entryTitle.clear();
                entrySummary.clear();
                text.clear();
            }
            else if (name == "title" && inEntry)
            {
                capture = "title";
                text.clear();
            }
            else if ((name == "summary" || name == "content") && inEntry)
            {
                capture = name;
                text.clear();
            }
            else if (name == "link")
            {
                Lk l;
                l.rel = r.attributes().value("rel").toString();
                l.href = r.attributes().value("href").toString();
                l.type = r.attributes().value("type").toString();
                if (inEntry) links.append(l);
                else if (l.rel == "next" && !l.href.isEmpty())
                    nextHref = QUrl(baseUrl).resolved(QUrl(l.href)).toString();
                else if (l.rel == "search" && !l.href.isEmpty() && l.type.contains("opensearch"))
                    searchDesc = QUrl(baseUrl).resolved(QUrl(l.href)).toString();
            }
            else if (!capture.isEmpty())
            {
                text += ' '; /* 嵌套标签当分隔 */
            }
        }
        else if (tok == QXmlStreamReader::Characters) /* CDATA 亦以 Characters 到达 */
        {
            if (inEntry && !capture.isEmpty()) text += r.text().toString();
        }
        else if (tok == QXmlStreamReader::EndElement)
        {
            if (name == "title" && capture == "title")
            {
                entryTitle = QString(text).replace('\n', " ").trimmed();
                capture.clear();
            }
            else if ((name == "summary" || name == "content") && capture == name)
            {
                if (entrySummary.isEmpty()) entrySummary = stripHtml(text).trimmed();
                capture.clear();
            }
            else if (name == "entry")
            {
                inEntry = false;
                if (!entryTitle.isEmpty())
                {
                    QString sub, acq, cover;
                    for (const Lk &l : links)
                    {
                        if (sub.isEmpty() &&
                            (l.rel.contains("subsection") || l.rel.contains("collection")) &&
                            !l.href.isEmpty())
                            sub = l.href;
                        if (acq.isEmpty() && l.rel.contains("acquisition") && !l.href.isEmpty())
                            acq = l.href;
                        if (cover.isEmpty() && !l.href.isEmpty() &&
                            (l.rel.contains("image") || l.type.startsWith("image/")))
                            cover = l.href;
                    }
                    OpdsEntry e;
                    e.name = entryTitle;
                    if (!sub.isEmpty())
                    {
                        e.isDir = true;
                        e.url = QUrl(baseUrl).resolved(QUrl(sub)).toString();
                    }
                    else if (!acq.isEmpty())
                    {
                        e.isDir = false;
                        e.url = QUrl(baseUrl).resolved(QUrl(acq)).toString();
                    }
                    else
                        continue;
                    e.cover = cover.isEmpty() ? QString()
                                             : QUrl(baseUrl).resolved(QUrl(cover)).toString();
                    e.summary = entrySummary.left(300);
                    out->append(e);
                }
                links.clear();
            }
        }
    }
    if (r.hasError() && r.error() != QXmlStreamReader::PrematureEndOfDocumentError)
    {
        if (err) *err = "xml: " + r.errorString();
        return false;
    }
    if (!searchDesc.isEmpty() && m_tpl.isEmpty())
    {
        QString de;
        const QByteArray d = get(searchDesc, &de);
        if (!d.isEmpty())
        {
            const QRegularExpressionMatch m =
                QRegularExpression("template\\s*=\\s*[\"']([^\"']*)[\"']").match(
                    QString::fromUtf8(d));
            if (m.hasMatch()) m_tpl = resolveTemplate(searchDesc, m.captured(1));
        }
    }
    if (!nextHref.isEmpty())
    {
        OpdsEntry nx;
        nx.name = "下一页 ›";
        nx.isDir = true;
        nx.url = nextHref;
        out->append(nx);
    }
    return true;
}

QList<OpdsEntry> OpdsClient::list(const QString &url, QString *err)
{
    const QString u = url.trimmed().isEmpty() ? m_root : url.trimmed();
    QList<OpdsEntry> out;
    QString e;
    const QByteArray body = get(u, &e);
    if (body.isEmpty())
    {
        if (err) *err = e;
        return out;
    }
    parseFeedData(body, u, &out, err);
    return out;
}

QList<OpdsEntry> OpdsClient::search(const QString &q, QString *err)
{
    QList<OpdsEntry> out;
    const QString query = q.trimmed();
    if (query.isEmpty()) return out;
    if (m_tpl.isEmpty())
        list(QString(), nullptr); /* 根 feed 发现一次搜索描述 */
    if (m_tpl.isEmpty())
    {
        if (err) *err = "no opensearch template";
        return out;
    }
    QString u = m_tpl;
    u.replace("{searchTerms}", QString::fromUtf8(QUrl::toPercentEncoding(query)));
    u.remove(QRegularExpression("\\{(?!searchTerms\\})[^}]*\\}"));
    QString e;
    const QByteArray body = get(u, &e);
    if (body.isEmpty())
    {
        if (err) *err = e;
        return out;
    }
    parseFeedData(body, u, &out, err);
    return out;
}

QByteArray OpdsClient::fetchBytes(const QString &url, QString *err)
{
    return get(url, err);
}

} // namespace hr
