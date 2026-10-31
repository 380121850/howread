@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package howread.app

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import howread.core.AppInfo
import howread.core.AppPaths
import howread.core.TestLog
import howread.core.data.Db
import howread.core.model.RemoteSource
import howread.core.library.Scanner
import howread.core.reader.ReaderPrefsStore
import howread.core.tts.TtsPrefs
import howread.core.tts.TtsPrefsStore
import howread.core.tts.TtsSpeaker
import howread.core.sync.SyncEngine
import howread.core.sync.SyncPrefs
import howread.core.Backup
import howread.core.FileAssoc
import howread.core.ai.AiConfigStore
import howread.core.dict.Dicts
import howread.core.remote.RemoteClients
import java.io.File
import javax.swing.JFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * 偏好页（v0.39.0 1:1 对齐安卓偏好页：fragment_preferences.xml + P30 真机实测行序）：
 * 标题行（偏好 + 退出程序）→ 配置文件（字母头像+当前配置名+齿轮→配置文件弹窗；恢复默认配置=出厂复位确认）→
 * ■书库设置色条：格式配置/书库设置/书库显示配置/书籍封面配置/阅读配置（展开行，就地展开）→
 * ■常规设置色条：调试日志(展开)/屏幕/语言/WebDAV 同步(Pro)/AI 大模型(Pro)/在线阅读(Pro)/缓存管理（键值行，右值弹对话框）/
 * 备份配置/文件关联配置(展开) → ■UI配置色条：主题配置/标签栏配置(展开)/软件说明(行→对话框)。
 * 阅读配置=安卓同款纯 10 行（字体/朗读/词典等桌面自有内容已按用户裁定移除）；屏幕方向为移动概念桌面省略。
 */
