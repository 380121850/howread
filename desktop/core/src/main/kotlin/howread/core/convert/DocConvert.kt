package howread.core.convert

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File
import java.util.zip.ZipFile

/**
 * DOCX/RTF/ODT → HTML 转换层（v0.44.0，对齐安卓"转 HTML 缓存再开"模式）：
 * - docx：mammoth（安卓 DocxContext 同款库 org.zwobble.mammoth）；
 * - rtf：rtfparserkit（安卓 RtfExtract 同款库；桌面简化版只取文本段落，不导出 WMF 图片）；
 * - odt：zip+content.xml 自写提取（与安卓 at.stefl.opendocument 的 TextTranslator 路径等价，零新增依赖）。
 * DOC：antiword（引擎 C 桥 HrDocNative）转 DocBook XML（安卓 main_u.c convertDocToHtml 同款）；
 * DJVU：不走本层——引擎内 libdjvu 渲染，DocumentHandle 直接分支打开（v0.46.0）。
 */
object DocConvert {

    private fun cacheRoot(): File = File(AppPaths.cacheDir, "convert").apply { mkdirs() }

    /** 转换产物 HTML（缓存键=路径+大小+mtime，源变化自动重建）；失败返回 null。
     *  检视修复：@Synchronized——封面线程与打开线程并发同书时，避免双转换竞态写坏同一缓存文件。 */
    @Synchronized
    fun convertedHtml(path: String): File? {
        val src = File(path)
        if (!src.isFile) return null
        val t0 = System.currentTimeMillis()
        val key = Integer.toHexString((src.absolutePath + "#" + src.length() + "#" + src.lastModified()).hashCode())
        val out = File(cacheRoot(), "doc-$key.html")
        if (out.isFile && out.length() > 0) return out
        val body: String? = runCatching {
            when (src.extension.lowercase()) {
                "docx" -> docxToHtml(src)
                "rtf" -> rtfToHtml(src)
                "odt" -> odtToHtml(src)
                "doc" -> docToHtml(src) ?: rtfToHtml(src) ?: txtToHtml(src)
                else -> null
            }
        }.getOrNull()
        if (body.isNullOrBlank()) {
            TestLog.evt("doc_convert_fail", "ext=" + src.extension.lowercase())
            return null
        }
        out.writeText(wrap(body), Charsets.UTF_8)
        TestLog.evt("doc_convert_ok", "ext=" + src.extension.lowercase() + " ms=" + (System.currentTimeMillis() - t0) + " size=" + out.length())
        return out
    }

    private fun wrap(body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>" + body + "</body></html>"

    private fun esc(t: String): String =
        t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- docx（mammoth：安卓同款库） ----
    private fun docxToHtml(f: File): String? =
        org.zwobble.mammoth.DocumentConverter().convertToHtml(f).value

    // ---- rtf（rtfparserkit：安卓同款库） ----
    private fun rtfToHtml(f: File): String? {
        val parts = ArrayList<String>()
        val source = com.rtfparserkit.parser.RtfStreamSource(f.inputStream())
        val parser = com.rtfparserkit.parser.standard.StandardRtfParser()
        parser.parse(source, object : com.rtfparserkit.converter.text.AbstractTextConverter() {
            override fun processExtractedText(text: String) {
                if (text.isNotEmpty()) parts.add(text)
            }

            override fun processString(s: String) {
                if (s.isNotEmpty()) parts.add(s)
            }
        })
        return paragraphsToHtml(parts)
    }

    /** 段落序列 → <p> HTML（rtf / doc 回退链 txt 级共用）。 */
    private fun paragraphsToHtml(parts: List<String>): String? {
        if (parts.isEmpty()) return null
        val sb = StringBuilder()
        var cur = StringBuilder()
        for (p in parts) {
            for (ch in p) {
                if (ch == '\n') {
                    if (cur.isNotBlank()) sb.append("<p>").append(esc(cur.toString())).append("</p>\n")
                    cur = StringBuilder()
                } else cur.append(ch)
            }
        }
        if (cur.isNotBlank()) sb.append("<p>").append(esc(cur.toString())).append("</p>\n")
        return sb.toString().ifBlank { null }
    }

    // ---- doc 回退链第三级：纯文本（antiword 失败且非 rtf 时；安卓 DocContext antiword→RTF→TXT 同款） ----
    private fun txtToHtml(f: File): String? {
        return runCatching {
            val bytes = f.readBytes()
            val probe = minOf(bytes.size, 4096)
            for (i in 0 until probe) if (bytes[i].toInt() == 0) return@runCatching null   // 二进制容器不是文本
            val det = org.mozilla.universalchardet.UniversalDetector(null)
            det.handleData(bytes, 0, minOf(bytes.size, 256 * 1024))
            det.dataEnd()
            val cs = det.detectedCharset ?: return@runCatching null
            val text = if (cs.equals("UTF-8", true) || cs.equals("ASCII", true) || cs.equals("US-ASCII", true)) {
                String(bytes, Charsets.UTF_8)
            } else String(bytes, java.nio.charset.Charset.forName(cs)).trimStart('\uFEFF')
            paragraphsToHtml(text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() })
        }.getOrNull()
    }

