package com.foobnix.ai;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the in-page bilingual AI-translate session for one book.
 *
 * A single background worker keeps translating the source paragraphs around the
 * current reading position, ordered "current page first, then ahead pages, then
 * back pages" (page window: +10 ahead / -3 back). Every finished paragraph is
 * upserted into the {@link TranslationCache}; after a short merge debounce the
 * {@link BilingualBuilder} regenerates the bilingual edition and the host
 * (reader activity) is asked to re-open the book *in place* (silently, anchored
 * to the top paragraph of the current page) so the translation renders without
 * the full Activity-restart flashing of the old approach.
 *
 * The session survives reader-activity restarts, so it is held as a per-book
 * singleton and only stopped when the mode is turned off or another book opens.
 */
public class BilingualSession {

    /** Backend-independent "please re-open the current book" hook. */
    public interface Host {
        Context getAppContext();

        void requestRestart();

        /** Re-open the book in place (or silently) landing on the same content. */
        void requestReload(int page0, String anchorMd5);

        /** Re-evaluate the "正在翻译中…" bottom hint for the current page. */
        void requestHintUpdate();
    }

    // page window around the current reading position
    private static final int BACK_PAGES = 2;
    private static final int AHEAD_PAGES = 5;
    // paragraphs sent to the AI in one request
    private static final int BATCH_SIZE = 5;
    // parallel translation lanes: 0 = current page, 1 = ahead pages, 2 = back pages
    private static final int LANES = 3;
    // merge window for rebuilds triggered by finished paragraphs
    private static final long REBUILD_DEBOUNCE_MS = 3000;

    private static final Map<String, BilingualSession> SESSIONS = new HashMap<String, BilingualSession>();

    public static synchronized BilingualSession attach(String bookPath, File book, String src, String tgt) {
        BilingualSession s = SESSIONS.get(bookPath);
        if (s == null) {
            s = new BilingualSession(book, src, tgt);
            SESSIONS.put(bookPath, s);
        }
        return s;
    }

    /** The session for a book path, or null when it was never created/stopped. */
    public static synchronized BilingualSession attachOrNull(String bookPath) {
        if (bookPath == null) {
            return null;
        }
        return SESSIONS.get(bookPath);
    }

    public static synchronized void pauseAllExcept(String bookPath) {
        for (Map.Entry<String, BilingualSession> e : SESSIONS.entrySet()) {
            boolean active = e.getKey().equals(bookPath);
            e.getValue().setActive(active);
        }
    }

    public static synchronized void stop(String bookPath) {
        BilingualSession s = SESSIONS.remove(bookPath);
        if (s != null) {
            s.dispose();
        }
    }

    /**
     * One-shot suppression for {@link #exitOnReaderDestroy}: set right before
     * a PROGRAMMATIC restart that keeps the bilingual mode (enable-restart,
     * silent vertical reload, in-place-reload fallback), because those also
     * destroy the reader activity.
     */
    public static volatile boolean suppressExitOnDestroy = false;

