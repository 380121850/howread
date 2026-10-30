package howread.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import howread.engine.DocumentHandle
import howread.engine.EngineLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 封面缩略图：缓存于 AppPaths.coversDir（key=pathHash+mtime），无缓存时引擎渲染第 1 页。 */
object CoverStore {
    fun coverFile(path: String, mtime: Long): File =
        File(howread.core.AppPaths.coversDir, "${Integer.toHexString(path.hashCode())}-${mtime}.png")

    fun loadOrRender(path: String, mtime: Long): BufferedImage? {
        val f = coverFile(path, mtime)
        if (f.exists()) {
            try { return ImageIO.read(f) } catch (_: Exception) {}
        }
        var doc: DocumentHandle? = null
        try {
            EngineLib.ensure()
            // docx/doc/rtf/odt/zip 需转换层产物打开（缓存后仅首帧一次性转换）；djvu 引擎直开；其余格式原样
            val openPath = if (path.substringAfterLast('.', "").lowercase() in setOf("docx", "doc", "rtf", "odt", "zip"))
                howread.core.FormatRouter.openablePath(path) else path
            doc = DocumentHandle(openPath)
            val n = doc.pageCount()
            if (n <= 0) return null
            val img = doc.render(0, 320)
            // 检视修复：.tmp+rename——并发渲染同封面不再互相踩出损坏 PNG
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                if (ImageIO.write(img, "png", tmp)) {
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) tmp.delete()
                } else tmp.delete()
            } catch (_: Exception) {}
            return img
        } catch (t: Throwable) {
            return null
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }
}

@Composable
fun BookCover(path: String, title: String, mtime: Long, modifier: Modifier = Modifier, crop: Boolean = true) {
    val img by produceState<BufferedImage?>(null, path, mtime) {
        value = withContext(Dispatchers.IO) { CoverStore.loadOrRender(path, mtime) }
    }
    val placeHolder = @Composable {
        Box(modifier.fillMaxSize().background(UiTheme.p.card), contentAlignment = Alignment.Center) {
            Text(
                title.take(28), color = UiTheme.p.textSecondary, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
    img?.let { i ->
        Image(
            bitmap = i.toComposeImageBitmap(),
            contentDescription = title,
            modifier = modifier.fillMaxSize(),
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        )
    } ?: placeHolder()
}
