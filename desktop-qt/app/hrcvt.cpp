#include "hrcvt.h"
#include "hr_bridge.h"

#include <windows.h> /* P1：GB18030 MultiByteToWideChar */
#include "hrpaths.h"

#include <QCryptographicHash>
#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMutex>
#include <string> /* P1：std::wstring */
#include <QMutexLocker>
#include <QProcess>
#include <algorithm> /* v0.104.0：convCacheTrim sort */
#include <QSettings> /* v0.104.0：convCacheMB */

namespace hr
{

static QString cvtCacheDir()
{
    const QDir d(dataDir() + "/cache/convert");
    if (!d.exists()) QDir().mkpath(d.absolutePath());
    return d.absolutePath();
}

/* 缓存键 = 绝对路径#大小#mtime 的 MD5 前 12 位（Java 版 hashCode 同思路，本版自洽即可） */
static QString cvtKey(const QString &src)
{
    const QFileInfo fi(src);
    const QString seed = fi.absoluteFilePath() + "#" + QString::number(fi.size()) + "#" +
                         QString::number(fi.lastModified().toMSecsSinceEpoch());
    return QString::fromLatin1(
        QCryptographicHash::hash(seed.toUtf8(), QCryptographicHash::Md5).toHex().left(12));
}

/* mobitool 定位：发行包 exe 同级 tools/ / exe 同级 / 开发树 prebuilt/ / 工作目录 tools/ */
static QString findMobitool()
{
    const QString exe = QString::fromLatin1("mobitool.exe");
    const QDir appDir = QCoreApplication::applicationDirPath();
    const QStringList cands = {
        appDir.filePath(QString::fromLatin1("tools/") + exe),
        appDir.filePath(exe),
        QDir(appDir.filePath(QString::fromLatin1("../..")))
            .filePath(QString::fromLatin1("prebuilt/windows/x86_64/tools/") + exe),
        QDir::current().filePath(QString::fromLatin1("tools/") + exe),
    };
    for (const QString &c : cands)
        if (QFileInfo(c).isFile()) return c;
    return QString();
}

/* azw3(KF8) / >8MB KF7 → mobitool 转 EPUB（Azw3Convert.convertedEpub 同语义；失败回落 null） */
static QString azw3Convert(const QString &src, bool force)
{
    static QMutex mu;
    QMutexLocker lock(&mu); /* Java 版无锁，此处加锁避免封面/打开双转换竞态写坏同目录 */
    const QFileInfo fi(src);
    const QString outDir = cvtCacheDir() + QString::fromLatin1("/azw3-") + cvtKey(src);
    const QString epub = outDir + QString::fromLatin1("/") + fi.completeBaseName() +
                         QString::fromLatin1(".epub");
    if (!force && QFileInfo(epub).isFile() && QFileInfo(epub).size() > 0) return epub;
    const QString tool = findMobitool();
    if (tool.isEmpty())
    {
        evt(QString::fromLatin1("azw3_no_tool"));
        return QString();
    }
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    QDir().mkpath(outDir);
    QProcess p;
    p.start(tool, QStringList() << QString::fromLatin1("-e") << QString::fromLatin1("-o")
                                << outDir << QDir::toNativeSeparators(fi.absoluteFilePath()));
    if (!p.waitForStarted(-1) || !p.waitForFinished(120000))
    {
        p.kill();
        QDir(outDir).removeRecursively();
        evt(QString::fromLatin1("azw3_convert_fail"), QString::fromLatin1("timeout/start"));
        return QString();
    }
    const int rc = p.exitCode();
    const QString outTxt = QString::fromLocal8Bit(p.readAllStandardOutput() +
                                                  p.readAllStandardError());
    if (rc != 0 || !QFileInfo(epub).isFile() || QFileInfo(epub).size() <= 0)
    {
        QDir(outDir).removeRecursively();
        evt(QString::fromLatin1("azw3_convert_fail"),
            QString::fromLatin1("rc=%1 %2").arg(rc).arg(outTxt.left(160)));
        return QString();
    }
    evt(QString::fromLatin1("azw3_convert"),
        QString::fromLatin1("%1ms size=%2")
            .arg(QDateTime::currentMSecsSinceEpoch() - t0)
            .arg(QFileInfo(epub).size()));
    return epub;
}

/* doc → DocBook XML（hr_qt_doc_to_html，antiword 编入 dll）包 HTML（DocConvert 同语义） */
static QString docConvert(const QString &src, bool force)
{
    static QMutex mu;
    QMutexLocker lock(&mu); /* DocConvert.@Synchronized 同款 */
    const QFileInfo fi(src);
    const QString key = cvtKey(src);
    const QString out = cvtCacheDir() + QString::fromLatin1("/doc-") + key +
                        QString::fromLatin1(".html");
    if (!force && QFileInfo(out).isFile() && QFileInfo(out).size() > 0) return out;
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    const QString tmp = cvtCacheDir() + QString::fromLatin1("/doc-") + key +
                        QString::fromLatin1(".xml");
    QFile::remove(tmp);
    const QByteArray inU = fi.absoluteFilePath().toUtf8();
    const QByteArray outU = tmp.toUtf8();
    const int rc = hr_qt_doc_to_html(inU.constData(), outU.constData());
    QString body;
    if (rc == 1)
    {
        QFile f(tmp);
        if (f.open(QIODevice::ReadOnly)) body = QString::fromUtf8(f.readAll());
        f.close(); /* 先关再删：Windows 下打开句柄未释放时 remove 静默失败 */
        QFile::remove(tmp);
    }
    if (body.trimmed().isEmpty())
    {
        evt(QString::fromLatin1("doc_convert_fail"), QString::fromLatin1("ext=doc rc=%1").arg(rc));
        return QString();
    }
    const QString html = QString::fromLatin1(
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>") + body +
        QString::fromLatin1("</body></html>");
    QFile o(out);
    if (!o.open(QIODevice::WriteOnly | QIODevice::Truncate)) return QString();
    o.write(html.toUtf8());
    o.close();
    evt(QString::fromLatin1("doc_convert_ok"),
        QString::fromLatin1("ext=doc ms=%1 size=%2")
            .arg(QDateTime::currentMSecsSinceEpoch() - t0)
            .arg(QFileInfo(out).size()));
    return out;
}

/* docx/odt/rtf → HTML（hr_qt_office 三转换，片段包 HTML；批次三 v0.61.0） */
static QString officeConvert(const QString &src, const QString &kind, bool force)
{
    static QMutex mu;
    QMutexLocker lock(&mu);
    const QFileInfo fi(src);
    const QString key = cvtKey(src);
    const QString out = cvtCacheDir() + QString::fromLatin1("/") + kind +
                        QString::fromLatin1("-") + key + QString::fromLatin1(".html");
    if (!force && QFileInfo(out).isFile() && QFileInfo(out).size() > 0) return out;
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    const QString tmp = cvtCacheDir() + QString::fromLatin1("/") + kind +
                        QString::fromLatin1("-") + key + QString::fromLatin1(".frag");
    QFile::remove(tmp);
    const QByteArray inU = fi.absoluteFilePath().toUtf8();
    const QByteArray outU = tmp.toUtf8();
    int rc = 0;
    if (kind == QString::fromLatin1("docx"))
        rc = hr_qt_docx_to_html(inU.constData(), outU.constData());
    else if (kind == QString::fromLatin1("odt"))
        rc = hr_qt_odt_to_html(inU.constData(), outU.constData());
    else
        rc = hr_qt_rtf_to_html(inU.constData(), outU.constData());
    QString body;
    if (rc == 1)
    {
        QFile f(tmp);
        if (f.open(QIODevice::ReadOnly)) body = QString::fromUtf8(f.readAll());
        f.close(); /* 先关再删：Windows 下打开句柄未释放时 remove 静默失败 */
        QFile::remove(tmp);
    }
    if (body.trimmed().isEmpty())
    {
        evt(QString::fromLatin1("office_convert_fail"),
            QString::fromLatin1("kind=%1 rc=%2").arg(kind).arg(rc));
        return QString();
    }
    const QString html = QString::fromLatin1(
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>") + body +
    QString::fromLatin1("</body></html>");
    QFile o(out);
    if (!o.open(QIODevice::WriteOnly | QIODevice::Truncate)) return QString();
    o.write(html.toUtf8());
    o.close();
    evt(QString::fromLatin1("office_convert_ok"),
        QString::fromLatin1("kind=%1 ms=%2 size=%3")
            .arg(kind).arg(QDateTime::currentMSecsSinceEpoch() - t0).arg(QFileInfo(out).size()));
    return out;
}

/* P1 适配（B1）：TXT 编码——BOM/UTF-8 校验/GB18030 转码副本（对齐 WIN TxtDecode） */
static bool txtLooksUtf8(const QByteArray &b)
{
    int i = 0;
    const int n = b.size();
    while (i < n)
    {
        const unsigned char c = (unsigned char)b[i];
        int len = 0;
        if (c < 0x80) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else return false;
        if (i + len > n) return false;
        for (int k = 1; k < len; k++)
            if (((unsigned char)b[i + k] & 0xC0) != 0x80) return false;
        i += len;
    }
    return true;
}

static QString txtConvert(const QString &path, bool force)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return QString();
    const QByteArray all = f.readAll();
    f.close();
    QString cs;
    if (all.startsWith("\xFF\xFE") || all.startsWith("\xFE\xFF"))
        cs = QString::fromLatin1("utf-16");
    else if (txtLooksUtf8(all))
        return path; /* UTF-8（含 BOM）：引擎直读 */
    else
        cs = QString::fromLatin1("gb18030");

    const QString key = QString::fromLatin1(
        QCryptographicHash::hash(
            (path + "#" + QString::number(QFileInfo(path).size()) + "#" +
             QString::number(QFileInfo(path).lastModified().toMSecsSinceEpoch()))
                .toUtf8(),
            QCryptographicHash::Md5)
            .toHex());
    const QString dir = hr::dataDir() + QString::fromLatin1("/cache/txt");
    QDir().mkpath(dir);
    const QString dest = dir + "/" + key.left(12) + ".txt";
    if (!force && QFileInfo::exists(dest)) return dest;

    QString text;
    if (cs == QString::fromLatin1("utf-16"))
    {
        const int off = (all.size() >= 2) ? 2 : 0; /* 跳 BOM（LE 假定） */
        text = QString::fromUtf16(reinterpret_cast<const ushort *>(all.constData() + off),
                                  (all.size() - off) / 2);
    }
    else
    {
        /* GB18030（54936，向下兼容 GBK/GB2312）→ UTF-16 */
        std::wstring ws;
        const int src = all.size();
        if (src > 0)
        {
            const int need = MultiByteToWideChar(54936, 0, all.constData(), src, nullptr, 0);
            if (need > 0)
            {
                ws.resize(need);
                MultiByteToWideChar(54936, 0, all.constData(), src, &ws[0], need);
            }
        }
        text = QString::fromStdWString(ws);
    }
    QFile of(dest);
    if (!of.open(QIODevice::WriteOnly | QIODevice::Truncate)) return QString();
    of.write(text.toUtf8());
    of.close();
    evt("txt_convert", QString("cs=%1 size=%2").arg(cs).arg(all.size()));
    return dest;
}

/* v0.104.0（P2-11）：转换缓存总量上限（convert+txt+zip 三处；默认 500MB，cache/convCapMB=0 关闭）。
 * v0.106.0（P0-3）：+cache/cbr（unrar 解包图）纳入同一上限。
 * v0.107.0（P2-8）：+cache/covers（封面可按键重建，删除安全）。
 * 按目录项 mtime LRU 删最旧直至回到限额；正被占用文件 Windows 下删除失败自然跳过。 */
static qint64 treeSize(const QString &path)
{
    const QFileInfo fi(path);
    if (!fi.exists()) return 0;
    if (fi.isFile()) return fi.size();
    qint64 t = 0;
    const QFileInfoList fs =
        QDir(path).entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot);
    for (const QFileInfo &f : fs) t += treeSize(f.absoluteFilePath());
    return t;
}

static void convCacheTrim()
{
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    const QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"), QSettings::IniFormat);
    const long long capMB = st.value(QString::fromLatin1("cache/convCapMB"), 500).toLongLong();
    if (capMB <= 0) return;
    const qint64 cap = capMB * 1024 * 1024;
    const QString roots[] = {
        dataDir() + QString::fromLatin1("/cache/convert"),
        dataDir() + QString::fromLatin1("/cache/txt"),
        dataDir() + QString::fromLatin1("/cache/zip"),
        dataDir() + QString::fromLatin1("/cache/cbr"),
        dataDir() + QString::fromLatin1("/cache/covers"),
    };
    QList<QPair<qint64, QFileInfo>> items; /* <mtime, 项> */
    qint64 total = 0;
    for (int i = 0; i < 5; i++)
    {
        const QDir d(roots[i]);
        if (!d.exists()) continue;
        const QFileInfoList fs = d.entryInfoList(QDir::Files | QDir::Dirs | QDir::NoDotAndDotDot);
        for (const QFileInfo &f : fs)
        {
            const qint64 sz = treeSize(f.absoluteFilePath());
            total += sz;
            items.append(qMakePair(f.lastModified().toMSecsSinceEpoch(), f));
        }
    }
    evt(QString::fromLatin1("conv_trim"),
        QString::fromLatin1("cap=%1 total=%2 items=%3")
            .arg(capMB).arg(total).arg(items.size()));
    if (total <= cap)
    {
        evt(QString::fromLatin1("conv_trim_ms"),
            QString::fromLatin1("ms=%1 items=%2 over=0")
                .arg(QDateTime::currentMSecsSinceEpoch() - t0)
                .arg(items.size()));
        return;
    }
    std::sort(items.begin(), items.end(),
              [](const QPair<qint64, QFileInfo> &a, const QPair<qint64, QFileInfo> &b)
              { return a.first < b.first; }); /* 最旧先删 */
    int del = 0;
    for (const auto &p : items)
    {
        if (total <= cap) break;
        const QString fp = p.second.absoluteFilePath();
        const qint64 sz = treeSize(fp);
        const bool gone = p.second.isDir() ? QDir(fp).removeRecursively()
                                           : QFile::remove(fp);
        if (gone)
        {
            total -= sz;
            del++;
        }
    }
    evt(QString::fromLatin1("conv_trim_ms"),
        QString::fromLatin1("ms=%1 items=%2 over=%3 del=%4")
            .arg(QDateTime::currentMSecsSinceEpoch() - t0)
            .arg(items.size())
            .arg(total > cap ? 1 : 0)
            .arg(del));
    if (del > 0)
        evt(QString::fromLatin1("conv_trim_del"), QString::fromLatin1("n=%1").arg(del));
}

/* ---------- v0.105.0（P1-5）：MHT/MHTML → HTML（MIME 分段解码；图片 base64 内嵌
 * data URI——引擎独立 HTML 只认 data URI 图片，html-parse.c:471）。字符集走
 * MultiByteToWideChar 映射（utf-8/gb18030/big5/shift_jis/euc-kr/1252）。 ---------- */

static int hexv2(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static UINT codepageFor(const QByteArray &cs)
{
    const QByteArray c = cs.toLower();
    if (c.contains("gb") || c.contains("936") || c.contains("2312")) return 54936;
    if (c.contains("big5") || c.contains("950")) return 950;
    if (c.contains("shift") || c.contains("sjis") || c.contains("932")) return 932;
    if (c.contains("euc-kr") || c.contains("949") || c.contains("ksc")) return 949;
    if (c.contains("1252") || c.contains("8859-1") || c.contains("latin")) return 1252;
    if (c.contains("1251") || c.contains("koi8")) return 1251;
    return 65001; /* utf-8/未知 */
}

/* quoted-printable 解码（=XX 十六进制；行尾孤立 = 软换行） */
/* v0.105.0: MXE Qt5 QByteArray::startsWith 无 CaseSensitivity 重载 */
static bool ciStartsWith(const QByteArray &s, const char *prefix)
{
    const int n = qstrlen(prefix);
    return s.size() >= n && s.left(n).toLower() == QByteArray(prefix).toLower();
}

static QByteArray qpDecode(const QByteArray &in)
{
    QByteArray out;
    out.reserve(in.size());
    for (int i = 0; i < in.size(); ++i)
    {
        const char c = in[i];
        if (c == '=')
        {
            if (i + 2 < in.size() && hexv2(in[i + 1]) >= 0 && hexv2(in[i + 2]) >= 0)
            {
                out += (char)(hexv2(in[i + 1]) * 16 + hexv2(in[i + 2]));
                i += 2;
            }
            else if (i + 2 < in.size() && in[i + 1] == '\r' && in[i + 2] == '\n')
                i += 2; /* =CRLF 软换行 */
            else if (i + 1 < in.size() && (in[i + 1] == '\n' || in[i + 1] == '\r'))
                i += 1; /* =CR/=LF 软换行 */
            else if (i == in.size() - 1)
                ; /* 末尾孤立 = 丢弃 */
            else
                out += c;
        }
        else
            out += c;
    }
    return out;
}

static void toUtf8Bytes(const QByteArray &body, const QByteArray &cs, QByteArray &out)
{
    const QByteArray c = cs.toLower();
    if (c.contains("utf-8") || c.contains("utf8") || c.isEmpty())
    {
        out = body;
        return;
    }
    const UINT cp = codepageFor(cs);
    if (cp == 65001)
    {
        out = body;
        return;
    }
    const int wn = MultiByteToWideChar(cp, 0, body.constData(), body.size(), NULL, 0);
    if (wn <= 0)
    {
        out = body;
        return;
    }
    std::wstring w((size_t)wn, L'\0');
    MultiByteToWideChar(cp, 0, body.constData(), body.size(), &w[0], wn);
    const int un = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), wn, NULL, 0, NULL, NULL);
    out.resize(un > 0 ? un : 0);
    if (un > 0) WideCharToMultiByte(CP_UTF8, 0, w.c_str(), wn, out.data(), un, NULL, NULL);
}