    // ---- odt（zip+content.xml：与安卓 at.stefl.opendocument 路径等价） ----
    private fun odtToHtml(f: File): String? {
        ZipFile(f).use { zf ->
            val e = zf.getEntry("content.xml") ?: return null
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val doc = dbf.newDocumentBuilder().parse(zf.getInputStream(e))
            val sb = StringBuilder()
            fun textOf(n: org.w3c.dom.Node): String {
                val sb2 = StringBuilder()
                fun walk(x: org.w3c.dom.Node) {
                    when {
                        x.nodeType == org.w3c.dom.Node.TEXT_NODE -> sb2.append(esc(x.nodeValue))
                        x.nodeName.endsWith(":tab") -> sb2.append("  ")
                        x.nodeName.endsWith(":line-break") -> sb2.append("<br/>")
                        x.nodeName.endsWith(":s") -> sb2.append(" ")
                    }
                    val kids = x.childNodes
                    for (i in 0 until kids.length) walk(kids.item(i))
                }
                walk(n)
                return sb2.toString()
            }
            val officeText = doc.documentElement.getElementsByTagName("office:text").item(0) ?: return null
            val kids = officeText.childNodes
            for (i in 0 until kids.length) {
                val n = kids.item(i)
                val name = n.nodeName
                when {
                    name.endsWith(":p") || name.endsWith(":h") -> sb.append("<p>").append(textOf(n)).append("</p>\n")
                    name.endsWith(":list") -> {
                        sb.append("<ul>\n")
                        val items = n.childNodes
                        for (j in 0 until items.length) {
                            val li = items.item(j)
                            if (li.nodeName.endsWith(":list-item")) sb.append("<li>").append(textOf(li)).append("</li>\n")
                        }
                        sb.append("</ul>\n")
                    }
                }
            }
            return sb.toString().ifBlank { null }
        }
    }

    // ---- doc（antiword：安卓 LibMobi.convertDocToHtml 同引擎同配置，C 桥 HrDocNative） ----
    @Synchronized
    private fun docToHtml(f: File): String? {
        val out = File(AppPaths.cacheDir, "antiword-" + Integer.toHexString(f.absolutePath.hashCode()) + "-" + f.length() + ".xml")
        runCatching { out.delete() }
        val rc = runCatching { HrDocNative.docToHtml(f.absolutePath, out.absolutePath) }.getOrDefault(0)
        if (rc != 1 || !out.isFile || out.length() == 0L) return null
        val body = runCatching { out.readText(Charsets.UTF_8) }.getOrDefault("")
        runCatching { out.delete() }
        return body.ifBlank { null }
    }
}
