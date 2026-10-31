package howread.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowState
import howread.core.reader.ReadTextures
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import howread.core.ai.AiClient
import howread.core.ai.AiConfigStore
import howread.core.ai.Lang
import howread.core.ai.TranslationCache
import howread.engine.SelRect
import howread.core.AppPaths
import howread.core.FormatRouter
import howread.core.reader.ReadTheme
import howread.core.reader.ReadThemes
import howread.core.reader.ReaderPrefs
import howread.core.reader.ReaderPrefsStore
import howread.core.tts.TtsSpeaker
import howread.core.TestLog
import howread.core.ai.TranslateSession
import howread.core.data.AnnQuad
import howread.core.dict.Dicts
import howread.core.data.AnnotationRow
import howread.core.data.Db
import howread.core.model.Book
import howread.core.model.Bookmark
import howread.engine.DocumentHandle
import howread.engine.EngineLib
import howread.engine.ReadMode
import howread.engine.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs

/** v0.29.0 冒烟探针结果（--smokegui 读；正式运行不触达）。 */
object Sm29Probe {
    @Volatile var done: Boolean = false
    @Volatile var ok: Boolean = false
}

private enum class Panel { NONE, TOC, BOOKMARK, SEARCH, TRANSLATE, QA, LAYOUT, RECENT }

/** 划词持久高亮色板（黄/绿/蓝/红/紫）。 */
private val HL_COLORS = intArrayOf(
    0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(), 0xFFE57373.toInt(), 0xFFBA68C8.toInt(),
)
private enum class ViewMode { PAGE, SCROLL }

private data class SearchHit(val page: Int, val count: Int)

