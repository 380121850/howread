package howread.core

import org.mozilla.universalchardet.UniversalDetector
import java.io.File

/** TXT 编码检测与 UTF-8 转换（GBK/Big5/UTF-16 等 → 缓存目录 UTF-8 副本）。 */
object TxtDecode {

    fun prepareTxt(path: String): String {
        val f = File(path)
        val bytes = f.readBytes()

        var charset: String? = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
            else -> null
        }
        if (charset == null) {
            val det = UniversalDetector(null)
            det.handleData(bytes, 0, minOf(bytes.size, 256 * 1024))
            det.dataEnd()
            charset = det.detectedCharset
        }
        if (charset == null) return path
        val cs = charset
        // UTF-8/ASCII 直开；其余（UTF-16LE/BE、GBK、Big5 等）统一转 UTF-8 副本
        if (cs.equals("UTF-8", true) || cs.equals("ASCII", true) || cs.equals("US-ASCII", true)) return path

        val text = try {
            String(bytes, charset(cs)).trimStart('\uFEFF')
        } catch (_: Exception) {
            return path
        }
        val out = File(File(AppPaths.cacheDir, "txt"),
            "${Integer.toHexString(path.hashCode())}-${f.length()}.txt")
        out.parentFile.mkdirs()
        if (!out.exists()) out.writeText(text, Charsets.UTF_8)
        return out.absolutePath
    }

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)
}
