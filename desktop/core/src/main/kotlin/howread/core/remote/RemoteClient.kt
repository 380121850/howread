package howread.core.remote

import howread.core.model.RemoteSource
import java.io.InputStream

data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val relPath: String,
    /** OPDS 条目封面缩略图绝对 URL（其它协议留空）。 */
    val coverUrl: String = "",
    /** OPDS 条目摘要（HTML 剥离后截断；其它协议留空）。 */
    val summary: String = "",
    /** 服务器端修改时间（毫秒；v0.58 远程缓存版本标记用，OPDS 等无时间源时为 0）。 */
    val mtime: Long = 0)

interface RemoteClient : AutoCloseable {
    /** 列目录（relPath 相对起始目录，空串=起始目录）。 */
    fun list(relDir: String): List<RemoteEntry>

    /** 打开远程文件流（下载用；调用方负责关闭）。 */
    fun open(relPath: String): InputStream

    /** 目录内搜索（OPDS OpenSearch；null=协议/目录不支持，空表=无命中）。 */
    fun search(query: String): List<RemoteEntry>? = null

    /** 拉取展示型资源字节（OPDS 封面；null=不支持或失败）。 */
    fun fetchBytes(url: String): ByteArray? = null

    /** 连通性测试，成功返回摘要，失败抛异常。 */
    fun test(): String

    /** 定长区间读（块缓存用）。返回实际读到的字节（不足=文件尾），null=失败。默认 open+skip 兜底。 */
    fun readRange(relPath: String, offset: Long, maxLen: Int): ByteArray? {
        if (offset < 0 || maxLen <= 0) return null
        return try {
            open(relPath).use { ins ->
                var toSkip = offset
                val sk = ByteArray(64 * 1024)
                while (toSkip > 0) {
                    val n = ins.read(sk, 0, minOf(sk.size.toLong(), toSkip).toInt())
                    if (n < 0) return null
                    toSkip -= n
                }
                val out = ByteArray(maxLen)
                var off = 0
                while (off < maxLen) {
                    val n = ins.read(out, off, maxLen - off)
                    if (n < 0) break
                    off += n
                }
                out.copyOf(off)
            }
        } catch (_: Exception) {
            null
        }
    }
}

object RemoteClients {
    fun create(src: RemoteSource): RemoteClient = when (src.protocol.uppercase()) {
        "WEBDAV" -> WebDavClient(src)
        "SMB" -> SmbClient(src)
        "SFTP" -> SftpClient(src)
        "OPDS" -> OpdsClient(src)
        else -> throw IllegalArgumentException("未知协议 ${src.protocol}")
    }
}

/** 路径拼接（'/'分隔，去重复斜杠）。 */
internal fun joinRel(base: String, rel: String): String {
    if (rel.isBlank()) return base.trimEnd('/')
    return (base.trimEnd('/') + "/" + rel.trimStart('/'))
}

/** URL path 段编码（RFC 3986：UTF-8 %XX，空格→%20；'/' 不编码）。
 *  v0.47.0：SMB/WebDAV 出口统一编码——未编码的空格/中文会让 HttpClient URI.create 直接抛
 *  "Illegal character in path"、jcifs URL 解析出 BAD_NETWORK_NAME/PATH_NOT_FOUND。 */
/** v0.47.0：final path = src.path + rel，但 rel 已含 src.path 前缀（浏览对话框以 path 为起点
 *  二次 list / 从共享列表逐级进入后回填 path）时不重复叠加——双拼 path/path → PATH_NOT_FOUND。 */
internal fun joinWithBase(base: String, rel: String): String {
    val b = base.trim('/')
    var r = rel.trim('/')
    if (b.isNotEmpty() && r != b && !r.startsWith(b + "/")) {
        r = if (r.isEmpty()) b else "$b/$r"
    }
    return r
}

/** 检视修复（v0.47.0）：joinWithBase 的"已含前缀"判定有边界歧义——若 base 下恰好存在同名子目录，
 *  相对路径 rel（恰以 base 开头）会被误判为绝对路径而漏拼一层。dualPaths 返回主选+备选拼接：
 *  客户端主选失败且备选存在时自动重试（由服务器裁决，无歧义场景零额外开销）。 */
internal fun dualPaths(base: String, rel: String): Pair<String, String?> {
    val b = base.trim('/')
    val r = rel.trim('/')
    if (b.isEmpty()) return r to null
    return if (r == b || r.startsWith(b + "/")) r to "$b/$r"
    else "$b/$r" to null
}

internal fun encodeUrlPath(p: String): String =
    p.split('/').joinToString("/") { seg ->
        java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    }

/** 下载到本地文件（原子：先写 <dest>.part，成功后替换；中断不留半截文件），
 *  onProgress(已读字节, 总字节)。 */
fun RemoteClient.downloadTo(relPath: String, dest: java.io.File, mtime: Long = 0L, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    dest.parentFile?.mkdirs()
    val tmp = java.io.File(dest.parentFile, dest.name + ".part")
    if (tmp.exists()) tmp.delete()
    try {
        java.io.FileOutputStream(tmp).use { out ->
            open(relPath).use { ins ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, -1)
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw java.io.IOException("rename failed: " + dest)
        writeVtag(dest, mtime)   // v0.58 版本标记：重扫发现登记 mtime 变化后，下次打开自动失效重下
    } catch (e: Exception) {
        tmp.delete()
        throw e
    }
}

/** v0.58 远程缓存版本标记侧车（<dest>.vtag，安卓 cache-first versionTag 同语义）：
 *  登记时记录的 mtime（重扫由服务器回填）与落盘时写入的标记对比——同尺寸换文件自动失效重下。
 *  mtime<=0（OPDS/未登记）不写标记、视为匹配（维持 v0.57 尺寸口径）。 */
fun vtagFileOf(dest: java.io.File): java.io.File = java.io.File(dest.parentFile, dest.name + ".vtag")

fun writeVtag(dest: java.io.File, mtime: Long) {
    val f = vtagFileOf(dest)
    if (mtime <= 0L) { runCatching { f.delete() }; return }
    runCatching { f.writeText("mtime=" + mtime, Charsets.UTF_8) }
}

fun readVtag(dest: java.io.File): Long = runCatching {
    vtagFileOf(dest).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        ?.removePrefix("mtime=")?.trim()?.toLongOrNull() ?: 0L
}.getOrDefault(0L)
