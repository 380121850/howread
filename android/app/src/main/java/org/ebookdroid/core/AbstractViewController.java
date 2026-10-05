package org.ebookdroid.core;

import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.sys.AdvGuestureDetector;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.common.settings.types.DocumentViewMode;
import org.ebookdroid.common.settings.types.PageAlign;
import org.ebookdroid.common.settings.types.PageType;
import org.ebookdroid.common.touch.DefaultGestureDetector;
import org.ebookdroid.common.touch.IGestureDetector;
import org.ebookdroid.common.touch.IMultiTouchListener;
import org.ebookdroid.common.touch.MultiTouchGestureDetector;
import org.ebookdroid.common.touch.TouchManager;
import org.ebookdroid.common.touch.TouchManager.Touch;
import org.ebookdroid.core.codec.Annotation;
import org.ebookdroid.core.codec.PageLink;
import org.ebookdroid.core.models.DocumentModel;
import org.ebookdroid.droids.mupdf.codec.TextWord;
import org.ebookdroid.ui.viewer.IActivityController;
import org.ebookdroid.ui.viewer.IView;
import org.ebookdroid.ui.viewer.IViewController;
import org.emdev.ui.actions.AbstractComponentController;
import org.emdev.ui.actions.ActionEx;
import org.emdev.ui.actions.params.Constant;
import org.emdev.ui.progress.IProgressIndicator;
import org.emdev.utils.LengthUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractViewController extends AbstractComponentController<IView> implements IViewController {


    public final IActivityController base;

    public final DocumentModel model;

    public final DocumentViewMode mode;
    protected final AtomicBoolean inZoom = new AtomicBoolean();
    protected final AtomicBoolean inQuickZoom = new AtomicBoolean();
    protected volatile boolean isInitialized = false;
    protected boolean isShown = false;

    //protected final PageIndex pageToGo;
    protected int firstVisiblePage;

    protected int lastVisiblePage;

    protected boolean layoutLocked;
    float xLong;
    float yLong;
    private List<IGestureDetector> detectors;

    public AbstractViewController(final IActivityController base, final DocumentViewMode mode) {
        super(base, base.getView());

        this.base = base;
        this.mode = mode;
        this.model = base.getDocumentModel();

        this.firstVisiblePage = -1;
        this.lastVisiblePage = -1;

        //this.pageToGo = SettingsManager.getBookSettings().getCurrentPage(base.getDocumentModel().getPageCount());

        //createAction(R.id.adFrame, new Constant("direction", -1));
        //createAction(R.id.adFrame, new Constant("direction", +1));

    }

    protected List<IGestureDetector> getGestureDetectors() {
        if (detectors == null) {
            detectors = initGestureDetectors(new ArrayList<IGestureDetector>());
        }
        return detectors;
    }

    /** The AdvGuestureDetector built in initGestureDetectors subscribes to
     * the EventBus; called when this controller is retired so the retired
     * instance stops receiving events and stops leaking its activity graph. */
    public void destroyGestures() {
        if (detectors != null) {
            detectors.clear();
            detectors = null;
        }
        if (guestureDetector != null) {
            guestureDetector.destroy();
            guestureDetector = null;
        }
    }

    private AdvGuestureDetector guestureDetector;
    /** 空文字网格自愈的一次性护栏：转换缓存删除+重载每进程只做一次，防循环 */

    protected List<IGestureDetector> initGestureDetectors(final List<IGestureDetector> list) {
        final AdvGuestureDetector listener = new AdvGuestureDetector(this, base.getListener());
        guestureDetector = listener;
        list.add(listener.innerDetector);
        list.add(new MultiTouchGestureDetector(listener));
        list.add(new DefaultGestureDetector(base.getContext(), listener));
        return list;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#getView()
     */
    @Override
    public final IView getView() {
        return base.getView();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#getBase()
     */
    @Override
    public final IActivityController getBase() {
        return base;
    }

    @Override
    public final void init(final IProgressIndicator task) {
        if (!isInitialized) {
            try {
                model.initPages(base, task);
            } finally {
                isInitialized = true;
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     *
     */
    @Override
    public final void onDestroy() {
        // isShown = false;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#show()
     */
    @Override
    public final void show() {
        if (!isInitialized) {
            return;
        }
        if (!isShown) {
            isShown = true;

            invalidatePageSizes(InvalidateSizeReason.INIT, null);

            final AppBook bs = SettingsManager.getBookSettings();

            final int pageCount = getBase().getDocumentModel().getPageCount();
            // Fast-open: restore by the saved absolute page whenever it is
            // inside the laid-out range (percent math needs the final count).
            PageIndex currentPage =
                    bs.pg >= 0 && bs.pg < pageCount ? new PageIndex(bs.pg, bs.pg) : bs.getCurrentPage(pageCount);
            int toPage = currentPage != null ? currentPage.docIndex : 0;

            if (AppState.get().isAlwaysOpenOnPage1) {
                toPage = 0;
            }


            goToPage(toPage, bs.x, bs.y);

        }
    }

    protected final void updatePosition(final Page page, final ViewState viewState) {
        if (page != null) {
            final PointF pos = viewState.getPositionOnPage(page);
            SettingsManager.positionChanged(pos.x, pos.y);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.core.events.ZoomListener#zoomChanged(float, float,
     * boolean)
     */
    @Override
    public final void zoomChanged(final float oldZoom, final float newZoom, final boolean committed) {
        if (!isShown) {
            return;
        }

        inZoom.set(!committed);
        EventPool.newEventZoom(this, oldZoom, newZoom, committed).process();

        if (!committed) {
            inQuickZoom.set(false);
        }
    }

    public final void quickZoom(final ActionEx action) {
        if (inZoom.get()) {
            return;
        }
        float zoomFactor = 2.0f;
        if (inQuickZoom.compareAndSet(true, false)) {
            zoomFactor = 1.0f / zoomFactor;
        } else {
            inQuickZoom.set(true);
        }
        base.getZoomModel().scaleAndCommitZoom(zoomFactor);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#updateMemorySettings()
     */
    @Override
    public final void updateMemorySettings() {
        EventPool.newEventReset(this, null, false).process();
    }

    public final int getScrollX() {
        return getView().getScrollX();
    }

    public final int getWidth() {
        return getView().getWidth();
    }

    public final int getScrollY() {
        return getView().getScrollY();
    }

    public final int getHeight() {
        return getView().getHeight();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#onTouchEvent(android.view.MotionEvent)
     */
    @Override
    public final boolean onTouchEvent(final MotionEvent ev) {
        for (final IGestureDetector d : getGestureDetectors()) {
            if (d.enabled() && d.onTouchEvent(ev)) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#onLayoutChanged(boolean,
     * boolean, android.graphics.Rect, android.graphics.Rect)
     */
    @Override
    public boolean onLayoutChanged(final boolean layoutChanged) {
        if (layoutChanged) {
            if (isShown) {
                EventPool.newEventReset(this, InvalidateSizeReason.LAYOUT, true).process();
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#toggleRenderingEffects()
     */
    @Override
    public final void toggleRenderingEffects() {
        EventPool.newEventReset(this, null, true).process();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#invalidateScroll()
     */
    @Override
    public final void invalidateScroll() {
        if (!isShown) {
            return;
        }
        getView().invalidateScroll();
    }

    /**
     * Sets the page align flag.
     *
     * @param align the new flag indicating align
     */
    @Override
    public final void setAlign(final PageAlign align) {
        EventPool.newEventReset(this, InvalidateSizeReason.PAGE_ALIGN, false).process();
    }

    /**
     * Checks if view is initialized.
     *
     * @return true, if is initialized
     */
    protected final boolean isShown() {
        return isShown;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#getFirstVisiblePage()
     */
    @Override
    public final int getFirstVisiblePage() {
        return firstVisiblePage;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#getLastVisiblePage()
     */
    @Override
    public final int getLastVisiblePage() {
        return lastVisiblePage;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#redrawView()
     */
    @Override
    public final void redrawView() {
        getView().redrawView(new ViewState(this));
    }

    /**
     * {@inheritDoc}
     *
     * @see org.ebookdroid.ui.viewer.IViewController#redrawView(org.ebookdroid.core.ViewState)
     */
    @Override
    public final void redrawView(final ViewState viewState) {
        getView().redrawView(viewState);
    }

    @Override
    public void clearSelectedText() {
        for (final Page page : model.getPages()) {
            page.selectedText.clear();
        }
        redrawView();
    }

    public final String processLongTap(boolean single, final MotionEvent e1, final MotionEvent e2, boolean draw) {
        if (e1 != null) {
            xLong = e1.getX();
            yLong = e1.getY();
        }

        float x2 = e2.getX();
        float y2 = e2.getY();

        final float zoom = base.getZoomModel().getZoom();

        final RectF tapRect = new RectF(xLong, yLong, x2, y2);
        if (yLong > y2) {
            tapRect.sort();
        }
        tapRect.offset(getScrollX(), getScrollY());

        StringBuilder build = new StringBuilder();

        boolean isHyphenWorld = false;
        Page hitPage = null;

        // BENCH 诊断（长按选中排查）：触点、可见页范围
        LOG.bench("LongTap begin rect=[" + (int) tapRect.left + "," + (int) tapRect.top
                + "," + (int) tapRect.right + "," + (int) tapRect.bottom + "] zoom=" + zoom
                + " pages=" + firstVisiblePage + ".." + lastVisiblePage);
        int logWords = 0;

        LOG.d("Add Word page", "----", firstVisiblePage, lastVisiblePage + 1);
        for (final Page page : model.getPages(firstVisiblePage, lastVisiblePage + 1)) {
            if (draw)
                page.selectedText.clear();
            LOG.d("Add Word page", page.hashCode());
            final RectF bounds = page.getBounds(zoom);
            LOG.bench("LongTapPage pg=" + page.index.docIndex
                    + " bounds=[" + (int) bounds.left + "," + (int) bounds.top + ","
                    + (int) bounds.right + "," + (int) bounds.bottom + "]"
                    + " texts=" + (page.texts == null ? "null" : page.texts.length + "ln")
                    + " hitBounds=" + RectF.intersects(bounds, tapRect));
            TextWord prevWord = null;
            if (RectF.intersects(bounds, tapRect)) {
                hitPage = page;
                // 强制现场重取：缓存的文字网格不可信——实证缺陷有二：①可能是
                // 相邻页的提取结果（12S/MI9 双机浮点精确匹配实锤，选中文字与
                // 页面所见错位）；②旧排版几何的压缩产物（词矩形只覆盖页面上部
                // ~64%，页面下部长按永远无选中框）。owned page 直取毫秒级，
                // 保证选中几何与当前渲染同页同源。取回仍是空网格才走下面的
                // 重试与 heal。
                if (BookCSS.get().isTextFormat()) {
                    try {
                        page.texts = null;
                        base.getDecodeService().processTextForPages(new Page[] { page });
                    } catch (final Throwable t) {
                        LOG.e(t);
                    }
                }
                if (LengthUtils.isEmpty(page.texts)) {
                    // 修复：解码取词撞上共享页回收窗口的空网格此前会被永久缓存，
                    // 该页整个会话选不了。长按现场补取（owned page，不经共享
                    // 缓存，绕开回收竞态），UI 线程单页取词为毫秒级。12S 现场
                    // 证明该提取会偶发空（开书探测取得到、随后取空），重试两次
                    // 收窄竞态窗口。
                    for (int attempt = 1; attempt <= 3 && LengthUtils.isEmpty(page.texts); attempt++) {
                        try {
                            base.getDecodeService().processTextForPages(new Page[] { page });
                            LOG.bench("LongTap refetch#" + attempt + " pg=" + page.index.docIndex
                                    + " texts=" + (page.texts == null ? "null" : page.texts.length + "ln"));
                        } catch (final Throwable t) {
                            LOG.e(t);
                        }
                        if (LengthUtils.isEmpty(page.texts) && attempt < 3) {
                            try {
                                Thread.sleep(120);
                            } catch (final InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                if (LengthUtils.isEmpty(page.texts)) {
                    // 12S 现场三轮实证：①开书探测取得到字、长按现场取空（打开后
                    // 提取状态劣化）；②自愈必须盐化缓存路径（旧版少拼文件盐从未
                    // 触发）；③该设备重转产物依旧无文字层——新缓存文件新签名，
                    // "每文件一次"记账防不住连环删缓存+重启，且 restartActivity
                    // 同步执行时同一手势的后续事件还会打进已回收的模型。现改为：
                    // 与打开路径共用 kept:<book> 闩（每本书至多自愈一次），
                    // 重启延迟投递，让当前触摸流先走完。
                    try {
                        final String bookPath = base.getListener().getCurrentBook().getPath();
                        final org.ebookdroid.core.codec.CodecContext ctxC =
                                org.ebookdroid.BookType.getCodecContextByPath(bookPath);
                        if (ctxC instanceof org.ebookdroid.core.codec.AbstractCodecContext) {
                            final android.content.SharedPreferences sp = com.foobnix.LibreraApp.context
                                    .getSharedPreferences("codec_probe", 0);
                            final java.io.File convCache = ((org.ebookdroid.core.codec.AbstractCodecContext) ctxC)
                                    .getCacheFileName(bookPath
                                            + org.ebookdroid.core.codec.AbstractCodecContext.getFileNameSalt(bookPath));
                            final String healSig = convCache.getPath() + ":" + convCache.length()
                                    + ":" + convCache.lastModified();
                            final boolean alreadyHealed = sp.contains("healed:" + healSig)
                                    || sp.contains("kept:" + bookPath);
                            if (convCache.isFile() && !alreadyHealed) {
                                sp.edit().putBoolean("healed:" + healSig, true)
                                        .putBoolean("kept:" + bookPath, true).apply();
                                LOG.bench("LongTap empty grid persists -> corrupt conversion cache, delete & reload: "
                                        + convCache);
                                convCache.delete();
                                // 延迟重启：当前手势的 UP 事件仍会进入本视图，
                                // 同步重启会让它命中已回收的页面数组（AIOOBE 闪退）
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed(new Runnable() {
                                            @Override public void run() {
                                                try {
                                                    base.getListener().restartActivity();
                                                } catch (final Throwable t) {
                                                    LOG.e(t);
                                                }
                                            }
                                        }, 300);
                            } else {
                                LOG.bench("LongTap empty grid persists (heal used or no cache): " + convCache);
                            }
                        }
                    } catch (final Throwable t) {
                        LOG.e(t);
                    }
                }
                if (LengthUtils.isNotEmpty(page.texts)) {
                    // 网格健康度（命中页才算）：texts 外层是"块"不是行（中文整行
                    // 一词时 1ln 曾被误读为整页只有 1 行）。词数 + 词矩形纵向
                    // 覆盖率一锤定音，选字排障先看这行。
                    int gridWords = 0;
                    float gridY0 = Float.MAX_VALUE, gridY1 = -Float.MAX_VALUE;
                    for (final TextWord[] glines : page.texts) {
                        if (glines == null) {
                            continue;
                        }
                        for (final TextWord gword : glines) {
                            if (gword == null || TxtUtils.isEmpty(gword.w)) {
                                continue;
                            }
                            final RectF gr = page.getPageRegion(bounds, gword);
                            if (gr == null) {
                                continue;
                            }
                            gridWords++;
                            gridY0 = Math.min(gridY0, gr.top);
                            gridY1 = Math.max(gridY1, gr.bottom);
                        }
                    }
                    if (gridWords > 0) {
                        TextWord sw0 = null, swm = null, swN = null;
                        int sn = 0;
                        for (final TextWord[] glines : page.texts) {
                            if (glines == null) {
                                continue;
                            }
                            for (final TextWord gword : glines) {
                                if (gword == null || TxtUtils.isEmpty(gword.w) || gword.isEmpty()) {
                                    continue;
                                }
                                sn++;
                                if (sn == 1) {
                                    sw0 = gword;
                                }
                                if (sn == gridWords / 2 + 1) {
                                    swm = gword;
                                }
                                swN = gword;
                            }
                        }
                        LOG.bench("LongTap sample pg=" + page.index.docIndex
                                + " w0=[" + (sw0 == null ? "-" : sw0.top + ".." + sw0.bottom) + "]"
                                + " wm=[" + (swm == null ? "-" : swm.top + ".." + swm.bottom) + "]"
                                + " wN=[" + (swN == null ? "-" : swN.top + ".." + swN.bottom) + "]");
                        LOG.bench("LongTap grid pg=" + page.index.docIndex + " words=" + gridWords
                                + " ycov=[" + (int) (Math.max(0f, (gridY0 - bounds.top) / bounds.height()) * 100)
                                + "%," + (int) (Math.min(1f, (gridY1 - bounds.top) / bounds.height()) * 100)
                                + "%] boundsH=" + (int) bounds.height());
                    }
                }
                if (LengthUtils.isNotEmpty(page.texts)) {

                    for (final TextWord[] lines : page.texts) {
                        final TextWord current[] = lines;
                        for (final TextWord line : current) {
                            if (!BookCSS.get().isTextFormat() && (line.left < 0 || line.top < 0)) {
                                continue;
                            }
                            RectF wordRect = page.getPageRegion(bounds, line);
                            if (wordRect == null) {
                                if (logWords < 3) {
                                    LOG.bench("LongTapWord pg=" + page.index.docIndex
                                            + " NULL-REGION w=" + line.w
                                            + " line=[" + line.left + "," + line.top + "]");
                                    logWords++;
                                }
                                continue;
                            }
                            if (logWords < 3) {
                                LOG.bench("LongTapWord pg=" + page.index.docIndex
                                        + " w=" + line.w
                                        + " rect=[" + (int) wordRect.left + "," + (int) wordRect.top
                                        + "," + (int) wordRect.right + "," + (int) wordRect.bottom + "]");
                                logWords++;
                            }

                            if (isHyphenWorld || (single && RectF.intersects(wordRect, tapRect))) {
                                if (prevWord != null && prevWord.w.endsWith("-") && !isHyphenWorld) {
                                    build.append(prevWord.w.replace("-", ""));
                                    if (draw)
                                        page.selectedText.add(prevWord);
                                }

                                build.append(line.w + " ");

                                if (!isHyphenWorld) {
                                    if (draw)
                                        page.selectedText.add(line);
                                }

                                LOG.d("Add Word", line.w);

                                if (isHyphenWorld && TxtUtils.isNotEmpty(line.w)) {
                                    if (draw)
                                        page.selectedText.add(line);
                                    isHyphenWorld = false;
                                }

                                if (line.w.endsWith("-")) {
                                    isHyphenWorld = true;
                                }

                                // get links
                                if (LengthUtils.isNotEmpty(page.links)) {
                                    for (final PageLink link : page.links) {
                                        final RectF linkRect = page.getLinkSourceRect(bounds, link);
                                        if (linkRect == null) {
                                            continue;
                                        }

                                        if (RectF.intersects(linkRect, wordRect)) {
                                            TempHolder.get().linkPage = link.targetPage;
                                        }
                                    }
                                }

                            } else if (!single) {
                                if (y2 > yLong) {
                                    if (wordRect.top < tapRect.top && wordRect.bottom > tapRect.top && wordRect.right > tapRect.left) {
                                        if (draw)
                                            page.selectedText.add(line);
                                        build.append(line.w + TxtUtils.space());

                                        LOG.d("Add Word", line.w);
                                    } else if (wordRect.top < tapRect.bottom && wordRect.bottom > tapRect.bottom && wordRect.left < tapRect.right) {
                                        if (draw)
                                            page.selectedText.add(line);
                                        build.append(line.w + TxtUtils.space());

                                        LOG.d("Add Word", line.w);
                                    } else if (wordRect.top > tapRect.top && wordRect.bottom < tapRect.bottom) {
                                        if (draw)
                                            page.selectedText.add(line);
                                        build.append(line.w + TxtUtils.space());

                                        LOG.d("Add Word", line.w);
                                    }

                                } else if (RectF.intersects(wordRect, tapRect)) {
                                    if (draw)
                                        page.selectedText.add(line);
                                    if (AppState.get().selectingByLetters) {
                                        build.append(line.w);
                                    } else {
                                        build.append(line.w.trim() + " ");
                                    }

                                    LOG.d("Add Word", line.w);
                                }
                            }
                            if (TxtUtils.isNotEmpty(line.w)) {
                                prevWord = line;
                            }
                        }
                        String k;
                        if (AppState.get().selectingByLetters && current.length >= 2 && !(k = current[current.length - 1].getWord()).equals(" ") && !k.equals("-")) {
                            build.append(" ");
                        }
                    }

                }

            }

        }
        // 最近词吸附：长按触点是零面积矩形，落在行间/字间空隙时与任何词矩
        // 形都不相交（大字号宽行距下尤其常见——12S 现场用户每次长按都掉进
        // 缝隙，表现为"弹菜单但永远没有选中框"）。此时在容差内吸附最近词，
        // 恢复标准"按词选中+选区框+拖拽手柄"交互；真空白（图片/页边大空档）
        // 超出容差不吸附，继续走 HTML 兜底。
        if (single && build.length() == 0 && hitPage != null && LengthUtils.isNotEmpty(hitPage.texts)) {
            final RectF snapBounds = hitPage.getBounds(zoom);
            final float tapX = tapRect.centerX();
            final float tapY = tapRect.centerY();
            TextWord best = null;
            RectF bestRect = null;
            double bestD2 = Double.MAX_VALUE;
            for (final TextWord[] lines : hitPage.texts) {
                if (lines == null) {
                    continue;
                }
                for (final TextWord word : lines) {
                    if (word == null || TxtUtils.isEmpty(word.w)) {
                        continue;
                    }
                    final RectF r = hitPage.getPageRegion(snapBounds, word);
                    if (r == null) {
                        continue;
                    }
                    final float dx = Math.max(Math.max(r.left - tapX, 0), tapX - r.right);
                    final float dy = Math.max(Math.max(r.top - tapY, 0), tapY - r.bottom);
                    final double d2 = (double) dx * dx + (double) dy * dy;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = word;
                        bestRect = r;
                    }
                }
            }
            if (best != null && bestRect != null) {
                final float tol = Math.max(bestRect.height(), bestRect.width() * 0.5f) * 1.5f;
                LOG.bench("LongTap snap-check pg=" + hitPage.index.docIndex + " bestW=" + best.w
                        + " rect=[" + (int) bestRect.left + "," + (int) bestRect.top
                        + "," + (int) bestRect.right + "," + (int) bestRect.bottom + "]"
                        + " d=" + (int) Math.sqrt(bestD2) + " tol=" + (int) tol);
                if (bestD2 <= (double) tol * tol) {
                    if (draw) {
                        hitPage.selectedText.add(best);
                    }
                    build.append(best.w + TxtUtils.space());
                    LOG.bench("LongTap snap-word pg=" + hitPage.index.docIndex + " w=" + best.w);
                }
            }
        }
        // 兜底：native 文字网格为空/缺失时（现场 12S 实证：该书的转换产物在
        // 部分设备上文字提取持续为空，refetch 亦空），退回页面 HTML 文本作为
        // 长按选中文本——选择菜单（复制/发送给AI/文内搜索）立即可用；精确到
        // 词的高亮选区在该页不可用属可接受降级。
        if (build.length() == 0 && hitPage != null) {
            try {
                final String html = base.getDecodeService().getPageHTML(hitPage.index.docIndex);
                if (TxtUtils.isNotEmpty(html)) {
                    final String plain = android.text.Html.fromHtml(html).toString().trim();
                    if (TxtUtils.isNotEmpty(plain)) {
                        // 12S 现场反馈"长按不同位置选中的都是页首文字"：没有词坐
                        // 标时按长按点在页内的纵向比例近似截取附近一段，让选取
                        // 至少跟着手指位置走（纯估算，非精确词界）。
                        final RectF fb = hitPage.getBounds(zoom);
                        float frac = 0f;
                        if (fb.height() > 0) {
                            frac = (tapRect.centerY() - fb.top) / fb.height();
                            frac = Math.max(0f, Math.min(1f, frac));
                        }
                        final int WIN = 200;
                        int mid = (int) (frac * plain.length());
                        int from = Math.max(0, Math.min(mid - WIN / 2, Math.max(0, plain.length() - 1)));
                        int to = Math.min(plain.length(), from + WIN);
                        String slice = plain.substring(from, to);
                        if (from > 0) {
                            final int sp = slice.indexOf(' ');
                            if (sp >= 0 && sp < slice.length() - 1) {
                                slice = slice.substring(sp + 1);
                            }
                        }
                        if (slice.trim().length() == 0) {
                            slice = plain;
                        }
                        LOG.bench("LongTap HTML fallback pg=" + hitPage.index.docIndex
                                + " len=" + plain.length() + " frac=" + ((int) (frac * 100))
                                + "% slice=[" + from + "," + to + ")");
                        build.append(slice);
                    }
                }
            } catch (final Throwable t) {
                LOG.e(t);
            }
        }
        LOG.bench("LongTap result len=" + build.length());
        if (build.length() > 0) {
            redrawView();

            String txt = build.toString();
            try {
                if (txt.endsWith("- ")) {
                    TextWord[][] texts = model.getPageByDocIndex(firstVisiblePage + 1).texts;
                    if (texts[0].length > 1) {
                        txt += texts[0][1].w;
                    } else {
                        txt += texts[0][0].w;
                    }
                }
            } catch (Exception e) {
                LOG.e(e);
            }

            String filterString = TxtUtils.filterString(txt);
            LOG.d("Add Word SELECT-TEXT", filterString);
            LOG.d("Add Word SELECT-TEXT-ACTION");

            return filterString;
        }

        return null;

    }

    public final boolean processTap(final TouchManager.Touch type, final MotionEvent e) {
        final float x = e.getX();
        final float y = e.getY();

        if (type == Touch.SingleTap) {
            if (processLinkTap(x, y)) {
                return true;
            }
        }

        return processActionTap(type, x, y);
    }

    protected boolean processActionTap(final TouchManager.Touch type, final float x, final float y) {
        // final Integer actionId = TouchManager.getAction(type, x, y,
        // getWidth(), getHeight());
        final Integer actionId = null;
        final ActionEx action = actionId != null ? getOrCreateAction(actionId) : null;
        if (action != null && action.getMethod().isValid()) {
            action.run();
            return true;
        }
        return false;
    }

    public void selectAnnotation(Annotation annotation) {
        if (annotation == null) {
            for (final Page page : model.getPages(firstVisiblePage, lastVisiblePage + 1)) {
                page.selectionAnnotion = null;
            }
            base.getDocumentController().redrawView();
            return;
        }
        Page pageByDocIndex = base.getDocumentModel().getPageByDocIndex(annotation.getPage() - 1);
        pageByDocIndex.selectionAnnotion = annotation;
        base.getDocumentController().redrawView();
    }

    public final Annotation isAnnotationTap(final float x, final float y) {

        final float zoom = base.getZoomModel().getZoom();
        final RectF rect = new RectF(x, y, x, y);
        rect.offset(getScrollX(), getScrollY());

        for (final Page page : model.getPages(firstVisiblePage, lastVisiblePage + 1)) {
            final RectF bounds = page.getBounds(zoom);
            if (RectF.intersects(bounds, rect)) {
                if (page.annotations == null) {
                    continue;
                }
                for (Annotation a : page.annotations) {
                    RectF wordRect = page.getPageRegion(bounds, a);
                    if (wordRect == null) {
                        continue;
                    }
                    boolean intersects = RectF.intersects(wordRect, rect);
                    LOG.d("Annotation", wordRect, rect, intersects);
                    if (intersects) {
                        LOG.d("Intersects with Annotation", a);
                        return a;
                    }
                }
            }
        }
        return null;

    }

    protected final boolean processLinkTap(final float x, final float y) {
        final float zoom = base.getZoomModel().getZoom();
        final RectF rect = new RectF(x, y, x, y);
        rect.offset(getScrollX(), getScrollY());

        for (final Page page : model.getPages(firstVisiblePage, lastVisiblePage + 1)) {
            final RectF bounds = page.getBounds(zoom);
            if (RectF.intersects(bounds, rect)) {
                if (LengthUtils.isNotEmpty(page.links)) {
                    for (final PageLink link : page.links) {
                        if (processLinkTap(page, link, bounds, rect)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    public int getLinkPage(final float x, final float y) {
        final float zoom = base.getZoomModel().getZoom();
        final RectF rect = new RectF(x, y, x, y);
        rect.offset(getScrollX(), getScrollY());

        for (final Page page : model.getPages(firstVisiblePage, lastVisiblePage + 1)) {
            final RectF bounds = page.getBounds(zoom);
            if (RectF.intersects(bounds, rect)) {
                if (LengthUtils.isNotEmpty(page.links)) {
                    for (final PageLink link : page.links) {
                        final RectF linkRect = page.getLinkSourceRect(bounds, link);

                        if (linkRect == null || !RectF.intersects(linkRect, rect)) {
                            return -1;
                        }

                        // if (link != null && link.url != null &&
                        // link.url.startsWith("http")) {
                        // openUrl(link.url);
                        // return true;
                        // }

                        if (link != null) {
                            return link.targetPage;
                        }

                    }
                }
                return -1;
            }
        }
        return -1;
    }

    protected final boolean processLinkTap(final Page page, final PageLink link, final RectF pageBounds, final RectF tapRect) {

        LOG.d("TEST", "processLinkTap");

        final RectF linkRect = page.getLinkSourceRect(pageBounds, link);

        if (linkRect == null || !RectF.intersects(linkRect, tapRect)) {
            return false;
        }

        if (link != null && link.url != null) {
            AlertDialogs.openUrl(base.getActivity(), link.url);
            return true;
        }

        if (link != null) {
            goToLink(link.targetPage, link.targetRect, true);
        }
        return true;
    }

    @Override
    public ViewState goToLink(final int pageDocIndex, final RectF targetRect, final boolean addToHistory) {
        if (pageDocIndex >= 0) {
            Page target = model.getPageByDocIndex(pageDocIndex);
            if (target == null) {
                return null;
            }
            float offsetX = 0;
            float offsetY = 0;
            if (targetRect != null) {
                offsetX = targetRect.left;
                offsetY = targetRect.top;
                if (target.type == PageType.LEFT_PAGE && offsetX >= 0.5f) {
                    target = model.getPageObject(target.index.viewIndex + 1);
                    offsetX -= 0.5f;
                }
            }
            if (target != null) {
                return base.jumpToPage(target.index.viewIndex, offsetX, offsetY, addToHistory);
            }
        }
        return null;
    }

    protected class GestureListener extends SimpleOnGestureListener implements IMultiTouchListener {

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onDoubleTap(android.view.MotionEvent)
         */
        @Override
        public boolean onDoubleTap(final MotionEvent e) {
            return processTap(TouchManager.Touch.DoubleTap, e);
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onDown(android.view.MotionEvent)
         */
        @Override
        public boolean onDown(final MotionEvent e) {
            getView().forceFinishScroll();
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onFling(android.view.MotionEvent,
         * android.view.MotionEvent, float, float)
         */
        @Override
        public boolean onFling(final MotionEvent e1, final MotionEvent e2, final float vX, final float vY) {
            final Rect l = getScrollLimits();
            float x = vX, y = vY;
            if (Math.abs(vX / vY) < 0.5) {
                x = 0;
            }
            if (Math.abs(vY / vX) < 0.5) {
                y = 0;
            }
            getView().startFling(x, y, l);
            getView().redrawView();
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onScroll(android.view.MotionEvent,
         * android.view.MotionEvent, float, float)
         */
        @Override
        public boolean onScroll(final MotionEvent e1, final MotionEvent e2, final float distanceX, final float distanceY) {
            float x = distanceX, y = distanceY;
            if (Math.abs(distanceX / distanceY) < 0.5) {
                x = 0;
            }
            if (Math.abs(distanceY / distanceX) < 0.5) {
                y = 0;
            }
            getView().scrollBy((int) x, (int) y);
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onSingleTapUp(android.view.MotionEvent)
         */
        @Override
        public boolean onSingleTapUp(final MotionEvent e) {
            return true;
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onSingleTapConfirmed(android.view.MotionEvent)
         */
        @Override
        public boolean onSingleTapConfirmed(final MotionEvent e) {
            return processTap(TouchManager.Touch.SingleTap, e);
        }

        /**
         * {@inheritDoc}
         *
         * @see android.view.GestureDetector.SimpleOnGestureListener#onLongPress(android.view.MotionEvent)
         */
        @Override
        public void onLongPress(final MotionEvent e) {
            // LongTap operation cause side-effects
            // processTap(TouchManager.Touch.LongTap, e);
        }


        @Override
        public void onTwoFingerPinch(final MotionEvent e, final float oldDistance, final float newDistance) {
            final float factor = (float) Math.sqrt(newDistance / oldDistance);
            base.getZoomModel().scaleZoom(factor);
        }


        @Override
        public void onTwoFingerPinchEnd(final MotionEvent e) {
            base.getZoomModel().commit();
        }


        @Override
        public void onTwoFingerTap(final MotionEvent e) {
            processTap(TouchManager.Touch.TwoFingerTap, e);
        }
    }

}
