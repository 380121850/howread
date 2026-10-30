package howread.core.remote

import howread.core.model.RemoteSource
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream

class SftpClient(private val src: RemoteSource) : RemoteClient {

    private val ssh: SSHClient = run {
        val c = SSHClient()
        c.addHostKeyVerifier(PromiscuousVerifier()) // M4 信任所有主机（与安卓首连信任勾选对齐，后续可存指纹）
        try {
            c.connect(src.host, src.port)
            c.authPassword(src.user, src.password)
        } catch (t: Throwable) {
            runCatching { c.disconnect() }   // 检视修复：认证失败不泄漏已连接的 SSH 会话
            throw t
        }
        c
    }
    private val sftp: SFTPClient = ssh.newSFTPClient()

    // v0.47.0：joinWithBase 防 path 双拼（浏览起点/回填场景）
    private fun abs(rel: String): String = joinWithBase(src.path, rel)

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
        // v0.49.0：目录选择器根锚定浏览会 list("")——sshj 空路径非法，映射为家目录（"."）
        sftp.ls(abs(relDirResolved).ifEmpty { "." }).map { r: RemoteResourceInfo ->
            RemoteEntry(
                name = r.name,
                isDir = r.attributes.mode.type == FileMode.Type.DIRECTORY,
                size = r.attributes.size,
                relPath = joinRel(relDir, r.name),
            )
        }.sortedByDescending { it.isDir }
    }

    override fun readRange(relPath: String, offset: Long, maxLen: Int): ByteArray? = try {
        withAlt(relPath) { rp ->
            sftp.open(abs(rp)).use { rf ->
                val out = ByteArray(maxLen)
                var got = 0
                while (got < maxLen) {
                    val n = rf.read(offset + got, out, got, maxLen - got)
                    if (n <= 0) break
                    got += n
                }
                out.copyOf(got)
            }
        }
    } catch (_: Exception) {
        super.readRange(relPath, offset, maxLen)
    }

    override fun open(relPath: String): InputStream = withAlt(relPath) { rp ->
        // sshj RemoteFile 按偏移读，包成 InputStream
        val rf = sftp.open(abs(rp))
        // 检视修复：stat 失败取 -1——0 会让 open 流立即 EOF，下载恒产出 0 字节文件
        val size = runCatching { sftp.stat(abs(rp)).size }.getOrDefault(-1L)
        object : InputStream() {
            private var off = 0L
            private val buf = ByteArray(64 * 1024)
            private var bufPos = 0
            private var bufLen = 0

            override fun read(): Int {
                if (bufPos >= bufLen) {
                    if (off >= size) return -1
                    bufLen = rf.read(off, buf, 0, buf.size)
                    if (bufLen <= 0) return -1
                    off += bufLen
                    bufPos = 0
                }
                if (off >= size) return -1   // 检视修复：size 未知(-1)时不走该捷径，交给 rf.read 的 -1
                return buf[bufPos++].toInt() and 0xFF
            }

            override fun close() {
                runCatching { rf.close() }
            }
        }
    }

    override fun test(): String {
        val n = list("").size
        return "SFTP 连接成功，起始目录 $n 项"
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }
}
