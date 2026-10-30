package howread.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import howread.core.AppPaths
import java.io.File

/**
 * 界面语言（45 语；译文表 L10nTables 由安卓 values-* 资源机械映射 + 手写高频补充生成）。
 * s(zh, en) 在组合内读取 lang 状态，切换语言立即全局重组。
 * 非 zh/en 按中文原文查表，未命中回退 en（zh-rTW 未命中回退中文）。存 dataDir/lang.json。
 */
object L10n {
    var lang by mutableStateOf(load())

    fun isEn(): Boolean = lang == "en"

    fun applyLang(l: String) {
        if (lang == l) return
        lang = l
        runCatching { File(AppPaths.dataDir, "lang.json").writeText("{\"lang\":\"$l\"}", Charsets.UTF_8) }
    }

    private fun load(): String = runCatching {
        Regex("\"lang\":\"([A-Za-z-]+)\"").find(File(AppPaths.dataDir, "lang.json").readText(Charsets.UTF_8))
            ?.groupValues?.get(1) ?: "zh"
    }.getOrDefault("zh")

    fun s(zh: String, en: String): String = when {
        lang == "zh" -> zh
        lang == "en" -> en
        else -> L10nTables.t(lang)?.get(zh) ?: if (lang.startsWith("zh")) zh else en
    }
}
