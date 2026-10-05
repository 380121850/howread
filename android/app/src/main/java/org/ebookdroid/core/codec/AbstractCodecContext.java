package org.ebookdroid.core.codec;

import android.graphics.Bitmap;

import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.CacheZipUtils.CacheDir;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.BookType;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.ebookdroid.droids.mupdf.codec.exceptions.MuPdfPasswordException;
import org.ebookdroid.droids.mupdf.codec.exceptions.MuPdfPasswordRequiredException;
import org.ebookdroid.ui.viewer.VerticalViewActivity;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractCodecContext implements CodecContext {

    private static final AtomicLong SEQ = new AtomicLong();

    private static Integer densityDPI;
    /** 最近一次因损坏被删除的转换缓存：防止“删了重转、重转仍坏”的死循环 */
    private static String lastDeletedConversionCache;

    private volatile long contextHandle;

    /**
     * Constructor.
     */
    protected AbstractCodecContext() {
        this(SEQ.incrementAndGet());
    }

    public abstract CodecDocument openDocumentInner(String fileName, String password);

    public CodecDocument openDocumentInnerCanceled(String fileName, String password) {
        long t = System.currentTimeMillis();
        CodecDocument openDocument = openDocumentInner(fileName, password);
        LOG.d("openDocumentInner-time", (float) (System.currentTimeMillis() - t) / 1000, fileName);
        LOG.d("removeTempFiles1", TempHolder.get().loadingCancelled.get());
//        if (TempHolder.get().loadingCancelled.get()) {
//            removeTempFiles();
//            return null;
//        }


        return openDocument;
    }

    public void removeTempFilesIfCancel() {
        LOG.d("removeTempFiles2", "remove temp files", TempHolder.get().loadingCancelled.get());
        if (TempHolder.get().loadingCancelled.get()) {
            //recycle();
            try {
                Thread.sleep(1000);
                CacheZipUtils.removeFiles(CacheZipUtils.CACHE_BOOK_DIR.listFiles());
                CacheZipUtils.removeFiles(CacheZipUtils.CACHE_TEMP.listFiles());
            }catch (Exception e){
                LOG.w(e);
            }
        }
    }

    public static long getFileNameSalt(String path) {
        long hashCode = 0;
        try {
            File file = new File(path);
            hashCode = file.length() + file.lastModified();
            LOG.d("getFileNameSalt", path, file.length(), file.lastModified());
        } catch (Exception e) {
            LOG.e(e);
        }
        return hashCode;
    }

    @Override
    public CodecDocument openDocument(String fileNameOriginal, String password) {
        // Guarantee the native MuPDF library is loaded before any document
        // is opened (reader, cover thumbnail, library scan all funnel here).
        // Usually already preloaded on a background thread from Application.
        AppsConfig.ensureMuPdfLoaded();
        LOG.d("Open-Document", fileNameOriginal);
        // TempHolder.loadingCancelled = false;
        if (com.foobnix.remote.RemoteBook.isRemotePath(fileNameOriginal)) {
            // Remote book: the chunk-cache stream is assembled inside
            // MuPdfDocument; skip every local-file step (salt, unzip, cache
            // files) — there is no local file to touch.
            LOG.remote("codec openDocument begin " + fileNameOriginal);
            try {
                return openDocumentInnerCanceled(fileNameOriginal, password);
            } catch (Throwable e) {
                LOG.remote("remote open failed: " + e, e);
                throw e;
            }
        }
        if (ExtUtils.isZip(fileNameOriginal)) {
            LOG.d("Open-Document ZIP", fileNameOriginal);
            return openDocumentInnerCanceled(fileNameOriginal, password);
        }

        LOG.d("Open-Document 2 LANG:", AppSP.get().hypenLang, fileNameOriginal);

        File cacheFileName = getCacheFileName(fileNameOriginal + getFileNameSalt(fileNameOriginal));
        if (!BookType.ODT.is(fileNameOriginal)) {
            // Keep the most recent conversion products (incl. the current
            // book) instead of wiping all other books on every open.
            CacheZipUtils.trimFiles(CacheZipUtils.CACHE_BOOK_DIR.listFiles(), cacheFileName, 8);
            CacheZipUtils.removeDirs(CacheZipUtils.CACHE_BOOK_DIR.listFiles(), new File(cacheFileName + "-source"));
        }

        if (cacheFileName != null && cacheFileName.isFile()) {
            LOG.d("Open-Document from cache", fileNameOriginal);
            LOG.bench("codec-cache hit " + fileNameOriginal);
            CodecDocument cachedDoc = null;
            boolean openFailed = false;
            try {
                cachedDoc = openDocumentInnerCanceled(fileNameOriginal, password);
            } catch (final MuPdfPasswordException pe) {
                throw new MuPdfPasswordRequiredException();
            } catch (final Throwable t) {
                // 直接判定一：缓存文件连打开都失败（现场 warmer 的
                // "PDF file not found or corrupted"）= 缓存损坏
                LOG.bench("codec-cache CORRUPT (open failed) -> delete & reconvert: " + fileNameOriginal);
                LOG.e(t);
                try {
                    cacheFileName.delete();
                } catch (final Throwable ignored) {
                }
                openFailed = true;
            }
            if (!openFailed && cachedDoc != null) {
                // 直接判定二：文字格式书的转换缓存可打开但文字提取为空
                // = 缓存内容损坏（渲染可恢复、文字层残缺）。
                // 12S 现场定位（08:56 日志）：此前探测里 cachedDoc.getPageCount()
                // 会触发整本书全量排版（该书 5.0s/14947 页），是"打开变慢"的主因，
                // 且把全书按整屏高度排一遍、随后阅读器又按视口高度重排。探测改为
                // 只看前 3 页（首章排版毫秒级），结果按缓存文件签名记账，之后每次
                // 打开零探测成本。
                if (isProbeSkipped(cacheFileName) || cacheFileName.getPath().equals(lastDeletedConversionCache)) {
                    return cachedDoc;
                }
                if (probeTextReadable(cachedDoc)) {
                    markProbeResult(cacheFileName, true);
                    return cachedDoc;
                }
                if (isConvertKept(fileNameOriginal)) {
                    // 同一本书已删过一次缓存、重转仍是文字空：该设备的转换本就
                    // 产不出文字层，删了还会再犯。保留缓存（渲染/阅读不受影响，
                    // 长按走 refetch 重试与整页文字兜底），并不再重复探测。
                    markProbeResult(cacheFileName, false);
                    return cachedDoc;
                }
                LOG.bench("codec-cache CORRUPT (text probe empty) -> delete & reconvert: " + fileNameOriginal);
                try {
                    cacheFileName.delete();
                    lastDeletedConversionCache = cacheFileName.getPath();
                    markConvertKept(fileNameOriginal);
                } catch (final Throwable ignored) {
                }
                // 落到底部全新转换路径当场重建
            }
        }

        CacheZipUtils.cacheLock2.lock();
        CacheZipUtils.createAllCacheDirs();
        try {
            String fileName = CacheZipUtils.extracIfNeed(fileNameOriginal, CacheDir.ZipApp).unZipPath;
            LOG.d("Open-Document extract", fileName);
            if (!ExtUtils.isValidFile(fileName)) {
                LOG.d( "isValidFile",fileName);
                return null;
            }
            try {
                final long benchConvertT0 = android.os.SystemClock.elapsedRealtime();
                final CodecDocument benchDoc = openDocumentInnerCanceled(fileName, password);
                LOG.bench("codec-convert " + (android.os.SystemClock.elapsedRealtime() - benchConvertT0) + "ms " + fileNameOriginal);
                return benchDoc;
            } catch (MuPdfPasswordException e) {
                throw new MuPdfPasswordRequiredException();
            } catch (Throwable e) {
                LOG.w(e);
                return null;
            }
        } finally {
            CacheZipUtils.cacheLock2.unlock();
        }

    }

    /**
     * 转换缓存损坏的直接判定：文字格式书（mobi/epub 等，有转换缓存的）前 3 页
     * 必须能提取到文字；全空 = 缓存损坏（渲染可恢复但文字层残缺）。任何一页
     * 有词即为健康；图片页 legitimately 为空不误伤（前 3 页全空才判坏）。
     * 只探测前 3 页：深页探测会强制排版其前所有章节（万页级书按秒计），
     * doc.getPageCount() 更是一次全文档排版，都不允许进打开关键路径。
     */
    private static boolean probeTextReadable(final CodecDocument doc) {
        try {
            for (int i = 0; i < 3; i++) {
                final CodecPage p;
                try {
                    p = doc.getOwnedPage(i);
                } catch (final Throwable t) {
                    continue;
                }
                if (p == null) {
                    continue;
                }
                try {
                    final TextWord[][] t = p.getText();
                    if (t != null && t.length > 0) {
                        int words = 0;
                        for (final TextWord[] line : t) {
                            if (line != null) {
                                words += line.length;
                            }
                        }
                        if (words > 0) {
                            LOG.bench("codec-cache probe ok (page " + i + ")");
                            return true;
                        }
                    }
                } finally {
                    p.recycle();
                }
            }
        } catch (final Throwable t) {
            LOG.w(t);
        }
        return false;
    }

    private static final String PREF_PROBE = "codec_probe";

    private static String probeSignature(final File f) {
        return f.getPath() + ":" + f.length() + ":" + f.lastModified();
    }

    /** 该缓存文件体检过（通过或判保留）——之后打开直接跳过探测，零成本 */
    private static boolean isProbeSkipped(final File cache) {
        try {
            return com.foobnix.LibreraApp.context.getSharedPreferences(PREF_PROBE, 0)
                    .contains("ok:" + probeSignature(cache));
        } catch (final Throwable t) {
            return false;
        }
    }

    private static void markProbeResult(final File cache, final boolean readable) {
        try {
            com.foobnix.LibreraApp.context.getSharedPreferences(PREF_PROBE, 0)
                    .edit().putBoolean("ok:" + probeSignature(cache), true).apply();
            LOG.bench("codec-cache probe marked readable=" + readable);
        } catch (final Throwable t) {
            LOG.w(t);
        }
    }

    /** 这本书已经历过"删缓存重转仍文字空"——保留缓存，不再删不再探测 */
    private static boolean isConvertKept(final String book) {
        try {
            return com.foobnix.LibreraApp.context.getSharedPreferences(PREF_PROBE, 0)
                    .contains("kept:" + book);
        } catch (final Throwable t) {
            return false;
        }
    }

    private static void markConvertKept(final String book) {
        try {
            com.foobnix.LibreraApp.context.getSharedPreferences(PREF_PROBE, 0)
                    .edit().putBoolean("kept:" + book, true).apply();
        } catch (final Throwable t) {
            LOG.w(t);
        }
    }

    public File getCacheFileName(String fileNameOriginal) {
        return null;
    }

    /**
     * Constructor.
     *
     * @param contextHandle contect handler
     */
    protected AbstractCodecContext(final long contextHandle) {
        this.contextHandle = contextHandle;
    }

    @Override
    protected final void finalize() throws Throwable {
        // recycle();
        super.finalize();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.codec.CodecContext#recycle()
     */
    @Override
    public final void recycle() {
        if (!isRecycled()) {
            // Same contract as AbstractCodecDocument.recycle(): the native
            // free and the isRecycled() flag update are one serialized step,
            // so concurrent guarded native calls can never observe a live
            // flag with an already-freed context.
            TempHolder.lock.lock();
            try {
                if (!isRecycled()) {
                    freeContext();
                    contextHandle = 0;
                }
            } finally {
                TempHolder.lock.unlock();
            }
        }
    }

    protected void freeContext() {
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.codec.CodecContext#isRecycled()
     */
    @Override
    public final boolean isRecycled() {
        return contextHandle == 0;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.codec.CodecContext#getContextHandle()
     */
    @Override
    public final long getContextHandle() {
        return contextHandle;
    }

    @Override
    public boolean isPageSizeCacheable() {
        return true;
    }

    @Override
    public boolean isParallelPageAccessAvailable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.codec.CodecContext#getBitmapConfig()
     */
    @Override
    public Bitmap.Config getBitmapConfig() {
        return AppsConfig.CURRENT_BITMAP_ARGB;
    }

    public static int getSizeInPixels(final float pdfHeight, float dpi) {
        if (dpi == 0) {
            // Archos fix
            dpi = getDensityDPI();
        }
        if (dpi < 72) { // Density lover then 72 is to small
            dpi = 72; // Set default density to 72
        }
        return (int) (pdfHeight * dpi / 72);
    }

    private static int getDensityDPI() {
        if (densityDPI == null) {
            try {
                final Field f = VerticalViewActivity.DM.getClass().getDeclaredField("densityDpi");
                densityDPI = ((Integer) f.get(VerticalViewActivity.DM));
            } catch (final Throwable ex) {
                densityDPI = Integer.valueOf(120);
            }
        }
        return densityDPI.intValue();
    }
}
