package com.foobnix.ai;

import android.content.Context;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI translation engine for the reader. Translates a small window of pages
 * around the current page (previous page .. current page .. 3 pages ahead),
 * paragraph by paragraph, and reports each result to a {@link Listener}.
 *
 * A paragraph is one MuPDF text block, recovered by splitting the page HTML
 * (from the working {@code getPageAsHtml} native) on its {@code <end-block>}
 * markers. The stable cache anchor (pid) is {@code ch<chapter>_h<md5(原文)>} —
 * chapter + a content hash of the paragraph — so it survives reflow (font
 * size / width changes), unlike line or page numbers.
 */
public class AiTranslator {

    /** Callbacks are delivered on the background translation thread. */
    public interface Listener {
        /** One paragraph translated (or failed). */
        void onParagraph(String pid, String orig, String tran, String status);

        /** All paragraphs in the window have been processed. */
        void onFinished(boolean ok);
    }

    /** Formats that expose a text layer and are supported by translation. */
    public static boolean isSupportedFormat(String path) {
        if (TxtUtils.isEmpty(path)) {
            return false;
        }
        String p = path.toLowerCase(Locale.US);
        return p.endsWith(".epub") || p.endsWith(".txt") || p.endsWith(".mobi")
                || p.endsWith(".azw") || p.endsWith(".azw3");
    }

    /** Human name of the target language for the prompt (英文/中文/日文). */
    public static String targetLangName(String tgt) {
        if (LanguageDetector.JA.equals(tgt)) {
            return "日文";
        }
        if (LanguageDetector.ZH.equals(tgt)) {
            return "中文";
        }
        return "英文";
    }

    /** The running translation thread (null when idle) — {@link #cancel()}
     * interrupts it. */
    private static volatile Thread currentJob;

