/* M4 TTS（Windows SAPI COM 直调；对齐 Java 版 JNA→SAPI 通道）。 */
#ifndef HRTTS_H
#define HRTTS_H

#include <QString>
#include <QStringList>

namespace hr
{

class Tts
{
public:
    static bool speakAsync(const QString &text); /* 异步连读；复用单例 voice */
    static void stop();
    static bool isSpeaking(); /* v0.83.0 W2：连读轮询（SAPI 朗读状态） */
    static bool exportWav(const QStringList &paras, const QString &dest, QString *err);
    static bool available(); /* SAPI 可用性（首次探测并缓存） */
    static QStringList voices(); /* P1（D6）：本机音色枚举（注册表 SPCAT_VOICES） */
};

} // namespace hr

#endif /* HRTTS_H */