@Composable
fun PrefsScreen(appState: AppState, window: ComposeWindow) {
    var dirs by remember { mutableStateOf(Db.listScanDirs()) }
    var exitConfirm by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var restoreMsg by remember { mutableStateOf("") }
    var ddPicking by remember { mutableStateOf(false) }
    var ddMsg by remember { mutableStateOf("") }
    var langDlg by remember { mutableStateOf(false) }
    var screenDlg by remember { mutableStateOf(false) }
    var syncDlg by remember { mutableStateOf(false) }
    var onlineDlg by remember { mutableStateOf(false) }
    var cacheDlg by remember { mutableStateOf(false) }
    var colDlg by remember { mutableStateOf(false) }
    var singleTapDlg by remember { mutableStateOf(false) }
    var widgetRecentDlg by remember { mutableStateOf(false) }
    var widgetLayoutDlg by remember { mutableStateOf(false) }
    var widgetCountDlg by remember { mutableStateOf(false) }
    // 首页“最近读书”小部件配置（显示/布局/书籍数）：桌面无小部件，值存 LibPrefs 备用（键名对齐安卓）
    var widgetRecent by remember { mutableStateOf(LibPrefs.str("widgetForRecent", "recent")) }
    var widgetLayout by remember { mutableStateOf(LibPrefs.str("widgetLayout", "list")) }
    var widgetCount by remember { mutableStateOf(LibPrefs.str("widgetItemsCount", "4")) }
    var migPicking by remember { mutableStateOf(false) }
    var themeDlg by remember { mutableStateOf(false) }
    var fontSizeDlg by remember { mutableStateOf(false) }
    var lockDlg by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var profileDlg by remember { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<Pair<String, File>?>(null) }
    var profileMsg by remember { mutableStateOf("") }

    val maximized = window.extendedState and JFrame.MAXIMIZED_BOTH != 0
    val webdavServers = remember { Db.listRemoteSources().filter { it.protocol.equals("webdav", ignoreCase = true) } }
    val aiCfg = remember { AiConfigStore.load() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ==================== 标题行：偏好 + 退出程序（安卓同款） ====================
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.s("偏好", "Preferences"), color = UiTheme.p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                L10n.s("退出程序", "Exit"), color = UiTheme.p.accent, fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { exitConfirm = true; TestLog.evt("prefs_exit_click") }.padding(6.dp),
            )
        }

        // ==================== 配置文件（v0.55 对齐安卓：粗体标题 + 字母头像 + 当前配置名 + 齿轮→弹窗；第二行恢复默认配置） ====================
        val curProfile = remember { AppProfiles.currentName() }
        // 行 1：标签 + 字母头像 + 当前配置名（居中）+ 齿轮（安卓 fragment_preferences profile 行同款）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(L10n.s("配置文件", "Profile"), color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(26.dp).clip(CircleShape).background(UiTheme.p.accent), contentAlignment = Alignment.Center) {
                Text(if (curProfile.isEmpty()) "?" else curProfile.take(1).uppercase(),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(curProfile, color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { profileDlg = true; TestLog.evt("prefs_profile_open") })
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Settings, contentDescription = L10n.s("配置文件设置", "Profile settings"),
                tint = UiTheme.p.textTertiary, modifier = Modifier.size(18.dp)
                    .clickable { profileDlg = true; TestLog.evt("prefs_profile_gear") })
        }
        // 行 2：恢复默认配置 | 更改数据目录 | 数据目录路径（点击=打开目录）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                L10n.s("恢复默认配置", "Restore default configuration"), color = UiTheme.p.accent, fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { restoreConfirm = true }.padding(vertical = 2.dp),
            )
            Spacer(Modifier.width(18.dp))
            Text(
                L10n.s("更改数据目录", "Change data folder"), color = UiTheme.p.accent, fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { ddPicking = true }.padding(vertical = 2.dp),
            )
            Spacer(Modifier.width(18.dp))
            Text(AppPaths.dataDir.absolutePath, color = UiTheme.p.textSecondary, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable {
                    runCatching { java.awt.Desktop.getDesktop().open(AppPaths.dataDir) }
                    TestLog.evt("datadir_open")
                })
        }
        if (ddMsg.isNotEmpty()) Text(ddMsg, color = UiTheme.p.textSecondary, fontSize = 11.sp)
        if (restoreMsg.isNotEmpty()) Text(restoreMsg, color = UiTheme.p.textSecondary, fontSize = 11.sp)
        if (profileMsg.isNotEmpty()) Text(profileMsg, color = UiTheme.p.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))

        // ==================== ■ 书库设置（色条分组，安卓 bg_search_second 同款） ====================
        SectionBar(L10n.s("书库设置", "Library settings"))
        // ---- 格式配置（安卓 12 组勾选行+右侧计数） ----
        ExpandRow(L10n.s("格式配置", "Formats")) {
            Text(L10n.s("勾选计入书库的电子书格式（取消后该格式不再扫描入库，改动后自动重新扫描）。",
                    "Formats counted into the library (unchecked ones are skipped)."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            var cur by remember { mutableStateOf(ScanPrefsUi.enabledSet()) }
            // v0.46.0：桌面并排铺开（3 个一行）；组内勾选只作用于该组受支持的扩展名
            //（doc/docx/djvu 全支持后全部可勾；分组勾选语义=全组受支持扩展名一起切换）
            for (row in FORMAT_GROUPS.chunked(3)) {
                Row(Modifier.fillMaxWidth()) {
                    for ((label, exts) in row) {
                        val supExts = exts.filter { it in Scanner.BOOK_EXTS }
                        val supported = supExts.isNotEmpty()
                        val on = supported && supExts.all { it in cur }
                        val count = appState.library.count { it.path.substringAfterLast('.', "").lowercase() in exts }
                        Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, enabled = supported, onCheckedChange = { v ->
                                val next = (if (v) cur + supExts else cur - supExts.toSet()).filter { it in Scanner.BOOK_EXTS }.toSet()
                                cur = next
                                ScanPrefsUi.save(next)
                                TestLog.evt("scan_exts", next.joinToString(","))
                                appState.rescan()
                            })
                            Text(
                                (if (supported) label else label + L10n.s("（桌面版暂不支持）", " (not on desktop)")) +
                                    (if (supported) " · $count" else ""),
                                color = if (supported) UiTheme.p.textPrimary else UiTheme.p.textFaintest,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        // ---- 书库设置（更多）：安卓同款 10 项（P30 实测顺序；7 真开关 + 3 桌面无底层项禁用） ----
        ExpandRow(L10n.s("书库设置", "Library settings")) {
            // v0.55 对齐安卓 populateLibrarySettings：纯勾选框列表（无立即扫描按钮、无灰字说明）
            var scanLaunch by remember { mutableStateOf(LibPrefs.bool("scanAtLaunch", true)) }
            CheckRow(L10n.s("发布会上扫描即可查看新书。", "Scan for new books at launch"), scanLaunch) { v ->
                scanLaunch = v; LibPrefs.put("scanAtLaunch", v); TestLog.evt("pref_scan_launch", "$v")
            }
            var surname by remember { mutableStateOf(LibPrefs.bool("authorSurnameFirst", false)) }
            CheckRow(L10n.s("作者姓氏在前", "Author surname first"), surname) { v ->
                surname = v; LibPrefs.put("authorSurnameFirst", v); TestLog.evt("pref_surname_first", "$v")
            }
            var nomedia by remember { mutableStateOf(LibPrefs.bool("ignoreNomedia", true)) }
            CheckRow(L10n.s("忽略包含.nomedia文件的文件夹", "Ignore folders with .nomedia"), nomedia) { v ->
                nomedia = v; LibPrefs.put("ignoreNomedia", v); TestLog.evt("pref_nomedia", "$v")
            }
            var pdfMeta by remember { mutableStateOf(LibPrefs.bool("usePdfMeta", true)) }
            CheckRow(L10n.s("显示PDF元数据中的作者和书名", "Use author/title from PDF metadata"), pdfMeta) { v ->
                pdfMeta = v; LibPrefs.put("usePdfMeta", v); TestLog.evt("pref_pdf_meta", "$v")
            }
            var showFn by remember { mutableStateOf(LibPrefs.bool("showFileName", true)) }
            CheckRow(L10n.s("显示文件名而非元数据", "Show file names instead of metadata"), showFn) { v ->
                showFn = v; LibPrefs.put("showFileName", v); TestLog.evt("pref_show_filename", "$v")
            }
            var calibre by remember { mutableStateOf(LibPrefs.bool("useCalibreOpf", true)) }
            CheckRow(L10n.s("使用Calibre metadata.opf", "Use Calibre metadata.opf"), calibre) { v ->
                calibre = v; LibPrefs.put("useCalibreOpf", v); TestLog.evt("pref_calibre_opf", "$v")
            }
            var showDesc by remember { mutableStateOf(LibPrefs.bool("showDescription", true)) }
            CheckRow(L10n.s("显示书籍描述", "Show book description"), showDesc) { v ->
                showDesc = v; LibPrefs.put("showDescription", v); TestLog.evt("pref_show_desc", "$v")
            }
            var hideRead by remember { mutableStateOf(LibPrefs.bool("hideReadBooks", false)) }
            CheckRow(L10n.s("隐藏读完的书", "Hide finished books"), hideRead) { v ->
                hideRead = v; LibPrefs.put("hideReadBooks", v); TestLog.evt("pref_hide_read", "$v")
            }
            var seriesNum by remember { mutableStateOf(LibPrefs.bool("seriesNumInTitle", true)) }
            CheckRow(L10n.s("在标题中显示丛书号", "Show series number in title"), seriesNum) { v ->
                seriesNum = v; LibPrefs.put("seriesNumInTitle", v); TestLog.evt("pref_series_num", "$v")
            }
            var autoAdd by remember { mutableStateOf(LibPrefs.bool("openAutoAdd", true)) }
            CheckRow(L10n.s("其他应用打开的书籍自动加入书库", "Auto-add externally opened books"), autoAdd) { v ->
                autoAdd = v; LibPrefs.put("openAutoAdd", v); TestLog.evt("pref_open_autoadd", "$v")
            }
        }
        // ---- 书库显示配置（安卓行序：列表滑杆/网格滑杆/列数+重置/显示封面；默认视图/排序为桌面特有置后） ----
        ExpandRow(L10n.s("书库显示配置", "Library display")) {
            // v0.56 对齐安卓 libraryDisplayConfigContainer：列表/网格滑杆(带−/+)、列数+重置同行、
            // 显示书籍封面、显示/布局/书籍数（安卓首页"最近读书"小部件的 内容/布局/数量；桌面无小部件，按用户裁定做成可配置存 LibPrefs）
            SeekRow(L10n.s("列表", "List"), LibPrefs.str("coverListDp", "34").toIntOrNull() ?: 34, 28..72, indent = CheckTextIndent) { v ->
                LibPrefs.put("coverListDp", v); TestLog.evt("pref_cover_list", "$v")
            }
            SeekRow(L10n.s("网格", "Grid"), LibPrefs.str("coverGridDp", "150").toIntOrNull() ?: 150, 110..280, indent = CheckTextIndent) { v ->
                LibPrefs.put("coverGridDp", v); TestLog.evt("pref_cover_grid", "$v")
            }
            Row(Modifier.fillMaxWidth().padding(start = CheckTextIndent).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.s("列数", "Columns"), color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (LibPrefs.str("gridColumnCount", "0") == "0") L10n.s("自适应", "Auto")
                    else LibPrefs.str("gridColumnCount", "0"),
                    color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { colDlg = true },
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    L10n.s("重置", "Reset"), color = UiTheme.p.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        LibPrefs.put("gridColumnCount", "0")
                        LibPrefs.put("coverListDp", "34")
                        LibPrefs.put("coverGridDp", "150")
                        TestLog.evt("pref_display_reset")
                    },
                )
            }
            var showCovers by remember { mutableStateOf(LibPrefs.bool("showCovers", true)) }
            CheckRow(L10n.s("显示书籍封面", "Show book covers"), showCovers) { v ->
                showCovers = v; LibPrefs.put("showCovers", v); TestLog.evt("pref_covers", "$v")
            }
            // 安卓原语义（PrefFragment2）：显示=最近/收藏、布局=列表/网格、书籍数=1..50（默认 4）
            KeyValRow(L10n.s("显示", "Show"), if (widgetRecent == "starred") L10n.s("收藏", "Starred") else L10n.s("最近", "Recent"),
                indent = CheckTextIndent) { widgetRecentDlg = true }
            KeyValRow(L10n.s("布局", "Layout"), if (widgetLayout == "grid") L10n.s("网格", "Grid") else L10n.s("列表", "List"),
                indent = CheckTextIndent) { widgetLayoutDlg = true }
            KeyValRow(L10n.s("书籍数", "Books count"), widgetCount, indent = CheckTextIndent) { widgetCountDlg = true }
        }
        // ---- 书籍封面配置（安卓同款 3 开关 + 缓存清理） ----
        ExpandRow(L10n.s("书籍封面配置", "Book covers")) {
            var c3d by remember { mutableStateOf(LibPrefs.bool("cover3d", true)) }
            CheckRow(L10n.s("立体封面", "Mock print book covers"), c3d) { v ->
                c3d = v; LibPrefs.put("cover3d", v); TestLog.evt("pref_cover_3d", "$v")
            }
            var ccrop by remember { mutableStateOf(LibPrefs.bool("cropCovers", true)) }
            CheckRow(L10n.s("平面封面（裁剪填满）", "Crop book covers"), ccrop) { v ->
                ccrop = v; LibPrefs.put("cropCovers", v); TestLog.evt("pref_cover_crop", "$v")
            }
            var cborder by remember { mutableStateOf(LibPrefs.bool("coverBorder", true)) }
            CheckRow(L10n.s("显示边框和阴影", "Borders and shadows"), cborder) { v ->
                cborder = v; LibPrefs.put("coverBorder", v); TestLog.evt("pref_cover_border", "$v")
            }
        }
        // ---- 阅读配置（安卓 10 项行序：单击/双击/密码/上次书/第1页/记住搜索/锁定/退出确认/无障碍/上下文菜单） ----
        ExpandRow(L10n.s("阅读配置", "Reading")) {
            // v0.56 对齐安卓阅读配置 10 行：单击(=开书默认模式)/双击(置灰)/密码(真开关+密码链接)/上次书/
            // 第1页/记住搜索/锁定/退出确认(真开关)/无障碍/上下文菜单；桌面自有附加块（字体/朗读/词典）已移除
            KeyValRow(L10n.s("阅读时单击", "Single tap while reading"),
                if (LibPrefs.defaultReaderModePage()) L10n.s("左右翻页", "Book mode") else L10n.s("上下翻页", "Scroll mode"),
                indent = CheckTextIndent) {
                singleTapDlg = true
            }
            KeyValRowGray(L10n.s("阅读时双击", "Double tap while reading"), L10n.s("缩放到合适大小", "Zoom to fit"),
                indent = CheckTextIndent)
            // 打开应用时需输入密码（真开关；勾选/取消/点「密码」都打开密码设置框，空密码保存=清除）
            val hasLock = LibPrefs.str("appLockPassword", "").isNotEmpty()
            Row(Modifier.fillMaxWidth().clickable { lockDlg = true }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = hasLock, onCheckedChange = { lockDlg = true })
                Text(L10n.s("打开应用时需输入密码", "Password on start"), color = UiTheme.p.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Text(L10n.s("密码", "Password"), color = UiTheme.p.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline)
            }
            var openLast by remember { mutableStateOf(LibPrefs.bool("openLastOnStart", false)) }
            CheckRow(L10n.s("启动时打开上次读的书", "Open the last book at launch"), openLast) { v ->
                openLast = v; LibPrefs.put("openLastOnStart", v); TestLog.evt("pref_open_last", "$v")
            }
            var onPage1 by remember { mutableStateOf(LibPrefs.bool("openOnPage1", false)) }
            CheckRow(L10n.s("打开书本第 1 页", "Open books on page 1"), onPage1) { v ->
                onPage1 = v; LibPrefs.put("openOnPage1", v); TestLog.evt("pref_open_page1", "$v")
            }
            var rememberQ by remember { mutableStateOf(LibPrefs.bool("rememberSearch", false)) }
            CheckRow(L10n.s("记住上次的搜索", "Restore last search"), rememberQ) { v ->
                rememberQ = v; LibPrefs.put("rememberSearch", v); TestLog.evt("pref_remember_search", "$v")
            }
            // 默认锁定书籍/无障碍/上下文菜单按用户裁定改为正常开关（键名/默认值对齐安卓 AppState）；
            // 后两项桌面暂无底层行为，仅存开关状态
            var lockDefault by remember { mutableStateOf(LibPrefs.bool("lockBooksByDefault", true)) }
            CheckRow(L10n.s("默认锁定书籍", "Lock books by default"), lockDefault) { v ->
                lockDefault = v; LibPrefs.put("lockBooksByDefault", v); TestLog.evt("pref_lock_default", "$v")
            }
            var exitAskPref by remember { mutableStateOf(LibPrefs.bool("exitConfirmReading", true)) }
            CheckRow(L10n.s("返回手势退出时需确认", "Confirm before closing a book"), exitAskPref) { v ->
                exitAskPref = v; LibPrefs.put("exitConfirmReading", v); TestLog.evt("pref_exit_confirm", "$v")
            }
            var accMode by remember { mutableStateOf(LibPrefs.bool("isEnableAccessibility", false)) }
            CheckRow(L10n.s("启用无障碍模式", "Accessibility mode"), accMode) { v ->
                accMode = v; LibPrefs.put("isEnableAccessibility", v); TestLog.evt("pref_accessibility", "$v")
            }
            var menuInteg by remember { mutableStateOf(LibPrefs.bool("isMenuIntegration", false)) }
            CheckRow(L10n.s("集成到全局上下文菜单", "Global context menu"), menuInteg) { v ->
                menuInteg = v; LibPrefs.put("isMenuIntegration", v); TestLog.evt("pref_menu_integration", "$v")
            }
        }

        // ==================== ■ 常规设置（色条） ====================
        SectionBar(L10n.s("常规设置", "General settings"))
        // ---- 调试日志（安卓同款三行：开关/导出/清空） ----
        ExpandRow(L10n.s("调试日志", "Debug log"), trailing = AppPaths.logsDir.absolutePath) {
            var dbg by remember { mutableStateOf(LibPrefs.bool("debugLog", true)) }
            CheckRow(L10n.s("记录调试事件日志（hrtest.log；默认关，问题排查时开启）。", "Write event log (for troubleshooting)."), dbg) { v ->
                dbg = v; LibPrefs.put("debugLog", v)
                TestLog.enabled = v
                TestLog.evt("pref_debuglog", "$v")
            }
            TextButton(onClick = {
                runCatching { java.awt.Desktop.getDesktop().open(AppPaths.logsDir) }
                TestLog.evt("logs_open")
            }) { Text(L10n.s("导出调试日志", "Export debug log"), fontSize = 12.sp) }
            TextButton(onClick = {
                val f = File(AppPaths.logsDir, "hrtest.log")
                val n = if (f.exists() && f.delete()) 1 else 0
                TestLog.evt("logs_clear", "n=$n")
            }) { Text(L10n.s("清空调试日志", "Clear debug log"), fontSize = 12.sp) }
        }
        // ---- 屏幕（值行 → 弹窗：正常/最大化） ----
        KeyValRow(L10n.s("屏幕", "Screen"), if (maximized) L10n.s("最大化", "Maximized") else L10n.s("正常", "Normal")) {
            screenDlg = true
        }
        // ---- 语言（值行 → 弹窗） ----
        KeyValRow(L10n.s("语言", "Language"),
            L10nTables.LANGS.firstOrNull { it.first == L10n.lang }?.second ?: "中文") { langDlg = true }
        // ---- WebDAV 同步 (Pro)（值行 → 对话框） ----
        KeyValRow(
            L10n.s("WebDAV 同步 (Pro)", "WebDAV sync (Pro)"),
            if (webdavServers.isEmpty()) L10n.s("未配置", "Not configured")
            else "http://" + webdavServers.first().host + ":" + webdavServers.first().port + "/",
        ) { syncDlg = true }
        // ---- AI 大模型 (Pro)（值行 → 配置窗口） ----
        KeyValRow(
            L10n.s("AI 大模型 (Pro)", "AI model (Pro)"),
            if (AiConfigStore.configured(aiCfg)) aiCfg.model else L10n.s("未配置", "Not configured"),
        ) { appState.showAiConfig = true; TestLog.evt("prefs_ai_open") }
        // ---- 在线阅读 (Pro)（值行 → 对话框） ----
        KeyValRow(
            L10n.s("在线阅读 (Pro)", "Online reading (Pro)"),
            if (LibPrefs.bool("onlineReading", true)) L10n.s("已开启", "On") else L10n.s("已关闭", "Off"),
        ) { onlineDlg = true }
        // ---- 缓存管理（值=占用 → 明细对话框，安卓同款） ----
        KeyValRow(L10n.s("缓存管理", "Cache management"), cacheSizeText()) { cacheDlg = true }
        // ---- 备份配置（v0.56 对齐安卓 backup_section：介绍+导入/导出/迁移+四路径行） ----
        ExpandRow(L10n.s("备份配置", "Backup")) {
            BackupBlock(onMigrate = { migPicking = true; TestLog.evt("prefs_migrate_click") })
            PathRow(L10n.s("配置文件", "Profile"), AppPaths.dataDir)
            PathRow(L10n.s("字体路径", "Fonts"), ReaderPrefsStore.fontsDir())
            PathRow(L10n.s("下载", "Downloads"), File(AppPaths.dataDir, "downloads"))
            PathRow(L10n.s("音频文件", "Audio files"), File(File(AppPaths.dataDir, "downloads"), "TTS"))
        }
        // ---- 文件关联配置（桌面特有，置于常规设置尾） ----
        ExpandRow(L10n.s("文件关联配置", "File association")) {
            if (!FileAssoc.isWindows()) {
                Text(L10n.s("文件关联仅 Windows 提供。", "Windows only."), color = UiTheme.p.textFaint, fontSize = 12.sp)
            } else {
                var assocMsg by remember { mutableStateOf("") }
                var sel by remember { mutableStateOf(FileAssoc.EXTS.toSet()) }
                Text(
                    L10n.s("勾选要关联的书籍格式，再点「建立关联」或「解除关联」（写入当前用户注册表，无需管理员）。",
                        "Tick the formats to change, then press Associate or Disassociate (per-user registry)."),
                    color = UiTheme.p.textFaint, fontSize = 12.sp,
                )
                // 格式勾选（当前支持格式全集，5 个一行）
                for (r in FileAssoc.EXTS.chunked(5)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        for (e in r) {
                            Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = e in sel, onCheckedChange = { c -> sel = if (c) sel + e else sel - e })
                                Text(".$e", color = UiTheme.p.textSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { assocMsg = FileAssoc.associate(sel); TestLog.evt("file_assoc", "associate n=" + sel.size) }) {
                        Text(L10n.s("建立关联", "Associate"))
                    }
                    OutlinedButton(onClick = { assocMsg = FileAssoc.disassociate(sel); TestLog.evt("file_assoc", "disassociate n=" + sel.size) }) {
                        Text(L10n.s("解除关联", "Disassociate"))
                    }
                }
                if (assocMsg.isNotEmpty()) Text(assocMsg, color = UiTheme.p.textSecondary, fontSize = 12.sp)
            }
        }

        // ==================== ■ UI 配置（色条） ====================
        SectionBar(L10n.s("UI配置", "UI settings"))
        // ---- 主题配置（安卓：主题值行+字体大小值行；主题颜色色块桌面主题内置） ----
        ExpandRow(L10n.s("主题配置", "Theme")) {
            KeyValRow(L10n.s("主题", "Theme"), if (UiTheme.dark) L10n.s("暗色", "Dark") else L10n.s("亮色", "Light")) {
                themeDlg = true
            }
            ColorRow(L10n.s("主题颜色", "Theme color"), UiTheme.accentOverride) { UiTheme.applyAccent(it) }
            ColorRow(L10n.s("着重色", "Accent"), UiTheme.tealOverride) { UiTheme.applyAccentTeal(it) }
            val fs = remember { ReaderPrefsStore.load().fontScale }
            KeyValRow(L10n.s("字体大小", "Font size"), if (fs == 0) L10n.s("自动", "Auto") else "$fs%") {
                fontSizeDlg = true
            }
            Text(L10n.s("字体大小为阅读排版基准（对新打开的书籍生效）；阅读器内也可单独调整。主题颜色随亮/暗主题内置。",
                    "Font scale applies to newly opened books; per-book tweaks in the reader."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
        }
        // ---- 标签栏配置（安卓=底部标签栏位置/动画/顺序拖拽；桌面不支持配置→置灰展示当前状态） ----
        ExpandRow(L10n.s("标签栏配置", "Tabs")) {
            var sidebar by remember { mutableStateOf(LibPrefs.bool("showSidebar", true)) }
            DisabledRow(L10n.s("显示左侧标签栏（重启生效）。", "Show left sidebar (restart to apply)."), sidebar)
        }
        // ---- 软件说明（行 → 对话框；v0.55 对齐安卓：入口行显示"软件说明"，不再直接显示应用名与版本号） ----
        Row(
            Modifier.fillMaxWidth().clickable { aboutOpen = true; TestLog.evt("prefs_about_open") }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = UiTheme.p.textTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(L10n.s("软件说明", "About"), color = UiTheme.p.textPrimary, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
            Spacer(Modifier.weight(1f))
            Text(L10n.s("查看 ›", "View ›"), color = UiTheme.p.accent, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
    }

    // ==================== 对话框 ====================
    if (ddPicking) {
        DirPickerDialog(
            title = L10n.s("选择数据目录", "Choose data folder"),
            initial = AppPaths.dataDir.absolutePath,
            onPick = { p ->
                ddPicking = false
                ddMsg = if (AppPaths.changeDataDir(p))
                    L10n.s("已保存，重启程序后生效。", "Saved. Restart to take effect.")
                else L10n.s("写入失败——软件所在目录可能只读。", "Failed to write (program folder may be read-only).")
                TestLog.evt("datadir_change", p)
            },
            onDismiss = { ddPicking = false },
        )
    }
    if (exitConfirm) {
        AlertDialog(
            onDismissRequest = { exitConfirm = false },
            title = { Text(L10n.s("退出程序", "Exit")) },
            text = { Text(L10n.s("确定退出 HowReadPro 吗？", "Exit HowReadPro now?")) },
            confirmButton = {
                TextButton(onClick = { TestLog.evt("prefs_exit"); exitProcess(0) }) { Text(L10n.s("退出", "Exit"), color = UiTheme.p.danger) }
            },
            dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text(L10n.s("取消", "Cancel")) } },
        )
    }
    if (restoreConfirm) {
        AlertDialog(
            onDismissRequest = { restoreConfirm = false },
            title = { Text(L10n.s("恢复默认配置", "Restore default configuration")) },
            text = { Text(L10n.s("恢复默认配置：将清空全部配置、书籍索引、阅读统计与缓存并退出程序（书籍文件不受影响）。下次启动即恢复所有默认配置。确定继续？",
                    "Restore defaults: all settings, book index, reading stats and cache will be cleared, then the app exits (book files are not touched). Defaults apply on next start. Continue?")) },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    TestLog.evt("prefs_factory_reset")
                    AppProfiles.requestFactoryReset()
                }) { Text(L10n.s("确定", "OK"), color = UiTheme.p.danger) }
            },
            dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text(L10n.s("取消", "Cancel")) } },
        )
    }
    // 配置文件弹窗（v0.55 对齐安卓 AppProfile.showDialog：列表点选切换 + 输入名称新建并切换）
    if (profileDlg) {
        var newName by remember { mutableStateOf("") }
        var err by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { profileDlg = false },
            title = { Text(L10n.s("配置文件", "Profile")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!AppPaths.isWindows) {
                        Text(L10n.s("Linux 版数据目录固定（~/.howread），切换/新建仅在 Windows 生效。", "Linux data folder is fixed; profile switching works on Windows only."),
                            color = UiTheme.p.textFaintest, fontSize = 11.sp)
                    }
                    for ((name, dir) in AppProfiles.list()) {
                        val isCur = dir == AppPaths.dataDir
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !isCur) {
                                profileDlg = false
                                switchTarget = name to dir
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(UiTheme.p.accent), contentAlignment = Alignment.Center) {
                                Text(if (name.isEmpty()) "?" else name.take(1).uppercase(),
                                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal)
                            if (isCur) {
                                Spacer(Modifier.weight(1f))
                                Text(L10n.s("当前", "Current"), color = UiTheme.p.textFaintest, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it; err = "" }, singleLine = true,
                        label = { Text(L10n.s("新建配置文件", "New profile")) },
                        isError = err.isNotEmpty(),
                    )
                    if (err.isNotEmpty()) Text(err, color = UiTheme.p.danger, fontSize = 11.sp)
                    TextButton(onClick = {
                        val nm = newName.trim()
                        when {
                            !AppProfiles.validName(nm) ->
                                err = L10n.s("名称不能为空，且不能含 \\ / : * ? \" < > | 等字符", "Name must be non-empty without \\ / : * ? \" < > |")
                            AppProfiles.create(nm) == null ->
                                err = L10n.s("同名配置文件已存在", "A profile with this name already exists")
                            else -> {
                                AppProfiles.switchTo(File(AppProfiles.root(), nm))
                                profileMsg = L10n.s("已创建并切换到「$nm」——该配置文件使用独立书库与设置，重启程序后生效。", "Created & switched to \"$nm\" (independent library & settings). Restart to apply.")
                                TestLog.evt("profile_create", nm)
                                profileDlg = false
                            }
                        }
                    }) { Text(L10n.s("新建并切换", "Create & switch"), color = UiTheme.p.accent) }
                }
            },
            confirmButton = { TextButton(onClick = { profileDlg = false }) { Text(L10n.s("关闭", "Close")) } },
        )
    }
    if (switchTarget != null) {
        val swName = switchTarget!!.first
        val swDir = switchTarget!!.second
        AlertDialog(
            onDismissRequest = { switchTarget = null },
            title = { Text(L10n.s("切换配置文件", "Switch profile")) },
            text = { Text(L10n.s("您要切换配置文件吗？\n\n「$swName」使用独立的书库与设置；切换后重启程序生效。", "Switch profile?\n\n\"$swName\" has its own library and settings. Restart to apply.")) },
            confirmButton = {
                TextButton(onClick = {
                    switchTarget = null
                    if (!AppPaths.isWindows) {
                        profileMsg = L10n.s("Linux 版数据目录固定，暂不支持切换配置文件。", "Linux data folder is fixed; switching is not supported.")
                    } else if (AppProfiles.switchTo(swDir)) {
                        profileMsg = L10n.s("已切换到「$swName」，重启程序后生效。", "Switched to \"$swName\". Restart to apply.")
                        TestLog.evt("profile_switch", swName)
                    } else {
                        profileMsg = L10n.s("写入失败——软件所在目录可能只读。", "Failed to write (program folder may be read-only).")
                    }
                }) { Text(L10n.s("确定", "OK"), color = UiTheme.p.accent) }
            },
            dismissButton = { TextButton(onClick = { switchTarget = null }) { Text(L10n.s("取消", "Cancel")) } },
        )
    }
    if (langDlg) {
        PickDialog(L10n.s("语言", "Language"), L10nTables.LANGS.map { it.second }, onDismiss = { langDlg = false }) { pick ->
            val idx = L10nTables.LANGS.indexOfFirst { it.second == pick }
            if (idx >= 0) L10n.applyLang(L10nTables.LANGS[idx].first)
            TestLog.evt("lang_change", pick)
        }
    }
    if (screenDlg) {
        PickDialog(L10n.s("屏幕", "Screen"),
            listOf(L10n.s("正常", "Normal"), L10n.s("最大化", "Maximized")), onDismiss = { screenDlg = false }) { pick ->
            val max = pick == L10n.s("最大化", "Maximized")
            window.extendedState = if (max) JFrame.MAXIMIZED_BOTH else JFrame.NORMAL
            TestLog.evt("pref_screen", if (max) "maximized" else "normal")
        }
    }
    if (lockDlg) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { lockDlg = false },
            title = { Text(L10n.s("应用密码锁", "App lock"), color = UiTheme.p.textPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L10n.s("设置后每次启动需输入密码（下次启动生效；置空保存=清除密码锁）。",
                        "A password will be required at each start (effective next launch; save empty to disable)."),
                        color = UiTheme.p.textTertiary, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(p1, { p1 = it }, singleLine = true,
                        label = { Text(L10n.s("新密码", "New password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    androidx.compose.material3.OutlinedTextField(p2, { p2 = it }, singleLine = true,
                        label = { Text(L10n.s("重复一遍", "Repeat password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (p1.isEmpty()) { LibPrefs.put("appLockPassword", ""); TestLog.evt("app_lock", "off") }
                    else if (p1 != p2) { TestLog.evt("app_lock", "mismatch") }
                    else {
                        val hh = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(p1.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                        LibPrefs.put("appLockPassword", hh); TestLog.evt("app_lock", "on")
                    }
                    lockDlg = false
                }) { Text(L10n.s("保存", "Save"), color = UiTheme.p.accent) }
            },
            dismissButton = { TextButton(onClick = { lockDlg = false }) { Text(L10n.s("取消", "Cancel"), color = UiTheme.p.textTertiary) } },
        )
    }
    if (syncDlg) {
        SimpleDialog(L10n.s("WebDAV 同步 (Pro)", "WebDAV sync (Pro)"), onDismiss = { syncDlg = false }, showClose = false) { WebDavBlock(onClose = { syncDlg = false }) }
    }
    if (onlineDlg) {
        SimpleDialog(L10n.s("在线阅读", "Online reading"), onDismiss = { onlineDlg = false }, showClose = false) {
            // v0.54 对齐安卓 RemoteCacheDialog（行序同款；键与安卓 app-State 同名，随字段映射互通）
            var onlineFirst by remember { mutableStateOf(LibPrefs.bool("remoteOnlineFirst", true)) }
            CheckRow(L10n.s("点击远程书在线阅读", "Tap remote book to read online"), onlineFirst) { v ->
                onlineFirst = v; LibPrefs.put("remoteOnlineFirst", v); TestLog.evt("pref_online_first", "$v")
            }
            var wifiOnly by remember { mutableStateOf(LibPrefs.bool("remoteWifiOnly", true)) }
            CheckRow(L10n.s("仅 WiFi 下预取与整本缓存", "Prefetch and whole-book cache on Wi-Fi only"), wifiOnly) { v ->
                wifiOnly = v; LibPrefs.put("remoteWifiOnly", v); TestLog.evt("pref_online_wifi", "$v")
            }
            var thrText by remember { mutableStateOf(LibPrefs.str("remoteWholeBookThresholdMB", "20")) }
            Text(L10n.s("小书整本缓存阈值 (MB，0 = 关闭)", "Whole-book cache threshold (MB, 0 = off)"),
                color = UiTheme.p.textSecondary, fontSize = 12.sp)
            CompactField(thrText, { v ->
                thrText = v
                v.toIntOrNull()?.let { n -> if (n in 0..4096) { LibPrefs.put("remoteWholeBookThresholdMB", n); TestLog.evt("pref_online_thr", "$n") } }
            }, hint = L10n.s("如 20；0 = 关闭", "e.g. 20; 0 = off"), modifier = Modifier.width(160.dp))
            var wB by remember { mutableStateOf(LibPrefs.str("remoteWindowBeforePct", "20")) }
            var wA by remember { mutableStateOf(LibPrefs.str("remoteWindowAfterPct", "30")) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.s("阅读位置前缓存范围（%）", "Window before (%)"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
                    CompactField(wB, { v -> wB = v; v.toIntOrNull()?.let { n -> if (n in 1..90) LibPrefs.put("remoteWindowBeforePct", n) } }, hint = "20")
                }
                Column(Modifier.weight(1f)) {
                    Text(L10n.s("阅读位置后缓存范围（%）", "Window after (%)"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
                    CompactField(wA, { v -> wA = v; v.toIntOrNull()?.let { n -> if (n in 1..90) LibPrefs.put("remoteWindowAfterPct", n) } }, hint = "30")
                }
            }
            var rC by remember { mutableStateOf(LibPrefs.str("remoteRetryCount", "3")) }
            var rI by remember { mutableStateOf(LibPrefs.str("remoteRetryIntervalMs", "100")) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(L10n.s("网络重试次数", "Network retries"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
                    CompactField(rC, { v -> rC = v; v.toIntOrNull()?.let { n -> if (n in 0..10) LibPrefs.put("remoteRetryCount", n) } }, hint = "3")
                }
                Column(Modifier.weight(1f)) {
                    Text(L10n.s("重试基础间隔（毫秒）", "Retry base interval (ms)"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
                    CompactField(rI, { v -> rI = v; v.toIntOrNull()?.let { n -> if (n in 0..30000) LibPrefs.put("remoteRetryIntervalMs", n) } }, hint = "100")
                }
            }
            Text(L10n.s("当前网络：稳定（后台整本缓存开启）", "Network: stable (background fill on)"),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            // 底部：关闭/保存（用户裁定：右下角；各项即时落盘，保存=显式提交全部字段并关闭）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onlineDlg = false }) { Text(L10n.s("关闭", "Close"), fontSize = 12.sp, color = UiTheme.p.textTertiary) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    LibPrefs.put("remoteOnlineFirst", onlineFirst)
                    LibPrefs.put("remoteWifiOnly", wifiOnly)
                    LibPrefs.put("remoteWholeBookThresholdMB", thrText.toIntOrNull()?.coerceIn(0, 4096) ?: 20)
                    LibPrefs.put("remoteWindowBeforePct", wB.toIntOrNull()?.coerceIn(1, 90) ?: 20)
                    LibPrefs.put("remoteWindowAfterPct", wA.toIntOrNull()?.coerceIn(1, 90) ?: 30)
                    LibPrefs.put("remoteRetryCount", rC.toIntOrNull()?.coerceIn(0, 10) ?: 3)
                    LibPrefs.put("remoteRetryIntervalMs", rI.toIntOrNull()?.coerceIn(0, 30000) ?: 100)
                    TestLog.evt("online_cfg_save")
                    onlineDlg = false
                }) { Text(L10n.s("保存", "Save")) }
            }
        }
    }
    if (cacheDlg) {
        var lines by remember { mutableStateOf(cacheLines()) }
        var total by remember { mutableStateOf(cacheSizeText()) }
        SimpleDialog(L10n.s("缓存管理", "Cache management"), onDismiss = { cacheDlg = false }, showClose = false) {
            for ((k, v) in lines) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, color = UiTheme.p.textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(humanSize(v), color = UiTheme.p.textTertiary, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L10n.s("合计", "Total"), color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(total, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // v0.54 缓存大小上限（安卓 remoteCacheMaxMB 同键同默认 500；0 = 不限，超限按最旧整目录回收）
            Text(L10n.s("缓存大小上限 (MB)", "Cache size limit (MB)"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
            var capText by remember { mutableStateOf(LibPrefs.str("remoteCacheMaxMB", "500")) }
            CompactField(capText, { v ->
                capText = v
                v.toLongOrNull()?.let { n -> if (n in 0..1048576L) LibPrefs.put("remoteCacheMaxMB", n) }
            }, hint = L10n.s("如 500；0 = 不限", "e.g. 500; 0 = no limit"), modifier = Modifier.width(160.dp))
            // 底部按钮行（用户裁定+附图：清空全部缓存 | 取消、保存上限；保存后关闭）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    var n = 0
                    for (f in AppPaths.cacheDir.listFiles() ?: emptyArray()) {
                        if (f.isDirectory) { n += f.walkTopDown().count { it.isFile }; runCatching { f.deleteRecursively() } }
                        else if (f.delete()) n++
                    }
                    lines = cacheLines(); total = cacheSizeText()
                    TestLog.evt("cache_clear", "n=$n")
                }) { Text(L10n.s("清空全部缓存", "Clear all caches"), fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { cacheDlg = false }) { Text(L10n.s("取消", "Cancel"), fontSize = 12.sp, color = UiTheme.p.textTertiary) }
                    Button(onClick = {
                        val n = capText.toLongOrNull()?.coerceIn(0, 1048576L) ?: 500L
                        LibPrefs.put("remoteCacheMaxMB", n)
                        howread.core.remote.BlockCacheStore.capMaxMB = n   // 即时生效，不等下本书
                        runCatching { howread.core.remote.BlockCacheStore.enforceCap(n) }
                        TestLog.evt("cache_cap", "$n")
                        cacheDlg = false
                    }) { Text(L10n.s("保存上限", "Save limit"), fontSize = 12.sp) }
                }
            }
        }
    }
    if (colDlg) {
        PickDialog(L10n.s("列数", "Columns"),
            listOf(L10n.s("自适应", "Auto"), "1", "2", "3", "4", "5", "6", "7", "8"), onDismiss = { colDlg = false }) { pick ->
            val v = if (pick == L10n.s("自适应", "Auto")) "0" else pick
            LibPrefs.put("gridColumnCount", v)
            TestLog.evt("pref_grid_columns", v)
        }
    }
    if (singleTapDlg) {
        PickDialog(L10n.s("阅读时单击", "Single tap while reading"),
            listOf(L10n.s("上下翻页", "Scroll mode"), L10n.s("左右翻页", "Book mode")),
            onDismiss = { singleTapDlg = false }) { pick ->
            val page = pick == L10n.s("左右翻页", "Book mode")
            LibPrefs.put("defaultReaderMode", if (page) "page" else "scroll")
            TestLog.evt("pref_readmode", if (page) "page" else "scroll")
        }
    }
    if (widgetRecentDlg) {
        PickDialog(L10n.s("显示", "Show"), listOf(L10n.s("最近", "Recent"), L10n.s("收藏", "Starred")),
            onDismiss = { widgetRecentDlg = false }) { pick ->
            widgetRecent = if (pick == L10n.s("收藏", "Starred")) "starred" else "recent"
            LibPrefs.put("widgetForRecent", widgetRecent)
            TestLog.evt("pref_widget_recent", widgetRecent)
        }
    }
    if (widgetLayoutDlg) {
        PickDialog(L10n.s("布局", "Layout"), listOf(L10n.s("列表", "List"), L10n.s("网格", "Grid")),
            onDismiss = { widgetLayoutDlg = false }) { pick ->
            widgetLayout = if (pick == L10n.s("网格", "Grid")) "grid" else "list"
            LibPrefs.put("widgetLayout", widgetLayout)
            TestLog.evt("pref_widget_layout", widgetLayout)
        }
    }
    if (widgetCountDlg) {
        PickDialog(L10n.s("书籍数", "Books count"), (1..50).map { it.toString() },
            onDismiss = { widgetCountDlg = false }) { pick ->
            widgetCount = pick
            LibPrefs.put("widgetItemsCount", pick)
            TestLog.evt("pref_widget_count", pick)
        }
    }
    if (migPicking) {
        DirPickerDialog(
            title = L10n.s("迁移数据目录", "Migrate data folder"),
            initial = AppPaths.dataDir.absolutePath,
            onPick = { p ->
                migPicking = false
                ddMsg = if (AppPaths.changeDataDir(p))
                    L10n.s("已保存，重启程序后生效。", "Saved. Restart to take effect.")
                else L10n.s("写入失败——软件所在目录可能只读。", "Failed to write (program folder may be read-only).")
                TestLog.evt("backup_migrate", p)
            },
            onDismiss = { migPicking = false },
        )
    }
    if (themeDlg) {
        PickDialog(L10n.s("主题", "Theme"),
            listOf(L10n.s("亮色", "Light"), L10n.s("暗色", "Dark")), onDismiss = { themeDlg = false }) { pick ->
            val dark = pick == L10n.s("暗色", "Dark")
            UiTheme.applyDark(dark)
            TestLog.evt("theme_change", if (dark) "dark" else "light")
        }
    }
    if (fontSizeDlg) {
        val opts = listOf(0, 70, 80, 90, 100, 110, 120, 140, 160, 180, 200)
        PickDialog(L10n.s("字体大小", "Font size"), opts.map { if (it == 0) L10n.s("自动", "Auto") else "$it%" },
            onDismiss = { fontSizeDlg = false }) { pick ->
            val v = if (pick == L10n.s("自动", "Auto")) 0 else pick.removeSuffix("%").toIntOrNull() ?: 100
            ReaderPrefsStore.save(ReaderPrefsStore.load().copy(fontScale = v))
            TestLog.evt("pref_fontscale", "$v")
        }
    }
    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}

/** 安卓 12 格式组 → 桌面支持的扩展名集合（不支持组勾选禁用）。 */
private val FORMAT_GROUPS: List<Pair<String, List<String>>> = listOf(
    "PDF" to listOf("pdf"),
    "DJVU" to listOf("djvu"),
    "FB2" to listOf("fb2"),
    "MOBI/AZW/PRC" to listOf("mobi", "azw", "azw3", "prc"),
    "EPUB" to listOf("epub"),
    "XPS" to listOf("xps"),
    "DOC/DOCX" to listOf("doc", "docx"),
    "RTF" to listOf("rtf"),
    "ODT" to listOf("odt"),
    "CBZ/CBR" to listOf("cbz", "cbr"),
    "ZIP" to listOf("zip"),
    "HTML/TXT" to listOf("html", "htm", "txt", "svg"),
)

/** 分组标题条（安卓 bg_search_second：满宽主题色底+白字加粗）。 */
@Composable
private fun SectionBar(title: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(UiTheme.p.accent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 紧凑下划线输入框（对话框用：替代 56dp 的 OutlinedTextField，行高约 26dp；hint=空态占位）。 */
@Composable
internal fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    visual: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = UiTheme.p.textPrimary, fontSize = 13.sp),
        cursorBrush = SolidColor(UiTheme.p.accent),
        visualTransformation = visual,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column {
                Box {
                    if (value.isEmpty()) Text(hint, color = UiTheme.p.textTertiary, fontSize = 13.sp, maxLines = 1)
                    inner()
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(UiTheme.p.textFaintest.copy(alpha = 0.45f)))
            }
        },
    )
}

/** 展开子项中非勾选行文本的统一缩进 = 勾选行 Checkbox 视觉框宽（桌面无 48dp 触摸目标强制，组件宽 20dp）。 */
private val CheckTextIndent = 20.dp

/** 键值行（安卓 ConfLineView：左粗体标题 + 右下划线值，整行可点）。indent 用于与勾选行文本列对齐。 */
@Composable
private fun KeyValRow(title: String, value: String, indent: Dp = 0.dp, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = indent).clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(190.dp))
        Text(value, color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 二级展开行（安卓：小图标+下划线标题，点开就地展开）。trailing=标题行右侧灰字（如路径）。 */
@Composable
private fun ExpandRow(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = UiTheme.p.textTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = UiTheme.p.textPrimary, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, color = UiTheme.p.textTertiary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
            Text(if (open) "▾" else "▸", color = UiTheme.p.textFaintest, fontSize = 12.sp)
        }
        if (open) {
            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }
}

/** 开关行（Checkbox + 文案，整行可点）。 */
@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, color = UiTheme.p.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/** 静态键值行（展示型信息：地址/账号/路径/策略，无点击）。 */
@Composable
private fun StaticRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(190.dp))
        Text(value, color = UiTheme.p.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 键值行（灰）：安卓有对应行但桌面无底层能力（桌面小部件配置/双击动作等），置灰展示不可点。 */
@Composable
private fun KeyValRowGray(title: String, value: String, indent: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(start = indent).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = UiTheme.p.textFaintest, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(190.dp))
        Text(value, color = UiTheme.p.textFaintest, fontSize = 13.sp)
    }
}

/** 禁用行（桌面无对应底层能力的安卓配置项：安卓同款禁用勾选框置灰不可点，保持 1:1 行序）。 */
@Composable
private fun DisabledRow(text: String, checked: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = false, onCheckedChange = null)
        Text(text, color = UiTheme.p.textFaintest, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

/** 滑杆行（安卓 CustomSeek 同款：标签+当前值+滑轨）。 */
@Composable
private fun SeekRow(label: String, init: Int, range: IntRange, indent: Dp = 0.dp, onCommit: (Int) -> Unit) {
    var v by remember { mutableStateOf(init.toFloat()) }
    // v0.56 对齐安卓 CustomSeek：标签+当前值+滑轨+−/+ 步进
    Row(Modifier.fillMaxWidth().padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
        Text("${v.toInt()}", color = UiTheme.p.textSecondary, fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onCommit(v.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("−", color = UiTheme.p.textSecondary, fontSize = 16.sp, modifier = Modifier
            .clickable(enabled = v.toInt() > range.first) { v -= 1f; onCommit(v.toInt()) }.padding(horizontal = 6.dp))
        Text("+", color = UiTheme.p.textSecondary, fontSize = 16.sp, modifier = Modifier
            .clickable(enabled = v.toInt() < range.last) { v += 1f; onCommit(v.toInt()) }.padding(horizontal = 6.dp))
    }
}

/** 路径行（安卓备份配置同款：左标签+右路径下划线，点击打开文件夹）。 */
@Composable
private fun PathRow(label: String, dir: File) {
    Row(
        Modifier.fillMaxWidth().clickable {
            runCatching {
                dir.mkdirs()
                java.awt.Desktop.getDesktop().open(dir)
            }
            TestLog.evt("prefs_path_open", label)
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = UiTheme.p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
        Text(dir.absolutePath, color = UiTheme.p.accent, fontSize = 11.sp, textDecoration = TextDecoration.Underline,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 颜色色块行（安卓 STYLE_COLORS/ACCENT_COLORS 同款：标签+预设色块横排，点击即时切换；首块=恢复默认）。 */
@Composable
private fun ColorRow(label: String, current: Color, onPick: (Int) -> Unit) {
    val colors = listOf(
        0x00000000, 0xFF2E6BD6.toInt(), 0xFF1F837C.toInt(), 0xFF7C4DCF.toInt(), 0xFFB0524B.toInt(),
        0xFFC97B2D.toInt(), 0xFF2E7D32.toInt(), 0xFF546E7A.toInt(),
    )
    // v0.56 对齐安卓主题配置：标签与色块同一行左右排列
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(190.dp))
        Spacer(Modifier.weight(1f))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
    ) {
        for (argb in colors) {
            val isDefault = argb == 0
            val selected = if (isDefault) current == Color(0) else current == Color(argb)
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (isDefault) UiTheme.p.bar else Color(argb))
                    .border(1.dp, if (selected) UiTheme.p.textPrimary else UiTheme.p.textFaintest.copy(alpha = 0.35f), CircleShape)
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (isDefault) Text(L10n.s("默认", "Auto"), color = UiTheme.p.textSecondary, fontSize = 8.sp)
                if (selected && !isDefault) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    }
    Text(L10n.s("首块恢复随亮/暗主题。", "Default restores the theme color."),
        color = UiTheme.p.textFaintest, fontSize = 10.sp)
}

/** 简单选择弹窗（安卓 PopupMenu 值行的桌面形态）：点选项回调后自动关闭。 */
@Composable
private fun PickDialog(title: String, options: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(300.dp).padding(vertical = 24.dp), color = UiTheme.p.panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp))
                for (o in options) {
                    Text(
                        o, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(o); onDismiss() }.padding(vertical = 10.dp, horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** 内容弹窗（WebDAV 同步/在线阅读/缓存管理对话框容器）。 */
@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, showClose: Boolean = true, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(560.dp).padding(vertical = 24.dp), color = UiTheme.p.panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                // 对话框内紧凑：禁 48dp 触摸目标（勾选框回 20dp，行高减半）
                CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                    content()
                }
                if (showClose) {
                    TextButton(onClick = onDismiss) { Text(L10n.s("关闭", "Close"), fontSize = 12.sp) }
                }
            }
        }
    }
}

private object ScanPrefsUi {
    fun enabledSet(): Set<String> = howread.core.library.ScanPrefs.configured() ?: howread.core.library.Scanner.BOOK_EXTS
    fun save(sel: Set<String>) = howread.core.library.ScanPrefs.setExts(sel.toList())
}

private fun cacheSizeText(): String {
    val b = (AppPaths.cacheDir.listFiles() ?: emptyArray()).sumOf { dirSize(it) }
    return humanSize(b)
}

private fun dirBytes(name: String): Long = dirSize(File(AppPaths.cacheDir, name))

/** 缓存明细（安卓缓存管理对话框同口径：在线阅读/转换/临时/封面/合计）。 */
private fun cacheLines(): List<Pair<String, Long>> = listOf(
    L10n.s("在线阅读缓存", "Online reading cache") to (dirBytes("remote") + dirBytes("remote-blocks")),
    L10n.s("转换缓存", "Convert cache") to (dirBytes("convert") + dirBytes("accels") + dirBytes("txt")),
    L10n.s("临时文件", "Temp files") to dirBytes("zip"),
    L10n.s("应用运行缓存", "App runtime cache") to runCacheSize(),
    L10n.s("封面缓存", "Cover cache") to dirBytes("covers"),
)

/** 应用运行缓存 = 缓存根目录散文件 + 各分类子目录之外的其余子目录（附图对齐安卓）。 */
private fun runCacheSize(): Long {
    val known = setOf("remote", "remote-blocks", "convert", "accels", "txt", "zip", "covers")
    var n = 0L
    for (f in AppPaths.cacheDir.listFiles() ?: emptyArray()) {
        if (f.isDirectory && f.name in known) continue
        n += dirSize(f)
    }
    return n
}

private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) }

private fun humanSize(b: Long): String =
    if (b >= 1024L * 1024) "%.2fMB".format(b / 1024f / 1024f) else "${b / 1024}KB"

/** 软件说明弹框（v0.54 对齐安卓 about_section：版本头条色底/描述/Pro 卡片/各行为 label 左 120dp + 值右）。 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(480.dp).padding(vertical = 24.dp), color = UiTheme.p.panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 版本头条（安卓 section6：主题色底白字满宽）+ 应用描述
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(UiTheme.p.accent)
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("HowReadPro: v" + AppInfo.VERSION, color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(L10n.s("一个专注于个人阅读体验的开源电子书阅读软件",
                        "An open-source ebook reader focused on personal reading experience"),
                    color = UiTheme.p.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                // Pro 卡片（安卓 proCardRoot：横行 + 激活态恢复/管理链接）
                AboutRow(L10n.s("高级功能", "Pro features"), L10n.s("Pro 已激活", "Pro unlocked"), accent = true)
                Text(L10n.s("解锁方式：本地 key · 购买时间： · 订单号后四位：",
                        "Unlock: local key · Purchased at: · Order tail:"),
                    color = UiTheme.p.textFaintest, fontSize = 10.sp, modifier = Modifier.padding(start = 120.dp))
                Row(Modifier.padding(start = 120.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(L10n.s("恢复购买", "Restore purchase"), color = UiTheme.p.textFaintest, fontSize = 12.sp)
                    Text(L10n.s("管理权益", "Manage entitlements"), color = UiTheme.p.textFaintest, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                AboutRow(L10n.s("开发者", "Developer"), "LeeStudio", bold = true)
                AboutRow(L10n.s("更新日志", "What is new"), "HowReadPro（好好读Pro） v" + AppInfo.VERSION)
                AboutRow(L10n.s("隐私政策", "Privacy policy"),
                    L10n.s("HowReadPro 隐私政策", "HowReadPro privacy policy"), url = "https://github.com/380121850/howread")
                AboutRow(L10n.s("开放源代码许可", "Open source licence (GNU GPL v3.0)"),
                    L10n.s("第三方库许可", "Licenses for libraries"), url = "https://github.com/380121850/howread")
                AboutRow(L10n.s("支持和问题反馈", "Support and feedback"),
                    L10n.s("HowReadPro 问题反馈", "HowReadPro issues"), url = "https://github.com/380121850/howread/issues")
                AboutRow(L10n.s("邮箱", "Email"), "380121850@163.com", url = "mailto:380121850@163.com")
                AboutRow(L10n.s("官网", "Website"), "HowReadPro", url = "https://howread.app")
                Text(L10n.s("This is a fork of Librera Reader", "This is a fork of Librera Reader"),
                    color = UiTheme.p.textFaintest, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(L10n.s("确定", "OK")) }
            }
        }
    }
}

/** 安卓 about_section 行：label 左 120dp + 值右（url 非空=下划线链接，点击系统浏览器）。 */
@Composable
private fun AboutRow(label: String, value: String, url: String = "", accent: Boolean = false, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = UiTheme.p.textFaint, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        val vm = if (url.isNotBlank()) Modifier.clickable {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            TestLog.evt("about_link", url)
        } else Modifier
        Text(value, color = if (accent) UiTheme.p.accent else UiTheme.p.textPrimary,
            fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (url.isNotBlank()) TextDecoration.Underline else null,
            modifier = vm.weight(1f))
    }
}

/** 链接行（安卓关于页链接行的桌面形态：下划线+系统浏览器打开）。 */
@Composable
private fun LinkRow(text: String, url: String) {
    Text(
        text, color = UiTheme.p.accent, fontSize = 12.sp, textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable {
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            TestLog.evt("about_link", url)
        },
    )
}

/** WebDAV 同步对话框（v0.45.0 对齐安卓 dialog_webdav_sync：地址/账号/密码可编辑、独立于网上书库服务器）。 */
@Composable
private fun WebDavBlock(onClose: () -> Unit = {}) {
    var syncing by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var intervalDlg by remember { mutableStateOf(false) }
    var enabledSync by remember { mutableStateOf(LibPrefs.bool("webdavSyncEnabled", true)) }
    // 首启预填：同步地址为空时取第一个 WebDAV 服务器（平滑迁移）。
    // 检视修复：移入 remember 初始化——组合体每次重组重写盘，用户清空地址会被重新预填回。
    var url by remember {
        var u = SyncPrefs.loadSyncUrl("")
        if (u.isBlank()) {
            val s0 = Db.listRemoteSources().firstOrNull { it.protocol.equals("webdav", ignoreCase = true) }
            if (s0 != null) {
                u = "http://" + s0.host + ":" + s0.port + (if (s0.path.isBlank()) "/" else "/" + s0.path)
                SyncPrefs.saveSyncUrl(u)
                SyncPrefs.saveSyncUser(s0.user)
                SyncPrefs.saveSyncPass(s0.password)
            }
        }
        mutableStateOf(u)
    }
    var user by remember { mutableStateOf(SyncPrefs.loadSyncUser("")) }
    var pass by remember { mutableStateOf(SyncPrefs.loadSyncPass("")) }
    var syncDir by remember { mutableStateOf(SyncPrefs.loadSyncDir("/HowRead")) }
    var dirDlg by remember { mutableStateOf(false) }
    var polDlg by remember { mutableStateOf(false) }
    var logDlg by remember { mutableStateOf(false) }
    val POLICIES = listOf(
        "newer" to L10n.s("较新优先（按修改时间）", "Newest wins (by time)"),
        "local" to L10n.s("本地优先", "Local first"),
        "server" to L10n.s("服务器优先", "Server first"),
        "farther" to L10n.s("更靠前优先（按阅读进度）", "Farthest first"),
    )
    fun commit() { SyncPrefs.saveSyncUrl(url); SyncPrefs.saveSyncUser(user); SyncPrefs.saveSyncPass(pass) }
    fun buildSrc(): RemoteSource? = SyncPrefs.syncSource(url, user, pass, syncDir.trim('/').ifEmpty { "HowRead" })

    CheckRow(L10n.s("启用同步（阅读进度与书签）", "Enable sync (reading progress and bookmarks)"), enabledSync) { v ->
        enabledSync = v; LibPrefs.put("webdavSyncEnabled", v); TestLog.evt("pref_sync_enabled", "$v")
    }
    CompactField(url, { url = it; commit() }, hint = L10n.s("WebDAV 地址（http(s)://主机[:端口]/路径）", "WebDAV URL"))
    CompactField(user, { user = it; commit() }, hint = L10n.s("账号", "Login"))
    CompactField(pass, { pass = it; commit() }, hint = L10n.s("密码", "Password"),
        visual = androidx.compose.ui.text.input.PasswordVisualTransformation())
    var trustAll by remember { mutableStateOf(LibPrefs.bool("syncTrustAll", true)) }
    CheckRow(L10n.s("信任自签名证书", "Trust self-signed certificates"), trustAll) { v ->
        trustAll = v; LibPrefs.put("syncTrustAll", v); TestLog.evt("pref_sync_trust", "$v")
    }
    // v0.54 对齐安卓 dialog_webdav_sync 行序：同步目录/冲突策略为可点值行
    KeyValRow(L10n.s("同步目录", "Sync folder"), syncDir) { dirDlg = true }
    KeyValRow(L10n.s("进度冲突策略", "Conflict policy"),
        POLICIES.firstOrNull { it.first == LibPrefs.str("webdavSyncPolicy", "newer") }?.second
            ?: L10n.s("较新优先（按修改时间）", "Newest wins")) { polDlg = true }
    val intervals = listOf(
        0 to L10n.s("关", "Off"), 5 to L10n.s("5分钟", "5m"), 15 to L10n.s("15分钟", "15m"), 30 to L10n.s("30分钟", "30m"),
        60 to L10n.s("1小时", "1h"), 180 to L10n.s("3小时", "3h"), 360 to L10n.s("6小时", "6h"), 1440 to L10n.s("每天", "Daily"),
    )
    var intervalMin by remember { mutableStateOf(SyncPrefs.loadIntervalMin()) }
    KeyValRow(L10n.s("定时同步", "Sync interval"),
        (intervals.firstOrNull { it.first == intervalMin }?.second ?: ("$intervalMin" + L10n.s("分钟", "m")))) { intervalDlg = true }
    // 测试连接/同步日志/立即同步（用户裁定：定时同步下一行，同一行）
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (testing) return@TextButton
            val src = buildSrc()
            if (src == null) { testMsg = L10n.s("地址无效——填 http(s)://主机[:端口]/路径", "Invalid URL"); return@TextButton }
            testing = true
            testMsg = L10n.s("测试中…", "Testing…")
            TestLog.evt("sync_test_begin", src.host)
            Thread {
                val r = runCatching { RemoteClients.create(src).use { it.test() } }
                testMsg = r.getOrElse { L10n.s("失败：", "Failed: ") + it }
                TestLog.evt("sync_test", "ok=" + r.isSuccess)
            }.apply { isDaemon = true }.start()
        }, enabled = !testing) { Text(if (testing) L10n.s("测试中…", "Testing…") else L10n.s("测试连接", "Test connection"), fontSize = 12.sp) }
        TextButton(onClick = { logDlg = true; TestLog.evt("sync_log_open") }) {
            Text(L10n.s("同步日志", "Sync log"), fontSize = 12.sp)
        }
        Button(
            onClick = {
                if (syncing) return@Button
                val src = buildSrc()
                if (src == null) { syncMsg = L10n.s("地址无效——填 http(s)://主机[:端口]/路径", "Invalid URL"); return@Button }
                syncing = true
                syncMsg = L10n.s("同步中…", "Syncing…")
                TestLog.evt("sync_click", "host=" + src.host)
                Thread {
                    if (LibPrefs.bool("webdavSyncCompat", true)) {
                        // v0.48.0：与安卓互通通道（books/<md5>.json 逐书进度+书签）
                        howread.core.sync.AndroidSync.onPrefsImported = { LibPrefs.invalidate() }
                        val ar = howread.core.sync.AndroidSync.sync(src, LibPrefs.str("webdavSyncPolicy", "newer")) { step ->
                            syncMsg = step
                        }
                        syncMsg = if (ar.ok)
                            "互通同步完成：进度采纳 ${ar.progressDown} 本 · 书签补录 ${ar.bookmarksDown} 条 · 上传/更新 ${ar.booksSynced} 本"
                        else
                            ("互通同步失败：" + ar.error).trim()
                        TestLog.evt("sync_manual_compat", "ok=" + ar.ok + " pDown=" + ar.progressDown + " bDown=" + ar.bookmarksDown)
                    } else {
                        val r = SyncEngine.sync(src)
                        if (r.themeAdopted) UiTheme.applyDark(ThemeStore.isDark())
                        syncMsg = if (r.ok)
                            "同步完成：进度采纳 ${r.progressAdopted} 条 / 本机较新 ${r.progressNewer} 条 · 书签补录 ${r.bookmarksAdded} 条 / 笔记互补 ${r.bookmarksMerged} 条 · 珍藏补录 ${r.favoritesAdded} 本"
                        else if (r.error == "busy") "已有同步正在进行，请稍候"
                        else ("同步失败：" + r.error + " " + r.detail).trim()
                        if (r.ok && r.configsAdopted > 0) {
                            syncMsg += " · 配置采纳 " + r.configsAdopted + " 项(" + r.configNames + ")"
                        }
                    }
                    syncing = false
                }.apply { isDaemon = true }.start()
            },
            enabled = !syncing,
        ) { Text(if (syncing) L10n.s("同步中…", "Syncing…") else L10n.s("立即同步", "Sync now")) }
    }
    // 底部操作行（用户裁定：关闭/保存；保存=显式落盘后关闭对话框）
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text(L10n.s("关闭", "Close"), fontSize = 12.sp, color = UiTheme.p.textTertiary) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            commit()
            TestLog.evt("sync_prefs_saved", "url=" + url.take(60))
            onClose()
        }, enabled = !syncing) { Text(L10n.s("保存", "Save")) }
    }
    // 测试连接的提示信息（用户裁定：关闭/保存按钮的下一行）
    if (testMsg.isNotEmpty()) Text(testMsg, color = UiTheme.p.textTertiary, fontSize = 12.sp)
    if (dirDlg) {
        var dirInput by remember { mutableStateOf(syncDir) }
        AlertDialog(
            onDismissRequest = { dirDlg = false },
            title = { Text(L10n.s("同步目录", "Sync folder"), fontSize = 15.sp) },
            text = {
                Column {
                    Text(L10n.s("服务器上的同步根目录（阅读进度/书签/配置所在，默认 /HowRead，与手机一致）。",
                            "Sync root folder on the server (default /HowRead, same as the phone)."),
                        color = UiTheme.p.textTertiary, fontSize = 11.sp)
                    OutlinedTextField(value = dirInput, onValueChange = { dirInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    syncDir = dirInput.trim().ifEmpty { "/HowRead" }
                    SyncPrefs.saveSyncDir(syncDir)
                    TestLog.evt("sync_dir", syncDir)
                    dirDlg = false
                }) { Text(L10n.s("保存", "Save"), color = UiTheme.p.accent) }
            },
            dismissButton = { TextButton(onClick = { dirDlg = false }) { Text(L10n.s("取消", "Cancel")) } },
        )
    }
    if (polDlg) {
        PickDialog(L10n.s("进度冲突策略", "Conflict policy"), POLICIES.map { it.second }, onDismiss = { polDlg = false }) { pick ->
            val code = POLICIES.firstOrNull { it.second == pick }?.first ?: "newer"
            LibPrefs.put("webdavSyncPolicy", code)
            TestLog.evt("sync_policy", code)
        }
    }
    if (logDlg) {
        SimpleDialog(L10n.s("同步日志", "Sync log"), onDismiss = { logDlg = false }) {
            // 检视修复：日志文件读 IO 移到后台协程，不卡打开动画
            var logLines by remember { mutableStateOf(listOf(L10n.s("读取中…", "Loading…"))) }
            LaunchedEffect(Unit) {
                logLines = withContext(Dispatchers.IO) {
                    runCatching {
                        File(AppPaths.dataDir, "logs/hrtest.log").readText(Charsets.UTF_8)
                            .lineSequence()
                            .filter { it.contains("sync") || it.contains("global") || it.contains("field_merge") }
                            .toList().takeLast(200)
                    }.getOrDefault(listOf(L10n.s("（无日志）", "(no log)")))
                }
            }
            Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                for (l in logLines) Text(l, fontSize = 10.sp, color = UiTheme.p.textTertiary)
            }
        }
    }
    if (syncMsg.isNotEmpty()) {
        Text(syncMsg, color = UiTheme.p.textSecondary, fontSize = 12.sp)
    }
    if (intervalDlg) {
        PickDialog(L10n.s("定时同步", "Sync interval"), intervals.map { it.second }, onDismiss = { intervalDlg = false }) { pick ->
            val v = intervals.firstOrNull { it.second == pick }?.first ?: 0
            intervalMin = v
            SyncPrefs.saveIntervalMin(v)
            TestLog.evt("sync_interval", "$v")
        }
    }
}

