#include "hrdict.h"
#include "hrpaths.h"

#include <QByteArray>
#include <QCryptographicHash>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QMutex>
#include <QRegularExpression>
#include <QStringList>

#include <zlib.h>

#if QT_VERSION >= QT_VERSION_CHECK(6, 0, 0)
#include <QStringDecoder>
#else
#include <QTextCodec>
#endif

namespace hr
{

/* gzip（.idx.gz/.dict.dz 同为合法 gzip 流）→ 原始内容（zlib raw inflate） */
static QByteArray gunzip(const QByteArray &gz)
{
    if (gz.size() < 18) return QByteArray();
    const unsigned char *b = (const unsigned char *)gz.constData();
    if (b[0] != 0x1f || b[1] != 0x8b || b[2] != 8) return QByteArray();
    const unsigned char flg = b[3];
    int p = 10;
    if (flg & 4)
    {
        if (p + 2 > gz.size()) return QByteArray();
        const int xlen = b[p] | (b[p + 1] << 8);
        p += 2 + xlen;
    }
    if (flg & 8)
    {
        while (p < gz.size() && gz[p] != 0) p++;
        p++;
    }
    if (flg & 16)
    {
        while (p < gz.size() && gz[p] != 0) p++;
        p++;
    }
    if (flg & 2) p += 2;
    if (p >= gz.size()) return QByteArray();
    z_stream zs;
    memset(&zs, 0, sizeof zs);
    if (inflateInit2(&zs, -15) != Z_OK) return QByteArray();
    QByteArray out;
    out.reserve(qMax(gz.size() * 4, 1 << 16));
    zs.next_in = (Bytef *)(gz.constData() + p);
    zs.avail_in = (uInt)(gz.size() - p);
    char chunk[65536];
    int rc = Z_OK;
    for (;;)
    {
        zs.next_out = (Bytef *)chunk;
        zs.avail_out = sizeof chunk;
        rc = inflate(&zs, Z_NO_FLUSH);
        const int made = (int)sizeof chunk - (int)zs.avail_out;
        if (made > 0) out.append(chunk, made);
        if (rc != Z_OK || made == 0) break;
    }
    inflateEnd(&zs);
    if (rc != Z_STREAM_END) return QByteArray();
    return out;
}

static QString decodeGbk(const QByteArray &b)
{
#if QT_VERSION >= QT_VERSION_CHECK(6, 0, 0)
    auto dec = QStringDecoder("GBK");
    return dec(b);
#else
    const QTextCodec *tc = QTextCodec::codecForName("GBK");
    return tc ? tc->toUnicode(b) : QString();
#endif
}

static QString cleanArticle(const QString &s)
{
    QString t = s;
    t.remove(QRegularExpression("<[^>]*>"));
    return t.simplified();
}

class StarDict
{
public:
    explicit StarDict(const QString &ifoPath) : m_ifo(ifoPath)
    {
        loadIfo();
        loadIndex();
        resolveDictFile();
    }
    bool usable() const { return !m_index.isEmpty() && !m_data.isEmpty(); }
    QString name() const { return m_name; }
    QString lookup(const QString &word) const
    {
        QPair<long long, long long> hit = m_index.value(word, { -1, -1 });
        if (hit.first < 0)
        {
            for (auto it = m_index.constBegin(); it != m_index.constEnd(); ++it)
            {
                if (QString::compare(it.key(), word, Qt::CaseInsensitive) == 0)
                {
                    hit = it.value();
                    break;
                }
            }
        }
        if (hit.first < 0 || hit.second <= 0 || hit.second > 4000000) return QString();
        if (hit.first + hit.second > m_data.size()) return QString();
        const QByteArray b = m_data.mid((int)hit.first, (int)hit.second);
        if (m_sts == "x" || m_sts == "h") return cleanArticle(QString::fromUtf8(b));
        if (m_sts == "t" || m_sts == "y" || m_sts == "k" || m_sts == "w")
            return QString::fromUtf8(b).trimmed();
        const QString utf = QString::fromUtf8(b);
        if (utf.contains(QChar(0xFFFD))) return cleanArticle(decodeGbk(b));
        return cleanArticle(utf);
    }

private:
    void loadIfo()
    {
        QFile f(m_ifo);
        if (!f.open(QIODevice::ReadOnly)) return;
        const QStringList lines = QString::fromUtf8(f.readAll()).split('\n');
        for (const QString &l : lines)
        {
            if (l.startsWith("bookname="))
                m_name = l.mid(9).trimmed();
            else if (l.startsWith("sametypesequence="))
                m_sts = l.mid(17).trimmed();
        }
        if (m_name.isEmpty()) m_name = QFileInfo(m_ifo).completeBaseName();
    }
    void loadIndex()
    {
        const QFileInfo fi(m_ifo);
        const QString stem = fi.absolutePath() + "/" + fi.completeBaseName();
        QByteArray bytes;
        QFile gz(stem + ".idx.gz");
        if (gz.open(QIODevice::ReadOnly))
            bytes = gunzip(gz.readAll());
        else
        {
            QFile p(stem + ".idx");
            if (p.open(QIODevice::ReadOnly)) bytes = p.readAll();
        }
        int i = 0;
        const int n = bytes.size();
        const char *d = bytes.constData();
        while (i < n)
        {
            int z = i;
            while (z < n && d[z] != 0) z++;
            if (z >= n) break;
            if (z > i && z + 9 <= n)
            {
                const QString word = QString::fromUtf8(d + i, z - i);
                const long long off = ((unsigned char)d[z + 1] << 24) |
                                      ((unsigned char)d[z + 2] << 16) |
                                      ((unsigned char)d[z + 3] << 8) | (unsigned char)d[z + 4];
                const long long sz = ((unsigned char)d[z + 5] << 24) |
                                     ((unsigned char)d[z + 6] << 16) |
                                     ((unsigned char)d[z + 7] << 8) | (unsigned char)d[z + 8];
                if (!m_index.contains(word)) m_index.insert(word, { off, sz });
            }
            i = z + 9;
        }
    }
    void resolveDictFile()
    {
        const QFileInfo fi(m_ifo);
        const QString stem = fi.absolutePath() + "/" + fi.completeBaseName();
        QFile plain(stem + ".dict");
        if (plain.open(QIODevice::ReadOnly))
        {
            m_data = plain.readAll();
            return;
        }
        QFile dz(stem + ".dict.dz");
        if (!dz.open(QIODevice::ReadOnly)) return;
        const QString key = QString::fromLatin1(
            QCryptographicHash::hash((dz.fileName() + "|" + QString::number(dz.size())).toUtf8(),
                                     QCryptographicHash::Md5)
                .toHex()
                .left(12));
        const QDir cache(dataDir() + "/cache/dicts");
        if (!cache.exists()) QDir().mkpath(cache.absolutePath());
        const QString outPath = cache.filePath(key + ".dict");
        QFile out(outPath);
        if (out.open(QIODevice::ReadOnly))
        {
            m_data = out.readAll();
            return;
        }
        m_data = gunzip(dz.readAll());
        if (!m_data.isEmpty())
        {
            QFile w2(outPath);
            if (w2.open(QIODevice::WriteOnly | QIODevice::Truncate)) w2.write(m_data);
        }
    }

