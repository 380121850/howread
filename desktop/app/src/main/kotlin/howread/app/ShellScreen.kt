package howread.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import howread.core.Quotes
import howread.core.TestLog
import howread.core.data.Db
import howread.core.sync.SyncEngine
import howread.core.sync.SyncPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

private fun mainTabIcon(t: MainTab): ImageVector = when (t) {
    MainTab.HOME -> Icons.Filled.Home
    MainTab.LIBRARY -> Icons.Filled.LocalLibrary
    MainTab.BROWSE -> Icons.Filled.FolderOpen
    MainTab.PREFS -> Icons.Filled.Settings
}

/**
 * 主窗口外壳（对齐安卓 APP 布局）：左侧常驻侧边栏（图标导航行，安卓抽屉同款
 * 图标+文字样式）+ 内容区 + 底部标签栏（图标+文字）。
 */
@Composable
fun ShellScreen(appState: AppState, window: ComposeWindow) {
    // 任务栏/标题栏图标（v0.30.0 白块修复）：直接对 AWT 窗口 setIconImages——任务栏图标取自窗口 ICON
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching {
            val img = javax.imageio.ImageIO.read(Res::class.java.classLoader.getResource("howread_icon.png"))
            if (img != null) {
                window.setIconImages(listOf(img, img))
                TestLog.evt("window_icon", "ok")
            } else {
                TestLog.evt("window_icon", "resource_missing")
            }
        }.onFailure { TestLog.evt("window_icon_fail", "$it") }
    }
    MaterialTheme(colorScheme = if (UiTheme.dark) darkColorScheme() else lightColorScheme()) {
        LaunchedEffect(Unit) {
            if (LibPrefs.bool("scanAtLaunch", true)) appState.rescan()
            if (LibPrefs.bool("openLastOnStart", false)) {
                val last = Db.allBooks().filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }
                if (last != null) {
                    TestLog.evt("open_last_book", last.title)
                    appState.open(last.path)
                }
            }
        }
        // 周期自动同步（偏好页设置间隔；每分钟检查一次到期与否）
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                runCatching {
                    if (!LibPrefs.bool("webdavSyncEnabled", true)) return@runCatching
                    val interval = SyncPrefs.loadIntervalMin()
                    if (interval <= 0) return@runCatching
                    if (System.currentTimeMillis() - SyncPrefs.loadLastSyncAt() < interval * 60_000L) return@runCatching
                    val src = SyncPrefs.syncSource(
                        SyncPrefs.loadSyncUrl(""), SyncPrefs.loadSyncUser(""), SyncPrefs.loadSyncPass(""),
                        SyncPrefs.loadSyncDir("/HowRead")
                    ) ?: return@runCatching
                    withContext(Dispatchers.IO) {
                        if (LibPrefs.bool("webdavSyncCompat", true)) {
                            howread.core.sync.AndroidSync.onPrefsImported = { LibPrefs.invalidate() }
                            val ar = howread.core.sync.AndroidSync.sync(src, LibPrefs.str("webdavSyncPolicy", "newer"))
                            TestLog.evt("sync_auto", "compat ok=" + ar.ok + " pDown=" + ar.progressDown + " bDown=" + ar.bookmarksDown + " synced=" + ar.booksSynced)
                            if (!ar.ok) TestLog.evt("sync_auto_fail", ar.error.take(80))
                        } else {
                            val sr = SyncEngine.sync(src)
                            if (sr.ok) TestLog.evt("sync_auto", "ok cfg=" + sr.configsAdopted)
                            else if (sr.error != "busy") TestLog.evt("sync_auto_fail", sr.error)
                        }
                    }
                }
            }
        }
        // 侧栏底部「软件说明」弹窗（v0.55.0 侧栏底部=软件说明+夜间模式，取代版本号行）
        var sideAboutOpen by remember { mutableStateOf(false) }
        if (sideAboutOpen) AboutDialog(onDismiss = { sideAboutOpen = false })
        // 记住上次的搜索（v0.56 补持久化半边：AppState 启动时回填 lastQuery，这里把搜索词落盘）
        LaunchedEffect(Unit) {
            snapshotFlow { appState.query }
                .drop(1)
                .collectLatest { q ->
                    if (!LibPrefs.bool("rememberSearch", false)) return@collectLatest
                    delay(600)
                    LibPrefs.put("lastQuery", q)
                    TestLog.evt("last_query_save", q.take(40))
                }
        }
        // 书籍打开：阅读器直接占据主窗口（不另开窗口）；关闭/Esc 返回原页面
        val rb = appState.readerBook
        LaunchedEffect(rb) { if (rb == null) appState.reloadLibrary() }
        if (rb != null) {
            ReaderScreen(
                rb.path, rb.page,
                onClose = {
                    TestLog.evt("reader_close")
                    appState.closeReader()
                },
                onOpenBook = { appState.open(it) },
            )
            return@MaterialTheme
        }
        Row(Modifier.fillMaxSize().background(UiTheme.p.bg)) {
            // ---- 侧边栏（安卓抽屉行样式：图标 22dp + 文字，行高 44dp） ----
            Column(
                Modifier.width(204.dp).fillMaxHeight().background(UiTheme.p.panel)
                    .padding(horizontal = 10.dp, vertical = 14.dp),
            ) {
                // 侧栏头部横幅（安卓抽屉同款：日/夜背景图 + 居中标语，随主题切换）
                Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp))) {
                    val banner = remember(UiTheme.dark) { Res.bitmap(if (UiTheme.dark) "banner_night.jpg" else "banner_day.jpg") }
                    LaunchedEffect(banner) { TestLog.evt("side_banner", "ok=" + (banner != null)) }
                    if (banner != null) {
                        Image(banner, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(UiTheme.p.bar))
                    }
                    Text(
                        "值得读，好好读",
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(shadow = Shadow(Color(0x88000000), Offset(0f, 1f), blurRadius = 4f)),
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 侧栏导航项（对齐安卓抽屉条目集；AI/统计入口在偏好页与首页区块）
                SideItem(L10n.s("最近阅读", "Recent"), Icons.Filled.History,
                    appState.mainTab == MainTab.LIBRARY && appState.extra == ExtraContent.NONE && appState.statusFilter == BookStatus.READING) {
                    TestLog.evt("nav_side", "recent")
                    appState.statusFilter = BookStatus.READING
                    appState.goto(MainTab.LIBRARY)
                }
                SideItem(L10n.s("我的书架", "My Shelf"), Icons.Filled.LocalLibrary,
                    appState.mainTab == MainTab.LIBRARY && appState.extra == ExtraContent.NONE && appState.statusFilter == BookStatus.ALL) {
                    TestLog.evt("nav_side", "shelf")
                    appState.statusFilter = BookStatus.ALL
                    appState.goto(MainTab.LIBRARY)
                }
                SideItem(L10n.s("我的文件", "My Files"), Icons.Filled.FolderOpen,
                    appState.mainTab == MainTab.BROWSE && appState.extra == ExtraContent.NONE) {
                    TestLog.evt("nav_side", "files")
                    appState.goto(MainTab.BROWSE)
                }
                SideItem(L10n.s("网上书库", "Network"), Icons.Filled.Public, appState.extra == ExtraContent.REMOTE) {
                    TestLog.evt("nav_side", "net")
                    appState.extra = ExtraContent.REMOTE
                }
                SideItem(L10n.s("书签笔记", "Notes"), Icons.Filled.Bookmark, appState.extra == ExtraContent.BOOKMARKS) {
                    TestLog.evt("nav_side", "bookmarks")
                    appState.extra = ExtraContent.BOOKMARKS
                }
                // 标签分组（v0.51.0 对齐安卓侧栏 Tags2）：点击=书库按该标签过滤，再点取消
                val tagList = remember(appState.library) { runCatching { Db.listTags() }.getOrDefault(emptyList()) }
                if (tagList.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(L10n.s("标签", "Tags"), color = UiTheme.p.textFaintest, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                    for ((tg, cnt) in tagList) {
                        SideItem(tg + " · " + cnt, Icons.Filled.Bookmark, appState.tagFilter == tg) {
                            TestLog.evt("nav_tag", tg)
                            appState.tagFilter = if (appState.tagFilter == tg) "" else tg
                            appState.goto(MainTab.LIBRARY)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // 侧栏格言（安卓抽屉格言条同款：随机一条，出处跟随）
                val quote = remember { Quotes.random() }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("「" + quote.first + "」", color = UiTheme.p.textTertiary, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("—— " + quote.second, color = UiTheme.p.textFaintest, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                if (appState.scanning) {
                    Text(L10n.s("扫描书库中…", "Scanning…"), color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                }
                // 侧栏底部两功能（v0.55.0 对齐安卓抽屉底部：软件说明 + 夜间模式，不再显示版本号）
                SideItem(L10n.s("软件说明", "About"), Icons.Filled.Info, false) {
                    TestLog.evt("side_about")
                    sideAboutOpen = true
                }
                SideItem(
                    if (UiTheme.dark) L10n.s("白天模式", "Day mode") else L10n.s("夜间模式", "Night mode"),
                    if (UiTheme.dark) Icons.Filled.WbSunny else Icons.Filled.DarkMode,
                    false,
                ) {
                    TestLog.evt("side_night", if (UiTheme.dark) "to_day" else "to_night")
                    UiTheme.applyDark(!UiTheme.dark)
                }
            }

            // ---- 内容区 + 底部标签栏 ----
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        appState.extra == ExtraContent.REMOTE ->
                            RemoteScreen(appState, onClose = { appState.goto(MainTab.HOME) })
                        appState.extra == ExtraContent.BOOKMARKS -> BookmarksPage(appState)
                        appState.extra == ExtraContent.FAVORITES -> FavoritesPage(appState)
                        appState.mainTab == MainTab.HOME -> HomeScreen(appState)
                        appState.mainTab == MainTab.LIBRARY -> LibraryGrid(appState)
                        appState.mainTab == MainTab.BROWSE -> BrowseScreen(appState)
                        appState.mainTab == MainTab.PREFS -> PrefsScreen(appState, window)
                    }
                }
                // 底部标签栏（图标 + 文字，安卓同款）——用户裁定保留（2026-10-31）
                Row(Modifier.fillMaxWidth().height(56.dp).background(UiTheme.p.panel)) {
                    for (t in MainTab.entries) {
                        val active = appState.mainTab == t && appState.extra == ExtraContent.NONE
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clickable {
                                TestLog.evt("tab_click", t.name)
                                appState.goto(t)
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                mainTabIcon(t), contentDescription = L10n.s(t.zh, t.en),
                                tint = if (active) UiTheme.p.accent else UiTheme.p.textTertiary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                L10n.s(t.zh, t.en),
                                color = if (active) UiTheme.p.accent else UiTheme.p.textTertiary,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 侧栏导航行（对齐安卓 addDrawerNavRow：图标 + 文字，整行可点）。 */
@Composable
private fun SideItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (active) UiTheme.p.rowActive else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (active) UiTheme.p.accent else UiTheme.p.textTertiary, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = if (active) UiTheme.p.accent else UiTheme.p.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

