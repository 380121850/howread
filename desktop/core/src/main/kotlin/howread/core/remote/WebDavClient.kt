package howread.core.remote

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import howread.core.model.RemoteSource
import java.io.InputStream

class WebDavClient(private val src: RemoteSource) : RemoteClient {

    private val sardine = SardineFactory.begin(src.user, src.password)

    private fun url(rel: String): String {
        val base = if (src.host.startsWith("http")) src.host.trimEnd('/') else "http://${src.host}:${src.port}"
        // v0.47.0：joinWithBase 防 path 双拼；path 段统一 URL 编码（未编码空格/中文 → URI.create 抛 Illegal character in path）
        val path = joinWithBase(src.path, rel).trim('/')
        return if (path.isBlank()) base else "$base/${encodeUrlPath(path)}"
    }

    /** 检视修复：双尝试消歧（主选=joinWithBase；同名目录边界时备选=纯叠加）。 */
    private fun <T> withAlt(rel: String, call: (String) -> T): T {
        val (main, alt) = dualPaths(src.path, rel)
        return try {
            call(main)
        } catch (e: Exception) {
            if (alt == null) throw e
            call(alt)
        }
    }

    override fun list(relDir: String): List<RemoteEntry> = withAlt(relDir) { relDirResolved ->
        val target = url(relDirResolved)
        sardine.list(target).drop(1).map { r: DavResource ->
            RemoteEntry(
                name = r.name.trimEnd('/').substringAfterLast('/'),
                isDir = r.isDirectory,
                size = r.contentLength ?: 0L,
                relPath = joinRel(relDir, r.name.trimEnd('/')),
            )
        }
    }

    override fun open(relPath: String): InputStream = withAlt(relPath) { rp -> sardine.get(url(rp)) }

    override fun readRange(relPath: String, offset: Long, maxLen: Int): ByteArray? = try {
        withAlt(relPath) { rp ->
            sardine.get(url(rp), mapOf("Range" to "bytes=$offset-${offset + maxLen - 1}")).use { ins ->
                val out = ByteArray(maxLen)
                var off = 0
                while (off < maxLen) {
                    val n = ins.read(out, off, maxLen - off)
                    if (n < 0) break
                    off += n
                }
                out.copyOf(off)
            }
        }
    } catch (_: Exception) {
        super.readRange(relPath, offset, maxLen)
    }

    override fun test(): String {
        val n = list("").size
        return "WebDAV 连接成功，起始目录 $n 项"
    }

    override fun close() {
        // 检视修复：sardine 持连接池与后台线程，显式 shutdown 防长会话句柄累积
        runCatching { (sardine as? java.io.Closeable)?.close() }
    }
}
