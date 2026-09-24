package com.foobnix.ai;

import android.content.Context;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Page-aware AI translation backing the reader's list panel.
 *
 * Translates a small ROLLING window of pages around the reader position
 * (previous page .. 3 pages ahead) paragraph by paragraph on three parallel
 * lanes, with streamed answers, and keeps the results GROUPED BY PAGE: the
 * panel always renders the page the user is currently reading — finished
 * paragraphs appear instantly, in-flight ones grow live, queued ones show a
 * placeholder. Turning a page re-centers the window and switches the panel to
 * that page: the translations made in the background are simply there when
 * the user arrives. Not-yet-started paragraphs that fall far outside the
 * window are dropped (no wasted quota); requests already in flight finish.
 *
 * The session replaces the former one-shot translate-a-fixed-window run.
 */
public class TranslateSession {

    /** One paragraph of the window. */
    public static class Slot {
        public final int page;      // 1-based reader page this paragraph belongs to
        public final String pid;    // cache anchor (chapter + content hash)
        public final String orig;   // cleaned source text
        public volatile boolean started;
        public volatile boolean failed;
        public volatile boolean dropped;
        public volatile String tran;    // final translation (null until done)
        public volatile String partial; // growing stream text (while running)
        public volatile float topY = -1; // first-line top / page height (-1 unknown)

        Slot(int page, String pid, String orig) {
            this.page = page;
            this.pid = pid;
            this.orig = orig;
        }

        /** Display state: 0 pending, 1 running, 2 done, 3 failed. */
        public int state() {
            if (tran != null) {
                return 2;
            }
            if (failed) {
                return 3;
            }
            return started ? 1 : 0;
        }
    }

    /** UI callbacks (worker threads — the panel posts itself). */
    public interface Listener {
        /** Window/page/slot state changed: re-render the displayed page. */
        void onSessionChanged();

        /** Live partial text of a running slot. */
        void onSlotPartial(Slot slot);
    }

    /** The one active panel session; a new translation replaces the old. */
    private static volatile TranslateSession CURRENT;

    /** Reader page turned: re-center the active session (no-op when none). */
    public static void feedForController(DocumentController dc) {
        TranslateSession s = CURRENT;
        if (s != null && dc != null && dc.getCurrentBook() != null) {
            s.onReaderPageChanged(dc);
        }
    }

    private static final int LANES = 3;
    private static final int BACK_PAGES = 1;
    private static final int AHEAD_PAGES = 3;

    private final Context appContext;
    private final String src;
    private final String tgt;
    private final File book;
    private final TranslationCache cache;
    private final List<OutlineLinkWrapper> outline;
    private final String suffix;

    private volatile Listener listener;
    private volatile DocumentController lastDc;
    private volatile boolean stopped = false;
    private volatile int displayedPage = -1;
    private Thread[] workers;

