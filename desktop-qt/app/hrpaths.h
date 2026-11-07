/* 数据目录 + evt 打桩（与 Java 版 TestLog/AppPaths 同协议同位置，探针脚本直接复用）。 */
#ifndef HRPATHS_H
#define HRPATHS_H

#include <QString>

namespace hr
{

/* 数据根：HOWREAD_DATA env > <exe根>\data-dir.txt > <exe根>\data（Windows 便携）。 */
QString dataDir();
QString logsDir();
QString dbFile();

/* evt=app_start ts=... [detail] 追加到 <data>\logs\hrtest.log（Java 版同文件）。 */
void evt(const QString &name, const QString &detail = QString());

/* 应用版本（desktop-qt/VERSION 同步；AppInfo 等价物）。 */
const char *appVersion();

} // namespace hr

#endif /* HRPATHS_H */
