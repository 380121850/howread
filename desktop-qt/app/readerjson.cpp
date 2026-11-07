#include "readerjson.h"
#include "hrpaths.h"

#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>

namespace hr
{

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
static double clampf(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

bool RJson::load()
{
    QFile f(dataDir() + QString::fromLatin1("/reader.json"));
    if (!f.open(QIODevice::ReadOnly)) return false;
    const QJsonObject j = QJsonDocument::fromJson(f.readAll()).object();
    if (j.isEmpty()) return false;
    lineHeight = clampi(j.value(QString::fromLatin1("lineHeight")).toInt(lineHeight), 100, 250);
    marginTop = clampi(j.value(QString::fromLatin1("marginTop")).toInt(marginTop), 0, 60);
    marginRight = clampi(j.value(QString::fromLatin1("marginRight")).toInt(marginRight), 0, 60);
    marginBottom = clampi(j.value(QString::fromLatin1("marginBottom")).toInt(marginBottom), 0, 60);
    marginLeft = clampi(j.value(QString::fromLatin1("marginLeft")).toInt(marginLeft), 0, 60);
    textIndent = clampi(j.value(QString::fromLatin1("textIndent")).toInt(textIndent), 0, 50);
    paraSpacing = clampi(j.value(QString::fromLatin1("paraSpacing")).toInt(paraSpacing), 0, 20);
    em = clampf(j.value(QString::fromLatin1("em")).toDouble(em), 8.0, 32.0);
    const QString a = j.value(QString::fromLatin1("align")).toString(align);
    if (a == "left" || a == "justify" || a == "right" || a == "center") align = a;
    const QString th = j.value(QString::fromLatin1("readTheme")).toString(readTheme);
    if (th == "day" || th == "milk" || th == "parchment" || th == "gray" || th == "night") readTheme = th;
    useDocCss = j.value(QString::fromLatin1("useDocumentCss")).toBool(true);
    {
        const QString tx = j.value(QString::fromLatin1("readTexture")).toString(readTexture);
        if (tx == "none" || tx == "paper" || tx == "fiber" || tx == "linen" ||
            tx == "parchment" || tx == "canvas")
            readTexture = tx;
    }
    return true;
}

void RJson::save() const
{
    /* P0 适配：保留未知键——不再整文件覆盖。与 Compose 桌面版共用 data 目录时，
     * fontPath/family/useDocumentCss/fontScale/maxChars/readTexture 等 WIN 专有键
     * 不再被抹成固定值；本版只更新自己管理的键。 */
    QJsonObject j;
    {
        QFile rf(dataDir() + QString::fromLatin1("/reader.json"));
        if (rf.open(QIODevice::ReadOnly))
            j = QJsonDocument::fromJson(rf.readAll()).object();
    }
    j.insert(QString::fromLatin1("lineHeight"), lineHeight);
    j.insert(QString::fromLatin1("marginTop"), marginTop);
    j.insert(QString::fromLatin1("marginRight"), marginRight);
    j.insert(QString::fromLatin1("marginBottom"), marginBottom);
    j.insert(QString::fromLatin1("marginLeft"), marginLeft);
    j.insert(QString::fromLatin1("align"), align);
    j.insert(QString::fromLatin1("textIndent"), textIndent);
    j.insert(QString::fromLatin1("paraSpacing"), paraSpacing);
    j.insert(QString::fromLatin1("readTheme"), readTheme);
    j.insert(QString::fromLatin1("readTexture"), readTexture);
    j.insert(QString::fromLatin1("em"), (double)em); /* Qt 扩展键 */
    j.insert(QString::fromLatin1("useDocumentCss"), useDocCss);
    QFile f(dataDir() + QString::fromLatin1("/reader.json"));
    if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        f.write(QJsonDocument(j).toJson(QJsonDocument::Compact));
}

bool importAndroidCssIfNeeded()
{
    const QString cssP = dataDir() + QString::fromLatin1("/app-CSS.json");
    QFile cssF(cssP);
    if (!cssF.exists()) return false;
    const long long mt = QFileInfo(cssF).lastModified().toMSecsSinceEpoch();
    QSettings st(dataDir() + QString::fromLatin1("/qt-prefs.ini"), QSettings::IniFormat);
    if (mt <= st.value(QString::fromLatin1("androidcss/mt"), 0).toLongLong()) return false;
    if (!cssF.open(QIODevice::ReadOnly)) return false;
    const QJsonObject css = QJsonDocument::fromJson(cssF.readAll()).object();
    if (css.isEmpty()) return false;

    RJson rj;
    rj.load(); /* 既有值兜底 */
    bool any = false;
    if (css.contains(QString::fromLatin1("fontSizeSp")))
    {
        rj.em = (float)clampi(css.value(QString::fromLatin1("fontSizeSp")).toInt(20), 8, 32);
        any = true;
    }
    if (css.contains(QString::fromLatin1("lineHeight12")))
    {
        rj.lineHeight = clampi(css.value(QString::fromLatin1("lineHeight12")).toInt(18) * 10, 100, 250);
        any = true;
    }
    static const char *maps[][2] = {
        {"marginTop", "marginTop"}, {"marginRight", "marginRight"},
        {"marginBottom", "marginBottom"}, {"marginLeft", "marginLeft"},
        {"textIndent", "textIndent"}, {"paragraphHeight", "paraSpacing"} };
    for (int i = 0; i < 6; i++)
    {
        if (css.contains(QString::fromLatin1(maps[i][0])))
        {
            const int v = css.value(QString::fromLatin1(maps[i][0])).toInt();
            if (i == 4) rj.textIndent = clampi(v, 0, 50);
            else if (i == 5) rj.paraSpacing = clampi(v, 0, 10);
            else *(i == 0 ? &rj.marginTop : i == 1 ? &rj.marginRight : i == 2 ? &rj.marginBottom : &rj.marginLeft) = clampi(v, 0, 60);
            any = true;
        }
    }
    if (css.contains(QString::fromLatin1("textAlign")))
    {
        static const char *a2s[] = { "justify", "left", "right", "center" };
        const int k = css.value(QString::fromLatin1("textAlign")).toInt(0);
        rj.align = QString::fromLatin1(a2s[k >= 0 && k <= 3 ? k : 0]);
        any = true;
    }
    if (any) rj.save();
    /* 双页：app-State.json alwaysTwoPages → qt-prefs reader/twoPage */
    QFile stF(dataDir() + QString::fromLatin1("/app-State.json"));
    if (stF.open(QIODevice::ReadOnly))
    {
        const QJsonObject stj = QJsonDocument::fromJson(stF.readAll()).object();
        if (stj.contains(QString::fromLatin1("alwaysTwoPages")))
            st.setValue(QString::fromLatin1("reader/twoPage"),
                        stj.value(QString::fromLatin1("alwaysTwoPages")).toBool(false));
    }
    st.setValue(QString::fromLatin1("androidcss/mt"), mt);
    evt(QString::fromLatin1("androidcss_import"),
        QString::fromLatin1("any=%1 em=%2 lh=%3 align=%4")
            .arg(any ? 1 : 0).arg(rj.em).arg(rj.lineHeight).arg(rj.align));
    return any;
}

} // namespace hr
