#include "hrsmb.h"
#include "hrpaths.h"

#include <QMutexLocker>

#ifdef HR_HAVE_LIBSMB2
#define _WINDOWS /* libsmb2.h 走 winsock 分支（与 vendored 库 -D_WINDOWS 构建一致） */
#include <winsock2.h>
#include <ws2tcpip.h>
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>

#include <algorithm>

#ifndef O_RDONLY
#define O_RDONLY 0
#endif

namespace hr
{

static void hr_smb_winsock()
{
    static bool once = false;
    if (!once)
    {
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa); /* Qt 已拉起也无害；幂等 */
        once = true;
    }
}

static QString smbClean(const QString &p)
{
    QString s = p;
    while (s.startsWith(QLatin1Char('/'))) s.remove(0, 1);
    while (s.endsWith(QLatin1Char('/'))) s.chop(1);
    return s;
}

SmbClient::SmbClient(const QString &hostPort, const QString &share,
                     const QString &user, const QString &pass)
{
    hr_smb_winsock();
    struct smb2_context *ctx = smb2_init_context();
    if (!ctx)
    {
        m_err = QString::fromLatin1("smb2_init_context failed");
        return;
    }
    m_ctx = ctx;
    if (!user.isEmpty()) smb2_set_user(ctx, user.toUtf8().constData());
    if (!pass.isEmpty()) smb2_set_password(ctx, pass.toUtf8().constData());
    if (smb2_connect_share(ctx, hostPort.toUtf8().constData(),
                           smbClean(share).toUtf8().constData(), nullptr) != 0)
    {
        m_err = QString::fromUtf8(smb2_get_error(ctx));
        return;
    }
    m_ok = true;
}

SmbClient::~SmbClient()
{
    if (m_ctx)
    {
        smb2_disconnect_share((struct smb2_context *)m_ctx);
        smb2_destroy_context((struct smb2_context *)m_ctx);
        m_ctx = nullptr;
    }
}

QList<SmbEntry> SmbClient::list(const QString &dirInShare, QString *err)
{
    QList<SmbEntry> out;
    QMutexLocker l(&m_mu);
    if (!m_ok)
    {
        if (err) *err = m_err;
        return out;
    }
    struct smb2_context *ctx = (struct smb2_context *)m_ctx;
    const QByteArray dir = smbClean(dirInShare).toUtf8();
    struct smb2dir *d = smb2_opendir(ctx, dir.constData());
    if (!d && dir.isEmpty()) d = smb2_opendir(ctx, ".");
    if (!d)
    {
        if (err) *err = QString::fromUtf8(smb2_get_error(ctx));
        return out;
    }
    struct smb2dirent *e;
    const QString base = QString::fromUtf8(dir);
    while ((e = smb2_readdir(ctx, d)) != nullptr)
    {
        const QString nm = QString::fromUtf8(e->name);
        if (nm == QLatin1String(".") || nm == QLatin1String("..")) continue;
        SmbEntry en;
        en.name = nm;
        en.isDir = e->st.smb2_type != SMB2_TYPE_FILE;
        en.size = (long long)e->st.smb2_size;
        en.relPath = base.isEmpty() ? nm : base + QLatin1Char('/') + nm;
        out.append(en);
    }
    smb2_closedir(ctx, d);
    std::sort(out.begin(), out.end(), [](const SmbEntry &a, const SmbEntry &b)
    {
        return a.isDir != b.isDir ? a.isDir > b.isDir
                                  : a.name.compare(b.name, Qt::CaseInsensitive) < 0;
    });
    return out;
}

QByteArray SmbClient::readRange(const QString &pathInShare, long long offset, int maxLen,
                                QString *err)
{
    QByteArray out;
    QMutexLocker l(&m_mu);
    if (!m_ok)
    {
        if (err) *err = m_err;
        return out;
    }
    struct smb2_context *ctx = (struct smb2_context *)m_ctx;
    struct smb2fh *fh = smb2_open(ctx, smbClean(pathInShare).toUtf8().constData(),
                                  O_RDONLY);
    if (!fh)
    {
        if (err) *err = QString::fromUtf8(smb2_get_error(ctx));
        return out;
    }
    out.resize(maxLen);
    long long got = 0;
    while (got < maxLen)
    {
        const int r = smb2_pread(ctx, fh, (uint8_t *)(out.data() + got),
                                 (uint32_t)(maxLen - got), (uint64_t)(offset + got));
        if (r < 0)
        {
            if (err) *err = QString::fromUtf8(smb2_get_error(ctx));
            out.clear();
            smb2_close(ctx, fh);
            return out;
        }
        if (r == 0) break; /* EOF */
        got += r;
    }
    smb2_close(ctx, fh);
    out.resize((int)got);
    return out;
}

long long SmbClient::statSize(const QString &pathInShare, QString *err)
{
    QMutexLocker l(&m_mu);
    if (!m_ok)
    {
        if (err) *err = m_err;
        return -1;
    }
    struct smb2_context *ctx = (struct smb2_context *)m_ctx;
    struct smb2_stat_64 st;
    memset(&st, 0, sizeof(st));
    if (smb2_stat(ctx, smbClean(pathInShare).toUtf8().constData(), &st) != 0)
    {
        if (err) *err = QString::fromUtf8(smb2_get_error(ctx));
        return -1;
    }
    return (long long)st.smb2_size;
}

} // namespace hr

#else /* !HR_HAVE_LIBSMB2：本机 Qt6 通道缺库自动降级（对齐 hrssh.cpp 模式） */

namespace hr
{

SmbClient::SmbClient(const QString &, const QString &, const QString &, const QString &)
{
    m_err = QString::fromLatin1("SMB support not built (HR_HAVE_LIBSMB2)");
}
SmbClient::~SmbClient() = default;
QList<SmbEntry> SmbClient::list(const QString &, QString *err)
{
    if (err) *err = m_err;
    return {};
}
QByteArray SmbClient::readRange(const QString &, long long, int, QString *err)
{
    if (err) *err = m_err;
    return {};
}
long long SmbClient::statSize(const QString &, QString *err)
{
    if (err) *err = m_err;
    return -1;
}

} // namespace hr

#endif /* HR_HAVE_LIBSMB2 */
