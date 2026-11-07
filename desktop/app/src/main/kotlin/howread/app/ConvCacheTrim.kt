package howread.app

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File

/** 转换/封面缓存帽（v0.58，对齐 Qt v0.104 convCacheTrim；安卓与桌面此前对 convert/txt/zip/covers
 *  四目录均无上限——Qt 二轮复核定谳的两侧同款真空缺）：按条目 mtime 从旧到新整条目删除直到 ≤ 上限。
 *  护栏：最近 24h 写入的条目不回收（在读书的 txt 转码副本/转换产物都是新文件，天然免护）；
 *  Windows 上打开中的文件删除失败=自动跳过；cap=lib.properties convCapMB（默认 500，0=不限）；
 *  开书路径调用十分钟一窗，不构成负担。 */
object ConvCacheTrim {
    @Volatile private var lastRun = 0L

    fun capMB(): Long = LibPrefs.str("convCapMB", "500").toLongOrNull()?.coerceIn(0, 1024L * 1024L) ?: 500L

    fun trim() {
        val cap = capMB() * 1024L * 1024L
        if (cap <= 0L) return
        val now = System.currentTimeMillis()
        if (now - lastRun < 10 * 60_000L) return
        lastRun = now
        var total = 0L
        val items = ArrayList<Triple<File, Long, Long>>()   // (条目, 字节, mtime)
        for (d in listOf("convert", "txt", "zip", "covers")) {
            for (e in File(AppPaths.cacheDir, d).listFiles() ?: emptyArray()) {
                val sz = if (e.isDirectory) e.walkTopDown().filter { it.isFile }.sumOf { it.length() } else e.length()
                items.add(Triple(e, sz, e.lastModified()))
                total += sz
            }
        }
        if (total <= cap) return
        val floor = now - 24L * 3600_000L
        for (t in items.sortedBy { it.third }) {
            if (total <= cap) break
            if (t.third >= floor) continue
            if (runCatching { t.first.deleteRecursively() }.getOrDefault(false)) {
                total -= t.second
                TestLog.evt("conv_cap_evict", "name=" + t.first.name + " freed=" + t.second)
            }
        }
    }

    /** 立即执行一次（缓存管理对话框保存上限时）。 */
    fun forceTrim() {
        lastRun = 0L
        trim()
    }
}