    /**
     * Stop the running translation at the next page/paragraph boundary.
     * Dismissing the panel only removed its view: the background thread used
     * to keep issuing AI requests (real API cost) for a dead panel and pin
     * the finished Activity until the loop ended.
     */
    public static void cancel() {
        final Thread t = currentJob;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Start translating on a background thread.
     *
     * @param c       context (for the AI client)
     * @param dc      the open document controller
     * @param srcLang detected/selected source language (informational)
     * @param tgtLang target language (en / zh-CN / ja)
     * @param listener progress callback (background thread)
     */
    public static void translate(final Context c, final DocumentController dc,
            final String srcLang, final String tgtLang, final Listener listener) {
        final Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    doTranslate(c, dc, srcLang, tgtLang, listener);
                } catch (Throwable th) {
                    LOG.e(th);
                    android.util.Log.i("BENCH", "AiTranslator EXCEPTION: "
                            + th.getClass().getName() + " " + th.getMessage());
                    listener.onFinished(false);
                } finally {
                    currentJob = null;
                }
            }
        }, "AiTranslate");
        currentJob = thread;
        thread.start();
    }

    private static void doTranslate(Context c, DocumentController dc, String srcLang,
            String tgtLang, Listener listener) {
        File book = dc.getCurrentBook();
        if (book == null) {
            listener.onFinished(false);
            return;
        }
        boolean saveEnabled = AppState.get().aiSaveTranslation;
        // inMemory() also READS the on-disk cache (session hits) but never
        // writes: with "save results" off the panel still reuses translations
        // instead of re-asking the provider for every paragraph
        TranslationCache cache = TranslationCache.inMemory(book);

        int total = dc.getPageCount();
        int current = dc.getCurentPageFirst1(); // 1-based
        int start = Math.max(1, current - 1);
        int end = Math.min(total, current + 3);
        if (total <= 0) {
            listener.onFinished(false);
            return;
        }

        List<OutlineLinkWrapper> outline = dc.getCurrentOutline();
        String suffix = "请把这段文字翻译成" + targetLangName(tgtLang)
                + "，不要启用思考过程，直接回复翻译内容";
        android.util.Log.i("BENCH", "AiTranslator start total=" + total + " current=" + current
                + " start=" + start + " end=" + end + " outline="
                + (outline == null ? "null" : outline.size()) + " save=" + saveEnabled);

        int done = 0, failed = 0, empty = 0;
        for (int p = start; p <= end; p++) {
            if (Thread.currentThread().isInterrupted()) {
                return; // cancelled: the panel is gone, stay silent
            }
            int chapter = chapterIndexForPage(outline, p);
            String[] paras = dc.getPageParagraphs(p - 1); // 0-based page
            // Pages outside the reader's decode window are recycled and yield
            // null; skip them (the current page is always live, so at least it
            // translates). Forcing a decode of the whole window here would stall
            // the first result for a long time.
            if (paras == null) {
                android.util.Log.i("BENCH", "AiTranslator page " + p + " paras=null (recycled)");
                continue;
            }
            android.util.Log.i("BENCH", "AiTranslator page " + p + " paras=" + paras.length);
            if (p == start && paras.length > 0) {
                String sample = paras[0];
                if (sample.length() > 120) {
                    sample = sample.substring(0, 120);
                }
                android.util.Log.i("BENCH", "AiTranslator page " + p + " sample=[" + sample + "]");
            }
            for (int i = 0; i < paras.length; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    return; // cancelled mid-page: stop spending API quota
                }
                String orig = clean(paras[i]);
                if (TxtUtils.isEmpty(orig)) {
                    empty++;
                    continue;
                }
                // Anchor = chapter + content hash of the paragraph. Computed from
                // text we already have (no chapter pre-scan), stable across
                // reflow (same text -> same hash), and the cache's orig drift-
                // guard already guarantees correctness. A within-chapter
                // paragraph number would require decoding every page from the
                // chapter start first (minutes of no output) and is itself
                // unstable when recycled pages return null.
                String pid = "ch" + chapter + "_h"
                        + com.foobnix.android.utils.FileHash.md5(orig);

                String cached = cache == null ? null : cache.lookup(pid, srcLang, tgtLang, orig);
                if (cached != null) {
                    done++;
                    android.util.Log.i("BENCH", "AiTranslator " + pid + " cache HIT");
                    listener.onParagraph(pid, orig, cached, "done");
                    continue;
                }

                String prompt = orig + "\n\n" + suffix;
                android.util.Log.i("BENCH", "AiTranslator " + pid + " AI ask orig.len=" + orig.length());
                AiClient.TestResult res = AiClient.ask(c, prompt);
                android.util.Log.i("BENCH", "AiTranslator " + pid + " AI res ok=" + res.ok
                        + " err=" + res.error + " detail=" + res.detail + " reply.len="
                        + (res.reply == null ? -1 : res.reply.length()));
                if (res.ok && TxtUtils.isNotEmpty(res.reply)) {
                    String tran = res.reply.trim();
                    if (cache != null) {
                        cache.save(pid, srcLang, tgtLang, orig, tran, "done");
                    }
                    done++;
                    listener.onParagraph(pid, orig, tran, "done");
                } else {
                    if (cache != null) {
                        cache.save(pid, srcLang, tgtLang, orig, "", "failed");
                    }
                    failed++;
                    listener.onParagraph(pid, orig, "", "failed");
                }
            }
        }

        if (cache != null) {
            cache.flush();
        }
        android.util.Log.i("BENCH", "AiTranslator done=" + done + " failed=" + failed + " empty=" + empty
                + " save=" + saveEnabled);
        listener.onFinished(failed == 0 && (done + empty) > 0);
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

    /**
     * Split a MuPDF page-HTML string into plain-text paragraphs.
     *
     * In this MuPDF build the page HTML (from the working {@code getPageAsHtml}
     * native) marks each paragraph boundary with {@code <pause>} and each line
     * within a paragraph with its own {@code <p>...</p>}; inline styling uses
     * {@code <b>/<i>/<tt>}. Lines are soft-hyphenated at the break (e.g.
     * "transla-" / "tion"), so when a line ends in a hyphen we drop it and join
     * without a space to recover the full word. We therefore split on
     * {@code <pause>} to get paragraphs, then join the {@code <p>} lines inside
     * each. This uses the working {@code getPageAsHtml} native — the
     * {@code MuPdfPage.text()} native is absent from the prebuilt libMuPDF.so
     * and throws {@code UnsatisfiedLinkError}, which is why the old paragraph
     * extraction silently returned nothing.
     */
    public static String[] htmlToParagraphs(String html) {
        if (html == null || html.isEmpty()) {
            return new String[0];
        }
        String[] chunks = html.split("<pause>");
        List<String> paras = new ArrayList<>();
        for (String chunk : chunks) {
            String t = joinLines(chunk);
            if (!t.isEmpty()) {
                paras.add(t);
            }
        }
        return paras.toArray(new String[0]);
    }

    /** Join the {@code <p>} lines of one paragraph chunk into a single clean string. */
    private static String joinLines(String chunk) {
        if (chunk == null) {
            return "";
        }
        // Soft-hyphenation: a line ending in "-" before the next line is a split
        // word (e.g. "transla-" + "tion" -> "translation"). Drop the hyphen and
        // join without a space.
        String s = chunk
                .replace("-</p>", "</p>")
                .replace("- </p>", "</p>")
                .replace("</p>", " ")
                .replace("<p>", " ")
                .replace("<b>", "")
                .replace("</b>", "")
                .replace("<i>", "")
                .replace("</i>", "")
                .replace("<tt>", "")
                .replace("</tt>", "");
        // Drop any residual tags (e.g. <div class="...">) as a safety net.
        s = s.replaceAll("<[^>]*>", " ");
        // Decode the few entities MuPDF may emit.
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }
}
