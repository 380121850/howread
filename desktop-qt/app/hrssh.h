/* v0.60 批次二：SFTP 客户端（libssh2/WinCNG，免 OpenSSL；Java SftpClient/sshj 同语义面）。
 * 主机指纹全信任（PromiscuousVerifier 对齐：与安卓首连信任一致，后续可存指纹）。
 * 会话非线程安全——内部互斥串行（块缓存前台读与后台预取并发调用同一会话）。 */
#ifndef HRSSH_H
#define HRSSH_H

#include <QList>
#include <QMutex>
#include <QString>

namespace hr
{

struct SshEntry
{
    QString name;
    bool isDir = false;
    long long size = 0;
    QString relPath;
};

class SshClient
{
public:
    SshClient(const QString &host, int port, const QString &user, const QString &pass);
    ~SshClient();
    SshClient(const SshClient &) = delete;
    SshClient &operator=(const SshClient &) = delete;

    bool ok() const { return m_ok; }
    const QString &error() const { return m_err; }

    /* absDir 空串=家目录（"."，sshj 同语义）；目录优先排序。 */
    QList<SshEntry> list(const QString &absDir, QString *err = nullptr);
    /* offset 起最多 maxLen 字节；网络/会话错误置 *err（内容空）。 */
    QByteArray readRange(const QString &absPath, long long offset, int maxLen, QString *err = nullptr);
    long long statSize(const QString &absPath, QString *err = nullptr);

private:
    mutable QMutex m_mu;
    void *m_sess = nullptr; /* LIBSSH2_SESSION* */
    void *m_sftp = nullptr; /* LIBSSH2_SFTP* */
    qintptr m_sock = -1;
    bool m_ok = false;
    QString m_err;
};

} // namespace hr

#endif /* HRSSH_H */
