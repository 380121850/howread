package howread.core.library

import howread.core.TestLog
import howread.core.data.Db
import howread.core.model.RemoteSource
import howread.core.remote.RemoteClients

/**
 * 远程源"扫描书籍"（v0.31.0 对齐安卓）：递归枚举远程目录（深度≤3、文件≤300），
 * 把全部支持格式的文件以 remote://<源id>/<rel> 登记进书库——**不自动下载**；
 * 点开时由应用层按单本缓存（缓存默认在软件目录 data/cache 下），已缓存秒开。
 */
object RemoteScanner {
    private const val MAX_DEPTH = 3
    private const val MAX_FILES = 300

    fun remotePath(s: RemoteSource, rel: String): String = "remote://" + s.id + "/" + rel.trim('/')

    /** 递归枚举并登记，返回登记总数（含已登记）。onProgress 汇报当前目录。 */
    fun registerSource(s: RemoteSource, onProgress: (String) -> Unit = {}): Int {
        val t0 = System.currentTimeMillis()
        var n = 0
        var partial = false
        val keep = HashSet<String>()
        val c = RemoteClients.create(s)
        try {
            c.test()
            val queue = ArrayDeque<Pair<String, Int>>()   // relPath to depth
            queue.add("" to 0)
            while (queue.isNotEmpty() && n < MAX_FILES) {
                val (rel, depth) = queue.removeFirst()
                onProgress(rel)
                val items = runCatching { c.list(rel) }
                    .onFailure { partial = true }   // 检视修复：中途断网不裁剪（部分集合会误删未扫到的书）
                    .getOrDefault(emptyList())
                for (e in items) {
                    if (n >= MAX_FILES) break
                    if (e.isDir) {
                        if (depth < MAX_DEPTH) queue.add(e.relPath to depth + 1)
                        continue
                    }
                    if (!Scanner.isBookFile(e.name)) continue
                    val title = e.name.substringBeforeLast('.', e.name)
                    Db.upsertFileMeta(remotePath(s, e.relPath), title, e.size, 0L)
                    keep.add(e.relPath)
                    n++
                }
            }
            // 检视修复：完整扫描后裁剪源上已删除/改名的登记书（partial 不裁，防误删）
            if (!partial) runCatching { Db.pruneRemoteSource(s.id, keep) }
        } finally {
            runCatching { c.close() }
        }
        TestLog.evt("remote_scan_done", "src=${s.name} registered=$n partial=$partial ${System.currentTimeMillis() - t0}ms")
        return n
    }
}