    private final Object lock = new Object();
    private final Map<Integer, List<Slot>> pages = new LinkedHashMap<Integer, List<Slot>>();
    private final ConcurrentLinkedDeque<Slot> queue = new ConcurrentLinkedDeque<Slot>();
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public TranslateSession(Context appContext, DocumentController dc, String src, String tgt) {
        this.appContext = appContext;
        this.src = src;
        this.tgt = tgt;
        this.lastDc = dc;
        this.book = dc == null ? null : dc.getCurrentBook();
        this.cache = book == null ? null : TranslationCache.inMemory(book);
        this.outline = dc == null ? null : dc.getCurrentOutline();
        this.suffix = "请把这段文字翻译成" + AiTranslator.targetLangName(tgt)
                + "，不要启用思考过程，直接回复翻译内容";
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** The page the panel should render (1-based reader page). */
    public int getDisplayedPage() {
        return displayedPage;
    }

    /** Snapshot of one page's slots in display order. */
    public List<Slot> snapshot(int page) {
        synchronized (lock) {
            List<Slot> l = pages.get(page);
            return l == null ? new ArrayList<Slot>() : new ArrayList<Slot>(l);
        }
    }

    /** Slots queued or in flight across the whole window (status line). */
    public int backgroundPending() {
        int n = inFlight.get();
        synchronized (lock) {
            for (List<Slot> l : pages.values()) {
                for (Slot s : l) {
                    if (s.state() == 0) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Begin translating; replaces any running panel session. */
    public void start() {
        if (book == null) {
            fireChanged();
            return;
        }
        CURRENT = this;
        DocumentController dc = lastDc;
        int first = dc == null ? -1 : dc.getCurentPageFirst1();
        recenter(dc, first <= 0 ? 1 : first);
        workers = new Thread[LANES];
        for (int i = 0; i < LANES; i++) {
            workers[i] = new Thread(new Runnable() {
                @Override public void run() {
                    workerLoop();
                }
            }, "TranslatePanel");
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    /** Stop everything (panel dismissed or replaced by a newer translation). */
    public void cancel() {
        stopped = true;
        if (CURRENT == this) {
            CURRENT = null;
        }
        lastDc = null;
        synchronized (lock) {
            queue.clear();
            pages.clear();
        }
        Thread[] ws = workers;
        if (ws != null) {
            for (Thread w : ws) {
                if (w != null) {
                    w.interrupt();
                }
            }
        }
        workers = null;
    }

    private void onReaderPageChanged(DocumentController dc) {
        if (stopped) {
            return;
        }
        lastDc = dc;
        int page = dc.getCurentPageFirst1();
        if (page <= 0 || page == displayedPage) {
            return; // frequent feeds (scroll events) are cheap no-ops
        }
        recenter(dc, page);
    }

    /**
     * Re-center the rolling window on the reader page: the current page is
     * queued first, then ahead pages, then the previous page; not-yet-started
     * slots that fell far outside are dropped (quota), in-flight ones finish.
     */
    private void recenter(DocumentController dc, int page) {
        displayedPage = page;
        synchronized (lock) {
            Iterator<Map.Entry<Integer, List<Slot>>> it = pages.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, List<Slot>> e = it.next();
                int p = e.getKey();
                if (p >= page - BACK_PAGES - 1 && p <= page + AHEAD_PAGES + 1) {
                    continue;
                }
                for (Slot s : e.getValue()) {
                    if (s.state() == 0) {
                        s.dropped = true;
                        queue.remove(s);
                    }
                }
                it.remove();
            }
            // current page first, then ahead, then back
            addPage(dc, page);
            for (int d = 1; d <= AHEAD_PAGES; d++) {
                addPage(dc, page + d);
            }
            addPage(dc, page - BACK_PAGES);
            // the page the reader is ON jumps the queue
            List<Slot> cur = pages.get(page);
            if (cur != null) {
                List<Slot> head = new ArrayList<Slot>();
                for (Slot s : cur) {
                    if (s.state() == 0 && queue.remove(s)) {
                        head.add(s);
                    }
                }
                for (int i = head.size() - 1; i >= 0; i--) {
                    queue.addFirst(head.get(i));
                }
            }
        }
        fireChanged();
    }

    /** Collect one page's paragraphs into slots (cache hits resolve at once). */
    private void addPage(DocumentController dc, int page) {
        if (dc == null || page < 1 || pages.containsKey(page)) {
            return;
        }
        String[] paras = dc.getPageParagraphs(page - 1); // 0-based
        if (paras == null) {
            // recycled page outside the decode window: retried on the next
            // re-center (the current page is always live)
            android.util.Log.i("BENCH", "TranslateSession page " + page + " paras=null (recycled)");
            return;
        }
        int chapter = chapterIndexForPage(outline, page);
        // collect the cleaned paragraphs first so the alignment lookup can
        // locate them on the page in the very same order
        java.util.ArrayList<String> cleaned = new java.util.ArrayList<String>();
        for (String raw : paras) {
            String orig = clean(raw);
            if (TxtUtils.isEmpty(orig)) {
                continue;
            }
            cleaned.add(orig);
        }
        float[] tops = dc.getParagraphTops(page - 1, cleaned.toArray(new String[cleaned.size()]));
        List<Slot> slots = new ArrayList<Slot>();
        int aligned = 0;
        for (int i = 0; i < cleaned.size(); i++) {
            String orig = cleaned.get(i);
            String pid = "ch" + chapter + "_h"
                    + com.foobnix.android.utils.FileHash.md5(orig);
            Slot s = new Slot(page, pid, orig);
            if (tops != null && i < tops.length && tops[i] >= 0) {
                s.topY = tops[i];
                aligned++;
            }
            String cached = cache == null ? null : cache.lookup(pid, src, tgt, orig);
            if (cached != null) {
                s.tran = cached;
                android.util.Log.i("BENCH", "TranslateSession " + pid + " cache HIT");
            }
            slots.add(s);
        }
        android.util.Log.i("BENCH", "TranslateSession page " + page + " slots=" + slots.size()
                + " aligned=" + aligned);
        pages.put(page, slots);
        for (Slot s : slots) {
            if (s.tran == null && !s.failed) {
                queue.addLast(s);
            }
        }
    }

    private void workerLoop() {
        while (!stopped) {
            Slot s = queue.pollFirst();
            if (s == null) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            if (s.dropped || s.tran != null || s.failed) {
                continue;
            }
            s.started = true;
            inFlight.incrementAndGet();
            fireChanged();
            translate(s);
            inFlight.decrementAndGet();
        }
    }

    private void translate(final Slot s) {
        String prompt = s.orig + "\n\n" + suffix;
        long t0 = System.currentTimeMillis();
        android.util.Log.i("BENCH", "TranslateSession " + s.pid + " AI ask p=" + s.page
                + " orig.len=" + s.orig.length());
        AiClient.TestResult res = AiClient.ask(appContext, prompt, new AiClient.StreamCallback() {
            @Override public void onDelta(String fullTextSoFar) {
                s.partial = fullTextSoFar;
                Listener l = listener;
                if (l != null) {
                    l.onSlotPartial(s);
                }
            }
        });
        android.util.Log.i("BENCH", "TranslateSession " + s.pid + " AI res ok=" + res.ok
                + " err=" + res.error + " detail=" + res.detail
                + " ms=" + (System.currentTimeMillis() - t0)
                + " reply.len=" + (res.reply == null ? -1 : res.reply.length()));
        if (res.ok && TxtUtils.isNotEmpty(res.reply)) {
            s.tran = res.reply.trim();
            if (cache != null) {
                cache.save(s.pid, src, tgt, s.orig, s.tran, "done");
            }
        } else if (!stopped && !Thread.currentThread().isInterrupted()) {
            s.failed = true;
            if (cache != null) {
                cache.save(s.pid, src, tgt, s.orig, "", "failed");
            }
        }
        s.partial = null;
        fireChanged();
    }

    private void fireChanged() {
        Listener l = listener;
        if (l != null) {
            l.onSessionChanged();
        }
    }

    /** Largest outline index whose targetPage <= page; 0 when no outline. */
    private static int chapterIndexForPage(List<OutlineLinkWrapper> outline, int page) {
        if (outline == null || outline.isEmpty()) {
            return 0;
        }
        int idx = 0;
        for (int i = 0; i < outline.size(); i++) {
            int tp = outline.get(i).targetPage;
            if (tp > 0 && tp <= page) {
                idx = i;
            } else if (tp > page) {
                break;
            }
        }
        return idx;
    }

    /** Trim a block's text into a single clean paragraph string. */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00a0', ' ')
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
