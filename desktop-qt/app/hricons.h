/* V1 图标体系：安卓 glyphicons SVG（icons/:/ 资源）→ 染色渲染。
 * 依赖 QtSvg（仅 MXE 通道定义 HR_HAVE_QTSVG；本机 Qt6 通道自动退化为无图标）。 */
#ifndef HRICONS_H
#define HRICONS_H

#include <QAbstractButton>
#include <QColor>
#include <QIcon>
#include <QPixmap>
#include <QString>

namespace hr
{

class Icons
{
public:
    static bool have(); /* QtSvg 可用且资源在包内 */
    static QPixmap pix(const QString &file, const QColor &tint, int size, qreal dpr = 1.0);
    static QIcon get(const QString &file, const QColor &tint, int size = 22);
    static QColor tint(); /* 亮=#5D6678 暗=#C8C8C8（对齐 Compose textTertiary） */
    static bool isDark();
    static void setDark(bool dark);
    static void bind(QAbstractButton *btn, const QString &file, int size = 22);
    static void bindTab(QAbstractButton *btn, const QString &file, int size = 22); /* v0.86.0 W5 H1：选中态 accent 染色 */
    static void retint(); /* 夜间切换后重染全部 bind 的按钮 */
    static void clearCache();
};

/* v0.77.0 V2：UI 尺度——QSS 字号七档（10/11/12/13/15/16/18px）随应用字体缩放（ui/appFontPct） */
namespace Ui
{
double qssScale();
void setQssScale(double s);
int fsPx(int basePx);
} // namespace Ui

} // namespace hr

#endif /* HRICONS_H */
