package howread.core.reader

import howread.core.AppPaths
import org.json.JSONObject
import java.io.File

/**
 * 排版偏好（作用于 reflowable 文档：EPUB/TXT/FB2/HTML…）。
 * v0.25.0 对齐安卓 BookCSS：默认两端对齐 / 行距 1.8em / 首行缩进 2em / 段距 0.2em /
 * 正文字号复位 medium / @page 页边距；桌面新增"每行字数"版心上限（0=全宽）与字号持久化。
 * 引擎侧配合：TXT 已由整块 <pre> 段落化为逐段 <p>（缩进/段距自此对 TXT 生效）。
 */
data class ReaderPrefs(
    val fontPath: String = "",       // dataDir/fonts 下 ttf/otf/ttc；空 = 未用
    val lineHeight: Int = 180,       // 行距 %（100~250；安卓默认 1.8em 同款）
    val marginTop: Int = 4,          // 页边距 pt（0~60；经 @page 注入）
    val marginRight: Int = 10,
    val marginBottom: Int = 4,
    val marginLeft: Int = 10,
    val align: String = "justify",   // left / justify / right / center（安卓默认两端）
    val family: String = "",         // "" = 书内默认；sans-serif / monospace / 用户字体名
    val useDocumentCss: Boolean = true, // false = 仅用户样式（覆盖书内 CSS）
    val fontScale: Int = 0,          // 字号 %（60~220 持久化；0 = 按屏显比例自动定基）
    val textIndent: Int = 20,        // 首行缩进 0.1em（20 = 2em；安卓同默认）
    val paraSpacing: Int = 1,        // 段间距 0.1em（1 = 0.2em；安卓同默认）
    val maxChars: Int = 0,           // 每行字数上限（0 = 全宽自适应跟随窗口；v0.29.0 起默认全宽，旧档案一次性迁移）
    val readTheme: String = "day",   // 阅读主题 day/milk/parchment/gray/night（v0.26.0，对齐安卓 READ_COLORS）
    val readTexture: String = "none", // 背景质感 none/paper/fiber/linen/parchment/canvas（v0.29.0，仅浅色主题叠加）
) {
    val fontName: String
        get() = if (fontPath.isBlank()) "书内默认" else File(fontPath).nameWithoutExtension

    /** 生成 MuPDF user CSS（在打开文档前 setUserCss 生效）。 */
    val css: String
        get() {
            val sb = StringBuilder()
            if (fontPath.isNotBlank()) {
                val url = "file:///" + File(fontPath).absolutePath.replace('\\', '/').replace("\"", "")
                sb.append("@font-face { font-family: \"HowReadUserFont\"; src: url(\"")
                    .append(url).append("\"); }\n")
                sb.append("body { font-family: \"HowReadUserFont\"; }\n")
            } else if (family.isNotBlank()) {
                // 预设关键字不能加引号（引号即具名族）；自定义字体名（=用户字体目录文件名）加引号
                val fam = family.replace("\"", "")
                if (fam == "sans-serif" || fam == "monospace" || fam == "serif")
                    sb.append("body, p, div, pre { font-family: ").append(fam).append(" !important; }\n")
                else
                    sb.append("body, p, div, pre { font-family: \"").append(fam).append("\" !important; }\n")
            }
            // 页边距（安卓 BookCSS 同款：@page !important，取代旧 body margin 写法）
            sb.append("@page { margin-top: ").append(marginTop).append("pt !important; margin-right: ")
                .append(marginRight).append("pt !important; margin-bottom: ").append(marginBottom)
                .append("pt !important; margin-left: ").append(marginLeft).append("pt !important; }\n")
            // 正文统一（安卓 BookCSS 同款：行距/缩进 !important、字号复位 medium、对齐）
            sb.append("body, div, p, span { line-height: ").append("%.2fem".format(java.util.Locale.ROOT, lineHeight / 100f))
                .append(" !important; text-indent: ").append("%.1fem".format(java.util.Locale.ROOT, textIndent / 10f))
                .append(" !important; text-align: ").append(align)
                .append("; font-size: medium !important; }\n")
            // 段间距（安卓 paragraphHeight 同款：值×2/10 em）
            if (paraSpacing > 0)
                sb.append("div, p { margin: ").append("%.1fem".format(java.util.Locale.ROOT, paraSpacing / 5f)).append(" 0 !important; }\n")
            // 阅读主题（非白昼时注入底色/字色；day 保持书内原色）——安卓 BookCSS 背景色注入同思路
            if (readTheme != "day") {
                val th = ReadThemes.byId(readTheme)
                val bgc = "#%06X".format(th.bg and 0xFFFFFF)
                val fgc = "#%06X".format(th.text and 0xFFFFFF)
                sb.append("html, body { background-color: ").append(bgc).append(" !important; }\n")
                sb.append("body, div, p, span { color: ").append(fgc).append(" !important; }\n")
                sb.append("h1, h2, h3, h4, h5, h6, hr { background-color: ").append(bgc)
                    .append(" !important; color: ").append(fgc).append(" !important; }\n")
            }
            // pre/code 换行包裹（安卓 customCSS2 同款）
            sb.append("code, pre, pre>* { white-space: pre-wrap; font-size: 0.8em; }\n")
            return sb.toString()
        }
}

