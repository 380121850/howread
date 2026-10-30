package howread.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.awt.image.BufferedImage
import java.io.File

/** 阅读主题（渲染后处理）：普通 / 纸色 / 夜间。 */
enum class ReadMode { NORMAL, SEPIA, NIGHT }

data class TocEntry(val level: Int, val title: String, val page: Int)

/** 划词高亮矩形（页面坐标系）。 */
data class SelRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/**
 * 单本文档句柄。fitz 非线程安全：所有引擎调用经类级锁串行化（安卓侧同款纪律）。
 */
class DocumentHandle private constructor(private val doc: Document?, private val djvu: HrDjvuSession?) {

    companion object {
        private val lock = ENGINE_LOCK

        /** 带排版加速器打开（安卓 accels 同思路）：加速器缺失/损坏/不匹配时回退普通打开。 */
        private fun openWithAccel(path: String, accelPath: String): Document = try {
            Document.openDocument(File(path).absolutePath, accelPath)
        } catch (_: Exception) {
            Document.openDocument(File(path).absolutePath)
        }

        /** DJVU 走引擎内 libdjvu 桥（v0.46.0），不经 mupdf（含 .djv 别名）。 */
        private fun isDjvu(path: String): Boolean = File(path).extension.lowercase() in setOf("djvu", "djv")
    }

    constructor(path: String) : this(
        // 检视修复：构造/打开也在引擎锁内——与封面线程渲染等并发共享同一 mupdf 全局上下文
        if (isDjvu(path)) null else synchronized(ENGINE_LOCK) { Document.openDocument(File(path).absolutePath) },
        if (isDjvu(path)) HrDjvuSession.openOrThrow(File(path).absolutePath) else null,
    )

    /** 远程块缓存打开（v0.34.0 引擎自有入口 HROpen，官方流路径在自建 DLL 上确定性崩溃）：
     *  MuPDF 从 SeekableInputStream 按需读块——任意格式（含 PDF 尾部 xref）均可部分打开。 */
    constructor(stream: com.artifex.mupdf.fitz.SeekableInputStream, magic: String) : this(
        synchronized(ENGINE_LOCK) { HROpen.openStream(magic, stream) }, null
    )

    constructor(path: String, accelPath: String) : this(
        if (isDjvu(path)) null else synchronized(ENGINE_LOCK) { openWithAccel(path, accelPath) },
        if (isDjvu(path)) HrDjvuSession.openOrThrow(File(path).absolutePath) else null,
    )

    @Volatile
    private var closed = false

    /** 引擎调用前守卫：句柄已关闭（换书/重开竞态）则快速失败，避免 destroy 后再用。 */
    private fun guard() {
        if (closed) throw IllegalStateException("DocumentHandle closed")
    }

    fun pageCount(): Int = synchronized(lock) { guard(); djvu?.let { return it.pageCount() }; doc!!.countPages() }

    /** 章节数（无章节概念的文档=1）。 */
    fun countChapters(): Int = synchronized(lock) {
        guard()
        djvu?.let { return 1 }
        runCatching { doc!!.countChapters() }.getOrDefault(1)
    }

    /** 单章页数（按章渐进计数原语；章与章之间锁会释放，页面渲染可插队）。 */
    fun countPagesOf(chapter: Int): Int = synchronized(lock) {
        guard()
        djvu?.let { return if (chapter == 0) it.pageCount() else 0 }
        runCatching { doc!!.countPages(chapter) }.getOrDefault(0)
    }

    /** 保存排版加速器（仅 EPUB 等支持加速器的格式；下次同几何打开免整书重排版）。 */
    fun saveAccel(destPath: String): Boolean = synchronized(lock) {
        guard()
        djvu?.let { return false }
        try {
            if (!doc!!.supportsAccelerator()) false
            else {
                File(destPath).parentFile?.mkdirs()
                doc!!.saveAccelerator(destPath)
                true
            }
        } catch (e: Exception) {
            if (System.getenv("HR_DEBUG") != null) e.printStackTrace()
            false
        }
    }

    /** 文档作者元数据（无则空串）。 */
    fun metaAuthor(): String = synchronized(lock) {
        guard()
        djvu?.let { return "" }
        runCatching { doc!!.getMetaData(Document.META_INFO_AUTHOR) }.getOrDefault("")?.trim() ?: ""
    }

