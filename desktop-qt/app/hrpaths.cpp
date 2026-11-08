#include "hrpaths.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QTextStream>
#include <QDateTime>
#include <QCoreApplication>
#include <QSettings>

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

void evt(const QString &name, const QString &detail)
{
    /* U4：调试日志记录开关（dbg/logEnabled，默认开；关后探针静默） */
    if (!QSettings(dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
              .value("dbg/logEnabled", true)
              .toBool())
        return;
    QDir().mkpath(logsDir());
    QFile f(logsDir() + "/hrtest.log");
    if (!f.open(QIODevice::Append | QIODevice::Text))
        return;
    QString line = QString("evt=%1 ts=%2%3\n")
                       .arg(name)
                       .arg(QDateTime::currentMSecsSinceEpoch())
                       .arg(detail.isEmpty() ? QString() : QString(" ") + detail);
    f.write(line.toUtf8());
}

const char *appVersion() { return "1.0.1"; } /* 遗留项批：B2/B3/B4/A1/A2/A6/B6/B7/C5/C6/D9/D12/E5/F4 */ /* 适配批：P0+P1 二次确认后落地 */ /* U4：随批对齐 desktop-qt/VERSION */

} // namespace hr
