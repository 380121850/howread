#include "hricons.h"

#include <QApplication>
#include <QFile>
#include <QHash>
#include <QPainter>
#include <QVector>
#include <algorithm>
#include <QPointer>

#ifdef HR_HAVE_QTSVG
#include <QSvgRenderer>
#endif

namespace hr
{

namespace
{
struct Bind
{
    QPointer<QAbstractButton> btn;
    QString file;
    int size = 22;
    bool tab = false; /* v0.86.0 W5 H1：底栏 tab——选中时用 accent（palette highlight）染色 */
};
/* v0.95.0 W16：底栏底色改主题色——tab 图标恒白（accent 染色会与底色同化隐形），
 * 选中态由 加深底+加粗 区分；非 tab 照常染色 */
QColor tabTint(const Bind &b)
{
    if (b.tab)
        return (b.btn && b.btn->isChecked()) ? QColor(0xFF, 0xFF, 0xFF)
                                             : QColor(255, 255, 255, 200);
    return Icons::tint();
}
QVector<Bind> &binds()
{
    static QVector<Bind> b;
    return b;
}
#ifdef HR_HAVE_QTSVG
QHash<QString, QPixmap> &cache()
{
    static QHash<QString, QPixmap> c;
    return c;
}
bool g_dark = false;
#endif
} // namespace

bool Icons::have()
{
#ifdef HR_HAVE_QTSVG
    return QFile::exists(":/icons/glyphicons_21_home.svg");
#else
    return false;
#endif
}

QPixmap Icons::pix(const QString &file, const QColor &tint, int size, qreal dpr)
{
#ifdef HR_HAVE_QTSVG
    if (size <= 0 || dpr <= 0) return QPixmap();
    const QString key = file + "|" + tint.name(QColor::HexRgb) + "|" + QString::number(size) +
                        "|" + QString::number(dpr);
    auto it = cache().constFind(key);
    if (it != cache().constEnd()) return it.value();
    QFile f(":/icons/" + file + ".svg");
    if (!f.open(QIODevice::ReadOnly)) return QPixmap();
    QByteArray svg = f.readAll();
    svg.replace(QByteArray("fill=\"#000000\""),
                QByteArray("fill=\"") + tint.name(QColor::HexRgb).toLatin1() + "\"");
    QSvgRenderer r(svg);
    if (!r.isValid()) return QPixmap();
    QImage img(qRound(size * dpr), qRound(size * dpr), QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter p(&img);
    r.render(&p, QRectF(0, 0, img.width(), img.height()));
    p.end();
    img.setDevicePixelRatio(dpr);
    const QPixmap pm = QPixmap::fromImage(img);
    cache().insert(key, pm);
    return pm;
#else
    Q_UNUSED(file);
    Q_UNUSED(tint);
    Q_UNUSED(size);
    Q_UNUSED(dpr);
    return QPixmap();
#endif
}

QIcon Icons::get(const QString &file, const QColor &tint, int size)
{
    const QPixmap pm = pix(file, tint, size, qApp->devicePixelRatio());
    return pm.isNull() ? QIcon() : QIcon(pm);
}

QColor Icons::tint()
{
#ifdef HR_HAVE_QTSVG
    return g_dark ? QColor(0xC8, 0xC8, 0xC8) : QColor(0x5D, 0x66, 0x78);
#else
    return QColor(0x5D, 0x66, 0x78);
#endif
}

bool Icons::isDark()
{
#ifdef HR_HAVE_QTSVG
    return g_dark;
#else
    return false;
#endif
}

void Icons::setDark(bool dark)
{
#ifdef HR_HAVE_QTSVG
    if (g_dark != dark)
    {
        g_dark = dark;
        cache().clear();
    }
#else
    Q_UNUSED(dark);
#endif
}

void Icons::clearCache()
{
#ifdef HR_HAVE_QTSVG
    cache().clear();
#endif
}

void Icons::bind(QAbstractButton *btn, const QString &file, int size)
{
    if (!btn || file.isEmpty()) return;
    if (have()) btn->setIcon(get(file, tint(), size));
    for (Bind &b : binds())
        if (b.btn == btn)
        {
            b.file = file;
            b.size = size;
            return;
        }
    binds().append({btn, file, size});
}

void Icons::bindTab(QAbstractButton *btn, const QString &file, int size)
{
    if (!btn || file.isEmpty()) return;
    if (have()) /* v0.95.0 W16：初值与 tabTint 一致（accent 底恒白） */
        btn->setIcon(get(file, btn->isChecked() ? QColor(0xFF, 0xFF, 0xFF)
                                                : QColor(255, 255, 255, 200), size));
    for (Bind &b : binds())
        if (b.btn == btn)
        {
            b.file = file;
            b.size = size;
            b.tab = true;
            return;
        }
    binds().append({btn, file, size, true});
}

void Icons::retint()
{
    for (const Bind &b : binds())
        if (b.btn) b.btn->setIcon(get(b.file, tabTint(b), b.size));
    binds().erase(std::remove_if(binds().begin(), binds().end(),
                                 [](const Bind &b) { return b.btn.isNull(); }),
                  binds().end());
}

namespace Ui
{
namespace
{
double g_qssScale = 1.0;
}
double qssScale() { return g_qssScale; }
void setQssScale(double s) { g_qssScale = (s > 0.3 && s < 4.0) ? s : 1.0; }
int fsPx(int basePx) { return qMax(8, qRound(basePx * g_qssScale)); }
} // namespace Ui

} // namespace hr
