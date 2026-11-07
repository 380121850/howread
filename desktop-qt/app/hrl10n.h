/* v0.59.0 批次一：界面多语言（同一份 resources/i18n/tables.json，Compose L10n.kt 同语义）。
 * lang 存 dataDir/lang.json（缺省=系统语言：zh* → zh，否则 en）；
 * s(zh,en)：zh 返回原文；非 zh/en 语种按中文原文查表，未命中回退 en（zh-rTW 回退中文）。 */
#ifndef HRL10N_H
#define HRL10N_H

#include <QString>
#include <QStringList>

namespace hr
{
namespace l10n
{
QString s(const QString &zh, const QString &en);
QString currentLang();
QStringList languages(); /* tables.json 全语种（批次三 42 语下拉） */
void setLang(const QString &lang); /* 落 lang.json；界面文字重启后生效 */
}
} // namespace hr

#endif /* HRL10N_H */
