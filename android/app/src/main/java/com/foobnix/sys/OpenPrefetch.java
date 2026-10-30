package com.foobnix.sys;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.remote.RemoteBook;
import com.foobnix.ui2.AppDB;
import org.ebookdroid.BookType;
import org.ebookdroid.common.settings.books.SharedBooks;
import org.ebookdroid.core.codec.AbstractCodecContext;
import org.ebookdroid.core.codec.CodecContext;
import org.ebookdroid.core.codec.CodecDocument;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Open-pipeline parallelization (open-prefetch): the moment the book path
 * is known (before the reader Activity is created) the document is opened
 * on a background thread. The reader's load task then adopts the already
 * open document instead of paying meta+native-open serially after the
 * activity's onCreate + first frame.
 *
 * The native open bakes in the file identity AND the reader settings (CSS
 * string, image scale, anti-alias level, memory cap, hyphenation language) —
 * every one of them is snapshotted into the entry key and re-verified at
 * adoption time; on any mismatch the prefetched document is recycled and
 * the normal serial open runs.
 *
 * The prefetch engages only when the book's conversion cache is already
 * warm: then the open is a tens-of-ms native attach and running it in
 * parallel with the reader launch is pure gain. Cold-cache books keep the
 * plain serial conversion — the prefetch and the load task would otherwise
 * race on the shared cache file and the conversion lock.
 */
public final class OpenPrefetch {

    /** Unadopted entries are recycled: a doc the reader never adopts would
     *  leak the whole native document (100s of MB for big books). */
    private static final long ADOPT_TIMEOUT_MS = 20 * 1000L;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final class Entry {
        final String path;
        final BookType type;
        final CodecContext context;
        volatile CodecDocument doc;
        /** Adoption key snapshotted on the prefetch thread AFTER the
         *  hyphenation language was aligned (see alignHyphenLang). Written
         *  before {@code current} is published, so a published entry
         *  always carries it. */
        volatile String finalKey;
        final long born;
        Entry(final String path, final BookType type,
              final CodecContext context, final CodecDocument doc) {
            this.path = path;
            this.type = type;
            this.context = context;
            this.doc = doc;
            this.born = SystemClock.elapsedRealtime();
        }
    }

    /** The single published-and-waiting-for-adoption entry. Every lifecycle
     *  transition (publish / adopt / timeout / supersede) claims the entry
     *  through a compare-and-swap: an entry is recycled by exactly one
     *  thread, and a doc handed to the reader can never be recycled behind
     *  its back — a lost race always degrades to the plain serial open. */
    private static final AtomicReference<Entry> current = new AtomicReference<Entry>();

    /** Path of the currently in-flight prefetch open (set on the caller
     *  thread before the worker starts, compare-and-cleared by the worker
     *  when done). Best-effort dedup so a rapid re-open of the same book
     *  does not spawn a duplicate native open; the atomic publish swap is
     *  the leak-proof safety net for any window this marker misses. */
    private static volatile String inFlightPath;

    private OpenPrefetch() {
    }

    /** Everything the native open bakes in, as one snapshot string. */
    private static String settingsKey(final String path) {
        try {
            final BookCSS css = BookCSS.get();
            return css.toCssString(path) + "|"
                    + (css.documentStyle == BookCSS.STYLES_ONLY_USER ? 0 : 1) + "|"
                    + css.imageScale + "|"
                    + AppState.get().allocatedMemorySize + "|"
                    + (AppState.get().enableImageScale ? 1 : 0) + "|"
                    + AppState.get().antiAliasLevel + "|"
                    + AbstractCodecContext.hyphenKeyPart();
        } catch (final Throwable t) {
            return "settings-key-err";
        }
    }

    private static String keyOf(final String path, final long mod, final long size,
                                final String password) {
        return path + "|" + mod + "|" + size + "|" + (password == null ? "" : password)
                + "|" + settingsKey(path);
    }

    /** Mirror of {@code BookCSS.detectLang} WITHOUT the O(file) meta
     *  creation (that belongs to the load task): it aligns the hyphenation
     *  language to the value the reader's load task will set for this book,
     *  so the settings snapshot below matches what adoption re-computes.
     *  Without this the raw language the CSS string embeds (a WebDAV sync
     *  flip, zh vs absent) would differ between the two threads and force a
     *  serial fallback on every open. */
    private static void alignHyphenLang(final String path) {
        try {
            if (AppState.get().isDefaultHyphenLanguage) {
                AppSP.get().hypenLang = AppState.get().defaultHyphenLanguageCode;
                return;
            }
            final FileMeta meta = AppDB.get().load(path);
            if (meta != null && TxtUtils.isNotEmpty(meta.getLang())) {
                AppSP.get().hypenLang = meta.getLang();
                return;
            }
            final AppBook load = SharedBooks.load(path);
            if (load != null && TxtUtils.isNotEmpty(load.ln)) {
                AppSP.get().hypenLang = load.ln;
                return;
            }
            final String appLang = AppState.get().getAppLang();
            if (TxtUtils.isNotEmpty(appLang)) {
                AppSP.get().hypenLang = appLang;
            }
        } catch (final Throwable ignored) {
        }
    }