/** 备份与恢复块（自 v0.29 平移；文件对话框仍用 Swing 文件选择）。 */
@Composable
private fun BackupBlock(onMigrate: () -> Unit = {}) {
    var backupMsg by remember { mutableStateOf("") }
    var restorePicking by remember { mutableStateOf(false) }
    if (restorePicking) {
        LaunchedEffect(Unit) {
            restorePicking = false
            val fc = javax.swing.JFileChooser()
            fc.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("HowRead 备份 (*.zip)", "zip")
            if (fc.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                backupMsg = Backup.stageRestore(fc.selectedFile)
                TestLog.evt("backup_restore_staged", fc.selectedFile.name)
            }
        }
    }
    // 介绍行（安卓 backup_section 同款文案）
    Text(
        L10n.s("将 HowRead 目录传输到另一台设备以迁移您的数据。", "Transfer the HowRead folder to another device to migrate your data."),
        color = UiTheme.p.textSecondary, fontSize = 12.sp,
    )
    // 导入/导出/迁移（附图第二行，三个下划线链接）
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 4.dp)) {
        TextButton(onClick = { restorePicking = true }) {
            Text(L10n.s("导入", "Import"), fontSize = 13.sp, color = UiTheme.p.accent)
        }
        TextButton(onClick = {
            val fc = javax.swing.JFileChooser()
            fc.dialogTitle = L10n.s("导出备份", "Export backup")
            fc.selectedFile = java.io.File(System.getProperty("user.home"),
                "HowRead-Backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date()) + ".zip")
            if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                var f = fc.selectedFile
                if (!f.name.lowercase().endsWith(".zip")) f = java.io.File(f.parentFile, f.name + ".zip")
                backupMsg = runCatching { Backup.exportTo(f) }
                    .getOrElse { L10n.s("导出失败：", "Export failed: ") + it }
                TestLog.evt("backup_export", f.name)
            }
        }) { Text(L10n.s("导出", "Export"), fontSize = 13.sp, color = UiTheme.p.accent) }
        TextButton(onClick = onMigrate) {
            Text(L10n.s("迁移", "Migrate"), fontSize = 13.sp, color = UiTheme.p.accent)
        }
    }
    if (backupMsg.isNotEmpty()) {
        Text(backupMsg, color = UiTheme.p.textSecondary, fontSize = 12.sp)
    }
}


