package howread.core.reader

/**
 * 阅读背景质感（v0.29.0）：纸/布纹理图（app 资源 howread/tex/<id>.jpg）乘色叠加到阅读区，
 * 仅浅色主题（!dark）生效；none = 纯色（旧行为）。纯显示层，不触发文档重开/重排。
 */
data class ReadTexture(val id: String, val label: String, val labelEn: String)

object ReadTextures {
    val NONE = ReadTexture("none", "无", "None")
    val ALL = listOf(
        NONE,
        ReadTexture("paper", "牛皮纸", "Kraft"),
        ReadTexture("fiber", "米色纤维", "Fiber"),
        ReadTexture("linen", "亚麻布", "Linen"),
        ReadTexture("parchment", "羊皮纸", "Parchment"),
        ReadTexture("canvas", "帆布", "Canvas"),
    )
    val IDS: Set<String> = ALL.map { it.id }.toSet()
    fun byId(id: String): ReadTexture = ALL.firstOrNull { it.id == id } ?: NONE
}
