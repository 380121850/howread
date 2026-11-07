#include "hrai.h"
#include "hrpaths.h"
#include "hrcred.h"

#include <QElapsedTimer> /* P2：SSE 节流 */
#include <QEventLoop>
#include <QJsonArray>
#include <QMutex> /* P1：会话缓存 */
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QUrl>

namespace hr
{

AiConfig AiConfig::load()
{
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    AiConfig c;
    c.base = st.value("ai/base", c.base).toString();
    c.key = hr::unprotectSecret(st.value("ai/key", QString()).toString());
    c.model = st.value("ai/model", c.model).toString();
    c.maxTokens = st.value("ai/maxTokens", c.maxTokens).toInt();
    if (c.maxTokens <= 0) c.maxTokens = 2048;
    c.thinking = st.value("ai/thinking", false).toBool();
    return c;
}

void AiConfig::save() const
{
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    st.setValue("ai/base", base);
    st.setValue("ai/key", hr::protectSecret(key));
    st.setValue("ai/model", model);
    st.setValue("ai/maxTokens", maxTokens);
    st.setValue("ai/thinking", thinking);
}

QString aiAsk(const AiConfig &c, const QString &prompt, QString *err, int maxTokens)
{
    if (!c.usable())
    {
        if (err) *err = "未配置 API Key（qt-prefs.ini 的 ai/key）";
        return QString();
    }
    QJsonObject body;
    body.insert("model", c.model);
    body.insert("max_tokens", maxTokens);
    QJsonArray msgs;
    QJsonObject m;
    m.insert("role", "user");
    m.insert("content", prompt);
    msgs.append(m);
    body.insert("messages", msgs);
    if (c.thinking) /* U6：思考模式（智谱系支持；OpenAI 兼容端不识别则忽略） */
    {
        QJsonObject th;
        th.insert("type", QString("enabled"));
        body.insert("thinking", th);
    }

    QNetworkRequest req(QUrl(c.base + "chat/completions"));
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    req.setRawHeader("Authorization", "Bearer " + c.key.toUtf8());
    QNetworkAccessManager nam;
    nam.setTransferTimeout(30000); /* P1 适配（D10）：超时保护（对齐 WIN read30s） */
    QNetworkReply *rep = nam.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    if (rep->error() != QNetworkReply::NoError)
    {
        if (err) *err = rep->errorString().left(160);
        rep->deleteLater();
        return QString();
    }
    const QByteArray data = rep->readAll();
    rep->deleteLater();
    const QJsonObject obj = QJsonDocument::fromJson(data).object();
    const QString text = obj.value("choices").toArray().at(0)
                             .toObject().value("message").toObject()
                             .value("content").toString();
    if (text.isEmpty() && err) *err = "响应无内容: " + QString::fromUtf8(data.left(160));
    return text;
}

QStringList aiModels(const AiConfig &c, QString *err)
{
    QStringList out;
    if (!c.usable())
    {
        if (err) *err = "未配置 API Key";
        return out;
    }
    QNetworkRequest req(QUrl(c.base + "models"));
    req.setRawHeader("Authorization", "Bearer " + c.key.toUtf8());
    QNetworkAccessManager nam;
    nam.setTransferTimeout(15000); /* P1 适配（D10）：模型列表超时（对齐 WIN connect15s） */
    QNetworkReply *rep = nam.get(req);
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    if (rep->error() != QNetworkReply::NoError)
    {
        if (err) *err = rep->errorString().left(160);
        rep->deleteLater();
        return out;
    }
    const QJsonArray arr =
        QJsonDocument::fromJson(rep->readAll()).object().value("data").toArray();
    rep->deleteLater();
    for (const QJsonValue &v : arr) out << v.toObject().value("id").toString();
    if (out.isEmpty() && err) *err = "响应无模型列表";
    return out;
}

/* P1 适配（D11）：会话级缓存（进程内 ≤300 条；同键命中不再计费） */
QString aiAskCached(const AiConfig &c, const QString &ckey, const QString &prompt,
                    QString *err, int maxTokens)
{
    static QMutex mu;
    static QHash<QString, QString> cache;
    mu.lock();
    const QHash<QString, QString>::const_iterator it = cache.constFind(ckey);
    if (it != cache.constEnd())
    {
        const QString hit = it.value();
        mu.unlock();
        if (err) err->clear();
        return hit;
    }
    mu.unlock();
    const QString ans = aiAsk(c, prompt, err, maxTokens);
    if (!ans.isEmpty())
    {
        mu.lock();
        if (cache.size() > 300) cache.clear();
        cache.insert(ckey, ans);
        mu.unlock();
    }
    return ans;
}

/* P2 适配（D9）：SSE 流式（对齐 WIN AiClient.readStream：data: 行解析、delta.content
 * 累积 + reasoning_content 兜底、150ms 节流、[DONE] 止、finish_reason=length 记截断） */
QString aiAskStream(const AiConfig &c, const QString &prompt,
                    const std::function<void(const QString &)> &onDelta,
                    QString *err, int maxTokens)
{
    if (!c.usable())
    {
        if (err) *err = QString::fromUtf8("未配置 API Key（qt-prefs.ini 的 ai/key）");
        return QString();
    }
    QJsonObject body;
    body.insert(QString::fromLatin1("model"), c.model);
    body.insert(QString::fromLatin1("max_tokens"), maxTokens);
    body.insert(QString::fromLatin1("stream"), true);
    QJsonArray msgs;
    QJsonObject m;
    m.insert(QString::fromLatin1("role"), QString::fromLatin1("user"));
    m.insert(QString::fromLatin1("content"), prompt);
    msgs.append(m);
    body.insert(QString::fromLatin1("messages"), msgs);
    if (c.thinking)
    {
        QJsonObject th;
        th.insert(QString::fromLatin1("type"), QString::fromLatin1("enabled"));
        body.insert(QString::fromLatin1("thinking"), th);
    }
    QNetworkRequest req(QUrl(c.base + QString::fromLatin1("chat/completions")));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QString::fromLatin1("application/json"));
    req.setRawHeader("Authorization", "Bearer " + c.key.toUtf8());
    QNetworkAccessManager nam;
    nam.setTransferTimeout(120000); /* P2：流式读上限（对齐 WIN read120s） */
    QNetworkReply *rep = nam.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    QString content, reasoning, finish;
    QElapsedTimer cbT;
    cbT.start();
    QObject::connect(rep, &QNetworkReply::readyRead, rep,
        [&content, &reasoning, &finish, rep, &onDelta, &cbT]()
    {
        while (rep->canReadLine())
        {
            const QString line = QString::fromUtf8(rep->readLine()).trimmed();
            if (!line.startsWith(QString::fromLatin1("data:"))) continue;
            const QString data = line.mid(5).trimmed();
            if (data == QString::fromLatin1("[DONE]")) return;
            const QJsonObject obj = QJsonDocument::fromJson(data.toUtf8()).object();
            const QJsonObject ch0 = obj.value(QString::fromLatin1("choices"))
                                        .toArray()
                                        .at(0)
                                        .toObject();
            const QJsonObject d = ch0.value(QString::fromLatin1("delta")).toObject();
            content += d.value(QString::fromLatin1("content")).toString();
            reasoning += d.value(QString::fromLatin1("reasoning_content")).toString();
            const QString fin = ch0.value(QString::fromLatin1("finish_reason")).toString();
            if (!fin.isEmpty()) finish = fin;
            if (onDelta && cbT.elapsed() >= 150)
            {
                onDelta(content);
                cbT.restart();
            }
        }
    });
    QEventLoop loop;
    QObject::connect(rep, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    if (rep->error() != QNetworkReply::NoError)
    {
        if (err) *err = rep->errorString().left(160);
        rep->deleteLater();
        return QString();
    }
    rep->deleteLater();
    QString reply = content;
    if (reply.trimmed().isEmpty()) reply = reasoning; /* 推理模型兜底 */
    if (reply.trimmed().isEmpty())
    {
        if (err) *err = QString::fromUtf8("empty");
        return QString();
    }
    if (onDelta) onDelta(reply); /* final flush */
    return reply;
}

} // namespace hr
