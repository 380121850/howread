package howread.core.remote

import howread.core.model.RemoteSource
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.InputStream
import java.util.Properties

class SmbClient(private val src: RemoteSource) : RemoteClient {

    private val ctx: CIFSContext = run {
        val props = Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        BaseContext(PropertyConfiguration(props))
            .withCredentials(NtlmPasswordAuthenticator("", src.user, src.password))
    }

    private fun smbUrl(rel: String): String {
        // v0.47.0 实证：jcifs 不解码 %XX（编码会被当字面路径发送），URL 必须用原文路径——
        // jcifs 自己解析 URL 后按 SMB2 的 UTF-16 文件名字段发送，空格/中文原样正确。
        val p = "smb://${src.host}:${src.port}/${joinWithBase(src.path, rel)}"
        return if (p.endsWith("/")) p else "$p/"
    }

    private fun fileUrl(rel: String): String =
        "smb://${src.host}:${src.port}/${joinWithBase(src.path, rel)}"

    /** 检视修复：双尝试消歧（主选=joinWithBase；同名子目录边界时备选=纯叠加）。 */
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
        val dir = SmbFile(smbUrl(relDirResolved), ctx)
        dir.listFiles().map { f ->
            RemoteEntry(
                name = f.name.trimEnd('/'),
                isDir = f.isDirectory,
                size = if (f.isDirectory) 0L else f.length(),
                relPath = joinRel(relDir, f.name.trimEnd('/')),
            )
        }.sortedByDescending { it.isDir }
    }

    override fun open(relPath: String): InputStream = withAlt(relPath) { rp ->
        SmbFileInputStream(SmbFile(fileUrl(rp), ctx))
    }

    override fun readRange(relPath: String, offset: Long, maxLen: Int): ByteArray? = try {
        withAlt(relPath) { rp ->
            jcifs.smb.SmbRandomAccessFile(SmbFile(fileUrl(rp), ctx), "r").use { raf ->
                raf.seek(offset)
                val out = ByteArray(maxLen)
                var got = 0
                while (got < maxLen) {
                    val n = raf.read(out, got, maxLen - got)
                    if (n < 0) break
                    got += n
                }
                out.copyOf(got)
            }
        }
    } catch (_: Exception) {
        super.readRange(relPath, offset, maxLen)
    }

    override fun test(): String {
        val n = list("").size
        return "SMB 连接成功，共享根 $n 项"
    }

    override fun close() {
        ctx.close()
    }
}
