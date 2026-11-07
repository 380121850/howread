/* 远程书块缓存（对齐 core/remote/BlockCacheStore.kt：LRU 内存 + 磁盘块 + P2 位置窗口预取 + P3 整书补齐）。 */
#ifndef HRBCACHE_H
#define HRBCACHE_H

#include <QByteArray>
#include <QHash>
#include <QList>
#include <QMutex>
#include <QSet>
#include <QString>
#include <functional>

class QThread;

namespace hr
{

class BlockCacheStore
{
public:
    typedef std::function<QByteArray(long long pos, int len)> Fetcher;

    /* key=内容指纹；目录 cache/remote-blocks/<key>/（尺寸/块长变化自动重建） */
    BlockCacheStore(const QString &key, long long size, int blockSize, Fetcher fetcher,
                    long long memLimitBytes, int fillThreads = 1);
    ~BlockCacheStore();

    /* P1 前台读：缺块当场取（锁外网络），返回实读字节数；-1=错误 */
    int readAt(long long pos, unsigned char *buf, int len);

    long long totalSize() const { return m_size; }
    bool fullyCached() const { return m_fullyCached; }
    void setFillWhole(bool on) { m_fillWhole = on; }
    /* P0 适配 2026-11-04：上限接线需要目录保护 + 晋升/窗口读取 */
    QString dirPath() const { return m_dir; }
    int blockSizeFor() const { return m_blockSize; }
    void setDepths(int fwdBlocks, int backBlocks); /* 前后窗口深度（块数） */
    void warmTail(); /* v0.105.0: 页式格式尾块预热(PDF xref/trailer 在尾部) */

    /* P2/P3 后台多工作线程（v0.111.0：并行 span 补齐；webdav/opds 每线程独立连接真并发） */
    void startWorker();
    /* v0.111.0：开引擎前预取头/尾块（两次合并请求，同步串行；预取期后台补齐暂停）；
     * 引擎 open 的格式头与容器索引读（PDF xref/EPUB 中央目录）全命中 */
    void prefillEnds(int headBlocks, int tailBlocks, int deadlineMs = 8000);
    void stopWorker();
    void setWindow(long long pos); /* 阅读位置变化时调 */

    static void enforceCap(long long maxMB, const QString &cacheRoot,
                           const QString &keepDir = QString()); /* keepDir=在读书保护 */

private:
    QByteArray fetchBlock(int idx);          /* 锁外调用 */
    bool fetchSpan(int start, int count);    /* v0.105.0: 顺序缺块合并请求(<=16 块一次 Range) */
    bool fetchSpanClaimed(int start, int n); /* v0.111.0: 取已认领段（取网→落盘→注销在途） */
    long long diskBytes();                   /* v0.105.0: 本书磁盘占用(惰性统计) */
    QByteArray blockFromDisk(int idx) const;
    void blockToDisk(int idx, const QByteArray &data);
    int blockAt(long long pos) const { return (int)(pos / m_blockSize); }

    QString m_dir;
    long long m_size;
    int m_blockSize;
    Fetcher m_fetch;
    long long m_memLimit;
    int m_blockCount = 1;
    int m_depthFwd = 8, m_depthBack = 4;
    QVector<quint8> m_flags; /* 1=有块（内存或盘） */
    QHash<int, QByteArray> m_mem; /* 简易 LRU：超限时清最旧（插入序） */
    QList<int> m_memOrder;
    bool m_fullyCached = false;
    bool m_fillWhole = true;
    bool m_bgDead = false;
    /* v0.105.0: 块 CRC(crc.bin)、单书帽后 RAM-only、尾块预热 */
    QSet<int> m_ramOnly;
    long long m_diskBytes = -1;
    volatile bool m_warmTailReq = false;
    static const long long PER_BOOK_CAP = 512LL * 1024 * 1024;

    class Worker;
    friend class Worker;
    QVector<QThread *> m_workers; /* v0.111.0：多补齐线程 */
    int m_fillThreads = 1;
    QSet<int> m_inflight; /* 在途块登记（m_memMutex 保护，防并发重复取同一块） */
    volatile bool m_hold = false; /* v0.111.0：预取期暂停后台补齐（10s 自解） */
    volatile long long m_holdAt = 0;
    volatile long long m_windowAt = -1;
    volatile bool m_stop = false;
    void workerLoop();
    bool ensureBlock(int idx); /* 前台：缺则取并落盘 */
    QByteArray blockData(int idx); /* 持锁取块数据：内存→磁盘 */
    QMutex &m_memMutex();
};

} // namespace hr

#endif /* HRBCACHE_H */
