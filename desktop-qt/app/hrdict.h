/* v0.60 批次二：StarDict 离线词典（Java StarDict/Dicts 同语义面）。
 * 词典文件夹放 dataDir/dicts/（≤3 层）；.ifo/.idx[.gz]/.dict[.dz]；
 * .dz=dictzip（合法 gzip 流）：首次查询整包解压到缓存后随机访问。 */
#ifndef HRDICT_H
#define HRDICT_H

#include <QList>
#include <QString>

namespace hr
{

struct DictHit
{
    QString book;
    QString text;
};

class Dicts
{
public:
    static QString dictDir(); /* dataDir/dicts（自动建目录） */
    static void refresh();    /* 重扫 .ifo（≤3 层） */
    static int installedCount();
    /* 全部词典查词（精确优先、忽略大小写次之），只含有命中的。 */
    static QList<DictHit> lookupAll(const QString &word);
};

} // namespace hr

#endif /* HRDICT_H */
