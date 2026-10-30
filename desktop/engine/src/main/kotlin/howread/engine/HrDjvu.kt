package howread.engine

import java.awt.image.BufferedImage

/**
 * DJVU 原生桥（v0.46.0）：libdjvu-3.5.28 编入引擎 dll/so，JNI 符号在 hr_djvu.c。
 * 一个文档一个 ddjvu context；调用串行化由 DocumentHandle 的类级锁保证。
 */
object HrDjvu {
    init {
        EngineLib.ensure()
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeOpen(ctx: Long, path: String): Long
    @JvmStatic external fun nativePageCount(doc: Long): Int
    @JvmStatic external fun nativePageInfo(ctx: Long, doc: Long, page: Int, out: IntArray): Boolean
    @JvmStatic external fun nativeRender(ctx: Long, doc: Long, page: Int, w: Int, h: Int, buf: IntArray): Boolean
    @JvmStatic external fun nativeReleaseDoc(ctx: Long, doc: Long)
    @JvmStatic external fun nativeReleaseCtx(ctx: Long)
}

/** 单本 DJVU 会话（DocumentHandle 的 djvu 后端）。 */
class HrDjvuSession private constructor(private val ctx: Long, private val doc: Long) {

    companion object {
        /** 打开失败抛异常（与 mupdf 打开失败行为一致，ReaderScreen 有统一 catch）。 */
        fun openOrThrow(path: String): HrDjvuSession {
            val ctx = HrDjvu.nativeCreate()
            require(ctx != 0L) { "djvu context failed" }
            val doc = HrDjvu.nativeOpen(ctx, path)
            if (doc == 0L) {
                HrDjvu.nativeReleaseCtx(ctx)
                throw IllegalStateException("djvu open failed: $path")
            }
            return HrDjvuSession(ctx, doc)
        }
    }

    fun pageCount(): Int = HrDjvu.nativePageCount(doc)

    /** 页面尺寸（旋转后像素）。 */
    fun pageSize(page: Int): Pair<Int, Int>? {
        val out = IntArray(2)
        if (!HrDjvu.nativePageInfo(ctx, doc, page, out)) return null
        return out[0] to out[1]
    }

    /** 渲染整页到指定像素宽（fit-width）+ 主题后处理，与 DocumentHandle mupdf 路径同语义。 */
    fun renderPage(page: Int, widthPx: Int, mode: ReadMode, tint: Int): BufferedImage {
        val d = pageSize(page) ?: throw IllegalStateException("djvu page info failed: $page")
        val w = d.first
        val h = d.second
        // 检视加固：乘积上限 40M 像素（≈160MB int 数组）——超大 zoom × 超长页时等比缩小，防 OOM
        var tw = widthPx.coerceAtLeast(16)
        var th = (h.toLong() * tw / w.toLong()).toInt().coerceAtLeast(1)
        if (th.toLong() * tw > 40_000_000L) {
            val k = kotlin.math.sqrt(40_000_000.0 / (th.toDouble() * tw))
            tw = (tw * k).toInt().coerceAtLeast(16)
            th = (h.toLong() * tw / w.toLong()).toInt().coerceAtLeast(1)
        }
        val buf = IntArray(tw * th)
        if (!HrDjvu.nativeRender(ctx, doc, page, tw, th, buf)) throw IllegalStateException("djvu render failed: $page")
        val px = when {
            mode == ReadMode.NIGHT -> IntArray(buf.size) { i ->
                val v = buf[i] or 0xFF000000.toInt()
                (0xFF shl 24) or ((255 - (v shr 16 and 0xFF)) shl 16) or ((255 - (v shr 8 and 0xFF)) shl 8) or (255 - (v and 0xFF))
            }
            tint != 0 -> {
                val tr = (tint shr 16) and 0xFF
                val tg = (tint shr 8) and 0xFF
                val tb = tint and 0xFF
                IntArray(buf.size) { i ->
                    val v = buf[i] or 0xFF000000.toInt()
                    (0xFF shl 24) or (((v shr 16 and 0xFF) * tr / 255) shl 16) or (((v shr 8 and 0xFF) * tg / 255) shl 8) or ((v and 0xFF) * tb / 255)
                }
            }
            else -> {
                for (i in buf.indices) buf[i] = buf[i] or 0xFF000000.toInt()
                buf
            }
        }
        val img = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, tw, th, px, 0, tw)
        return img
    }

    fun close() {
        HrDjvu.nativeReleaseDoc(ctx, doc)
        HrDjvu.nativeReleaseCtx(ctx)
    }
}
