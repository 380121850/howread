package howread.app

import howread.core.AppPaths
import java.io.File
import java.util.Properties

/** 界面/书库轻偏好（v0.30.0：lib.properties，键值存储；视图/排序/默认阅读模式/侧栏/封面/在线阅读开关）。 */
object LibPrefs {
    @Volatile
    private var cached: Properties? = null

    @Synchronized
    private fun file(): File = File(AppPaths.dataDir, "lib.properties")

    @Synchronized
    private fun j(): Properties {
        cached?.let { return it }
        val p = Properties()
        runCatching { file().inputStream().use { p.load(it) } }
        cached = p
        return p
    }

    fun str(k: String, d: String): String = j().getProperty(k, d)
    fun bool(k: String, d: Boolean): Boolean = j().getProperty(k, d.toString())?.toBooleanStrictOrNull() ?: d

    @Synchronized
    fun put(k: String, v: Any) {
        val p = j()
        p.setProperty(k, v.toString())
        runCatching { file().outputStream().use { p.store(it, "HowRead desktop prefs") } }
        cached = p
    }

    /** 外部（core 字段映射）直接改过 lib.properties 后刷新缓存。 */
    @Synchronized
    fun invalidate() { cached = null }

    fun defaultView(): LibView = LibView.entries.firstOrNull { it.name == str("defaultView", "GRID") } ?: LibView.GRID
    fun defaultSort(): SortKey = listOf(SortKey.TITLE, SortKey.SIZE, SortKey.DATE, SortKey.LANG, SortKey.RECENT, SortKey.FORMAT,
        SortKey.AUTHOR, SortKey.SERIES, SortKey.PAGES)   // 检视修复：菜单可存的白名单不全导致重启回退
        .firstOrNull { it.name == str("defaultSort", "TITLE") } ?: SortKey.TITLE
    fun defaultReaderModePage(): Boolean = str("defaultReaderMode", "scroll") == "page"
}
