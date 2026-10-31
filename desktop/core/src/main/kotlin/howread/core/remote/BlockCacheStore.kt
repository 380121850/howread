package howread.core.remote

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 远程书块缓存（v0.33.0 建立；v0.34.0 引擎接线启用并补齐预取模型，对齐安卓 RemoteBookSession）：
 * 内存 LRU + 磁盘块，目录 cache/remote-blocks/&lt;key&gt;/（data.bin / blocks.bin / meta.json）。
 * 文本格式 1MB 块、页面格式（pdf/cbz/xps/djvu…）64KB 块（安卓同参数：页面格式的 xref/对象树是离散小读）。
 * - P1 前台 readAt：缺块当场取（网络取块在锁外进行，前台读与后台预取不互相卡）；
 * - P2 位置窗口预取：每次前台读把窗口拉到当前块前后（深度=窗口 MB/块大小），后台单工作线程；
 * - P3 整书补齐：窗口补满后自动从前往后填，新窗口请求随时插队（"优先缓存打开位置前后，后台再缓存其它"）；
 * - 尺寸/块长变化自动整目录重建，绝不混用不同版本文件的缓存。
 */
class BlockCacheStore private constructor(
    val key: String,
    val size: Long,
    private val blockSize: Int,
    private val fetcher: (Long, Int) -> ByteArray?,
    private val memLimit: Long,
    private val windowBeforePct: Int = 20,
    private val windowAfterPct: Int = 30,
) {
    private val dir = File(AppPaths.cacheDir, "remote-blocks/" + key).apply { mkdirs() }
    private val dataFile = File(dir, "data.bin")
    private val flagFile = File(dir, "blocks.bin")
    private val metaFile = File(dir, "meta.json")
    private val blockCount = (((size + blockSize - 1) / blockSize).toInt()).coerceAtLeast(1)
    private val depth = (memLimit / blockSize).toInt().coerceAtLeast(2)   // P2 窗口总深度（块数）
    // v0.54 阅读位置前后非对称窗口（安卓 remoteWindowBefore/AfterPct 同款：默认 前20%/后30%）
    private val depthFwd: Int = (depth.toLong() * windowAfterPct / (windowAfterPct + windowBeforePct)).toInt().coerceAtLeast(1)
    private val depthBack: Int = (depth - depthFwd).coerceAtLeast(1)
    private val flags = ByteArray(blockCount)
    private val inFlight = HashSet<Int>()
    private val mem = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean =
            size > (memLimit / blockSize + 1).toInt()
    }

    @Volatile var fullyCached = false
        private set

    /** 缓存进度回调（工作线程触发；UI 转发由调用方处理）。 */
    @Volatile var onCacheUpdate: (() -> Unit)? = null

    /** 整书补齐完成回调（仅 fullyCached 由 false 翻转 true 时触发一次；回调内勿做重活，开线程）。 */
    @Volatile var onFullyCached: (() -> Unit)? = null

    /** P3 后台整书补齐开关（false=只做 P2 位置窗口预取，省流量；对新写入块仍照常缓存）。 */
    @Volatile var fillWhole: Boolean = true

    private val lock = Any()
    private val wake = java.lang.Object()
    private val windowAt = AtomicInteger(-1)   // P2 目标块；-1=无窗口在办
    @Volatile private var closed = false
    @Volatile private var worker: Thread? = null
    @Volatile private var bgDead = false   // 后台取块曾失败：不再重生 worker（前台读不受影响，重开新会话即复位）

    companion object {
        /** v0.54 在线缓存上限（MB，0=不限）；应用在打开远程书/保存设置时设置。 */
        @Volatile var capMaxMB: Long = 0L

        /** 检视修复：活跃会话的 key 集——LRU 回收永不触碰正在阅读书的缓存目录（flags 内存态与盘一致性依赖它）。 */
        val activeKeys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

        private val capTick = java.util.concurrent.atomic.AtomicInteger(0)

        /** remote-blocks 总占用字节。 */
        fun totalBytes(): Long =
            java.io.File(AppPaths.cacheDir, "remote-blocks").listFiles()
                ?.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } } ?: 0L

        /** 超限回收：按书目录最近修改时间从旧到新整目录删除（安卓 remoteCacheMaxMB 同语义）。 */
        fun enforceCap(maxMB: Long) {
            if (maxMB <= 0L) return
            val root = java.io.File(AppPaths.cacheDir, "remote-blocks")
            val dirs = root.listFiles() ?: return
            val limit = maxMB * 1024L * 1024L
            var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            if (total <= limit) return
            for (d in dirs.sortedBy { it.lastModified() }) {
                if (total <= limit) break
                if (d.name in activeKeys) continue   // 检视修复：正在阅读的书不回收
                val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (runCatching { d.deleteRecursively() }.getOrDefault(false)) {
                    total -= sz
                    TestLog.evt("block_cap_evict", "key=" + d.name + " freed=" + sz)
                }
            }
        }

        private val PAGE_FMTS = setOf("pdf", "cbz", "cbr", "xps", "djvu")

        /** 缓存目录 key（cache/remote-blocks/<key>/）；resolve 惰性回收晋升后的块缓存目录时复算用。 */
        fun cacheKey(path: String, size: Long, ext: String): String {
            val block = if (ext in PAGE_FMTS) 64 * 1024 else 1024 * 1024
            return Integer.toHexString(path.hashCode()) + "-" + size + "-" + block
        }

        fun open(path: String, size: Long, ext: String, fetcher: (Long, Int) -> ByteArray?, memLimit: Long,
                 windowBeforePct: Int = 20, windowAfterPct: Int = 30): BlockCacheStore {
            val s = BlockCacheStore(cacheKey(path, size, ext), size, if (ext in PAGE_FMTS) 64 * 1024 else 1024 * 1024,
                fetcher, memLimit, windowBeforePct, windowAfterPct)
            activeKeys.add(s.key)
            return s
        }

        /** 免开会话查询块缓存百分比（v0.36.0 书库网络书角标）。目录缺失/尺寸不符/块位图不完整 = -1（未知）。 */
        fun cachedPercent(path: String, size: Long, ext: String): Int {
            if (size <= 0) return -1
            val d = File(AppPaths.cacheDir, "remote-blocks/" + cacheKey(path, size, ext))
            val metaF = File(d, "meta.json")
            val flagF = File(d, "blocks.bin")
            if (!metaF.isFile || !flagF.isFile) return -1
            return runCatching {
                var sz = -1L
                var block = -1
                for (tok in metaF.readText().split(' ')) {
                    if (tok.startsWith("size=")) sz = tok.substring(5).toLongOrNull() ?: -1L
                    if (tok.startsWith("block=")) block = tok.substring(6).toIntOrNull() ?: -1
                }
                if (sz != size || block <= 0) return -1
                val count = (((size + block - 1) / block).toInt()).coerceAtLeast(1)
                val f = flagF.readBytes()
                if (f.size < count) return -1
                (f.count { it == 1.toByte() } * 100L / count).toInt().coerceIn(0, 100)
            }.getOrDefault(-1)
        }
    }

    /** 块缓存目录（晋升整本后惰性回收用）。 */
    fun cacheDir(): File = dir

    private fun expectedMeta(): String = "size=$size block=$blockSize"

    private fun loadState() {
        runCatching {
            if (metaFile.isFile && metaFile.readText() == expectedMeta() && flagFile.isFile &&
                flagFile.length() >= blockCount
            ) {
                val f = flagFile.readBytes()
                System.arraycopy(f, 0, flags, 0, blockCount)
                fullyCached = flags.all { it == 1.toByte() }
            } else {
                dataFile.delete(); flagFile.delete(); metaFile.delete()
            }
        }
    }

    init {
        loadState()
        if (fullyCached) TestLog.evt("block_cache_full_hit", "key=$key")
    }

    fun cachedBytes(): Long = synchronized(lock) { flags.count { it == 1.toByte() }.toLong() * blockSize }

    fun percent(): Int = if (size <= 0) 100 else (cachedBytes() * 100 / size).toInt().coerceIn(0, 100)

    /** 读（可跨块）；缺失块当场取（当前线程阻塞）。返回读取字节数，-1=EOF/失败。
     *  引擎读到哪，P2 窗口跟到哪（当前块前后优先补块）。 */
    fun readAt(pos: Long, b: ByteArray, off: Int, len: Int): Int {
        bgDead = false   // 检视修复：前台在读=用户活跃，后台补齐失败后允许重生 worker
        if (closed) return -1
        if (len <= 0) return 0
        if (pos >= size) return -1
        var done = 0
        var lastIdx = -1
        while (done < len) {
            val p = pos + done
            if (p >= size) break
            val idx = (p / blockSize).toInt()
            lastIdx = idx
            val inOff = (p % blockSize).toInt()
            var chunk = minOf(len - done, blockSize - inOff)
            if (p + chunk > size) chunk = (size - p).toInt()
            val blk = blockAt(idx) ?: return if (done == 0) -1 else done
            System.arraycopy(blk, inOff, b, off + done, chunk)
            done += chunk
        }
        if (lastIdx >= 0) requestWindowAround(lastIdx)
        return if (done == 0) -1 else done
    }

    /** P2：把预取窗口拉到 idx 前后（引擎每次 readAt 自动调用；外部也可点名）。 */
    fun requestWindowAround(idx: Int) {
        if (closed || fullyCached) return
        windowAt.set(idx.coerceIn(0, blockCount - 1))
        ensureWorker()
        synchronized(wake) { wake.notifyAll() }
    }

    /** 会话关闭（文档关闭后调用）：停预取；已缓存块此前已随写落盘。 */
    fun close() {
        closed = true
        activeKeys.remove(key)
        synchronized(wake) { wake.notifyAll() }
    }

    @Synchronized private fun ensureWorker() {
        if (closed || fullyCached || bgDead) return
        val w = worker
        if (w != null && w.isAlive) return
        worker = Thread { workLoop() }.apply { isDaemon = true; name = "hr-blockcache-" + key }
        worker?.start()
        TestLog.evt("block_cache_worker", "key=" + key + " blocks=" + blockCount + " depth=" + depth)
    }

    /** P2→P3 工作循环：窗口优先、可被新窗口插队；窗口办结后从前往后补齐整书。取块失败即退（下次前台读会重启）。 */
    private fun workLoop() {
        while (!closed && !fullyCached) {
            val t = windowAt.get()
            if (t >= 0) {
                var i = (t - depthBack).coerceAtLeast(0)
                val end = minOf(blockCount, t + depthFwd)
                while (i < end && !closed) {
                    val now = windowAt.get()
                    if (now >= 0 && now != t) break   // 新窗口插队
                    if (!backgroundFill(i)) { bgDead = true; return }
                    i++
                }
                if (windowAt.get() == t) windowAt.set(-1)   // 窗口办结
            }
            if (closed || fullyCached || !fillWhole) break
            for (i in 0 until blockCount) {
                if (closed || fullyCached) return
                if (windowAt.get() >= 0) break
                if (!backgroundFill(i)) { bgDead = true; return }
            }
            if (fullyCached) break
            synchronized(wake) { wake.wait(500) }
        }
    }

    private fun hasBlock(idx: Int): Boolean = synchronized(lock) { idx in 0 until blockCount && flags[idx] == 1.toByte() }

    private fun blockLen(idx: Int): Int = (minOf(blockSize.toLong(), size - idx.toLong() * blockSize)).toInt()

    /** 前台取块：内存/磁盘命中即回；未命中在锁外走网络（必取，不避让并发）。 */
    private fun blockAt(idx: Int): ByteArray? {
        if (idx < 0 || idx >= blockCount) return null
        synchronized(lock) {
            mem[idx]?.let { return it }
            if (flags[idx] == 1.toByte()) {
                val blk = readBlockFromDisk(idx)
                if (blk != null) {
                    mem[idx] = blk
                    return blk
                }
                flags[idx] = 0   // 盘上块异常：重取
            }
        }
        val got = fetcher(idx.toLong() * blockSize, blockLen(idx)) ?: return null
        return installBlock(idx, got)
    }

    /** 后台补一块：true=已就绪或他人正在取（继续下一块）；false=取失败（停 worker）。 */
    private fun backgroundFill(idx: Int): Boolean {
        if (hasBlock(idx)) return true
        val claim = synchronized(lock) { if (idx in inFlight) return true else inFlight.add(idx) }
        if (!claim) return true
        try {
            val got = runCatching { fetcher(idx.toLong() * blockSize, blockLen(idx)) }.getOrNull() ?: return false
            return installBlock(idx, got) != null
        } finally {
            synchronized(lock) { inFlight.remove(idx) }
        }
    }

    /** 落块：双重检查后写盘+置位+入内存 LRU（并发同块重复取时后到者直接用先到结果）。 */
    private fun installBlock(idx: Int, got: ByteArray): ByteArray? {
        if (got.isEmpty() || got.size < blockLen(idx)) return null
        val res: ByteArray = synchronized(lock) {
            mem[idx]?.let { return it }
            if (flags[idx] == 1.toByte()) {
                readBlockFromDisk(idx)?.let {
                    mem[idx] = it
                    return it
                }
            }
            writeBlockToDisk(idx, got)
            flags[idx] = 1
            mem[idx] = got
            persist()
            got
        }
        if (capTick.incrementAndGet() % 24 == 0) runCatching { enforceCap(capMaxMB) }
        onCacheUpdate?.let { it() }
        return res
    }

    private fun readBlockFromDisk(idx: Int): ByteArray? = try {
        val want = blockLen(idx)
        val out = ByteArray(want)
        RandomAccessFile(dataFile, "r").use { r ->
            r.seek(idx.toLong() * blockSize)
            r.readFully(out)
        }
        out
    } catch (_: Exception) {
        null
    }

    private fun writeBlockToDisk(idx: Int, data: ByteArray) {
        runCatching {
            RandomAccessFile(dataFile, "rw").use { r ->
                r.seek(idx.toLong() * blockSize)
                r.write(data)
            }
        }
    }

    private fun persist() {
        runCatching {
            flagFile.writeBytes(flags)
            metaFile.writeText(expectedMeta())
            if (!fullyCached && flags.all { it == 1.toByte() }) {
                fullyCached = true
                TestLog.evt("block_cache_full", "key=$key")
                onFullyCached?.let { it() }
            }
        }
    }
}
