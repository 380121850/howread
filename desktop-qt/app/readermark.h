/* M4 阅读器扩展：划词/搜索（与 readerwindow.cpp 分文件，避免单文件膨胀）。 */
#ifndef HRREADERMARK_H
#define HRREADERMARK_H

#include <QRectF>
#include <QVector>
#include <QString>

class QMouseEvent;

namespace hr
{

class ReaderWindow;

/* 划词会话结果 */
struct SelResult
{
    QString text;
    QVector<QRectF> rectsPx; /* 像素坐标（当前页位图内） */
    float scale = 0;
};

class ReaderMark
{
public:
    /* 像素→页坐标换算工具 */
    static bool pageToPix(const QRectF &imgRect, const float box[4], float scale,
                          float dx, float dy, float *px, float *py);
    /* 执行划词：返回选中文字与像素矩形（imgRect=位图在视口中的矩形） */
    static SelResult select(ReaderWindow *w, int page, const QRectF &imgRect,
                            const QPointF &a, const QPointF &b);
    /* 全本搜索：返回 (页, 命中数) 列表（跳过 0） */
    static QVector<QPair<int, int>> searchAll(ReaderWindow *w, const QString &needle);
};

} // namespace hr

#endif /* HRREADERMARK_H */
