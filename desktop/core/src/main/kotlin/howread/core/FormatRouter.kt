package howread.core

/** 打开路径路由：按扩展名把原始文件解析为引擎可直接打开的路径。 */
object FormatRouter {

    val REFLOWABLE = setOf("epub", "txt", "fb2", "html", "htm", "xps", "svg", "mobi", "azw", "azw3", "prc", "docx", "doc", "rtf", "odt", "zip")
    val ENGINE_DIRECT = setOf("pdf", "epub", "mobi", "azw", "prc", "fb2", "txt", "cbz", "cbr", "xps", "html", "htm", "svg")

    fun ext(p: String): String = p.substringAfterLast('.', "").lowercase()

    fun isReflowable(p: String): Boolean = ext(p) in REFLOWABLE

    /** TXT 需编码转换；azw3(KF8) 引擎不支持，经 mobitool 转 EPUB 缓存后打开。
     *  大体积 KF7 mobi/azw/prc（>8MB）同样转 EPUB：htdoc 单章"整书排版才能出第一页"
     *  是大书天花板（v0.27.0），EPUB 链路=分章惰性排版+加速器重开秒开。 */
    fun openablePath(p: String): String = when (ext(p)) {
        "txt" -> TxtDecode.prepareTxt(p)
        "azw3" -> howread.core.convert.Azw3Convert.convertedEpub(p)?.absolutePath ?: p
        "mobi", "azw", "prc" -> {
            val f = java.io.File(p)
            if (f.length() > 8L * 1024 * 1024) howread.core.convert.Azw3Convert.convertedEpub(p)?.absolutePath ?: p
            else p
        }
        // v0.44.0：docx/rtf/odt 转 HTML 缓存后打开（对齐安卓转 HTML 模式）；zip 解出包内书重开（安卓 ZipContext 同语义）
        // v0.46.0：doc 并入同链路（antiword→DocBook XML，引擎 C 桥 HrDocNative）
        "docx", "doc", "rtf", "odt" -> howread.core.convert.DocConvert.convertedHtml(p)?.absolutePath ?: p
        "zip" -> {
            val first = ZipBooks.listEntries(p).firstOrNull()
            if (first == null) p else ZipBooks.extract(p, first)?.absolutePath ?: p
        }
        else -> p
    }
}
