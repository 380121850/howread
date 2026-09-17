package com.foobnix.pdf.search.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.PointF;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.Intents;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Safe;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.CopyAsyncTask;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.PageUrl;
import com.foobnix.pdf.info.model.AnnotationType;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.search.activity.msg.InvalidateMessage;
import com.foobnix.pdf.search.activity.msg.MessageAutoFit;
import com.foobnix.pdf.search.activity.msg.MessageCenterHorizontally;
import com.foobnix.pdf.search.activity.msg.MessagePageXY;
import com.foobnix.pdf.search.activity.msg.MovePageAction;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.sys.TempHolder;
import com.foobnix.tts.TTSEngine;
import com.foobnix.tts.TTSNotification;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.core.PageSearcher;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.core.codec.OutlineLink;
import org.ebookdroid.core.codec.PageLink;
import org.ebookdroid.droids.mupdf.codec.MuPdfLinks;
import org.ebookdroid.droids.mupdf.codec.MuPdfPage;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public abstract class HorizontalModeController extends DocumentController {

    int currentPage;
    String bookPath;
    CodecDocument codeDocument;
    int imageWidth, imageHeight;
    private int pagesCount;
    /** Remote text book whose full layout is deferred to a background task. */
    private boolean pendingRemoteLayout;
    private float pendingRestorePercent = -1f;
    /** True while the deferred layout task is inside the native layout call
     * (which holds the global native lock): closing the codec then would
     * block the UI thread until the layout finishes. */
    private volatile boolean remoteLayoutRunning;
    /** epub fast path: the deferred layout runs in progressive chunks so the
     * first screen appears once the first chapters are cached; fb2/mobi/txt
     * are parsed whole by the engine and keep the one-shot background layout. */
    private boolean remoteProgressive;
    /** Page count stored by the previous full layout (DB): landing estimate
     * while the progressive layout is still catching up. */
    private Integer pendingRestorePages;
    /** Set once the landing page has been applied to the pager. */
    private boolean remoteLanded;
    /** The user turned pages while the landing was still pending — never jump. */
    private boolean remoteUserTookOver;
    private CopyAsyncTask searchTask;
    private boolean isTextFormat = false;
    private SharedPreferences matrixSP;
    private volatile boolean isClosed = false;

    @Override public boolean hasPDFAnnotations() {
        return false;
    }

    public HorizontalModeController(Activity activity, int w, int h) {
        super(activity);

        isTextFormat = ExtUtils.isTextFomat(activity.getIntent());
        udpateImageSize(isTextFormat, w, h);

        matrixSP = activity.getSharedPreferences("matrix", Context.MODE_PRIVATE);

        PageImageState.get()
                      .cleanSelectedWords();
        PageImageState.get().pagesText.clear();

        AppSP.get().isSmartReflow = false;

        if (isTextFormat) {
            AppSP.get().isCrop = false;
            AppSP.get().isCut = false;
            AppSP.get().isLocked = true;
        }

        bookPath = Apps.getBookPathFromActivity(activity);
        setCurrentBook(new File(bookPath));

        AppBook bs = SettingsManager.getBookSettings(bookPath);

        if (bs != null) {
            LOG.d("isRTL", "AppBook.rtl", bs.rtl);
            AppSP.get().isRTL = bs.rtl;
            AppSP.get().isCut = bs.sp;
            AppSP.get().isCrop = bs.cp;
            AppSP.get().isDouble = bs.dp;
            AppSP.get().isDoubleCoverAlone = bs.dc;
            AppSP.get().isLocked = bs.getLock(isTextFormat, AppState.get().lockBooksByDefault);
            TempHolder.get().pageDelta = bs.d;

            if (AppState.get().isCropPDF && !isTextFormat) {
                AppSP.get().isCrop = true;
            }
        }

        if (AppState.get().alwaysTwoPages) {
            AppSP.get().isDouble = true;
            AppSP.get().isCut = false;
            AppSP.get().isDoubleCoverAlone = false;
            AppSP.get().isSmartReflow = false;
        }

        FileMetaCore.checkOrCreateMetaInfo(activity);
        BookCSS.get()
               .detectLang(bookPath);

        String pasw = activity.getIntent()
                              .getStringExtra(EXTRA_PASSWORD);
        pasw = TxtUtils.nullToEmpty(pasw);

        if (AppSP.get().isDouble && isTextFormat) {
            imageWidth = Dips.screenWidth() / 2;
        }

        final boolean remoteText = com.foobnix.remote.RemoteBook.isRemotePath(bookPath) && isTextFormat;
        final boolean deferRemote = remoteText && deferRemoteLayout();
        android.util.Log.i("REMOTE", "hcontroller remoteText=" + remoteText
                + " isTextFormat=" + isTextFormat + " deferRemote=" + deferRemote
                + " book=" + bookPath);
        codeDocument = ImageExtractor.getNewCodecContext(bookPath, pasw, imageWidth, imageHeight, !deferRemote);
        if (deferRemote && codeDocument != null) {
            // Big remote text book: skip the blocking full-document layout
            // here — the reader shell shows immediately and the layout runs
            // in the background (see HorizontalViewActivity.startRemoteTextLayout),
            // so the first screen appears fast and the rest of the book is
            // laid out / cached afterwards.
            pendingRemoteLayout = true;
            pagesCount = 1;
            // epub is a zip container the engine walks chapter by chapter:
            // the progressive chunked layout can show the first screen after
            // the first few MB of cache. fb2/mobi/txt are single-file parses
            // (the engine reads the whole file at open) — the one-shot
            // background layout is their ceiling without engine changes.
            String rext = com.foobnix.remote.RemoteBook.getExt(bookPath);
            remoteProgressive = "epub".equals(rext) || "epub2".equals(rext);
        } else if (codeDocument != null) {
            pagesCount = codeDocument.getPageCount(imageWidth, imageHeight, BookCSS.get().fontSizeSp);
        } else {
            pagesCount = 0;
        }

        if (pagesCount <= 0) {
            CacheZipUtils.emptyAllCacheDirs();
            throw new IllegalArgumentException("Pages count: "+pagesCount);
        }

        try {
            FileMeta meta = AppDB.get()
                                 .load(bookPath);
            if (meta != null) {
                if (pendingRemoteLayout) {
                    // keep the last known real page count in the DB (writing
                    // the provisional 1 would corrupt the landing estimate
                    // and the shelf page column) and remember it as the
                    // landing estimate for the deferred layout
                    pendingRestorePages = meta.getPages();
                } else {
                    meta.setPages(pagesCount);
                    AppDB.get()
                         .update(meta);
                    LOG.d("update openDocument.getPageCount()", bookPath, pagesCount);
                }
            }

        } catch (Exception e) {
            LOG.e(e);
        }

        AppDB.get()
             .addRecent(bookPath);

        float percent = Intents.getFloatAndClear(activity.getIntent(), DocumentController.EXTRA_PERCENT);

        if (pendingRemoteLayout) {
            // the real page count is not known yet: remember where to land
            // and start at page 0; applyRemoteTextLayout() jumps afterwards
            pendingRestorePercent = percent > 0f ? percent : (bs != null ? bs.p : 0f);
            currentPage = 0;
        } else if (percent > 0.0f) {
            currentPage = Math.round(pagesCount * percent) - 1;
        } else if (pagesCount > 0) {
            currentPage = bs.getCurrentPage(getPageCount()).viewIndex;
        }
        if (AppState.get().isAlwaysOpenOnPage1) {
            currentPage = 0;
        }

        if (false) {
            PageImageState.get().needAutoFit = true;
        } else {
            if (TxtUtils.isNotEmpty(bookPath) && !ExtUtils.isTextFomat(bookPath)) {
                String string = matrixSP.getString(bookPath.hashCode() + "", "");
                LOG.d("MATRIX", "READ STR", string);
                if (TxtUtils.isEmpty(string) || AppSP.get().isCut || AppSP.get().isCrop) {
                    PageImageState.get().needAutoFit = true;
                } else {
                    PageImageState.get().needAutoFit = false;
                }
                Matrix matrix = PageImageState.fromString(string);
                PageImageState.get()
                              .getMatrix()
                              .set(matrix);

                LOG.d("MATRIX", "READ", bookPath.hashCode() + "", PageImageState.get()
                                                                                .getMatrixAsString());

            }
        }

    }

    public static String getTempTitle(Activity a) {
        try {
            return getTitle(Apps.getBookPathFromActivity(a));
        } catch (Exception e) {
            LOG.e(e);
            return "";
        }
    }

    public static String getTitle(String path) {
        if (ExtUtils.hasTitle(path)) {
            return AppDB.get()
                        .getOrCreate(path)
                        .getTitle();
        }
        return new File(path).getName();
    }

    @Override public void updateRendering() {

    }

    @Override public void onScrollYPercent(float value) {
        int page2 = Math.round(value * getPageCount());
        onGoToPage(page2);
    }

    public void udpateImageSize(boolean isTextFormat, int w, int h) {
        LOG.d("udpateImageSize", w, h, isTextFormat);
        imageWidth = isTextFormat ? w :
                (int) (Math.min(Dips.screenWidth(), Dips.screenHeight()) * AppState.get().pageQuality);
        imageHeight = isTextFormat ? h :
                (int) (Math.max(Dips.screenWidth(), Dips.screenHeight()) * AppState.get().pageQuality);
    }

    @Override public int getBookHeight() {
        return imageHeight;
    }

    @Override public int getBookWidth() {
        return imageWidth;
    }

    @Override public void onLinkHistory() {
        if (!getLinkHistory().isEmpty()) {
            final int last = getLinkHistory().removeLast();
            onGoToPage(last);
        }
    }

    @Override public float getOffsetY() {
        return getCurentPageFirst1();
    }

    @Override public void cleanImageMatrix() {
        try {
            PageImageState.get()
                          .getMatrix()
                          .reset();
            matrixSP.edit()
                    .remove("" + bookPath.hashCode())
                    .commit();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    @Override public void saveAnnotationsToFile() {
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int page) {
        currentPage = page;
    }

    @Override public int getCurentPageFirst1() {
        return currentPage + 1;
    }

    public int getOpenPageNumber() {
        return currentPage;
    }

    @Override public PageUrl getPageUrl(int page) {
        PageUrl build = PageUrl.build(getBookPath(), page, imageWidth, imageHeight);
        build.setDoText(true);
        return build;
    }

    public abstract void onGoToPageImpl(int page);

    public abstract void notifyAdapterDataChanged();

    public abstract void showInterstialAndClose();

    @Override public void onGoToPage(int page) {
        if (page <= getPageCount()) {
            onGoToPageImpl(page - 1);
        }
    }

    @Override public void onSrollLeft() {
        throw new RuntimeException("Not Implemented");
    }

    public TextWord[][] getPageText(int number) {
        LOG.d("Get page text for page", number);
        try {
            // owned page: recycled here, so it must not be the shared cache
            // instance other threads may still be rendering
            CodecPage page = codeDocument.getOwnedPage(number);
            if (page != null && !page.isRecycled()) {
                TextWord[][] text = page.getText();
                page.recycle();
                return text;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return null;
    }

    @Override public String[] getPageParagraphs(int page) {
        try {
            if (codeDocument == null || codeDocument.isRecycled()) {
                return null;
            }
            // owned page: recycled here, so it must not be the shared cache
            // instance other threads may still be rendering (same pattern as
            // getPageText above) — a background AI thread holding the shared
            // page across a document recycle crashed natively
            CodecPage cp = codeDocument.getOwnedPage(page);
            if (cp == null) {
                return null;
            }
            try {
                if (cp.isRecycled() || codeDocument.isRecycled()) {
                    return null;
                }
                if (cp instanceof MuPdfPage) {
                    // Use the page HTML (working getPageAsHtml native) and split it
                    // into paragraphs. MuPdfPage.text() has no native impl in the
                    // prebuilt libMuPDF.so, so it throws UnsatisfiedLinkError.
                    return com.foobnix.ai.AiTranslator.htmlToParagraphs(cp.getPageHTML());
                }
            } finally {
                cp.recycle();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return null;
    }

    @Override public synchronized String getTextForPage(int page) {
        try {
            // owned page: recycled below, so it must not be the shared cache
            // instance other threads may still be rendering
            CodecPage codecPage = codeDocument.getOwnedPage(page);
            if (codecPage != null && !codecPage.isRecycled()) {
                String pageHTML = codecPage.getPageHTML();
                codecPage.recycle();
                pageHTML = TxtUtils.replaceHTMLforTTS(pageHTML);
                pageHTML = pageHTML.replace(TxtUtils.TTS_PAUSE, " ");
                pageHTML = pageHTML.replace(TxtUtils.NON_BREAKE_SPACE, " ");
                return pageHTML;

            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return "";
    }

    @Override public String getPageHtml() {
        try {
            CodecPage codecPage = codeDocument.getPage(getCurentPageFirst1() - 1);
            if (!codecPage.isRecycled()) {
                String pageHTML = codecPage.getPageHTML();
                pageHTML = TxtUtils.replaceHTMLforTTS(pageHTML);
                pageHTML = pageHTML.replace(TxtUtils.TTS_PAUSE, TxtUtils.TTS_PAUSE_VIEW);

                return pageHTML;

            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return "";
    }

    @Override public List<PageLink> getLinksForPage(int page) {
        try {
            return codeDocument.getPage(page)
                               .getPageLinks();
        } catch (Exception e) {
            LOG.e(e);
            return Collections.emptyList();
        }
    }

    @Override public void onSrollRight() {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void onNextPage(boolean animate) {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void onPrevPage(boolean animate) {
        throw new RuntimeException("Not Implemented");
    }

    @Override public void onNextScreen(boolean animate) {
        // TODO Auto-generated method stub

    }

    @Override public boolean isCropCurrentBook() {
        return false;
    }

    @Override public void onPrevScreen(boolean animate) {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void onZoomInc() {
        EventBus.getDefault()
                .post(new MovePageAction(MovePageAction.ZOOM_PLUS, getCurentPage()));
    }

    @Override public void onZoomDec() {
        EventBus.getDefault()
                .post(new MovePageAction(MovePageAction.ZOOM_MINUS, getCurentPage()));
    }

    @Override public void onZoomInOut(int x, int y) {

    }

    @Override public String getFootNote(String text, String chapter) {
        try {
            return TxtUtils.getFooterNote(text, chapter, codeDocument.getFootNotes());
        } catch (Exception e) {
            LOG.e(e);
            return "";
        }
    }

    @Override public List<String> getMediaAttachments() {
        try {
            return codeDocument.getMediaAttachments();
        } catch (Exception e) {
            LOG.e(e);
            return Collections.emptyList();
        }
    }

    @Override public void onScrollDown() {
    }

    @Override public void onScrollUp() {
    }

    @Override public void onCloseActivityFinal(final Runnable run) {
        stopTimer();
        TTSEngine.get()
                 .stop();
        TTSNotification.hideNotification();

        Safe.run(new Runnable() {

            @Override public void run() {
                isClosed = true;
                if (codeDocument != null) {
                    codeDocument.recycle();
                    codeDocument = null;
                }
                try {
                    if (!ExtUtils.isTextFomat(bookPath)) {
                        matrixSP.edit()
                                .putString(bookPath.hashCode() + "", PageImageState.get()
                                                                                   .getMatrixAsString())
                                .commit();
                        LOG.d("MATRIX", "SAVE", bookPath.hashCode() + "", PageImageState.get()
                                                                                        .getMatrixAsString());
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }

                //saveCurrentPage();
                LOG.d("_PAGE", "SAVE", getCurentPage());
                final Intent i = new Intent();
                i.putExtra("page", getCurentPage());
                activity.setResult(Activity.RESULT_OK, i);
                activity.finish();

                ImageExtractor.clearCodeDocument();
                if (run != null) {
                    run.run();
                }
            }
        });
    }

    @Override public void onCloseActivityAdnShowInterstial() {
        showInterstialAndClose();

    }

    @Override public void onNightMode() {
    }

    @Override public void onCrop() {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void onFullScreen() {
        throw new RuntimeException("Not Implemented");

    }

    @Override public int getCurentPage() {
        LOG.d("_PAGE", "getCurentPage", currentPage);
        return currentPage;
    }

    @Override public int getPageCount() {
        return PageUrl.realToFake(pagesCount);
    }

    public boolean isPendingRemoteLayout() {
        return pendingRemoteLayout;
    }

    /** Runs the deferred full layout (background thread). Returns the real
     * page count, or the provisional count when the layout failed. */
    public int runRemoteTextLayout() {
        pendingRemoteLayout = false;
        remoteLayoutRunning = true;
        try {
            return codeDocument.getPageCount(imageWidth, imageHeight, BookCSS.get().fontSizeSp);
        } catch (Throwable t) {
            LOG.e(t);
            return pagesCount;
        } finally {
            remoteLayoutRunning = false;
        }
    }

    public boolean isRemoteLayoutRunning() {
        return remoteLayoutRunning;
    }

    public boolean isRemoteProgressive() {
        return remoteProgressive;
    }

    /** True once the pager sits on the page to show (saved position,
     * page 0, or the user's own page) — no further jumps needed. */
    public boolean isRemoteLanded() {
        return remoteLanded;
    }

    /** Page count known so far (0 while the deferred layout has not produced
     * its first result). */
    public int getPagesCount() {
        return pagesCount;
    }

    /** Saved reading position (0..1) the deferred layout must land on. */
    public float getPendingRestorePercent() {
        return pendingRestorePercent;
    }

    /** Marks the deferred progressive layout as started (background thread). */
    public void beginRemoteLayout() {
        pendingRemoteLayout = false;
    }

    /**
     * One progressive layout chunk (background thread): lays out chapters
     * only until {@code uptoPage} is reachable and returns the cumulative
     * page count so far (less than requested = book end reached). Falls back
     * to the full one-shot layout for non-progressive books. Returns 0 when
     * the codec is already closed.
     */
    public int runRemoteLayoutChunk(int uptoPage) {
        if (codeDocument == null || isClosed) {
            return 0;
        }
        remoteLayoutRunning = true;
        try {
            if (remoteProgressive) {
                return codeDocument.getPageCountProgressive(imageWidth, imageHeight,
                        BookCSS.get().fontSizeSp, Math.max(1, uptoPage));
            }
            return codeDocument.getPageCount(imageWidth, imageHeight, BookCSS.get().fontSizeSp);
        } catch (Throwable t) {
            LOG.e(t);
            return pagesCount > 0 ? pagesCount : 0;
        } finally {
            remoteLayoutRunning = false;
        }
    }

    /**
     * Applies one progressive layout result on the UI thread: the page count
     * grows monotonically; the first result covering the landing page (or the
     * last one) lands the pager on the saved position. Later results only
     * grow the count, so pages the user already turned are preserved.
     *
     * @return true when the landing happened on this call — the caller shows
     *         content (removes the overlay) and jumps the pager.
     */
    public boolean applyRemoteLayoutResult(int count, boolean last) {
        if (count <= 0) {
            return false;
        }
        pagesCount = count;
        boolean justLanded = false;
        if (!remoteLanded) {
            if (currentPage > 0) {
                // the user turned pages while the saved position was
                // still being located — keep their page, drop the jump
                remoteLanded = true;
                remoteUserTookOver = true;
            } else if (pendingRestorePercent > 0f) {
                int est = pendingRestorePages != null ? pendingRestorePages : 0;
                // early landing is only allowed with a page count from a
                // previous real layout (est>1); a stale count lands on
                // the wrong page, so wait for the final count instead
                int base = (last || est <= 1) ? count : est;
                int target = Math.max(0, Math.min(Math.round(base * pendingRestorePercent) - 1, count - 1));
                if (last || target < count) {
                    android.util.Log.i("REMOTE", "remoteLand est=" + est + " base=" + base
                            + " pct=" + pendingRestorePercent + " -> page " + target
                            + " of " + count + (last ? " (last)" : ""));
                    currentPage = target;
                    remoteLanded = true;
                    justLanded = true;
                }
                // else: the landing chapter is not laid out yet — keep
                // waiting for the next chunk
            } else {
                // fresh open: page 0 is the landing, content can show now
                remoteLanded = true;
                justLanded = true;
            }
        } else if (last && pendingRestorePercent > 0f && !remoteUserTookOver) {
            // re-anchor: the stored page count the early landing used may
            // be stale (font size changed etc.) — snap to the position
            // derived from the real final count
            int target = Math.max(0, Math.min(Math.round(count * pendingRestorePercent) - 1, count - 1));
            if (Math.abs(target - currentPage) > 2) {
                android.util.Log.i("REMOTE", "remoteLand re-anchor " + currentPage + " -> " + target
                        + " of " + count + " (est was " + pendingRestorePages + ")");
                currentPage = target;
                justLanded = true;
            }
        }
        try {
            FileMeta meta = AppDB.get().load(bookPath);
            if (meta != null) {
                meta.setPages(pagesCount);
                AppDB.get().update(meta);
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
        return justLanded;
    }

    /** Applies the background layout result: real page count, persisted meta
     * and the landing page. Call on the UI thread. */
    public void applyRemoteTextLayout(int count) {
        if (count > 0) {
            pagesCount = count;
        }
        try {
            FileMeta meta = AppDB.get().load(bookPath);
            if (meta != null) {
                meta.setPages(pagesCount);
                AppDB.get().update(meta);
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
        if (pendingRestorePercent > 0f) {
            currentPage = Math.round(pagesCount * pendingRestorePercent) - 1;
        }
        if (currentPage < 0) {
            currentPage = 0;
        }
        if (currentPage >= pagesCount) {
            currentPage = pagesCount - 1;
        }
    }

    /** Remote text books defer the full layout only when the book is big
     * enough to make the first screen wait (small ones lay out instantly). */
    private boolean deferRemoteLayout() {
        try {
            final FileMeta meta = AppDB.get().load(bookPath);
            final Long size = meta == null ? null : meta.getSize();
            android.util.Log.i("REMOTE", "deferRemoteLayout size=" + size);
            return size == null || size >= 10L * 1024 * 1024;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override public void onScrollY(int value) {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void onAutoScroll() {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void clearSelectedText() {
        EventBus.getDefault()
                .post(new MessagePageXY(MessagePageXY.TYPE_HIDE));
        AppState.get().selectedText = null;
        PageImageState.get()
                      .cleanSelectedWords();
        EventBus.getDefault()
                .post(new InvalidateMessage());
    }

    @Override public void saveChanges(List<PointF> points, int color) {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void deleteAnnotation(long pageHander, int page, int index) {
        throw new RuntimeException("Not Implemented");

    }

    @Override public void underlineText(int color, float width, AnnotationType type) {
        // TODO Auto-generated method stub

    }

    @Override public void addTextNote(String text, int color) {
        // TODO Auto-generated method stub

    }

    @Override
    public void getOutline(final com.foobnix.android.utils.ResultResponse<List<OutlineLinkWrapper>> outlineResonse,
                           boolean forse) {

        if (Apps.isDestroyedActivity(activity)) {
            return;
        }

        if (codeDocument == null) {
            outlineResonse.onResultRecive(Collections.emptyList());
            return;
        }

        if (outline == null) {
            outline = (ArrayList<OutlineLinkWrapper>) CacheZipUtils.loadJavaCache(getCurrentBook());
            if (outline != null) {
                outlineResonse.onResultRecive(outline);
                return;
            }

            outline = new ArrayList<>();
            AppsConfig.executorServiceSingle.execute(() -> {

                try {
                    for (OutlineLink ol : codeDocument.getOutline()) {
                        if (TempHolder.get().loadingCancelled.get()) {
                            return;
                        }

                        if (Apps.isDestroyedActivity(activity)) {
                            return;
                        }

                        if (codeDocument.isRecycled()) {
                            return;
                        }
                        if (TxtUtils.isNotEmpty(ol.getTitle())) {
                            if (ol.getLink() != null && ol.getLink()
                                                          .startsWith("#") && !ol.getLink()
                                                                                 .startsWith("#0")) {
                                outline.add(
                                        new OutlineLinkWrapper(ol.getTitle(), ol.getLink(), ol.getLevel(), ol.linkUri));
                            } else {
                                int page = MuPdfLinks.getLinkPageWrapper(ol.docHandle, ol.linkUri) + 1;
                                outline.add(
                                        new OutlineLinkWrapper(ol.getTitle(), "#" + page, ol.getLevel(), ol.linkUri));
                            }
                        }
                    }

                    CacheZipUtils.savaJavaCache(outline, getCurrentBook());

                    // setOutline(outline);
                    if (outlineResonse != null) {
                        getActivity().runOnUiThread(new Runnable() {

                            @Override public void run() {
                                outlineResonse.onResultRecive(outline);
                            }
                        });

                    }
                } catch (Exception e) {
                    LOG.e(e);
                }

            });

        } else {
            outlineResonse.onResultRecive(outline);
        }

    }

    @Override public void recyclePage(int number) {
        // Deliberately a no-op: fetching a page by number just to recycle it
        // would either free the SHARED cache instance out from under other
        // threads (crash) or burn a create/free round trip. Text-extraction
        // paths now recycle their own owned pages (see getOwnedPage), and the
        // page cache is bounded by the slot + CodecPageHolder LRU.
    }

    @Override public void doSearch(final String text, final com.foobnix.android.utils.ResultResponse<Integer> result,
                                   int firstPage, int lastPage) {
        if (searchTask != null && searchTask.getStatus() != CopyAsyncTask.Status.FINISHED) {
            return;
        }

        searchTask = new CopyAsyncTask() {

            @Override protected Object doInBackground(Object... params) {
                try {
                    PageImageState.get()
                                  .cleanSelectedWords();
                    String textLowCase = text.toLowerCase(Locale.US);
                    String bookPath = getBookPath();
                    int prev = -1;

                    boolean nextWorld = false;
                    String firstPart = "";
                    TextWord firstWord = null;
                    int firstWordIndex = 0;

                    PageSearcher pageSearcher = new PageSearcher();
                    pageSearcher.setTextForSearch(text);
                    pageSearcher.setListener(new PageSearcher.OnWordSearched() {
                        @Override public void onSearch(TextWord word, Object data) {
                            if (!(data instanceof Integer)) return;
                            Integer pageNumber = (Integer) data;
                            LOG.d("Find on page_", pageNumber, text, word);
                            List<TextWord> selectedWords = PageImageState.get()
                                                                         .getSelectedWords(pageNumber);
                            if (selectedWords == null || selectedWords.size() <= 0) {
                                result.onResultRecive(pageNumber);
                                LOG.d("Find on page", pageNumber, text);
                            }
                            if (selectedWords == null || !selectedWords.contains(word)) {
                                PageImageState.get()
                                              .addWord(pageNumber, word);
                            }
                        }
                    });

                    for (int i = firstPage; i < lastPage; i++) {
                        if (!TempHolder.isSeaching) {
                            result.onResultRecive(Integer.MAX_VALUE);
                            return null;
                        }

                        if (isClosed) {
                            TempHolder.isSeaching = false;
                            return null;
                        }
                        if (i > 1) {
                            result.onResultRecive(i * -1);
                        }

                        TextWord[][] pageText = getPageText(i);
                        recyclePage(i);
                        if (pageText == null) {
                            continue;
                        }
                        int index = 0;
                        List<TextWord> find = new ArrayList<TextWord>();
                        for (TextWord[] line : pageText) {
                            find.clear();
                            index = 0;
                            for (TextWord word : line) {
                                if (AppState.get().selectingByLetters) {
                                    String it = String.valueOf(textLowCase.charAt(index));
                                    if (word.w.toLowerCase(Locale.US)
                                              .equals(it)) {
                                        index++;
                                        find.add(word);
                                    } else {
                                        index = 0;
                                        find.clear();
                                    }

                                    if (index == text.length()) {
                                        index = 0;
                                        if (prev != i) {
                                            result.onResultRecive(i);
                                            prev = i;
                                        }
                                        for (TextWord t : find) {
                                            PageImageState.get()
                                                          .addWord(i, t);
                                        }
                                    }

                                } else if (word.w.toLowerCase(Locale.US)
                                                 .contains(textLowCase)) {
                                    LOG.d("Contains 1", word.w);
                                    if (prev != i) {
                                        result.onResultRecive(i);
                                        prev = i;
                                    }
                                    PageImageState.get()
                                                  .addWord(i, word);
                                } else if (word.w.length() >= 3 && word.w.endsWith("-")) {
                                    nextWorld = true;
                                    firstWord = word;
                                    firstWordIndex = i;
                                    firstPart = word.w.replace("-", "");
                                } else if (nextWorld && (firstPart + word.w.toLowerCase(Locale.US)).contains(text)) {
                                    LOG.d("Contains 2", firstPart, word.w, text);
                                    PageImageState.get()
                                                  .addWord(firstWordIndex, firstWord);
                                    PageImageState.get()
                                                  .addWord(i, word);
                                    nextWorld = false;
                                    firstWord = null;
                                    firstPart = "";
                                    if (prev != firstWordIndex) {
                                        result.onResultRecive(firstWordIndex);
                                        prev = firstWordIndex;
                                    }
                                    if (prev != i) {
                                        result.onResultRecive(i);
                                        prev = i;
                                    }

                                } else if (nextWorld && TxtUtils.isNotEmpty(word.w)) {
                                    nextWorld = false;
                                    firstWord = null;
                                }
                                pageSearcher.addWord(new PageSearcher.WordData(word, i));
                            }
                        }

                    }
                    result.onResultRecive(-1);
                } catch (Exception e) {
                    result.onResultRecive(-1);
                }
                TempHolder.isSeaching = false;
                return null;
            }

            @Override protected void onPostExecute(Object result) {
                EventBus.getDefault()
                        .post(new InvalidateMessage());
            }

            ;

        }.execute();

    }

    public String getBookPath() {
        return bookPath;
    }

    @Override public File getCurrentBook() {
        return new File(getBookPath());
    }

    @Override public String getTitle() {
        return getTitle(getBookPath());
    }

    @Override public void alignDocument() {
        PageImageState.get().isAutoFit = true;
        EventBus.getDefault()
                .post(new MessageAutoFit(getCurentPage()));
    }

    @Override public void centerHorizontal() {
        PageImageState.get().isAutoFit = true;
        EventBus.getDefault()
                .post(new MessageCenterHorizontally(getCurentPage()));
    }

}