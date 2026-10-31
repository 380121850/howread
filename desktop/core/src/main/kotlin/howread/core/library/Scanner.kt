package howread.core.library

import howread.core.TestLog
import howread.core.data.Db
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object Scanner {
    val BOOK_EXTS = setOf("pdf", "epub", "mobi", "azw", "azw3", "prc", "fb2", "txt", "cbz", "cbr", "xps", "html", "htm", "svg", "docx", "doc", "rtf", "odt", "zip", "djvu")

    fun isBookFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in BOOK_EXTS && ScanPrefs.enabled(path)

    /** 扫描全部书库目录（mtime+size 指纹增量 upsert），返回在册书籍总数。
     *  v0.30.0：只扫描勾选上架（shelf=1）的目录——取消勾选的目录其书籍随本次扫描自动从书架移除。 */
    fun scanAll(ignoreNomedia: Boolean = false, useCalibreOpf: Boolean = false): Int {
        val t0 = System.currentTimeMillis()
        val dirs = Db.listScanDirRows().filter { it.shelf }.map { it.path }
        val existing = HashSet<String>()
        var added = 0
        // 检视修复：任一目录本轮不可访问（U 盘/网络盘离线、IO 错误）即跳过裁剪——
        // 否则该目录全部书籍的进度/书签/批注会被级联永久删除
        var anyDirMissing = false
        for (d in dirs) {
            val dir = File(d)
            if (!dir.isDirectory) { anyDirMissing = true; continue }
            try {
                walkBooks(File(d), ignoreNomedia) { f ->
                    Db.upsertFileMeta(f.absolutePath, bookTitle(f, useCalibreOpf), f.length(), f.lastModified())
                    if (existing.add(f.absolutePath)) added++
                } ?: run { anyDirMissing = true }   // listFiles 为 null=目录不可读
            } catch (e: Exception) {
                TestLog.evt("scan_dir_error", "dir=$d err=$e")
                anyDirMissing = true
            }
        }
        val pruned = if (!anyDirMissing) Db.pruneMissing(existing) else 0
        val total = existing.size
        TestLog.evt("scan_done", "dirs=${dirs.size} found=$total added=$added pruned=$pruned ${System.currentTimeMillis() - t0}ms")
        return total
    }

    /** 递归收集书籍文件；ignoreNomedia 时跳过含 .nomedia 的子目录（安卓同行为）。
     *  返回 null 表示目录不可读（listFiles 失败），调用方据此跳过本轮裁剪。 */
    private fun walkBooks(dir: File, ignoreNomedia: Boolean, sink: (File) -> Unit): Boolean? {
        val kids = dir.listFiles() ?: return null
        for (f in kids) {
            if (f.isDirectory) {
                if (ignoreNomedia && File(f, ".nomedia").isFile) continue
                if (walkBooks(f, ignoreNomedia, sink) == null) return null
            } else if (isBookFile(f.absolutePath)) sink(f)
        }
        return true
    }

    /** 书名：默认=文件名去扩展名；useCalibreOpf 时优先取书同目录 Calibre metadata.opf 的 dc:title（v0.55.0）。 */
    fun bookTitle(f: File, useCalibreOpf: Boolean): String {
        if (useCalibreOpf) calibreOpfTitle(f)?.let { return it }
        return f.nameWithoutExtension
    }

    /** Calibre opf 书名：同目录 metadata.opf / <书名>.opf 的 dc:title，未命中返 null。 */
    fun calibreOpfTitle(book: File): String? = runCatching {
        val opf = File(book.parentFile, "metadata.opf").takeIf { it.isFile }
            ?: File(book.parentFile, book.nameWithoutExtension + ".opf").takeIf { it.isFile }
            ?: return@runCatching null
        val xml = opf.readText(Charsets.UTF_8)
        Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { unescapeXml(it) }
    }.getOrNull()

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