@Composable
fun ReaderScreen(path: String, initialPage: Int = -1, onClose: () -> Unit, onOpenBook: (String) -> Unit = {}, smoke: Boolean = false, smokeWindow: WindowState? = null) {
    EngineLib.ensure()
    val scope = rememberCoroutineScope()
    val isReflowable = remember(path) { FormatRouter.isReflowable(path) }

    // 字号：持久化偏好优先，否则按屏显 DPI 自动定基（安卓 fontSizeSp=20 的等价思路）
    val screenDensity = LocalDensity.current.density
    var fontScale by remember(path) {
        val saved = ReaderPrefsStore.load().fontScale
        val auto = (screenDensity * 100).toInt().coerceIn(60, 220)
        mutableIntStateOf(if (saved in 60..220) saved else auto)
    }
    var zoomPdf by remember(path) { mutableFloatStateOf(1.0f) }
    var panel by remember(path) { mutableStateOf(Panel.NONE) }
    var viewMode by remember(path) { mutableStateOf(if (LibPrefs.defaultReaderModePage()) ViewMode.PAGE else ViewMode.SCROLL) }
    var bilingual by remember(path) { mutableStateOf(false) }
    var ttsOn by remember(path) { mutableStateOf(false) }
    var ttsCont by remember(path) { mutableStateOf(false) }
    var ttsProg by remember(path) { mutableStateOf("") }
    var ttsRunId by remember(path) { mutableStateOf(0) }
    // TTS 睡眠定时（v0.50.0 对齐安卓 ttsTimer）：设定时长后到点停读；按钮循环 关→15→30→60→关
    var ttsTimerMin by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(ttsTimerMin) {
        if (ttsTimerMin > 0) {
            delay(ttsTimerMin * 60_000L)
            if (ttsTimerMin > 0) {
                TtsSpeaker.cancel()
                ttsRunId++
                ttsOn = false
                ttsProg = ""
                ttsTimerMin = 0
                TestLog.evt("tts_timer", "fire")
            }
        }
    }

    // ---- 排版偏好（P2-A：字体/行距/边距/对齐；改动即存，prefsRev 触发引擎重开重排） ----
    var prefs by remember(path) { mutableStateOf(ReaderPrefsStore.load()) }
    var prefsRev by remember(path) { mutableIntStateOf(0) }
    var immersive by remember(path) { mutableStateOf(false) }
    var themeMenu by remember(path) { mutableStateOf(false) }
    var trimWhite by remember(path) { mutableStateOf(LibPrefs.bool("trimWhite", false)) }
    // ---- 批次三（v0.52.0 对齐安卓）：双页并排 / RSVP 速读 / TTS 导出 ----
    var twoPage by remember(path) { mutableStateOf(LibPrefs.bool("twoPage", false)) }
    var rsvpOn by remember(path) { mutableStateOf(false) }
    var rsvpIdx by remember(path) { mutableIntStateOf(0) }
    var rsvpPause by remember(path) { mutableStateOf(false) }
    var rsvpWpm by remember(path) { mutableIntStateOf(LibPrefs.str("rsvpWpm", "300").toIntOrNull()?.coerceIn(100, 900) ?: 300) }
    var ttsExpMsg by remember(path) { mutableStateOf("") }
    fun stepSize(): Int = if (viewMode == ViewMode.PAGE && twoPage) 2 else 1

    var viewportLocked by remember(path) { mutableStateOf(false) }   // 视口锁定：首排后不再随窗口缩放重排（v0.31.0）
    var viewportW by remember(path) { mutableIntStateOf(0) }
    var viewportH by remember(path) { mutableIntStateOf(0) }
    var renderW by remember(path) { mutableIntStateOf(0) }
    var layoutW by remember(path) { mutableIntStateOf(0) }
    var layoutRev by remember(path) { mutableIntStateOf(0) }   // 排版纪元：字号/行距/窗口变化后使页面位图缓存失效

    // ---- 自动播放（v0.43.0 对齐安卓自动滚动/自动翻页）：滚动=逐帧 scrollBy 像素推进；翻页=定时 jumpTo 下一页 ----
    var autoScrollOn by remember(path) { mutableStateOf(false) }
    var autoScrollSpeed by remember(path) { mutableStateOf(LibPrefs.str("autoScrollSpeed", "3").toIntOrNull()?.coerceIn(1, 30) ?: 3) }
    var autoFlipOn by remember(path) { mutableStateOf(false) }
    var autoFlipSec by remember(path) { mutableStateOf(LibPrefs.str("autoFlipSec", "10").toIntOrNull()?.coerceIn(3, 300) ?: 10) }

    // 文档句柄（v0.26.0 异步打开：解析整书不再卡 UI 线程；排版偏好变化 → 重开文档，页数变化由百分比逻辑自动落回）
    var doc by remember(path) { mutableStateOf<DocumentHandle?>(null) }
    // 实际交给引擎的文件（TXT 转码副本 / 大 mobi 转 EPUB 产物）——accel 命名与保存按它判定
    var docEnginePath by remember(path) { mutableStateOf("") }
    var docRev by remember(path) { mutableIntStateOf(0) }
    var docOpening by remember(path) { mutableStateOf(true) }
    var openError by remember(path) { mutableStateOf("") }
    var remoteMsg by remember(path) { mutableStateOf("") }   // 远程书缓存进度提示（v0.31.0）
    var openStream by remember(path) { mutableStateOf<RemoteOpenCache.Resolution.Streamed?>(null) }   // 块缓存会话（v0.34.0，随文档生命周期）
    LaunchedEffect(path, prefsRev) {
        val me = ++docRev
        docOpening = true
        openError = ""
        TestLog.evt("open_begin", "ext=" + FormatRouter.ext(path) + " reflow=$isReflowable rev=$me")
        val t0 = System.currentTimeMillis()
        val holder = arrayOfNulls<DocumentHandle>(1)
        val streamedRef = arrayOfNulls<RemoteOpenCache.Resolution.Streamed>(1)
        try {
            withContext(Dispatchers.Default) {
                // 远程书（remote://）：单本缓存到软件目录后打开（已缓存秒开；v0.31.0）
                // openablePath（TXT 整书转码 / 大 mobi 转 EPUB）同样是重活，一并放后台（v0.27.0）
                val res = if (path.startsWith("remote://")) {
                    RemoteOpenCache.resolve(path) { m -> remoteMsg = m }
                } else null
                var streamed: RemoteOpenCache.Resolution.Streamed? = null
                val op = when (res) {
                    is RemoteOpenCache.Resolution.Local -> FormatRouter.openablePath(res.file)
                    is RemoteOpenCache.Resolution.Streamed -> { streamed = res; "" }
                    null -> {
                        // 检视修复：远程解析失败（源被删/离线）直接终止——remote:// 伪路径无法本地打开
                        if (path.startsWith("remote://"))
                            throw IllegalStateException(remoteMsg.ifBlank { "远程书打开失败" })
                        FormatRouter.openablePath(path)
                    }
                }
                remoteMsg = ""
                docEnginePath = op
                EngineLib.setUserCss(prefs.css)
                EngineLib.setUseDocumentCss(prefs.useDocumentCss)
                streamedRef[0] = streamed
                holder[0] = if (streamed != null) {
                    // 大远程书：块缓存流式打开（v0.34.0，magic=纯文件名；块缓存即加速层，无 accel）
                    DocumentHandle(streamed!!.stream, streamed!!.magic)
                } else {
                    val acc = accelPathFor(op)
                    if (acc != null) DocumentHandle(op, acc) else DocumentHandle(op)
                }
            }
            TestLog.evt("open_doc_done", "ms=" + (System.currentTimeMillis() - t0) + " ext=" + FormatRouter.ext(path))
        } catch (e: Exception) {
            holder[0]?.close()   // 取消或打开失败：关闭刚创建的句柄防泄漏
            runCatching { streamedRef[0]?.close() }
            if (e is kotlinx.coroutines.CancellationException) throw e
            openError = e.message ?: e.toString()
            docOpening = false
            TestLog.evt("open_doc_fail", openError.take(120))
            return@LaunchedEffect
        }
        ensureActive()   // 检视修复：打开完成瞬间换书/退出——恢复点之后补一次取消检查，防句柄写进已废弃状态
        val made = holder[0] ?: run { streamedRef[0]?.close(); return@LaunchedEffect }
        if (me != docRev) {   // 已被更新的重开 superseded
            made.close(); runCatching { streamedRef[0]?.close() }; return@LaunchedEffect
        }
        val old = doc
        val oldStream = openStream
        doc = made
        openStream = streamedRef[0]
        docOpening = false
        old?.close()
        oldStream?.close()
    }
    DisposableEffect(path) {
        onDispose {
            runCatching { doc?.close() }
            runCatching { openStream?.close() }
        }
    }
    // 作者元数据回填（懒式：打开书时读引擎 meta，库里有空位才写）
    LaunchedEffect(doc, path) {
        val d = doc ?: return@LaunchedEffect
        if (!LibPrefs.bool("usePdfMeta", true)) return@LaunchedEffect
        val a = runCatching { d.metaAuthor() }.getOrDefault("")
        if (a.isNotBlank()) Db.setAuthor(path, a)
    }

    // 退出确认（v0.56 对齐安卓 show_application_close_dialog_every_time）：关闭按钮/Esc 均先问
    var exitAsk by remember { mutableStateOf(false) }
    fun requestClose() { if (LibPrefs.bool("exitConfirmReading", true)) exitAsk = true else onClose() }
    if (exitAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { exitAsk = false },
            title = { Text(L10n.s("退出阅读", "Close book"), color = UiTheme.p.textPrimary, fontSize = 15.sp) },
            text = { Text(L10n.s("确定退出当前书籍吗？", "Close the current book?"), color = UiTheme.p.textSecondary, fontSize = 13.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { exitAsk = false; onClose() }) {
                    Text(L10n.s("确定", "OK"), color = UiTheme.p.accent)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { exitAsk = false }) {
                    Text(L10n.s("取消", "Cancel"), color = UiTheme.p.textTertiary)
                }
            },
        )
    }

    var pageCount by remember(path) { mutableIntStateOf(0) }

    // ---- 进度恢复（v0.27.0 乐观落位）：先按上次页码直接渲染首屏（重开有加速器时页数秒得），
    //      全量页数就位后再做精确/百分比校正；几何变化按百分比回落 ----
    val savedProgress = remember(path) { runCatching { Db.loadProgress(path) }.getOrNull() }
    var page by remember(path, initialPage) {
        mutableIntStateOf(
            when {
                initialPage >= 0 -> initialPage
                savedProgress != null && savedProgress.pageCount > 0 && !LibPrefs.bool("openOnPage1", false) -> savedProgress.page.coerceAtLeast(0)
                else -> 0
            }
        )
    }

    var prevCount by remember(path) { mutableIntStateOf(-1) }
    var layoutReady by remember(path) { mutableStateOf(false) }
    var countDone by remember(path) { mutableStateOf(false) }

    // ---- 阅读统计埋点：30s 心跳计时 + 翻页累计上报（安卓 statHours/Today/Speed 同口径） ----
    var pendingPages by remember(path) { mutableIntStateOf(0) }
    var lastSeenPage by remember(path) { mutableIntStateOf(-1) }
    LaunchedEffect(path) {
        while (true) {
            delay(30_000)
            runCatching { Db.addReadTime(path, 30, 0) }
        }
    }
    var scrollJumpToken by remember(path) { mutableIntStateOf(0) }

    fun jumpTo(p: Int) {
        if (pageCount <= 0) return
        val t = p.coerceIn(0, pageCount - 1)
        if (t != page) page = t
        scrollJumpToken++
        TestLog.evt("jump", "to=${t + 1}")
    }

    // ---- 导航历史（v0.51.0 对齐安卓阅读器 Back/Fwd）：目录/书签/批注/历史跳转可回退前进 ----
    val navBack = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    val navFwd = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
    fun jumpNav(p: Int) {
        if (pageCount <= 0) return
        val t = p.coerceIn(0, pageCount - 1)
        if (t != page) { navBack.add(page); navFwd.clear() }
        jumpTo(t)
    }
    fun navGoBack() {
        if (navBack.isEmpty()) return
        navFwd.add(page)
        jumpTo(navBack.removeAt(navBack.size - 1))
        TestLog.evt("nav_back", "depth=" + navBack.size)
    }
    fun navGoFwd() {
        if (navFwd.isEmpty()) return
        navBack.add(page)
        jumpTo(navFwd.removeAt(navFwd.size - 1))
        TestLog.evt("nav_fwd", "depth=" + navFwd.size)
    }

    // ---- 朗读（v0.20.0：参数走 TtsPrefs；连读=播完当前页自动翻页续读，手动翻页也跟随续读） ----
    fun speakPage(p: Int) {
        val paras = doc?.let { runCatching { it.pageParagraphs(p) }.getOrDefault(emptyList()) } ?: emptyList()
        if (paras.isEmpty()) {
            if (ttsCont && p < pageCount - 1) {
                TestLog.evt("tts_cont_skip", "page=" + (p + 1))
                jumpTo(p + 1)
            } else {
                ttsOn = false
                ttsProg = ""
            }
            return
        }
        ttsOn = true
        ttsRunId++
        val run = ttsRunId
        TestLog.evt("tts_start", "page=" + (p + 1) + " paras=" + paras.size)
        TtsSpeaker.speak(paras, { finished ->
            if (run != ttsRunId) return@speak
            if (finished && ttsCont && page < pageCount - 1) {
                TestLog.evt("tts_cont_next", "from=" + (page + 1))
                jumpTo(page + 1)
            } else {
                ttsOn = false
                ttsProg = ""
            }
        }) { i, n -> if (run == ttsRunId) ttsProg = "$i/$n" }
    }

    LaunchedEffect(path, page) {
        if (ttsOn) {
            if (ttsCont) {
                TestLog.evt("tts_cont_follow", "page=" + (page + 1))
                speakPage(page)
            } else {
                TtsSpeaker.cancel()
                ttsOn = false
                ttsProg = ""
                TestLog.evt("tts_stop", "page_change")
            }
        }
        if (lastSeenPage >= 0 && page != lastSeenPage) pendingPages += abs(page - lastSeenPage)
        lastSeenPage = page
        if (pendingPages > 0) {
            runCatching { Db.addReadTime(path, 0, pendingPages) }
            pendingPages = 0
        }
    }

    // 翻页即存（防抖 600ms），关窗 dispose 兜底
    LaunchedEffect(path, pageCount) {
        snapshotFlow { page }.collectLatest { p ->
            delay(600)
            if (countDone && pageCount > 0) {
                Db.saveProgress(path, p, pageCount, viewportW)
                TestLog.evt("progress_save", "page=${p + 1}/$pageCount vw=$viewportW")
            }
        }
    }
    DisposableEffect(path) {
        onDispose {
            TtsSpeaker.cancel()
            if (countDone && pageCount > 0) runCatching { Db.saveProgress(path, page, pageCount, viewportW) }
            if (pendingPages > 0) runCatching { Db.addReadTime(path, 0, pendingPages) }
            TestLog.evt("doc_close", "page=${page + 1}/$pageCount")
        }
    }

    // 定时自动翻页（对齐安卓 automatic_page_flipping）：page 变化即重置计时，到点翻下一页（末页自停）
    LaunchedEffect(autoFlipOn, autoFlipSec, page, viewMode) {
        if (autoFlipOn && viewMode == ViewMode.PAGE) {
            kotlinx.coroutines.delay(autoFlipSec * 1000L)
            val pc = pageCount   // 检视修复：key 不含 pageCount——渐进计数批量发布不再重置倒计时
            if (pc > 0 && page < pc - 1) jumpTo(page + stepSize())
        }
    }

    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }


    // ---- 侧板数据 ----
    var toc by remember(path) { mutableStateOf<List<TocEntry>>(emptyList()) }
    LaunchedEffect(panel, doc) {
        if (panel == Panel.TOC && toc.isEmpty()) {
            toc = withContext(Dispatchers.Default) { runCatching { doc?.outline() }.getOrNull().orEmpty() }
            TestLog.evt("toc_loaded", "n=${toc.size}")
        }
    }

    var bookmarks by remember(path) { mutableStateOf<List<Bookmark>>(emptyList()) }
    var hlAll by remember(path) { mutableStateOf<List<AnnotationRow>>(emptyList()) }
    var recentBooks by remember(path) { mutableStateOf<List<Book>>(emptyList()) }
    var editingBm by remember(path) { mutableStateOf<Bookmark?>(null) }
    var noteDraft by remember(path) { mutableStateOf("") }
    LaunchedEffect(panel) {
        if (panel == Panel.RECENT) recentBooks = Db.recentBooks(24)
        if (panel == Panel.BOOKMARK) {
            bookmarks = Db.listBookmarks(path)
            hlAll = Db.listAllAnnotations(path)
        }
    }

    var query by remember(path) { mutableStateOf("") }
    var hits by remember(path) { mutableStateOf<List<SearchHit>>(emptyList()) }
    var scanned by remember(path) { mutableIntStateOf(-1) }
    var searchJob by remember(path) { mutableStateOf<Job?>(null) }
    fun runSearch(q: String) {
        if (q.isBlank()) return
        searchJob?.cancel()
        hits = emptyList()
        scanned = 0
        TestLog.evt("search_begin", "q=$q pages=$pageCount")
        searchJob = scope.launch {
            for (p in 0 until pageCount) {
                ensureActive()
                val c = withContext(Dispatchers.Default) { runCatching { doc?.searchPage(p, q) ?: 0 }.getOrDefault(0) }
                if (c > 0) hits = hits + SearchHit(p, c)
                scanned = p + 1
            }
            TestLog.evt("search_done", "q=$q hits=${hits.size}")
        }
    }

    // ---- AI 问答（基于当前页内容的阅读助手，流式回答） ----
    var qaMsgs by remember(path) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var qaBusy by remember(path) { mutableStateOf(false) }
    var qaDraft by remember(path) { mutableStateOf("") }
    fun askAi(q: String) {
        if (qaBusy || q.isBlank() || !AiConfigStore.configured()) return
        qaMsgs = qaMsgs + ("user" to q.trim()) + ("ai" to "")
        qaBusy = true
        TestLog.evt("qa_ask", "len=" + q.length + " page=" + (page + 1))
        scope.launch(Dispatchers.IO) {   // 检视修复：裸线程→结构化协程（关书即取消，不再跑完残留）
            val ctx = (doc?.let { runCatching { it.pageParagraphs(page).joinToString("\n") }.getOrDefault("") } ?: "").take(4000)
            val hist = qaMsgs.dropLast(2).takeLast(4).joinToString("\n") { (role, text) ->
                (if (role == "user") "问：" else "答：") + text.take(500)
            }
            val prompt = "你是阅读助手。用户正在读《" + File(path).nameWithoutExtension + "》第 " + (page + 1) + " 页。" +
                "页面内容：\n" + ctx + "\n" + hist + "\n\n请优先依据页面内容回答；页面没有的信息可按常识补充并说明。问题：" + q.trim()
            val cfg = AiConfigStore.load()
            val r = AiClient.chat(cfg, prompt, cfg.maxTokens, AiClient.StreamCallback { full ->
                qaMsgs = qaMsgs.toMutableList().also { l -> if (l.isNotEmpty()) l[l.size - 1] = "ai" to full }
            })
            if (!r.ok) {
                qaMsgs = qaMsgs.toMutableList().also { l -> if (l.isNotEmpty()) l[l.size - 1] = "ai" to ("回答失败：" + r.error) }
                TestLog.evt("qa_fail", r.error)
            } else {
                TestLog.evt("qa_done", "len=" + r.reply.length)
            }
            qaBusy = false
        }
    }

    // ---- AI 翻译会话（滚动窗口 + 3 并发；面板开=建会话，关/切书=cancel） ----
    var aiTick by remember(path) { mutableIntStateOf(0) }
    var aiSession by remember(path) { mutableStateOf<TranslateSession?>(null) }
    var aiTgtSel by remember(path) { mutableStateOf("auto") }
    LaunchedEffect(panel, aiTgtSel, bilingual, path) {
        aiSession?.cancel()
        aiSession = null
        if ((panel == Panel.TRANSLATE || bilingual) && AiConfigStore.configured()) {
            val s = TranslateSession(
                bookPath = path,
                cfgProvider = { AiConfigStore.load() },
                paragraphsOf = { pg ->
                    kotlinx.coroutines.runBlocking(Dispatchers.Default) {
                        runCatching { doc?.pageParagraphs(pg) }.getOrNull().orEmpty()
                    }
                },
                chapterOf = { 0 },
                tgtSel = aiTgtSel,
                onTick = { scope.launch { aiTick++ } },
            )
            aiSession = s
            s.start(page + 1)
            TestLog.evt("ai_panel_open", "tgt=$aiTgtSel page=${page + 1}")
        }
    }
    LaunchedEffect(page, aiSession) { aiSession?.onPageChanged(page + 1) }
    DisposableEffect(path) {
        onDispose { aiSession?.cancel() }
    }

    val padXpx = with(LocalDensity.current) { 24.dp.toPx() }   // 与页面区 horizontal padding 一致
    val padYpx = with(LocalDensity.current) { 12.dp.toPx() }   // 与页面区 vertical padding 一致
    // ---- 渲染宽度：重排文档=版心宽（列居中显示）、固定版式=视口宽；× PDF 缩放（>1 仅超采样） ----
    LaunchedEffect(viewportW, zoomPdf, layoutW) {
        val base = if (isReflowable && layoutW > 0) layoutW else viewportW
        if (base > 0) renderW = (base * zoomPdf).toInt().coerceAtMost(4096)   // 检视加固：4K×4x 缩放防巨图
    }

    // ---- reflowable 排版（v0.27.0 后台多线程）：引擎 layout 只设参数即返回（惰性），首屏立即渲染；
    //      页数逐章渐进统计（countPages(chapter)，章间释放引擎锁，渲染可插队，安卓渐进计数同思路）；
    //      字号/每行字数变化经 250ms 防抖合并；EPUB 排版加速器后台保存（重开免整书重排版） ----
    var layoutBusy by remember(path) { mutableStateOf(false) }
    LaunchedEffect(doc) {
        val d = doc ?: return@LaunchedEffect
        layoutReady = false
        countDone = false
        if (!isReflowable) {
            // 固定版式（PDF 等）：页数直接可得
            val pc = withContext(Dispatchers.Default) { runCatching { d.pageCount() }.getOrDefault(0) }
            if (pc > 0) { pageCount = pc; prevCount = pc; countDone = true }
            layoutReady = true
            layoutRev++
            TestLog.evt("doc_open_done", "pages=$pc")
            return@LaunchedEffect
        }
        var first = true
        snapshotFlow { listOf(viewportW, fontScale, prefs.maxChars, if (viewMode == ViewMode.PAGE && twoPage) 1 else 0) }
            .collectLatest { _ ->
                if (viewportW <= 0) return@collectLatest
                val debounced = !first
                first = false
                if (debounced) delay(250)
                val em = 20f * fontScale / 100f
                // 版心：maxChars>0 = 每行字数上限（居中窄列）；0 = 全宽自适应——跟随窗口扣除页边留白（v0.29.0 默认）
                // 双页并排（v0.52.0）：翻页模式下页按半屏宽重排（安卓横屏双页同语义），滚动模式不受影响
                val vwEff = if (viewMode == ViewMode.PAGE && twoPage) viewportW / 2 else viewportW
                val target = if (prefs.maxChars > 0) (prefs.maxChars * em).toInt() + prefs.marginLeft + prefs.marginRight
                    else (vwEff - 2 * padXpx).toInt()
                // 检视修复：窗口缩到 <360px 时 min>max 必抛 IllegalArgumentException——下限钳 360
                val w = target.coerceIn(360, maxOf(360, vwEff))
                val h = (if (viewportH > 200) (viewportH - 2 * padYpx).toInt() else (viewportW * 3) / 2)
                    .coerceAtLeast(200).toFloat()
                layoutBusy = true
                countDone = false
                val t0 = System.currentTimeMillis()
                TestLog.evt("layout_begin", "w=$w em=$em debounced=$debounced")
                try {
                    val ok = withContext(Dispatchers.Default) { runCatching { d.layout(w.toFloat(), h, em) }.isSuccess }
                    if (!ok) return@collectLatest
                    layoutW = w
                    layoutReady = true
                    layoutRev++   // 排版纪元前移：位图缓存/渲染键随之失效（翻页模式字号等改动立即生效的关键）
                    TestLog.evt("layout_ready", "w=$w em=$em ms=" + (System.currentTimeMillis() - t0))
                    // 渐进页数：16 章一批计（批间释放引擎锁，渲染插队上限约一批），批量发布避免高频重组
                    var acc = 0
                    val nc = withContext(Dispatchers.Default) { runCatching { d.countChapters() }.getOrDefault(1) }
                    var ci = 0
                    while (ci < nc) {
                        val batch = minOf(16, nc - ci)
                        acc += withContext(Dispatchers.Default) {
                            var t = 0
                            for (k in ci until ci + batch) t += runCatching { d.countPagesOf(k) }.getOrDefault(0)
                            t
                        }
                        ci += batch
                        if (acc > 0 && (ci % 16 == 0 || ci >= nc)) pageCount = acc
                    }
                    if (acc <= 0) return@collectLatest
                    pageCount = acc
                    TestLog.evt("layout_done", "w=$w em=$em pages=$acc ms=" + (System.currentTimeMillis() - t0) + " chapters=$nc debounced=$debounced")
                    // 全量页数就位：乐观页校正（精确/百分比），几何变化按百分比回落
                    if (prevCount == -1) {
                        val saved = savedProgress
                        if (initialPage < 0 && saved != null && saved.pageCount > 0) {
                            val p = if (saved.pageCount == acc) saved.page
                            else ((saved.page.toFloat() / saved.pageCount) * acc).toInt()
                            if (p != page) TestLog.evt("progress_restore", "page=${p.coerceIn(0, acc - 1) + 1}/$acc saved=${saved.page + 1}/${saved.pageCount}")
                            page = p.coerceIn(0, acc - 1)
                        } else {
                            if (page >= acc) page = (acc - 1).coerceAtLeast(0)
                        }
                    } else if (prevCount > 0 && prevCount != acc) {
                        page = ((page.toFloat() / prevCount) * acc).toInt().coerceIn(0, acc - 1)
                        TestLog.evt("relocate_after_relayout", "page=${page + 1}/$acc prev=$prevCount")
                    }
                    prevCount = acc
                    countDone = true
                    TestLog.evt("doc_open_done", "pages=$acc")
                    // EPUB 排版加速器后台保存：下次同几何打开免整书重排版（安卓 accels 同思路）
                    val acc0 = accelPathFor(docEnginePath)
                    if (acc0 != null) {
                        withContext(Dispatchers.Default) {
                            if (d.saveAccel(acc0)) {
                                cleanupAccels(docEnginePath, acc0)
                                TestLog.evt("accel_save", "ok")
                            }
                        }
                    }
                } finally {
                    layoutBusy = false
                }
            }
    }


    // ---- 划词翻译（页模式：拖选 -> 高亮 -> AI 译文浮层；安卓长按选择的桌面等价物） ----
    var selAnchor by remember(path) { mutableStateOf<Pair<Float, Float>?>(null) }
    var selCur by remember(path) { mutableStateOf<Pair<Float, Float>?>(null) }
    var selQuads by remember(path) { mutableStateOf<List<SelRect>>(emptyList()) }
    var selText by remember(path) { mutableStateOf("") }
    var selTran by remember(path) { mutableStateOf("") }
    var selBusy by remember(path) { mutableStateOf(false) }
    var selErr by remember(path) { mutableStateOf("") }
    var selTgt by remember(path) { mutableStateOf("auto") }
    var selPopup by remember(path) { mutableStateOf(false) }
    var selReqToken by remember(path) { mutableIntStateOf(0) }
    val selCache = remember(path) { TranslationCache.forBook(path) }
    var pageBoxArr by remember(path) { mutableStateOf<FloatArray?>(null) }
    // ---- 离线词典（划词查词） ----
    var dictOpen by remember(path) { mutableStateOf(false) }
    var dictWord by remember(path) { mutableStateOf("") }
    var dictResults by remember(path) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    fun runDictLookup(word: String) {
        if (word.isBlank()) return
        dictWord = word
        dictResults = emptyList()
        Thread {
            val r = Dicts.lookupAll(word)
            dictResults = r
            dictOpen = true
            TestLog.evt("dict_lookup", "len=" + word.length + " hits=" + r.size)
        }.apply { isDaemon = true }.start()
    }

    // ---- PDF 高亮写回 ----
    var wbBusy by remember(path) { mutableStateOf(false) }
    var wbMsg by remember(path) { mutableStateOf("") }

    // ---- 持久高亮：翻页载入当前页（页数与创建时不一致则不渲染，防重排错位） ----
    var hlList by remember(path) { mutableStateOf<List<AnnotationRow>>(emptyList()) }
    LaunchedEffect(page, pageCount) {
        hlList = Db.listAnnotations(path, page).filter { it.pageCount == pageCount }
    }
    LaunchedEffect(page, pageCount, layoutRev) {   // 检视修复：重排后页数恰未变时也要刷新页面盒子
        pageBoxArr = withContext(Dispatchers.Default) { runCatching { doc?.pageBox(page) }.getOrNull() }
        selPopup = false
        selQuads = emptyList()
        selAnchor = null
        selCur = null
    }
    fun runSelTranslate() {
        if (selText.isBlank()) return
        if (!AiConfigStore.configured()) {
            selBusy = false; selTran = ""
            selErr = "未配置 AI 大模型——请到 偏好 → AI 大模型 配置后重试"
            return
        }
        val tgt = when (selTgt) { Lang.ZH -> Lang.ZH; Lang.EN -> Lang.EN; else -> Lang.autoFor(selText).second }
        val tok = ++selReqToken
        selCache.lookup("sel", selText, tgt)?.let { hit ->
            selBusy = false; selErr = ""; selTran = hit
            return
        }
        selBusy = true; selErr = ""; selTran = ""
        Thread {
            val cfg = AiConfigStore.load()
            val prompt = "请把下面的文字翻译成" + Lang.targetName(tgt) + "，不要启用思考过程，直接回复翻译内容，不要任何解释：\n\n" + selText
            val r = AiClient.chat(cfg, prompt, cfg.maxTokens)
            if (tok != selReqToken) return@Thread
            if (r.ok) {
                selTran = r.reply.trim()
                selCache.save("sel", selText, tgt, selTran)
                TestLog.evt("sel_ai_done", "len=" + selTran.length)
            } else {
                selErr = "翻译失败：" + r.error + (if (r.detail.isNotBlank()) " " + r.detail.take(80) else "")
                TestLog.evt("sel_ai_fail", r.error)
            }
            selBusy = false
        }.apply { isDaemon = true }.start()
    }

    if (dictOpen) {
        Dialog(onDismissRequest = { dictOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(L10n.s("词典 · ", "Dict · ") + dictWord.take(40), color = UiTheme.p.textPrimary, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { dictOpen = false }) { Text(L10n.s("关闭", "Close")) }
                    }
                    val inst = remember { Dicts.installed() }
                    when {
                        inst.isEmpty() -> Text(
                            "未安装离线词典。\n把 StarDict 词典文件夹（含 .ifo/.idx/.dict）放入：\n" + Dicts.dictDir().absolutePath + "\n（偏好 → 离线词典 可直接打开该文件夹）",
                            color = UiTheme.p.textTertiary, fontSize = 12.sp)
                        dictResults.isEmpty() -> Text(L10n.s("${inst.size} 本词典均未命中该词条。", "No entry found in ${inst.size} dictionaries."), color = UiTheme.p.textFaint, fontSize = 12.sp)
                        else -> LazyColumn {
                            items(dictResults.size) { i ->
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    Text(dictResults[i].first, color = UiTheme.p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(dictResults[i].second, color = UiTheme.p.textPrimary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 速读 RSVP（v0.52.0 对齐安卓 DialogSpeedRead）：逐词展示当前页，可调速/暂停/自动续页 ----
    if (rsvpOn) {
        val rWords = remember(path, page, doc, layoutRev) {
            runCatching { doc?.pageParagraphs(page)?.joinToString(" ").orEmpty() }.getOrDefault("").let { rsvpSplit(it) }
        }
        LaunchedEffect(rsvpOn, rsvpIdx, rsvpPause, rsvpWpm, page) {
            if (rsvpOn && !rsvpPause) {
                delay((60_000L / rsvpWpm).coerceAtLeast(50L))
                if (rWords.isEmpty()) { if (page < pageCount - 1) { jumpTo(page + 1); rsvpIdx = 0 } }
                else if (rsvpIdx + 1 >= rWords.size) {
                    if (page < pageCount - 1) { jumpTo(page + 1); rsvpIdx = 0 }
                } else rsvpIdx++
            }
        }
        Dialog(onDismissRequest = { rsvpOn = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = Color(0xF2000000)) {
                Box(Modifier.fillMaxSize().clickable { rsvpPause = !rsvpPause }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(rWords.getOrElse(rsvpIdx) { "" }, color = Color.White, fontSize = 52.sp,
                            textAlign = TextAlign.Center, maxLines = 2)
                        Spacer(Modifier.height(14.dp))
                        Text(L10n.s("${rsvpIdx + 1}/${rWords.size} 词 · 第 ${page + 1}/$pageCount 页",
                            "${rsvpIdx + 1}/${rWords.size} words · p${page + 1}/$pageCount") +
                            (if (rsvpPause) L10n.s(" · 已暂停（点击继续）", " · paused (click to resume)") else ""),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 26.dp)) {
                            TextButton(onClick = {
                                rsvpWpm = when (rsvpWpm) { 200 -> 300; 300 -> 450; 450 -> 600; 600 -> 800; else -> 200 }
                                LibPrefs.put("rsvpWpm", "$rsvpWpm")
                                TestLog.evt("rsvp_wpm", "$rsvpWpm")
                            }) { Text("${rsvpWpm} wpm", color = Color(0xFF9CC3FF), fontSize = 14.sp) }
                            TextButton(onClick = { rsvpPause = !rsvpPause }) {
                                Text(if (rsvpPause) L10n.s("继续", "Play") else L10n.s("暂停", "Pause"), color = Color.White, fontSize = 14.sp)
                            }
                            TextButton(onClick = { rsvpOn = false; TestLog.evt("rsvp", "off") }) {
                                Text(L10n.s("关闭", "Close"), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    val theme = ReadThemes.byId(prefs.readTheme)
    val bg = readerBg(theme)

    // ---- 页面位图 LRU 缓存 + PAGE 模式邻页后台预取：翻页即时出图（安卓 OpenPrefetch 同思路） ----
    val pageCache = remember(doc, renderW, theme, layoutRev, trimWhite) {
        // 检视修复：位图按字节限容（默认堆下 4K×4x 单页可达数百 MB，仅限条数必 OOM）
        object : LinkedHashMap<Int, BufferedImage>(16, 0.75f, true) {
            val maxBytes = 192L * 1024 * 1024
            var bytes = 0L
            private fun trim() {
                while ((size > 6 || bytes > maxBytes) && size > 1) {
                    val it = entries.iterator()
                    if (!it.hasNext()) break
                    val e = it.next()
                    it.remove()
                    bytes -= e.value.width.toLong() * e.value.height * 4
                }
            }
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BufferedImage>): Boolean = false
            override fun put(key: Int, value: BufferedImage): BufferedImage? {
                val old = super.put(key, value)
                if (old != null) bytes -= old.width.toLong() * old.height * 4
                bytes += value.width.toLong() * value.height * 4
                trim()
                return old
            }
        }
    }
    LaunchedEffect(doc, page, renderW, theme, viewMode, layoutRev) {
        val d = doc ?: return@LaunchedEffect
        if (viewMode != ViewMode.PAGE || renderW <= 0) return@LaunchedEffect
        delay(120) // 当前页渲染优先拿引擎锁
        for (np in intArrayOf(page + 1, page - 1)) {
            if (np < 0 || (pageCount > 0 && np >= pageCount)) continue
            if (synchronized(pageCache) { pageCache[np] } != null) continue
            val bmp = withContext(Dispatchers.Default) {
                runCatching { d.render(np, renderW, pixelMode(theme), pixelTint(theme)) }.getOrNull()
            }
            if (bmp != null) synchronized(pageCache) { pageCache[np] = bmp }
        }
    }


    Column(Modifier.fillMaxSize().background(bg)) {
        // ---- 工具栏（沉浸模式隐藏） ----
        if (!immersive) Row(
            Modifier.fillMaxWidth().height(40.dp).background(UiTheme.p.bar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBtn(L10n.s("换书", "Switch"), panel == Panel.RECENT) { panel = if (panel == Panel.RECENT) Panel.NONE else Panel.RECENT }
            ToolBtn(L10n.s("目录", "TOC"), panel == Panel.TOC) { panel = if (panel == Panel.TOC) Panel.NONE else Panel.TOC }
            ToolBtn(L10n.s("书签", "Mark"), panel == Panel.BOOKMARK) { panel = if (panel == Panel.BOOKMARK) Panel.NONE else Panel.BOOKMARK }
            ToolBtn(L10n.s("回退", "Back"), navBack.isNotEmpty()) { navGoBack() }
            ToolBtn(L10n.s("前进", "Fwd"), navFwd.isNotEmpty()) { navGoFwd() }
            ToolBtn(L10n.s("搜索", "Search"), panel == Panel.SEARCH) { panel = if (panel == Panel.SEARCH) Panel.NONE else Panel.SEARCH }
            ToolBtn(L10n.s("译", "Tr"), panel == Panel.TRANSLATE) { panel = if (panel == Panel.TRANSLATE) Panel.NONE else Panel.TRANSLATE }
            ToolBtn(L10n.s("双语", "Bi"), bilingual) {
                bilingual = !bilingual
                TestLog.evt("bilingual_toggle", if (bilingual) "on" else "off")
            }
            ToolBtn(L10n.s("问", "Ask"), panel == Panel.QA) { panel = if (panel == Panel.QA) Panel.NONE else Panel.QA }
            ToolBtn(
                if (ttsOn) L10n.s("停读", "Stop") + (if (ttsProg.isEmpty()) "" else " $ttsProg")
                else L10n.s("朗读", "Speak"),
                ttsOn,
            ) {
                if (ttsOn) {
                    TtsSpeaker.cancel()
                    ttsRunId++
                    ttsOn = false
                    ttsProg = ""
                    TestLog.evt("tts_stop", "manual")
                } else {
                    speakPage(page)
                }
            }
            ToolBtn(L10n.s("连读", "Auto"), ttsCont) {
                ttsCont = !ttsCont
                TestLog.evt("tts_cont", if (ttsCont) "on" else "off")
            }
            ToolBtn(if (ttsTimerMin > 0) L10n.s("定时", "Timer") + ttsTimerMin + "m" else L10n.s("定时", "Timer"),
                ttsTimerMin > 0) {
                ttsTimerMin = when (ttsTimerMin) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }
                TestLog.evt("tts_timer", if (ttsTimerMin > 0) "set=" + ttsTimerMin + "m" else "off")
            }
            // 导出当前页朗读为 WAV（v0.52.0 对齐安卓 TTS 导出音频）
            ToolBtn(L10n.s("导出", "Export")) {
                val d0 = doc
                if (d0 == null) { ttsExpMsg = L10n.s("书未打开", "No book"); return@ToolBtn }
                val paras = runCatching { d0.pageParagraphs(page) }.getOrDefault(emptyList())
                if (paras.isEmpty()) { ttsExpMsg = L10n.s("本页无可读文本", "No text"); return@ToolBtn }
                val parent = File(path).parentFile?.takeIf { it.isDirectory } ?: AppPaths.dataDir
                // v0.56 音频文件默认目录（偏好→备份配置→音频文件；安卓 Download/HowRead/TTS 同语义）
                val ttsDir = java.io.File(java.io.File(AppPaths.dataDir, "downloads"), "TTS")
                val baseDir = if (ttsDir.isDirectory || ttsDir.mkdirs()) ttsDir else parent
                val chooser = javax.swing.JFileChooser(baseDir)
                chooser.dialogTitle = L10n.s("导出朗读音频（WAV）", "Export speech audio (WAV)")
                chooser.selectedFile = File(baseDir, File(path).nameWithoutExtension.take(40) + "-p" + (page + 1) + ".wav")
                javax.swing.SwingUtilities.invokeLater {
                    if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) { ttsExpMsg = ""; return@invokeLater }
                    val outF = chooser.selectedFile
                    ttsExpMsg = L10n.s("导出中…", "Exporting…")
                    TestLog.evt("tts_export_begin", "page=${page + 1} paras=" + paras.size)
                    val t0 = System.currentTimeMillis()
                    TtsSpeaker.export(paras, outF, { ok2, m ->
                        ttsExpMsg = if (ok2) L10n.s("已导出 ", "Exported ") + outF.name else m
                        TestLog.evt(if (ok2) "tts_export_ui" else "tts_export_ui_fail",
                            outF.name + " ms=" + (System.currentTimeMillis() - t0))
                    }) { i, n -> ttsExpMsg = L10n.s("导出中", "Exporting") + " $i/$n" }
                }
            }
            if (isReflowable) {
                ToolBtn(L10n.s("排", "Layout"), panel == Panel.LAYOUT) { panel = if (panel == Panel.LAYOUT) Panel.NONE else Panel.LAYOUT }
                ToolBtn("A-") { fontScale = (fontScale - 10).coerceIn(60, 220); ReaderPrefsStore.save(prefs.copy(fontScale = fontScale)); TestLog.evt("font_change", "scale=$fontScale") }
                ToolBtn("A+") { fontScale = (fontScale + 10).coerceIn(60, 220); ReaderPrefsStore.save(prefs.copy(fontScale = fontScale)); TestLog.evt("font_change", "scale=$fontScale") }
                ToolBtn("${fontScale}%") {}
            } else {
                ToolBtn("-") { zoomPdf = (zoomPdf / 1.15f).coerceIn(0.5f, 4f) }
                ToolBtn("+") { zoomPdf = (zoomPdf * 1.15f).coerceIn(0.5f, 4f) }
                ToolBtn("x${"%.2f".format(zoomPdf)}") {}
            }
            ToolBtn(
                if (theme.id == "day") L10n.s("背景", "Theme") else L10n.s("背景·", "Theme·") + theme.label,
                themeMenu,
            ) { themeMenu = !themeMenu }
            // 裁白边（v0.51.0 对齐安卓 CropImage）：按页面背景色裁掉四周空白，翻页/滚动两模式生效
            ToolBtn(if (trimWhite) L10n.s("白边·裁", "Trim·on") else L10n.s("白边", "Trim"), trimWhite) {
                trimWhite = !trimWhite
                LibPrefs.put("trimWhite", trimWhite.toString())
                TestLog.evt("trim_white", if (trimWhite) "on" else "off")
            }
            // 双页并排（v0.52.0 对齐安卓横屏双页）：翻页两页并排/滚动双列，翻页步进 2
            ToolBtn(if (twoPage) L10n.s("双页 ✓", "2pg ✓") else L10n.s("双页", "2pg"), twoPage) {
                twoPage = !twoPage
                LibPrefs.put("twoPage", twoPage.toString())
                TestLog.evt("two_page", if (twoPage) "on" else "off")
            }
            ToolBtn(L10n.s("速读", "RSVP"), rsvpOn) {
                rsvpOn = !rsvpOn
                rsvpIdx = 0
                rsvpPause = false
                TestLog.evt("rsvp", if (rsvpOn) "on page=${page + 1}" else "off")
            }
            ToolBtn(if (viewMode == ViewMode.PAGE)
                LibPrefs.str("modeNameScroll", "").ifBlank { L10n.s("滚动", "Scroll") }
            else LibPrefs.str("modeNamePage", "").ifBlank { L10n.s("翻页", "Paged") }) {
                viewMode = if (viewMode == ViewMode.PAGE) ViewMode.SCROLL else ViewMode.PAGE
                TestLog.evt("view_toggle", viewMode.name)
            }
            if (viewMode == ViewMode.SCROLL) {
                // 自动滚动（滚动模式）：开关 + 速度循环（像素/帧，2..30）
                ToolBtn(if (autoScrollOn) L10n.s("自动·", "Auto·") else L10n.s("自动", "Auto")) {
                    autoScrollOn = !autoScrollOn
                    TestLog.evt("auto_scroll", if (autoScrollOn) "on speed=$autoScrollSpeed" else "off")
                }
                ToolBtn("x$autoScrollSpeed") {
                    autoScrollSpeed = if (autoScrollSpeed >= 30) 2 else autoScrollSpeed + 2
                    LibPrefs.put("autoScrollSpeed", "$autoScrollSpeed")
                    TestLog.evt("auto_scroll_speed", "$autoScrollSpeed")
                }
            } else {
                // 自动翻页（翻页模式）：开关 + 间隔循环（5/10/30/60 秒）
                ToolBtn(if (autoFlipOn) L10n.s("自动·", "Auto·") else L10n.s("自动", "Auto")) {
                    autoFlipOn = !autoFlipOn
                    TestLog.evt("auto_flip", if (autoFlipOn) "on sec=$autoFlipSec" else "off")
                }
                ToolBtn("${autoFlipSec}s") {
                    autoFlipSec = when (autoFlipSec) { 5 -> 10; 10 -> 30; 30 -> 60; else -> 5 }
                    LibPrefs.put("autoFlipSec", "$autoFlipSec")
                    TestLog.evt("auto_flip_sec", "$autoFlipSec")
                }
            }
            ToolBtn(L10n.s("沉浸", "Immersive")) {
                immersive = !immersive
                TestLog.evt("immersive", if (immersive) "on" else "off")
            }
            Spacer(Modifier.weight(1f))
            if (ttsExpMsg.isNotEmpty()) Text(ttsExpMsg.take(36), color = UiTheme.p.textFaint, fontSize = 11.sp,
                maxLines = 1, modifier = Modifier.padding(end = 6.dp))
            Text(if (pageCount > 0) "${page + 1} / $pageCount" else "…", color = UiTheme.p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(end = 4.dp))
            ToolBtn(L10n.s("关闭", "Close")) { requestClose() }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- 页面区 ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
                    .onSizeChanged { sz ->
                        // 视口冻结（v0.31.0）：只在首排前取一次——窗口缩放不再触发整书重排/重渲染（卡顿源移除）
                        if (!viewportLocked && sz.width > 0) {
                            viewportW = sz.width
                            viewportH = sz.height
                            viewportLocked = true
                        }
                    }
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.F11 -> {
                                immersive = !immersive
                                TestLog.evt("immersive", if (immersive) "on" else "off")
                                true
                            }
                            Key.Escape -> if (immersive) { immersive = false; true } else { requestClose(); true }
                            // Alt+←/→=链接历史回退/前进（v0.51.0；CMP 桌面修饰键取包装的 AWT 事件）；无 Alt 方向键仍直接翻页
                            Key.DirectionLeft, Key.DirectionRight -> {
                                val altNav = if (viewMode == ViewMode.PAGE)
                                    runCatching { (e.nativeKeyEvent as java.awt.event.KeyEvent).isAltDown }.getOrDefault(false) else false
                                when {
                                    viewMode != ViewMode.PAGE -> false
                                    altNav -> { if (e.key == Key.DirectionLeft) navGoBack() else navGoFwd(); true }
                                    else -> { jumpTo(page + stepSize() * if (e.key == Key.DirectionRight) 1 else -1); true }
                                }
                            }
                            else -> if (viewMode != ViewMode.PAGE) false else when (e.key) {
                                Key.PageDown -> { jumpTo(page + stepSize()); true }
                                Key.PageUp -> { jumpTo(page - stepSize()); true }
                                Key.MoveHome -> { jumpTo(0); true }
                                Key.MoveEnd -> { jumpTo(pageCount - 1); true }
                                else -> false
                            }
                        }
                    }
                    .pointerInput(viewMode, pageCount) {
                        if (viewMode == ViewMode.PAGE) {
                            detectTapGestures { offset ->
                                if (selPopup || selQuads.isNotEmpty()) {
                                    selPopup = false
                                    selQuads = emptyList()
                                }
                                val third = size.width / 3f
                                when {
                                    offset.x < third -> { jumpTo(page - stepSize()); TestLog.evt("zone_click", "side=left to=${page + 1}") }
                                    offset.x > third * 2 -> { jumpTo(page + stepSize()); TestLog.evt("zone_click", "side=right to=${page + 1}") }
                                    else -> TestLog.evt("zone_click", "side=center")
                                }
                            }
                        }
                    }
                    .pointerInput(viewMode, page, pageCount) {
                        if (viewMode != ViewMode.PAGE) return@pointerInput
                        val padX = 24.dp.toPx()
                        val padY = 12.dp.toPx()
                        fun toPage(off: Offset): Pair<Float, Float>? {
                            val pb = pageBoxArr ?: return null
                            val availW = size.width - padX * 2
                            val availH = size.height - padY * 2
                            val pw = maxOf(1f, pb[2] - pb[0])
                            val ph = maxOf(1f, pb[3] - pb[1])
                            val sc = minOf(availW / pw, availH / ph)
                            val offX = padX + (availW - pw * sc) / 2
                            val offY = padY + (availH - ph * sc) / 2
                            val x = pb[0] + (off.x - offX) / sc
                            val y = pb[1] + (off.y - offY) / sc
                            if (x < pb[0] || x > pb[2] || y < pb[1] || y > pb[3]) return null
                            return x to y
                        }
                        var hlKey = -999f
                        detectDragGestures(
                            onDragStart = { off ->
                                val m = toPage(off)
                                selAnchor = m
                                selCur = m
                                selQuads = emptyList()
                                selPopup = false
                            },
                            onDrag = { change, _ ->
                                val m = toPage(change.position) ?: return@detectDragGestures
                                selCur = m
                                val a = selAnchor ?: return@detectDragGestures
                                // 距上次取词 >3 页面单位才重算高亮，拖动高频不打引擎
                                if (abs(m.first - hlKey) > 3f) {
                                    hlKey = m.first
                                    scope.launch(Dispatchers.Default) {
                                        val r = runCatching { doc?.selection(page, a.first, a.second, m.first, m.second) }
                                            .getOrNull() ?: ("" to emptyList())
                                        selQuads = r.second
                                    }
                                }
                            },
                            onDragEnd = {
                                val a = selAnchor
                                val c = selCur
                                if (a == null || c == null) return@detectDragGestures
                                if (abs(a.first - c.first) < 6f && abs(a.second - c.second) < 6f) {
                                    selQuads = emptyList(); selPopup = false
                                    return@detectDragGestures
                                }
                                scope.launch(Dispatchers.Default) {
                                    val r = runCatching { doc?.selection(page, a.first, a.second, c.first, c.second) }
                                        .getOrNull() ?: ("" to emptyList())
                                    if (r.first.isBlank()) {
                                        selQuads = emptyList(); selPopup = false
                                        return@launch
                                    }
                                    selText = r.first; selQuads = r.second; selPopup = true
                                    TestLog.evt("sel_text", "len=" + r.first.length + " page=" + (page + 1))
                                    runSelTranslate()
                                }
                            },
                        )
                    },
            ) {
                val d = doc
                if (d == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (docOpening) (if (remoteMsg.isEmpty()) L10n.s("正在打开…", "Opening…") else remoteMsg)
                            else L10n.s("打开失败：" + openError.take(80), "Open failed: " + openError.take(80)),
                            color = UiTheme.p.textTertiary, fontSize = 14.sp,
                        )
                    }
                } else if (isReflowable && !layoutReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(L10n.s("排版中…", "Laying out…"), color = UiTheme.p.textTertiary, fontSize = 14.sp)
                    }
                } else if (bilingual) {
                    BilingualView(aiSession, aiTick, aiTgtSel, onSelectTgt = { aiTgtSel = it }, fontScale = fontScale)
                } else if (viewMode == ViewMode.PAGE) {
                    if (renderW > 0) {
                        if (twoPage) {
                            // 双页并排：page + page+1 同屏（末页单页留白），翻页步进 2（快速导航条可精确落任意页）
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    PageImage(d, page, renderW, pixelMode(theme), pixelTint(theme), pageCache, layoutRev,
                                        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), trimWhite = trimWhite)
                                }
                                if (page + 1 < pageCount) {
                                    Box(Modifier.weight(1f).fillMaxHeight()) {
                                        PageImage(d, page + 1, renderW, pixelMode(theme), pixelTint(theme), pageCache, layoutRev,
                                            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), trimWhite = trimWhite)
                                    }
                                } else Spacer(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            PageImage(d, page, renderW, pixelMode(theme), pixelTint(theme), pageCache, layoutRev,
                                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), trimWhite = trimWhite)
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(L10n.s("渲染中…", "Rendering…"), color = UiTheme.p.textTertiary, fontSize = 14.sp) }
                    }
                } else {
                    ScrollView(d, pageCount, theme, page, zoomPdf, scrollJumpToken, isReflowable, layoutRev, pageCache,
                        onPageFromScroll = { page = it },
                        autoScroll = autoScrollOn, autoScrollSpeed = autoScrollSpeed, trimWhite = trimWhite, twoPage = twoPage)
                }
                // 背景质感（v0.29.0）：纸/布纹理乘色叠加（仅浅色主题），纯显示层不触发重排
                if (!theme.dark && prefs.readTexture != "none") {
                    val tex = remember(prefs.readTexture) { Res.bitmap("howread/tex/" + prefs.readTexture + ".jpg") }
                    if (tex != null) {
                        Canvas(Modifier.matchParentSize()) {
                            drawImage(
                                tex,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                                alpha = 0.45f,
                                blendMode = BlendMode.Multiply,
                            )
                        }
                    }
                }
                if (layoutBusy) {
                    Text(L10n.s("重排中…", "Relayout…"), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        color = UiTheme.p.textFaintest, fontSize = 11.sp)
                }
                if (themeMenu) {
                    ThemeMenu(
                        current = theme.id,
                        texture = prefs.readTexture,
                        onPick = { tp ->
                            themeMenu = false
                            if (tp.id != prefs.readTheme) {
                                prefs = prefs.copy(readTheme = tp.id)
                                ReaderPrefsStore.save(prefs)
                                prefsRev++
                                TestLog.evt("theme_change", tp.id)
                            }
                        },
                        onPickTexture = { tid ->
                            if (tid != prefs.readTexture) {
                                prefs = prefs.copy(readTexture = tid)
                                ReaderPrefsStore.save(prefs)
                                TestLog.evt("texture_change", tid)
                            }
                        },
                        onClose = { themeMenu = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
                    )
                }
                Text(
                    File(path).nameWithoutExtension,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
                    color = UiTheme.p.textFaintest, fontSize = 11.sp,
                )
                if (selQuads.isNotEmpty() && pageBoxArr != null) {
                    Canvas(Modifier.matchParentSize()) {
                        val pb = pageBoxArr ?: return@Canvas
                        val padX = 24.dp.toPx()
                        val padY = 12.dp.toPx()
                        val availW = size.width - padX * 2
                        val availH = size.height - padY * 2
                        val pw = maxOf(1f, pb[2] - pb[0])
                        val ph = maxOf(1f, pb[3] - pb[1])
                        val sc = minOf(availW / pw, availH / ph)
                        val offX = padX + (availW - pw * sc) / 2
                        val offY = padY + (availH - ph * sc) / 2
                        val c = UiTheme.p.accent.copy(alpha = 0.30f)
                        for (q in selQuads) {
                            drawRect(
                                color = c,
                                topLeft = Offset(offX + (q.x0 - pb[0]) * sc, offY + (q.y0 - pb[1]) * sc),
                                size = Size((q.x1 - q.x0) * sc, (q.y1 - q.y0) * sc),
                            )
                        }
                    }
                }
                if (hlList.isNotEmpty() && pageBoxArr != null) {
                    Canvas(Modifier.matchParentSize()) {
                        val pb = pageBoxArr ?: return@Canvas
                        val padX = 24.dp.toPx()
                        val padY = 12.dp.toPx()
                        val availW = size.width - padX * 2
                        val availH = size.height - padY * 2
                        val pw = maxOf(1f, pb[2] - pb[0])
                        val ph = maxOf(1f, pb[3] - pb[1])
                        val sc = minOf(availW / pw, availH / ph)
                        val offX = padX + (availW - pw * sc) / 2
                        val offY = padY + (availH - ph * sc) / 2
                        for (a in hlList) {
                            val c = Color(a.color).copy(alpha = 0.32f)
                            for (q in a.quads) {
                                drawRect(
                                    color = c,
                                    topLeft = Offset(offX + (q.x0 - pb[0]) * sc, offY + (q.y0 - pb[1]) * sc),
                                    size = Size((q.x1 - q.x0) * sc, (q.y1 - q.y0) * sc),
                                )
                            }
                        }
                    }
                }
                if (selPopup) {
                    SelectionPopup(
                        selText = selText, selTran = selTran, selBusy = selBusy, selErr = selErr, selTgt = selTgt,
                        onTgt = { selTgt = it; runSelTranslate() },
                        onClose = { selPopup = false; selQuads = emptyList(); selAnchor = null; selCur = null },
                        onCopy = {
                            clipboard.setText(AnnotatedString(selText))
                            TestLog.evt("sel_copy", "len=" + selText.length)
                        },
                        onSpeak = {
                            ttsOn = true
                            ttsRunId++
                            TestLog.evt("sel_speak", "len=" + selText.length)
                            TtsSpeaker.speak(listOf(selText), onDone = { ttsOn = false; ttsProg = "" })
                        },
                        onBookmark = {
                            Db.addBookmark(path, page, "第${page + 1}页", selText.take(200))
                            bookmarks = Db.listBookmarks(path)
                            TestLog.evt("sel_bm", "page=" + (page + 1))
                        },
                        onDict = { runDictLookup(selText) },
                        onWebSearch = {
                            // 选区网络搜索（安卓 WebSearch 等价）：搜索引擎可在偏好里选
                            val q = selText.take(60)
                            val base = when (LibPrefs.str("webSearchEngine", "google")) {
                                "bing" -> "https://www.bing.com/search?q="
                                "baidu" -> "https://www.baidu.com/s?wd="
                                "duckduckgo" -> "https://duckduckgo.com/?q="
                                else -> "https://www.google.com/search?q="
                            }
                            val url = base + java.net.URLEncoder.encode(q, "UTF-8")
                            TestLog.evt("sel_websearch", q)
                            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                        },
                        // 选区分享为图片（v0.52.0 安卓"分享为图片"桌面等价）：文字合成为 PNG，复制剪贴板/存盘
                        onCopyImage = {
                            TestLog.evt("sel_image_copy", "len=" + selText.length)
                            scope.launch(Dispatchers.Default) {
                                val img = runCatching { selectionImage(selText, selTran) }.getOrNull() ?: return@launch
                                val fl = java.awt.datatransfer.DataFlavor.imageFlavor
                                javax.swing.SwingUtilities.invokeLater {
                                    runCatching {
                                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                            object : java.awt.datatransfer.Transferable {
                                                override fun getTransferDataFlavors() = arrayOf(fl)
                                                override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) = f == fl
                                                override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any = img
                                            }, null)
                                    }.onFailure { TestLog.evt("sel_image_copy_fail", it.toString().take(80)) }
                                }
                            }
                        },
                        onSaveImage = {
                            TestLog.evt("sel_image_save_begin", "len=" + selText.length)
                            scope.launch(Dispatchers.Default) {
                                val img = runCatching { selectionImage(selText, selTran) }.getOrNull()
                                if (img == null) { TestLog.evt("sel_image_save_fail", "render"); return@launch }
                                val defDir = File(path).parentFile?.takeIf { it.isDirectory } ?: AppPaths.dataDir
                                val chooser = javax.swing.JFileChooser(defDir)
                                chooser.dialogTitle = L10n.s("保存选区图片（PNG）", "Save selection image (PNG)")
                                chooser.selectedFile = File(defDir, File(path).nameWithoutExtension.take(30) + "-p" + (page + 1) + ".png")
                                javax.swing.SwingUtilities.invokeLater {
                                    if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return@invokeLater
                                    val okp = runCatching { javax.imageio.ImageIO.write(img, "png", chooser.selectedFile) }.getOrDefault(false)
                                    TestLog.evt(if (okp) "sel_image_save" else "sel_image_save_fail", chooser.selectedFile.name)
                                }
                            }
                        },
                        onHighlight = { c ->
                            Db.addAnnotation(path, page, c, selQuads.map { AnnQuad(it.x0, it.y0, it.x1, it.y1) },
                                selText.take(200), pageCount)
                            hlList = Db.listAnnotations(path, page).filter { it.pageCount == pageCount }
                            hlAll = Db.listAllAnnotations(path)
                            selPopup = false; selQuads = emptyList(); selAnchor = null; selCur = null
                            TestLog.evt("hl_add", "page=" + (page + 1) + " color=" + c)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    )
                }

            }

            // ---- 侧板 ----
            if (panel != Panel.NONE) {
                Column(
                    Modifier.width(320.dp).fillMaxHeight().background(UiTheme.p.panel).padding(10.dp)
                ) {
                    when (panel) {
                        Panel.RECENT -> {
                            PanelTitle(L10n.s("换书 · 最近阅读（点书名直达）", "Switch book · Recent"))
                            if (recentBooks.isEmpty()) {
                                Text(L10n.s("暂无最近阅读", "No recent books"), color = UiTheme.p.textFaint, fontSize = 13.sp)
                            }
                            LazyColumn {
                                items(recentBooks.size) { i ->
                                    val b = recentBooks[i]
                                    if (b.path != path) {
                                        TextButton(onClick = {
                                            TestLog.evt("reader_switchbook", "to=" + b.title)
                                            panel = Panel.NONE
                                            onOpenBook(b.path)
                                        }) {
                                            Text(b.title, color = UiTheme.p.textSecondary, fontSize = 13.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        Panel.TOC -> {
                            PanelTitle(L10n.s("目录（${toc.size}）", "TOC (${toc.size})"))
                            if (toc.isEmpty()) Text(L10n.s("无目录", "No TOC"), color = UiTheme.p.textFaint, fontSize = 13.sp)
                            LazyColumn {
                                items(toc.size) { i ->
                                    val t = toc[i]
                                    if (t.page >= 0) {
                                        TextButton(onClick = { jumpNav(t.page) }) {
                                            Text(
                                                t.title.ifBlank { "（无标题）" },
                                                color = UiTheme.p.textSecondary, fontSize = 13.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = (t.level * 12).dp),
                                            )
                                        }
                                    } else {
                                        Text(
                                            t.title.ifBlank { "（无标题）" },
                                            color = UiTheme.p.textTertiary, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(start = (t.level * 12).dp, top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Panel.BOOKMARK -> {
                            PanelTitle(L10n.s("书签 / 笔记", "Bookmarks / Notes"))
                            Row {
                                TextButton(onClick = {
                                    Db.addBookmark(path, page, "第${page + 1}页", "")
                                    bookmarks = Db.listBookmarks(path)
                                    TestLog.evt("bm_add", "page=${page + 1}")
                                }) { Text(L10n.s("＋ 在第${page + 1}页添加", "+ Add at p${page + 1}"), color = UiTheme.p.accent, fontSize = 13.sp) }
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                items(bookmarks.size) { i ->
                                    val b = bookmarks[i]
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { jumpNav(b.page) }) {
                                                Text(b.label, color = UiTheme.p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            TextButton(onClick = {
                                                editingBm = b; noteDraft = b.note
                                            }) { Text(L10n.s("笔记", "Note"), fontSize = 11.sp, color = UiTheme.p.textTertiary) }
                                            TextButton(onClick = {
                                                Db.deleteBookmark(b.id)
                                                bookmarks = Db.listBookmarks(path)
                                                TestLog.evt("bm_del", "id=${b.id}")
                                            }) { Text(L10n.s("删", "Del"), fontSize = 11.sp, color = UiTheme.p.danger) }
                                        }
                                        if (b.note.isNotBlank()) {
                                            Text(b.note, color = UiTheme.p.textTertiary, fontSize = 12.sp, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                                item {
                                    if (hlAll.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                                            Text(L10n.s("高亮（${hlAll.size}）", "Highlights (${hlAll.size})"), color = UiTheme.p.textSecondary, fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                            if (FormatRouter.ext(path) == "pdf") {
                                                TextButton(onClick = {
                                                    if (wbBusy) return@TextButton
                                                    wbBusy = true
                                                    wbMsg = ""
                                                    Thread {
                                                        val items = hlAll.filter { it.pageCount == pageCount }.map { row ->
                                                            Triple(row.page, row.color, row.quads.map { q -> SelRect(q.x0, q.y0, q.x1, q.y1) })
                                                        }
                                                        val dest = File(File(path).parentFile, File(path).nameWithoutExtension + "-标注.pdf")
                                                        val ok = runCatching { doc?.exportPdfWithHighlights(dest.absolutePath, items) }.getOrNull() ?: false
                                                        wbMsg = if (ok) "已写回：" + dest.name else "写回失败（仅支持 PDF）"
                                                        wbBusy = false
                                                        TestLog.evt(if (ok) "pdf_writeback" else "pdf_writeback_fail", dest.name)
                                                    }.apply { isDaemon = true }.start()
                                                }, enabled = !wbBusy) { Text(L10n.s("写回PDF", "Save PDF"), fontSize = 11.sp, color = UiTheme.p.accent) }
                                            }
                                        }
                                        if (wbMsg.isNotEmpty()) {
                                            Text(wbMsg, color = UiTheme.p.textTertiary, fontSize = 11.sp)
                                        }
                                        for (a in hlAll) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(vertical = 2.dp)) {
                                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp))
                                                    .background(Color(a.color)))
                                                TextButton(onClick = { jumpNav(a.page) }) {
                                                    Text(L10n.s("${a.page + 1}页", "p${a.page + 1}"), color = UiTheme.p.textTertiary, fontSize = 11.sp)
                                                }
                                                Text(a.note, color = UiTheme.p.textTertiary, fontSize = 11.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    Db.deleteAnnotation(a.id)
                                                    hlAll = Db.listAllAnnotations(path)
                                                    hlList = Db.listAnnotations(path, page).filter { it.pageCount == pageCount }
                                                    TestLog.evt("hl_del", "id=" + a.id)
                                                }) { Text(L10n.s("删", "Del"), fontSize = 11.sp, color = UiTheme.p.danger) }
                                            }
                                        }
                                    }
                                }
                            }
                            editingBm?.let { b ->
                                OutlinedTextField(
                                    value = noteDraft,
                                    onValueChange = { noteDraft = it },
                                    label = { Text("笔记 @${b.label}") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row {
                                    TextButton(onClick = {
                                        Db.updateBookmarkNote(b.id, noteDraft)
                                        editingBm = null
                                        bookmarks = Db.listBookmarks(path)
                                        TestLog.evt("bm_note_save", "id=${b.id} len=${noteDraft.length}")
                                    }) { Text(L10n.s("保存笔记", "Save note"), color = UiTheme.p.accent, fontSize = 13.sp) }
                                    TextButton(onClick = { editingBm = null }) { Text(L10n.s("取消", "Cancel"), fontSize = 13.sp) }
                                }
                            }
                        }
                        Panel.SEARCH -> {
                            PanelTitle(L10n.s("书内搜索", "Search in book"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    label = { Text(L10n.s("关键词", "Keyword")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { runSearch(query.trim()) }) { Text(L10n.s("搜", "Go"), color = UiTheme.p.accent) }
                            }
                            if (scanned >= 0 && scanned < pageCount) {
                                Text(L10n.s("扫描中 $scanned / $pageCount", "$scanned / $pageCount"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                            } else if (hits.isEmpty()) {
                                Text(L10n.s("无结果", "No results"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                            }
                            LazyColumn {
                                items(hits.size) { i ->
                                    val h = hits[i]
                                    TextButton(onClick = { jumpNav(h.page) }) {
                                        Text(L10n.s("第${h.page + 1} 页（${h.count} 处）", "p${h.page + 1} (${h.count})"), color = UiTheme.p.textSecondary, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        Panel.TRANSLATE -> {
                            PanelTitle(L10n.s("AI 翻译", "AI Translate"))
                            if (!AiConfigStore.configured()) {
                                Text("未配置 AI 大模型。请回书库窗口，点侧栏 \"AI 大模型…\" 填写 API Key 后重试。",
                                    color = UiTheme.p.textTertiary, fontSize = 13.sp)
                            } else {
                                val s = aiSession
                                if (s == null) {
                                    Text("启动中…", color = UiTheme.p.textFaint, fontSize = 12.sp)
                                } else {
                                    Row {
                                        ToolBtn(L10n.s("自动", "Auto"), aiTgtSel == "auto") { aiTgtSel = "auto" }
                                        ToolBtn(L10n.s("→中文", "→ZH"), aiTgtSel == Lang.ZH) { aiTgtSel = Lang.ZH }
                                        ToolBtn(L10n.s("→英文", "→EN"), aiTgtSel == Lang.EN) { aiTgtSel = Lang.EN }
                                    }
                                    val slots = remember(aiTick, s.displayedPage) { s.snapshot(s.displayedPage) }
                                    val done = slots.count { it.tran != null }
                                    val running = slots.count { it.started && it.tran == null && !it.failed }
                                    val failedN = slots.count { it.failed }
                                    Text(
                                        "第 ${s.displayedPage} 页 · ${slots.size} 段：完成 $done / 译中 $running" +
                                            (if (failedN > 0) " / 失败 $failedN" else ""),
                                        color = UiTheme.p.textFaint, fontSize = 11.sp,
                                    )
                                    LazyColumn {
                                        itemsIndexed(slots, key = { i, sl -> "${sl.page}_${i}_${sl.pid}" }) { _, sl ->
                                            Column(Modifier.padding(vertical = 6.dp)) {
                                                Text(sl.orig, color = UiTheme.p.textTertiary, fontSize = 12.sp)
                                                when {
                                                    sl.tran != null -> Text(
                                                        sl.tran!!, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                                                    )
                                                    sl.partial != null -> Text(
                                                        sl.partial!!, color = UiTheme.p.textSecondary, fontSize = 13.sp,
                                                    )
                                                    sl.failed -> Text(L10n.s("翻译失败", "Failed"), color = UiTheme.p.warn, fontSize = 12.sp)
                                                    sl.started -> Text(L10n.s("翻译中…", "Translating…"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                                                    else -> Text(L10n.s("待翻译", "Queued"), color = UiTheme.p.textFaintest, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Panel.QA -> {
                            PanelTitle(L10n.s("AI 问答", "AI Ask"))
                            if (!AiConfigStore.configured()) {
                                Text("未配置 AI 大模型。请回书库窗口，点侧栏 \"AI 大模型…\" 配置后重试。",
                                    color = UiTheme.p.textFaint, fontSize = 13.sp)
                            } else {
                                Text("基于当前页（第 ${page + 1} 页）内容回答，可连续追问。",
                                    color = UiTheme.p.textFaintest, fontSize = 11.sp)
                                LazyColumn(Modifier.weight(1f)) {
                                    itemsIndexed(qaMsgs, key = { i, _ -> i }) { _, m ->
                                        Column(Modifier.padding(vertical = 5.dp).fillMaxWidth()) {
                                            when {
                                                m.first == "user" -> Text(m.second, color = UiTheme.p.accent, fontSize = 13.sp)
                                                m.second.isEmpty() -> Text(L10n.s("思考中…", "Thinking…"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                                                else -> Text(m.second, color = UiTheme.p.textPrimary, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = qaDraft,
                                        onValueChange = { qaDraft = it },
                                        label = { Text(L10n.s("问当前页…", "Ask this page…")) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { askAi(qaDraft); qaDraft = "" }, enabled = !qaBusy && qaDraft.isNotBlank()) {
                                        Text(L10n.s("发送", "Send"), color = UiTheme.p.accent)
                                    }
                                }
                            }
                        }
                        Panel.LAYOUT -> {
                            PanelTitle(L10n.s("排版", "Layout"))
                            fun apply(p: ReaderPrefs, what: String) {
                                if (p == prefs) return
                                prefs = p; ReaderPrefsStore.save(p); prefsRev++
                                TestLog.evt("layout_change", what)
                            }
                            Text(L10n.s("字号 $fontScale%", "Size $fontScale%"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                            Slider(
                                value = fontScale.toFloat(),
                                onValueChange = { fontScale = it.toInt() / 10 * 10 },
                                onValueChangeFinished = {
                                    ReaderPrefsStore.save(prefs.copy(fontScale = fontScale))
                                    TestLog.evt("font_change", "scale=$fontScale")
                                },
                                valueRange = 60f..220f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(L10n.s("行距 ${prefs.lineHeight}%", "LH ${prefs.lineHeight}%"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                            Slider(
                                value = prefs.lineHeight.toFloat(),
                                onValueChange = { prefs = prefs.copy(lineHeight = it.toInt().coerceIn(100, 250)) },
                                onValueChangeFinished = {
                                    ReaderPrefsStore.save(prefs); prefsRev++
                                    TestLog.evt("layout_change", "lh=${prefs.lineHeight}")
                                },
                                valueRange = 100f..250f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (prefs.maxChars == 0) L10n.s("每行字数：全宽", "Chars/line: full")
                                else L10n.s("每行字数 ${prefs.maxChars}", "Chars/line ${prefs.maxChars}"),
                                color = UiTheme.p.textFaint, fontSize = 11.sp,
                            )
                            Slider(
                                value = (if (prefs.maxChars == 0) 40 else prefs.maxChars).toFloat(),
                                onValueChange = { prefs = prefs.copy(maxChars = (it.toInt() / 2 * 2).coerceIn(24, 80)) },
                                onValueChangeFinished = {
                                    ReaderPrefsStore.save(prefs)
                                    TestLog.evt("layout_change", "maxChars=${prefs.maxChars}")
                                },
                                valueRange = 24f..80f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ToolBtn(if (prefs.maxChars == 0) L10n.s("全宽 ✓", "Full ✓") else L10n.s("版心全宽", "Full width")) {
                                val v = if (prefs.maxChars == 0) 40 else 0
                                apply(prefs.copy(maxChars = v), "maxChars=$v")
                            }
                            Text(L10n.s("首行缩进 " + "%.1f".format(prefs.textIndent / 10f) + "em",
                                "Indent " + "%.1f".format(prefs.textIndent / 10f) + "em"),
                                color = UiTheme.p.textFaint, fontSize = 11.sp)
                            Slider(
                                value = prefs.textIndent.toFloat(),
                                onValueChange = { prefs = prefs.copy(textIndent = (it.toInt() / 2 * 2).coerceIn(0, 40)) },
                                onValueChangeFinished = {
                                    ReaderPrefsStore.save(prefs); prefsRev++
                                    TestLog.evt("layout_change", "indent=${prefs.textIndent}")
                                },
                                valueRange = 0f..40f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(L10n.s("段间距 " + "%.1f".format(prefs.paraSpacing / 5f) + "em",
                                "Para spacing " + "%.1f".format(prefs.paraSpacing / 5f) + "em"),
                                color = UiTheme.p.textFaint, fontSize = 11.sp)
                            Slider(
                                value = prefs.paraSpacing.toFloat(),
                                onValueChange = { prefs = prefs.copy(paraSpacing = it.toInt().coerceIn(0, 10)) },
                                onValueChangeFinished = {
                                    ReaderPrefsStore.save(prefs); prefsRev++
                                    TestLog.evt("layout_change", "para=${prefs.paraSpacing}")
                                },
                                valueRange = 0f..10f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField(L10n.s("上边距(pt)", "Top (pt)"), prefs.marginTop) { apply(prefs.copy(marginTop = it), "mt=$it") }
                                NumField(L10n.s("下边距(pt)", "Bottom (pt)"), prefs.marginBottom) { apply(prefs.copy(marginBottom = it), "mb=$it") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NumField(L10n.s("左边距(pt)", "Left (pt)"), prefs.marginLeft) { apply(prefs.copy(marginLeft = it), "ml=$it") }
                                NumField(L10n.s("右边距(pt)", "Right (pt)"), prefs.marginRight) { apply(prefs.copy(marginRight = it), "mr=$it") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for ((a, label) in listOf("left" to L10n.s("左对齐", "Left"), "justify" to L10n.s("两端", "Justify"), "right" to L10n.s("右对齐", "Right"), "center" to L10n.s("居中", "Center"))) {
                                    ToolBtn(label, prefs.align == a) { apply(prefs.copy(align = a), "align=$a") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(L10n.s("字体", "Font"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                                for ((fam, label) in listOf("" to L10n.s("书内", "Book"), "sans-serif" to L10n.s("无衬线", "Sans"), "monospace" to L10n.s("等宽", "Mono"))) {
                                    ToolBtn(label, prefs.family == fam) { apply(prefs.copy(family = fam), "family=$fam") }
                                }
                            }
                            val userFonts = remember(panel) { ReaderPrefsStore.listUserFonts() }
                            if (userFonts.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(L10n.s("自定义", "Custom"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                                    for (uf in userFonts.take(8)) {
                                        ToolBtn(uf.take(14), prefs.family == uf) { apply(prefs.copy(family = uf), "family=user:$uf") }
                                    }
                                }
                            }
                            Text(L10n.s("自定义字体：把 .ttf/.otf/.ttc 文件放入 ", "Custom fonts: put .ttf/.otf/.ttc into ") +
                                ReaderPrefsStore.fontsDir().absolutePath +
                                (if (userFonts.isEmpty()) L10n.s("（目录为空，文件名即字体名）", " (empty; filename = family name)") else "") +
                                L10n.s("，这里即可选用。", " and pick it here."),
                                color = UiTheme.p.textFaintest, fontSize = 10.sp)
                            Text("字体与行距等对书内强指定样式的书籍效果有限，可配合「书内样式：忽略」使用。",
                                color = UiTheme.p.textFaintest, fontSize = 10.sp)
                            ToolBtn(if (prefs.useDocumentCss) L10n.s("书内样式：遵循", "Book CSS: on") else L10n.s("书内样式：忽略", "Book CSS: off")) {
                                apply(prefs.copy(useDocumentCss = !prefs.useDocumentCss), "docCss=" + !prefs.useDocumentCss)
                            }
                            Text("改动立即重排，并按百分比保持阅读位置；PDF 等固定版式不受影响。",
                                color = UiTheme.p.textFaintest, fontSize = 10.sp)
                        }
                        Panel.NONE -> {}
                    }
                }
            }
        }

        // ---- 快速导航条（页模式底部；拖动即时跳页，对齐安卓快速导航条） ----
        if (!immersive && viewMode == ViewMode.PAGE && pageCount > 1) {
            var sliderPos by remember(path) { mutableFloatStateOf(page.toFloat()) }
            var sliderDragging by remember(path) { mutableStateOf(false) }
            LaunchedEffect(page) { if (!sliderDragging) sliderPos = page.toFloat() }
            Row(
                Modifier.fillMaxWidth().height(32.dp).background(UiTheme.p.bar),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${page + 1}", color = UiTheme.p.textTertiary, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 14.dp))
                Slider(
                    value = sliderPos.coerceIn(0f, (pageCount - 1).toFloat()),
                    onValueChange = {
                        sliderDragging = true
                        sliderPos = it
                        page = it.toInt().coerceIn(0, pageCount - 1)
                    },
                    onValueChangeFinished = {
                        sliderDragging = false
                        TestLog.evt("nav_slider", "to=${page + 1}")
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text("$pageCount", color = UiTheme.p.textTertiary, fontSize = 12.sp,
                    modifier = Modifier.padding(end = 14.dp))
            }
        }
        if (immersive) {
            Text(L10n.s("沉浸阅读 · F11 或 Esc 退出", "Immersive · F11 / Esc to exit"), color = UiTheme.p.textFaintest, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp))
        }
    }

    // ---- v0.29.0 冒烟探针（--smokegui 专用）：字号→重排→位图失效 / 纹理资源 / 窗口自适应 ----
    if (smoke) {
        LaunchedEffect(Unit) {
            try {
                var guard = 0
                while ((pageCount <= 0 || !countDone || renderW <= 0) && guard < 300) { delay(100); guard++ }
                val revBefore = layoutRev
                TestLog.evt("sm29_font_before", "scale=$fontScale w=$layoutW vw=$viewportW rev=$revBefore")
                fontScale = (fontScale + 30).coerceIn(60, 220)   // 与 A+ 按钮同一状态写入路径
                guard = 0
                while (layoutRev <= revBefore && guard < 200) { delay(100); guard++ }
                delay(2000)   // 等新纪元当前页渲染落图（PageImage produceState 随 epoch 重启）
                TestLog.evt("sm29_font_after", "scale=$fontScale w=$layoutW rev=$layoutRev")
                val texOk = Res.bitmap("howread/tex/paper.jpg") != null
                prefs = prefs.copy(readTexture = "paper")
                ReaderPrefsStore.save(prefs)
                delay(600)
                TestLog.evt("sm29_texture", if (texOk) "ok" else "missing")
                // v0.31.0：视口冻结——窗口缩放不再触发重排（去掉 resize 断言）
                smokeWindow?.size = DpSize(1400.dp, 900.dp)
                delay(1500)
                TestLog.evt("sm29_resize", "vw=$viewportW (frozen, no relayout) rev=$layoutRev")
                val relaid = layoutRev > revBefore
                // v0.52.0 双页并排断言：切翻页模式开双页 → 版心近半屏、纪元前移；测后还原
                val vw0 = viewportW
                viewMode = ViewMode.PAGE
                delay(600)
                val revT = layoutRev
                twoPage = true
                guard = 0
                while (layoutRev <= revT && guard < 200) { delay(100); guard++ }
                delay(1200)
                val twoOk = layoutW > 0 && layoutW * 2 < vw0 && layoutRev > revT
                TestLog.evt("sm52_two", "vw=$vw0 layoutW=$layoutW rev=$layoutRev ok=$twoOk")
                twoPage = false
                guard = 0
                val revU = layoutRev
                while (layoutRev <= revU && guard < 200) { delay(100); guard++ }
                val ok = texOk && relaid && twoOk
                TestLog.evt("sm29_done", if (ok) "OK" else "FAIL tex=$texOk relaid=$relaid")
                Sm29Probe.ok = ok
            } catch (t: Throwable) {
                TestLog.evt("sm29_done", "EXC $t")
                Sm29Probe.ok = false
            } finally {
                Sm29Probe.done = true
            }
        }
    }
}

/** 阅读区背景随阅读主题：白昼用浅色衬底（书页感），纸色系与页同色，夜间系恒暗。 */
private fun readerBg(t: ReadTheme): Color = when {
    t.id == "day" -> if (UiTheme.p.isLight) Color(0xFFE4E8EF) else UiTheme.DARK.bg
    else -> Color(t.bg)
}

/** 主题像素后处理映射（实测引擎不绘制 CSS body 底色，重排/固定版式统一走像素路径）：
     *  夜间系反相、浅色系按底色相乘（白底 → 主题底色，字色由 CSS 再柔化）。 */
private fun pixelMode(t: ReadTheme): ReadMode = if (t.dark) ReadMode.NIGHT else ReadMode.NORMAL

private fun pixelTint(t: ReadTheme): Int = if (!t.dark && t.id != "day") t.bg else 0

@Composable
private fun ToolBtn(label: String, active: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label, fontSize = 12.sp,
            color = if (active) UiTheme.p.accent else UiTheme.p.textTertiary,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 排版面板数字输入（边距，0..60）。 */
@Composable
private fun NumField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            s.toIntOrNull()?.let { if (it in 0..60) onChange(it) }
        },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        modifier = Modifier.width(128.dp),
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, color = UiTheme.p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp))
}

/** 双语重排视图：当前页逐段 原文+译文 交错，译文流式填充（会话滚动窗口预取后页）。 */
@Composable
private fun BilingualView(
    session: TranslateSession?,
    aiTick: Int,
    tgtSel: String,
    onSelectTgt: (String) -> Unit,
    fontScale: Int,
) {
    if (!AiConfigStore.configured()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未配置 AI 大模型——请到 偏好 → AI 大模型 配置后开启双语阅读", color = UiTheme.p.textFaint, fontSize = 13.sp)
        }
        return
    }
    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("启动翻译会话…", color = UiTheme.p.textFaint, fontSize = 13.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                L10n.s("双语 · 第 ${s.displayedPage} 页", "Bilingual · p${s.displayedPage}"), color = UiTheme.p.textFaint, fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            ToolBtn(L10n.s("自动", "Auto"), tgtSel == "auto") { onSelectTgt("auto") }
            ToolBtn(L10n.s("→中文", "→ZH"), tgtSel == Lang.ZH) { onSelectTgt(Lang.ZH) }
            ToolBtn(L10n.s("→英文", "→EN"), tgtSel == Lang.EN) { onSelectTgt(Lang.EN) }
        }
        val page1 = s.displayedPage
        val slots = remember(aiTick, page1) { s.snapshot(page1) }
        if (slots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(L10n.s("本页翻译排队中…（稍候自动出现）", "Queuing this page…"), color = UiTheme.p.textFaint, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                itemsIndexed(slots, key = { i, sl -> "${sl.page}_${i}_${sl.pid}" }) { _, sl ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(sl.orig, color = UiTheme.p.textTertiary, fontSize = (12 * fontScale / 100f).sp)
                        when {
                            sl.tran != null -> Text(
                                sl.tran!!, color = UiTheme.p.textPrimary, fontSize = (14 * fontScale / 100f).sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            sl.partial != null -> Text(
                                sl.partial!!, color = UiTheme.p.textSecondary, fontSize = (14 * fontScale / 100f).sp,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            sl.failed -> Text(L10n.s("翻译失败", "Failed"), color = UiTheme.p.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            sl.started -> Text(L10n.s("翻译中…", "Translating…"), color = UiTheme.p.textFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            else -> Text(L10n.s("待翻译", "Queued"), color = UiTheme.p.textFaintest, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionPopup(
    selText: String, selTran: String, selBusy: Boolean, selErr: String, selTgt: String,
    onTgt: (String) -> Unit, onClose: () -> Unit,
    onCopy: () -> Unit, onSpeak: () -> Unit, onBookmark: () -> Unit, onHighlight: (Int) -> Unit,
    onDict: () -> Unit = {},
    onWebSearch: () -> Unit = {},
    onCopyImage: () -> Unit = {},
    onSaveImage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = 560.dp)
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(UiTheme.p.panel)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(L10n.s("划词翻译", "Selection"), color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.weight(1f))
            ToolBtn(L10n.s("自动", "Auto"), selTgt == "auto") { onTgt("auto") }
            ToolBtn(L10n.s("→中文", "→ZH"), selTgt == Lang.ZH) { onTgt(Lang.ZH) }
            ToolBtn(L10n.s("→英文", "→EN"), selTgt == Lang.EN) { onTgt(Lang.EN) }
            ToolBtn("×") { onClose() }
        }
        Text(
            selText, color = UiTheme.p.textTertiary, fontSize = 12.sp,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = onCopy) { Text(L10n.s("复制", "Copy"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onSpeak) { Text(L10n.s("朗读", "Speak"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onBookmark) { Text(L10n.s("加书签", "Mark"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onDict) { Text(L10n.s("查词", "Dict"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onWebSearch) { Text(L10n.s("网搜", "Web"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onCopyImage) { Text(L10n.s("图片", "Img"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            TextButton(onClick = onSaveImage) { Text(L10n.s("存图", "PNG"), fontSize = 11.sp, color = UiTheme.p.textSecondary) }
            Spacer(Modifier.weight(1f))
            Text(L10n.s("高亮", "Highlight"), color = UiTheme.p.textFaintest, fontSize = 10.sp)
            for (c in HL_COLORS) {
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(Color(c))
                        .clickable { onHighlight(c) }
                )
            }
        }
        when {
            selBusy -> Text(L10n.s("翻译中…", "Translating…"), color = UiTheme.p.textFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            selErr.isNotEmpty() -> Text(selErr, color = UiTheme.p.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            selTran.isNotEmpty() -> Text(selTran, color = UiTheme.p.textPrimary, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 裁白边（v0.51.0 对齐安卓 CropImage）：四角取样背景色，2px 步进扫描内容边界后裁剪；无边界可裁时原样返回。 */
private fun trimWhiteBorders(src: BufferedImage): BufferedImage {
    val w = src.width
    val h = src.height
    if (w < 32 || h < 32) return src
    fun near(c1: Int, c2: Int): Boolean {
        val dr = Math.abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val dg = Math.abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val db = Math.abs((c1 and 0xFF) - (c2 and 0xFF))
        return dr + dg + db <= 48
    }
    val bg = src.getRGB(2, 2)
    var top = 0
    var y = 0
    outer@ while (y < h) {
        var x = 0
        while (x < w) { if (!near(src.getRGB(x, y), bg)) break@outer; x += 2 }
        y += 2
    }
    top = (y - 2).coerceAtLeast(0)
    var bottom = h - 1
    y = h - 1
    outer2@ while (y > top) {
        var x = 0
        while (x < w) { if (!near(src.getRGB(x, y), bg)) break@outer2; x += 2 }
        y -= 2
    }
    bottom = (y + 2).coerceAtMost(h - 1)
    var left = 0
    var x2 = 0
    outer3@ while (x2 < w) {
        var yy = top
        while (yy <= bottom) { if (!near(src.getRGB(x2, yy), bg)) break@outer3; yy += 2 }
        x2 += 2
    }
    left = (x2 - 2).coerceAtLeast(0)
    var right = w - 1
    x2 = w - 1
    outer4@ while (x2 > left) {
        var yy = top
        while (yy <= bottom) { if (!near(src.getRGB(x2, yy), bg)) break@outer4; yy += 2 }
        x2 -= 2
    }
    right = (x2 + 2).coerceAtMost(w - 1)
    val nw = right - left + 1
    val nh = bottom - top + 1
    if (nw < 16 || nh < 16 || (nw >= w && nh >= h)) return src
    return src.getSubimage(left, top, nw, nh)
}

/** RSVP 分词（v0.52.0）：英文/数字按词，CJK 连续串每 2 字一组（中文速读节奏）。 */
fun rsvpSplit(text: String): List<String> {
    val out = ArrayList<String>()
    for (w0 in text.split(' ', '\n', '\t', '\r').filter { it.isNotBlank() }) {
        val seg = StringBuilder()
        var segCjk = false
        var i = 0
        while (i < w0.length) {
            val c = w0[i]
            val isCjk = c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF ||
                c.code in 0xFE30..0xFE4F || c.code in 0xFF00..0xFFEF
            if (isCjk != segCjk && seg.isNotEmpty()) { out.add(seg.toString()); seg.setLength(0) }
            segCjk = isCjk
            seg.append(c)
            if (segCjk && seg.length >= 2) { out.add(seg.toString()); seg.setLength(0) }
            i++
        }
        if (seg.isNotEmpty()) out.add(seg.toString())
    }
    return out
}

/** 选区文字（+译文）合成 PNG 位图（v0.52.0 安卓"分享为图片"等价）：白底黑字自动换行，译文灰字附下。 */
fun selectionImage(text: String, tran: String): BufferedImage {
    val pw = 800
    fun wrap(s0: String, size: Int): List<String> {
        val gg = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        gg.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, size)
        val f = gg.fontMetrics
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (ch in s0) {
            if (ch == '\n') { lines.add(cur.toString()); cur = StringBuilder(); continue }
            if (cur.isNotEmpty() && f.stringWidth(cur.toString() + ch) > pw - 80) { lines.add(cur.toString()); cur = StringBuilder() }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        gg.dispose()
        if (lines.isEmpty()) lines.add("")
        return lines
    }
    val l1 = wrap(text, 22)
    val l2 = if (tran.isNotBlank()) wrap(tran, 18) else emptyList()
    val ph = 60 + l1.size * 30 + (if (l2.isNotEmpty()) 24 + l2.size * 25 else 40)
    val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, pw, ph)
    var y = 50
    g.color = java.awt.Color(0x20, 0x20, 0x20)
    g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 22)
    for (ln in l1) { g.drawString(ln, 40, y); y += 30 }
    if (l2.isNotEmpty()) {
        y += 16
        g.color = java.awt.Color(0x60, 0x60, 0x60)
        g.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18)
        for (ln in l2) { g.drawString(ln, 40, y); y += 25 }
    }
    g.dispose()
    return img
}

/** 通用页面图像：渲染在 Default 线程，产 Compose ImageBitmap。 */
@Composable
private fun PageImage(doc: DocumentHandle, page: Int, widthPx: Int, mode: ReadMode, tint: Int, cache: MutableMap<Int, BufferedImage>? = null, epoch: Int = 0, modifier: Modifier = Modifier, trimWhite: Boolean = false) {
    val img by produceState<BufferedImage?>(null, doc, page, widthPx, mode, tint, cache, epoch, trimWhite) {
        if (widthPx > 0) {
            if (cache != null) {
                val hit = synchronized(cache) { cache[page] }
                if (hit != null) {
                    value = hit
                    return@produceState
                }
            }
            val t0 = System.currentTimeMillis()
            val bmp = withContext(Dispatchers.Default) {
                val raw = runCatching { doc.render(page, widthPx, mode, tint) }.getOrNull()
                if (raw != null && trimWhite) runCatching { trimWhiteBorders(raw) }.getOrDefault(raw) else raw
            }
            if (bmp != null && cache != null) synchronized(cache) { cache[page] = bmp }
            value = bmp
            TestLog.evt("render_done", "page=${page + 1} ${System.currentTimeMillis() - t0}ms ${bmp?.width}x${bmp?.height} mode=$mode tint=$tint")
        }
    }
    img?.let {
        Image(bitmap = it.toComposeImageBitmap(), contentDescription = "page ${page + 1}",
            modifier = modifier, contentScale = ContentScale.Fit)
    } ?: Box(modifier, contentAlignment = Alignment.Center) {
        Text(L10n.s("渲染中…", "Rendering…"), color = UiTheme.p.textTertiary, fontSize = 13.sp)
    }
}

/** 连续滚动：LazyColumn 按页铺开，条目按各自页宽高比占位，视口内按需渲染。 */
@Composable
private fun ScrollView(
    doc: DocumentHandle,
    pageCount: Int,
    theme: ReadTheme,
    page: Int,
    zoom: Float,
    scrollJumpToken: Int,
    reflow: Boolean,
    layoutRev: Int,
    cache: MutableMap<Int, BufferedImage>,
    onPageFromScroll: (Int) -> Unit,
    autoScroll: Boolean = false,
    autoScrollSpeed: Int = 3,
    trimWhite: Boolean = false,
    twoPage: Boolean = false,
) {
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 自动滚动（对齐安卓 autoScroll）：逐帧像素推进，零重排（LazyColumn 按需渲染，开销恒定）
    LaunchedEffect(autoScroll, autoScrollSpeed) {
        while (autoScroll) {
            runCatching { listState.scrollBy(autoScrollSpeed.toFloat()) }
            withFrameNanos { }
        }
    }
    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collectLatest { if (it in 0 until pageCount) onPageFromScroll(it) }
    }
    var scrollAligned by remember { mutableStateOf(false) }
    LaunchedEffect(scrollJumpToken) {
        runCatching { listState.scrollToItem(page.coerceIn(0, maxOf(0, pageCount - 1))) }
    }
    // 检视修复：页数首次就位后的对齐只做一次——渐进计数批量发布 pageCount 不再把用户拽回
    LaunchedEffect(pageCount) {
        if (pageCount > 0 && !scrollAligned) {
            scrollAligned = true
            runCatching { listState.scrollToItem(page.coerceIn(0, pageCount - 1)) }
        }
    }
    Box(Modifier.fillMaxSize().background(readerBg(theme))) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            state = listState,
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (twoPage) 2 else 1),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageCount, key = { it }) { p ->
                PageScrollItem(doc, p, theme, zoom, reflow, layoutRev, cache, trimWhite)
            }
        }
        ScrollBarOverlay(listState, pageCount, Modifier.align(Alignment.CenterEnd))
    }
}

/** 滚动模式右侧滚动条（v0.30.0）：按可见页比例画滑块，可拖动跳页。 */
@Composable
private fun ScrollBarOverlay(listState: androidx.compose.foundation.lazy.grid.LazyGridState, pageCount: Int, modifier: Modifier) {
    if (pageCount <= 1) return
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val vis = info.visibleItemsInfo
    if (total <= 0 || vis.isEmpty()) return
    val first = vis.first().index
    val frac = (first.toFloat() / total).coerceIn(0f, 1f)
    val thumbH = (vis.size.toFloat() / total).coerceIn(0.05f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Canvas(
        modifier.width(18.dp).fillMaxHeight().pointerInput(total) {
            detectVerticalDragGestures(
                onDragStart = { dragging = true },
                onVerticalDrag = { ch, _ ->
                    val p = (ch.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                    val target = (p * total).toInt().coerceIn(0, total - 1)
                    if (target != listState.firstVisibleItemIndex) scope.launch { runCatching { listState.scrollToItem(target) } }
                },
                onDragEnd = { dragging = false },
            )
        }
    ) {
        val trackW = 5.dp.toPx()
        val x = this.size.width - trackW - 5.dp.toPx()
        drawRect(
            UiTheme.p.textFaintest.copy(alpha = 0.18f),
            topLeft = Offset(x, 0f),
            size = Size(trackW, this.size.height),
        )
        val th = this.size.height * thumbH
        drawRect(
            UiTheme.p.accent.copy(alpha = if (dragging) 0.9f else 0.5f),
            topLeft = Offset(x, (this.size.height - th) * frac),
            size = Size(trackW, th),
        )
    }
}

@Composable
private fun PageScrollItem(doc: DocumentHandle, page: Int, theme: ReadTheme, zoom: Float, reflow: Boolean, layoutRev: Int, cache: MutableMap<Int, BufferedImage>, trimWhite: Boolean = false) {
    var w by remember(page, doc, layoutRev) { mutableIntStateOf(0) }
    val size = remember(page, doc, layoutRev) { runCatching { doc.pageSize(page) }.getOrNull() }
    val ratio = if (size != null && size.second > 0) size.first / size.second else 0.72f
    if (reflow && size != null && size.first > 0) {
        // 重排文档：页宽=版心宽，条目按自然宽居中（不再拉满视口宽）
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val natDp = with(LocalDensity.current) { (size.first * zoom).toInt().toDp() }
            val boxW = if (natDp < maxWidth) natDp else maxWidth
            Box(
                Modifier
                    .width(boxW)
                    .aspectRatio(ratio / zoom)
                    .onSizeChanged { w = it.width }
            ) {
                if (w > 0) {
                    PageImage(doc, page, (w * zoom).toInt(), pixelMode(theme), pixelTint(theme), cache, layoutRev,
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp), trimWhite = trimWhite)
                }
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio / zoom)
                .onSizeChanged { w = it.width }
        ) {
            if (w > 0) {
                PageImage(doc, page, (w * zoom).toInt(), pixelMode(theme), pixelTint(theme), cache, layoutRev,
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp), trimWhite = trimWhite)
            }
        }
    }
}

/** 阅读主题弹层：底色/字色成对预设（对齐安卓 READ_COLORS；reflowable 走 CSS、PDF 走像素后处理）。 */
@Composable
private fun ThemeMenu(current: String, texture: String, onPick: (ReadTheme) -> Unit, onPickTexture: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(196.dp)
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(UiTheme.p.panel)
            .padding(6.dp),
    ) {
        for (t in ReadThemes.ALL) {
            Row(
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .border(1.dp, UiTheme.p.textFaintest, CircleShape)
                        .background(Color(t.bg))
                )
                Text(
                    if (t.id == "day") L10n.s("白", "Light") else L10n.s(t.label, t.labelEn),
                    color = if (t.id == current) UiTheme.p.accent else UiTheme.p.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (t.id == current) Text("✓", color = UiTheme.p.accent, fontSize = 13.sp)
            }
        }
        // 背景质感（v0.29.0）：纹理缩略块；仅浅色主题（非 dark）下叠加生效，纯显示层
        Text(
            L10n.s("背景质感", "Texture"), color = UiTheme.p.textFaintest, fontSize = 10.sp,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            for (tx in ReadTextures.ALL) {
                val sel = tx.id == texture
                Box(
                    Modifier.size(width = 34.dp, height = 24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(
                            if (sel) 2.dp else 1.dp,
                            if (sel) UiTheme.p.accent else UiTheme.p.textFaintest,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { onPickTexture(tx.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (tx.id == "none") {
                        Text(L10n.s("无", "-"), fontSize = 10.sp, color = UiTheme.p.textSecondary)
                    } else {
                        val tb = remember(tx.id) { Res.bitmap("howread/tex/" + tx.id + ".jpg") }
                        if (tb != null) {
                            Image(bitmap = tb, contentDescription = tx.label, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                        } else {
                            Text("…", fontSize = 10.sp, color = UiTheme.p.textFaintest)
                        }
                    }
                }
            }
        }
    }
}

/** EPUB 排版加速器路径（安卓 accels 同思路）：文件名含源文件 size+mtime，源文件变化自动换名失效。 */
private fun accelPathFor(path: String): String? {
    if (FormatRouter.ext(path) != "epub") return null
    val f = File(path)
    if (!f.isFile) return null
    val dir = File(AppPaths.cacheDir, "accels")
    dir.mkdirs()
    return File(dir, Integer.toHexString(path.hashCode()) + "-" + f.length() + "-" + f.lastModified() + ".accel").absolutePath
}

/** 清理同书旧加速器（源文件更新换名后的残留）。 */
private fun cleanupAccels(path: String, keep: String) {
    val dir = File(AppPaths.cacheDir, "accels")
    val pre = Integer.toHexString(path.hashCode()) + "-"
    for (f in dir.listFiles() ?: emptyArray()) {
        if (f.name.startsWith(pre) && f.absolutePath != keep) runCatching { f.delete() }
    }
    // 全局 LRU（安卓 Temp accels 留 8 同值）：全书加速器按 mtime 只保留最近 8 个
    val rest = (dir.listFiles() ?: emptyArray()).filter { it.isFile }
        .sortedByDescending { it.lastModified() }
    for (f in rest.drop(8)) runCatching { f.delete() }
}