    QString m_ifo, m_name, m_sts;
    QHash<QString, QPair<long long, long long>> m_index;
    QByteArray m_data;
};

static QMutex g_mu;
static QList<StarDict *> g_dicts;

QString Dicts::dictDir()
{
    const QDir d(dataDir() + "/dicts");
    if (!d.exists()) QDir().mkpath(d.absolutePath());
    return d.absolutePath();
}

void Dicts::refresh()
{
    g_mu.lock();
    for (StarDict *d : g_dicts) delete d;
    g_dicts.clear();
    QDirIterator it(dictDir(), QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext())
    {
        const QString p = it.next();
        if (!p.endsWith(".ifo", Qt::CaseInsensitive)) continue;
        const QString rel = QDir(dictDir()).relativeFilePath(p);
        if (rel.count('/') >= 3) continue; /* ≤3 层 */
        StarDict *sd = new StarDict(p);
        if (sd->usable()) g_dicts.append(sd);
        else delete sd;
    }
    g_mu.unlock();
}

int Dicts::installedCount()
{
    g_mu.lock();
    const int n = g_dicts.size();
    g_mu.unlock();
    return n;
}

QList<DictHit> Dicts::lookupAll(const QString &word)
{
    QList<DictHit> out;
    const QString w = word.trimmed();
    if (w.isEmpty()) return out;
    g_mu.lock();
    for (StarDict *d : g_dicts)
    {
        const QString t = d->lookup(w);
        if (!t.trimmed().isEmpty()) out.append({ d->name(), t });
    }
    g_mu.unlock();
    return out;
}

} // namespace hr
