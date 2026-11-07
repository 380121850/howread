#include "hrtheme.h"

#include "hrpaths.h"

#include <QApplication>
#include <QSettings>

namespace hr
{

namespace
{
bool g_dark = false;
QColor g_acc, g_teal; /* 覆盖（invalid=默认） */
bool g_loaded = false;

QString prefsPath() { return hr::dataDir() + "/qt-prefs.ini"; }

void loadOverrides()
{
    if (g_loaded) return;
    g_loaded = true;
    QSettings st(prefsPath(), QSettings::IniFormat);
    const QString a = st.value("ui/accentColor", "").toString();
    const QString t = st.value("ui/accentTealColor", "").toString();
    g_acc = a.isEmpty() ? QColor() : QColor(a);
    g_teal = t.isEmpty() ? QColor() : QColor(t);
}

UiPalette mk(unsigned bg, unsigned panel, unsigned bar, unsigned card, unsigned rowActive,
             unsigned t1, unsigned t2, unsigned t3, unsigned t4, unsigned t5, unsigned acc,
             unsigned teal, unsigned danger, unsigned warn, unsigned green, unsigned gold,
             bool isLight)
{
    UiPalette p;
    p.bg = QColor(bg); p.panel = QColor(panel); p.bar = QColor(bar); p.card = QColor(card);
    p.rowActive = QColor(rowActive);
    p.textPrimary = QColor(t1); p.textSecondary = QColor(t2); p.textTertiary = QColor(t3);
    p.textFaint = QColor(t4); p.textFaintest = QColor(t5);
    p.accent = QColor(acc); p.accentTeal = QColor(teal); p.danger = QColor(danger);
    p.warn = QColor(warn); p.green = QColor(green); p.gold = QColor(gold);
    p.isLight = isLight;
    return p;
}
} // namespace

/* UiTheme.kt:34-51 同值 */
const UiPalette &UiTheme::dark()
{
    static const UiPalette d = mk(0x171A21, 0x1D2129, 0x20242B, 0x222734, 0x2A3140, 0xE8EAF0,
                                  0xC2CAD8, 0x8A93A6, 0x6B7385, 0x566070, 0x8AB4F8, 0x7DC9C4,
                                  0x855252, 0xC98B7D, 0x7DC98F, 0xFFC53D, false);
    return d;
}

const UiPalette &UiTheme::light()
{
    static const UiPalette l = mk(0xF3F5F9, 0xFFFFFF, 0xE4E9F2, 0xE9EEF6, 0xDCE5F7, 0x1B2028,
                                  0x3B4353, 0x5D6678, 0x7C8699, 0x9AA4B6, 0x2E6BD6, 0x1F837C,
                                  0xB0524B, 0xA6653F, 0x2E8B57, 0xD99A00, true);
    return l;
}

UiPalette UiTheme::current()
{
    loadOverrides();
    UiPalette p = g_dark ? dark() : light();
    if (g_acc.isValid()) p.accent = g_acc;
    if (g_teal.isValid()) p.accentTeal = g_teal;
    return p;
}

bool UiTheme::isDark() { return g_dark; }

void UiTheme::apply(bool dark)
{
    g_dark = dark;
    const UiPalette p = current();
    QPalette q = qApp->palette();
    q.setColor(QPalette::Window, p.bg);
    q.setColor(QPalette::WindowText, p.textPrimary);
    q.setColor(QPalette::Base, p.panel);
    q.setColor(QPalette::AlternateBase, p.card);
    q.setColor(QPalette::Text, p.textPrimary);
    q.setColor(QPalette::Button, p.bar);
    q.setColor(QPalette::ButtonText, p.textPrimary);
    q.setColor(QPalette::Highlight, p.accent);
    q.setColor(QPalette::HighlightedText, Qt::white);
    q.setColor(QPalette::Link, p.accent);
    q.setColor(QPalette::LinkVisited, p.accentTeal);
    q.setColor(QPalette::ToolTipBase, p.panel);
    q.setColor(QPalette::ToolTipText, p.textPrimary);
    q.setColor(QPalette::PlaceholderText, p.textFaint);
    q.setColor(QPalette::Disabled, QPalette::Text, p.textFaintest);
    q.setColor(QPalette::Disabled, QPalette::WindowText, p.textFaintest);
    q.setColor(QPalette::Disabled, QPalette::ButtonText, p.textFaintest);
    qApp->setPalette(q);
}

QColor UiTheme::accent() { return current().accent; }
QColor UiTheme::accentTeal() { return current().accentTeal; }
QColor UiTheme::accentOverride() { loadOverrides(); return g_acc; }
QColor UiTheme::tealOverride() { loadOverrides(); return g_teal; }

void UiTheme::setAccent(const QColor &c)
{
    loadOverrides();
    g_acc = c;
    QSettings st(prefsPath(), QSettings::IniFormat);
    st.setValue("ui/accentColor", c.isValid() ? c.name(QColor::HexRgb) : QString());
    apply(g_dark);
}

void UiTheme::setAccentTeal(const QColor &c)
{
    loadOverrides();
    g_teal = c;
    QSettings st(prefsPath(), QSettings::IniFormat);
    st.setValue("ui/accentTealColor", c.isValid() ? c.name(QColor::HexRgb) : QString());
    apply(g_dark);
}

/* 8 预设：Compose 默认蓝/浅蓝 + 安卓 STYLE_COLORS（靛/珊瑚红/青）+ 紫/金/绿 */
QVector<QColor> UiTheme::accentPresets()
{
    return {QColor(0x2E6BD6), QColor(0x8AB4F8), QColor(0x3949AB), QColor(0xEA5964),
            QColor(0x00897B), QColor(0x6A5ACD), QColor(0xD99A00), QColor(0x2E8B57)};
}

/* 着重色 8 预设：Compose 青/浅青 + 安卓 ACCENT_COLORS（黄/珊瑚红/青）+ 橙/紫/蓝灰 */
QVector<QColor> UiTheme::tealPresets()
{
    return {QColor(0x1F837C), QColor(0x7DC9C4), QColor(0xE3C800), QColor(0xEA5964),
            QColor(0x00897B), QColor(0xFF8C00), QColor(0x8E24AA), QColor(0x455A64)};
}

QString UiTheme::hex(const QColor &c)
{
    return c.isValid() ? c.name(QColor::HexRgb) : QString::fromLatin1("default");
}

} // namespace hr