    /**
     * Called from the readers' onDestroy: leaving the reader (a real finish,
     * not a programmatic bilingual restart) also turns the in-page bilingual
     * mode off, so the next session starts from the base book.
     */
    public static void exitOnReaderDestroy(Activity activity) {
        try {
            if (suppressExitOnDestroy) {
                suppressExitOnDestroy = false;
                return;
            }
            if (activity == null || !activity.isFinishing()) {
                return;
            }
            AppState st = AppState.get();
            if (!st.aiBilingual) {
                return;
            }
            android.util.Log.i("BENCH", "BilingualSession reader exited -> mode off book=" + st.aiBilingualBook);
            stop(st.aiBilingualBook);
            st.aiBilingual = false;
            st.aiBilingualBook = "";
            com.foobnix.model.AppProfile.save(activity);
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    public static synchronized boolean isActive(String bookPath) {
        BilingualSession s = SESSIONS.get(bookPath);
        return s != null && s.active.get() && !s.stopped.get();
    }

    /** The top-paragraph anchor currently remembered for a book, or null. */
    public static String anchorOf(String bookPath) {
        BilingualSession s = attachOrNull(bookPath);
        if (s == null) {
            return null;
        }
        if (s.anchorMd5 == null) {
            // first enumeration may have finished after the page was shown:
            // recompute the anchor on the spot so a reload can re-land
            s.onPageShown(s.lastDc);
        }
        return s.anchorMd5;
    }

    /**
     * Find the rendered page whose text layer contains the anchor paragraph,
     * scanning a small band around {@code approxPage0} (0-based). Returns -1
     * when the anchor is unknown or not found.
     */
    public static int locateAnchorPage(DocumentController dc, String anchorMd5, int approxPage0) {
        try {
            if (dc == null || dc.getCurrentBook() == null || TxtUtils.isEmpty(anchorMd5)) {
                return -1;
            }
            int pageCount = dc.getPageCount();
            if (pageCount <= 0) {
                return -1;
            }
            BilingualSession s = attachOrNull(dc.getCurrentBook().getPath());
            if (s == null || s.paraByMd5 == null) {
                return -1;
            }
            BilingualBuilder.Para p = s.paraByMd5.get(anchorMd5);
            if (p == null) {
                return -1;
            }
            String nSource = norm(p.text);
            if (nSource.length() < 4) {
                return -1;
            }
            int start = Math.max(0, approxPage0 - 10);
            int end = Math.min(pageCount - 1, approxPage0 + 10);
            int bestPage = -1;
            int bestLen = 0;
            for (int page = start; page <= end; page++) {
                String[] frags = dc.getPageParagraphs(page);
                if (frags == null) {
                    continue;
                }
                for (String f : frags) {
                    if (TxtUtils.isEmpty(f)) {
                        continue;
                    }
                    String nf = norm(f);
                    if (nf.length() < 6) {
                        continue;
                    }
                    int ov = nf.indexOf(nSource) >= 0 ? nSource.length()
                            : nSource.indexOf(nf) >= 0 ? nf.length() : 0;
                    if (ov >= Math.min(8, nSource.length()) && nf.length() > bestLen) {
                        bestLen = nf.length();
                        bestPage = page;
                    }
                }
            }
            return bestPage;
        } catch (Throwable t) {
            LOG.e(t);
            return -1;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /**
     * Reader-controller level attach used by both reading modes: creates (or
     * re-points) the session for the book behind {@code dc} when the in-page
     * bilingual mode is on for it, otherwise pauses every session.
     */
    public static void attachForController(final Activity activity,
            final DocumentController dc) {
        try {
            if (activity == null || dc == null || dc.getCurrentBook() == null) {
                return;
            }
            final String path = dc.getCurrentBook().getPath();
            final AppState st = AppState.get();
            BilingualSession existing = attachOrNull(path);
            if (existing != null) {
                // stale sessions must obey the same gate as new ones: a
                // leftover session used to resurrect here (translating and
                // spending quota) with NO ui switch left to stop it
                if (!st.aiBilingual || TxtUtils.isEmpty(st.aiBilingualBook)
                        || !st.aiBilingualBook.equals(path)) {
                    existing.detachHost();
                    pauseAllExcept(null);
                    return;
                }
                existing.attachHost(hostFor(activity, dc, path));
                pauseAllExcept(path);
                existing.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                existing.updateHint();
                return;
            }
            if (!st.aiBilingual || TxtUtils.isEmpty(st.aiBilingualBook)
                    || !st.aiBilingualBook.equals(path)) {
                pauseAllExcept(null);
                return;
            }
            BilingualSession created = attach(path, dc.getCurrentBook(), st.aiBilingualSrc, st.aiBilingualTgt);
            created.attachHost(hostFor(activity, dc, path));
            pauseAllExcept(path);
            created.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
            created.updateHint();
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private static Host hostFor(final Activity activity, final DocumentController dc, final String path) {
        return new Host() {
            @Override public Context getAppContext() {
                return activity.getApplicationContext();
            }

            @Override public void requestRestart() {
                if (dc != null) {
                    suppressExitOnDestroy = true;
                    try {
                        dc.restartActivity();
                    } catch (Throwable t) {
                        // the restart never reached onDestroy: without the
                        // reset the next REAL exit would skip the bilingual
                        // shutdown and leave a stale session behind
                        suppressExitOnDestroy = false;
                        LOG.e(t);
                    }
                }
            }

            @Override public void requestReload(int page0, String anchorMd5) {
                reloadForController(activity, dc, page0, anchorMd5);
            }

            @Override public void requestHintUpdate() {
                try {
                    BilingualSession s = attachOrNull(path);
                    String text = s == null ? null : s.currentHintText();
                    if (activity instanceof BilingualHintUi) {
                        ((BilingualHintUi) activity).setBilingualHint(text);
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
        };
    }

    /**
     * In-place silent reload for the horizontal reader, silent restart for the
     * vertical one; any failure degrades to the classic full restart.
     */
    private static void reloadForController(Activity activity, DocumentController dc, int page0, String anchorMd5) {
        try {
            if (activity instanceof com.foobnix.pdf.search.activity.HorizontalViewActivity) {
                ((com.foobnix.pdf.search.activity.HorizontalViewActivity) activity).reloadBilingualInPlace();
            } else if (dc != null) {
                suppressExitOnDestroy = true;
                try {
                    dc.restartActivitySilently(page0, anchorMd5);
                } catch (Throwable t) {
                    suppressExitOnDestroy = false;
                    throw t;
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
            try {
                if (dc != null) {
                    suppressExitOnDestroy = true;
                    try {
                        dc.restartActivity();
                    } catch (Throwable t2) {
                        suppressExitOnDestroy = false;
                        throw t2;
                    }
                }
            } catch (Throwable t2) {
                LOG.e(t2);
            }
        }
    }

    /** Feed the current page to the session of the book behind {@code dc}. */
    public static void feedForController(DocumentController dc) {
        try {
            if (dc == null || dc.getCurrentBook() == null) {
                return;
            }
            BilingualSession s = attachOrNull(dc.getCurrentBook().getPath());
            if (s != null) {
                s.lastDc = dc;
                s.onView(dc.getCurentPageFirst1() - 1, dc.getPageCount());
                s.onPageShown(dc);
                s.maybeRefreshCurrentPage();
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private final File book;
    private final String src;
    private final String tgt;
    private final TranslationCache cache;

    private volatile Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean building = new AtomicBoolean(false);

    private Thread[] worker;
    private final Object workerLock = new Object();

    // source paragraphs (lazily enumerated from the base file the doc opened)
    private volatile List<BilingualBuilder.Para> paras;
    // normalized paragraph texts parallel to paras (match acceleration)
    private volatile List<String> paraNorms;
    private volatile Map<String, BilingualBuilder.Para> paraByMd5;
    private final AtomicBoolean ensuring = new AtomicBoolean(false);
    private final Set<String> pending = new HashSet<String>();
    private final Set<String> queued = new HashSet<String>();
    // concurrent set: the three worker lanes add/remove WITHOUT queueLock
    // (a plain HashSet corrupted under them and stranded paragraphs mid-book)
    private final Set<String> inFlight =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final Set<String> failed = new HashSet<String>();
    // three translation lanes: current page / ahead pages / back pages, each
    // served by its own worker (stolen from in priority order when idle)
    private final ArrayDeque<String>[] lanes = new ArrayDeque[LANES];
    private final Object queueLock = new Object();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<String, Integer>();

    private int lastPage0 = -1;
    private int lastPageCount = 0;
    // the page previously fed via onView (to detect jumps and re-prioritize)
    private int lastFedPage0 = -1;

    // top paragraph md5 of the current page, used to re-land after a rebuild
    private volatile String anchorMd5;
    // the ACTUAL source paragraphs rendered on the current page, matched from
    // the page text layer; null = fall back to the linear page estimate
    private volatile Set<String> currentPageMd5s;
    // linear page->paragraph estimates DRIFT as the bilingual file grows with
    // every merge; the precise anchor ordinal measures the drift and shifts
    // the translation window back onto the real reading position
    private volatile int ordOffset = 0;
    // the last controller that fed this session (to re-run the page-anchor
    // computation once the first paragraph enumeration has finished)
    private volatile DocumentController lastDc;

    // the md5 set the currently open book was built with (translations it shows)
    private volatile Set<String> builtMd5s = new HashSet<String>();
    private volatile boolean reloading = false;
    private volatile boolean reloadPending = false;

    private final Runnable rebuildRunnable = new Runnable() {
        @Override public void run() {
            rebuildScheduled = false;
            // only merge (rebuild + reload) when the CURRENT page actually
            // gained translations; background-only progress must not refresh
            // the page the user is reading (no periodic flashing)
            if (!needsRefreshForCurrentPage()) {
                android.util.Log.i("BENCH", "BilingualSession rebuild skipped: current page has no new translations");
                return;
            }
            rebuild();
        }
    };
    private boolean rebuildScheduled = false;

    // delayed re-check of the bottom hint: shows "正在翻译中…" shortly after
    // the user parks on a page, without needing a tap
    private final Runnable hintRecheckRunnable = new Runnable() {
        @Override public void run() {
            updateHint();
        }
    };

    private BilingualSession(File book, String src, String tgt) {
        this.book = book;
        this.src = src;
        this.tgt = tgt;
        // "save AI translation results" off -> session-only cache: same API,
        // nothing is written to disk (see TranslationCache.inMemory)
        this.cache = AppState.get().aiSaveTranslation
                ? new TranslationCache(book) : TranslationCache.inMemory(book);
        for (int i = 0; i < LANES; i++) {
            lanes[i] = new ArrayDeque<String>();
        }
    }

    public File getBook() {
        return book;
    }

    public String getSrc() {
        return src;
    }

    public String getTgt() {
        return tgt;
    }

    /** Attach the reader activity; also activates this session and pauses others. */
    public void attachHost(Host h) {
        this.host = h;
        try {
            // the open book was just built from the cached translations
            builtMd5s = new HashSet<String>(cache.doneByTextHash(src, tgt).keySet());
        } catch (Throwable t) {
            LOG.e(t);
        }
        setActive(true);
        startWorker();
    }

    public void detachHost() {
        this.host = null;
        setActive(false);
    }

    private void setActive(boolean on) {
        active.set(on);
        if (on) {
            synchronized (queueLock) {
                pending.clear();
            }
        }
        wake();
    }

    private void wake() {
        synchronized (workerLock) {
            workerLock.notifyAll();
        }
    }

    private void startWorker() {
        synchronized (workerLock) {
            if (worker == null) {
                worker = new Thread[LANES];
                for (int i = 0; i < LANES; i++) {
                    final int lane = i;
                    worker[i] = new Thread(new Runnable() {
                        @Override public void run() {
                            workerLoop(lane);
                        }
                    }, "BiTran-" + i);
                    worker[i].setDaemon(true);
                    worker[i].start();
                }
            }
        }
    }

    /** Reader position changed: recompute the translation window. page = 0-based. */
    public void onView(int page0, int pageCount) {
        final boolean jumped = page0 != lastFedPage0;
        lastFedPage0 = page0;
        lastPage0 = page0;
        lastPageCount = pageCount;
        // show/refresh the "正在翻译中…" hint shortly after the user stops
        // flipping, regardless of taps
        ui.removeCallbacks(hintRecheckRunnable);
        ui.postDelayed(hintRecheckRunnable, 500);
        if (jumped && page0 >= 0) {
            android.util.Log.i("BENCH", "BilingualSession jump to page=" + page0
                    + " -> queues rebuilt for the new position");
        }
        if (paras == null) {
            // first enumeration reads the whole base file: do it off the UI
            if (ensuring.compareAndSet(false, true)) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            recomputeWindow();
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (stopped.get()) {
                                        return;
                                    }
                                    // the anchor/hint could not be computed
                                    // before the enumeration finished
                                    onPageShown(lastDc);
                                    updateHint();
                                    maybeRefreshCurrentPage();
                                }
                            });
                        } finally {
                            ensuring.set(false);
                        }
                    }
                }, "BiEnum").start();
            }
            return;
        }
        recomputeWindow();
        wake();
    }

    private synchronized void ensureParas() {
        if (paras != null) {
            return;
        }
        File base = BilingualBuilder.baseFor(book);
        List<BilingualBuilder.Para> enumerated = BilingualBuilder.enumerateParagraphs(base == null ? book : base);
        if (enumerated.isEmpty()) {
            // the cache holds empty placeholder epubs (22-byte zips) for some
            // books — enumerating them once must not poison the session with
            // an empty list forever: leave paras null and retry next time
            android.util.Log.i("BENCH", "BilingualSession paras EMPTY (bad base?) base="
                    + (base == null ? book : base).getPath());
            return;
        }
        paras = enumerated;
        paraNorms = new ArrayList<String>(paras.size());
        paraByMd5 = new HashMap<String, BilingualBuilder.Para>();
        for (BilingualBuilder.Para p : paras) {
            paraNorms.add(norm(p == null ? null : p.text));
            if (p.md5 != null && !paraByMd5.containsKey(p.md5)) {
                paraByMd5.put(p.md5, p);
            }
        }
        android.util.Log.i("BENCH", "BilingualSession paras total=" + paras.size() + " base="
                + (base == null ? book : base).getPath());
    }

    /** 0-based page index -> global source paragraph index (linear estimate). */
    private int pageStart(int page0, int pageCount, int total) {
        if (pageCount <= 0 || total <= 0) {
            return 0;
        }
        long v = (long) total * page0 / pageCount;
        if (v < 0) {
            v = 0;
        }
        if (v > total) {
            v = total;
        }
        return (int) v;
    }

    private void recomputeWindow() {
        try {
            ensureParas();
            if (paras == null) {
                // legitimately no paragraph enumeration (empty base file):
                // nothing to build a window for
                return;
            }
            int total = paras.size();
            if (total == 0 || lastPageCount <= 0 || lastPage0 < 0) {
                return;
            }
            int off = ordOffset;
            int from = Math.max(0, Math.min(total - 1, pageStart(lastPage0 - BACK_PAGES, lastPageCount, total) + off));
            int to = Math.max(0, Math.min(total - 1, pageStart(lastPage0 + AHEAD_PAGES + 1, lastPageCount, total) + off) - 1);
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            int added = 0;
            synchronized (queueLock) {
                // DETERMINISTIC rebuild: the lanes are re-derived from the
                // ground truth (cached / in-flight / failed) on every window
                // recompute, so the queue bookkeeping can never drift (a lost
                // paragraph would otherwise stall translation forever while
                // the hint keeps counting it)
                lanes[0].clear();
                lanes[1].clear();
                lanes[2].clear();
                queued.clear();
                pending.retainAll(inFlight);
                // current page -> lane 0, ahead pages -> lane 1, back pages -> lane 2
                added += enqueuePageRange(done, lastPage0, lastPage0 + 1, 0);
                for (int d = 1; d <= AHEAD_PAGES; d++) {
                    added += enqueuePageRange(done, lastPage0 + d, lastPage0 + d + 1, 1);
                }
                for (int d = 1; d <= BACK_PAGES; d++) {
                    added += enqueuePageRange(done, lastPage0 - d, lastPage0 - d + 1, 2);
                }
                // the precise page paragraphs can sit OUTSIDE the (shifted)
                // linear window when the estimate is still uncalibrated —
                // always make sure the page the reader is ON is queued
                if (currentPageMd5s != null) {
                    for (String md5 : currentPageMd5s) {
                        if (md5 == null || done.containsKey(md5) || inFlight.contains(md5)) {
                            continue;
                        }
                        if (queued.contains(md5)) {
                            continue;
                        }
                        failed.remove(md5);
                        pending.add(md5);
                        queued.add(md5);
                        lanes[0].addLast(md5);
                        added++;
                    }
                }
            }
            android.util.Log.i("BENCH", "BilingualSession onView page=" + lastPage0 + "/" + lastPageCount
                    + " winPages=[" + (lastPage0 - BACK_PAGES) + "," + (lastPage0 + AHEAD_PAGES) + "]"
                    + " winParas=[" + from + "," + to + "] queued=" + added
                    + " done=" + done.size() + " pending=" + pending.size() + " queuedSet=" + queued.size()
                    + " lanes=[" + lanes[0].size() + "," + lanes[1].size() + "," + lanes[2].size() + "]");
            updateHint();
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    /** Enqueue one page range into the given lane (0 = current, 1 = ahead, 2 = back). */
    private int enqueuePageRange(Map<String, String> done, int pageFrom, int pageTo, int lane) {
        if (lastPageCount <= 0) {
            return 0;
        }
        int total = paras.size();
        int off = ordOffset;
        int from = Math.max(0, Math.min(total - 1, pageStart(pageFrom, lastPageCount, total) + off));
        int to = Math.max(0, Math.min(total - 1, pageStart(pageTo, lastPageCount, total) + off) - 1);
        int added = 0;
        for (int i = from; i <= to; i++) {
            BilingualBuilder.Para p = paras.get(i);
            if (p == null || p.md5 == null || TxtUtils.isEmpty(p.text)) {
                continue;
            }
            if (done.containsKey(p.md5) || inFlight.contains(p.md5)) {
                continue;
            }
            if (lane == 0) {
                // the reader is looking at it: allow one more retry after failure
                failed.remove(p.md5);
            } else if (failed.contains(p.md5)) {
                continue;
            }
            pending.add(p.md5);
            queued.add(p.md5);
            lanes[lane].addLast(p.md5);
            added++;
        }
        return added;
    }

    /**
     * One worker per lane. Each drains its own lane first (up to BATCH_SIZE in
     * one AI request); when its lane is empty it steals from the others in
     * priority order (current -> ahead -> back).
     */
    private void workerLoop(final int lane) {
        while (!stopped.get()) {
            synchronized (workerLock) {
                if (!active.get()) {
                    try {
                        workerLock.wait(800);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
            }
            List<String> batch = new ArrayList<String>();
            List<Integer> batchLanes = new ArrayList<Integer>();
            synchronized (queueLock) {
                int guard = 0;
                while (batch.size() < BATCH_SIZE && guard++ < LANES * 2) {
                    int take = lane;
                    if (lanes[take].isEmpty()) {
                        take = -1;
                        // steal: current lane first, then ahead, then back
                        for (int i = 0; i < LANES; i++) {
                            if (!lanes[i].isEmpty()) {
                                take = i;
                                break;
                            }
                        }
                        if (take < 0) {
                            break;
                        }
                    }
                    String md5 = lanes[take].pollFirst();
                    if (md5 == null) {
                        continue;
                    }
                    queued.remove(md5);
                    pending.remove(md5);
                    batch.add(md5);
                    batchLanes.add(take);
                }
            }
            if (batch.isEmpty()) {
                try {
                    synchronized (workerLock) {
                        if (!stopped.get() && allLanesEmpty()) {
                            workerLock.wait(800);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            if (!active.get() || host == null) {
                // paused (activity restarting/detached): keep the items, back
                // to the front of their own lanes
                synchronized (queueLock) {
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        String md5 = batch.get(i);
                        lanes[batchLanes.get(i)].addFirst(md5);
                        queued.add(md5);
                        pending.add(md5);
                    }
                }
                try {
                    synchronized (workerLock) {
                        if (!stopped.get()) {
                            workerLock.wait(800);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            translateBatch(batch);
        }
    }

    private boolean allLanesEmpty() {
        synchronized (queueLock) {
            for (int i = 0; i < LANES; i++) {
                if (!lanes[i].isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Send up to {@link #BATCH_SIZE} paragraphs in ONE AI request; falls back to per-paragraph on any mismatch. */
    private void translateBatch(final List<String> md5s) {
        final List<BilingualBuilder.Para> ps = new ArrayList<BilingualBuilder.Para>();
        synchronized (this) {
            if (paraByMd5 == null) {
                return;
            }
            for (String md5 : md5s) {
                BilingualBuilder.Para p = paraByMd5.get(md5);
                if (p != null && !TxtUtils.isEmpty(p.text)) {
                    ps.add(p);
                }
            }
        }
        if (ps.isEmpty()) {
            return;
        }
        // drop paragraphs that are already translated (cache hit)
        Map<String, String> done = cache.doneByTextHash(src, tgt);
        for (int i = ps.size() - 1; i >= 0; i--) {
            if (done.containsKey(ps.get(i).md5)) {
                android.util.Log.i("BENCH", "BilingualSession " + ps.get(i).md5 + " cache HIT");
                onParagraphDone(ps.get(i).md5, false);
                ps.remove(i);
            }
        }
        if (ps.isEmpty()) {
            return;
        }
        for (BilingualBuilder.Para p : ps) {
            inFlight.add(p.md5);
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ps.size(); i++) {
                sb.append("【").append(i + 1).append("】").append(ps.get(i).text).append("\n\n");
            }
            String suffix = "请把上面编号的" + ps.size() + "个段落逐段翻译成" + AiTranslator.targetLangName(tgt)
                    + "。要求：不要思考、不要分析、不要解释，仅输出译文；必须保持编号，"
                    + "每段译文单独一段，格式为【编号】译文，编号顺序与输入一致，不要合并或拆分段落。";
            String prompt = sb.toString() + suffix;
            StringBuilder ordLog = new StringBuilder();
            for (BilingualBuilder.Para p : ps) {
                if (ordLog.length() > 0) {
                    ordLog.append(',');
                }
                ordLog.append(p.ordinal);
            }
            android.util.Log.i("BENCH", "BilingualBatch ask n=" + ps.size() + " ords=[" + ordLog + "] len="
                    + prompt.length());
            long t0 = System.currentTimeMillis();
            AiClient.TestResult res = AiClient.ask(host == null ? null : host.getAppContext(), prompt);
            android.util.Log.i("BENCH", "BilingualBatch res ok=" + res.ok + " ms="
                    + (System.currentTimeMillis() - t0) + " err=" + res.error + " detail=" + head(res.detail, 200)
                    + " reply=" + (res.reply == null ? -1 : res.reply.length()) + " truncated=" + res.truncated);
            if (res.ok && !res.truncated && TxtUtils.isNotEmpty(res.reply)) {
                String[] parts = splitNumbered(res.reply, ps.size());
                if (parts != null) {
                    boolean allSaved = true;
                    for (int i = 0; i < ps.size(); i++) {
                        String tran = parts[i] == null ? null : parts[i].trim();
                        if (TxtUtils.isEmpty(tran)) {
                            allSaved = false;
                            break;
                        }
                    }
                    if (allSaved) {
                        for (int i = 0; i < ps.size(); i++) {
                            cache.save("h" + ps.get(i).md5, src, tgt, ps.get(i).text, parts[i].trim(), "done");
                        }
                        cache.flush();
                        for (int i = 0; i < ps.size(); i++) {
                            onParagraphDone(ps.get(i).md5, true);
                        }
                        return;
                    }
                }
                android.util.Log.i("BENCH", "BilingualBatch parse mismatch n=" + ps.size() + " head="
                        + head(res.reply, 600));
            }
            // request failed / truncated / reply did not split cleanly:
            // translate the batch paragraph-by-paragraph (each with its own
            // bounded retry), so no paragraph is lost
            for (BilingualBuilder.Para p : ps) {
                translateOne(p.md5, p, res.ok ? "parse" : res.error);
            }
        } catch (Throwable t) {
            LOG.e(t);
        } finally {
            for (BilingualBuilder.Para p : ps) {
                inFlight.remove(p.md5);
            }
        }
    }

    /**
     * Split a numbered reply into {@code n} segments. Accepts the requested
     * "【N】" format and common variants ("1." / "1、" / "[1]" / "1)"). Each
     * parsed segment must be non-empty, otherwise null is returned (the caller
     * falls back to per-paragraph translation).
     */
    private static String[] splitNumbered(String reply, int n) {
        try {
            if (reply == null || n <= 0) {
                return null;
            }
            String[] out = new String[n];
            // "【N】" markers, if the reply uses them at all; otherwise fall
            // back to "N." / "N、" / "[N]" / "N)" at line starts
            String pattern = reply.contains("【")
                    ? "【\\s*(\\d+)\\s*】"
                    : "(?m)^\\s*(?:\\[(\\d+)\\]|(\\d+)\\s*[.、．)）])\\s*";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(reply);
            List<Integer> nums = new ArrayList<Integer>(); // paragraph number of each marker
            List<Integer> body = new ArrayList<Integer>(); // reply offset just after the marker
            while (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                nums.add(Integer.parseInt(g));
                body.add(m.end());
            }
            if (nums.isEmpty()) {
                return null;
            }
            for (int i = 0; i < nums.size(); i++) {
                int num = nums.get(i);
                if (num < 1 || num > n) {
                    continue;
                }
                int end = i + 1 < nums.size() ? lineStartOf(reply, body.get(i + 1)) : reply.length();
                String seg = reply.substring(Math.min(body.get(i), reply.length()),
                        Math.min(end, reply.length()));
                out[num - 1] = out[num - 1] == null ? seg : out[num - 1] + seg;
            }
            for (int i = 0; i < n; i++) {
                if (out[i] == null || out[i].trim().length() == 0) {
                    return null;
                }
            }
            return out;
        } catch (Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    private static int lineStartOf(String s, int pos) {
        int i = pos - 1;
        while (i >= 0 && s.charAt(i) != '\n') {
            i--;
        }
        return i + 1;
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', '|');
        return t.length() <= n ? t : t.substring(0, n);
    }

    /** Single-paragraph translation (fallback path), same retry rules as before. */
    private void translateOne(final String md5, final BilingualBuilder.Para p, final String prevErr) {
        if (p == null || TxtUtils.isEmpty(p.text)) {
            return;
        }
        inFlight.add(md5);
        try {
            if (cache.doneByTextHash(src, tgt).containsKey(md5)) {
                android.util.Log.i("BENCH", "BilingualSession " + md5 + " cache HIT");
                onParagraphDone(md5, false);
                return;
            }
            String suffix = "请把这段文字翻译成" + AiTranslator.targetLangName(tgt)
                    + "，不要思考、不要分析、不要解释，直接回复翻译内容";
            String prompt = p.text + "\n\n" + suffix;
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " AI ask ord=" + p.ordinal
                    + " len=" + p.text.length());
            long t0 = System.currentTimeMillis();
            AiClient.TestResult res = AiClient.ask(host == null ? null : host.getAppContext(), prompt);
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " AI res ok=" + res.ok + " ms="
                    + (System.currentTimeMillis() - t0) + " err=" + res.error + " detail=" + head(res.detail, 200)
                    + " reply=" + (res.reply == null ? -1 : res.reply.length()));
            if (res.ok && TxtUtils.isNotEmpty(res.reply)) {
                cache.save("h" + md5, src, tgt, p.text, res.reply.trim(), "done");
                cache.flush();
                onParagraphDone(md5, true);
                return;
            }
            noteFailure(md5, res == null ? prevErr : res.error);
        } catch (Throwable t) {
            LOG.e(t);
        } finally {
            inFlight.remove(md5);
        }
    }

    /** Bounded retry bookkeeping shared by the batch and single paths. */
    private void noteFailure(final String md5, final String err) {
        Integer n = attempts.get(md5);
        int attempt = n == null ? 0 : n;
        attempts.put(md5, attempt + 1);
        if (attempt < 2) {
            synchronized (queueLock) {
                if (!pending.contains(md5) && !queued.contains(md5)) {
                    pending.add(md5);
                    queued.add(md5);
                    // retries go to the lowest-priority (back) lane so fresh
                    // window paragraphs stay ahead of them
                    lanes[2].addLast(md5);
                }
            }
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " retry attempt=" + attempt);
        } else {
            synchronized (queueLock) {
                failed.add(md5);
            }
            android.util.Log.i("BENCH", "BilingualSession " + md5 + " FAILED err=" + err);
        }
    }

    /** A paragraph finished: schedule one merged rebuild (on the UI thread). */
    private void onParagraphDone(String md5, boolean newly) {
        android.util.Log.i("BENCH", "BilingualSession paragraph done=" + md5 + " new=" + newly);
        if (!newly) {
            return; // nothing changed, nothing to rebuild
        }
        updateHint();
        ui.post(new Runnable() {
            @Override public void run() {
                if (stopped.get()) {
                    return;
                }
                if (rebuildScheduled) {
                    ui.removeCallbacks(rebuildRunnable);
                }
                rebuildScheduled = true;
                ui.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS);
            }
        });
    }

    /**
     * Regenerate the bilingual edition with the latest cached translations and
     * ask the host to re-open the book silently (in place) so the new page
     * shows the translation without flashing.
     */
    private void rebuild() {
        if (stopped.get() || host == null) {
            return;
        }
        rebuildScheduled = false;
        if (!building.compareAndSet(false, true)) {
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File base = BilingualBuilder.baseFor(book);
                    if (base == null) {
                        base = book;
                    }
                    long t0 = System.currentTimeMillis();
                    final File target = BilingualBuilder.ensure(book, base, cache, src, tgt);
                    android.util.Log.i("BENCH", "BilingualSession rebuild ensure ms="
                            + (System.currentTimeMillis() - t0) + " target=" + (target == null ? "null" : target.getName()));
                    if (target != null) {
                        try {
                            builtMd5s = new HashSet<String>(cache.doneByTextHash(src, tgt).keySet());
                        } catch (Throwable t) {
                            LOG.e(t);
                        }
                        final int page0 = lastPage0;
                        final String anchor = anchorMd5;
                        final Host h = host;
                        if (h != null && !stopped.get()) {
                            requestReload(page0, anchor);
                        }
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                } finally {
                    building.set(false);
                }
            }
        }, "BiRebuild").start();
    }

    /** Coalesced request to the host: at most one reload in flight at a time. */
    private void requestReload(final int page0, final String anchorMd5) {
        synchronized (this) {
            if (reloading) {
                reloadPending = true;
                return;
            }
            reloading = true;
        }
        final Host h = host;
        if (h == null || stopped.get()) {
            synchronized (this) {
                reloading = false;
            }
            return;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (stopped.get() || host == null) {
                    synchronized (BilingualSession.this) {
                        reloading = false;
                    }
                    return;
                }
                host.requestReload(page0, anchorMd5);
                synchronized (BilingualSession.this) {
                    reloading = false;
                    if (reloadPending) {
                        reloadPending = false;
                        scheduleRebuild();
                    }
                }
                updateHint();
            }
        });
    }

    private void scheduleRebuild() {
        if (stopped.get()) {
            return;
        }
        if (rebuildScheduled) {
            ui.removeCallbacks(rebuildRunnable);
        }
        rebuildScheduled = true;
        ui.postDelayed(rebuildRunnable, 50);
    }

    /**
     * Remember the top paragraph of the currently shown page (content anchor
     * used to re-land after a rebuild) and dump the page text layer for debug.
     */
    private long lastPageLogMs = 0;

    private void onPageShown(DocumentController dc) {
        try {
            if (paras == null) {
                return;
            }
            int p = dc.getCurentPageFirst1() - 1;
            if (p < 0) {
                return;
            }
            String[] frags = dc.getPageParagraphs(p);
            // remember which source paragraphs are ACTUALLY on this page
            // (the linear page estimate drifts as the bilingual file grows)
            currentPageMd5s = matchPageParagraphs(frags);
            String anchor = anchorOfFragments(frags);
            if (anchor == null) {
                anchor = fallbackAnchor();
            }
            if (anchor != null) {
                anchorMd5 = anchor;
                // calibrate the linear estimate: measure how far the real
                // anchor ordinal sits from the pageStart estimate
                BilingualBuilder.Para ap = paraByMd5 == null ? null : paraByMd5.get(anchor);
                if (ap != null && lastPageCount > 0) {
                    ordOffset = ap.ordinal - pageStart(lastPage0, lastPageCount, paras.size());
                    int maxOff = paras.size();
                    if (ordOffset < -maxOff) {
                        ordOffset = -maxOff;
                    }
                    if (ordOffset > maxOff) {
                        ordOffset = maxOff;
                    }
                }
                android.util.Log.i("BENCH", "BilingualSession anchor p=" + p + " md5=" + anchor
                        + " pageParas=" + (currentPageMd5s == null ? -1 : currentPageMd5s.size())
                        + " ord=" + (ap == null ? -1 : ap.ordinal) + " ordOffset=" + ordOffset);
            }
            long now = System.currentTimeMillis();
            if (now - lastPageLogMs >= 15000) {
                lastPageLogMs = now;
                if (frags == null) {
                    android.util.Log.i("BENCH", "BilingualPageText p=" + p + " paras=null");
                } else {
                    StringBuilder sb = new StringBuilder();
                    int n = Math.min(frags.length, 6);
                    for (int i = 0; i < n; i++) {
                        String t = frags[i];
                        if (t != null && t.length() > 90) {
                            t = t.substring(0, 90);
                        }
                        sb.append('[').append(i).append("]").append(t).append(" || ");
                    }
                    android.util.Log.i("BENCH", "BilingualPageText p=" + p + " count=" + frags.length
                            + " anchor=" + anchor + " " + sb.toString());
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private String anchorOfFragments(String[] frags) {
        try {
            if (frags == null || frags.length == 0) {
                return null;
            }
            List<BilingualBuilder.Para> ps = paras;
            List<String> norms = paraNorms;
            if (ps == null || norms == null || ps.size() != norms.size()) {
                return null;
            }
            for (String f : frags) {
                if (TxtUtils.isEmpty(f)) {
                    continue;
                }
                String nf = norm(f);
                if (nf.length() < 6) {
                    continue;
                }
                String best = null;
                int bestLen = 0;
                for (int i = 0; i < ps.size(); i++) {
                    BilingualBuilder.Para p = ps.get(i);
                    if (p.md5 == null || TxtUtils.isEmpty(p.text)) {
                        continue;
                    }
                    String np = norms.get(i);
                    if (np.length() >= nf.length() && np.indexOf(nf) >= 0 && np.length() > bestLen) {
                        bestLen = np.length();
                        best = p.md5;
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
        return null;
    }

    /**
     * Match the rendered page's text layer back to the source paragraphs it
     * contains. Returns null when nothing matched (caller falls back to the
     * linear page estimate).
     */
    private Set<String> matchPageParagraphs(String[] frags) {
        try {
            if (frags == null || frags.length == 0) {
                return null;
            }
            List<BilingualBuilder.Para> ps = paras;
            List<String> norms = paraNorms;
            if (ps == null || norms == null || ps.size() != norms.size()) {
                return null;
            }
            Set<String> out = new HashSet<String>();
            for (String f : frags) {
                if (TxtUtils.isEmpty(f)) {
                    continue;
                }
                String nf = norm(f);
                if (nf.length() < 6) {
                    continue;
                }
                for (int i = 0; i < ps.size(); i++) {
                    BilingualBuilder.Para p = ps.get(i);
                    if (p == null || p.md5 == null) {
                        continue;
                    }
                    String np = norms.get(i);
                    if (np.length() < 6) {
                        continue;
                    }
                    if (np.indexOf(nf) >= 0) {
                        // the page fragment sits inside this source paragraph
                        out.add(p.md5);
                    } else if (np.length() >= 8 && nf.indexOf(np) >= 0) {
                        // a short paragraph fully contained in a page fragment
                        out.add(p.md5);
                    }
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    /**
     * Fill {@code out} with the md5s of the paragraphs the current page shows:
     * precise text-layer match when available, else the linear estimate.
     */
    private void collectCurrentPage(Set<String> out) {
        Set<String> exact = currentPageMd5s;
        if (exact != null && !exact.isEmpty()) {
            out.addAll(exact);
            return;
        }
        List<BilingualBuilder.Para> ps = paras;
        if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
            return;
        }
        int total = ps.size();
        int from = pageStart(lastPage0, lastPageCount, total);
        int to = pageStart(lastPage0 + 1, lastPageCount, total) - 1;
        for (int i = Math.max(0, from); i <= Math.min(total - 1, to); i++) {
            BilingualBuilder.Para p = ps.get(i);
            if (p != null && p.md5 != null) {
                out.add(p.md5);
            }
        }
    }

    private String fallbackAnchor() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return null;
            }
            long idx = (long) (ps.size() * (lastPage0 + 0.5f) / lastPageCount);
            int i = Math.max(0, Math.min(ps.size() - 1, (int) idx));
            BilingualBuilder.Para p = ps.get(i);
            return p == null ? null : p.md5;
        } catch (Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    /** True when the current page still has paragraphs not shown bilingually. */
    public boolean isCurrentPagePending() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return false;
            }
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            Set<String> built = builtMd5s;
            Set<String> page = new HashSet<String>();
            collectCurrentPage(page);
            synchronized (queueLock) {
                for (String md5 : page) {
                    if (done.containsKey(md5)) {
                        // translated but the open book predates it
                        if (!built.contains(md5)) {
                            return true;
                        }
                    } else if (!failed.contains(md5)) {
                        // still translating / queued / not started
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            LOG.e(t);
            return false;
        }
    }

    /**
     * Bottom-hint text with live queue counters, or null when nothing is
     * pending for the current page.
     */
    public String currentHintText() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return null;
            }
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            Set<String> built = builtMd5s;
            Set<String> page = new HashSet<String>();
            collectCurrentPage(page);
            int pageLeft = 0;
            synchronized (queueLock) {
                for (String md5 : page) {
                    if (done.containsKey(md5)) {
                        if (!built.contains(md5)) {
                            pageLeft++;
                        }
                    } else if (!failed.contains(md5)) {
                        pageLeft++;
                    }
                }
            }
            if (pageLeft <= 0) {
                return null;
            }
            int inQueue;
            synchronized (queueLock) {
                inQueue = pending.size() + inFlight.size();
            }
            return "正在翻译中… 本页剩 " + pageLeft + " 段 · 队列 " + inQueue + " 段";
        } catch (Throwable t) {
            LOG.e(t);
            return null;
        }
    }

    /** If the current page has translations ready that the open book lacks, refresh it. */
    private void maybeRefreshCurrentPage() {
        try {
            if (!needsRefreshForCurrentPage()) {
                return;
            }
            android.util.Log.i("BENCH", "BilingualSession current page stale -> refresh");
            ui.post(new Runnable() {
                @Override public void run() {
                    if (!stopped.get() && host != null) {
                        scheduleRebuild();
                    }
                }
            });
        } catch (Throwable t) {
            LOG.e(t);
        }
    }

    private boolean needsRefreshForCurrentPage() {
        try {
            List<BilingualBuilder.Para> ps = paras;
            if (ps == null || ps.isEmpty() || lastPageCount <= 0 || lastPage0 < 0) {
                return false;
            }
            Map<String, String> done = cache.doneByTextHash(src, tgt);
            Set<String> built = builtMd5s;
            Set<String> page = new HashSet<String>();
            collectCurrentPage(page);
            for (String md5 : page) {
                if (done.containsKey(md5) && !built.contains(md5)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOG.e(t);
            return false;
        }
    }

    private void updateHint() {
        if (host == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                if (!stopped.get() && host != null) {
                    host.requestHintUpdate();
                }
            }
        });
    }

    private void dispose() {
        stopped.set(true);
        ui.removeCallbacks(rebuildRunnable);
        synchronized (workerLock) {
            workerLock.notifyAll();
        }
        try {
            if (worker != null) {
                for (Thread w : worker) {
                    if (w != null) {
                        w.interrupt();
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        worker = null;
        // the session can outlive the reader in the static SESSIONS map:
        // drop the Activity/controller references so they are not pinned
        host = null;
        lastDc = null;
    }
}
