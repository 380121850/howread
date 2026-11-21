#ifndef HRMERGE_H
/* 安卓 WebDavSyncer/ProfileStateIO 三方合并语义的 C++ 移植（v1.3.0：A1/D/E 全局文件参与）。
 * 语义锚点：WebDavSyncer.merge3(:1048)/firstSyncMerge(:1327)/unionArrays(:1116)/
 * isEmptyValue(:1370)；ProfileStateIO.mergeItemFields(:1321)。 */
#define HRMERGE_H

#include <QJsonArray>
#include <QJsonObject>
#include <QSet>
#include <QString>

namespace hr
{

/* "未配置"值判定（安卓 isEmptyValue：空串/"[]"/"{}"/空容器/null） */
bool jIsEmptyValue(const QJsonValue &v);
/* 值等价（安卓 eq：容器按紧凑序列化比较；数值按双精度等） */
bool jEqVal(const QJsonValue &a, const QJsonValue &b);
/* 数组 union（本地序在前、远端新增随后；双改冲突中标量侧保留） */
QJsonArray jUnionArrays(const QJsonValue &lv, const QJsonValue &rv);
/* 递归三方合并（安卓 merge3：单边改胜/双边改本地胜/数组 union/
 * 老版本字段保本地/keepLocal 键恒本地） */
QJsonObject jMerge3(const QJsonObject &base, const QJsonObject &local,
                    const QJsonObject &remote,
                    const QSet<QString> &keepLocal = QSet<QString>());
/* 首装无基线合并（安卓 firstSyncMerge：双设标量服务器胜/数组 union/仅一方有即采纳） */
QJsonObject jFirstSyncMerge(const QJsonObject &local, const QJsonObject &remote,
                            const QSet<QString> &keepLocal = QSet<QString>());
/* 单条目字段级三方（安卓 mergeItemFields：本地改胜平局） */
QJsonObject jMergeItemFields(const QJsonObject &l, const QJsonObject &r, const QJsonObject &b);

} // namespace hr

#endif /* HRMERGE_H */
