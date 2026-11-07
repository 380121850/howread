#include "hrtts.h"

#include <windows.h>
#include <initguid.h>
#include <sapi.h>
#include <shlwapi.h>
/* sapi 头只声明未定义该 GUID（initguid 也不覆盖）——按 SDK 标准值补定义 */
DEFINE_GUID(SPDFID_WaveFormatEx, 0xc31adbae, 0x527f, 0x4ff5, 0xa2, 0x30, 0xf6,
            0x2b, 0xb6, 0x1f, 0xf7, 0x0c); /* 真机 SDK SAPI.Lib 实证（Win10 26100），旧值尾3字节错 */
#include <objbase.h>
#include <QFile>
#include <QSettings>
#include "hrpaths.h" /* P1：tts/* 偏好 */

namespace hr
{

static ISpVoice *g_voice = nullptr;
static bool g_comInit = false;
static bool g_probeDone = false;
static bool g_available = false;

static bool ensureVoice()
{
    if (g_voice) return true;
    if (!g_comInit)
    {
        if (FAILED(CoInitializeEx(NULL, COINIT_APARTMENTTHREADED))) return false;
        g_comInit = true;
    }
    if (FAILED(CoCreateInstance(CLSID_SpVoice, NULL, CLSCTX_ALL, IID_ISpVoice,
                                (void **)&g_voice)))
    {
        g_voice = nullptr;
        return false;
    }
    return true;
}

bool Tts::available()
{
    if (!g_probeDone)
    {
        g_available = ensureVoice();
        g_probeDone = true;
    }
    return g_available;
}

/* P1 适配（D6）：按注册表 token 名选音色——mingw sapi.h 无 SelectVoice(LPCWSTR)，
 * 走 ISpObjectToken::SetId + ISpVoice::SetVoice 等价通道 */
static bool setVoiceToken(const QString &voiceName)
{
    if (!g_voice || voiceName.isEmpty()) return false;
    QSettings root("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens",
                   QSettings::NativeFormat);
    QString tokenId;
    for (const QString &t : root.childGroups())
    {
        if (t == voiceName)
        {
            tokenId = t;
            break;
        }
        QSettings tk("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens/" + t,
                     QSettings::NativeFormat);
        if (tk.value(".").toString() == voiceName)
        {
            tokenId = t;
            break;
        }
    }
    if (tokenId.isEmpty()) return false;
    ISpObjectToken *tok = nullptr;
    if (FAILED(CoCreateInstance(CLSID_SpObjectToken, NULL, CLSCTX_ALL,
                                IID_ISpObjectToken, (void **)&tok)))
        return false;
    const QString fullId = QString("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens\\") +
                           tokenId;
    HRESULT hr = tok->SetId(NULL, fullId.toStdWString().c_str(), FALSE);
    if (SUCCEEDED(hr)) hr = g_voice->SetVoice(tok);
    tok->Release();
    return SUCCEEDED(hr);
}

/* P1 适配（D6）：朗读参数注入（语速/音量/音色；每次朗读前套用） */
static void applyTtsParams()
{
    if (!g_voice) return;
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    g_voice->SetRate(qBound(-10, st.value("tts/rate", 0).toInt(), 10));
    g_voice->SetVolume((USHORT)qBound(0, st.value("tts/volume", 100).toInt(), 100));
    const QString vn = st.value("tts/voice").toString();
    if (!vn.isEmpty())
        setVoiceToken(vn);
}

/* P1 适配（D8）：生词替换（tts/replacements 逐行 "原文=>替换"，tts/replaceEnabled 开关） */
static QString ttsApplyReplacements(const QString &in)
{
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    if (!st.value("tts/replaceEnabled", true).toBool()) return in;
    QString out = in;
    const QStringList lines = st.value("tts/replacements").toString()
                                  .split(QLatin1Char('\n'), Qt::SkipEmptyParts);
    for (const QString &ln : lines)
    {
        const int p = ln.indexOf("=>");
        if (p <= 0) continue;
        const QString from = ln.left(p).trimmed();
        if (!from.isEmpty()) out.replace(from, ln.mid(p + 2).trimmed());
    }
    return out;
}

bool Tts::speakAsync(const QString &text0)
{
    if (!ensureVoice()) return false;
    applyTtsParams(); /* P1（D6） */
    const QString text = ttsApplyReplacements(text0); /* P1（D8） */
    /* SPF_ASYNC(1)；PURGE(2) 清队列实现"重新朗读"。
     * P1 适配（D7）：>8000 字按段落切块排队（原实现整页 left(8000) 截断丢尾） */
    QStringList chunks;
    if (text.size() <= 8000)
    {
        chunks << text;
    }
    else
    {
        QString cur;
        const QStringList paras = text.split(QLatin1Char('\n'));
        for (const QString &para : paras)
        {
            if (!cur.isEmpty() && cur.size() + para.size() + 1 > 8000)
            {
                chunks << cur;
                cur.clear();
            }
            cur += (cur.isEmpty() ? QString() : QString("\n")) + para;
            while (cur.size() > 8000) /* 单段超长硬切 */
            {
                chunks << cur.left(8000);
                cur = cur.mid(8000);
            }
        }
        if (!cur.isEmpty()) chunks << cur;
    }
    bool first = true;
    for (const QString &ck : chunks)
    {
        const std::wstring w = ck.toStdWString();
        if (w.empty()) continue;
        SPEAKFLAGS fl = SPF_ASYNC;
        if (first) fl = (SPEAKFLAGS)(fl | SPF_PURGEBEFORESPEAK);
        if (FAILED(g_voice->Speak(w.c_str(), fl, NULL))) return false;
        first = false;
    }
    return !first || !chunks.isEmpty();
}

void Tts::stop()
{
    if (!g_voice) return;
    g_voice->Speak(L"", SPF_ASYNC | SPF_PURGEBEFORESPEAK, NULL);
}

bool Tts::isSpeaking() /* v0.83.0 W2：SPRIS 连读轮询 */
{
    if (!g_voice) return false;
    SPVOICESTATUS st;
    if (FAILED(g_voice->GetStatus(&st, NULL))) return false;
    return (st.dwRunningState == SPRS_IS_SPEAKING);
}

bool Tts::exportWav(const QStringList &paras, const QString &dest, QString *err)
{
    if (!ensureVoice())
    {
        if (err) *err = "SAPI 不可用";
        return false;
    }
    ISpStream *stream = NULL;
    if (FAILED(CoCreateInstance(CLSID_SpFileStream, NULL, CLSCTX_ALL, IID_ISpStream,
                                (void **)&stream)))
    {
        if (err) *err = "SpFileStream 创建失败";
        return false;
    }
    /* SAPI 写 SMB/NFS 盘可能失败——先写 %TEMP% 再拷回目的地 */
    QString tmpDest = dest;
    {
        wchar_t tp[MAX_PATH * 2] = {0};
        GetTempPathW(MAX_PATH, tp);
        tmpDest = QString::fromWCharArray(tp) + "hr_tts_out.wav";
        QFile::remove(tmpDest);
    }
    /* 批次三修复：写 WAV 走 ISpStream::BindToFile（SPFM_CREATE_ALWAYS + 真实 PCM 格式）。
     * M4 旧实现 SHCreateStreamOnFileEx+SetBaseStream 在 SetBaseStream 处恒报
     * E_INVALIDARG(0x80070057，发行包遗留已知问题)——BindToFile 才是 SpFileStream
     * 的正规写文件通道（格式必须给真实值，NULL → Speak 写出失败）。 */
    WAVEFORMATEX fmt = {};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = 1;
    fmt.nSamplesPerSec = 22050;
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = fmt.nChannels * fmt.wBitsPerSample / 8;
    fmt.nAvgBytesPerSec = fmt.nSamplesPerSec * fmt.nBlockAlign;
    const std::wstring wDest = tmpDest.toStdWString();
    /* 独立诊断实证：sapi.dll 的 SPFM_CREATE_ALWAYS=3（mingw 枚举正确、PSDK 文档值反了），
     * mode=2 才是 E_INVALIDARG；配合上方已纠正的 SPDFID_WaveFormatEx GUID（尾3字节）。 */
    HRESULT hr = stream->BindToFile(wDest.c_str(), SPFM_CREATE_ALWAYS,
                                    &SPDFID_WaveFormatEx, &fmt, 0);
    if (FAILED(hr))
    {
        stream->Release();
        if (err) *err = QString("BindToFile 失败 hr=0x%1").arg((quint32)hr, 8, 16, QChar('0'));
        return false;
    }
    applyTtsParams(); /* P1（D6）：导出同样套用语速/音量/音色 */
    hr = g_voice->SetOutput((IUnknown *)stream, FALSE);
    if (FAILED(hr))
    {
        stream->Close();
        stream->Release();
        if (err) *err = "SetOutput 失败";
        QFile::remove(dest);
        return false;
    }
    for (const QString &p : paras)
    {
        const std::wstring w = p.toStdWString();
        if (w.empty()) continue;
        if (FAILED(g_voice->Speak(w.c_str(), SPF_DEFAULT, NULL)))
        {
            hr = E_FAIL;
            break;
        }
    }
    g_voice->SetOutput(NULL, FALSE);
    if (FAILED(hr))
    {
        stream->Close();
        stream->Release();
        if (err) *err = "Speak 失败（无语音引擎?）";
        QFile::remove(dest);
        return false;
    }
    stream->Close();
    stream->Release();
    if (tmpDest != dest)
    {
        QFile::remove(dest);
        if (!QFile::copy(tmpDest, dest))
        {
            if (err) *err = "TEMP 拷回目的地失败";
            return false;
        }
        QFile::remove(tmpDest);
    }
    return true;
}

/* P1 适配（D6）：音色枚举（注册表 SPCAT_VOICES Tokens；避开 COM 枚举的 mingw 头差异） */
QStringList Tts::voices()
{
    QStringList out;
    QSettings root("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens",
                   QSettings::NativeFormat);
    const QStringList toks = root.childGroups();
    for (const QString &t : toks)
    {
        QSettings tk("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens/" + t,
                     QSettings::NativeFormat);
        const QString nm = tk.value(".").toString();
        out << (nm.isEmpty() ? t : nm);
    }
    out.removeDuplicates();
    return out;
}

} // namespace hr
