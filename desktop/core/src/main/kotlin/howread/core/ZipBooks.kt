package howread.core

import howread.core.library.Scanner
import java.io.File
import java.util.zip.ZipFile

/** zip 压缩包内阅读：列出包内图书条目、按需解出单个条目到缓存（缓存键=zip路径+大小+mtime）。 */
object ZipBooks {

    fun isZip(path: String): Boolean = path.substringAfterLast('.', "").lowercase() == "zip"

    /** zip 内的书籍条目名（按扩展名过滤）。 */
    fun listEntries(zipPath: String): List<String> = runCatching {
        ZipFile(zipPath).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { Scanner.isBookFile(it.name) }
                .map { it.name }
                .toList()
                .distinct()
        }
    }.getOrDefault(emptyList())

    /** 解出指定条目到缓存目录，返回本地文件（已缓存则直用）；失败返回 null。 */
    fun extract(zipPath: String, entry: String): File? = runCatching {
        val zip = File(zipPath)
        val key = Integer.toHexString((zipPath + "|" + zip.length() + "|" + zip.lastModified()).hashCode())
        val out = File(File(File(AppPaths.cacheDir, "zip"), key), File(entry).name)
        if (!out.isFile() || out.length() == 0L) {
            out.parentFile?.mkdirs()
            ZipFile(zipPath).use { zf ->
                val e = zf.getEntry(entry) ?: return@use
                zf.getInputStream(e).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        if (out.isFile() && out.length() > 0) out else null
    }.getOrNull()
}
