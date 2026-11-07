/* v0.71.0：SMB 客户端（libsmb2 vendored 静态库；对齐 hrssh.h 范式：互斥串行/条件编译降级）。
 * 认证 NTLMv2（user 可带 DOMAIN\\user）；pathInShare 相对共享根（无前导 /）。
 * 服务器串支持 host[:port]（libsmb2 原生解析，默认 445）。 */
#ifndef HRSMB_H
#define HRSMB_H

#include <QList>
#include <QMutex>
#include <QString>

namespace hr
{

struct SmbEntry
{
    QString name;
    bool isDir = false;
    long long size = 0;
    QString relPath;
};

class SmbClient
{
public:
    SmbClient(const QString &hostPort, const QString &share,
              const QString &user, const QString &pass);
    ~SmbClient();
    SmbClient(const SmbClient &) = delete;
    SmbClient &operator=(const SmbClient &) = delete;

    bool ok() const { return m_ok; }
    const QString &error() const { return m_err; }

    /* dirInShare 空串=共享根；目录优先排序。 */
    QList<SmbEntry> list(const QString &dirInShare, QString *err = nullptr);
    /* offset 起最多 maxLen 字节；网络/会话错误置 *err。 */
    QByteArray readRange(const QString &pathInShare, long long offset, int maxLen,
                         QString *err = nullptr);
    long long statSize(const QString &pathInShare, QString *err = nullptr);

private:
    mutable QMutex m_mu;
    void *m_ctx = nullptr; /* smb2_context* */
    bool m_ok = false;
    QString m_err;
};

} // namespace hr

#endif /* HRSMB_H */