/**
 * 配置文件（v0.55.0 对齐安卓 AppProfile：每个配置文件=一份独立数据目录）。
 * 默认配置 = <软件根>\data（便携默认位）；其余配置文件在 <软件根>\profiles\<名称>\。
 * 切换/新建 = 写 data-dir.txt 指向对应目录，重启后生效（安卓为保存后自动重启，桌面以提示代替）。
 */
object AppProfiles {
    private const val RESET_MARK = ".hr_reset_defaults"

    fun root(): File = File(AppPaths.rootDir, "profiles")

    /** 默认配置的数据目录（与 AppPaths 默认解析一致）。 */
    fun defaultDir(): File =
        if (AppPaths.isWindows) File(AppPaths.rootDir, "data")
        else File(System.getProperty("user.home"), ".howread")

    /** 全部配置文件（默认配置在前，其余按名称排序）。 */
    fun list(): List<Pair<String, File>> {
        val out = mutableListOf(L10n.s("默认配置", "Default") to defaultDir())
        val kids = runCatching { root().listFiles { f -> f.isDirectory } }.getOrNull() ?: emptyArray()
        for (d in kids.sortedBy { it.name.lowercase() }) out += d.name to d
        return out
    }

    /** 当前配置文件名（默认位=「默认配置」；profiles 下=目录名；其他自定义=目录名）。 */
    fun currentName(): String {
        val cur = runCatching { AppPaths.dataDir.canonicalPath }.getOrDefault("")
        for ((n, d) in list()) {
            if (runCatching { d.canonicalPath }.getOrDefault("#") == cur) return n
        }
        return runCatching { File(cur).name }.getOrDefault("")
    }

