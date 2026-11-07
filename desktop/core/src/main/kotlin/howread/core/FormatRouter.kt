package howread.core

/** 打开路径路由：按扩展名把原始文件解析为引擎可直接打开的路径。 */
object FormatRouter {

    // v0.58 +md/pdb/mht/mhtml（tif/tiff 为图片文档走固定版式分支，绝不入 reflowable——否则渐进计 Chapter 数得 0 页）
    val REFLOWABLE = setOf("epub", "txt", "md", "fb2", "html", "htm", "mht", "mhtml", "xps", "svg", "mobi", "azw", "azw3", "prc", "pdb", "docx", "doc", "rtf", "odt", "zip")
    val ENGINE_DIRECT = setOf("pdf", "epub", "mobi", "azw", "prc", "fb2", "txt", "cbz", "cbr", "xps", "html", "htm", "svg")

    fun ext(p: String): String = p.substringAfterLast('.', "").lowercase()

    fun isReflowable(p: String): Boolean = ext(p) in REFLOWABLE

    /** TXT 需编码转换；azw3(KF8) 引擎不支持，经 mobitool 转 EPUB 缓存后打开。
     *  大体积 KF7 mobi/azw/prc（>8MB）同样转 EPUB：htdoc 单章"整书排版才能出第一页"
     *  是大书天花板（v0.27.0），EPUB 链路=分章惰性排版+加速器重开秒开。 */
    fun openablePath(p: String): String = when (ext(p)) {
        // v0.58：md 走 TXT 通道——引擎 html-doc 本认 md/markdown，经 TxtDecode 使 GBK/UTF-16 副本同样可读
        "txt", "md" -> TxtDecode.prepareTxt(p)
        "azw3" -> howread.core.convert.Azw3Convert.convertedEpub(p)?.absolutePath ?: p
        "mobi", "azw", "prc" -> {
            val f = java.io.File(p)
            if (f.length() > 8L * 1024 * 1024) howread.core.convert.Azw3Convert.convertedEpub(p)?.absolutePath ?: p
            else p
        }
        // v0.44.0：docx/rtf/odt 转 HTML 缓存后打开（对齐安卓转 HTML 模式）；zip 解出包内书重开（安卓 ZipContext 同语义）
        // v0.46.0：doc 并入同链路（antiword→DocBook XML，引擎 C 桥 HrDocNative）
        "docx", "doc", "rtf", "odt" -> howread.core.convert.DocConvert.convertedHtml(p)?.absolutePath ?: p
        // v0.58：MHT/MHTML 归档 → 单 HTML（Qt v0.105 mhtConvert 同思路）；pdb/tif/tiff 引擎原生直开（mobi_extensions/muimg）落 else 分支
        "mht", "mhtml" -> howread.core.convert.MhtConvert.convertedHtml(p)?.absolutePath ?: p
        "zip" -> {
            val first = ZipBooks.listEntries(p).firstOrNull()
            if (first == null) p else ZipBooks.extract(p, first)?.absolutePath ?: p
        }
        else -> p
    }
}
