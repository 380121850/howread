/* V4 主题色槽位：Compose UiTheme.kt 16 槽双色板 1:1 落到 QPalette；
 * 主题色(accent)/着重色(accentTeal) 可覆盖并持久化（qt-prefs.ini ui/accentColor, ui/accentTealColor）。
 * 命名 UiTheme（避免与 readerwindow.h 的阅读器 5 主题 struct Theme 撞名）。 */
#ifndef HRTHEME_H
#define HRTHEME_H

#include <QColor>
#include <QPalette>
#include <QString>
#include <QVector>

namespace hr
{

struct UiPalette
{
    QColor bg, panel, bar, card, rowActive;
    QColor textPrimary, textSecondary, textTertiary, textFaint, textFaintest;
    QColor accent, accentTeal, danger, warn, green, gold;
    bool isLight = true;
};

class UiTheme
{
public:
    static const UiPalette &dark();
    static const UiPalette &light();
    static UiPalette current(); /* 含 accent/teal 覆盖 */
    static bool isDark();
    static void apply(bool dark); /* 构建 QPalette 并应用到 qApp（不写 ui/dark） */

    static QColor accent();
    static QColor accentTeal();
    static QColor accentOverride(); /* 无覆盖=invalid */
    static QColor tealOverride();
    static void setAccent(const QColor &c);     /* invalid=恢复默认；持久化+即时重应用 */
    static void setAccentTeal(const QColor &c);
    static QVector<QColor> accentPresets();     /* 8 预设 */
    static QVector<QColor> tealPresets();
    static QString hex(const QColor &c);        /* "#RRGGBB" 或 "default" */
};

} // namespace hr

#endif /* HRTHEME_H */
