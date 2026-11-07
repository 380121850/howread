/* M4 AI 客户端（OpenAI 兼容 /chat/completions；对齐 desktop AiClient 通道）。 */
#ifndef HRAI_H
#define HRAI_H

#include <QString>
#include <QStringList>
#include <functional>

namespace hr
{

struct AiConfig
{
    QString base = "https://open.bigmodel.cn/api/paas/v4/";
    QString key;
    QString model = "glm-4-flash";
    int maxTokens = 2048;  /* U6：输出上限 */
    bool thinking = false; /* U6：思考模式（服务商支持时携带，不支持则忽略） */
    static AiConfig load();
    void save() const;
    bool usable() const { return !key.isEmpty(); }
};

/* 同步问答（可在 QThreadPool 工作线程调用；err 非 0 时含错误文本） */
QString aiAsk(const AiConfig &c, const QString &prompt, QString *err, int maxTokens = 2048);

/* P2 适配（D9）：SSE 流式问答（150ms 节流 onDelta；reasoning 兜底；对齐 WIN readStream） */
QString aiAskStream(const AiConfig &c, const QString &prompt,
                    const std::function<void(const QString &)> &onDelta,
                    QString *err, int maxTokens = 2048);

/* P1 适配（D11）：会话级缓存问答（键=调用方给定；命中不重复计费，进程内 ≤300 条） */
QString aiAskCached(const AiConfig &c, const QString &ckey, const QString &prompt,
                    QString *err, int maxTokens = 2048);

/* U6：GET {base}/models → 模型 id 列表（OpenAI 兼容） */
QStringList aiModels(const AiConfig &c, QString *err = nullptr);

} // namespace hr

#endif /* HRAI_H */
