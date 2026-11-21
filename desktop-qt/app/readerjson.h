/* v0.61.0 批次三：reader.json 配置互通（与 Compose 桌面版 ReaderPrefsStore 同文件同键；
 * 另从安卓 app-CSS.json/app-State.json 导入映射，语义对齐 desktop/core AndroidSync）。 */
#ifndef HRREADERJSON_H
#define HRREADERJSON_H

#include <QJsonObject>
#include <QString>

namespace hr
{

struct RJson
{
    int lineHeight = 180;  /* %（100~250） */
    int marginTop = 4, marginRight = 10, marginBottom = 4, marginLeft = 10; /* pt */
    int textIndent = 20;   /* 0.1em */
    int paraSpacing = 1;   /* 0.1em */
    float em = 12.0f;      /* Qt 侧绝对字号（reader.json 扩展键，Compose 忽略） */
    QString align = "justify"; /* left/justify/right/center */
    QString readTheme = "day";
    bool useDocCss = true; /* WIN 键 useDocumentCss（P0：互通保留，不再恒 true） */
    QString readTexture = "none"; /* P2（B6）：阅读质感（P0 起保留未知键，本批转管理键） */
    bool load();          /* dataDir/reader.json；不存在/损坏返回 false */
    void save() const;    /* 同键写回（Compose 可读） */
};

/* 安卓 app-CSS.json/app-State.json → reader.json（mtime 门控一次性采纳）；
 * 命中导入返回 true。映射：fontSizeSp→em、lineHeight12×10→lineHeight、段距/缩进/边距直映、
 * textAlign 枚举→align、alwaysTwoPages→twoPage（写 qt-prefs.ini reader/twoPage）。 */
bool importAndroidCssIfNeeded();

/* v1.3.0（A1）：Qt 排版真源(reader.json) ⇄ 安卓 app-CSS.json 双向——
 * mirrorReaderToAndroidCss：9 个映射键从 reader.json 镜像进本地 app-CSS.json 副本
 * （文件已存在才镜像——首装采纳前不种子）；applyAndroidCssKeys：合并结果即时映射回
 * reader.json（与导入器同一张映射表）。 */
bool mirrorReaderToAndroidCss();
void applyAndroidCssKeys(const QJsonObject &css);

} // namespace hr

#endif /* HRREADERJSON_H */
