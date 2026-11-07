#include "hrl10n.h"
#include "hrpaths.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocale>
#include <QMutex>
#include <QMutexLocker>

namespace hr
{
namespace l10n
{

static QMutex g_mu;
static QHash<QString, QHash<QString, QString>> g_tabs;
static QString g_lang;
static bool g_loaded = false;

static QString detectLang()
{
    QFile f(dataDir() + QString::fromLatin1("/lang.json"));
    if (f.open(QIODevice::ReadOnly))
    {
        const QJsonObject j = QJsonDocument::fromJson(f.readAll()).object();
        const QString l = j.value(QString::fromLatin1("lang")).toString();
        if (!l.isEmpty()) return l;
    }
    return QLocale::system().name().startsWith(QString::fromLatin1("zh"), Qt::CaseInsensitive)
               ? QString::fromLatin1("zh")
               : QString::fromLatin1("en");
}

static void loadLocked()
{
    if (g_loaded) return;
    g_loaded = true;
    g_lang = detectLang();
    if (g_lang.isEmpty()) /* 空=跟随系统 */
        g_lang = QLocale::system().name().startsWith(QString::fromLatin1("zh"), Qt::CaseInsensitive)
                     ? QString::fromLatin1("zh") : QString::fromLatin1("en");
    const QDir appDir = QCoreApplication::applicationDirPath();
    const QStringList cands = {
        appDir.filePath(QString::fromLatin1("i18n/tables.json")),
        QDir(appDir.filePath(QString::fromLatin1("../..")))
            .filePath(QString::fromLatin1("desktop/app/src/main/resources/i18n/tables.json")),
    };
    for (const QString &c : cands)
    {
        QFile f(c);
        if (!f.open(QIODevice::ReadOnly)) continue;
        const QJsonObject root = QJsonDocument::fromJson(f.readAll()).object();
        for (auto it = root.begin(); it != root.end(); ++it)
        {
            const QJsonObject o = it.value().toObject();
            QHash<QString, QString> m;
            for (auto k = o.begin(); k != o.end(); ++k)
                m.insert(k.key(), k.value().toString());
            g_tabs.insert(it.key(), m);
        }
        evt(QString::fromLatin1("l10n_loaded"),
            QString::fromLatin1("langs=%1 lang=%2 src=%3")
                .arg(g_tabs.size()).arg(g_lang).arg(QFileInfo(c).fileName()));
        break;
    }
}

QString currentLang()
{
    QMutexLocker l(&g_mu);
    loadLocked();
    return g_lang;
}

void setLang(const QString &lang)
{
    QMutexLocker l(&g_mu);
    loadLocked();
    if (lang == g_lang) return;
    g_lang = lang;
    QFile f(dataDir() + QString::fromLatin1("/lang.json"));
    if (lang.isEmpty())
    {
        f.remove(); /* 空串 = 跟随系统（下次启动重新探测） */
        return;
    }
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        f.write(QString::fromLatin1("{\"lang\":\"%1\"}").arg(lang).toUtf8());
}

QStringList languages()
{
    QMutexLocker l(&g_mu);
    loadLocked();
    QStringList ks = g_tabs.keys();
    ks.sort();
    return ks;
}

QString s(const QString &zh, const QString &en)
{
    QMutexLocker l(&g_mu);
    loadLocked();
    if (g_lang == QString::fromLatin1("zh")) return zh;
    if (g_lang == QString::fromLatin1("en")) return en;
    const QString v = g_tabs.value(g_lang).value(zh);
    if (!v.isEmpty()) return v;
    if (g_lang.startsWith(QString::fromLatin1("zh"), Qt::CaseInsensitive)) return zh;
    return en;
}

}
} // namespace hr
