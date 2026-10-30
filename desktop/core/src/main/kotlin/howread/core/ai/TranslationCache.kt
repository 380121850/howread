package howread.core.ai

import howread.core.AppPaths
import howread.core.TestLog
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * 段级翻译缓存：key = pid|src|tgt（pid 已含原文 md5），内存命中 + JSON 落盘
 * （cache/ai/<书路径md5>.json，每 10 段增量刷盘）。二次阅读同书同段零请求。
 */
class TranslationCache(private val file: File) {
    private val lock = Any()
    private val map = HashMap<String, String>()
    private var dirty = false
    private var saves = 0

    init {
        runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                map[e.getString(0) + "|" + e.getString(1) + "|" + e.getString(2)] = e.getString(3)
            }
        }
        TestLog.evt("ai_cache_load", file.name + " entries=" + map.size)
    }

    fun lookup(pid: String, src: String, tgt: String): String? =
        synchronized(lock) { map[pid + "|" + src + "|" + tgt]?.takeIf { it.isNotEmpty() } }

    fun save(pid: String, src: String, tgt: String, tran: String) {
        synchronized(lock) {
            map[pid + "|" + src + "|" + tgt] = tran
            dirty = true
            saves++
        }
        if (saves % 10 == 0) flush()
    }

    fun flush() {
        synchronized(lock) {
            if (!dirty) return
            val ok = runCatching {
                val arr = JSONArray()
                for ((k, v) in map) {
                    val p = k.split("|")
                    if (p.size == 3) arr.put(JSONArray().put(p[0]).put(p[1]).put(p[2]).put(v))
                }
                file.writeText(arr.toString(), Charsets.UTF_8)
            }.isSuccess
            // 检视修复：写盘失败保持 dirty——否则本轮及后续增量全部静默不落盘
            if (!ok) TestLog.evt("tc_flush_fail", "bytes=" + map.size)
            if (ok) dirty = false
        }
    }

    companion object {
        fun forBook(bookPath: String): TranslationCache {
            val d = File(AppPaths.cacheDir, "ai").apply { mkdirs() }
            val h = MessageDigest.getInstance("MD5").digest(bookPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return TranslationCache(File(d, h + ".json"))
        }
    }
}
