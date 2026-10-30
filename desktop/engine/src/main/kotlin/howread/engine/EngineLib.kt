package howread.engine

import java.io.File

/** 全引擎互斥锁：mupdf 全局 fz_context 非线程安全——DocumentHandle 全部方法与
 *  EngineLib 的全局 CSS 设置共用此锁（检视修复：构造/打开/CSS 此前绕锁与渲染并发）。 */
internal val ENGINE_LOCK = Any()

/** 引擎原生库加载：优先显式路径 System.load，兜底 java.library.path（启动器负责设置）。 */
object EngineLib {
    @Volatile private var loaded = false

    fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val libName = if (isWin) "mupdf_java64.dll" else "libmupdf_java64.so"
            val candidates = listOf(
                System.getProperty("howread.engine.dir"),
                "engine", "../engine",
                "../prebuilt/linux/x86_64", "../prebuilt/windows/x86_64",
                System.getenv("HOWREAD_ENGINE_DIR"),
            ).filterNotNull()
            var ok = false
            for (d in candidates) {
                val f = File(d, libName)
                if (f.exists()) { System.load(f.absolutePath); ok = true; break }
            }
            if (!ok) System.loadLibrary("mupdf_java64") // 走 java.library.path（含 Context 兜底路径）
            loaded = true
        }
    }

    /** 用户 CSS（EPUB/TXT 字号等，需在打开文档前设置；null 清除）。 */
    fun setUserCss(css: String?) {
        ensure()
        synchronized(ENGINE_LOCK) { com.artifex.mupdf.fitz.Context.setUserCSS(css) }
    }

    /** 书内自带 CSS 开关（false=仅用户样式；需在打开文档前设置）。 */
    fun setUseDocumentCss(use: Boolean) {
        ensure()
        synchronized(ENGINE_LOCK) { com.artifex.mupdf.fitz.Context.useDocumentCSS(use) }
    }
}
