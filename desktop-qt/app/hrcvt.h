/* v0.59.0 批次一：打开路径路由（FormatRouter.openablePath 同语义）。
 * azw3(KF8) / 大体积 KF7 mobi/azw/prc(>8MB) → mobitool 转 EPUB 缓存后打开；
 * doc → antiword（hr_qt_doc_to_html）转 DocBook XML 包 HTML 缓存后打开；
 * djvu 直通（hr_bridge 内 libdjvu 渲染）；其余原样（引擎直开）。
 * 转换产物缓存 dataDir/cache/convert/，键=路径+大小+mtime（源变化自动重建）。 */
#ifndef HRCVT_H
#define HRCVT_H

#include <QString>

namespace hr
{
/* 返回引擎可直接打开的路径；转换失败回落原路径（引擎给出打开错误，Java 版同语义）。 */
QString openablePath(const QString &path);
/* v0.104.0（P1-4）：forceCacheMiss=损坏探针删产物后强制重转（跳过缓存命中检查） */
QString openablePath(const QString &path, bool forceCacheMiss);
} // namespace hr

#endif /* HRCVT_H */