static QString mhtConvert(const QString &src, bool force)
{
    const QString key = cvtKey(src);
    const QString out = cvtCacheDir() + QString::fromLatin1("/mht-") + key +
                        QString::fromLatin1(".html");
    if (!force && QFileInfo(out).isFile() && QFileInfo(out).size() > 0) return out;
    QFile f(src);
    if (!f.open(QIODevice::ReadOnly)) return QString();
    const QByteArray all = f.readAll();
    f.close();
    if (all.isEmpty()) return QString();
    /* 边界：Content-Type 头里的 boundary="..." */
    QByteArray boundary;
    {
        const QByteArray head = all.left(qMin(all.size(), 8192));
        int b = head.indexOf("boundary=");
        if (b < 0) b = head.indexOf("Boundary=");
        if (b >= 0)
        {
            int s = b + 9;
            while (s < head.size() && (head[s] == '"' || head[s] == ' ')) s++;
            int e = s;
            while (e < head.size() && head[e] != '"' && head[e] != '\r' && head[e] != '\n' &&
                   head[e] != ';')
                e++;
            boundary = head.mid(s, e - s);
        }
    }
    if (boundary.isEmpty()) return QString();
    const QByteArray dash = "--" + boundary;
    struct MhtPart
    {
        QByteArray type, enc, loc, body;
    };
    QList<MhtPart> parts;
    int pos = all.indexOf(dash);
    while (pos >= 0)
    {
        int q = pos + dash.size();
        if (q + 1 < all.size() && all[q] == '-' && all[q + 1] == '-') break; /* 结束边界 */
        const int lineEnd = all.indexOf('\n', pos);
        if (lineEnd < 0) break;
        const int hdrBegin = lineEnd + 1;
        int bodyBegin = all.indexOf("\r\n\r\n", hdrBegin);
        if (bodyBegin < 0) bodyBegin = all.indexOf("\n\n", hdrBegin);
        if (bodyBegin < 0) break;
        int sep = 2;
        if (all[bodyBegin] == '\r') sep = 4;
        const QByteArray hdrs = all.mid(hdrBegin, bodyBegin - hdrBegin);
        const int next = all.indexOf(dash, bodyBegin + sep);
        int bodyEnd = next >= 0 ? next : all.size();
        while (bodyEnd > bodyBegin && (all[bodyEnd - 1] == '\n' || all[bodyEnd - 1] == '\r'))
            bodyEnd--;
        MhtPart part;
        const QList<QByteArray> lines = hdrs.split('\n');
        for (const QByteArray &ln0 : lines)
        {
            const QByteArray ln = ln0.trimmed();
            if (ciStartsWith(ln, "Content-Type:"))
                part.type = ln.mid(13).trimmed();
            else if (ciStartsWith(ln, "Content-Transfer-Encoding:"))
                part.enc = ln.mid(26).trimmed().toLower();
            else if (ciStartsWith(ln, "Content-Location:"))
                part.loc = ln.mid(17).trimmed();
        }
        part.body = all.mid(bodyBegin, bodyEnd - bodyBegin);
        parts.append(part);
        if (next < 0) break;
        pos = next;
    }
    QByteArray htmlRaw;
    struct MhtImg
    {
        QByteArray loc, mime, data;
    };
    QList<MhtImg> imgs;
    for (const MhtPart &p : parts)
    {
        QByteArray body = p.body;
        if (p.enc == "base64")
            body = QByteArray::fromBase64(body);
        else if (p.enc == "quoted-printable")
            body = qpDecode(body);
        if (ciStartsWith(p.type, "text/html") && htmlRaw.isEmpty())
            toUtf8Bytes(body, p.type, htmlRaw);
        else if (ciStartsWith(p.type, "image/") && !p.loc.isEmpty() &&
                 !body.isEmpty() && body.size() < 12 * 1024 * 1024)
        {
            MhtImg im;
            im.loc = p.loc;
            im.mime = p.type.left(p.type.indexOf(';')).trimmed();
            im.data = body;
            imgs.append(im);
        }
    }
    if (htmlRaw.isEmpty()) return QString();
    /* 图片内嵌：src 引用与 Content-Location 全串/尾段匹配则替换为 data URI */
    QString html = QString::fromUtf8(htmlRaw);
    for (const MhtImg &im : imgs)
    {
        const QString dataUri = QString::fromLatin1("data:") +
                                QString::fromLatin1(im.mime) +
                                QString::fromLatin1(";base64,") +
                                QString::fromLatin1(im.data.toBase64());
        const QString loc = QString::fromUtf8(im.loc);
        html.replace(loc, dataUri, Qt::CaseInsensitive);
        const int slash = qMax(loc.lastIndexOf('/'), loc.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < loc.size())
        {
            const QString base = loc.mid(slash + 1);
            if (!base.isEmpty()) html.replace(base, dataUri, Qt::CaseInsensitive);
        }
    }
    QFile o(out);
    if (!o.open(QIODevice::WriteOnly | QIODevice::Truncate)) return QString();
    o.write(html.toUtf8());
    o.close();
    evt(QString::fromLatin1("mht_convert"),
        QString::fromLatin1("parts=%1 imgs=%2 size=%3")
            .arg(parts.size()).arg(imgs.size()).arg(html.size()));
    return out;
}

