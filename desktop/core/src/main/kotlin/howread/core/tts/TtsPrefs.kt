package howread.core.tts

import howread.core.AppPaths
import org.json.JSONObject
import java.io.File

/** 朗读参数（偏好页设置；每次朗读即时读取）。
 *  @JvmOverloads：v0.52 加 replacements/replaceEnabled 后保持 Java 侧旧 4 参构造可用（Tts2Smoke）。 */
data class TtsPrefs @JvmOverloads constructor(
    val rate: Int = 0,          // 语速 -10..10（System.Speech Rate 档）
    val volume: Int = 100,      // 音量 0..100
    val voice: String = "",     // 指定语音名；空=自动（中文段优先 zh-CN 音色）
    val pauseMs: Int = 0,       // 段间额外停顿 0..2000ms
    // v0.52.0 生词文本替换（对齐安卓 app-TextReplacement.json + isEnableTextReplacement）
    val replacements: String = "",   // 规则：每行一条 "原文=>替换"
    val replaceEnabled: Boolean = false,
)

/** 存 %LOCALAPPDATA%\HowRead\tts.json。 */
object TtsPrefsStore {
    private val file: File get() = File(AppPaths.dataDir, "tts.json")

    @Volatile
    private var cached: TtsPrefs? = null

    @Synchronized
    fun load(): TtsPrefs {
        cached?.let { return it }
        val p = try {
            val j = JSONObject(file.readText(Charsets.UTF_8))
            TtsPrefs(
                rate = j.optInt("rate", 0).coerceIn(-10, 10),
                volume = j.optInt("volume", 100).coerceIn(0, 100),
                voice = j.optString("voice", ""),
                pauseMs = j.optInt("pauseMs", 0).coerceIn(0, 2000),
                replacements = j.optString("replacements", ""),
                replaceEnabled = j.optBoolean("replaceEnabled", false),
            )
        } catch (_: Exception) {
            TtsPrefs()
        }
        cached = p
        return p
    }

    @Synchronized
    fun save(p: TtsPrefs) {
        file.writeText(
            JSONObject().put("rate", p.rate).put("volume", p.volume)
                .put("voice", p.voice).put("pauseMs", p.pauseMs)
                .put("replacements", p.replacements).put("replaceEnabled", p.replaceEnabled).toString(),
            Charsets.UTF_8,
        )
        cached = p
    }
}

/** 朗读文本替换（对齐安卓 TextReplacement）：每行 "原文=>替换"，按行序字面替换；空行/无=>跳过。 */
object TtsReplace {
    fun apply(text: String, rules: String): String {
        var t = text
        for (line in rules.split('\n')) {
            val i = line.indexOf("=>")
            if (i <= 0) continue
            val a = line.substring(0, i)
            val b = line.substring(i + 2)
            if (a.isNotEmpty()) t = t.replace(a, b)
        }
        return t
    }
}
