package howread.core.convert

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File
import java.util.Base64

/**
 * MHT/MHTML（MIME HTML 归档）→ 单 HTML（v0.58；Qt v0.105 mhtConvert 同思路移植）：
 * - 按头部 boundary 切分 MIME 分段（字节 1:1 的 ISO-8859-1 域内操作，段体按编码还原字节）；
 * - 段体按 Content-Transfer-Encoding 解码（base64 / quoted-printable / 原样）；
 * - 首个 text/html 段 = 正文，按段 charset 转 UTF-8（GB18030/Big5/Shift_JIS/EUC-KR/1252 等）；
 * - 图片段按 Content-Location / Content-ID 以 data URI 内联进正文（仅 jpeg/png/gif——引擎
 *   html-parse 只认这三类；单图 <12MB），其余资源丢弃；
 * - 缓存键 = 路径+大小+mtime（DocConvert 同款），失败回退原路径。
 */
object MhtConvert {

    private const val MAX_IMG_BYTES = 12L * 1024 * 1024

    private val IMG_MIMES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif")

    @Synchronized
    fun convertedHtml(path: String): File? {
        val src = File(path)
        if (!src.isFile) return null
        val t0 = System.currentTimeMillis()
        val key = Integer.toHexString((src.absolutePath + "#" + src.length() + "#" + src.lastModified()).hashCode())
        val out = File(File(AppPaths.cacheDir, "convert").apply { mkdirs() }, "mht-$key.html")
        if (out.isFile && out.length() > 0) return out
        val html = runCatching { convert(src) }.getOrNull()
        if (html.isNullOrBlank()) {
            TestLog.evt("mht_convert_fail", "size=" + src.length())
            return null
        }
        out.writeText(html, Charsets.UTF_8)
        TestLog.evt("mht_convert_ok", "ms=" + (System.currentTimeMillis() - t0) + " size=" + out.length())
        return out
    }

    private fun convert(f: File): String? {
        val s = String(f.readBytes(), Charsets.ISO_8859_1)   // 字节 1:1 域
        val head = s.substring(0, minOf(s.length, 8192))
        val boundary = Regex("boundary=\"?([^\"\\r\\n;]+)\"?", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.trim() ?: return decodeSingle(s)
        if (boundary.isBlank()) return decodeSingle(s)

        var html: String? = null
        val subs = ArrayList<Pair<Regex, String>>()   // (替换正则, data URI)
        val segs = s.split("--" + boundary)
        for (i in 1 until segs.size) {   // segs[0] = 前导
            var seg = segs[i]
            if (seg.startsWith("--")) break   // 收尾 boundary--
            seg = seg.trimStart('\r', '\n')
            var cutAt = -1
            var cutLen = 0
            val a = seg.indexOf("\r\n\r\n")
            if (a >= 0) { cutAt = a; cutLen = 4 } else {
                val b2 = seg.indexOf("\n\n")
                if (b2 >= 0) { cutAt = b2; cutLen = 2 }
            }
            if (cutAt < 0) continue
            val headers = seg.substring(0, cutAt)
            val hl = headers.lowercase()
            val body = seg.substring(cutAt + cutLen).removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
                .toByteArray(Charsets.ISO_8859_1)
            val data = when {
                hl.contains("base64") -> runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: continue
                hl.contains("quoted-printable") -> qpDecode(body)
                else -> body
            }
            if (html == null && hl.contains("text/html")) {
                html = String(data, java.nio.charset.Charset.forName(charsetFor(hl)))
                continue
            }
            if (html != null && hl.contains("image/")) {
                if (data.size > MAX_IMG_BYTES) continue
                val mime = Regex("content-type:\\s*([^\\r\\n;]+)", RegexOption.IGNORE_CASE)
                    .find(hl)?.groupValues?.get(1)?.trim() ?: continue
                if (mime !in IMG_MIMES) continue
                val uri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data)
                val loc = headerVal(headers, "Content-Location")
                if (!loc.isNullOrBlank()) {
                    subs.add(Regex(Regex.escape(loc), RegexOption.IGNORE_CASE) to uri)
                    val base = loc.substringAfterLast('/')
                    if (base.isNotBlank() && base != loc) {
                        // 前缀容忍：html 常引用相对路径（page_files/x.png）——仅替裸名会残留目录前缀，
                        // 用 [\w./\\:\-]*basename 把任意路径形态（绝对/相对/file:///）整段替换
                        subs.add(Regex("[\\w./\\\\:\\-]*" + Regex.escape(base), RegexOption.IGNORE_CASE) to uri)
                    }
                }
                val cid = headerVal(headers, "Content-Id")
                if (!cid.isNullOrBlank()) {
                    val id = cid.trim().removePrefix("<").removeSuffix(">")
                    if (id.isNotBlank()) subs.add(Regex(Regex.escape("cid:" + id), RegexOption.IGNORE_CASE) to uri)
                }
            }
        }
        val doc = html ?: return null
        var outHtml = doc
        for ((rx, uri) in subs) outHtml = rx.replace(outHtml, uri)
        return outHtml
    }

