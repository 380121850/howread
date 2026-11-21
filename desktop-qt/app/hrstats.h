/* v0.61.0 批次三：阅读统计（dataDir/stats.json：按日 分钟/页数/书目）。 */
#ifndef HRSTATS_H
#define HRSTATS_H

#include <QJsonObject>
#include <QString>
#include <QVector>

namespace hr
{
namespace Stats
{
struct DayRow
{
    QString day; /* YYYY-MM-DD */
    int minutes = 0;
    int pages = 0;
    int books = 0;
};
/* 累加当日阅读（book 入当日书目集合，页数为增量，分钟为增量）；线程安全 */
void addReading(const QString &book, int pagesDelta, int minutesDelta);
/* 最近 lastN 天（按日期降序）；books=当日去重书目数 */
QVector<DayRow> rows(int lastN = 14);
QVector<DayRow> allDays(); /* P2（D12）：全部按日记录（周/月/年聚合用） */
int totalBooks(); /* 全部去重书目数 */
void clearAll();  /* U7 缺陷#3：清空全部阅读统计（删 stats.json） */

/* v1.2.0（C）：app-Stats.json 六键互通（安卓 ProfileStateIO 同 schema） */
QJsonObject exportSix(); /* 本机按日行聚合 → 六键（桶=毫秒串；daily 留40/monthly 留13） */
QJsonObject mergeSix(const QJsonObject &l, const QJsonObject &r); /* 安卓 mergeStats 同语义 */
void importSix(const QJsonObject &o); /* 收敛回 stats.json（分钟取大；远端多出的日补行） */
} // namespace Stats
} // namespace hr

#endif /* HRSTATS_H */
