/* 安卓 WebDavSyncer/ProfileStateIO 三方合并语义移植（v1.3.0：A1 app-CSS 双向、
 * D app-AI、E app-NetworkSources 全局文件参与）。头注语义锚点见 hrmerge.h。 */
#include "hrmerge.h"

#include <QJsonDocument>

namespace hr
{

static QString jKeyOf(const QJsonValue &v)
{
    if (v.isObject())
        return QString::fromUtf8(QJsonDocument(v.toObject()).toJson(QJsonDocument::Compact));
    if (v.isArray())
        return QString::fromUtf8(QJsonDocument(v.toArray()).toJson(QJsonDocument::Compact));
    return v.toVariant().toString();
}

bool jIsEmptyValue(const QJsonValue &v)
{
    if (v.isNull() || v.isUndefined()) return true;
    if (v.isString())
    {
        const QString s = v.toString().trimmed();
        return s.isEmpty() || s == QLatin1String("[]") || s == QLatin1String("{}");
    }
    if (v.isArray()) return v.toArray().isEmpty();
    if (v.isObject()) return v.toObject().isEmpty();
    return false;
}

bool jEqVal(const QJsonValue &a, const QJsonValue &b)
{
    if (a.isObject() || a.isArray() || b.isObject() || b.isArray())
        return jKeyOf(a) == jKeyOf(b);
    if (a.type() != b.type())
    {
        if (a.isDouble() && b.isDouble()) return a.toDouble() == b.toDouble();
        return false;
    }
    return a == b;
}

QJsonArray jUnionArrays(const QJsonValue &lv, const QJsonValue &rv)
{
    QJsonArray out;
    QSet<QString> seen;
    const QJsonValue sides[2] = { lv, rv };
    for (int s = 0; s < 2; s++)
    {
        const QJsonValue &arr = sides[s];
        if (!arr.isArray())
        {
            /* 双改冲突中标量侧：非空则保留为值（安卓 unionArrays 同款） */
            if (!arr.isNull() && !arr.isUndefined() && !jIsEmptyValue(arr))
            {
                const QString key = jKeyOf(arr);
                if (!seen.contains(key)) { seen.insert(key); out.append(arr); }
            }
            continue;
        }
        const QJsonArray a = arr.toArray();
        for (const QJsonValue &v : a)
        {
            const QString key = jKeyOf(v);
            if (!seen.contains(key)) { seen.insert(key); out.append(v); }
        }
    }
    return out;
}

static QJsonObject jMerge3Impl(const QJsonObject &base, const QJsonObject &local,
                               const QJsonObject &remote, const QSet<QString> &keepLocal)
{
    QJsonObject out;
    QSet<QString> keys;
    for (auto it = local.constBegin(); it != local.constEnd(); ++it) keys.insert(it.key());
    for (auto it = remote.constBegin(); it != remote.constEnd(); ++it) keys.insert(it.key());
    for (auto it = base.constBegin(); it != base.constEnd(); ++it) keys.insert(it.key());
    for (const QString &k : keys)
    {
        if (keepLocal.contains(k))
        {
            if (local.contains(k)) out.insert(k, local.value(k));
            continue;
        }
        const bool lb = local.contains(k), rb = remote.contains(k), bb = base.contains(k);
        const QJsonValue lv = local.value(k), rv = remote.value(k), bv = base.value(k);
        const bool localChanged = (lb != bb) || (lb && !jEqVal(lv, bv));
        const bool remoteChanged = (rb != bb) || (rb && !jEqVal(rv, bv));
        if (bb && lb && rb && bv.isObject() && lv.isObject() && rv.isObject())
        {
            out.insert(k, jMerge3Impl(bv.toObject(), lv.toObject(), rv.toObject(), keepLocal));
            continue;
        }
        if (!bb && lb && rb && lv.isObject() && rv.isObject())
        {
            out.insert(k, jFirstSyncMerge(lv.toObject(), rv.toObject(), keepLocal));
            continue;
        }
        if (localChanged && remoteChanged)
        {
            if (lv.isArray() || rv.isArray())
                out.insert(k, jUnionArrays(lv, rv));
            else if (lb)
                out.insert(k, lv);
            /* lb==false：本地删且远端改 → 保持删除 */
        }
        else if (remoteChanged)
        {
            if (rb)
                out.insert(k, rv);
            else if (lb)
                out.insert(k, lv); /* 老版本设备不认识的键：保本地，防误清 */
        }
        else if (lb)
        {
            out.insert(k, lv);
        }
    }
    return out;
}

QJsonObject jMerge3(const QJsonObject &base, const QJsonObject &local,
                    const QJsonObject &remote, const QSet<QString> &keepLocal)
{
    return jMerge3Impl(base, local, remote, keepLocal);
}

QJsonObject jFirstSyncMerge(const QJsonObject &local, const QJsonObject &remote,
                            const QSet<QString> &keepLocal)
{
    QJsonObject out;
    QSet<QString> keys;
    for (auto it = local.constBegin(); it != local.constEnd(); ++it) keys.insert(it.key());
    for (auto it = remote.constBegin(); it != remote.constEnd(); ++it) keys.insert(it.key());
    for (const QString &k : keys)
    {
        if (keepLocal.contains(k))
        {
            if (local.contains(k)) out.insert(k, local.value(k));
            continue;
        }
        const bool lb = local.contains(k), rb = remote.contains(k);
        const QJsonValue lv = local.value(k), rv = remote.value(k);
        const bool lEmpty = jIsEmptyValue(lv), rEmpty = jIsEmptyValue(rv);
        if (lb && rb && lv.isObject() && rv.isObject())
        {
            out.insert(k, jFirstSyncMerge(lv.toObject(), rv.toObject(), keepLocal));
            continue;
        }
        if (lEmpty && rEmpty) continue;          /* 两边都未配置 */
        if (lEmpty) out.insert(k, rv);           /* 仅服务器有：采纳 */
        else if (rEmpty) out.insert(k, lv);      /* 仅本地有：保留并上行（治愈服务器） */
        else if (lv.isArray() || rv.isArray())
            out.insert(k, jUnionArrays(lv, rv)); /* 列表：两端条目都留 */
        else
            out.insert(k, rv);                   /* 双设不同：服务器胜 */
    }
    return out;
}

QJsonObject jMergeItemFields(const QJsonObject &l, const QJsonObject &r, const QJsonObject &b)
{
    QJsonObject out;
    QSet<QString> keys;
    for (auto it = l.constBegin(); it != l.constEnd(); ++it) keys.insert(it.key());
    for (auto it = r.constBegin(); it != r.constEnd(); ++it) keys.insert(it.key());
    for (auto it = b.constBegin(); it != b.constEnd(); ++it) keys.insert(it.key());
    for (const QString &k : keys)
    {
        const bool lh = l.contains(k), rh = r.contains(k), bh = b.contains(k);
        const QJsonValue lv = l.value(k), rv = r.value(k), bv = b.value(k);
        const bool localChanged = (lh != bh) || (lh && !jEqVal(lv, bv));
        const bool remoteChanged = (rh != bh) || (rh && !jEqVal(rv, bv));
        if (localChanged)
        {
            if (lh) out.insert(k, lv);
        }
        else if (remoteChanged)
        {
            if (rh) out.insert(k, rv);
        }
        else if (lh)
        {
            out.insert(k, lv);
        }
    }
    return out;
}

} // namespace hr
