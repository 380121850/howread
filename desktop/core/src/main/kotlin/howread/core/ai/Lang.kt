package howread.core.ai

/** 目标语言小工具（安卓 LanguageDetector 的桌面简化版：CJK 比例判定）。 */
object Lang {
    const val ZH = "zh"
    const val EN = "en"

    private fun isCjk(c: Char): Boolean {
        val k = c.code
        return k in 0x4E00..0x9FFF || k in 0x3000..0x303F || k in 0xFF00..0xFFEF
    }

    /** 文本是否以中日文为主（非空白字符中 CJK 占比 ≥ 30%）。 */
    fun mostlyCjk(s: String): Boolean {
        val t = s.filterNot { it.isWhitespace() }
        if (t.length < 4) return false
        return t.count { isCjk(it) } * 100 / t.length >= 30
    }

    /** 自动方向：中文书 → 译英文；外文书 → 译中文。 */
    fun autoFor(sample: String): Pair<String, String> =
        if (mostlyCjk(sample)) ZH to EN else EN to ZH

    /** 提示词里的目标语言名（与安卓 AiTranslator.targetLangName 同款）。 */
    fun targetName(tgt: String): String = if (tgt == ZH) "中文" else "英文"
}
