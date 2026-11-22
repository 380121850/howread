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

int minutesBack(int days) /* v1.13.1：近 N 天（含今日）阅读分钟 */
{
    QMutexLocker l(&g_mu);
    const QJsonObject daysO = load().value(QString::fromLatin1("days")).toObject();
    const QDate cur = QDate::currentDate();
    const QDate lo = cur.addDays(-days); /* 滑动窗口 dt > cur-N → [cur-N+1, cur] 共 N 天 */
    int sum = 0;
    for (auto it = daysO.constBegin(); it != daysO.constEnd(); ++it)
    {
        const QDate dt = QDate::fromString(it.key(), QString::fromLatin1("yyyy-MM-dd"));
        if (dt.isValid() && dt > lo)
            sum += it.value().toObject().value(QString::fromLatin1("minutes")).toInt();
    }
    return sum;
}

QVector<QPair<QString, int>> bucketsDaily(int days, bool shortLabel) /* v1.14.1 */
{
    QMutexLocker l(&g_mu);
    const QJsonObject daysO = load().value(QString::fromLatin1("days")).toObject();
    const QDate cur = QDate::currentDate();
    QVector<QPair<QString, int>> out;
    for (int i = days - 1; i >= 0; i--)
    {
        const QDate d = cur.addDays(-i);
        const QString lb = shortLabel ? QString::number(d.day())
                                      : QString("%1/%2").arg(d.month()).arg(d.day());
        out.append(qMakePair(
            lb,
            daysO.value(d.toString(QString::fromLatin1("yyyy-MM-dd")))
                .toObject()
                .value(QString::fromLatin1("minutes"))
                .toInt()));
    }
    return out;
}

QVector<QPair<QString, int>> bucketsMonthly() /* v1.14.1：近 12 自然月分钟 */
{
    QMutexLocker l(&g_mu);
    const QJsonObject daysO = load().value(QString::fromLatin1("days")).toObject();
    QMap<int, int> mp;
    for (auto it = daysO.constBegin(); it != daysO.constEnd(); ++it)
    {
        const QDate dt = QDate::fromString(it.key(), QString::fromLatin1("yyyy-MM-dd"));
        if (dt.isValid())
            mp[dt.year() * 100 + dt.month()] +=
                it.value().toObject().value(QString::fromLatin1("minutes")).toInt();
    }
    const QDate cur = QDate::currentDate();
    QVector<QPair<QString, int>> out;
    for (int i = 11; i >= 0; i--)
    {
        const QDate m = cur.addMonths(-i);
        out.append(qMakePair(QString("%1/%2").arg(m.year() % 100).arg(m.month()),
                              mp.value(m.year() * 100 + m.month(), 0)));
    }
    return out;
}

void clearAll()
{
    QMutexLocker l(&g_mu);
    const bool had = QFile::exists(file());
    QFile::remove(file());
    if (had) evt(QString::fromLatin1("stats_clear_all"), QString::fromLatin1("had=1"));
}

/* ---------- v1.2.0（C）：app-Stats.json 六键（安卓 ProfileStateIO 同 schema；
 * 桶值单位=毫秒、JSON-in-string；monthly 留 13 / daily 留 40；
 * merge=标量 max + 桶逐键 max + dayKey 同日才并） ---------- */

static QString todayKeyS()
{
    return QDate::currentDate().toString(QString::fromLatin1("yyyy-MM-dd"));
}

static long long jll(const QJsonObject &o, const char *k)
{
    return (long long)o.value(QLatin1String(k)).toDouble();
}

static QJsonObject bucketParse(const QString &s)
{
    const QJsonDocument d = QJsonDocument::fromJson(s.toUtf8());
    return d.isObject() ? d.object() : QJsonObject();
}

static QString bucketMergeS(const QString &a, const QString &b)
{
    QJsonObject ja = bucketParse(a), jb = bucketParse(b);
    if (ja.isEmpty() && jb.isEmpty()) return QString::fromLatin1("{}");
    for (auto it = jb.constBegin(); it != jb.constEnd(); ++it)
    {
        const long long rv = (long long)it.value().toDouble();
        const long long lv = (long long)ja.value(it.key()).toDouble();
        if (rv > lv) ja.insert(it.key(), (double)rv);
    }
    return QString::fromUtf8(QJsonDocument(ja).toJson(QJsonDocument::Compact));
}

