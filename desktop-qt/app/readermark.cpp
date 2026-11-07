#include "readermark.h"
#include "readerwindow.h"
#include "hr_bridge.h"

#include <QApplication>
#include <QElapsedTimer>

namespace hr
{

/* 访问 ReaderWindow 私有引擎句柄：readerwindow.h 提供受控出口 */
/* 经 friend 或公共 getter —— 这里用公共 getter hrDoc()/hrPage()（readerwindow.h 内声明） */

bool ReaderMark::pageToPix(const QRectF &imgRect, const float box[4], float scale,
                           float dx, float dy, float *px, float *py)
{
    *px = (float)((dx - imgRect.left()) / scale + box[0]);
    *py = (float)((dy - imgRect.top()) / scale + box[1]);
    return true;
}

SelResult ReaderMark::select(ReaderWindow *w, int page, const QRectF &imgRect,
                             const QPointF &a, const QPointF &b)
{
    SelResult out;
    void *doc = w->hrDoc();
    if (!doc || imgRect.width() <= 0) return out;
    float box[4] = {0, 0, 0, 0};
    if (hr_qt_page_box(doc, page, box) != 0) return out;
    const float bw = qMax(1.0f, box[2] - box[0]);
    const float scale = (float)(imgRect.width() / bw);
    out.scale = scale;
    float ax, ay, bx2, by2;
    pageToPix(imgRect, box, scale, a.x(), a.y(), &ax, &ay);
    pageToPix(imgRect, box, scale, b.x(), b.y(), &bx2, &by2);
    char *text = nullptr;
    float *rects = nullptr;
    int rn = 0;
    if (hr_qt_selection(doc, page, ax, ay, bx2, by2, &text, &rects, &rn) == 0)
    {
        out.text = QString::fromUtf8(text);
        for (int i = 0; i < rn; i++)
        {
            /* 页坐标→像素（减 box 偏移 × scale + 图像原点） */
            const float x0 = (rects[i * 4 + 0] - box[0]) * scale + imgRect.left();
            const float y0 = (rects[i * 4 + 1] - box[1]) * scale + imgRect.top();
            const float x1 = (rects[i * 4 + 2] - box[0]) * scale + imgRect.left();
            const float y1 = (rects[i * 4 + 3] - box[1]) * scale + imgRect.top();
            out.rectsPx.append(QRectF(QPointF(x0, y0), QPointF(x1, y1)));
        }
    }
    free(text);
    free(rects);
    return out;
}

QVector<QPair<int, int>> ReaderMark::searchAll(ReaderWindow *w, const QString &needle)
{
    QVector<QPair<int, int>> out;
    void *doc = w->hrDoc();
    if (!doc || needle.isEmpty()) return out;
    const QByteArray n = needle.toUtf8();
    const int pages = w->pageCount();
    for (int p = 0; p < pages; p++)
    {
        const int hits = hr_qt_search_page(doc, p, n.constData());
        if (hits > 0) out.append(qMakePair(p, hits));
        QApplication::processEvents(); /* 保持 UI 响应（搜索对话框可取消由调用方控制） */
    }
    return out;
}

} // namespace hr
