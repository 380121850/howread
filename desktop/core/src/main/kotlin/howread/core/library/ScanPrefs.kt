package howread.core.library

import howread.core.AppPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 书库格式过滤（v0.30.0 偏好→书库设置→格式配置）：scan.json exts 为空/缺省 = 全部格式。 */
object ScanPrefs {
    @Volatile
    private var cached: Set<String>? = null

    @Synchronized
    private fun file(): File = File(AppPaths.dataDir, "scan.json")

    /** 生效的扩展名集合；null = 全部（未配置过）。 */
    @Synchronized
    fun configured(): Set<String>? {
        cached?.let { return it }
        cached = try {
            val j = JSONObject(file().readText(Charsets.UTF_8))
            val a = j.optJSONArray("exts") ?: return null
            val set = (0 until a.length()).map { a.optString(it).lowercase() }.filter { it.isNotBlank() }.toSet()
            if (set.isEmpty() || set == Scanner.BOOK_EXTS) null else set
        } catch (_: Exception) {
            null
        }
        return cached
    }

    fun enabled(path: String): Boolean {
        val set = configured() ?: return true
        return path.substringAfterLast('.', "").lowercase() in set
    }

    @Synchronized
    fun setExts(list: List<String>) {
        val set = list.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        val j = JSONObject()
        if (set.isEmpty() || set == Scanner.BOOK_EXTS) {
            file().delete()
        } else {
            j.put("exts", JSONArray(set.toList()))
            file().writeText(j.toString(), Charsets.UTF_8)
        }
        cached = null
    }
}
