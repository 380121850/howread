package com.foobnix.pdf.info;

import android.os.SystemClock;
import android.util.Log;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.BookType;
import org.ebookdroid.core.codec.CodecContext;
import org.ebookdroid.core.codec.CodecDocument;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lays out books in the background (open + page count) purely to persist the
 * MuPDF accelerator file, so the first real open of a book skips the expensive
 * full-document layout. Skipped while a reader activity is in the foreground.
 */
public class BookWarmer {
    private static final int MAX_WARM_BOOKS = 5;
    private static final Set<String> warmed = Collections.synchronizedSet(new LinkedHashSet<String>());

    public static void warmAsync(final List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        final List<String> todo = new ArrayList<String>();
        synchronized (warmed) {
            for (String path : paths) {
                if (path != null && warmed.size() < MAX_WARM_BOOKS && !warmed.contains(path) && new File(path).isFile()) {
                    warmed.add(path);
                    todo.add(path);
                }
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        // dedicated thread: the shared 2-thread AppsConfig pool must stay
        // free for the decode consumer and other UI services
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                for (String path : todo) {
                    if (TempHolder.readerActive) {
                        return; // user started reading; don't compete for the native lock
                    }
                    warmOne(path);
                }
            }
        }, "@T BookWarmer");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Books larger than this are skipped: their full-document warm layout
     * can run for many minutes (giant single-chapter scan copies) while
     * holding the global native lock in chunks, starving real reader opens. */
    private static final long MAX_WARM_FILE_BYTES = 100L * 1024 * 1024;

    private static void warmOne(final String path) {
        if (new File(path).length() > MAX_WARM_FILE_BYTES) {
            return; // too big to warm: the first real open handles it
        }
        final long t0 = SystemClock.elapsedRealtime();
        CodecContext context = null;
        CodecDocument document = null;
        try {
            context = BookType.getCodecContextByPath(path);
            if (context == null) {
                return;
            }
            document = context.openDocument(path, "");
            if (document != null && !document.isRecycled()) {
                final int w = Dips.screenWidth();
                final int h = Dips.screenHeight();
                final int sp = BookCSS.get().fontSizeSp;
                // Chunked warm-up: ~400 pages per native call with the global
                // lock released in between, and an immediate yield as soon as
                // the user starts reading, so warming never blocks the reader.
                int total = 0;
                int requested = 400;
                int guard = 0;
                while (!TempHolder.readerActive && guard++ < 200) {
                    // Yield the global native lock to anyone waiting (reader
                    // threads have priority over background warming).
                    int yield = 0;
                    while (TempHolder.lock.hasQueuedThreads() && yield++ < 50) {
                        Thread.sleep(100);
                    }
                    if (TempHolder.readerActive) {
                        break;
                    }
                    final int n = document.getPageCountProgressive(w, h, sp, requested);
                    if (n <= total) {
                        total = Math.max(total, n);
                        break;
                    }
                    total = n;
                    if (n < requested) {
                        break; // document end reached
                    }
                    requested = total + 400;
                }
                Log.i("BENCH", "warm " + ExtUtils.getFileName(path) + " pages=" + total
                        + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
            }
        } catch (Throwable e) {
            LOG.e(e);
        } finally {
            try {
                if (document != null) {
                    document.recycle();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (context != null) {
                    context.recycle();
                }
            } catch (Throwable ignore) {
            }
        }
    }
}