    /** 无边界单段 MHT：整文件按 quoted-printable/原样处理 + 编码探测转 UTF-8；探测失败返回 null。 */
    private fun decodeSingle(s: String): String? {
        val body = if (s.contains("quoted-printable", ignoreCase = true)) {
            qpDecode(s.toByteArray(Charsets.ISO_8859_1))
        } else s.toByteArray(Charsets.ISO_8859_1)
        val det = org.mozilla.universalchardet.UniversalDetector(null)
        det.handleData(body, 0, minOf(body.size, 256 * 1024))
        det.dataEnd()
        val cs = det.detectedCharset ?: return null
        val text = if (cs.equals("UTF-8", true) || cs.equals("ASCII", true) || cs.equals("US-ASCII", true)) {
            String(body, Charsets.UTF_8)
        } else String(body, java.nio.charset.Charset.forName(cs))
        return text.ifBlank { null }
    }

    /** quoted-printable：=XX 十六进制字节；=\r\n / 行尾 = 为软换行。 */
    private fun qpDecode(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            when {
                c == '='.code && (i + 1 >= b.size || b[i + 1] == '\r'.code.toByte() || b[i + 1] == '\n'.code.toByte()) -> {
                    i++
                    if (i < b.size && b[i] == '\r'.code.toByte()) i++
                    if (i < b.size && b[i] == '\n'.code.toByte()) i++
                }
                c == '='.code && i + 2 < b.size -> {
                    val hv = Character.digit((b[i + 1].toInt() and 0xFF).toChar(), 16)
                    val lv = Character.digit((b[i + 2].toInt() and 0xFF).toChar(), 16)
                    if (hv >= 0 && lv >= 0) {
                        out.write((hv shl 4) or lv)
                        i += 3
                    } else {
                        out.write(c)
                        i++
                    }
                }
                else -> {
                    out.write(c)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    private fun headerVal(headers: String, name: String): String? {
        for (line0 in headers.split('\n')) {
            val line = line0.trimEnd('\r')
            val i = line.indexOf(':')
            if (i <= 0) continue
            if (line.substring(0, i).trim().equals(name, ignoreCase = true)) return line.substring(i + 1).trim()
        }
        return null
    }

    /** 段 charset → JVM 字符集名（Qt codepageFor 同表；中文电纸书最常见 gb、big5）。 */
    private fun charsetFor(headersLower: String): String {
        val m = Regex("charset=\"?([\\w\\-]+)\"?", RegexOption.IGNORE_CASE)
            .find(headersLower)?.groupValues?.get(1)?.lowercase() ?: return "UTF-8"
        return when {
            m.startsWith("gb") || m == "936" || m == "cp936" || m == "gb2312" || m == "gb18030" -> "GB18030"
            m == "big5" || m == "950" || m == "cp950" -> "Big5"
            m.startsWith("shift") || m.startsWith("sjis") || m == "932" || m == "cp932" -> "Shift_JIS"
            m.startsWith("euc-kr") || m.startsWith("euckr") || m == "949" || m == "cp949" -> "EUC-KR"
            m == "1251" || m.startsWith("windows-1251") -> "windows-1251"
            m == "1252" || m.startsWith("windows-1252") || m.startsWith("latin") || m.startsWith("iso-8859") -> "windows-1252"
            m.startsWith("utf-16") -> "UTF-16"
            else -> "UTF-8"
        }
    }
}