/** 存 %LOCALAPPDATA%\HowRead\reader.json。 */
object ReaderPrefsStore {
    private val file: File get() = File(AppPaths.dataDir, "reader.json")

    @Volatile
    private var cached: ReaderPrefs? = null

    @Synchronized
    fun load(): ReaderPrefs {
        cached?.let { return it }
        val p = try {
            val j = JSONObject(file.readText(Charsets.UTF_8))
            val hadIndent = j.has("textIndent") // 旧档案（v0.25 前）迁移标记
            var rp = ReaderPrefs(
                fontPath = j.optString("fontPath", ""),
                lineHeight = j.optInt("lineHeight", 180).coerceIn(100, 250),
                marginTop = j.optInt("marginTop", 4).coerceIn(0, 60),
                marginRight = j.optInt("marginRight", 10).coerceIn(0, 60),
                marginBottom = j.optInt("marginBottom", 4).coerceIn(0, 60),
                marginLeft = j.optInt("marginLeft", 10).coerceIn(0, 60),
                align = listOf("left", "justify", "right", "center").firstOrNull { it == j.optString("align", "justify") } ?: "justify",
                family = j.optString("family", "").replace("\"", "").replace("\n", " ").trim().take(60),
                useDocumentCss = j.optBoolean("useDocumentCss", true),
                fontScale = j.optInt("fontScale", 0).let { if (it == 0) 0 else it.coerceIn(60, 220) },
                textIndent = j.optInt("textIndent", 20).coerceIn(0, 50),
                paraSpacing = j.optInt("paraSpacing", 1).coerceIn(0, 20),
                maxChars = j.optInt("maxChars", 40).let { if (it == 0) 0 else it.coerceIn(24, 120) },
                readTheme = j.optString("readTheme", "day").let { tp -> if (tp in ReadThemes.IDS) tp else "day" },
            )
            // 旧档案且行距/对齐仍是旧默认（从未个性化）→ 升级到 v0.25 新默认
            if (!hadIndent) {
                if (rp.lineHeight == 150) rp = rp.copy(lineHeight = 180)
                if (rp.align == "left") rp = rp.copy(align = "justify")
            }
            // v0.29.0 一次性迁移：旧档案无标记 → 版心改全宽自适应（0，随窗口铺满）；保存后带标记不再迁移
            if (!j.has("vwAdaptive")) rp = rp.copy(maxChars = 0)
            rp = rp.copy(readTexture = j.optString("readTexture", "none").let { if (it in ReadTextures.IDS) it else "none" })
            rp
        } catch (_: Exception) {
            ReaderPrefs()
        }
        cached = p
        return p
    }

    @Synchronized
    fun save(p: ReaderPrefs) {
        val j = JSONObject()
            .put("fontPath", p.fontPath)
            .put("lineHeight", p.lineHeight)
            .put("marginTop", p.marginTop)
            .put("marginRight", p.marginRight)
            .put("marginBottom", p.marginBottom)
            .put("marginLeft", p.marginLeft)
            .put("align", p.align)
            .put("family", p.family)
            .put("useDocumentCss", p.useDocumentCss)
            .put("fontScale", p.fontScale)
            .put("textIndent", p.textIndent)
            .put("paraSpacing", p.paraSpacing)
            .put("maxChars", p.maxChars)
            .put("readTheme", p.readTheme)
            .put("readTexture", p.readTexture)
            .put("vwAdaptive", true)
        file.writeText(j.toString(), Charsets.UTF_8)
        cached = p
    }

    @Synchronized
    fun invalidate() { cached = null }

    /** 用户字体目录（dataDir/fonts；引擎 hr_user_fonts 加载器同一目录，v0.23.0 起生效）。 */
    fun fontsDir(): File = File(AppPaths.dataDir, "fonts")

    /** 目录内可用字体（文件名去扩展名 = font-family 名）。 */
    fun listUserFonts(): List<String> = runCatching {
        val d = fontsDir()
        if (!d.isDirectory) emptyList()
        else d.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "ttc") }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
    }.getOrDefault(emptyList())

    /** 预留：导入自定义字体到 dataDir/fonts。 */
    @Synchronized
    fun importFont(src: File): File? = try {
        val dir = File(AppPaths.dataDir, "fonts")
        dir.mkdirs()
        val dst = File(dir, src.name)
        src.copyTo(dst, overwrite = true)
        dst
    } catch (_: Exception) {
        null
    }
}
