package howread.core.reader

/**
 * 阅读主题（底色/字色成对预设，对齐安卓 READ_COLORS；v0.26.0）。
 * reflowable 文档经 user CSS 注入底色/字色；固定版式（PDF）走渲染像素后处理。
 */
data class ReadTheme(
    val id: String,
    val label: String,
    val labelEn: String,
    val bg: Int,
    val text: Int,
    val dark: Boolean,
)

object ReadThemes {
    val ALL = listOf(
        ReadTheme("day", "白", "Light", 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), false),
        ReadTheme("milk", "米黄", "Milk", 0xFFF5EFE0.toInt(), 0xFF3A3A3A.toInt(), false),
        ReadTheme("parchment", "羊皮", "Parchment", 0xFFF5E6C8.toInt(), 0xFF5B4636.toInt(), false),
        ReadTheme("gray", "深灰", "Slate", 0xFF1E1E1E.toInt(), 0xFFC8C8C8.toInt(), true),
        ReadTheme("night", "夜间", "Night", 0xFF000000.toInt(), 0xFF8CFFB5.toInt(), true),
    )

    val IDS: Set<String> = ALL.map { it.id }.toSet()

    fun byId(id: String): ReadTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