    /** 名称合法性：非空、不含 Windows 文件名非法字符、长度受限。 */
    fun validName(s: String): Boolean =
        s.isNotBlank() && s.length <= 60 && !s.contains(Regex("[\\\\/:*?\"<>|]"))

    /** 新建配置文件（目录已存在或创建失败返 null）。 */
    fun create(name: String): File? = runCatching {
        val d = File(root(), name.trim())
        if (d.exists() || !d.mkdirs()) null else d
    }.getOrNull()

    /** 切换配置文件：默认位=清除 data-dir.txt，其余=写入其路径。重启后生效。 */
    fun switchTo(dir: File): Boolean =
        if (dir == defaultDir()) AppPaths.clearDataDirOverride()
        else AppPaths.changeDataDir(dir.absolutePath)

    /** 恢复默认配置（安卓 reset_app_confirm 同语义）：落标记 → 尽力自动重启 → 退出；重启时 applyPendingReset 清理。 */
    fun requestFactoryReset() {
        runCatching { File(AppPaths.dataDir, RESET_MARK).createNewFile() }
        TestLog.evt("factory_reset_mark")
        runCatching {
            if (AppPaths.isWindows) {
                val exe = File(AppPaths.rootDir, "HowReadPro.exe")
                if (exe.isFile) {
                    Runtime.getRuntime().exec(
                        arrayOf("cmd", "/c", "timeout /t 1 /nobreak >nul & start \"\" \"" + exe.canonicalPath + "\""),
                        null, AppPaths.rootDir,
                    )
                }
            }
        }
        kotlin.system.exitProcess(0)
    }

    /** 启动早期（任何 Db/偏好访问之前）：存在恢复标记则清空配置/索引/缓存。 */
    fun applyPendingReset() {
        val m = File(AppPaths.dataDir, RESET_MARK)
        if (m.isFile) {
            applyResetIn(AppPaths.dataDir)
            runCatching { m.delete() }
            TestLog.evt("factory_reset_applied")
        }
    }

    /** 在指定数据目录执行出厂复位（配置 json/properties + 书籍索引 db + cache 全清；字体/下载/日志保留）。 */
    fun applyResetIn(dir: File): Int {
        var n = 0
        val files = listOf(
            "lib.properties", "ui.json", "reader.json", "ai.json", "sync.json", "tts.json", "lang.json",
            "howread.db", "howread.db-wal", "howread.db-shm", "howread.db-journal",
        )
        for (nm in files) if (File(dir, nm).delete()) n++
        val cache = File(dir, "cache")
        for (f in cache.listFiles() ?: emptyArray()) {
            if (f.isDirectory) { if (f.deleteRecursively()) n++ } else if (f.delete()) n++
        }
        return n
    }
}
