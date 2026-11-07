/* v0.61.0 批次三：Windows DPAPI 凭据保护（密码/API Key 落盘加密，明文兼容读取）。 */
#ifndef HRCRED_H
#define HRCRED_H

#include <QString>

namespace hr
{
/* 加密：Windows 下返回 "dpapi:" + base64(CryptProtectData)；失败/非 Windows 原样返回。 */
QString protectSecret(const QString &plain);
/* 解密：带 "dpapi:" 前缀走 CryptUnprotectData（失败返回空）；无前缀=历史明文原样返回。 */
QString unprotectSecret(const QString &stored);
} // namespace hr

#endif /* HRCRED_H */
