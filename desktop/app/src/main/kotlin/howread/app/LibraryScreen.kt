package howread.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import howread.core.FormatRouter
import howread.core.TestLog
import howread.core.data.Db
import howread.core.model.Book
import howread.engine.DocumentHandle
import howread.engine.EngineLib
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** 全文搜索命中（跨书）。 */
private data class FullHit(val path: String, val title: String, val page: Int, val excerpt: String)

/** 多书全文搜索：逐本引擎检索，命中给页码+摘录，点击直达该书该页。 */
@Composable
private fun FullTextSearchDialog(appState: AppState, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scannedBooks by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf<List<FullHit>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun runAll(q: String) {
        if (q.isBlank()) return
        job?.cancel()
        hits = emptyList()
        scannedBooks = 0
        busy = true
        TestLog.evt("fulltext_begin", "q=$q books=${appState.library.size}")
        val books = appState.library.toList()
        job = scope.launch {
            EngineLib.ensure()
            for (b in books) {
                if (!isActive) break
                withContext(Dispatchers.IO) {
                    runCatching {
                        val h = DocumentHandle(FormatRouter.openablePath(b.path))
                        try {
                            val pc = h.pageCount()
                            for (p in 0 until minOf(pc, 4000)) {
                                if (h.searchPage(p, q) > 0) {
                                    val paras = runCatching { h.pageParagraphs(p) }.getOrDefault(emptyList())
                                    val ex = paras.firstOrNull { it.contains(q, ignoreCase = true) } ?: ""
                                    hits = hits + FullHit(b.path, b.title, p, ex.take(140))
                                    if (hits.size >= 150) return@runCatching
                                }
                            }
                        } finally {
                            h.close()
                        }
                    }
                }
                scannedBooks++
                if (hits.size >= 150) break
            }
            busy = false
            TestLog.evt("fulltext_done", "hits=${hits.size} books=$scannedBooks")
        }
    }

    Dialog(onDismissRequest = { job?.cancel(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp)) {
                Text(L10n.s("全文搜索（书库 ${appState.library.size} 本）", "Full-text search (${appState.library.size} books)"), color = UiTheme.p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text(L10n.s("关键词", "Keyword")) }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { runAll(query.trim()) }, enabled = !busy && query.isNotBlank()) {
                        Text(L10n.s("搜索", "Search"), color = UiTheme.p.accent)
                    }
                    TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(L10n.s("关闭", "Close")) }
                }
                if (busy) {
                    Text(L10n.s("检索中 $scannedBooks / ${appState.library.size} 本 · 命中 ${hits.size}", "$scannedBooks / ${appState.library.size} books · ${hits.size} hits"),
                        color = UiTheme.p.textFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                } else if (hits.isEmpty()) {
                    Text(L10n.s("输入关键词后点击搜索；结果含书名/页码/上下文摘录，点击直达。", "Type a keyword and search; results show book/page/context, click to jump."),
                        color = UiTheme.p.textFaint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
                LazyColumn {
                    items(hits, key = { it.path + "#" + it.page + "#" + it.excerpt.hashCode() }) { h ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                TestLog.evt("fulltext_jump", "page=${h.page + 1}")
                                job?.cancel()
                                onDismiss()
                                appState.openAt(h.path, h.page)
                            }.padding(vertical = 6.dp),
                        ) {
                            Text(L10n.s("${h.title} · 第 ${h.page + 1} 页", "${h.title} · p${h.page + 1}"), color = UiTheme.p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            if (h.excerpt.isNotBlank()) {
                                Text(h.excerpt, color = UiTheme.p.textTertiary, fontSize = 12.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 在资源管理器中显示该文件（Windows /select 定位；Linux 打开父目录）。 */
fun revealInExplorer(path: String) {
    try {
        if (System.getProperty("os.name").lowercase().contains("win"))
            Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", path))
        else Runtime.getRuntime().exec(arrayOf("xdg-open", File(path).parent ?: "."))
    } catch (_: Exception) {
    }
}

/** 移出一本书（清全部数据，不删文件）。 */
private fun removeBookData(b: Book, appState: AppState) {
    Db.removeBook(b.path)
    appState.selected = appState.selected - b.path
    appState.reloadLibrary()
    TestLog.evt("lib_remove", "name=" + b.title)
}

/**
 * 书库页：搜索 + 阅读状态过滤（全部/未读/在读/已读，v0.36.0 与工具行并一行）+ 排序 + 网格/列表双视图 +
 * 右键(长按)上下文菜单 + 多选批量（收藏/移出书库）。
 */
@Composable
fun LibraryGrid(appState: AppState) {
    var infoFor by remember { mutableStateOf<Book?>(null) }
    var tagFor by remember { mutableStateOf<Book?>(null) }
    var introFor by remember { mutableStateOf<Book?>(null) }
    var introText by remember { mutableStateOf("") }
    var introBusy by remember { mutableStateOf(false) }

    /** AI 简介（v0.51.0 对齐安卓 AiSummarize）：首页正文节选喂大模型，产出≤150字简介，后台线程生成。 */
    fun startIntro(b: Book) {
        introFor = b; introBusy = true; introText = ""
        TestLog.evt("ai_intro", b.title.take(30))
        Thread {
            val sb = StringBuilder()
            runCatching {
                val openable = runCatching { howread.core.FormatRouter.openablePath(b.path) }.getOrDefault(b.path)
                val d = howread.engine.DocumentHandle(openable)
                try { for (pa in d.pageParagraphs(0)) sb.append(pa).append('\n') } finally { runCatching { d.close() } }
            }
            val cfg = howread.core.ai.AiConfigStore.load()
            if (!howread.core.ai.AiConfigStore.configured(cfg)) {
                introBusy = false
                introText = L10n.s("先在「偏好 → AI 大模型」配置 API Key 与模型。", "Configure the AI model in Prefs first.")
                return@Thread
            }
            val prompt = "请为下面这本书写一段不超过150字的中文内容简介；直接输出简介正文，不要标题、不要客套：\n书名：《" +
                b.title + "》" + (if (b.author.isNotBlank()) "，作者：" + b.author else "") +
                "\n首页内容节选：\n" + sb.toString().trim().take(1200).ifEmpty { "（无可提取文本，请按书名与作者的常识撰写）" }
            val r = howread.core.ai.AiClient.chat(cfg, prompt, 800)
            introBusy = false
            introText = if (r.ok) r.reply.trim() else L10n.s("生成失败：", "Failed: ") + r.error
        }.apply { isDaemon = true }.start()
    }
    var fulls by remember { mutableStateOf(false) }
    var foldersDlg by remember { mutableStateOf(false) }
    var delFor by remember { mutableStateOf<Book?>(null) }
    var deleteHint by remember { mutableStateOf("") }   // 检视修复：删除失败反馈

    // 网络书缓存角标（v0.36.0）：有远程书时周期重算（读块位图为轻量 IO，2s 一次足够）
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (appState.library.any { it.path.startsWith("remote://") }) appState.cacheTick++
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = appState.query,
                onValueChange = { appState.query = it },
                label = { Text(L10n.s("搜索书名 / 路径", "Search title / path")) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(L10n.s("${appState.visibleBooks().size} 本", "${appState.visibleBooks().size} books"), color = UiTheme.p.textFaint, fontSize = 13.sp)
        }
        // ---- 单行工具条（v0.36.0）：阅读状态过滤（全部/未读/在读/已读，取代原 全部/最近/收藏 tab 行）
        //      + 全文搜索 + 排序 + 视图 + 添加书籍 + 多选，两行并一行 ----
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for (st in BookStatus.entries) {
                val selected = appState.statusFilter == st
                TextButton(onClick = { appState.statusFilter = st; TestLog.evt("lib_status", st.name) }) {
                    Text(
                        L10n.s(st.zh, st.en),
                        color = if (selected) UiTheme.p.accent else UiTheme.p.textTertiary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                    )
                }
            }
            TextButton(onClick = { fulls = true; TestLog.evt("fulltext_open") }) {
                Text(L10n.s("全文搜索", "Full-text"), color = UiTheme.p.accent, fontSize = 12.sp)
            }
            // 格式过滤 chips（v0.50.0 安卓 ExtFilter 等价）：仅列出书库实际出现的扩展名，多选叠加
            val presentExts = remember(appState.library) {
                appState.library.map { it.path.substringAfterLast('.', "").lowercase() }
                    .filter { it.isNotEmpty() && !it.contains('/') && !it.contains(':') && it.length <= 5 }
                    .distinct().sorted()
            }
            for (fx in presentExts) {
                val onExt = fx in appState.extFilter
                TextButton(onClick = {
                    appState.extFilter = if (onExt) appState.extFilter - fx else appState.extFilter + fx
                    TestLog.evt("lib_ext_filter", appState.extFilter.sorted().joinToString(","))
                }) {
                    Text(fx, color = if (onExt) UiTheme.p.accent else UiTheme.p.textFaint, fontSize = 11.sp,
                        fontWeight = if (onExt) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Text(L10n.s("排序: ", "Sort: ") + L10n.s(appState.sortKey.zh, appState.sortKey.en), color = UiTheme.p.textTertiary, fontSize = 12.sp)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for (sk in listOf(SortKey.TITLE, SortKey.SIZE, SortKey.DATE, SortKey.PAGES, SortKey.LANG, SortKey.RECENT, SortKey.FORMAT, SortKey.AUTHOR, SortKey.SERIES)) {
                        DropdownMenuItem(
                            text = { Text(L10n.s(sk.zh, sk.en), color = if (appState.sortKey == sk) UiTheme.p.accent else UiTheme.p.textSecondary, fontSize = 13.sp) },
                            onClick = {
                                appState.sortKey = sk
                                LibPrefs.put("defaultSort", sk.name)
                                sortMenu = false; TestLog.evt("lib_sort", sk.name)
                            },
                        )
                    }
                }
            }
            var viewMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { viewMenu = true }) {
                    Text(L10n.s("视图: ", "View: ") + L10n.s(appState.libView.zh, appState.libView.en), color = UiTheme.p.textTertiary, fontSize = 12.sp)
                }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    for (v in LibView.entries) {
                        DropdownMenuItem(
                            text = { Text(L10n.s(v.zh, v.en), color = if (appState.libView == v) UiTheme.p.accent else UiTheme.p.textSecondary, fontSize = 13.sp) },
                            onClick = {
                                appState.libView = v
                                LibPrefs.put("defaultView", v.name)
                                viewMenu = false; TestLog.evt("lib_view", v.name)
                            },
                        )
                    }
                }
            }
            // 书库文件夹配置（v0.36.0：＋号旁加"添加书籍"字样）
            TextButton(onClick = { foldersDlg = true; TestLog.evt("lib_folders_open") }) {
                Icon(Icons.Filled.Add, contentDescription = L10n.s("书库文件夹配置", "Shelf folders"),
                    tint = UiTheme.p.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(L10n.s("添加书籍", "Add books"), color = UiTheme.p.accent, fontSize = 12.sp)
            }
            TextButton(onClick = {
                appState.selectionMode = !appState.selectionMode
                if (!appState.selectionMode) appState.selected = emptySet()
                TestLog.evt("lib_selmode", if (appState.selectionMode) "on" else "off")
            }) {
                Text(
                    if (appState.selectionMode) L10n.s("退出多选", "Done") else L10n.s("多选", "Select"),
                    color = if (appState.selectionMode) UiTheme.p.accent else UiTheme.p.textTertiary, fontSize = 12.sp,
                )
            }
        }
        if (appState.selectionMode) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L10n.s("已选 ${appState.selected.size} 本", "${appState.selected.size} selected"), color = UiTheme.p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    appState.selected = appState.visibleBooks().map { it.path }.toSet()
                }) { Text(L10n.s("全选", "All"), fontSize = 12.sp) }
                TextButton(onClick = {
                    for (p in appState.selected) Db.setFavorite(p, true)
                    TestLog.evt("lib_batch_fav", "n=" + appState.selected.size)
                    appState.reloadLibrary()
                }) { Text(L10n.s("批量收藏", "Favorite"), fontSize = 12.sp, color = UiTheme.p.gold) }
                TextButton(onClick = {
                    for (p in appState.selected) Db.removeBook(p)
                    TestLog.evt("lib_batch_remove", "n=" + appState.selected.size)
                    appState.selected = emptySet()
                    appState.reloadLibrary()
                }) { Text(L10n.s("移出书库", "Remove"), fontSize = 12.sp, color = UiTheme.p.danger) }
            }
        }
        val books = appState.visibleBooks()
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (appState.library.isEmpty()) L10n.s("书库为空——到「偏好」添加书库目录，或从「我的文件」浏览打开", "Library empty — add a folder in Prefs or open from My Files")
                    else L10n.s("没有匹配的书籍", "No matching books"),
                    color = UiTheme.p.textTertiary, fontSize = 14.sp,
                )
            }
        } else when (appState.libView) {
            LibView.LIST -> LazyColumn {
                items(books, key = { it.path }) { b ->
                    BookRow(b, appState, onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                        onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
                }
            }
            LibView.TABLE -> LazyColumn {
                items(books, key = { it.path }) { b ->
                    BookTable(b, appState, onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                        onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
                }
            }
            LibView.COVER -> LazyVerticalGrid(
                columns = if (gridColumns() > 0) GridCells.Fixed(gridColumns()) else GridCells.Adaptive((gridCoverDp() + 80).dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                gridItems(books, key = { it.path }) { b ->
                    BookCard(b, appState, big = true, modifier = Modifier.fillMaxWidth(), onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                        onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
                }
            }
            LibView.SHELF -> ShelfView(books, appState, onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
            LibView.GROUP_AUTHOR, LibView.GROUP_SERIES, LibView.GROUP_LANG, LibView.GROUP_FORMAT -> {
                // 分组视图（v0.51.0 对齐安卓 groupingByAuthor/Series/Lang/Format）：组头=组名·数量，组内行式复用 BookRow
                val grouped = remember(books, appState.libView) {
                    val cjk = { t: String -> t.any { it.code in 0x4E00..0x9FFF } }
                    val keyOf: (Book) -> String = when (appState.libView) {
                        LibView.GROUP_AUTHOR -> { b -> b.author.ifBlank { L10n.s("未知作者", "Unknown") } }
                        LibView.GROUP_SERIES -> { b -> b.series.ifBlank { L10n.s("无系列", "No series") } }
                        LibView.GROUP_LANG -> { b -> if (cjk(b.title) || cjk(b.author)) L10n.s("中文", "Chinese") else L10n.s("其他", "Other") }
                        else -> { b -> b.path.substringAfterLast('.', "").uppercase().ifBlank { "?" } }
                    }
                    books.groupBy(keyOf).toSortedMap()
                }
                LazyColumn {
                    for ((gk, gl) in grouped) {
                        item(key = "g:" + gk) {
                            Text(gk + " · " + gl.size, color = UiTheme.p.textTertiary, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().background(UiTheme.p.card).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                        items(gl, key = { "r:" + it.path }) { b ->
                            BookRow(b, appState, onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                                onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
                        }
                    }
                }
            }
            else -> LazyVerticalGrid(
                columns = if (gridColumns() > 0) GridCells.Fixed(gridColumns()) else GridCells.Adaptive(gridCoverDp().dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(books, key = { it.path }) { b ->
                    BookCard(b, appState, modifier = Modifier.fillMaxWidth(), onAskDelete = { deleteHint = ""; delFor = it }, onAskInfo = { infoFor = it },
                        onSetTag = { tagFor = it }, onAskIntro = { startIntro(it) })
                }
            }
        }
    }

    tagFor?.let { b ->
        var tagInput by remember(b) { mutableStateOf(b.tag) }
        AlertDialog(
            onDismissRequest = { tagFor = null },
            title = { Text(L10n.s("标签 · ", "Tag · ") + displayTitle(b).take(24), color = UiTheme.p.textPrimary, fontSize = 15.sp) },
            text = {
                androidx.compose.material3.OutlinedTextField(value = tagInput, onValueChange = { tagInput = it }, singleLine = true,
                    label = { Text(L10n.s("标签名（空=清除）", "Tag name (empty clears)")) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = {
                Db.setBookTag(b.path, tagInput.trim())
                TestLog.evt("tag_set", tagInput.trim())
                tagFor = null
                appState.reloadLibrary()
            }) { Text(L10n.s("保存", "Save"), color = UiTheme.p.accent) } },
            dismissButton = { TextButton(onClick = { tagFor = null }) { Text(L10n.s("取消", "Cancel"), color = UiTheme.p.textTertiary) } },
        )
    }
    introFor?.let { b ->
        AlertDialog(
            onDismissRequest = { introFor = null },
            title = { Text(L10n.s("AI 简介 · ", "AI intro · ") + displayTitle(b).take(24), color = UiTheme.p.textPrimary, fontSize = 15.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (introBusy) L10n.s("生成中…（首次调用可能需等待）", "Generating…")
                         else if (introText.isEmpty()) L10n.s("（空）", "(empty)") else introText,
                        color = UiTheme.p.textSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                val cb = androidx.compose.ui.platform.LocalClipboardManager.current
                TextButton(onClick = {
                    cb.setText(androidx.compose.ui.text.AnnotatedString(introText))
                    TestLog.evt("ai_intro_copy", b.title.take(30))
                }) { Text(L10n.s("复制", "Copy"), color = UiTheme.p.accent) }
            },
            dismissButton = { TextButton(onClick = { introFor = null }) { Text(L10n.s("关闭", "Close"), color = UiTheme.p.textTertiary) } },
        )
    }
    infoFor?.let { b -> BookInfoDialog(b, onDismiss = { infoFor = null }) }
    delFor?.let { b ->
        AlertDialog(
            onDismissRequest = { delFor = null },
            title = { Text(L10n.s("删除文件", "Delete file")) },
            text = {
                Column {
                    Text(L10n.s("将把《${b.title}》从磁盘删除（不可恢复），并移出书库与其进度/书签/高亮。", "Permanently delete this book from disk, removing it with progress/bookmarks/highlights."))
                    if (deleteHint.isNotBlank()) Text(deleteHint, color = UiTheme.p.danger, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 检视修复：删除失败（文件被占用/远程书）给明确反馈，不再静默
                    val ok = !b.path.startsWith("remote://") && File(b.path).delete()
                    if (ok) removeBookData(b, appState)
                    TestLog.evt("lib_delete_file", "name=" + b.title + " ok=" + ok)
                    delFor = null
                    if (!ok) deleteHint = L10n.s("删除失败：文件可能正被占用（远程书请用「移出书库」）。",
                        "Delete failed: file may be in use (for remote books use 「remove from library」).")
                }) { Text(L10n.s("删除", "Delete"), color = UiTheme.p.danger) }
            },
            dismissButton = { TextButton(onClick = { delFor = null }) { Text(L10n.s("取消", "Cancel")) } },
        )
    }

    if (fulls) {
        FullTextSearchDialog(appState) { fulls = false }
    }
    if (foldersDlg) {
        ShelfFoldersDialog(appState, onDismiss = { foldersDlg = false })
    }
}

/** 书库文件夹配置弹框（v0.30.0）：本地文件夹/远程目录两类勾选上架；「搜索书籍」= 重扫本地 + 扫描勾选的远程源；
 *  取消勾选的来源其书籍随本次扫描自动从书架移除。 */
@Composable
private fun ShelfFoldersDialog(appState: AppState, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(Db.listScanDirRows()) }
    var srcs by remember { mutableStateOf(Db.listRemoteSourceRows()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!scanning) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.size(720.dp, 600.dp).clip(RoundedCornerShape(12.dp)), color = UiTheme.p.panel) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L10n.s("书库文件夹配置", "Shelf folders"), color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    L10n.s("勾选要放入书架的文件夹/远程目录，点「搜索书籍」入库；取消勾选后对应书籍将从书架移除（文件保留）。",
                        "Check folders/sources to shelve, then Scan; unchecking removes their books from the shelf (files kept)."),
                    color = UiTheme.p.textFaintest, fontSize = 11.sp,
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(L10n.s("本地文件夹", "Local folders"), color = UiTheme.p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (rows.isEmpty()) Text(L10n.s("（无——到「我的文件」添加本地目录）", "(none — add local folders in My Files)"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                    for (d in rows) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = d.shelf, onCheckedChange = { on ->
                                Db.setScanDirShelf(d.path, on)
                                rows = Db.listScanDirRows()
                                TestLog.evt("shelf_dir_toggle", (if (on) "on " else "off ") + d.path)
                            })
                            Text(d.path, color = UiTheme.p.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(L10n.s("远程目录", "Remote sources"), color = UiTheme.p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (srcs.isEmpty()) Text(L10n.s("（无——到「我的文件」添加网络服务器）", "(none — add servers in My Files)"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                    for ((sv, on) in srcs) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, onCheckedChange = { v ->
                                Db.setRemoteSourceShelf(sv.id, v)
                                if (!v) Db.deleteRemoteBooks(sv.id)   // 取消勾选→其 remote 登记书立即移出书架（v0.31.0）
                                srcs = Db.listRemoteSourceRows()
                                appState.reloadLibrary()
                                TestLog.evt("shelf_src_toggle", (if (v) "on " else "off ") + sv.name)
                            })
                            Column(Modifier.weight(1f)) {
                                Text(sv.name, color = UiTheme.p.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${sv.protocol.lowercase()} · ${sv.host}", color = UiTheme.p.textFaintest, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(msg, color = UiTheme.p.textTertiary, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (scanning) return@Button
                            scanning = true
                            if (appState.scanning) return@Button   // 检视修复：与进行中的全局扫描互斥（prune 竞态防误删）
                            msg = L10n.s("扫描中…", "Scanning…")
                            TestLog.evt("shelf_search", "dirs=${rows.count { it.shelf }} srcs=${srcs.count { it.second }}")
                            Thread {
                                val remote = srcs.filter { it.second }.map { it.first }
                                var n = 0
                                for (sv in remote) {
                                    n += runCatching { howread.core.library.RemoteScanner.registerSource(sv) }.getOrDefault(0)
                                }
                                // 检视修复：与启动扫描同口径（否则 .nomedia 目录的书扫描后入库、重启又消失）
                                howread.core.library.Scanner.scanAll(LibPrefs.bool("ignoreNomedia", true))
                                javax.swing.SwingUtilities.invokeLater {
                                    appState.reloadLibrary()
                                    scanning = false
                                    msg = L10n.s("完成：远程新增 $n 本", "Done: +$n remote books")
                                }
                            }.apply { isDaemon = true }.start()
                        },
                        enabled = !scanning,
                    ) { Text(if (scanning) L10n.s("搜索中…", "Scanning…") else L10n.s("搜索书籍", "Scan books")) }
                    TextButton(onClick = { if (!scanning) onDismiss() }) { Text(L10n.s("关闭", "Close")) }
                }
            }
        }
    }
}

/** 简表视图（v0.30.0）：名称/大小/时间/格式 四列，无封面。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTable(b: Book, appState: AppState, onAskDelete: (Book) -> Unit, onAskInfo: (Book) -> Unit,
    onSetTag: (Book) -> Unit = {}, onAskIntro: (Book) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val sel = b.path in appState.selected
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (sel && appState.selectionMode) UiTheme.p.rowActive else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (appState.selectionMode) {
                        appState.selected = if (sel) appState.selected - b.path else appState.selected + b.path
                    } else {
                        TestLog.evt("open_book", "name=" + b.title)
                        appState.open(b.path)
                    }
                },
                onLongClick = { menu = true },
            )
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(displayTitle(b), color = UiTheme.p.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RemoteCacheBadge(appState, b.path)
        Text("${b.size / 1024} KB", color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.width(76.dp))
        Text(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(b.mtime)),
            color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.width(120.dp),
        )
        Text(b.path.substringAfterLast('.', "").uppercase(), color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.width(52.dp))
        BookMenu(b, appState, menu, { menu = false }, onAskDelete, onAskInfo, onSetTag, onAskIntro)
    }
}

/** 书架视图（v0.30.0）：每行 4 本封面对齐在一条"搁板"上。 */
@Composable
private fun ShelfView(books: List<Book>, appState: AppState, onAskDelete: (Book) -> Unit, onAskInfo: (Book) -> Unit,
    onSetTag: (Book) -> Unit = {}, onAskIntro: (Book) -> Unit = {}) {
    val rows = books.chunked(4)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(rows.size) { r ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
                    for (b in rows[r]) {
                        BookCard(b, appState, modifier = Modifier.weight(1f), onAskDelete = onAskDelete, onAskInfo = onAskInfo,
                            onSetTag = onSetTag, onAskIntro = onAskIntro)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(7.dp)
                        .background(Color(0xFF9A7B53), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** 书籍右键菜单（网格/列表共用）。 */
@Composable
private fun BookMenu(b: Book, appState: AppState, expanded: Boolean, onDismiss: () -> Unit,
    onAskDelete: (Book) -> Unit, onAskInfo: (Book) -> Unit,
    onSetTag: (Book) -> Unit = {}, onAskIntro: (Book) -> Unit = {}) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(L10n.s("文件信息", "Info")) }, onClick = { onDismiss(); onAskInfo(b) })
        DropdownMenuItem(text = { Text(L10n.s("标签…", "Tag…")) }, onClick = { onDismiss(); onSetTag(b) })
        if (!b.path.startsWith("remote://")) {   // AI 简介要读正文，远程书不适用（v0.51.0）
            DropdownMenuItem(text = { Text(L10n.s("AI 简介", "AI intro")) }, onClick = { onDismiss(); onAskIntro(b) })
        }
        if (!b.path.startsWith("remote://")) {   // 检视修复：远程书没有本地文件夹
            DropdownMenuItem(text = { Text(L10n.s("打开所在文件夹", "Show in folder")) }, onClick = {
                onDismiss(); revealInExplorer(b.path); TestLog.evt("lib_reveal", b.path)
            })
        }
        DropdownMenuItem(text = { Text(if (b.isFavorite) L10n.s("取消收藏", "Unfavorite") else L10n.s("加入收藏", "Favorite")) }, onClick = {
            onDismiss()
            Db.toggleFavorite(b.path)
            appState.reloadLibrary()
            TestLog.evt("fav_toggle", "name=" + b.title)
        })
        DropdownMenuItem(text = { Text(L10n.s("移出书库（保留文件）", "Remove from library (keep file)")) }, onClick = {
            onDismiss(); removeBookData(b, appState)
        })
        DropdownMenuItem(text = { Text(L10n.s("删除文件…", "Delete file…"), color = UiTheme.p.danger) }, onClick = {
            onDismiss(); onAskDelete(b)
        })
    }
}

/** 书卡片（书库/珍藏共用；点击=开书或多选，长按=菜单）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(b: Book, appState: AppState, big: Boolean = false, modifier: Modifier = Modifier, onAskDelete: (Book) -> Unit = {}, onAskInfo: (Book) -> Unit = {},
    onSetTag: (Book) -> Unit = {}, onAskIntro: (Book) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val sel = b.path in appState.selected
    val threeD = LibPrefs.bool("cover3d", true)
    val borderOn = LibPrefs.bool("coverBorder", true)
    val crop = LibPrefs.bool("cropCovers", true)
    Column(
        modifier
            .then(if (threeD) Modifier.shadow(6.dp, RoundedCornerShape(8.dp)) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, UiTheme.p.textFaintest.copy(alpha = if (borderOn) 0.5f else 0f), RoundedCornerShape(8.dp))
            .background(if (sel && appState.selectionMode) UiTheme.p.rowActive else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (appState.selectionMode) {
                        appState.selected = if (sel) appState.selected - b.path else appState.selected + b.path
                    } else {
                        TestLog.evt("open_book", "name=" + b.title)
                        appState.open(b.path)
                    }
                },
                onLongClick = { menu = true },
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(UiTheme.p.card)) {
            BookCover(path = b.path, title = b.title, mtime = b.mtime, modifier = Modifier.fillMaxSize(), crop = crop)
            if (threeD) {
                Box(Modifier.fillMaxHeight().width(6.dp).background(Brush.horizontalGradient(listOf(Color(0x59000000), Color.Transparent))))
            }
            TextButton(
                onClick = {
                    val nowFav = Db.toggleFavorite(b.path)
                    TestLog.evt("fav_toggle", "name=" + b.title + " fav=" + nowFav)
                    appState.reloadLibrary()
                },
                modifier = Modifier.align(Alignment.TopEnd),
            ) { Text(if (b.isFavorite) "★" else "☆", fontSize = 16.sp, color = if (b.isFavorite) UiTheme.p.gold else UiTheme.p.textTertiary) }
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { RemoteCacheBadge(appState, b.path, darkOverlay = true) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            displayTitle(b), color = UiTheme.p.textPrimary, fontSize = 13.sp,
            minLines = 2, maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subLine(b), color = UiTheme.p.textFaint, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        BookMenu(b, appState, menu, { menu = false }, onAskDelete, onAskInfo, onSetTag, onAskIntro)
    }
}

/** 行式列表条目（列表视图）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(b: Book, appState: AppState, onAskDelete: (Book) -> Unit, onAskInfo: (Book) -> Unit,
    onSetTag: (Book) -> Unit = {}, onAskIntro: (Book) -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val sel = b.path in appState.selected
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (sel && appState.selectionMode) UiTheme.p.rowActive else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (appState.selectionMode) {
                        appState.selected = if (sel) appState.selected - b.path else appState.selected + b.path
                    } else {
                        TestLog.evt("open_book", "name=" + b.title)
                        appState.open(b.path)
                    }
                },
                onLongClick = { menu = true },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(listCoverDp().dp).aspectRatio(0.72f).background(UiTheme.p.card)) {
            BookCover(path = b.path, title = b.title, mtime = b.mtime, modifier = Modifier.fillMaxSize(), crop = LibPrefs.bool("cropCovers", true))
        }
        Column(Modifier.weight(1f)) {
            Text(displayTitle(b), color = UiTheme.p.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subLine(b), color = UiTheme.p.textFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RemoteCacheBadge(appState, b.path)
        Text(
            if (b.isFavorite) "★" else "☆", fontSize = 14.sp,
            color = if (b.isFavorite) UiTheme.p.gold else UiTheme.p.textTertiary,
            modifier = Modifier.clickable {
                Db.toggleFavorite(b.path)
                TestLog.evt("fav_toggle", "name=" + b.title)
                appState.reloadLibrary()
            },
        )
        BookMenu(b, appState, menu, { menu = false }, onAskDelete, onAskInfo, onSetTag, onAskIntro)
    }
}

/** 标题显示取舍：开"显示文件名而非元数据"=恒文件名；否则优先元数据标题（打开书籍后回填）。 */
private fun displayTitle(b: Book): String =
    if (LibPrefs.bool("showFileName", true)) b.title
    else if (b.metaTitle.isNotBlank()) b.metaTitle else b.title

private fun listCoverDp(): Int = LibPrefs.str("coverListDp", "34").toIntOrNull()?.coerceIn(28, 72) ?: 34

private fun gridCoverDp(): Int = LibPrefs.str("coverGridDp", "150").toIntOrNull()?.coerceIn(110, 280) ?: 150

private fun gridColumns(): Int = LibPrefs.str("gridColumnCount", "0").toIntOrNull()?.coerceIn(0, 8) ?: 0

private fun subLine(b: Book): String {
    val pages = if (b.pages > 0) L10n.s(" · ${b.pages}页", " · ${b.pages}p") else ""
    val series = if (b.series.isNotBlank() && LibPrefs.bool("seriesNumInTitle", true))
        b.series + (if (b.seriesIdx >= 0) " " + (if (b.seriesIdx < 10) "0" else "") + b.seriesIdx else "") + " · "
    else ""
    val author = if (b.author.isNotBlank()) b.author + " · " else ""
    return series + author + (if (b.lastReadAt > 0) L10n.s("最近读过", "Read") + pages else "${b.size / 1024} KB$pages")
}

/** 网络书缓存角标（v0.36.0，参考安卓远程书云端角标）：整本=CloudDone 100%；缓存中=CloudDownload+百分比；
 *  从未缓存或大小未知（-1）不显示。darkOverlay=封面深色底上的白色半透明徽标。 */
@Composable
fun RemoteCacheBadge(appState: AppState, path: String, darkOverlay: Boolean = false) {
    appState.cacheTick   // 周期订阅：缓存推进时重组
    val pct = if (path.startsWith("remote://")) RemoteOpenCache.cachePercent(path) else -1
    if (pct < 0) return
    val fg = if (darkOverlay) Color.White else UiTheme.p.accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = if (darkOverlay) Modifier
            .background(Color(0xA0000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp) else Modifier,
    ) {
        Icon(
            if (pct >= 100) Icons.Filled.CloudDone else Icons.Filled.CloudDownload,
            contentDescription = L10n.s("缓存", "Cache"),
            tint = fg, modifier = Modifier.size(13.dp),
        )
        Text("${pct}%", color = fg, fontSize = 10.sp)
    }
}

/** 文件信息对话框。 */
@Composable
private fun BookInfoDialog(b: Book, onDismiss: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L10n.s("文件信息", "Info")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(b.title, color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (b.series.isNotBlank()) {
                    Text(L10n.s("系列：", "Series: ") + b.series +
                        (if (b.seriesIdx >= 0) " · #" + b.seriesIdx else ""),
                        color = UiTheme.p.accent, fontSize = 12.sp)
                }
                Text(b.path, color = UiTheme.p.textTertiary, fontSize = 11.sp)
                Text(if (b.pages > 0) L10n.s("${b.pages} 页", "${b.pages} pages") else L10n.s("页数未知", "Pages unknown"), color = UiTheme.p.textTertiary, fontSize = 12.sp)
                Text("${b.size / 1024} KB · Fav: ${if (b.isFavorite) "Y" else "N"}", color = UiTheme.p.textTertiary, fontSize = 12.sp)
                Text(L10n.s("文件时间 ", "File time ") + fmt.format(Date(b.mtime)), color = UiTheme.p.textTertiary, fontSize = 12.sp)
                if (LibPrefs.bool("showDescription", true) && b.description.isNotBlank()) {
                    Text(L10n.s("描述", "Description"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                    Text(b.description, color = UiTheme.p.textSecondary, fontSize = 12.sp)
                }
                Text(if (b.lastReadAt > 0) L10n.s("最近阅读 ", "Last read ") + fmt.format(Date(b.lastReadAt)) else L10n.s("未读过", "Unread"), color = UiTheme.p.textTertiary, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L10n.s("关闭", "Close")) } },
    )
}
