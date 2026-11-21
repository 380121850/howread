/* WebDAV 客户端（QtNetwork 手写 PROPFIND/GET/PUT/MKCOL/DELETE；Basic 预置 +
 * 401 Digest 挑战自动重试（v1.1.0，安卓 10-31 小米 12S 同款修复）；同步等价 sardine 用法）。 */
#ifndef HRWEBDAV_H
#define HRWEBDAV_H

#include <QByteArray>
#include <QHash>
#include <QPair>
#include <QString>
#include <QVector>

class QNetworkAccessManager;

namespace hr
{

struct DavEntry
{
    QString name; /* 文件/目录名（非全 URL） */
    bool isDir = false;
    long long size = 0;
    long long mtime = 0;
};

/* HTTP Digest Authorization 头（RFC 2617 qop=auth；challenge=WWW-Authenticate 原文）。
 * 不支持/缺参时返回空串。OPDS 与 WebDAV 通道共用（v1.1.0 移自 hropds）。 */
QString davDigestAuth(const QString &method, const QString &uri, const QString &user,
                      const QString &pass, const QString &challenge);

class WebDavClient
{
public:
    WebDavClient(const QString &baseUrl, const QString &user, const QString &pass);

    bool list(const QString &dirUrl, QVector<DavEntry> *out, QString *err = nullptr);
    QByteArray get(const QString &url, int *code = nullptr);
    /* Range 读（块缓存取块用；服务器须支持 bytes= 区间） */
    QByteArray getRange(const QString &url, long long start, int len, int *code = nullptr);
    /* HEAD 取内容长度（无 Content-Length 或失败=-1） */
    long long contentLength(const QString &url);
    bool put(const QString &url, const QByteArray &data, const QString &contentType);
    bool mkcol(const QString &url);
    bool exists(const QString &url);
    /* v1.1.0：删除（同步清理已删书的服务器副本；404 视为成功） */
    bool del(const QString &url);

private:
    int request(const char *verb, const QString &url, const QByteArray &body,
                const QString &contentType, QByteArray *outBody, const char *depth);
    /* v1.1.0：统一发送（Basic 预置 → 401+Digest 挑战重试一次） */
    int perform(const char *verb, const QString &url, const QByteArray &body,
                const QString &contentType,
                const QVector<QPair<QByteArray, QByteArray>> &extra,
                QByteArray *outBody,
                QHash<QByteArray, QByteArray> *outHeaders = nullptr);
    QString m_base, m_user, m_pass;
    QNetworkAccessManager *m_nam = nullptr;
};

} // namespace hr

#endif /* HRWEBDAV_H */
