#include "hrstats.h"
#include "hrpaths.h"

#include <QDate>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutex>
#include <QMutexLocker>

namespace hr
{
namespace Stats
{

static QMutex g_mu;

static QString file()
{
    return dataDir() + QString::fromLatin1("/stats.json");
}

static QJsonObject load()
{
    QFile f(file());
    if (f.open(QIODevice::ReadOnly))
        return QJsonDocument::fromJson(f.readAll()).object();
    return QJsonObject();
}

static void save(const QJsonObject &root)
{
    QFile f(file());
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        f.write(QJsonDocument(root).toJson(QJsonDocument::Compact));
}

void addReading(const QString &book, int pagesDelta, int minutesDelta)
{
    if (pagesDelta <= 0 && minutesDelta <= 0) return;
    QMutexLocker l(&g_mu);
    const QString day = QDate::currentDate().toString(QString::fromLatin1("yyyy-MM-dd"));
    QJsonObject root = load();
    QJsonObject days = root.value(QString::fromLatin1("days")).toObject();
    QJsonObject d = days.value(day).toObject();
    d.insert(QString::fromLatin1("minutes"), d.value(QString::fromLatin1("minutes")).toInt() + minutesDelta);
    d.insert(QString::fromLatin1("pages"), d.value(QString::fromLatin1("pages")).toInt() + pagesDelta);
    QJsonArray books = d.value(QString::fromLatin1("books")).toArray();
    if (!book.isEmpty())
    {
        const QString nm = QFileInfo(book).fileName();
        bool has = false;
        for (int i = 0; i < books.size(); i++)
            if (books.at(i).toString() == nm) { has = true; break; }
        if (!has && books.size() < 200) books.append(nm);
    }
    d.insert(QString::fromLatin1("books"), books);
    days.insert(day, d);
    root.insert(QString::fromLatin1("days"), days);
    save(root);
    if (pagesDelta > 0)
        evt(QString::fromLatin1("stats_add"),
            QString::fromLatin1("book=%1 pages=+%2 min=+%3")
                .arg(QFileInfo(book).fileName().left(40)).arg(pagesDelta).arg(minutesDelta));
}

QVector<DayRow> rows(int lastN)
{
    QMutexLocker l(&g_mu);
    const QJsonObject days = load().value(QString::fromLatin1("days")).toObject();
    QVector<DayRow> out;
    const QStringList keys = days.keys();
    for (int i = keys.size() - 1; i >= 0 && out.size() < lastN; i--)
    {
        const QJsonObject d = days.value(keys[i]).toObject();
        DayRow r;
        r.day = keys[i];
        r.minutes = d.value(QString::fromLatin1("minutes")).toInt();
        r.pages = d.value(QString::fromLatin1("pages")).toInt();
        r.books = d.value(QString::fromLatin1("books")).toArray().size();
        out.append(r);
    }
    return out;
}

int totalBooks()
{
    QMutexLocker l(&g_mu);
    const QJsonObject days = load().value(QString::fromLatin1("days")).toObject();
    QStringList all;
    for (auto it = days.begin(); it != days.end(); ++it)
    {
        const QJsonArray a = it.value().toObject().value(QString::fromLatin1("books")).toArray();
        for (int i = 0; i < a.size(); i++)
            if (!all.contains(a.at(i).toString())) all.append(a.at(i).toString());
    }
    return all.size();
}

void clearAll()
{
    QMutexLocker l(&g_mu);
    const bool had = QFile::exists(file());
    QFile::remove(file());
    if (had) evt(QString::fromLatin1("stats_clear_all"), QString::fromLatin1("had=1"));
}

} // namespace Stats

QVector<hr::Stats::DayRow> hr::Stats::allDays() /* P2（D12）：全量按日（聚合用） */
{
    return rows(1000000);
}

} // namespace hr