QJsonObject exportSix()
{
    QMutexLocker l(&g_mu);
    const QJsonObject days = load().value(QString::fromLatin1("days")).toObject();
    long long totalMs = 0, totalPg = 0, todayMs = 0;
    QJsonObject daily, monthly;
    const QString today = todayKeyS();
    QStringList ks = days.keys();
    ks.sort();
    for (const QString &k : ks)
    {
        const QJsonObject d = days.value(k).toObject();
        const long long ms =
            (long long)d.value(QString::fromLatin1("minutes")).toInt() * 60000;
        totalMs += ms;
        totalPg += d.value(QString::fromLatin1("pages")).toInt();
        daily.insert(k, (double)ms);
        const QString ym = k.left(7);
        monthly.insert(ym, monthly.value(ym).toDouble() + (double)ms);
        if (k == today) todayMs = ms;
    }
    while (daily.size() > 40) daily.erase(daily.begin());
    while (monthly.size() > 13) monthly.erase(monthly.begin());
    QJsonObject o;
    o.insert(QString::fromLatin1("readTimeMs"), (double)totalMs);
    o.insert(QString::fromLatin1("readDayKey"), today);
    o.insert(QString::fromLatin1("readDayMs"), (double)todayMs);
    o.insert(QString::fromLatin1("readPages"), (double)totalPg);
    o.insert(QString::fromLatin1("readMonthlyJson"),
             QString::fromUtf8(QJsonDocument(monthly).toJson(QJsonDocument::Compact)));
    o.insert(QString::fromLatin1("readDailyJson"),
             QString::fromUtf8(QJsonDocument(daily).toJson(QJsonDocument::Compact)));
    return o;
}

QJsonObject mergeSix(const QJsonObject &l, const QJsonObject &r)
{
    QJsonObject o;
    o.insert(QString::fromLatin1("readTimeMs"),
             (double)qMax(jll(l, "readTimeMs"), jll(r, "readTimeMs")));
    o.insert(QString::fromLatin1("readPages"),
             (double)qMax(jll(l, "readPages"), jll(r, "readPages")));
    /* 今日桶：仅当日键参与合并（安卓 applyStats 同规则，防历史键冒充今日） */
    const QString today = todayKeyS();
    const QString lk = l.value(QString::fromLatin1("readDayKey")).toString();
    const QString rk = r.value(QString::fromLatin1("readDayKey")).toString();
    long long dm = 0;
    QString dk;
    if (lk == today && jll(l, "readDayMs") > dm) { dm = jll(l, "readDayMs"); dk = lk; }
    if (rk == today && jll(r, "readDayMs") > dm) { dm = jll(r, "readDayMs"); dk = rk; }
    if (dm == 0) { dm = jll(l, "readDayMs"); dk = lk; } /* 无同日候选：维持本地 */
    o.insert(QString::fromLatin1("readDayKey"), dk);
    o.insert(QString::fromLatin1("readDayMs"), (double)dm);
    o.insert(QString::fromLatin1("readMonthlyJson"),
             bucketMergeS(l.value(QString::fromLatin1("readMonthlyJson")).toString(),
                          r.value(QString::fromLatin1("readMonthlyJson")).toString()));
    o.insert(QString::fromLatin1("readDailyJson"),
             bucketMergeS(l.value(QString::fromLatin1("readDailyJson")).toString(),
                          r.value(QString::fromLatin1("readDailyJson")).toString()));
    return o;
}

void importSix(const QJsonObject &o)
{
    if (o.isEmpty()) return;
    const QJsonObject buckets =
        bucketParse(o.value(QString::fromLatin1("readDailyJson")).toString());
    if (buckets.isEmpty()) return;
    QMutexLocker l(&g_mu);
    QJsonObject root = load();
    QJsonObject days = root.value(QString::fromLatin1("days")).toObject();
    bool changed = false;
    for (auto it = buckets.constBegin(); it != buckets.constEnd(); ++it)
    {
        const int mins = (int)((long long)it.value().toDouble() / 60000);
        QJsonObject d = days.value(it.key()).toObject();
        const int cur = d.value(QString::fromLatin1("minutes")).toInt();
        if (mins > cur)
        {
            d.insert(QString::fromLatin1("minutes"), mins);
            days.insert(it.key(), d);
            changed = true;
        }
    }
    if (changed)
    {
        root.insert(QString::fromLatin1("days"), days);
        save(root);
        evt(QString::fromLatin1("stats_import"),
            QString::fromLatin1("days=%1").arg(buckets.size()));
    }
}

} // namespace Stats

QVector<hr::Stats::DayRow> hr::Stats::allDays() /* P2（D12）：全量按日（聚合用） */
{
    return rows(1000000);
}

} // namespace hr