    /** 把持久高亮写为 PDF 注解并另存副本（仅 PDF）。items: (页, 颜色ARGB, 四边形页坐标)。 */
    fun exportPdfWithHighlights(destPath: String, items: List<Triple<Int, Int, List<SelRect>>>): Boolean = synchronized(lock) {
        guard()
        djvu?.let { return false }
        try {
            val pdf = doc as? com.artifex.mupdf.fitz.PDFDocument ?: return false
            for ((pageIdx, argb, quads) in items) {
                val p = pdf.loadPage(pageIdx) as? com.artifex.mupdf.fitz.PDFPage ?: continue
                try {
                    val ann = p.createAnnotation(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT)
                    // Highlight 注解不允许 setRect（引擎由 QuadPoints 自算边界）
                    val fq = quads.map { q -> com.artifex.mupdf.fitz.Quad(q.x0, q.y1, q.x1, q.y1, q.x0, q.y0, q.x1, q.y0) }
                    ann.setQuadPoints(fq.toTypedArray())
                    val r = ((argb shr 16) and 0xFF) / 255f
                    val g = ((argb shr 8) and 0xFF) / 255f
                    val b = (argb and 0xFF) / 255f
                    ann.setColor(floatArrayOf(r, g, b))
                    ann.setOpacity(0.5f)
                    ann.setAuthor("HowReadPro")
                    ann.update()
                } finally {
                    p.destroy()
                }
            }
            pdf.save(destPath, "")
            true
        } catch (e: Exception) {
            if (System.getenv("HR_DEBUG") != null) e.printStackTrace()
            false
        }
    }

    /** 某页 PDF 注解数量（非 PDF 返回 -1；验证写回用）。 */
    fun pdfAnnotationCount(page: Int): Int = synchronized(lock) {
        guard()
        djvu?.let { return -1 }
        try {
            val p = doc!!.loadPage(page) as? com.artifex.mupdf.fitz.PDFPage ?: return -1
            try { p.annotations?.size ?: 0 } finally { p.destroy() }
        } catch (_: Exception) {
            -1
        }
    }

    /** reflowable 文档（EPUB/TXT/FB2…）排版：按视口宽高分页，em=字号（pt 基准 12）。
     *  排版后页数/页面尺寸变化，需重新读取 pageCount/pageSize。 */
    fun layout(widthPx: Float, heightPx: Float, em: Float) = synchronized(lock) {
        guard()
        djvu?.let { return }
        doc!!.layout(widthPx, heightPx, em)
    }

    fun pageSize(page: Int): Pair<Float, Float> = synchronized(lock) {
        guard()
        djvu?.let {
            val d = it.pageSize(page) ?: return 595f to 842f
            return d.first.toFloat() to d.second.toFloat()
        }
        val p = doc!!.loadPage(page)
        try {
            Pair(p.bounds.x1 - p.bounds.x0, p.bounds.y1 - p.bounds.y0)
        } finally {
            p.destroy()
        }
    }

    /** 页面盒子（页面坐标系 x0,y0,x1,y1；划词屏幕坐标映射用）。 */
    fun pageBox(page: Int): FloatArray = synchronized(lock) {
        guard()
        djvu?.let {
            val d = it.pageSize(page) ?: return floatArrayOf(0f, 0f, 595f, 842f)
            return floatArrayOf(0f, 0f, d.first.toFloat(), d.second.toFloat())
        }
        val p = doc!!.loadPage(page)
        try {
            val b = p.bounds
            floatArrayOf(b.x0, b.y0, b.x1, b.y1)
        } finally {
            p.destroy()
        }
    }

    /** 划词选区：页面坐标两点 -> (选中文字, 高亮矩形列表)；无文字层返回空。 */
    fun selection(page: Int, ax: Float, ay: Float, bx: Float, by: Float): Pair<String, List<SelRect>> = synchronized(lock) {
        guard()
        djvu?.let { return "" to emptyList<SelRect>() }
        val p = doc!!.loadPage(page)
        var st: StructuredText? = null
        try {
            st = p.toStructuredText()
            val s = st ?: return "" to emptyList()
            val text = runCatching { s.copy(Point(ax, ay), Point(bx, by)) }.getOrDefault("") ?: ""
            val rects = runCatching {
                s.highlight(Point(ax, ay), Point(bx, by)).map { q ->
                    val r = q.toRect()
                    SelRect(r.x0, r.y0, r.x1, r.y1)
                }
            }.getOrDefault(emptyList())
            text.trim() to rects
        } catch (_: Exception) {
            "" to emptyList()
        } finally {
            st?.destroy()
            p.destroy()
        }
    }

