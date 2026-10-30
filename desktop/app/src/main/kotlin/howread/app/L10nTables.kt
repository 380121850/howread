package howread.app

import org.json.JSONObject

/** 45 语界面译文表。数据=资源 resources/i18n/tables.json（生成器产出：安卓 values-* 机械映射 + 手写高频补充）。勿手改。 */
object L10nTables {
    private val TBL: Map<String, Map<String, String>> by lazy {
        runCatching {
            val ins = L10nTables::class.java.getResourceAsStream("/i18n/tables.json") ?: return@runCatching emptyMap()
            val root = JSONObject(ins.use { it.readBytes().toString(Charsets.UTF_8) })
            root.keys().asSequence().associateWith { lg ->
                val o = root.getJSONObject(lg)
                o.keys().asSequence().associateWith { k -> o.getString(k) }
            }
        }.getOrDefault(emptyMap())
    }

    fun t(code: String): Map<String, String>? = TBL[code]

    val LANGS: List<Pair<String, String>> = listOf(
        "zh" to "中文（简体）",
        "zh-rTW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "ru" to "Русский",
        "pt" to "Português",
        "it" to "Italiano",
        "ar" to "العربية",
        "he" to "עברית",
        "hi" to "हिन्दी",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "fa" to "فارسی",
        "uk" to "Українська",
        "pl" to "Polski",
        "cs" to "Čeština",
        "sk" to "Slovenčina",
        "nl" to "Nederlands",
        "el" to "Ελληνικά",
        "tr" to "Türkçe",
        "sv" to "Svenska",
        "no" to "Norsk",
        "fi" to "Suomi",
        "hu" to "Magyar",
        "ro" to "Română",
        "bg" to "Български",
        "ca" to "Català",
        "gl" to "Galego",
        "eu" to "Euskara",
        "ga" to "Gaeilge",
        "lt" to "Lietuvių",
        "kk" to "Қазақ тілі",
        "ml" to "മലയാളം",
        "ta" to "தமிழ்",
        "la" to "Latina",
        "sw" to "Kiswahili",
        "be" to "Беларуская",
        "sc" to "Sardu",
    )
}
