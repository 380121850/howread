package howread.core.ai

import howread.core.TestLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 页感知 AI 翻译会话（安卓 TranslateSession 的桌面移植）：
 * 以阅读位置为中心的滚动窗口（前 1 页..后 3 页）逐段翻译，3 条并行 lane，
 * 流式更新 partial；翻页重定心，掉出窗口且未开始的段被丢弃（省额度）。
 * 段级缓存命中即时回填。onTick 从工作线程回调（UI 侧自行切主线程）。
 */
class TranslateSession(
    private val bookPath: String,
    private val cfgProvider: () -> AiConfig,
    private val paragraphsOf: (Int) -> List<String>, // 0 基页码 -> 段落列表
    private val chapterOf: (Int) -> Int,             // 1 基页码 -> 章序号（缓存锚）
    private val tgtSel: String,                      // auto | zh | en
    private val onTick: () -> Unit,
) {
    class Slot(val page: Int, val pid: String, val orig: String) {
        @Volatile var started = false
        @Volatile var failed = false
        @Volatile var dropped = false
        @Volatile var tran: String? = null
        @Volatile var partial: String? = null
        val state: Int get() = if (tran != null) 2 else if (failed) 3 else if (started) 1 else 0
    }

    private val lock = Any()
    private val pages = HashMap<Int, MutableList<Slot>>()
    private val queue = ConcurrentLinkedDeque<Slot>()
    private val inFlight = AtomicInteger(0)
    private val cache = TranslationCache.forBook(bookPath)
    private val pool = Executors.newFixedThreadPool(LANES) { r ->
        Thread(r, "AiTranslate").apply { isDaemon = true }
    }

    @Volatile var stopped = false
        private set
    @Volatile var displayedPage = -1
        private set

    // auto 方向：用首个窗口文本样本定向后固定
    private var tgtFixed: String? = null

    fun tgt(): String = tgtFixed ?: tgtSel
    private fun resolveTgt(sample: String): String {
        tgtFixed?.let { return it }
        val t = when (tgtSel) {
            Lang.ZH -> Lang.ZH
            Lang.EN -> Lang.EN
            else -> Lang.autoFor(sample).second
        }
        tgtFixed = t
        return t
    }

    private val suffix: String
        get() = "请把这段文字翻译成" + Lang.targetName(tgt()) + "，不要启用思考过程，直接回复翻译内容"

    private fun src(): String = if (tgt() == Lang.ZH) Lang.EN else Lang.ZH

    fun start(firstPage1: Int) {
        recenter(firstPage1)
        repeat(LANES) { pool.submit(::workerLoop) }
    }

    fun onPageChanged(page1: Int) {
        if (stopped || page1 <= 0 || page1 == displayedPage) return
        recenter(page1)
    }

    fun snapshot(page1: Int): List<Slot> = synchronized(lock) { pages[page1]?.toList() ?: emptyList() }

    fun pending(): Int {
        var n = inFlight.get()
        synchronized(lock) {
            for (l in pages.values) for (s in l) if (s.state == 0) n++
        }
        return n
    }

    fun cancel() {
        stopped = true
        pool.shutdownNow()
        cache.flush()
        synchronized(lock) {
            queue.clear()
            pages.clear()
        }
    }

    private fun recenter(page1: Int) {
        displayedPage = page1
        synchronized(lock) {
            val it = pages.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key in page1 - BACK_PAGES - 1..page1 + AHEAD_PAGES + 1) continue
                for (s in e.value) if (s.state == 0) {
                    s.dropped = true
                    queue.remove(s)
                }
                it.remove()
            }
            addPageLocked(page1)
            for (d in 1..AHEAD_PAGES) addPageLocked(page1 + d)
            addPageLocked(page1 - BACK_PAGES)
            // 当前页插队头
            pages[page1]?.let { cur ->
                val head = ArrayList<Slot>()
                for (s in cur) if (s.state == 0 && queue.remove(s)) head.add(s)
                for (i in head.indices.reversed()) queue.addFirst(head[i])
            }
        }
        onTick()
    }

    /** 引擎提取在锁外执行；本方法仅由 UI 线程经 recenter 串行调用。 */
    private fun addPageLocked(page1: Int) {
        if (page1 < 1 || pages.containsKey(page1)) return
        val paras = paragraphsOf(page1 - 1)
        val cleaned = paras.map { it.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        resolveTgt(cleaned.first())
        val chapter = chapterOf(page1)
        val slots = ArrayList<Slot>()
        var cached = 0
        for (orig in cleaned) {
            val pid = "ch" + chapter + "_h" + md5(orig)
            val s = Slot(page1, pid, orig)
            cache.lookup(pid, src(), tgt())?.let {
                s.tran = it
                cached++
            }
            slots.add(s)
        }
        pages[page1] = slots
        for (s in slots) if (s.tran == null && !s.failed) queue.addLast(s)
        TestLog.evt("ai_page_done", "page=" + page1 + " segs=" + slots.size + " cached=" + cached)
    }

    private fun workerLoop() {
        while (!stopped) {
            val s = try { queue.pollFirst() } catch (t: Throwable) { if (stopped) break; null }
            if (s == null) {
                Thread.sleep(150)
                continue
            }
            if (s.dropped || s.tran != null || s.failed) continue
            s.started = true
            inFlight.incrementAndGet()
            try {
                onTick()
                translate(s)   // 检视修复：单条异常不再减员 worker
            } catch (t: Throwable) {
                TestLog.evt("ts_worker_err", (t.message ?: t.toString()).take(120))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun translate(s: Slot) {
        val cfg = cfgProvider()
        val prompt = s.orig + "\n\n" + suffix
        val res = AiClient.chat(cfg, prompt, cfg.maxTokens, AiClient.StreamCallback { t ->
            s.partial = t
            onTick()
        })
        if (res.ok && res.reply.isNotBlank()) {
            val t = res.reply.trim()
            s.tran = t
            cache.save(s.pid, src(), tgt(), t)
        } else if (!stopped && !Thread.currentThread().isInterrupted) {
            s.failed = true
            TestLog.evt("ai_ask_fail", "err=" + res.error + " detail=" + res.detail.take(80))
        }
        s.partial = null
        onTick()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val LANES = 3
        private const val BACK_PAGES = 1
        private const val AHEAD_PAGES = 3
    }
}
