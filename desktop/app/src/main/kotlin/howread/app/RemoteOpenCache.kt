package howread.app

import howread.core.AppPaths
import howread.core.TestLog
import howread.core.data.Db
import howread.core.remote.BlockCacheStore
import howread.core.remote.RemoteClients
import howread.core.remote.downloadTo
import java.io.File

/**
 * 远程书打开解析（v0.31 建立；v0.33 分档；v0.34.0 块缓存流式全线接通）：
 * - 命中整本缓存 → 秒开本地文件；
 * - 小于窗口阈值（偏好→缓存管理，默认 5MB）或大小未知 → 整本简单缓存后打开；
 * - 大于阈值 → **块缓存会话 + 引擎自有流入口（HROpen）**——任意格式（含 PDF 的 xref 在文件尾）
 *   无需整本下载即可打开；MuPDF 读到哪块取哪块，P2 窗口自动跟住阅读位置前后，P3 后台补齐整书。
 */
object RemoteOpenCache {
    sealed class Resolution {
        data class Local(val file: String) : Resolution()

        /** 块缓存流式会话（v0.34.0）：随文档存活；文档关闭后调用 close()。 */
        class Streamed(
            val session: BlockCacheStore,
            val stream: RemoteSeekableStream,
            val magic: String,
            private val onEnd: () -> Unit,
        ) : Resolution() {
            fun close() {
                runCatching { stream.closeStream() }
                runCatching { session.close() }
                runCatching(onEnd)
            }
        }
    }

    fun windowBytes(): Long =
        (LibPrefs.str("remoteCacheWindowMB", "5").toLongOrNull()?.coerceIn(1, 4096L) ?: 5L) * 1024L * 1024L

    /** v0.54 小书整本缓存阈值（安卓 remoteWholeBookThresholdMB 同语义，0 = 关闭整本缓存分档）。 */
    fun wholeBookBytes(): Long =
        (LibPrefs.str("remoteWholeBookThresholdMB", "20").toLongOrNull()?.coerceIn(0, 4096L) ?: 20L) * 1024L * 1024L

    /** v0.54 在线缓存上限（MB，0 = 不限；安卓 remoteCacheMaxMB 同默认 500）。 */
    fun capMB(): Long =
        LibPrefs.str("remoteCacheMaxMB", "500").toLongOrNull()?.coerceIn(0, 1024L * 1024L) ?: 500L

    /** 简易联网判定：存在已启用、非回环、非虚拟且有地址的网络接口（仅区分离线/在线）。 */
    fun netUsable(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .any { nic -> nic.isUp && !nic.isLoopback && !nic.isVirtual &&
                nic.inetAddresses.asSequence().any { a -> !a.isLoopbackAddress } }
    }.getOrDefault(true)

    fun parse(path: String): Pair<Long, String>? {
        val body = path.removePrefix("remote://")
        val i = body.indexOf('/')
        if (i <= 0) return null
        val id = body.substring(0, i).toLongOrNull() ?: return null
        val rel = body.substring(i + 1)
        return if (rel.isBlank()) null else id to rel
    }

