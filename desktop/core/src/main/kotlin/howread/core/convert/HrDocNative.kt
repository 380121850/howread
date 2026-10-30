package howread.core.convert

import java.io.File

/** DOC(Word 97-2003) 转换 JNI（antiword，编入引擎 dll；v0.46.0，桥在 hr_antiword.c）。 */
object HrDocNative {
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val win = System.getProperty("os.name").lowercase().contains("win")
            val lib = if (win) "mupdf_java64.dll" else "libmupdf_java64.so"
            val dir = System.getProperty("howread.engine.dir")
            if (dir != null && File(dir, lib).isFile) System.load(File(dir, lib).absolutePath)
            else System.loadLibrary("mupdf_java64")
            loaded = true
        }
    }

    /** 成功返回 1（产物为 DocBook XML 正文，由调用方包缓存）。 */
    fun docToHtml(input: String, output: String): Int {
        ensure()
        return nativeDocToHtml(input, output)
    }

    @JvmStatic private external fun nativeDocToHtml(input: String, output: String): Int
}
