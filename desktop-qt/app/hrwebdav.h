/* WebDAV 客户端（QtNetwork 手写 PROPFIND/GET/PUT/MKCOL；Basic 鉴权；同步等价 sardine 用法）。 */
#ifndef HRWEBDAV_H
#define HRWEBDAV_H

#include <QByteArray>
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

private:
    int request(const char *verb, const QString &url, const QByteArray &body,
                const QString &contentType, QByteArray *outBody, const char *depth);
    QString m_base, m_user, m_pass;
    QNetworkAccessManager *m_nam = nullptr;
};

} // namespace hr

#endif /* HRWEBDAV_H */
