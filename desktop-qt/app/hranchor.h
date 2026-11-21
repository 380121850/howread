/* 章节锚（安卓 1.3.21 / 鸿蒙 0.9.67 同款 sec/sr/st 三字段的折算数学）：
 * sec=本机目录序号、sr=章内位置比例、st=章节标题指纹（标题比对在调用方）。
 * 目录页表 = 当前排版的 0 基绝对页；全书页数用于末章/边界。 */
#ifndef HRANCHOR_H
#define HRANCHOR_H

#include <QVector>

namespace hr
{

/* 捕获：page 所在目录区间 → sec/sr。无目录/页越界/目录无有效条目时返回 false（sec=-1） */
bool anchorCapture(const QVector<int> &tocPages, int page, int totalPages,
                   int *sec, double *sr);

/* 折算：锚 → 当前排版页。sec 无效返回 -1（调用方走比例回落） */
int anchorResolve(const QVector<int> &tocPages, int totalPages, int sec, double sr);

} // namespace hr

#endif /* HRANCHOR_H */
