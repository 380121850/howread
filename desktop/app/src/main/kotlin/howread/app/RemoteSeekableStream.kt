package howread.app

import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import howread.core.remote.BlockCacheStore
import java.io.IOException

/**
 * MuPDF 原生流桥（安卓 RemoteSeekableStream 同思路）：JNI 在任意渲染线程调用 read(byte[])/seek →
 * 块缓存会话按需取块（线程安全）。文档打开后任意格式的离散读（xref/对象树/页数据）都经此按需缓存。
 * 本绑定中 SeekableInputStream 为接口：read(byte[]) + seek/position 三方法。
 */
class RemoteSeekableStream(private val session: BlockCacheStore) : SeekableInputStream {
    private var pos = 0L

    @Volatile
    private var closed = false

    override fun read(b: ByteArray): Int {
        if (closed) throw IOException("Stream closed")
        if (b.isEmpty()) return 0
        val n = session.readAt(pos, b, 0, b.size)
        if (n > 0) pos += n
        return n
    }

    override fun seek(offset: Long, whence: Int): Long {
        if (closed) throw IOException("Stream closed")
        pos = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> pos + offset
            SeekableStream.SEEK_END -> session.size + offset
            else -> pos
        }
        return pos
    }

    override fun position(): Long = pos

    fun closeStream() {
        closed = true
    }
}
