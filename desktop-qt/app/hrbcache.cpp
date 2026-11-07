#include "hrbcache.h"
#include "hrpaths.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <algorithm> /* v0.105.0 */
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QThread>
#include <QWaitCondition>

namespace hr
{

class BlockCacheStore::Worker : public QThread
{
public:
    BlockCacheStore *s;
    explicit Worker(BlockCacheStore *st) : QThread(), s(st) {}
    void run() override { s->workerLoop(); }
};

BlockCacheStore::BlockCacheStore(const QString &key, long long size, int blockSize,
                                 Fetcher fetcher, long long memLimitBytes, int fillThreads)
    : m_size(size), m_blockSize(blockSize), m_fetch(std::move(fetcher)),
      m_memLimit(memLimitBytes), m_fillThreads(qBound(1, fillThreads, 8))
{
    QDir root(hr::dataDir() + "/cache/remote-blocks");
    m_dir = root.filePath(key);
    QDir().mkpath(m_dir);
    m_blockCount = (int)((size + blockSize - 1) / blockSize);
    if (m_blockCount < 1) m_blockCount = 1;
    const int depth = (int)(memLimitBytes / blockSize);
    m_depthFwd = qMax(1, depth * 60 / 100);
    m_depthBack = qMax(1, depth - m_depthFwd);
    m_flags.resize(m_blockCount);
    /* 磁盘位图恢复（blocks.bin：每块 1 字节） */
    QFile bf(m_dir + "/blocks.bin");
    if (bf.open(QIODevice::ReadOnly))
    {
        QByteArray bits = bf.read(m_blockCount);
        for (int i = 0; i < bits.size() && i < m_blockCount; i++)
            m_flags[i] = (quint8)bits[i];
    }
    for (int i = 0; i < m_blockCount; i++) m_fullyCached = m_fullyCached || false;
    int have = 0;
    for (int i = 0; i < m_blockCount; i++) have += m_flags[i] ? 1 : 0;
    m_fullyCached = (have == m_blockCount);
    m_hold = true; /* v0.111.0：prefillEnds 预取优先（认领头/尾后放行），超时自解 */
    m_holdAt = QDateTime::currentMSecsSinceEpoch();
    evt("rc_cache", QString("blocks=%1 bs=%2 threads=%3 have=%4")
            .arg(m_blockCount).arg(m_blockSize).arg(m_fillThreads).arg(have));
    startWorker();
}

BlockCacheStore::~BlockCacheStore()
{
    stopWorker();
}

QByteArray BlockCacheStore::blockFromDisk(int idx) const
{
    QFile f(m_dir + QString("/b%1.bin").arg(idx));
    if (!f.open(QIODevice::ReadOnly)) return QByteArray();
    return f.readAll();
}

/* v0.105.0: CRC32(0xEDB88320 查表;对齐安卓 crc.bin 语义) */
static quint32 crc32Of(const QByteArray &d)
{
    static quint32 tbl[256];
    static bool init = false;
    if (!init)
    {
        for (quint32 i = 0; i < 256; i++)
        {
            quint32 c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            tbl[i] = c;
        }
        init = true;
    }
    quint32 c = 0xFFFFFFFFu;
    for (int i = 0; i < d.size(); i++) c = tbl[(c ^ (quint8)d[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

void BlockCacheStore::blockToDisk(int idx, const QByteArray &data)
{
    QFile f(m_dir + QString("/b%1.bin").arg(idx));
    if (f.open(QIODevice::WriteOnly)) f.write(data);
    /* v0.105.0: 块 CRC 落盘(crc.bin 每块 4B LE;旧目录无 crc.bin=不校验) */
    QFile cf(m_dir + "/crc.bin");
    if (cf.open(QIODevice::ReadWrite))
    {
        QByteArray cs = cf.readAll();
        if (cs.size() < m_blockCount * 4) cs.append(QByteArray(m_blockCount * 4 - cs.size(), 0));
        const quint32 v = crc32Of(data);
        cs[idx * 4] = (char)(v & 0xFF);
        cs[idx * 4 + 1] = (char)((v >> 8) & 0xFF);
        cs[idx * 4 + 2] = (char)((v >> 16) & 0xFF);
        cs[idx * 4 + 3] = (char)((v >> 24) & 0xFF);
        cf.seek(0);
        cf.write(cs);
    }
    QFile bf(m_dir + "/blocks.bin");
    if (bf.open(QIODevice::ReadWrite))
    {
        QByteArray bits = bf.readAll();
        if (bits.size() < m_blockCount) bits.append(QByteArray(m_blockCount - bits.size(), 0));
        bits[idx] = 1;
        bf.seek(0);
        bf.write(bits);
    }
}

bool BlockCacheStore::ensureBlock(int idx)
{
    if (m_flags[idx]) return true;
    const long long pos = (long long)idx * m_blockSize;
    const int len = (int)qMin<long long>(m_blockSize, m_size - pos);
    if (len <= 0) return false;
    QByteArray data = m_fetch(pos, len);
    if (data.isEmpty()) return false;
    if (data.size() < len) data.resize(len); /* 尾块短读补零（mupdf 不读越界） */
    QMutexLocker _(&m_memMutex());
    /* v0.105.0: 单书 512MB 帽——超帽后只驻内存(对齐安卓 PER_BOOK_LIMIT 语义) */
    if (diskBytes() + len <= PER_BOOK_CAP)
    {
        blockToDisk(idx, data);
        m_diskBytes += len;
    }
    else
        m_ramOnly.insert(idx);
    m_flags[idx] = 1;
    if (m_mem.size() >= (int)(m_memLimit / m_blockSize) + 1 && !m_memOrder.isEmpty())
    {
        const int old = m_memOrder.takeFirst();
        m_mem.remove(old);
    }
    m_mem.insert(idx, data);
    m_memOrder.append(idx);
    int have = 0;
    for (int i = 0; i < m_blockCount; i++) have += m_flags[i] ? 1 : 0;
    m_fullyCached = (have == m_blockCount);
    return true;
}

QByteArray BlockCacheStore::blockData(int idx)
{
    /* 须持 m_memMutex 调用；内存没有则落盘读 */
    if (m_flags[idx])
    {
        const QByteArray mem = m_mem.value(idx);
        if (!mem.isEmpty()) return mem;
        const QByteArray d = blockFromDisk(idx);
        if (d.isEmpty()) return d;
        /* v0.105.0: 读盘 CRC 校验(有 crc.bin 才校验);坏块按缺失返回 -> 重取 */
        QFile cf(m_dir + "/crc.bin");
        if (cf.open(QIODevice::ReadOnly))
        {
            const QByteArray cs = cf.readAll();
            if (cs.size() >= (idx + 1) * 4)
            {
                const quint32 want = (quint8)cs[idx * 4] |
                                     ((quint32)(quint8)cs[idx * 4 + 1] << 8) |
                                     ((quint32)(quint8)cs[idx * 4 + 2] << 16) |
                                     ((quint32)(quint8)cs[idx * 4 + 3] << 24);
                if (crc32Of(d) != want) return QByteArray();
            }
        }
        return d;
    }
    return QByteArray();
}

long long BlockCacheStore::diskBytes()
{
    if (m_diskBytes < 0)
    {
        m_diskBytes = 0;
        const QFileInfoList fs = QDir(m_dir).entryInfoList(QDir::Files);
        for (const QFileInfo &f : fs) m_diskBytes += f.size();
    }
    return m_diskBytes;
}

void BlockCacheStore::warmTail() { m_warmTailReq = true; }

/* v0.105.0: 顺序缺块合并请求(对齐安卓 COALESCE_BLOCKS=16:一次 Range 拉连续段);
 * v0.111.0: 并发口径——先认领(跳过已有/在途)再取网,落盘后注销在途,多线程不重复取 */
bool BlockCacheStore::fetchSpan(int start, int count)
{
    if (start < 0 || count <= 0 || start >= m_blockCount) return false;
    if (count > m_blockCount - start) count = m_blockCount - start;
    int n = 0;
    {
        QMutexLocker _(&m_memMutex());
        for (int k = 0; k < count; k++)
        {
            const int idx = start + k;
            if (m_flags[idx] || m_ramOnly.contains(idx) || m_inflight.contains(idx)) break;
            n++;
        }
        if (n == 0) return true;
        for (int k = 0; k < n; k++) m_inflight.insert(start + k);
    } /* 认领后解锁再做网络 IO(与 ensureBlock 同口径,前台读不被阻塞) */
    return fetchSpanClaimed(start, n);
}

/* 取已认领段 [start,start+n)：取网→逐块落盘/入内存→注销在途。失败也注销。 */
bool BlockCacheStore::fetchSpanClaimed(int start, int n)
{
    if (n <= 0 || start < 0 || start >= m_blockCount) return false;
    if (n > m_blockCount - start) n = m_blockCount - start;
    const long long pos = (long long)start * m_blockSize;
    const long long maxLen = qMin<long long>((long long)n * m_blockSize, m_size - pos);
    if (maxLen <= 0)
    {
        QMutexLocker _(&m_memMutex());
        for (int k = 0; k < n; k++) m_inflight.remove(start + k);
        return false;
    }
    const int want = (int)maxLen;
    QByteArray data = m_fetch(pos, want);
    if (data.isEmpty())
    {
        QMutexLocker _(&m_memMutex());
        for (int k = 0; k < n; k++) m_inflight.remove(start + k);
        return false;
    }
    if (data.size() < want) data.resize(want); /* 尾段短读补零 */
    QMutexLocker _(&m_memMutex());
    for (int k = 0; k < n; k++)
    {
        const int idx = start + k;
        const QByteArray slice = data.mid(k * m_blockSize, m_blockSize);
        const int len = slice.size();
        if (diskBytes() + len <= PER_BOOK_CAP)
        {
            blockToDisk(idx, slice);
            m_diskBytes += len;
        }
        else
            m_ramOnly.insert(idx);
        m_flags[idx] = 1;
        if (m_mem.size() >= (int)(m_memLimit / m_blockSize) + 1 && !m_memOrder.isEmpty())
        {
            const int old = m_memOrder.takeFirst();
            m_mem.remove(old);
        }
        m_mem.insert(idx, slice);
        m_memOrder.append(idx);
    }
    for (int k = 0; k < n; k++) m_inflight.remove(start + k);
    int have = 0;
    for (int i = 0; i < m_blockCount; i++) have += m_flags[i] ? 1 : 0;
    m_fullyCached = (have == m_blockCount);
    return true;
}

int BlockCacheStore::readAt(long long pos, unsigned char *buf, int len)
{
    if (pos < 0 || len <= 0 || pos >= m_size) return pos >= m_size && len >= 0 ? 0 : -1;
    int done = 0;
    while (done < len)
    {
        const int idx = blockAt(pos + done);
        if (idx >= m_blockCount) break;
        if (!ensureBlock(idx)) break; /* 取块失败：以短读返回 */
        const int off = (int)(pos + done - (long long)idx * m_blockSize);
        QByteArray blk;
        {
            QMutexLocker _(&m_memMutex()); /* worker 预取并发动 hash：拷出再读 */
            blk = blockData(idx);
            if (blk.isEmpty())
            {
                m_flags[idx] = 0; /* v0.105.0: RAM-only 被逐/CRC 坏块——标缺失重取一次 */
                m_ramOnly.remove(idx);
            }
        }
        if (blk.isEmpty())
        {
            if (!ensureBlock(idx)) break;
            QMutexLocker _(&m_memMutex());
            blk = blockData(idx);
            if (blk.isEmpty()) break;
        }
        const int n = qMin(len - done, blk.size() - off);
        if (n <= 0) break;
        memcpy(buf + done, blk.constData() + off, n);
        done += n;
        if ((long long)idx * m_blockSize + off + n >= m_size) break; /* 尾 */
    }
    /* 阅读位置窗口预取提示 */
    if (done > 0) setWindow(pos);
    return done;
}

void BlockCacheStore::setWindow(long long pos) { m_windowAt = blockAt(pos); }

void BlockCacheStore::startWorker()
{
    if (!m_workers.isEmpty()) return;
    for (int i = 0; i < m_fillThreads; i++)
    {
        Worker *w = new Worker(this);
        m_workers.append(w);
        w->start();
    }
}

void BlockCacheStore::stopWorker()
{
    m_stop = true;
    bool stuck = false;
    for (QThread *w : m_workers) stuck = (w->wait(1500) == false) || stuck;
    for (QThread *w : m_workers) delete w;
    m_workers.clear();
    if (stuck) evt("bcs_stop_stuck", QString::number(m_fillThreads));
}

/* v0.111.0：开引擎前预取头/尾段——同步串行两次合并请求（无残留线程，栈上
 * smoke 会话也安全；并行收益交给后台多线程补齐）。预取期后台已由 ctor 置 hold。 */
void BlockCacheStore::prefillEnds(int headBlocks, int tailBlocks, int deadlineMs)
{
    Q_UNUSED(deadlineMs);
    headBlocks = qBound(0, headBlocks, m_blockCount);
    tailBlocks = qBound(0, tailBlocks, m_blockCount);
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    int hn = 0, tn = 0, ts = m_blockCount;
    {
        /* 先认领头/尾缺块（后台暂停中，不被抢走）。认领取连续段：头部从 0 起、
         * 尾部取窗口内"最后一段连续缺块"（xref/中央目录在最末） */
        QMutexLocker _(&m_memMutex());
        for (int k = 0; k < headBlocks; k++)
        {
            if (m_flags[k] || m_ramOnly.contains(k) || m_inflight.contains(k)) break;
            m_inflight.insert(k);
            hn++;
        }
        for (int k = 0; k < tailBlocks; k++)
        {
            const int idx = m_blockCount - tailBlocks + k;
            if (idx < 0 || idx >= m_blockCount) break;
            if (m_flags[idx] || m_ramOnly.contains(idx) || m_inflight.contains(idx))
            {
                ts = m_blockCount;
                tn = 0;
                continue;
            }
            if (tn == 0) ts = idx;
            tn++;
        }
    }
    m_hold = false; /* 放行后台补齐 */
    if (hn > 0) fetchSpanClaimed(0, hn);
    if (tn > 0) fetchSpanClaimed(ts, tn);
    evt("rc_prefill", QString("head=%1 tail=%2 ms=%3")
            .arg(hn).arg(tn)
            .arg(QDateTime::currentMSecsSinceEpoch() - t0));
}

void BlockCacheStore::workerLoop()
{
    /* P2 位置窗口优先 + P3 整书补齐；缺块失败置 bgDead 退出（前台读不受影响） */
    while (!m_stop)
    {
        if (m_hold)
        {
            /* v0.111.0：预取期暂停补齐（头/尾段让给 prefillEnds）；10s 兜底自解 */
            if (QDateTime::currentMSecsSinceEpoch() - m_holdAt > 10000) m_hold = false;
            else { QThread::msleep(40); continue; }
        }
        if (m_bgDead || !m_fillWhole) { QThread::msleep(120); continue; }
        if (m_warmTailReq)
        {
            m_warmTailReq = false;
            if (m_blockSize <= 64 * 1024 && m_blockCount >= 2)
                fetchSpan(m_blockCount - 2, 2); /* v0.105.0: 尾 2 块预热(PDF xref/trailer) */
            continue;
        }
        int target = -1;
        {
            /* v0.111.0：多线程补齐——扫描持锁并跳过在途块（并发认领不重复取） */
            QMutexLocker _wl(&m_memMutex());
            const long long w = m_windowAt;
            if (w >= 0)
            {
                for (int d = 0; d <= qMax(m_depthFwd, m_depthBack); d++)
                {
                    const int a = (int)w + d, b = (int)w - d;
                    if (a < m_blockCount && !m_flags[a] && !m_inflight.contains(a))
                    { target = a; break; }
                    if (b >= 0 && b <= a && d <= m_depthBack && !m_flags[b] &&
                        !m_inflight.contains(b))
                    { target = b; break; }
                }
            }
            if (target < 0)
            {
                for (int i = 0; i < m_blockCount; i++)
                    if (!m_flags[i] && !m_inflight.contains(i)) { target = i; break; }
            }
        }
        if (target < 0) { QThread::msleep(200); continue; }
        /* v0.105.0: 合并请求优先(<=16 块一次 Range),失败回落单块 */
        bool ok = fetchSpan(target, 16);
        if (!ok) ok = ensureBlock(target);
        if (!ok)
        {
            m_bgDead = true; /* 网络故障：停止后台（会话重开复位） */
        }
    }
}

void BlockCacheStore::enforceCap(long long maxMB, const QString &cacheRoot,
                                 const QString &keepDir)
{
    if (maxMB <= 0) return;
    QDir root(cacheRoot + "/remote-blocks");
    if (!root.exists()) return;
    qint64 total = 0;
    QHash<QString, qint64> dirBytes;
    const QFileInfoList dirs = root.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot);
    for (const QFileInfo &d : dirs)
    {
        qint64 s = 0;
        const QFileInfoList fs = QDir(d.absoluteFilePath()).entryInfoList(QDir::Files);
        for (const QFileInfo &f : fs) s += f.size();
        dirBytes.insert(d.absoluteFilePath(), s);
        total += s;
    }
    if (total <= maxMB * 1024 * 1024) return;
    QList<QPair<qint64, QString>> byMtime;
    for (const QFileInfo &d : dirs)
        byMtime.append(qMakePair(d.lastModified().toMSecsSinceEpoch(), d.absoluteFilePath()));
    std::sort(byMtime.begin(), byMtime.end());
    for (const auto &p : byMtime)
    {
        if (total <= maxMB * 1024 * 1024) break;
        if (!keepDir.isEmpty() && p.second == keepDir) continue; /* P0：在读书保护 */
        const qint64 s = dirBytes.value(p.second);
        QDir(p.second).removeRecursively();
        total -= s;
    }
}

/* P0 适配：窗口深度按偏好接线（前/后 % × 窗口字节 / 块长，1..128 块） */
void BlockCacheStore::setDepths(int fwdBlocks, int backBlocks)
{
    m_depthFwd = qBound(1, fwdBlocks, 128);
    m_depthBack = qBound(1, backBlocks, 128);
}

/* mutex 由 m_mem 访问共用：这里用一个文件级静态互斥（MVP：读写都短临界区） */
QMutex &BlockCacheStore::m_memMutex()
{
    static QMutex m;
    return m;
}

} // namespace hr