    /** Kick off the prefetched open for a local book. No-op for remote
     *  streaming paths, unknown types, folders, cold conversion cache, and
     *  when the same book is already being prefetched. */
    public static void begin(final String path, final String password) {
        try {
            if (path == null || path.length() == 0) {
                return;
            }
            if (path.startsWith(RemoteBook.PREFIX)) {
                return; // remote streaming: its own pipeline
            }
            final BookType type = BookType.getByUri(path);
            if (type == null || type == BookType.NULL || type == BookType.FOLDER) {
                return;
            }
            final String normPath = AbstractCodecContext.normalizeStoragePath(path);
            final File f = new File(normPath);
            if (!f.isFile() || !f.canRead()) {
                return;
            }
            final Entry old = current.get();
            if (old != null && old.path.equals(normPath)) {
                // the same book is already published and waiting for adoption
                LOG.bench("prefetch reuse " + shortName(normPath));
                return;
            }
            final String inflight = inFlightPath;
            if (inflight != null && inflight.equals(normPath)) {
                // the same book is still being opened on the prefetch thread
                LOG.bench("prefetch reuse(in-flight) " + shortName(normPath));
                return;
            }
            final BookType btype = type;
            final String bnormPath = normPath;
            // keyOf() normalizes a null password to "" — the open call must
            // too: a raw null flows into native MuPdfDocument.open and JNI
            // aborts the whole process (GetStringUTFChars received NULL
            // jstring). Unprotected books are the common null case.
            final String bpassword = password == null ? "" : password;
            final String bfname = shortName(normPath);
            if (old != null && current.compareAndSet(old, null)) {
                // a published entry for ANOTHER book is waiting: claim it
                // (if an adopt is committing it concurrently the CAS loses
                // and the adopter owns it) and recycle it here.
                recycleQuiet(old, "superseded");
            }
            inFlightPath = normPath;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final long t0 = SystemClock.elapsedRealtime();
                        LOG.bench("prefetch begin " + bfname);
                        final CodecContext context = BookType.getCodecContextByType(btype);
                        if (context == null) {
                            return;
                        }
                        // 1) align the hyphenation language (see above)
                        alignHyphenLang(bnormPath);
                        // 2) warm-cache gate: a missing conversion cache means
                        //    the load task would have to convert and the
                        //    prefetch must not race that conversion — except
                        //    for native-open formats (isConversionFreeOpen):
                        //    PDF always, EPUB when no enhancement flag is on
                        //    and the accelerator file already exists.
                        File cache = null;
                        if (context instanceof AbstractCodecContext) {
                            cache = ((AbstractCodecContext) context).getCacheFileName(
                                    bnormPath + AbstractCodecContext.getFileNameSalt(bnormPath));
                        }
                        if (cache == null || !cache.isFile()) {
                            if (!isConversionFreeOpen(btype, cache)) {
                                LOG.bench("prefetch skip(no-cache) " + bfname);
                                // the context is dropped unused here — recycle it,
                                // it may hold a native handle (DjvuContext creates
                                // one right in its constructor)
                                context.recycle();
                                return;
                            }
                            LOG.bench("prefetch gate native-open " + bfname);
                        }
                        // 3) a reader close parks a cancel flag in the
                        //    shared temp state; this is a fresh open — clear
                        //    it the way the load task does before its open.
                        TempHolder.get().loadingCancelled.set(false);
                        final CodecDocument doc = context.openDocument(bnormPath, bpassword);
                        if (doc == null) {
                            context.recycle();
                            LOG.bench("prefetch open-null " + bfname);
                            return;
                        }
                        final File now = new File(bnormPath);
                        final Entry e = new Entry(bnormPath, btype, context, doc);
                        e.finalKey = keyOf(bnormPath, now.lastModified(), now.length(), bpassword);
                        // Atomic publish: also claim whatever entry is
                        // currently waiting — a rapid second open may have
                        // published while this open was in flight. Without
                        // the swap the displaced entry's native document
                        // would leak until process death.
                        final Entry prev = current.getAndSet(e);
                        if (prev != null) {
                            LOG.bench("prefetch superseded-at-publish " + shortName(prev.path));
                            recycleQuiet(prev, "superseded-publish");
                        }
                        LOG.bench("prefetch open-done "
                                + (SystemClock.elapsedRealtime() - t0) + "ms " + bfname);
                        UI.postDelayed(new Runnable() {
                            @Override public void run() {
                                final Entry c = current.get();
                                if (c == e && e.doc != null && current.compareAndSet(e, null)) {
                                    LOG.bench("prefetch unadopted -> recycle " + bfname);
                                    recycleQuiet(e, "timeout");
                                }
                            }
                        }, ADOPT_TIMEOUT_MS);
                    } catch (final Throwable t2) {
                        LOG.e(t2);
                    } finally {
                        if (bnormPath.equals(inFlightPath)) {
                            inFlightPath = null;
                        }
                    }
                }
            }, "@T OpenPrefetch");
            t.start();
        } catch (final Throwable t) {
            LOG.e(t);
        }
    }

    /** Adopt the prefetched document when it matches the open request, or
     *  recycle it and return null (caller then opens serially). */
    public static CodecDocument adopt(final String fileName, final String password) {
        final Entry e = current.get();
        if (e == null || e.doc == null) {
            return null;
        }
        try {
            final BookType type = BookType.getByUri(fileName);
            if (type != e.type) {
                return miss(e, "type");
            }
            final String normPath = AbstractCodecContext.normalizeStoragePath(fileName);
            if (!e.path.equals(normPath)) {
                return miss(e, "path");
            }
            final File f = new File(normPath);
            final String key = keyOf(normPath, f.lastModified(), f.length(), password);
            final String stored = e.finalKey;
            if (stored == null || !stored.equals(key)) {
                return miss(e, "key");
            }
            if (!current.compareAndSet(e, null)) {
                // displaced while we checked (a newer open published, or the
                // watchdog fired): the new owner recycles e — fall back to
                // the serial open, never hand out a recycled document.
                LOG.bench("prefetch miss(displaced) " + shortName(normPath));
                return null;
            }
            LOG.bench("prefetch adopt " + shortName(normPath));
            return e.doc;
        } catch (final Throwable t) {
            LOG.e(t);
            return miss(e, "err");
        }
    }

    /** True when opening this book never touches the conversion cache, so a
     *  prefetch cannot race any conversion. PDF is native per-page (no
     *  conversion concept). EPUB without the hyphen/replacement/footer-note
     *  flags also opens natively — but only counts as prefetch-safe when
     *  its accelerator file already exists: a first open without accel
     *  parses the whole container (slow), and adopt() has no in-flight
     *  wait, so racing it would leave the load task waiting on the native
     *  lock and then re-opening the book serially. */
    private static boolean isConversionFreeOpen(final BookType type, final File cache) {
        if (type == BookType.PDF) {
            return true;
        }
        if (type == BookType.EPUB) {
            final AppState st = AppState.get();
            final boolean noEnhancementFlags = !(st.isEnableTextReplacement
                    || BookCSS.get().isAutoHypens
                    || st.isReferenceMode
                    || st.isShowFooterNotesInText);
            if (!noEnhancementFlags || cache == null) {
                return false;
            }
            // mirror of MuPdfDocument.openFile's accelerator key: the cache
            // name relocated from the book cache dir into the temp cache dir
            final File accel = new File(cache.getPath()
                    .replace(CacheZipUtils.CACHE_BOOK_DIR.getPath(), CacheZipUtils.CACHE_TEMP.getPath())
                    + "+accel");
            return accel.isFile();
        }
        return false;
    }

    private static CodecDocument miss(final Entry e, final String why) {
        // claim before recycling: a concurrent adopt/publish may already
        // have taken e over — then it is theirs, not ours to touch
        if (current.compareAndSet(e, null)) {
            LOG.bench("prefetch miss(" + why + ") " + shortName(e.path));
            recycleQuiet(e, why);
        }
        return null;
    }

    private static void recycleQuiet(final Entry e, final String why) {
        if (e == null) {
            return;
        }
        try {
            if (e.doc != null) {
                e.doc.recycle();
            }
        } catch (final Throwable t) {
            LOG.w(t);
        }
        try {
            e.context.recycle();
        } catch (final Throwable t) {
            LOG.w(t);
        }
        e.doc = null;
        LOG.bench("prefetch recycled(" + why + ") " + shortName(e.path));
    }

    private static String shortName(final String path) {
        final int i = path.lastIndexOf('/');
        return i >= 0 && i < path.length() - 1 ? path.substring(i + 1) : path;
    }
}