    fun cacheFile(srcId: Long, rel: String): File {
        val name = rel.substringAfterLast('/')
        val safe = name.replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "book" }
        return File(File(AppPaths.cacheDir, "remote/" + srcId), safe)
    }

    fun isCached(path: String): Boolean {
        val (id, rel) = parse(path) ?: return false
        val dest = cacheFile(id, rel)
        val exp = Db.bookSize(path)
        return dest.isFile && (exp <= 0 || dest.length() == exp)
    }

    /** 远程书缓存百分比（v0.36.0 书库角标）：整本已缓存=100；块缓存中=按块计；无缓存/大小未知=-1（不显示）。 */
    fun cachePercent(path: String): Int {
        if (isCached(path)) return 100
        val exp = Db.bookSize(path)
        if (exp <= 0) return -1
        return BlockCacheStore.cachedPercent(path, exp, path.substringAfterLast('.', "").lowercase())
    }

    /** 解析远程书打开方式（IO 线程调用）；onMsg 汇报进度文本。失败返回 null。 */
    fun resolve(path: String, onMsg: (String) -> Unit): Resolution? {
        val (srcId, rel) = parse(path) ?: return null
        val src = Db.listRemoteSources().firstOrNull { it.id == srcId }
        if (src == null) {
            onMsg("远程服务器已被删除")
            return null
        }
        val dest = cacheFile(srcId, rel)
        val exp = Db.bookSize(path)
        val ext = rel.substringAfterLast('.', "").lowercase()

        // 已整本简单缓存 → 秒开（若同名块缓存目录还在=晋升前遗留，惰性回收）
        if (dest.isFile && (exp <= 0 || dest.length() == exp)) {
            runCatching {
                val key = BlockCacheStore.cacheKey(path, exp, ext)
                val bd = File(AppPaths.cacheDir, "remote-blocks/" + key)
                if (bd.isDirectory) {
                    bd.deleteRecursively()
                    TestLog.evt("remote_block_recycled", "key=$key")
                }
            }
            TestLog.evt("remote_cache_hit", "size=" + dest.length())
            return Resolution.Local(dest.absolutePath)
        }
        // v0.54 每次打开远程书前先核对缓存上限（超限按目录最旧整目录回收）
        BlockCacheStore.capMaxMB = capMB()
        runCatching { BlockCacheStore.enforceCap(capMB()) }
        dest.parentFile?.mkdirs()
        val t0 = System.currentTimeMillis()

        // 小书（≤ 窗口阈值）或大小未知：整本缓存（.part→rename 完整落定）；
        // djvu 不经 mupdf 流入口（引擎内 libdjvu 桥 v0.46.0），恒整本缓存。
        if (exp in 1..wholeBookBytes() || exp <= 0 || ext == "djvu") {   // v0.54 阈值=整本缓存阈值（安卓同键）
            onMsg("正在缓存 0%")
            val c = RemoteClients.create(src)
            try {
                var last = 0L
                c.downloadTo(rel, dest) { done, _ ->
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        last = now
                        onMsg("正在缓存 " + done / 1024 + "KB")
                    }
                }
                TestLog.evt("remote_cache_done", "ms=" + (System.currentTimeMillis() - t0) + " size=" + dest.length())
                onMsg("缓存完成")
                return Resolution.Local(dest.absolutePath)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t   // 检视修复：取消不被当"缓存失败"
                TestLog.evt("remote_cache_fail", "rel=$rel err=$t")
                onMsg("缓存失败：" + (t.message ?: t.toString()))
                runCatching { dest.delete() }
                return null
            } finally {
                runCatching { c.close() }
            }
        }

        // v0.54「点击远程书在线阅读」关闭时：大书也先整本下载完成再打开（安卓 remoteOnlineFirst=false 同语义）
        if (!LibPrefs.bool("remoteOnlineFirst", true)) {
            onMsg(L10n.s("正在下载整本…", "Downloading whole book…"))
            val c2 = RemoteClients.create(src)
            try {
                var last = 0L
                c2.downloadTo(rel, dest) { done, _ ->
                    val now = System.currentTimeMillis()
                    if (now - last > 400) {
                        last = now
                        onMsg(L10n.s("正在下载整本…", "Downloading…") + " " + done * 100 / exp.coerceAtLeast(1) + "%")
                    }
                }
                TestLog.evt("remote_whole_dl", "ms=" + (System.currentTimeMillis() - t0) + " size=" + dest.length())
                onMsg(L10n.s("下载完成", "Download complete"))
                return Resolution.Local(dest.absolutePath)
            } catch (t2: Throwable) {
                if (t2 is kotlinx.coroutines.CancellationException) throw t2
                TestLog.evt("remote_whole_dl_fail", "rel=$rel err=$t2")
                onMsg(L10n.s("下载失败：", "Download failed: ") + (t2.message ?: t2.toString()))
                runCatching { dest.delete() }
                return null
            } finally {
                runCatching { c2.close() }
            }
        }

        // 大书：块缓存流式打开（v0.34.0）——任意格式按需取块，magic=纯文件名（安卓 magicFor 同思路）
        // v0.35.0：取块加离线熔断（连续 2 次失败停发网络请求，60s 半开试探）；
        // 整书补齐完成后自动"晋升"为整本缓存（下次走 Local+排版加速器，惰性回收块缓存目录）。
        val c = RemoteClients.create(src)
        var lastMsg = 0L
        var consecFail = 0
        var offline = false
        var offlineAt = 0L
        val session = BlockCacheStore.open(
            path, exp, ext,
            fetcher = { off, len ->
                val now = System.currentTimeMillis()
                if (offline && now - offlineAt < 60_000) null   // 熔断中
                else {
                    if (offline) { offline = false; TestLog.evt("block_cache_halfopen", "rel=$rel") }
                    // v0.54 网络重试（安卓 remoteRetryCount/IntervalMs 同键）：检视修复=单次退避钳 500ms
                    // 且总等待预算 2s（fetcher 也被前台同步调用，防大参数组合卡 UI）
                    val rN = LibPrefs.str("remoteRetryCount", "3").toIntOrNull()?.coerceIn(0, 10) ?: 3
                    val rMs = LibPrefs.str("remoteRetryIntervalMs", "100").toLongOrNull()?.coerceIn(0, 500L) ?: 100L
                    var got: ByteArray? = null
                    var spent = 0L
                    for (k in 0..rN) {
                        got = runCatching { c.readRange(rel, off, len) }.getOrNull()
                        if (got != null) break
                        val d = rMs * (k + 1)
                        if (k < rN && d > 0 && spent + d <= 2000L) {
                            runCatching { Thread.sleep(d) }
                            spent += d
                        }
                    }
                    if (got == null) {
                        if (++consecFail >= 2) {
                            offline = true
                            offlineAt = System.currentTimeMillis()
                            TestLog.evt("block_cache_offline", "rel=$rel")
                        }
                        null
                    } else {
                        consecFail = 0
                        got
                    }
                }
            },
            memLimit = windowBytes(),
            windowBeforePct = LibPrefs.str("remoteWindowBeforePct", "20").toIntOrNull()?.coerceIn(1, 90) ?: 20,
            windowAfterPct = LibPrefs.str("remoteWindowAfterPct", "30").toIntOrNull()?.coerceIn(1, 90) ?: 30,
        )
        // v0.54「仅 WiFi 下预取与整本缓存」消费：检测不到可用网络（离线）时停后台整本补齐；
        // 有网即视为稳定网络（桌面无计费网络 API，仅区分离线/在线）
        session.fillWhole = LibPrefs.bool("remoteFillWhole", true) &&
            (!LibPrefs.bool("remoteWifiOnly", true) || netUsable())
        session.onCacheUpdate = {
            val now = System.currentTimeMillis()
            if (now - lastMsg > 800) {
                lastMsg = now
                onMsg("已缓存 " + session.percent() + "%")
            }
        }
        session.onFullyCached = {
            // 整书补齐完成（persist 锁内触发）——开线程晋升为整本缓存（重开即 Local+accel）
            Thread {
                try {
                    promote(session, dest, exp)
                } catch (t: Throwable) {
                    TestLog.evt("remote_cache_promote_fail", "rel=$rel err=$t")
                }
            }.apply { isDaemon = true; name = "hr-promote" }.start()
        }
        TestLog.evt("remote_cache_stream", "rel=$rel size=$exp pct0=" + session.percent() + " fill=" + session.fillWhole)
        onMsg("流式打开（已缓存 " + session.percent() + "%）")
        return Resolution.Streamed(session, RemoteSeekableStream(session), rel.substringAfterLast('/')) {
            runCatching { c.close() }
        }
    }

    /** 晋升：把块缓存拼成整本缓存文件（.part→rename 完整落定）。 */
    private fun promote(session: BlockCacheStore, dest: File, size: Long) {
        if (dest.isFile && dest.length() == size) return
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        java.io.FileOutputStream(tmp).use { out ->
            var off = 0L
            val bs = 1024L * 1024
            while (off < size) {
                val want = minOf(bs, size - off).toInt()
                val buf = ByteArray(want)
                val got = session.readAt(off, buf, 0, want)
                if (got <= 0) {
                    TestLog.evt("remote_cache_promote_partial", "have=$off exp=$size")
                    tmp.delete()
                    return
                }
                out.write(buf, 0, got)
                off += got
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            TestLog.evt("remote_cache_promote_fail", "rename failed")
            return
        }
        TestLog.evt("remote_cache_promoted", "size=" + dest.length())
    }
}
