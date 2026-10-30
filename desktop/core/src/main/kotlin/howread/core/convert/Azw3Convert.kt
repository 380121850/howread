package howread.core.convert

import howread.core.AppPaths
import howread.core.TestLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * azw3(KF8) 转换层：MuPDF 不支持 KF8，借 libmobi 的 mobitool 转成 EPUB 后打开。
 * 工具随包分发（engine 同级 tools/ 目录）；产物按 源路径+大小+mtime 缓存于
 * cache/convert/，二次打开零转换。DRM 加密或转换失败返回 null（回落原路径，
 * 由引擎给出打开错误）。
 */
object Azw3Convert {

    private val exe =
        if (System.getProperty("os.name", "").lowercase().contains("win")) "mobitool.exe" else "mobitool"

    /** 定位 mobitool：java.library.path 各目录（engine）内 / 其同级 tools/ / 工作目录 tools/。 */
    fun toolFile(): File? {
        val cands = ArrayList<File>()
        for (p in System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (p.isBlank()) continue
            cands.add(File(p, exe))
            cands.add(File(File(p).parentFile, "tools/$exe"))
        }
        cands.add(File("tools", exe))
        return cands.firstOrNull { it.isFile }
    }

    private fun cacheRoot(): File = File(AppPaths.cacheDir, "convert").apply { mkdirs() }

    /** 返回转换产物 epub；无工具/失败返回 null。 */
    fun convertedEpub(path: String): File? {
        val src = File(path)
        if (!src.isFile) return null
        val key = Integer.toHexString((src.absolutePath + "#" + src.length() + "#" + src.lastModified()).hashCode())
        val outDir = File(cacheRoot(), "azw3-$key")
        val epub = File(outDir, src.nameWithoutExtension + ".epub")
        if (epub.isFile && epub.length() > 0) return epub
        val tool = toolFile()
        if (tool == null) {
            TestLog.evt("azw3_no_tool")
            return null
        }
        val t0 = System.currentTimeMillis()
        var output = ""
        return try {
            outDir.mkdirs()
            val p = ProcessBuilder(tool.absolutePath, "-e", "-o", outDir.absolutePath, src.absolutePath)
                .redirectErrorStream(true)
                .start()
            val reader = Thread {
                output = runCatching { p.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
            }.apply { isDaemon = true }
            reader.start()
            val finished = p.waitFor(120, TimeUnit.SECONDS)
            reader.join(1000)
            if (finished && p.exitValue() == 0 && epub.isFile && epub.length() > 0) {
                TestLog.evt("azw3_convert", (System.currentTimeMillis() - t0).toString() + "ms size=" + epub.length())
                epub
            } else {
                p.destroyForcibly()
                outDir.deleteRecursively()
                TestLog.evt("azw3_convert_fail",
                    "rc=" + (if (finished) p.exitValue().toString() else "timeout") + " " + output.take(160))
                null
            }
        } catch (e: Exception) {
            TestLog.evt("azw3_convert_fail", e.toString())
            null
        }
    }
}
