#include "hranchor.h"

#include <QtGlobal>

namespace hr
{

/* 目录内最后一个 ≤page 的有效条目为 sec；区间止于下一个更大页码的条目（或全书末）。
 * 页码越界（旧排版遗留）的目录条目不采信。 */
bool anchorCapture(const QVector<int> &tocPages, int page, int totalPages,
                   int *sec, double *sr)
{
    if (sec) *sec = -1;
    if (sr) *sr = 0;
    if (tocPages.isEmpty() || page < 0 || totalPages <= 0) return false;
    int idx = -1;
    for (int i = 0; i < tocPages.size(); i++)
    {
        const int p = tocPages[i];
        if (p < 0 || p >= totalPages) continue;
        if (p <= page) idx = i;
        else break;
    }
    if (idx < 0) return false;
    const int start = tocPages[idx];
    int end = totalPages;
    for (int i = idx + 1; i < tocPages.size(); i++)
    {
        if (tocPages[i] > start && tocPages[i] < totalPages) { end = tocPages[i]; break; }
    }
    const int span = qMax(1, end - start);
    if (sec) *sec = idx;
    if (sr) *sr = qBound(0.0, (page - start) / (double)span, 0.999999);
    return true;
}

int anchorResolve(const QVector<int> &tocPages, int totalPages, int sec, double sr)
{
    if (sec < 0 || sec >= tocPages.size() || totalPages <= 0) return -1;
    const int start = tocPages[sec];
    if (start < 0 || start >= totalPages) return -1;
    int end = totalPages;
    for (int i = sec + 1; i < tocPages.size(); i++)
    {
        if (tocPages[i] > start && tocPages[i] < totalPages) { end = tocPages[i]; break; }
    }
    const int span = qMax(1, end - start);
    const int off = (int)qRound(qBound(0.0, sr, 0.999999) * span);
    return qBound(start, start + off, qMax(start, end - 1));
}

} // namespace hr
