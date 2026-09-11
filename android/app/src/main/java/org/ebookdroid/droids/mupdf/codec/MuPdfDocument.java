package org.ebookdroid.droids.mupdf.codec;

import android.graphics.RectF;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.BookType;
import org.ebookdroid.core.codec.AbstractCodecContext;
import org.ebookdroid.core.codec.AbstractCodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.core.codec.CodecPageInfo;
import org.ebookdroid.core.codec.OutlineLink;
import org.ebookdroid.droids.EpubContext;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MuPdfDocument extends AbstractCodecDocument {

    public static final int FORMAT_PDF = 0;

    public static final String META_INFO_AUTHOR = "info:Author";
    public static final String META_INFO_TITLE = "info:Title";
    public static final String META_INFO_SUBJECT = "info:Subject";
    public static final String META_INFO_KEYWORDS = "info:Keywords";
    public static final String META_INFO_CREATOR = "info:Creator";
    public static final String META_INFO_PRODUCER = "info:Producer";
    public static final String META_INFO_CREATIONDATE = "info:CreationDate";
    public static final String META_INFO_MODIFICATIONDATE = "info:ModDate";
    private static long cacheHandle;
    private static int cacheWH;
    private static long cacheSize;
    private static int cacheCount;
    int w, h;
    BookType bookType;
    private boolean isEpub = false;
    private volatile Map<String, String> footNotes;
    private volatile List<String> mediaAttachment;
    private int pagesCount = -1;
    private String fname;

    public MuPdfDocument(final MuPdfContext context, final int format, final String fname, final String pwd) {
        super(context, openFile(format, fname, pwd, BookCSS.get()
                                                           .toCssString(fname)));
        this.fname = fname;
        isEpub = ExtUtils.isTextFomat(fname);
        bookType = BookType.getByUri(fname);
    }

    static void normalizeLinkTargetRect(final long docHandle, final int targetPage, final RectF targetRect,
                                        final int flags) {

        if ((flags & 0x0F) == 0) {
            targetRect.right = targetRect.left = 0;
            targetRect.bottom = targetRect.top = 0;
            return;
        }

        final CodecPageInfo cpi = new CodecPageInfo();
        TempHolder.lock.lock();
        try {
            MuPdfDocument.getPageInfo(docHandle, targetPage, cpi);
        } finally {
            TempHolder.lock.unlock();
        }

        final float left = targetRect.left;
        final float top = targetRect.top;

        if (((cpi.rotation / 90) % 2) != 0) {
            targetRect.right = targetRect.left = left / cpi.height;
            targetRect.bottom = targetRect.top = 1.0f - top / cpi.width;
        } else {
            targetRect.right = targetRect.left = left / cpi.width;
            targetRect.bottom = targetRect.top = 1.0f - top / cpi.height;
        }
    }

    native static int getPageInfo(long docHandle, int pageNumber, CodecPageInfo cpi);

    // 'info:Title'
    // 'info:Author'
    // 'info:Subject'
    // 'info:Keywords'
    // 'info:Creator'
    // 'info:Producer'
    // 'info:CreationDate'
    // 'info:ModDate'
    private native static String getMeta(long docHandle, final String option);

    private native static String setMetaData(long docHandle, final String key, String value);

    private static long openFile(final int format, String fname, final String pwd, String css) {
        if (fname != null && fname.startsWith(com.foobnix.remote.RemoteBook.PREFIX)) {
            return openRemoteFile(format, fname, pwd, css);
        }
        TempHolder.lock.lock();
        try {
            int allocatedMemory = AppState.get().allocatedMemorySize * 1024 * 1024;
            // int allocatedMemory = CoreSettings.get().pdfStorageSize;
            LOG.d("allocatedMemory", AppState.get().allocatedMemorySize, " MB " + allocatedMemory);
            int isImageScale = AppState.get().enableImageScale ? 1 : 0;

            LOG.d("accel cache1", fname);
            // Key the accelerator by file content version too: reusing a stale
            // accelerator after the book file changed would report wrong page
            // counts.
            String accel = new EpubContext().getCacheFileName(fname + AbstractCodecContext.getFileNameSalt(fname))
                                            .getPath() + "+accel";
            accel = accel.replace(CacheZipUtils.CACHE_BOOK_DIR.getPath(), CacheZipUtils.CACHE_TEMP.getPath());
            LOG.d("accel cache2", accel, new File(accel).exists());
            CacheZipUtils.trimAccel(CacheZipUtils.CACHE_TEMP == null ? null : CacheZipUtils.CACHE_TEMP.listFiles(),
                    new File(accel), 8);

            final long open = open(allocatedMemory, format, fname, pwd, css,
                    BookCSS.get().documentStyle == BookCSS.STYLES_ONLY_USER ? 0 : 1, BookCSS.get().imageScale,
                    AppState.get().antiAliasLevel, accel, isImageScale);
            LOG.d("TEST", "Open document " + fname + " " + open);
            LOG.d("TEST", "Open document css ", css);
            LOG.d("TEST", "Open document isImageScale ", isImageScale);
            LOG.d("MUPDF! >>> open [document]", open, ExtUtils.getFileName(fname));

            if (open == -1) {
                throw new RuntimeException("Document is corrupted");
            }

            // final int n = getPageCountWithException(open);
            return open;
        } finally {
            TempHolder.lock.unlock();
        }
    }

    /**
     * Remote book: opens MuPDF over the chunk-cached random-access stream
     * (no local file, no accelerator file — accelerators require a path).
     */
    private static long openRemoteFile(final int format, final String fname, final String pwd, final String css) {
        android.util.Log.i("REMOTE", "openRemoteFile enter, waiting lock");
        TempHolder.lock.lock();
        android.util.Log.i("REMOTE", "openRemoteFile locked");
        try {
            int allocatedMemory = AppState.get().allocatedMemorySize * 1024 * 1024;
            int isImageScale = AppState.get().enableImageScale ? 1 : 0;
            com.foobnix.remote.RemoteBookSession session;
            try {
                session = com.foobnix.remote.RemoteSessionFactory.obtain(fname);
            } catch (java.io.IOException e) {
                android.util.Log.i("REMOTE", "session obtain failed: " + e, e);
                LOG.e(e);
                throw new RuntimeException("Cannot open remote book: " + e.getMessage(), e);
            }
            com.foobnix.remote.RemoteSeekableStream stream = new com.foobnix.remote.RemoteSeekableStream(session);
            android.util.Log.i("REMOTE", "native openStream begin size=" + session.size);
            final long open = openStream(allocatedMemory, format, com.foobnix.remote.RemoteBook.magicFor(fname),
                    pwd, css,
                    BookCSS.get().documentStyle == BookCSS.STYLES_ONLY_USER ? 0 : 1, BookCSS.get().imageScale,
                    AppState.get().antiAliasLevel, isImageScale, stream);
            android.util.Log.i("REMOTE", "native openStream done handle=" + open);
            LOG.d("MUPDF! >>> openStream [document]", open, fname);
            if (open == -1) {
                throw new RuntimeException("Document is corrupted");
            }
            return open;
        } finally {
            TempHolder.lock.unlock();
        }
    }

    public static native String getFzVersion();

    private static native long open(int storememory, int format, String fname, String pwd, String css, int useDocStyle,
                                    float scale, int antialias, String accel, int isImageScale);

    private static native long openStream(int storememory, int format, String magic, String pwd, String css,
                                          int useDocStyle, float scale, int antialias, int isImageScale,
                                          com.artifex.mupdf.fitz.SeekableInputStream stream);

    private static native void free(long handle);

    private int getPageCountWithException(final long handle, int w, int h, int size) {
        final int count = getPageCountSafe(handle, w, h, Dips.spToPx(size));
//        if (count == 0) {
//            throw new RuntimeException("Document is corrupted");
//        }
        return count;
    }

    private int getPageCountSafe(long handle, int w, int h, int size) {

        LOG.d("getPageCountSafe w h size", w, h, size);

        // key on w and h separately: w+h collides for portrait/landscape
        // (1080+1920 == 1920+1080) and served a stale page count after a
        // device rotation for reflowable formats
        final int whKey = w * 31 + h;
        if (handle == cacheHandle && size == cacheSize && whKey == cacheWH) {
            LOG.d("getPageCount from cache", cacheCount);
            return cacheCount;
        }
        TempHolder.lock.lock();
        try {
            if (isRecycled()) {
                LOG.d("getPageCount", "getPageCount isRecycled");
                return 0;
            }
            final int count = getPageCount(handle, w, h, size);
            // commit the cache only after a successful native call: a throw
            // used to leave the PREVIOUS document's count cached under the
            // new key
            cacheHandle = handle;
            cacheSize = size;
            cacheWH = whKey;
            cacheCount = count;
            LOG.d("getPageCount put to  cache", cacheCount);
            return cacheCount;
        } catch (Exception e) {
            return -1;
        } finally {
            TempHolder.lock.unlock();
        }
    }

    private static native int getPageCount(long handle, int w, int h, int size);

    public String getPath() {
        return fname;
    }

    @Override public void setMeta(String key, String value) {
        TempHolder.lock.lock();
        try {
            LOG.d(this.getClass(), "setMetaData", key, value);
            setMetaData(documentHandle, key, value);
            markDirty();
        } finally {
            TempHolder.lock.unlock();
        }
    }

    @Override public BookType getBookType() {
        return bookType;
    }

    @Override public String documentToHtml() {
        StringBuilder out = new StringBuilder();
        int pages = getPageCount();
        for (int i = 0; i < pages; i++) {
            CodecPage pageCodec = getPage(i);
            String pageHTML = pageCodec.getPageHTML();
            out.append(pageHTML);
        }
        return out.toString();
    }

    @Override public Map<String, String> getFootNotes() {
        return footNotes;
    }

    public void setFootNotes(Map<String, String> footNotes) {
        this.footNotes = footNotes;
    }

    @Override public synchronized List<OutlineLink> getOutline() {
        if (isRecycled()) {
            LOG.d("getOutline doc isRecycled");
            return Collections.emptyList();
        }
        final MuPdfOutline ou = new MuPdfOutline();
        return ou.getOutline(this);
    }

    @Override public CodecPage getPageInner(final int pageNumber) {
        MuPdfPage createPage = MuPdfPage.createPage(this, pageNumber + 1);
        return createPage;
    }

    @Override public int getPageCount() {
        LOG.d("MuPdfDocument,getPageCount", getW(), getH(), BookCSS.get().fontSizeSp);
        return getPageCountWithException(documentHandle, getW(), getH(), BookCSS.get().fontSizeSp);
    }

    @Override public CodecPageInfo getUnifiedPageInfo() {
        if (isEpub) {
            LOG.d("MuPdfDocument, getUnifiedPageInfo");
            return new CodecPageInfo(getW(), getH());
        } else {
            return null;
        }
    }

    @Override
    public int getPageCount(int w, int h, int size) {
        this.w = w;
        this.h = h;
        int pageCountWithException = getPageCountWithException(documentHandle, w, h, size);
        LOG.d("MuPdfDocument,, getPageCount", w, h, size, "count", pageCountWithException);
        return pageCountWithException;
    }

    private static native int getPageCountProgressive(long handle, int w, int h, int size, int uptoPage);

    @Override
    public int getPageCountProgressive(int w, int h, int size, int uptoPage) {
        this.w = w;
        this.h = h;
        if (!isEpub) {
            // Chapter-wise layout only applies to reflowable documents.
            return getPageCount(w, h, size);
        }
        TempHolder.lock.lock();
        try {
            if (isRecycled()) {
                return 0;
            }
            // size is in sp here (same contract as getPageCount); the native
            // layout em must match the other call sites exactly, or the
            // accelerator is invalidated on every open.
            //
            // No full-count fallback here: callers chunk the work in small
            // uptoPage steps and must never trigger a long full-document
            // layout inside a single call.
            final int n = getPageCountProgressive(documentHandle, w, h, Dips.spToPx(size), Math.max(1, uptoPage));
            LOG.d("MuPdfDocument getPageCountProgressive", uptoPage, "->", n);
            return n;
        } finally {
            TempHolder.lock.unlock();
        }
    }

    public int getW() {
        return w > 0 ? w : Dips.screenWidth();
    }

    public int getH() {
        return h > 0 ? h : Dips.screenHeight();
    }

    @Override public CodecPageInfo getPageInfo(final int pageNumber) {
        final CodecPageInfo info = new CodecPageInfo();
        TempHolder.lock.lock();
        try {
            final int res = getPageInfo(documentHandle, pageNumber + 1, info);
            if (res == -1) {
                return null;
            } else {
                // Check rotation
                info.rotation = (360 + info.rotation) % 360;
                return info;
            }
        } finally {
            TempHolder.lock.unlock();
        }
    }

    @Override protected void freeDocument() {
        TempHolder.lock.lock();
        try {
            cacheHandle = -1;
            free(documentHandle);
        } finally {
            TempHolder.lock.unlock();
        }

        LOG.d("MUPDF! <<< recycle [document]", documentHandle, ExtUtils.getFileName(fname));
    }

    @Override public String getMeta(final String option) {
        TempHolder.lock.lock();
        try {

            if (true) {
                return getMeta(documentHandle, option);
            }

            final AtomicBoolean ready = new AtomicBoolean(false);
            final StringBuilder info = new StringBuilder();

            new Thread("@T extract meta") {
                @Override public void run() {

                    try {
                        LOG.d("getMeta", option);
                        String key = getMeta(documentHandle, option);
                        info.append(key);
                    } catch (Throwable e) {
                        LOG.e(e);
                    } finally {
                        ready.set(true);
                    }

                }

                ;
            }.start();

            while (!ready.get()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
            }

            return info.toString();
        } finally {
            TempHolder.lock.unlock();
        }
    }

    @Override public String getBookTitle() {
        return getMeta("info:Title");
    }

    @Override public String getBookAuthor() {
        return getMeta("info:Author");
    }

    private native void saveInternal(long handle, String path);

    /**
     * Session-dirty flag for the exit "save changes?" decision. Deliberately
     * Java-side only: the native pdf_has_unsaved_changes() also reports true
     * whenever any in-session object write happened (not tied to a user edit),
     * which made a freshly opened PDF prompt for unsaved changes on exit.
     * Set by the mutating wrappers (setMeta, deleteAnnotation and the page
     * annotation additions), cleared after a successful save.
     */
    volatile boolean isHasChanges = false;

    public void markDirty() {
        isHasChanges = true;
    }

    @Override public boolean hasChanges() {
        return isHasChanges;
    }

    @Override public void saveAnnotations(String path) {
        LOG.d("Save Annotations saveInternal 1");
        TempHolder.lock.lock();
        try {
            saveInternal(documentHandle, path);
            // only trust the save when the output really appeared: a native
            // save failing silently must not leave the book marked clean
            // (the edits would be lost without any prompt)
            if (new File(path).isFile() && new File(path).length() > 0) {
                isHasChanges = false;
            }
            LOG.d("Save Annotations saveInternal 2");
        } finally {
            TempHolder.lock.unlock();
        }
    }

    @Override public List<RectF> searchText(final int pageNuber, final String pattern) throws DocSearchNotSupported {
        throw new DocSearchNotSupported();
    }

    @Override public void deleteAnnotation(long pageHandle, int index) {
        TempHolder.lock.lock();
        try {
            deleteAnnotationInternal(documentHandle, pageHandle, index);
            markDirty();
        } finally {
            TempHolder.lock.unlock();
        }

    }

    private native void deleteAnnotationInternal(long docHandle, long pageHandle, int annot_index);

    public void setMediaAttachment(List<String> mediaAttachment) {
        this.mediaAttachment = mediaAttachment;
    }

    @Override public List<String> getMediaAttachments() {
        return mediaAttachment;
    }

}
