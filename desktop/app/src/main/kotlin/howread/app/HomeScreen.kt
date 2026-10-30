package howread.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import howread.core.TestLog
import howread.core.data.Db
import howread.core.data.LibStats
import howread.core.model.Book
import java.io.File
import java.text.SimpleDateFormat

/** 时长格式化（安卓同款：X小时Y分 / Y分钟）。 */
fun fmtDur(sec: Long): String {
    val m = sec / 60
    return if (L10n.isEn()) {
        if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    } else {
        if (m >= 60) "${m / 60}小时${m % 60}分" else "${m}分钟"
    }
}

/** 统计小框（书籍总数/已读/今日/速度）。 */
@Composable
fun StatSmall(num: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.height(54.dp).clip(RoundedCornerShape(10.dp)).background(UiTheme.p.panel)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(num, color = UiTheme.p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label + if (onClick != null) " ›" else "", color = UiTheme.p.textFaint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 统计区整块（安卓真机同款：左侧 2×2 + 右侧跨行总时长大卡）。 */
@Composable
fun StatsGrid(stats: LibStats, modifier: Modifier = Modifier, onTotal: (() -> Unit)? = null, onRead: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(2.2f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatSmall("${stats.books}", L10n.s("书籍总数", "Books"), Modifier.weight(1f))
                StatSmall("${stats.readBooks}", L10n.s("已读书籍", "Read"), Modifier.weight(1f), onClick = onRead)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatSmall(fmtDur(stats.todaySeconds), L10n.s("今日阅读", "Today"), Modifier.weight(1f))
                StatSmall("%.1f".format(stats.speedPerPageMin), L10n.s("阅读速度(页/分)", "Pages/min"), Modifier.weight(1f))
            }
        }
        Column(
            Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(10.dp)).background(UiTheme.p.panel)
                .clickable(enabled = onTotal != null) { onTotal?.invoke() }
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(fmtDur(stats.totalSeconds), color = UiTheme.p.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(L10n.s("总阅读时间", "Total") + if (onTotal != null) " ›" else "", color = UiTheme.p.textFaint, fontSize = 10.sp)
        }
    }
}