    /** 目录（拍平带层级缩进；page 为 0 基绝对页码，解析失败 -1）。 */
    fun outline(): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        synchronized(lock) {
            guard()
            djvu?.let { return emptyList<TocEntry>() }
            fun walk(list: Array<Outline>?, level: Int) {
                if (list == null) return
                for (o in list) {
                    val page = try {
                        val loc = doc!!.resolveLink(o.uri)
                        if (loc != null) doc!!.pageNumberFromLocation(loc) else -1
                    } catch (_: Exception) {
                        -1
                    }
                    out.add(TocEntry(level, o.title ?: "", page))
                    walk(o.down, level + 1)
                }
            }
            walk(doc!!.loadOutline(), 0)
        }
        return out
    }

    /** 指定页搜索命中次数（Quad 数组行数和）。 */
    fun searchPage(page: Int, needle: String): Int = synchronized(lock) {
        guard()
        djvu?.let { return 0 }
        val p = doc!!.loadPage(page)
        var st: StructuredText? = null
        try {
            st = p.toStructuredText()
            st.search(needle).sumOf { it.size }
        } catch (_: Exception) {
            0
        } finally {
            st?.destroy()
            p.destroy()
        }
    }


    /** 提取指定页段落（stext block 级）；行尾软连字符摘除、CJK 行间不加空格。
     *  供 AI 翻译分段使用（安卓 getPageAsHtml 的桌面等价物）。 */
    fun pageParagraphs(page: Int): List<String> = synchronized(lock) {
        guard()
        djvu?.let { return emptyList<String>() }
        val p = doc!!.loadPage(page)
        var st: StructuredText? = null
        try {
            st = p.toStructuredText()
            val paras = ArrayList<String>()
            val block = StringBuilder()
            val line = StringBuilder()
            fun isCjk(c: Char): Boolean {
                val k = c.code
                return k in 0x4E00..0x9FFF || k in 0x3000..0x303F || k in 0xFF00..0xFFEF || k in 0x3040..0x30FF
            }
            fun flushLine() {
                var t = line.toString()
                if (t.endsWith("-")) t = t.dropLast(1) // soft-hyphenated split word
                if (t.isNotEmpty()) {
                    if (block.isNotEmpty() && !isCjk(block.last()) && !isCjk(t.first())) block.append(' ')
                    block.append(t)
                }
                line.setLength(0)
            }
            st.walk(object : StructuredTextWalker {
                override fun onImageBlock(bbox: Rect, transform: Matrix, image: Image) {}
                override fun beginTextBlock(bbox: Rect) { block.setLength(0); line.setLength(0) }
                override fun endTextBlock() {
                    flushLine()
                    val t = block.toString().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) paras.add(t)
                }
                override fun beginLine(bbox: Rect, wmode: Int, dir: Point) {}
                override fun endLine() { flushLine() }
                override fun onChar(c: Int, origin: Point, font: Font, size: Float, q: Quad) {
                    if (c >= 0x10000) line.append(Character.toChars(c)) else line.append(c.toChar())
                }
            })
            paras
        } catch (_: Exception) {
            emptyList()
        } finally {
            st?.destroy()
            p.destroy()
        }
    }

    /** 渲染指定页到给定像素宽（fit-width），按阅读主题做后处理，返回 RGB BufferedImage。
     *  tint != 0：按底色相乘（浅色主题：白底 → tint 色，深色字/图保持）。 */
    fun render(page: Int, widthPx: Int, mode: ReadMode = ReadMode.NORMAL, tint: Int = 0): BufferedImage = synchronized(lock) {
        guard()
        djvu?.let { return it.renderPage(page, widthPx, mode, tint) }
        val p: Page = doc!!.loadPage(page)
        var pm: Pixmap? = null
        try {
            val b = p.bounds
            var scale = widthPx / maxOf(1f, b.x1 - b.x0)
            // 检视加固：与 djvu 路径同款 40M 像素上限——极端长页×大 viewport 防 OOM
            run {
                val pw = (b.x1 - b.x0) * scale
                val ph = (b.y1 - b.y0) * scale
                if (pw > 0f && ph > 0f && pw * ph > 40_000_000f) {
                    scale *= kotlin.math.sqrt(40_000_000f / (pw * ph))
                }
            }
            pm = p.toPixmap(Matrix(scale, scale), ColorSpace.DeviceRGB, false)
            val w = pm.width
            val h = pm.height
            val samples = pm.samples
            val n = 3 // DeviceRGB, alpha=false
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val px = IntArray(w * h)
            when {
                mode == ReadMode.NIGHT ->
                    for (i in 0 until w * h) {
                        val r = 255 - (samples[i * n].toInt() and 0xFF)
                        val g = 255 - (samples[i * n + 1].toInt() and 0xFF)
                        val bl = 255 - (samples[i * n + 2].toInt() and 0xFF)
                        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                tint != 0 -> {
                    val tr = (tint shr 16) and 0xFF
                    val tg = (tint shr 8) and 0xFF
                    val tb = tint and 0xFF
                    for (i in 0 until w * h) {
                        val r = ((samples[i * n].toInt() and 0xFF) * tr) / 255
                        val g = ((samples[i * n + 1].toInt() and 0xFF) * tg) / 255
                        val bl = ((samples[i * n + 2].toInt() and 0xFF) * tb) / 255
                        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                }
                else ->
                    for (i in 0 until w * h) {
                        px[i] = (0xFF shl 24) or
                            ((samples[i * n].toInt() and 0xFF) shl 16) or
                            ((samples[i * n + 1].toInt() and 0xFF) shl 8) or
                            (samples[i * n + 2].toInt() and 0xFF)
                    }
            }
            img.setRGB(0, 0, w, h, px, 0, w)
            img
        } finally {
            pm?.destroy()
            p.destroy()
        }
    }

    fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        djvu?.close()
        doc?.destroy()
    }
}
