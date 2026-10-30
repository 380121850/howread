package howread.app

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** 安卓平台同源位图资源（侧栏横幅 / 应用图标）；skia 解码，进程内缓存，失败返 null 不炸 UI。 */
object Res {
    private val cache = HashMap<String, ImageBitmap?>()

    private fun bytes(name: String): ByteArray? =
        Res::class.java.classLoader?.getResourceAsStream(name)?.use { it.readBytes() }

    fun bitmap(name: String): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(name)) return cache[name] }
        val bmp = runCatching {
            val b = bytes(name) ?: return@runCatching null
            Image.makeFromEncoded(b).toComposeImageBitmap()
        }.getOrNull()
        synchronized(cache) { cache[name] = bmp }
        return bmp
    }

    /** 主窗口图标 Painter（Main.kt Window(icon=...)；安卓 mipmap 同图）。 */
    fun appIconPainter(): Painter? {
        val bmp = bitmap("howread_icon.png") ?: return null
        return object : Painter() {
            override val intrinsicSize: Size = Size(bmp.width.toFloat(), bmp.height.toFloat())
            override fun DrawScope.onDraw() {
                drawImage(bmp)
            }
        }
    }
}
