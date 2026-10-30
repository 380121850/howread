package howread.core.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import howread.core.TestLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 统一 LLM 访问层（安卓 AiClient 的桌面移植，openai 兼容协议）：
 * 覆盖 OpenAI / GLM(bigmodel) / DeepSeek / OpenRouter / 本地网关。
 *
 * GLM 适配（安卓实测经验）：GLM-4.5+ 默认开思考且静默忽略 vLLM 式开关（慢 4-5 倍），
 * 非官方端点附带 thinking 开关字段；端点对扩展字段报 400 的组合被记忆，后续直接跳过。
 */
object AiClient {

    class TestResult {
        var ok = false
        var error = "" // ""|"auth"|"rate"|"timeout"|"network"|"model"|"empty"|"no_config"|"other"
        var detail = ""
        var truncated = false
        var reply = ""
    }

    fun interface StreamCallback {
        fun onDelta(fullTextSoFar: String)
    }

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    private fun shared(longRead: Boolean): OkHttpClient =
        clients.computeIfAbsent(if (longRead) "long" else "short") {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(if (longRead) 120 else 30, TimeUnit.SECONDS)
                .build()
        }

    /** base|model 记忆：对 GLM thinking 开关报 400 的端点组合，后续跳过该字段。 */
    private val thinkingUnsupported = ConcurrentHashMap.newKeySet<String>()

    /** 最小真实请求验证连通/凭据/模型名（max_tokens=5，几乎零成本）。 */
    fun testConnection(cfg: AiConfig): TestResult =
        if (!AiConfigStore.configured(cfg)) TestResult().apply { error = "no_config" }
        else chat(cfg, "ping", 5, null)

    fun chat(cfg: AiConfig, userText: String, maxTokens: Int, stream: StreamCallback? = null): TestResult {
        val res = TestResult()
        val t0 = System.currentTimeMillis()
        val client = shared(stream == null)
        val base = cfg.baseUrl.trim().trimEnd('/')
        try {
            val body = JSONObject()
                .put("model", cfg.model.trim())
                .put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
            val official = base.contains("api.openai.com")
            val blocked = official || thinkingUnsupported.contains(base + "|" + cfg.model)
            if (!blocked) {
                body.put("chat_template_kwargs", JSONObject().put("enable_thinking", cfg.thinking))
                body.put("enable_thinking", cfg.thinking)
                body.put("thinking", JSONObject().put("type", if (cfg.thinking) "enabled" else "disabled"))
            }
            body.put("stream", stream != null)
            return chatOpenAi(client, base, cfg.apiKey.trim(), body, official, stream, res, t0)
        } catch (_: SocketTimeoutException) {
            res.error = "timeout"; return res
        } catch (_: UnknownHostException) {
            res.error = "network"; return res
        } catch (_: Exception) {
            res.error = "other"; return res
        }
    }

    private fun requestOf(base: String, apiKey: String, body: JSONObject): Request =
        Request.Builder()
            .url(base + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun chatOpenAi(
        client: OkHttpClient, base: String, apiKey: String, body: JSONObject,
        official: Boolean, stream: StreamCallback?, res: TestResult, t0: Long,
    ): TestResult {
        var response = client.newCall(requestOf(base, apiKey, body)).execute()
        if (response.code == 400 && !official) {
            val err = response.body?.string() ?: ""
            response.close()
            if (err.contains("think")) thinkingUnsupported.add(base + "|" + body.optString("model"))
            body.remove("thinking")
            body.remove("chat_template_kwargs")
            body.remove("enable_thinking")
            body.put("stream", false)
            response = client.newCall(requestOf(base, apiKey, body)).execute()
        }
        try {
            if (body.optBoolean("stream", false) && response.isSuccessful) {
                val sres = readStream(response, stream, res)
                if (sres.ok) return sres
                if (sres.error == "empty") {
                    // 网关忽略 stream=true：回退一次普通请求
                    response.close()
                    body.put("stream", false)
                    response = client.newCall(requestOf(base, apiKey, body)).execute()
                } else return sres
            }
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                res.error = classify(response.code)
                res.detail = "HTTP " + response.code + " " + text.take(200)
                return res
            }
            res.reply = extractText(text)
            if (res.reply.isBlank()) {
                res.error = "empty"
                res.detail = "HTTP 200 " + text.take(200)
                return res
            }
            res.ok = true
            res.truncated = lengthTruncated(text)
            TestLog.evt("ai_chat_done", (System.currentTimeMillis() - t0).toString() + "ms out=" + res.reply.length)
            return res
        } finally {
            response.close()
        }
    }

    /** 消费 openai 兼容 SSE：150ms 节流 onDelta；content 累积，reasoning 作空回退。 */
    private fun readStream(response: okhttp3.Response, stream: StreamCallback?, res: TestResult): TestResult {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var finish: String? = null
        var lastCb = 0L
        response.body?.byteStream()?.bufferedReader(Charsets.UTF_8)?.use { ins ->
            while (true) {
                val line = ins.readLine() ?: break
                if (Thread.currentThread().isInterrupted) {
                    res.error = "cancelled"; return res
                }
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val obj = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    continue
                }
                val ch0 = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                ch0.optJSONObject("delta")?.let { d ->
                    d.optString("content").takeIf { it.isNotEmpty() }?.let { content.append(it) }
                    d.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                }
                ch0.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { finish = it }
                if (stream != null && System.currentTimeMillis() - lastCb >= 150) {
                    lastCb = System.currentTimeMillis()
                    stream.onDelta(content.toString())
                }
            }
        }
        stream?.onDelta(content.toString()) // final flush
        var reply = content.toString()
        if (reply.isBlank()) reply = reasoning.toString()
        if (reply.isBlank()) {
            res.error = "empty"; return res
        }
        res.reply = reply
        res.ok = true
        res.truncated = finish == "length"
        return res
    }

    private fun extractText(body: String): String = try {
        val msg = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        var c = msg.optString("content")
        if (c.isBlank()) c = msg.optString("reasoning_content") // 推理模型可见文本兜底
        c
    } catch (_: Exception) {
        ""
    }

    private fun lengthTruncated(body: String): Boolean = try {
        JSONObject(body).getJSONArray("choices").getJSONObject(0).optString("finish_reason") == "length"
    } catch (_: Exception) {
        false
    }

    private fun classify(code: Int): String = when (code) {
        401, 403 -> "auth"
        429 -> "rate"
        404 -> "model"
        else -> "other"
    }

    /** GET {base}/models 拉取模型列表（配置窗口点选）。失败返回 null。 */
    fun listModels(cfg: AiConfig): List<String>? {
        val base = cfg.baseUrl.trim().trimEnd('/')
        return try {
            val req = Request.Builder()
                .url(base + "/models")
                .header("Authorization", "Bearer " + cfg.apiKey.trim())
                .get()
                .build()
            shared(false).newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return null
                val j = JSONObject(text)
                val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: return emptyList()
                val ids = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    var id = o.optString("id", o.optString("name"))
                    if (id.startsWith("models/")) id = id.removePrefix("models/")
                    if (id.isNotBlank()) ids.add(id)
                }
                ids.sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        } catch (_: Exception) {
            null
        }
    }
}
