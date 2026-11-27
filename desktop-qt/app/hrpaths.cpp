#include "hrpaths.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QTextStream>
#include <QDateTime>
#include <QCoreApplication>
#include <QSettings>
#include <QMutex>
#include <QElapsedTimer>

namespace hr
{

static QString g_dataDir; // 首次访问解析后定死

static QString exeRootDir()
{
    QString exe = QCoreApplication::applicationFilePath();
    QDir d(QFileInfo(exe).absolutePath());
    return d.absolutePath();
}

static QString resolveDataDir()
{
    QByteArray env = qgetenv("HOWREAD_DATA");
    if (!env.isEmpty())
        return QString::fromLocal8Bit(env).trimmed();
    // data-dir.txt 覆盖（Java 版同款）
    QString cfg = exeRootDir() + "/data-dir.txt";
    QFile f(cfg);
    if (f.open(QIODevice::ReadOnly | QIODevice::Text))
    {
        QString line = QString::fromUtf8(f.readAll()).trimmed();
        if (!line.isEmpty())
            return line;
    }
    return exeRootDir() + "/data";
}

QString dataDir()
{
    if (g_dataDir.isEmpty())
    {
        g_dataDir = resolveDataDir();
        QDir().mkpath(g_dataDir);
    }
    return g_dataDir;
}

QString logsDir() { return dataDir() + "/logs"; }

QString dbFile() { return dataDir() + "/howread.db"; }

/* v1.15.12 曾把 evt 改成缓冲写：起因是每条 evt 都构造 QSettings + 开/写/关一次日志文件，
 * 拖动风暴时（每页一条渲染日志）主线程磁盘操作把页码刷新饿死。
 * v1.15.13 自检修正：缓冲只在"下一条 evt 到来"或 QCoreApplication 正常析构时落盘，
 * 进程崩溃/主线程卡死/被强杀时最后一批事件永久丢失——而这恰是排障最需要的现场。
 * 改：日志文件常开句柄逐条直写（每条仅一次 WriteFile，微秒级），开关读盘 5s 缓存；
 * 两段同锁（evt 会被渲染/phase2/重排等 worker 线程并发调用，v1.15.12 的开关缓存
 * 在锁外裸读裸写是数据竞争）。 */
static QMutex g_evtMu;
static QFile *g_evtFile = nullptr; /* 常开句柄；有意不释放，避开静态析构次序 */
static qint64 g_evtRetryAt = 0;    /* 打开失败后的退避时刻（失败路径不逐条重试） */
static void evtWriteLocked(const QString &line)
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (!g_evtFile && now >= g_evtRetryAt)
    {
        QDir().mkpath(logsDir());
        QFile *f = new QFile(logsDir() + "/hrtest.log");
        /* Unbuffered 必须加：QFile 默认带 16KB 用户态写缓冲，常开句柄从不关闭=缓冲永不落盘
         * （实测日志 0 字节） */
        if (f->open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text |
                    QIODevice::Unbuffered))
            g_evtFile = f;
        else
        {
            delete f;
            g_evtRetryAt = now + 5000;
            return;
        }
    }
    if (!g_evtFile) return;
    g_evtFile->write(line.toUtf8());
    if (g_evtFile->error() != QFile::NoError)
    {
        delete g_evtFile; /* 句柄失效（盘掉/写失败）：退避后重新打开 */
        g_evtFile = nullptr;
        g_evtRetryAt = now + 1000;
    }
}

void evt(const QString &name, const QString &detail)
{
    static QElapsedTimer swTm;
    static bool swInit = false;
    static qint64 swChecked = -100000;
    static int swEnabled = 1;
    QMutexLocker lk(&g_evtMu);
    if (!swInit)
    {
        swInit = true;
        swTm.start();
    }
    /* U4：调试日志记录开关（dbg/logEnabled，默认开；关后探针静默）——5s 缓存重读 */
    const qint64 nowMs = swTm.elapsed();
    if (nowMs - swChecked > 5000)
    {
        swChecked = nowMs;
        swEnabled = QSettings(dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                        .value("dbg/logEnabled", true)
                        .toBool() ? 1 : 0;
    }
    if (!swEnabled) return;
    evtWriteLocked(QString("evt=%1 ts=%2%3\n")
                       .arg(name)
                       .arg(QDateTime::currentMSecsSinceEpoch())
                       .arg(detail.isEmpty() ? QString() : QString(" ") + detail));
}
const char *appVersion() { return "1.15.13"; } /* 孤岛对齐批二：排版双向/AI·服务器配置上云/注解侧车/朗读书签 */ /* 遗留项批：B2/B3/B4/A1/A2/A6/B6/B7/C5/C6/D9/D12/E5/F4 */ /* 适配批：P0+P1 二次确认后落地 */ /* U4：随批对齐 desktop-qt/VERSION */

} // namespace hr
