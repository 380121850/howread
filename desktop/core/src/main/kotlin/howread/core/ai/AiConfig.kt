package howread.core.ai

import howread.core.AppPaths
import org.json.JSONObject
import java.io.File

/** AI 大模型配置。默认值即 bigmodel（智谱 openai 兼容端点）。 */
data class AiConfig(
    val protocol: String = "openai",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/",
    val apiKey: String = "",
    val model: String = "glm-4-flash",
    val maxTokens: Int = 4096,
    val thinking: Boolean = false,
)

/** 配置存 %LOCALAPPDATA%\\HowRead\\ai.json（明文本机存储；安卓侧为加密 prefs）。 */
object AiConfigStore {
    private val file: File get() = File(AppPaths.dataDir, "ai.json")

    @Volatile
    private var cached: AiConfig? = null

    @Synchronized
    fun load(): AiConfig {
        cached?.let { return it }
        val c = try {
            val j = JSONObject(file.readText(Charsets.UTF_8))
            AiConfig(
                protocol = j.optString("protocol", "openai"),
                baseUrl = j.optString("baseUrl", AiConfig().baseUrl),
                apiKey = howread.core.Creds.unprotect(j.optString("apiKey", "")),
                model = j.optString("model", AiConfig().model),
                maxTokens = j.optInt("maxTokens", 4096),
                thinking = j.optBoolean("thinking", false),
            )
        } catch (_: Exception) {
            AiConfig()
        }
        cached = c
        return c
    }

    @Synchronized
    fun save(c: AiConfig) {
        val j = JSONObject()
            .put("protocol", c.protocol)
            .put("baseUrl", c.baseUrl)
            .put("apiKey", howread.core.Creds.protect(c.apiKey)) // DPAPI 本机加密（安卓 KeyStore 等价）
            .put("model", c.model)
            .put("maxTokens", c.maxTokens)
            .put("thinking", c.thinking)
        file.writeText(j.toString(), Charsets.UTF_8)
        cached = c
    }

    @Synchronized
    fun invalidate() { cached = null }

    fun configured(c: AiConfig = load()): Boolean =
        c.apiKey.isNotBlank() && c.baseUrl.isNotBlank() && c.model.isNotBlank()
}