QString openablePath(const QString &path, bool forceCacheMiss)
{
    const QString ext = QFileInfo(path).suffix().toLower();
    convCacheTrim(); /* v0.104.0：进转换前先收旧缓存 */
    QString out = path;
    const qint64 tcv0 = QDateTime::currentMSecsSinceEpoch();
    QString route; /* v0.107.1：卡顿定位——转换分路由计时 */
    /* P1 适配（B1）：TXT 编码检测（GBK/Big5/UTF-16 直开必乱码）；v0.103.0：md 同通道 */
    if (ext == QString::fromLatin1("txt") || ext == QString::fromLatin1("md"))
    {
        route = QString::fromLatin1("txt");
        const QString c = txtConvert(path, forceCacheMiss);
        if (!c.isEmpty()) out = c;
    }
    /* P2 适配（B2）：zip 包内首书解出（cache/zip/<key>/；对齐 WIN ZipBooks） */
    else if (ext == QString::fromLatin1("zip"))
    {
        route = QString::fromLatin1("zip");
        const QString dir = dataDir() + QString::fromLatin1("/cache/zip/") + cvtKey(path);
        if (forceCacheMiss) QDir(dir).removeRecursively();
        QDir().mkpath(dir);
        char name[512] = {0};
        if (hr_qt_zip_first_book(
                path.toUtf8().constData(),
                "pdf,epub,mobi,azw,azw3,prc,fb2,txt,cbz,xps,html,htm,docx,doc,rtf,odt,djvu",
                dir.toUtf8().constData(), name, sizeof name) == 0 && name[0])
            out = dir + "/" + QString::fromUtf8(name);
    }
    /* v0.105.0（P1-5）：CBR → 解包全部条目到 <key>.ldir 目录（引擎 cbz 处理器认
     * "<key> 并生成父目录 <key>.ldir 容器 XML（安卓 FolderContext 同协议）交引擎直开；失败回退原路径 */
    else if (ext == QString::fromLatin1("cbr"))
    {
        route = QString::fromLatin1("cbr");
        const QString dir = dataDir() + QString::fromLatin1("/cache/cbr/") +
                            cvtKey(path);
        if (forceCacheMiss) QDir(dir).removeRecursively();
        if (!QFileInfo(dir).isDir() || QDir(dir).entryList(QDir::Files).isEmpty())
        {
            QDir().mkpath(dir);
            char nm[64] = {0};
            const int rrc = hr_qt_rar_extract(path.toUtf8().constData(),
                                              dir.toUtf8().constData(), nm, sizeof nm);
            if (rrc != 0)
                evt(QString::fromLatin1("cbr_extract_fail"),
                    QString::fromLatin1("path=%1 rc=%2 dir=%3")
                        .arg(path.left(80)).arg(rrc)
                        .arg(QString::fromLatin1(nm)));
        }
        const QStringList fl = QDir(dir).entryList(QDir::Files, QDir::Name);
        if (fl.isEmpty())
        {
            out = path;
        }
        else
        {
            /* .ldir 容器 XML（安卓 FolderContext.genarateXML 同协议）：置于图片目录的
             * 父目录，item path 为图片绝对路径；引擎 mucbz.c 见 ".ldir" 后缀即
             * 目录归档 + fz_read_file 逐页直读。 */
            QStringList imgs;
            for (const QString &n : fl)
            {
                const QString e2 = QFileInfo(n).suffix().toLower();
                if (e2 == QString::fromLatin1("png") ||
                    e2 == QString::fromLatin1("jpg") ||
                    e2 == QString::fromLatin1("jpeg") ||
                    e2 == QString::fromLatin1("gif") ||
                    e2 == QString::fromLatin1("bmp") ||
                    e2 == QString::fromLatin1("webp") ||
                    e2 == QString::fromLatin1("tif") ||
                    e2 == QString::fromLatin1("tiff"))
                    imgs << n;
            }
            const QString xml = dir + QString::fromLatin1(".ldir");
            QFile xf(xml);
            if (!imgs.isEmpty() && xf.open(QIODevice::WriteOnly | QIODevice::Truncate))
            {
                QByteArray xb;
                xb += "<container count=\"" + QByteArray::number(imgs.size()) + "\">\n";
                for (const QString &n : imgs)
                {
                    QString p = dir + QLatin1Char('/') + n;
                    p.replace(QLatin1Char('&'), QString::fromLatin1("&amp;"));
                    p.replace(QLatin1Char('<'), QString::fromLatin1("&lt;"));
                    p.replace(QLatin1Char('"'), QString::fromLatin1("&quot;"));
                    xb += "<item path=\"" + p.toUtf8() + "\" />\n";
                }
                xb += "</container>\n";
                xf.write(xb);
                xf.close();
                evt(QString::fromLatin1("cbr_ldir"),
                    QString::fromLatin1("n=%1").arg(imgs.size()));
                out = xml;
            }
            else
                out = path;
        }
    }
    else if (ext == QString::fromLatin1("mht") || ext == QString::fromLatin1("mhtml"))
    {
        route = QString::fromLatin1("mht");
        const QString c = mhtConvert(path, forceCacheMiss);
        if (!c.isEmpty()) out = c;
    }
    else if (ext == QString::fromLatin1("azw3"))
    {
        route = QString::fromLatin1("azw3");
        const QString c = azw3Convert(path, forceCacheMiss);
        if (!c.isEmpty()) out = c;
    }
    else if (ext == QString::fromLatin1("mobi") || ext == QString::fromLatin1("azw") ||
             ext == QString::fromLatin1("prc"))
    {
        route = QString::fromLatin1("mobi");
        /* v0.107.1：size() 读不到（W: 等云盘占位文件返回 0）也尝试转换——
         * 转换有缓存且失败回落直开，安全；否则大书被 size 未知卡在慢直开路径 */
        const qint64 msz = QFileInfo(path).size();
        if (msz > 8LL * 1024 * 1024 || msz == 0)
        {
            /* 大体积 KF7：htdoc 单章"整书排版才能出第一页"是大书天花板（v0.27.0 同款决策） */
            const QString c = azw3Convert(path, forceCacheMiss);
            if (!c.isEmpty()) out = c;
        }
    }
    else if (ext == QString::fromLatin1("doc"))
    {
        route = QString::fromLatin1("doc");
        QString c = docConvert(path, forceCacheMiss);
        if (c.isEmpty())
        {
            /* v0.105.0（P1-3）：doc 回退链（对齐安卓 DocContext：antiword 失败→按 RTF 解→TXT） */
            c = officeConvert(path, QString::fromLatin1("rtf"), forceCacheMiss);
            if (c.isEmpty()) c = txtConvert(path, forceCacheMiss);
            if (!c.isEmpty())
                evt(QString::fromLatin1("doc_fallback"),
                    QString::fromLatin1("path=%1").arg(path.left(80)));
        }
        if (!c.isEmpty()) out = c;
    }
    else if (ext == QString::fromLatin1("docx") || ext == QString::fromLatin1("odt") ||
             ext == QString::fromLatin1("rtf"))
    {
        route = QString::fromLatin1("office");
        const QString c = officeConvert(path, ext, forceCacheMiss);
        if (!c.isEmpty()) out = c;
    }
    if (!route.isEmpty())
        evt(QString::fromLatin1("conv_route"),
            QString::fromLatin1("fmt=%1 ms=%2 converted=%3")
                .arg(route)
                .arg(QDateTime::currentMSecsSinceEpoch() - tcv0)
                .arg(out == path ? 0 : 1));
    return out; /* v0.106.0：去掉落盘后二次裁剪（每开一本书只跑一轮） */
}

QString openablePath(const QString &path)
{
    return openablePath(path, false);
}

} // namespace hr
