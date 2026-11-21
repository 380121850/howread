/* v1.9.0（P1-5）：EPUB/PDF 排版加速器路径/清理——原 readerwindow.cpp 文件内静态，
 * 空闲预热器（librarywindow）共用；实现仍在 readerwindow.cpp */
#ifndef HRACCELS_H
#define HRACCELS_H

#include <QString>

namespace hr
{
QString accelPathFor(const QString &path); /* cache/accels/<md5-12>-<size>-<mtime>.accel */
void accelCleanup(const QString &path, const QString &keep); /* 同书旧档清理 + 全局 LRU 留 8 */
} // namespace hr

#endif /* HRACCELS_H */
