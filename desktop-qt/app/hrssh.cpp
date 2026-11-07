#include "hrssh.h"

#ifdef HR_HAVE_LIBSSH2
#include <libssh2.h>
#include <libssh2_sftp.h>
#endif

#include <QByteArray>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
typedef SOCKET hr_sock_t;
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
typedef int hr_sock_t;
#endif

#include <algorithm>
#include <cstring>
#include <cstdio>

namespace hr
{

#ifdef _WIN32
static void hr_sock_init()
{
    static bool once = [] {
        WSADATA d;
        return WSAStartup(MAKEWORD(2, 2), &d) == 0;
    }();
    (void)once;
}
static void hr_sock_close(qintptr s)
{
    if (s >= 0) closesocket((SOCKET)s);
}
#else
static void hr_sock_init() {}
static void hr_sock_close(qintptr s)
{
    if (s >= 0) ::close((int)s);
}
#endif

#ifdef HR_HAVE_LIBSSH2
static bool hr_libssh2_global()
{
    static bool once = libssh2_init(0) == 0;
    return once;
}
static QString sftpErr(LIBSSH2_SESSION *s)
{
    char *e = nullptr;
    libssh2_session_last_error(s, &e, nullptr, 0);
    return QString(e ? e : "sftp error");
}
/* 归一化绝对路径：空=家目录；去尾 /（根除外）。 */
static QString sftpNorm(const QString &p)
{
    QString d = p.trimmed();
    if (d.isEmpty()) return QString(".");
    while (d.size() > 1 && d.endsWith('/')) d.chop(1);
    return d;
}
#endif

SshClient::SshClient(const QString &host, int port, const QString &user, const QString &pass)
{
#ifdef HR_HAVE_LIBSSH2
    hr_sock_init();
    if (!hr_libssh2_global())
    {
        m_err = "libssh2 init failed";
        return;
    }
    /* 纯 socket 连接（QTcpSocket 析构会关 fd，与 libssh2 会话生命周期解耦） */
    struct addrinfo hints;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo *res = nullptr;
    const QByteArray hb = host.toUtf8();
    const QByteArray pb = QByteArray::number(port > 0 ? port : 22);
    if (getaddrinfo(hb.constData(), pb.constData(), &hints, &res) != 0 || !res)
    {
        m_err = "getaddrinfo failed: " + host;
        return;
    }
    for (struct addrinfo *ai = res; ai; ai = ai->ai_next)
    {
        m_sock = (qintptr)::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (m_sock < 0) continue;
        if (::connect((hr_sock_t)m_sock, ai->ai_addr, (int)ai->ai_addrlen) == 0) break;
        hr_sock_close(m_sock);
        m_sock = -1;
    }
    freeaddrinfo(res);
    if (m_sock < 0)
    {
        m_err = "tcp connect failed: " + host + ":" + pb;
        return;
    }
    m_sess = libssh2_session_init();
    if (!m_sess)
    {
        m_err = "session init failed";
        return;
    }
    libssh2_session_set_blocking((LIBSSH2_SESSION *)m_sess, 1);
    if (getenv("HR_SSH_TRACE"))
        libssh2_trace((LIBSSH2_SESSION *)m_sess, 0xffff);
    libssh2_session_set_timeout((LIBSSH2_SESSION *)m_sess, 30000);
    if (libssh2_session_handshake((LIBSSH2_SESSION *)m_sess, (libssh2_socket_t)m_sock) != 0)
    {
        m_err = "handshake: " + sftpErr((LIBSSH2_SESSION *)m_sess);
        return;
    }
    /* 主机指纹全信任（PromiscuousVerifier 同口径）：不做校验 */
    const QByteArray ub = user.toUtf8(), pw = pass.toUtf8();
    if (libssh2_userauth_password((LIBSSH2_SESSION *)m_sess, ub.constData(), pw.constData()) != 0)
    {
        m_err = "auth failed: " + sftpErr((LIBSSH2_SESSION *)m_sess);
        return;
    }
    m_sftp = libssh2_sftp_init((LIBSSH2_SESSION *)m_sess);
    if (!m_sftp)
    {
        m_err = "sftp init failed";
        return;
    }
    m_ok = true;
#else
    (void)host; (void)port; (void)user; (void)pass;
    m_err = "built without SFTP (no libssh2)";
#endif
}

SshClient::~SshClient()
{
#ifdef HR_HAVE_LIBSSH2
    if (m_sftp) libssh2_sftp_shutdown((LIBSSH2_SFTP *)m_sftp);
    if (m_sess)
    {
        libssh2_session_disconnect((LIBSSH2_SESSION *)m_sess, "HowRead bye");
        libssh2_session_free((LIBSSH2_SESSION *)m_sess);
    }
#endif
    hr_sock_close(m_sock);
}

QList<SshEntry> SshClient::list(const QString &absDir, QString *err)
{
    QList<SshEntry> out;
#ifdef HR_HAVE_LIBSSH2
    m_mu.lock();
    if (!m_ok)
    {
        if (err) *err = m_err;
        m_mu.unlock();
        return out;
    }
    const QString dir = sftpNorm(absDir);
    const QByteArray db = dir.toUtf8();
    LIBSSH2_SFTP_HANDLE *h =
        libssh2_sftp_open_ex((LIBSSH2_SFTP *)m_sftp, db.constData(), (size_t)db.size(),
                             0, 0, LIBSSH2_SFTP_OPENDIR);
    if (!h && dir != "/" && !dir.endsWith('/'))
    {
        /* 双尝试消歧（SftpClient.withAlt 同思路）：补尾 / 再试一次 */
        const QByteArray db2 = (dir + "/").toUtf8();
        h = libssh2_sftp_open_ex((LIBSSH2_SFTP *)m_sftp, db2.constData(), (size_t)db2.size(),
                                 0, 0, LIBSSH2_SFTP_OPENDIR);
    }
    if (!h)
    {
        if (err) *err = sftpErr((LIBSSH2_SESSION *)m_sess);
        m_mu.unlock();
        return out;
    }
    const QString base = (dir == "/") ? QString() : dir;
    char name[512];
    for (;;)
    {
        char longname[512];
        LIBSSH2_SFTP_ATTRIBUTES attrs;
        memset(&attrs, 0, sizeof attrs);
        int n = libssh2_sftp_readdir_ex(h, name, sizeof name, longname, sizeof longname, &attrs);
        if (n <= 0) break;
        if (!strcmp(name, ".") || !strcmp(name, "..")) continue;
        SshEntry e;
        e.name = QString::fromUtf8(name);
        e.isDir = LIBSSH2_SFTP_S_ISDIR(attrs.permissions) != 0;
        e.size = (long long)attrs.filesize;
        e.relPath = base + "/" + e.name;
        out.append(e);
    }
    libssh2_sftp_closedir(h);
    std::stable_sort(out.begin(), out.end(),
                     [](const SshEntry &a, const SshEntry &b) { return a.isDir > b.isDir; });
    m_mu.unlock();
#else
    (void)absDir;
    if (err) *err = m_err;
#endif
    return out;
}

QByteArray SshClient::readRange(const QString &absPath, long long offset, int maxLen, QString *err)
{
#ifdef HR_HAVE_LIBSSH2
    m_mu.lock();
    if (!m_ok || maxLen <= 0 || offset < 0)
    {
        if (err && !m_ok) *err = m_err;
        m_mu.unlock();
        return QByteArray();
    }
    const QByteArray pb = sftpNorm(absPath).toUtf8();
    LIBSSH2_SFTP_HANDLE *h =
        libssh2_sftp_open_ex((LIBSSH2_SFTP *)m_sftp, pb.constData(), (size_t)pb.size(),
                             LIBSSH2_FXF_READ, 0, LIBSSH2_SFTP_OPENFILE);
    if (!h)
    {
        if (err) *err = sftpErr((LIBSSH2_SESSION *)m_sess);
        m_mu.unlock();
        return QByteArray();
    }
    libssh2_sftp_seek64(h, (libssh2_uint64_t)offset);
    QByteArray out;
    out.reserve(maxLen);
    char buf[32768];
    while (out.size() < maxLen)
    {
        int want = qMin((int)sizeof buf, maxLen - out.size());
        int n = libssh2_sftp_read(h, buf, (size_t)want);
        if (n <= 0) break;
        out.append(buf, n);
    }
    libssh2_sftp_close(h);
    m_mu.unlock();
    return out;
#else
    (void)absPath; (void)offset; (void)maxLen;
    if (err) *err = m_err;
    return QByteArray();
#endif
}

long long SshClient::statSize(const QString &absPath, QString *err)
{
#ifdef HR_HAVE_LIBSSH2
    m_mu.lock();
    if (!m_ok)
    {
        if (err) *err = m_err;
        m_mu.unlock();
        return -1;
    }
    const QByteArray pb = sftpNorm(absPath).toUtf8();
    LIBSSH2_SFTP_ATTRIBUTES attrs;
    memset(&attrs, 0, sizeof attrs);
    if (libssh2_sftp_stat_ex((LIBSSH2_SFTP *)m_sftp, pb.constData(), (size_t)pb.size(),
                             LIBSSH2_SFTP_STAT, &attrs) != 0)
    {
        if (err) *err = sftpErr((LIBSSH2_SESSION *)m_sess);
        m_mu.unlock();
        return -1;
    }
    m_mu.unlock();
    return (long long)attrs.filesize;
#else
    (void)absPath;
    if (err) *err = m_err;
    return -1;
#endif
}

} // namespace hr
