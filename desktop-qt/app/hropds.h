/* v0.60 批次二：OPDS 目录客户端（Java OpdsClient 同语义面）：
 * Atom 浏览/翻页 + feed 级 rel=search 的 OpenSearch 描述发现 + 目录内搜索 +
 * Basic/Digest(MD5) 认证（401 按 RFC2617 重试一次）+ 条目封面/摘要。
 * url 语义与 Java 版一致：relDir=绝对 URL（feed 的 subsection/acquisition href 直接可用）。 */
#ifndef HROPDS_H
#define HROPDS_H

#include <QByteArray>
#include <QList>
#include <QString>

class QNetworkAccessManager;

namespace hr
{

struct OpdsEntry
{
    QString name;
    bool isDir = false; /* subsection/collection=目录；acquisition=书 */
    long long size = 0;
    QString url;     /* 绝对 URL（下一页/子目录/书） */
    QString cover;   /* 封面缩略图 URL（可能空） */
    QString summary; /* 摘要（已去 HTML，≤300 字） */
};

class OpdsClient
{
public:
    OpdsClient(const QString &rootUrl, const QString &user, const QString &pass);
    ~OpdsClient();

    const QString &searchTemplate() const { return m_tpl; }

    /* url 空=root；失败返回空表并置 *err。 */
    QList<OpdsEntry> list(const QString &url, QString *err = nullptr);
    /* 无模板时 *err 原因、返回空表。 */
    QList<OpdsEntry> search(const QString &q, QString *err = nullptr);
    QByteArray fetchBytes(const QString &url, QString *err = nullptr);

    /* feed XML 解析（本地文件/网络共用）。 */
    bool parseFeedData(const QByteArray &xml, const QString &baseUrl, QList<OpdsEntry> *out,
                       QString *err = nullptr);

private:
    QByteArray get(const QString &url, QString *err);

    QNetworkAccessManager *m_nam = nullptr;
    QString m_root, m_user, m_pass, m_tpl;
};

} // namespace hr

#endif /* HROPDS_H */
