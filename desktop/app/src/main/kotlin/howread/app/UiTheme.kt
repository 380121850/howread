package howread.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import howread.core.AppPaths
import howread.core.TestLog
import java.io.File

/** UI 配色槽位（亮/暗双主题；全部界面统一从 UiTheme.p 取色，切换即时生效）。 */
data class UiPalette(
    val bg: Color, val panel: Color, val bar: Color, val card: Color, val rowActive: Color,
    val textPrimary: Color, val textSecondary: Color, val textTertiary: Color,
    val textFaint: Color, val textFaintest: Color,
    val accent: Color, val accentTeal: Color, val danger: Color, val warn: Color,
    val green: Color, val gold: Color,
    val isLight: Boolean,
)

/** 主题偏好持久化（dataDir/ui.json，缺省暗色）。 */
object ThemeStore {
    private val file: File get() = File(AppPaths.dataDir, "ui.json")
    fun isDark(): Boolean = runCatching { !file.readText().contains("\"light\"") }.getOrDefault(true)
    fun save(dark: Boolean) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (dark) "{\"theme\":\"dark\"}" else "{\"theme\":\"light\"}")
        }
    }
}

object UiTheme {
    val DARK = UiPalette(
        bg = Color(0xFF171A21), panel = Color(0xFF1D2129), bar = Color(0xFF20242B), card = Color(0xFF222734),
        rowActive = Color(0xFF2A3140),
        textPrimary = Color(0xFFE8EAF0), textSecondary = Color(0xFFC2CAD8), textTertiary = Color(0xFF8A93A6),
        textFaint = Color(0xFF6B7385), textFaintest = Color(0xFF566070),
        accent = Color(0xFF8AB4F8), accentTeal = Color(0xFF7DC9C4), danger = Color(0xFF855252), warn = Color(0xFFC98B7D),
        green = Color(0xFF7DC98F), gold = Color(0xFFFFC53D),
        isLight = false,
    )
    val LIGHT = UiPalette(
        bg = Color(0xFFF3F5F9), panel = Color(0xFFFFFFFF), bar = Color(0xFFE4E9F2), card = Color(0xFFE9EEF6),
        rowActive = Color(0xFFDCE5F7),
        textPrimary = Color(0xFF1B2028), textSecondary = Color(0xFF3B4353), textTertiary = Color(0xFF5D6678),
        textFaint = Color(0xFF7C8699), textFaintest = Color(0xFF9AA4B6),
        accent = Color(0xFF2E6BD6), accentTeal = Color(0xFF1F837C), danger = Color(0xFFB0524B), warn = Color(0xFFA6653F),
        green = Color(0xFF2E8B57), gold = Color(0xFFD99A00),
        isLight = true,
    )

    /** 当前是否暗色（启动读 ui.json；切换即时生效并持久化）。 */
    var dark by mutableStateOf(ThemeStore.isDark())
        private set

    /** 主题颜色覆盖（v0.40.0 偏好"主题颜色"色块行；0 = 默认随亮/暗主题）。 */
    var accentOverride by mutableStateOf(Color(0))
        private set

    /** 着重色覆盖（v0.41.0 偏好"着重色"色块行；0 = 默认）。 */
    var tealOverride by mutableStateOf(Color(0))
        private set

    val p: UiPalette
        get() {
            val base = if (dark) DARK else LIGHT
            return base.copy(
                accent = if (accentOverride == Color(0)) base.accent else accentOverride,
                accentTeal = if (tealOverride == Color(0)) base.accentTeal else tealOverride,
            )
        }

    fun applyDark(d: Boolean) {
        if (dark == d) return
        dark = d
        ThemeStore.save(d)
    }

    /** 设置主题颜色（argb=0 恢复默认；持久化 lib.properties accentColor）。 */
    fun applyAccent(argb: Int) {
        accentOverride = if (argb == 0) Color(0) else Color(argb)
        LibPrefs.put("accentColor", if (argb == 0) "" else "%08X".format(argb))
        TestLog.evt("pref_accent", "%08X".format(argb))
    }

    /** 设置着重色（argb=0 恢复默认；持久化 lib.properties accentTealColor）。 */
    fun applyAccentTeal(argb: Int) {
        tealOverride = if (argb == 0) Color(0) else Color(argb)
        LibPrefs.put("accentTealColor", if (argb == 0) "" else "%08X".format(argb))
        TestLog.evt("pref_accent_teal", "%08X".format(argb))
    }

    init {
        val s = LibPrefs.str("accentColor", "")
        if (s.isNotEmpty()) runCatching { accentOverride = Color(s.toLong(16).toInt()) }
        val t = LibPrefs.str("accentTealColor", "")
        if (t.isNotEmpty()) runCatching { tealOverride = Color(t.toLong(16).toInt()) }
    }
}
