package howread.app

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
 * 标题行（偏好 + 退出程序）→ 配置文件（首字母+名称+齿轮+恢复默认配置）→
 * ■书库设置色条：格式配置/书库设置/书库显示配置/书籍封面配置/阅读配置（展开行，就地展开）→
 * ■常规设置色条：调试日志(展开)/屏幕/语言/WebDAV 同步(Pro)/AI 大模型(Pro)/在线阅读(Pro)/缓存管理（键值行，右值弹对话框）/
 * 备份配置/文件关联配置(展开) → ■UI配置色条：主题配置/标签栏配置(展开)/软件说明(行→对话框)。
 * 朗读与离线词典归入阅读配置（安卓对应行在其二级内）；屏幕方向为移动概念桌面省略。
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
    var migPicking by remember { mutableStateOf(false) }
    var themeDlg by remember { mutableStateOf(false) }
    var fontSizeDlg by remember { mutableStateOf(false) }
    var blueDlg by remember { mutableStateOf(false) }
    var engineDlg by remember { mutableStateOf(false) }
    var lockDlg by remember { mutableStateOf(false) }
    var modeNameDlg by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

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

        // ==================== 配置文件（安卓：左粗体标题+圆头像+当前配置+齿轮；下方恢复默认配置） ====================
        Text(L10n.s("配置文件", "Profile"), color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 6.dp, top = 4.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(UiTheme.p.accent), contentAlignment = Alignment.Center) {
                Text("H", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("HowReadPro（好好读Pro）", color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(L10n.s("厂商 leestudio · v" + AppInfo.VERSION, "vendor leestudio · v" + AppInfo.VERSION),
                    color = UiTheme.p.textFaintest, fontSize = 11.sp)
            }
            Icon(Icons.Filled.Settings, contentDescription = L10n.s("打开数据文件夹", "Open data folder"),
                tint = UiTheme.p.textTertiary, modifier = Modifier.size(18.dp).clickable {
                    runCatching { java.awt.Desktop.getDesktop().open(AppPaths.dataDir) }
                    TestLog.evt("datadir_open")
                })
        }
        Text(AppPaths.dataDir.absolutePath, color = UiTheme.p.textSecondary, fontSize = 11.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        Text(
            if (AppPaths.overrideConfigured()) L10n.s("当前：自定义目录（data-dir.txt），重启生效。", "Custom location (data-dir.txt).")
            else L10n.s("当前：默认——随软件目录（便携模式）。", "Default: portable, next to the program."),
            color = UiTheme.p.textFaintest, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            L10n.s("更改数据目录", "Change data folder"), color = UiTheme.p.accent, fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { ddPicking = true }.padding(start = 6.dp, top = 4.dp, bottom = 2.dp),
        )
        Text(
            L10n.s("恢复默认配置", "Restore default configuration"), color = UiTheme.p.accent, fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { restoreConfirm = true }.padding(start = 6.dp, top = 2.dp, bottom = 8.dp),
        )
        if (ddMsg.isNotEmpty()) Text(ddMsg, color = UiTheme.p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        if (restoreMsg.isNotEmpty()) Text(restoreMsg, color = UiTheme.p.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))

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
            Text(L10n.s("书库目录（本地文件夹）的添加/编辑/删除/扫描在「我的文件」页的「本地位置」分组。当前在册 ${dirs.size} 个目录 / ${appState.library.size} 本书。",
                    "Folder management lives in My Files → Local. ${dirs.size} folders / ${appState.library.size} books."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            TextButton(onClick = { appState.rescan(); TestLog.evt("prefs_rescan") }, enabled = !appState.scanning) {
                Text(if (appState.scanning) L10n.s("扫描中…", "Scanning…") else L10n.s("立即扫描", "Scan now"), fontSize = 12.sp)
            }
            var scanLaunch by remember { mutableStateOf(LibPrefs.bool("scanAtLaunch", true)) }
            CheckRow(L10n.s("启动时扫描新书", "Scan for new books at launch"), scanLaunch) { v ->
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
            DisabledRow(L10n.s("使用Calibre metadata.opf", "Use Calibre metadata.opf"),
                L10n.s("暂不支持", "not on desktop"))
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
            SeekRow(L10n.s("列表（封面小图）", "List cover size"),
                LibPrefs.str("coverListDp", "34").toIntOrNull() ?: 34, 28..72) { v ->
                LibPrefs.put("coverListDp", v); TestLog.evt("pref_cover_list", "$v")
            }
            SeekRow(L10n.s("网格（封面大图）", "Grid cover size"),
                LibPrefs.str("coverGridDp", "150").toIntOrNull() ?: 150, 110..280) { v ->
                LibPrefs.put("coverGridDp", v); TestLog.evt("pref_cover_grid", "$v")
            }
            KeyValRow(L10n.s("列数", "Columns"),
                if (LibPrefs.str("gridColumnCount", "0") == "0") L10n.s("自适应", "Auto")
                else LibPrefs.str("gridColumnCount", "0")) { colDlg = true }
            TextButton(onClick = {
                LibPrefs.put("gridColumnCount", "0")
                LibPrefs.put("coverListDp", "34")
                LibPrefs.put("coverGridDp", "150")
                TestLog.evt("pref_display_reset")
            }) { Text(L10n.s("重置", "Reset"), fontSize = 12.sp) }
            var showCovers by remember { mutableStateOf(LibPrefs.bool("showCovers", true)) }
            CheckRow(L10n.s("显示书籍封面（关闭后书卡片只显示文字占位）。", "Show book covers."), showCovers) { v ->
                showCovers = v; LibPrefs.put("showCovers", v); TestLog.evt("pref_covers", "$v")
            }
            Text(L10n.s("视图与排序的默认值=书库页顶栏最近一次选择（自动记住）。", "View/sort defaults follow the library toolbar."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
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
            Text(L10n.s("封面缓存占用：${coversSizeText()}（封面图缩略缓存，可安全清除）。", "Cover cache: ${coversSizeText()}."),
                color = UiTheme.p.textSecondary, fontSize = 12.sp)
            TextButton(onClick = {
                val d = File(AppPaths.cacheDir, "covers")
                var n = 0
                for (f in d.listFiles() ?: emptyArray()) if (f.delete()) n++
                TestLog.evt("covers_clear", "n=$n")
            }) { Text(L10n.s("清除封面缓存", "Clear cover cache"), fontSize = 12.sp) }
        }
        // ---- 阅读配置（安卓 10 项行序：单击/双击/密码/上次书/第1页/记住搜索/锁定/退出确认/无障碍/上下文菜单） ----
        ExpandRow(L10n.s("阅读配置", "Reading")) {
            // 阅读时单击（安卓同款值行；桌面映射=打开书籍默认模式：滚动/书页）
            KeyValRow(L10n.s("阅读时单击", "Single tap while reading"),
                if (LibPrefs.defaultReaderModePage()) L10n.s("书页（横）", "Book pages") else L10n.s("滚动（竖）", "Scroll")) {
                singleTapDlg = true
            }
            DisabledRow(L10n.s("阅读时双击", "Double tap while reading"), L10n.s("桌面不适用", "n/a on desktop"))
            DisabledRow(L10n.s("打开应用时需输入密码", "Password on start"), L10n.s("桌面不适用", "n/a on desktop"))
            DisabledRow(L10n.s("密码", "Password"), L10n.s("桌面不适用", "n/a on desktop"))
            Text(L10n.s("字体：放入 .ttf/.otf/.ttc 后，阅读器「排」面板即可选用；阅读器内可调字号/行距/版心（会记住）。",
                    "Fonts go here; size/line-height/margins adjustable in reader."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            TextButton(onClick = {
                runCatching {
                    ReaderPrefsStore.fontsDir().mkdirs()
                    java.awt.Desktop.getDesktop().open(ReaderPrefsStore.fontsDir())
                }
                TestLog.evt("fonts_open_folder")
            }) { Text(L10n.s("打开字体文件夹", "Open fonts folder"), fontSize = 12.sp) }
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
            DisabledRow(L10n.s("默认锁定书籍", "Lock books by default"), L10n.s("桌面不适用", "n/a on desktop"))
            DisabledRow(L10n.s("返回手势退出时需确认", "Confirm on exit gesture"), L10n.s("桌面不适用", "n/a on desktop"))
            DisabledRow(L10n.s("启用无障碍模式", "Accessibility mode"), L10n.s("桌面不适用", "n/a on desktop"))
            DisabledRow(L10n.s("集成到全局上下文菜单", "Global context menu"), L10n.s("桌面不适用", "n/a on desktop"))
            TtsBlock()
            val n = remember { Dicts.installed(true).size }
            Text(
                if (n == 0) L10n.s("离线词典：未检测到词典。把 StarDict 词典文件夹（含 .ifo/.idx/.dict）放入词典目录后，阅读器划词即可「查词」。",
                    "Dictionaries: none. Put StarDict folders (.ifo/.idx/.dict) into the dict folder.")
                else L10n.s("离线词典：已安装 $n 本词典，阅读器划词后点「查词」即可离线释义。", "Dictionaries: $n installed."),
                color = UiTheme.p.textFaint, fontSize = 11.sp,
            )
            TextButton(onClick = {
                runCatching {
                    Dicts.dictDir().mkdirs()
                    java.awt.Desktop.getDesktop().open(Dicts.dictDir())
                }
                TestLog.evt("dict_open_folder")
            }) { Text(L10n.s("打开词典文件夹", "Open dict folder"), fontSize = 12.sp) }
        }

        // ==================== ■ 常规设置（色条） ====================
        SectionBar(L10n.s("常规设置", "General settings"))
        // ---- 调试日志（安卓同款三行：开关/导出/清空） ----
        ExpandRow(L10n.s("调试日志", "Debug log")) {
            var dbg by remember { mutableStateOf(LibPrefs.bool("debugLog", true)) }
            CheckRow(L10n.s("记录调试事件日志（hrtest.log；默认关，问题排查时开启）。", "Write event log (for troubleshooting)."), dbg) { v ->
                dbg = v; LibPrefs.put("debugLog", v)
                TestLog.enabled = v
                TestLog.evt("pref_debuglog", "$v")
            }
            Text(AppPaths.logsDir.absolutePath, color = UiTheme.p.textFaintest, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        // ---- 蓝光滤镜（v0.50.0 对齐安卓护眼：桌面=全局暖色叠加层，即时生效） ----
        KeyValRow(L10n.s("蓝光滤镜", "Blue light filter"),
            when (appState.blueFilter) { 2 -> L10n.s("中", "Medium"); 1 -> L10n.s("轻", "Light"); else -> L10n.s("关", "Off") }
        ) { blueDlg = true }
        // ---- 搜索引擎（阅读器选区「网搜」用，安卓 WebSearch 可自定义等价） ----
        KeyValRow(L10n.s("搜索引擎", "Web search engine"), LibPrefs.str("webSearchEngine", "google")) { engineDlg = true }
        // ---- 应用密码锁（v0.51.0 对齐安卓 PasswordLock） ----
        KeyValRow(L10n.s("应用密码锁", "App lock"),
            if (LibPrefs.str("appLockPassword", "").isNotEmpty()) L10n.s("已开启", "On") else L10n.s("关", "Off")
        ) { lockDlg = true }
        // ---- 阅读模式自定义命名（v0.52.0 对齐安卓 dialog_custom_reading_modes） ----
        KeyValRow(L10n.s("Reading mode names", "阅读模式命名"),
            LibPrefs.str("modeNamePage", "").ifBlank { L10n.s("翻页", "Paged") } + " / " +
                LibPrefs.str("modeNameScroll", "").ifBlank { L10n.s("滚动", "Scroll") }
        ) { modeNameDlg = true }
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
        // ---- 备份配置（安卓同款：提示+导入导出+路径行） ----
        ExpandRow(L10n.s("备份配置", "Backup")) {
            BackupBlock()
            TextButton(onClick = { migPicking = true; TestLog.evt("prefs_migrate_click") }) {
                Text(L10n.s("迁移", "Migrate"), fontSize = 12.sp, color = UiTheme.p.accent)
            }
            Text(L10n.s("迁移=把数据目录指向新位置（配合备份导出，可将数据带到另一台电脑）。", "Migrate = point the data folder to a new location."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            PathRow(L10n.s("配置文件", "Profile"), AppPaths.dataDir)
            PathRow(L10n.s("字体路径", "Fonts"), ReaderPrefsStore.fontsDir())
            PathRow(L10n.s("下载", "Downloads"), File(AppPaths.dataDir, "downloads"))
            DisabledRow(L10n.s("音频文件", "Audio files"), L10n.s("桌面不适用", "n/a on desktop"))
        }
        // ---- 文件关联配置（桌面特有，置于常规设置尾） ----
        ExpandRow(L10n.s("文件关联配置", "File association")) {
            if (!FileAssoc.isWindows()) {
                Text(L10n.s("文件关联仅 Windows 提供。", "Windows only."), color = UiTheme.p.textFaint, fontSize = 12.sp)
            } else {
                var assocMsg by remember { mutableStateOf("") }
                Text(
                    L10n.s("把 .epub/.pdf/.mobi/.azw3/.fb2/.txt/.cbz 的双击打开方式关联到本程序（写入当前用户注册表，无需管理员）。",
                        "Associate ebook extensions with this program (per-user registry)."),
                    color = UiTheme.p.textFaint, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { assocMsg = FileAssoc.associate(); TestLog.evt("file_assoc", "associate") }) {
                        Text(L10n.s("建立关联", "Associate"))
                    }
                    OutlinedButton(onClick = { assocMsg = FileAssoc.disassociate(); TestLog.evt("file_assoc", "disassociate") }) {
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
        // ---- 标签栏配置（安卓=底部标签栏位置/动画/顺序拖拽；桌面为左侧栏，仅保留侧栏开关） ----
        ExpandRow(L10n.s("标签栏配置", "Tabs")) {
            Text(L10n.s("桌面版导航为左侧标签栏（无底部标签栏，顺序固定）；安卓的标签栏位置/动画/顺序拖拽不适用。",
                    "Desktop uses the left sidebar; tab position/animation/reorder are Android-only."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            var sidebar by remember { mutableStateOf(LibPrefs.bool("showSidebar", true)) }
            CheckRow(L10n.s("显示左侧标签栏（重启生效）。", "Show left sidebar (restart to apply)."), sidebar) { v ->
                sidebar = v; LibPrefs.put("showSidebar", v); TestLog.evt("pref_sidebar", "$v")
            }
        }
        // ---- 软件说明（行 → 对话框） ----
        Row(
            Modifier.fillMaxWidth().clickable { aboutOpen = true; TestLog.evt("prefs_about_open") }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(UiTheme.p.accent), contentAlignment = Alignment.Center) {
                Text("H", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "HowReadPro（好好读Pro）v${AppInfo.VERSION}",
                color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp),
            )
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
            text = { Text(L10n.s("将数据目录恢复为默认（随软件目录，便携模式）。阅读进度与配置文件保留。", "Restore the default portable data folder. Progress and configs are kept.")) },
            confirmButton = {
                TextButton(onClick = {
                    restoreConfirm = false
                    restoreMsg = if (AppPaths.overrideConfigured()) {
                        AppPaths.clearDataDirOverride()
                        TestLog.evt("datadir_reset")
                        L10n.s("已恢复默认（随软件目录），重启后生效。", "Restored default. Restart to apply.")
                    } else {
                        L10n.s("当前已是默认配置。", "Already default.")
                    }
                }) { Text(L10n.s("确定", "OK"), color = UiTheme.p.danger) }
            },
            dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text(L10n.s("取消", "Cancel")) } },
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
    if (blueDlg) {
        PickDialog(L10n.s("蓝光滤镜", "Blue light filter"),
            listOf(L10n.s("关", "Off"), L10n.s("轻", "Light"), L10n.s("中", "Medium")),
            onDismiss = { blueDlg = false }) { pick ->
            val lv = when (pick) { L10n.s("轻", "Light") -> 1; L10n.s("中", "Medium") -> 2; else -> 0 }
            appState.blueFilter = lv
            LibPrefs.put("blueFilter", "$lv")
            TestLog.evt("blue_filter", "$lv")
        }
    }
    if (engineDlg) {
        PickDialog(L10n.s("搜索引擎", "Web search engine"),
            listOf("google", "bing", "baidu", "duckduckgo"), onDismiss = { engineDlg = false }) { pick ->
            LibPrefs.put("webSearchEngine", pick)
            TestLog.evt("web_search_engine", pick)
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
    if (modeNameDlg) {
        var pn by remember { mutableStateOf(LibPrefs.str("modeNamePage", "")) }
        var sn by remember { mutableStateOf(LibPrefs.str("modeNameScroll", "")) }
        AlertDialog(
            onDismissRequest = { modeNameDlg = false },
            title = { Text(L10n.s("阅读模式命名", "Reading mode names"), color = UiTheme.p.textPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L10n.s("自定义两种阅读模式的显示名（留空=默认；阅读器切换按钮即用此名）。",
                        "Custom names for the two reading modes (blank = default)."),
                        color = UiTheme.p.textTertiary, fontSize = 12.sp)
                    androidx.compose.material3.OutlinedTextField(pn, { pn = it }, singleLine = true,
                        label = { Text(L10n.s("翻页模式名称", "Paged mode name")) })
                    androidx.compose.material3.OutlinedTextField(sn, { sn = it }, singleLine = true,
                        label = { Text(L10n.s("滚动模式名称", "Scroll mode name")) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    LibPrefs.put("modeNamePage", pn.trim())
                    LibPrefs.put("modeNameScroll", sn.trim())
                    TestLog.evt("mode_names", "page=" + pn.trim().take(12) + " scroll=" + sn.trim().take(12))
                    modeNameDlg = false
                }) { Text(L10n.s("保存", "Save"), color = UiTheme.p.accent) }
            },
            dismissButton = { TextButton(onClick = { modeNameDlg = false }) { Text(L10n.s("取消", "Cancel"), color = UiTheme.p.textTertiary) } },
        )
    }
    if (syncDlg) {
        SimpleDialog(L10n.s("WebDAV 同步 (Pro)", "WebDAV sync (Pro)"), onDismiss = { syncDlg = false }) { WebDavBlock() }
    }
    if (onlineDlg) {
        SimpleDialog(L10n.s("在线阅读 (Pro)", "Online reading (Pro)"), onDismiss = { onlineDlg = false }) {
            var online by remember { mutableStateOf(LibPrefs.bool("onlineReading", true)) }
            CheckRow(L10n.s("启用网上书库（我的文件中的 OPDS/WebDAV/SMB/SFTP 分组与远程目录扫描）。",
                "Enable network libraries in My Files."), online) { v ->
                online = v; LibPrefs.put("onlineReading", v); TestLog.evt("pref_online", "$v")
            }
            var winText by remember { mutableStateOf(LibPrefs.str("remoteCacheWindowMB", "5")) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L10n.s("远程缓存窗口 (MB)", "Remote cache window (MB)"), color = UiTheme.p.textSecondary, fontSize = 12.sp)
                OutlinedTextField(
                    value = winText,
                    onValueChange = { v ->
                        winText = v
                        v.toLongOrNull()?.let { n ->
                            if (n in 1..4096) { LibPrefs.put("remoteCacheWindowMB", n); TestLog.evt("pref_cache_window", "$n") }
                        }
                    },
                    singleLine = true, modifier = Modifier.width(90.dp),
                )
            }
            Text(L10n.s("小于该值的小书整本缓存后打开；大书按此为内存上限分块边读边缓存（默认 5）。",
                    "Books smaller than this cache whole; bigger ones stream in blocks (default 5)."),
                color = UiTheme.p.textFaintest, fontSize = 10.sp)
            var fw by remember { mutableStateOf(LibPrefs.bool("remoteFillWhole", true)) }
            CheckRow(L10n.s("后台自动补齐整本（关=只预取阅读位置前后，省流量；对新打开的书生效）。",
                "Auto-fill whole book in background."), fw) { v ->
                fw = v; LibPrefs.put("remoteFillWhole", v); TestLog.evt("pref_cache_fillwhole", "$v")
            }
        }
    }
    if (cacheDlg) {
        var lines by remember { mutableStateOf(cacheLines()) }
        var total by remember { mutableStateOf(cacheSizeText()) }
        SimpleDialog(L10n.s("缓存管理", "Cache management"), onDismiss = { cacheDlg = false }) {
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
            Button(onClick = {
                var n = 0
                for (sub in listOf("covers", "accels", "convert", "txt", "zip", "ai")) {
                    val d = File(AppPaths.cacheDir, sub)
                    for (f in d.listFiles() ?: emptyArray()) if (f.delete()) n++
                }
                for (sub in listOf("remote", "remote-blocks")) {
                    val d = File(AppPaths.cacheDir, sub)
                    if (d.exists()) {
                        n += d.walkTopDown().count { it.isFile }
                        runCatching { d.deleteRecursively() }
                    }
                }
                lines = cacheLines(); total = cacheSizeText()
                TestLog.evt("cache_clear", "n=$n")
            }) { Text(L10n.s("清理全部缓存", "Clear all caches"), fontSize = 12.sp) }
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
            listOf(L10n.s("滚动（竖）", "Scroll (vertical)"), L10n.s("书页（横）", "Book pages (horizontal)")),
            onDismiss = { singleTapDlg = false }) { pick ->
            val page = pick == L10n.s("书页（横）", "Book pages (horizontal)")
            LibPrefs.put("defaultReaderMode", if (page) "page" else "scroll")
            TestLog.evt("pref_readmode", if (page) "page" else "scroll")
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

/** 键值行（安卓 ConfLineView：左粗体标题 + 右下划线值，整行可点）。 */
@Composable
private fun KeyValRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(190.dp))
        Text(value, color = UiTheme.p.accent, fontSize = 13.sp, textDecoration = TextDecoration.Underline,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 二级展开行（安卓：小图标+下划线标题，点开就地展开）。 */
@Composable
private fun ExpandRow(title: String, content: @Composable () -> Unit) {
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
            Text(if (open) "▾" else "▸", color = UiTheme.p.textFaintest, fontSize = 12.sp)
        }
        if (open) {
            Column(Modifier.padding(start = 23.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** 禁用行（桌面无对应底层能力的安卓配置项：照常占位保持 1:1 行序，置灰并注明原因）。 */
@Composable
private fun DisabledRow(text: String, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = false, enabled = false, onCheckedChange = null)
        Text(text, color = UiTheme.p.textFaintest, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(note, color = UiTheme.p.textFaintest, fontSize = 10.sp)
    }
}

/** 滑杆行（安卓 CustomSeek 同款：标签+当前值+滑轨）。 */
@Composable
private fun SeekRow(label: String, init: Int, range: IntRange, onCommit: (Int) -> Unit) {
    var v by remember { mutableStateOf(init.toFloat()) }
    Text("$label：${v.toInt()}", color = UiTheme.p.textSecondary, fontSize = 11.sp)
    Slider(
        value = v,
        onValueChange = { v = it },
        onValueChangeFinished = { onCommit(v.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
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
    Text(label, color = UiTheme.p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
private fun SimpleDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(560.dp).padding(vertical = 24.dp), color = UiTheme.p.panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                content()
                TextButton(onClick = onDismiss) { Text(L10n.s("关闭", "Close"), fontSize = 12.sp) }
            }
        }
    }
}

private object ScanPrefsUi {
    fun enabledSet(): Set<String> = howread.core.library.ScanPrefs.configured() ?: howread.core.library.Scanner.BOOK_EXTS
    fun save(sel: Set<String>) = howread.core.library.ScanPrefs.setExts(sel.toList())
}

private fun coversSizeText(): String {
    val d = File(AppPaths.cacheDir, "covers")
    val b = (d.listFiles() ?: emptyArray()).sumOf { it.length() }
    return humanSize(b)
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
    L10n.s("封面缓存", "Cover cache") to dirBytes("covers"),
)

private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) }

private fun humanSize(b: Long): String =
    if (b >= 1024L * 1024) "%.2fMB".format(b / 1024f / 1024f) else "${b / 1024}KB"

/** 软件说明弹框（v0.42.0 与安卓 P30 实测 22 行 1:1：版本头条/描述/Pro 卡片/开发者/更新日志/隐私政策/许可/反馈/邮箱/官网/fork 行）。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(480.dp).padding(vertical = 24.dp), color = UiTheme.p.panel, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 版本头条 + 应用描述
                Text("HowReadPro: v" + AppInfo.VERSION, color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(L10n.s("一个专注于个人阅读体验的开源电子书阅读软件",
                        "An open-source ebook reader focused on personal reading experience"),
                    color = UiTheme.p.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                // Pro 卡片
                Text(L10n.s("高级功能", "Pro features"), color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(L10n.s("Pro 已激活", "Pro unlocked"), color = UiTheme.p.accent, fontSize = 12.sp)
                Text(L10n.s("解锁方式：本地 key\n购买时间：\n订单号后四位：", "Unlock: local key\nPurchased at:\nOrder tail:"),
                    color = UiTheme.p.textFaintest, fontSize = 10.sp)
                TextButton(onClick = { }, enabled = false) { Text(L10n.s("恢复购买", "Restore purchase"), fontSize = 12.sp) }
                TextButton(onClick = { }, enabled = false) { Text(L10n.s("管理权益", "Manage entitlements"), fontSize = 12.sp) }
                Spacer(Modifier.height(6.dp))
                // 开发者
                Text(L10n.s("开发者", "Developer"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                Text("LeeStudio", color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // 更新日志
                Text(L10n.s("更新日志", "What is new"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                Text("HowReadPro（好好读Pro） v" + AppInfo.VERSION, color = UiTheme.p.textPrimary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                // 隐私政策
                Text(L10n.s("隐私政策", "Privacy policy"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                LinkRow(L10n.s("HowReadPro 隐私政策", "HowReadPro privacy policy"), "https://github.com/380121850/howread")
                Spacer(Modifier.height(6.dp))
                // 许可
                LinkRow(L10n.s("开放源代码许可", "Open source licence (GNU GPL v3.0)"), "https://github.com/380121850/howread")
                LinkRow(L10n.s("第三方库许可", "Licenses for libraries"), "https://github.com/380121850/howread")
                Spacer(Modifier.height(6.dp))
                // 支持和问题反馈
                Text(L10n.s("支持和问题反馈", "Support and feedback"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                LinkRow(L10n.s("HowReadPro 问题反馈", "HowReadPro issues"), "https://github.com/380121850/howread/issues")
                Spacer(Modifier.height(6.dp))
                // 邮箱
                Text(L10n.s("邮箱", "Email"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                LinkRow("380121850@163.com", "mailto:380121850@163.com")
                Spacer(Modifier.height(6.dp))
                // 官网
                Text(L10n.s("官网", "Website"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                LinkRow("HowReadPro", "https://howread.app")
                Spacer(Modifier.height(6.dp))
                Text(L10n.s("This is a fork of Librera Reader", "This is a fork of Librera Reader"),
                    color = UiTheme.p.textFaintest, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(L10n.s("确定", "OK")) }
            }
        }
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

/** 朗读配置块（归入阅读配置；自 v0.29 平移）。 */
@Composable
private fun TtsBlock() {
    var tts by remember { mutableStateOf(TtsPrefsStore.load()) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        voices = withContext(Dispatchers.IO) { TtsSpeaker.listVoices() }
    }
    fun upd(p: TtsPrefs) { tts = p; TtsPrefsStore.save(p) }
    Text(L10n.s("朗读 · 语速 ${tts.rate}（-10 慢 ~ +10 快，0 常速）", "TTS · rate ${tts.rate}"),
        color = UiTheme.p.textFaint, fontSize = 11.sp)
    Slider(
        value = tts.rate.toFloat(),
        onValueChange = { tts = tts.copy(rate = it.toInt()) },
        onValueChangeFinished = { upd(tts); TestLog.evt("tts_pref", "rate=${tts.rate}") },
        valueRange = -10f..10f, steps = 19, modifier = Modifier.fillMaxWidth(),
    )
    Text(L10n.s("音量 ${tts.volume}", "Volume ${tts.volume}"), color = UiTheme.p.textFaint, fontSize = 11.sp)
    Slider(
        value = tts.volume.toFloat(),
        onValueChange = { tts = tts.copy(volume = it.toInt()) },
        onValueChangeFinished = { upd(tts); TestLog.evt("tts_pref", "volume=${tts.volume}") },
        valueRange = 0f..100f, steps = 9, modifier = Modifier.fillMaxWidth(),
    )
    Text(L10n.s("段间停顿 ${tts.pauseMs} ms", "Pause ${tts.pauseMs} ms"), color = UiTheme.p.textFaint, fontSize = 11.sp)
    Slider(
        value = tts.pauseMs.toFloat(),
        onValueChange = { tts = tts.copy(pauseMs = (it.toInt() / 250) * 250) },
        onValueChangeFinished = { upd(tts); TestLog.evt("tts_pref", "pause=${tts.pauseMs}") },
        valueRange = 0f..2000f, steps = 7, modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(L10n.s("语音：", "Voice: "), color = UiTheme.p.textFaint, fontSize = 11.sp)
        TextButton(onClick = { upd(tts.copy(voice = "")); TestLog.evt("tts_pref", "voice=auto") }) {
            Text(L10n.s("自动", "Auto"), fontSize = 11.sp,
                color = if (tts.voice.isEmpty()) UiTheme.p.accent else UiTheme.p.textTertiary,
                fontWeight = if (tts.voice.isEmpty()) FontWeight.Bold else FontWeight.Normal)
        }
        for (v in voices) {
            TextButton(onClick = { upd(tts.copy(voice = v)); TestLog.evt("tts_pref", "voice=" + v.take(40)) }) {
                Text(v, fontSize = 11.sp, maxLines = 1,
                    color = if (tts.voice == v) UiTheme.p.accent else UiTheme.p.textTertiary,
                    fontWeight = if (tts.voice == v) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    if (voices.isEmpty()) {
        Text(L10n.s("未枚举到系统语音（仅 Windows 提供）。", "No system voices (Windows only)."),
            color = UiTheme.p.textFaintest, fontSize = 10.sp)
    }
    // ---- 生词文本替换（v0.52.0 对齐安卓 app-TextReplacement + 开关）：朗读/导出前套用 ----
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(L10n.s("文本替换", "Text replace"), color = UiTheme.p.textFaint, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { upd(tts.copy(replaceEnabled = !tts.replaceEnabled)); TestLog.evt("tts_pref", "replace=" + !tts.replaceEnabled) }) {
            Text(if (tts.replaceEnabled) L10n.s("开", "On") else L10n.s("关", "Off"), fontSize = 11.sp,
                color = if (tts.replaceEnabled) UiTheme.p.accent else UiTheme.p.textTertiary,
                fontWeight = if (tts.replaceEnabled) FontWeight.Bold else FontWeight.Normal)
        }
    }
    androidx.compose.material3.OutlinedTextField(
        value = tts.replacements,
        onValueChange = { v -> tts = tts.copy(replacements = v) },
        label = { Text(L10n.s("规则：每行一条 原文=>替换词（朗读/导出前生效）", "Rules: one per line, original=>replacement"), fontSize = 10.sp) },
        maxLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = {
        upd(tts)
        TestLog.evt("tts_pref", "replace_saved lines=" + tts.replacements.lines().count { it.contains("=>") })
    }) { Text(L10n.s("保存规则", "Save rules"), fontSize = 11.sp, color = UiTheme.p.accent) }
}

/** WebDAV 同步对话框（v0.45.0 对齐安卓 dialog_webdav_sync：地址/账号/密码可编辑、独立于网上书库服务器）。 */
@Composable
private fun WebDavBlock() {
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
    fun commit() { SyncPrefs.saveSyncUrl(url); SyncPrefs.saveSyncUser(user); SyncPrefs.saveSyncPass(pass) }
    fun buildSrc(): RemoteSource? = SyncPrefs.syncSource(url, user, pass)

    CheckRow(L10n.s("启用同步（阅读进度与书签）", "Enable sync (reading progress and bookmarks)"), enabledSync) { v ->
        enabledSync = v; LibPrefs.put("webdavSyncEnabled", v); TestLog.evt("pref_sync_enabled", "$v")
    }
    var compat by remember { mutableStateOf(LibPrefs.bool("webdavSyncCompat", true)) }
    CheckRow(L10n.s("与手机互通（安卓版格式，互相同步阅读进度与书签）", "Interoperate with the Android app (per-book progress and bookmarks)"), compat) { v ->
        compat = v; LibPrefs.put("webdavSyncCompat", v); TestLog.evt("pref_sync_compat", "$v")
    }
    OutlinedTextField(
        value = url, onValueChange = { url = it; commit() },
        label = { Text(L10n.s("WebDAV 地址", "WebDAV URL")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it; commit() },
        label = { Text(L10n.s("账号", "Login")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pass, onValueChange = { pass = it; commit() },
        label = { Text(L10n.s("密码", "Password")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    )
    var trustAll by remember { mutableStateOf(LibPrefs.bool("syncTrustAll", true)) }
    CheckRow(L10n.s("信任自签名证书", "Trust self-signed certificates"), trustAll) { v ->
        trustAll = v; LibPrefs.put("syncTrustAll", v); TestLog.evt("pref_sync_trust", "$v")
    }
    StaticRow(L10n.s("同步路径", "Sync path"), "HowRead-sync-desktop.json")
    StaticRow(L10n.s("进度冲突策略", "Conflict policy"), L10n.s("较新优先", "Newest wins"))
    val intervals = listOf(
        0 to L10n.s("关", "Off"), 5 to L10n.s("5分钟", "5m"), 15 to L10n.s("15分钟", "15m"), 30 to L10n.s("30分钟", "30m"),
        60 to L10n.s("1小时", "1h"), 180 to L10n.s("3小时", "3h"), 360 to L10n.s("6小时", "6h"), 1440 to L10n.s("每天", "Daily"),
    )
    var intervalMin by remember { mutableStateOf(SyncPrefs.loadIntervalMin()) }
    KeyValRow(L10n.s("定时同步", "Sync interval"),
        (intervals.firstOrNull { it.first == intervalMin }?.second ?: ("$intervalMin" + L10n.s("分钟", "m")))) { intervalDlg = true }
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
    if (testMsg.isNotEmpty()) Text(testMsg, color = UiTheme.p.textTertiary, fontSize = 12.sp)
    Text(
        "同步文件：服务器起始目录 HowRead-sync-desktop.json。合并规则：进度按时间新者胜；书签按 页+标题 去重、笔记互补；珍藏取并集。删除不会跨设备传播。",
        color = UiTheme.p.textFaintest, fontSize = 11.sp,
    )
    Text(L10n.s("同步配置独立于「我的文件」里的 WebDAV 服务器（桌面 WebDAV 客户端默认接受自签名证书）。",
            "Sync is independent of My Files servers (desktop WebDAV client accepts self-signed certs)."),
        color = UiTheme.p.textFaintest, fontSize = 10.sp)
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
private fun BackupBlock() {
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
    Text(
        "导出：把书库数据（阅读进度/书签/高亮/珍藏/统计）与全部配置打包为一个 zip；恢复：选择备份包，重启应用后生效。",
        color = UiTheme.p.textFaint, fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        Button(onClick = {
            val fc = javax.swing.JFileChooser()
            fc.dialogTitle = "导出备份"
            fc.selectedFile = java.io.File(System.getProperty("user.home"),
                "HowRead-Backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date()) + ".zip")
            if (fc.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                var f = fc.selectedFile
                if (!f.name.lowercase().endsWith(".zip")) f = java.io.File(f.parentFile, f.name + ".zip")
                backupMsg = runCatching { Backup.exportTo(f) }
                    .getOrElse { "导出失败：" + it }
                TestLog.evt("backup_export", f.name)
            }
        }) { Text(L10n.s("导出备份…", "Export backup…")) }
        OutlinedButton(onClick = { restorePicking = true }) { Text(L10n.s("从备份恢复…", "Restore from backup…")) }
    }
    if (backupMsg.isNotEmpty()) {
        Text(backupMsg, color = UiTheme.p.textSecondary, fontSize = 12.sp)
    }
}