/** 圆形图标条目（真机"我的文件/网上书库"区块同款形态）。 */
@Composable
fun CircleItem(icon: ImageVector, name: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.width(74.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(UiTheme.p.card),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = name, tint = tint, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(name, color = UiTheme.p.textTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 首页（对齐安卓真机 P30）：六个区块从上到下——
 * 最近阅读 / 书签笔记 / 我的珍藏 / 阅读统计 / 我的文件 / 网上书库；
 * 每区块 = 标题行（右侧更多）+ 横向滚动条目。
 */
@Composable
fun HomeScreen(appState: AppState) {
    val books = appState.library
    val recent = remember(books) { books.filter { it.lastReadAt > 0 }.sortedByDescending { it.lastReadAt } }
    val favorites = remember(books) { books.filter { it.isFavorite }.sortedByDescending { it.lastReadAt } }
    val bookmarks = remember { Db.allBookmarks(12) }
    var statsTick by remember { mutableStateOf(0) }
    val stats = remember(statsTick) { Db.stats() }
    val servers = remember { Db.listRemoteSources() }
    val fileRoots = remember {
        val list = ArrayList<String>()
        for (d in Db.listScanDirs()) list.add(L10n.s("书库 · ", "Lib · ") + File(d).name)
        val home = File(System.getProperty("user.home"))
        for ((label, f) in listOf(
            L10n.s("主目录", "Home") to home, L10n.s("下载", "Downloads") to File(home, "Downloads"),
            L10n.s("文档", "Documents") to File(home, "Documents"), L10n.s("桌面", "Desktop") to File(home, "Desktop"),
        )) {
            if (f.isDirectory) list.add(label)
        }
        list
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm") }
    var statsGear by remember { mutableStateOf(false) }
    var statsTimeDlg by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(L10n.s("首页", "Home"), color = UiTheme.p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        // ---- 1 最近阅读 ----
        item { SectionHeader(L10n.s("最近阅读", "Recent"), L10n.s("共 ${recent.size} 本", "${recent.size} books")) { appState.statusFilter = BookStatus.READING; appState.sortKey = SortKey.RECENT; appState.goto(MainTab.LIBRARY) } }
        item {
            if (recent.isEmpty()) Empty(L10n.s("还没有开始阅读——从书库或我的文件打开一本书", "Nothing read yet — open a book from the library or My Files"))
            else CoverRow(appState, recent.take(12), "home_recent_open")
        }

        // ---- 2 书签笔记（横条卡） ----
        item { SectionHeader(L10n.s("书签笔记", "Notes"), L10n.s("共 ${stats.bookmarks} 条", "${stats.bookmarks} items")) { appState.extra = ExtraContent.BOOKMARKS } }
        item {
            if (bookmarks.isEmpty()) Empty(L10n.s("还没有书签——阅读器中点「书签」即可添加", "No bookmarks yet — tap 「书签」 in the reader"))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(bookmarks, key = { it.first.id }) { (bm, title) ->
                    val prog = remember(bm.path) { Db.loadProgress(bm.path) }
                    val pct = if (prog != null && prog.pageCount > 0) (prog.page + 1) * 100 / prog.pageCount else 0
                    Row(
                        Modifier.width(216.dp).clip(RoundedCornerShape(10.dp)).background(UiTheme.p.panel)
                            .clickable {
                                TestLog.evt("home_bm_jump", "page=" + (bm.page + 1))
                                appState.openAt(bm.path, bm.page)
                            }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(42.dp).aspectRatio(0.72f).background(UiTheme.p.card)) {
                            BookCover(path = bm.path, title = title, mtime = 0L, modifier = Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = UiTheme.p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                bm.note.ifBlank { bm.label + " " + fmt.format(java.util.Date(bm.createdAt)) },
                                color = UiTheme.p.textTertiary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(L10n.s("$pct% · 第 ${bm.page + 1} 页", "$pct% · p${bm.page + 1}"), color = UiTheme.p.textFaintest, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // ---- 3 我的珍藏 ----
        item { SectionHeader(L10n.s("我的珍藏", "Favorites"), L10n.s("共 ${favorites.size} 本", "${favorites.size} books")) { appState.extra = ExtraContent.FAVORITES } }
        item {
            if (favorites.isEmpty()) Empty(L10n.s("书库封面右上角 ☆ 点亮即可收藏", "Tap ☆ on a cover to favorite"))
            else CoverRow(appState, favorites.take(12), "home_fav_open")
        }

        // ---- 4 阅读统计（2×2 + 总时长大卡，安卓真机同款） ----
        item { SectionHeader(L10n.s("阅读统计", "Stats"), L10n.s("${stats.books} 本在册", "${stats.books} books"), gear = { TestLog.evt("stats_gear"); statsGear = true }) }
        item {
            StatsGrid(
                stats,
                onTotal = { TestLog.evt("home_stats_total_open"); statsTimeDlg = true },
                onRead = {
                    TestLog.evt("home_stats_read_jump", "n=" + stats.readBooks)
                    appState.statusFilter = BookStatus.FINISHED
                    appState.goto(MainTab.LIBRARY)
                },
            )
        }

        // ---- 5 我的文件（圆形图标条目，真机同款） ----
        item { SectionHeader(L10n.s("我的文件", "My Files"), L10n.s("本地浏览", "Local")) { appState.goto(MainTab.BROWSE) } }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fileRoots.size) { i ->
                    CircleItem(Icons.Filled.Folder, fileRoots[i], UiTheme.p.accentTeal) {
                        TestLog.evt("home_files", fileRoots[i])
                        appState.goto(MainTab.BROWSE)
                    }
                }
            }
        }

        // ---- 6 网上书库（圆形图标条目，真机同款） ----
        item { SectionHeader(L10n.s("网上书库", "Network"), if (servers.isEmpty()) L10n.s("未配置", "Not set") else L10n.s("${servers.size} 个服务器", "${servers.size} servers")) { appState.extra = ExtraContent.REMOTE } }
        item {
            if (servers.isEmpty()) {
                Empty(L10n.s("添加 WebDAV/SMB/SFTP 服务器，家里的书直接读", "Add a WebDAV/SMB/SFTP server to read your books"))
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers, key = { it.id }) { s ->
                        val icon: ImageVector = when (s.protocol.uppercase()) {
                            "WEBDAV" -> Icons.Filled.Cloud
                            "SMB" -> Icons.Filled.Lan
                            else -> Icons.Filled.VpnKey
                        }
                        CircleItem(icon, s.name, UiTheme.p.accentTeal) {
                            TestLog.evt("home_server", s.protocol + " " + s.name)
                            appState.extra = ExtraContent.REMOTE
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
    if (statsGear) ClearStatsDialog(onDismiss = { statsGear = false; statsTick++ })
    if (statsTimeDlg) ReadingTimeDialog(onDismiss = { statsTimeDlg = false })
        // FAB（安卓同款）：右下角继续阅读（最近一本；无最近阅读则去书库）
        Box(
            Modifier.align(Alignment.BottomEnd).padding(22.dp).size(56.dp)
                .clip(CircleShape).background(Color(0xFF151515)).clickable {
                    val last = recent.firstOrNull()
                    if (last != null) {
                        TestLog.evt("home_fab", "name=" + last.title)
                        appState.open(last.path)
                    } else {
                        TestLog.evt("home_fab", "empty->library")
                        appState.goto(MainTab.LIBRARY)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MenuBook, contentDescription = L10n.s("继续阅读", "Continue"),
                tint = Color.White, modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String, gear: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = UiTheme.p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(sub, color = UiTheme.p.textFaintest, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (gear != null) {
            Icon(
                Icons.Filled.Settings, contentDescription = L10n.s("阅读统计配置", "Stats settings"),
                tint = UiTheme.p.textFaint,
                modifier = Modifier.size(16.dp).clickable { gear() },
            )
            Spacer(Modifier.width(6.dp))
        }
        if (onMore != null) {
            Text(L10n.s("更多", "More") + " ›", color = UiTheme.p.textFaint, fontSize = 12.sp,
                modifier = Modifier.clickable { onMore() })
        }
    }
}

/** 清空阅读统计（阅读统计区齿轮；按钮两次点击确认，v0.46.0）。 */
@Composable
private fun ClearStatsDialog(onDismiss: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(400.dp).clip(RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L10n.s("清空阅读统计", "Clear statistics"), color = UiTheme.p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    L10n.s("将删除全部阅读时长与阅读页数统计（各书进度、书签、珍藏不受影响）。此操作不可恢复。",
                        "Deletes all reading time and page statistics (progress, bookmarks and favorites are kept). This cannot be undone."),
                    color = UiTheme.p.textSecondary, fontSize = 12.sp,
                )
                if (armed) {
                    Text(L10n.s("请再次点击「确认清空」执行。", "Click the confirm button once more to proceed."),
                        color = Color(0xFFD32F2F), fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(L10n.s("取消", "Cancel")) }
                    Button(onClick = {
                        if (!armed) {
                            armed = true
                            TestLog.evt("stats_clear_arm")
                        } else {
                            Db.clearReadStats()
                            TestLog.evt("stats_cleared")
                            onDismiss()
                        }
                    }) {
                        Text(
                            if (armed) L10n.s("确认清空", "Confirm clear") else L10n.s("清空统计", "Clear stats"),
                            color = if (armed) Color(0xFFD32F2F) else Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = UiTheme.p.textFaint, fontSize = 12.sp)
}

/** 封面横排（最近阅读/我的珍藏共用）。 */
@Composable
private fun CoverRow(appState: AppState, list: List<Book>, evt: String) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.path }) { b ->
            Column(
                Modifier.width(96.dp).clickable {
                    TestLog.evt(evt, "name=" + b.title)
                    appState.open(b.path)
                },
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(UiTheme.p.card)) {
                    BookCover(path = b.path, title = b.title, mtime = b.mtime, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    b.title, color = UiTheme.p.textSecondary, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
