#include "hrcred.h"

#ifdef Q_OS_WIN
#include <windows.h>
#include <wincrypt.h>
#endif

namespace hr
{

QString protectSecret(const QString &plain)
{
    if (plain.isEmpty()) return QString();
#ifdef Q_OS_WIN
    const std::wstring w = plain.toStdWString();
    DATA_BLOB in, out;
    in.pbData = (BYTE *)w.c_str();
    in.cbData = (DWORD)(w.size() * sizeof(wchar_t));
    if (CryptProtectData(&in, L"HowReadQt", NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &out))
    {
        const QByteArray b((const char *)out.pbData, (int)out.cbData);
        LocalFree(out.pbData);
        return QString::fromLatin1("dpapi:") + QString::fromLatin1(b.toBase64());
    }
    return plain; /* DPAPI 失败回落明文（不阻塞功能） */
#else
    return plain;
#endif
}

QString unprotectSecret(const QString &stored)
{
    if (!stored.startsWith(QString::fromLatin1("dpapi:"))) return stored; /* 明文兼容 */
#ifdef Q_OS_WIN
    const QByteArray b = QByteArray::fromBase64(stored.mid(6).toLatin1());
    if (!b.isEmpty())
    {
        DATA_BLOB in, out;
        in.pbData = (BYTE *)b.constData();
        in.cbData = (DWORD)b.size();
        if (CryptUnprotectData(&in, NULL, NULL, NULL, NULL, CRYPTPROTECT_UI_FORBIDDEN, &out))
        {
            const QString r = QString::fromWCharArray((const wchar_t *)out.pbData,
                                                      (int)(out.cbData / sizeof(wchar_t)));
            LocalFree(out.pbData);
            return r;
        }
    }
#endif
    return QString();
}

} // namespace hr
