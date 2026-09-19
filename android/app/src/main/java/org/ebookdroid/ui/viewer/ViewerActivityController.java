package org.ebookdroid.ui.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.foobnix.ai.BilingualSession;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Intents;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.Safe;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.ReadingStats;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.DocumentWrapperUI;
import com.foobnix.pdf.CopyAsyncTask;
import com.foobnix.pdf.search.activity.HorizontalModeController;
import com.foobnix.sys.FirstPaintGate;
import com.foobnix.sys.TempHolder;
import com.foobnix.sys.VerticalModeController;
import com.foobnix.tts.TTSEngine;
import com.foobnix.tts.TTSNotification;
import com.foobnix.ui2.AdsFragmentActivity;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;

import org.ebookdroid.BookType;
import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.common.settings.listeners.IBookSettingsChangeListener;
import org.ebookdroid.common.settings.types.DocumentViewMode;
import org.ebookdroid.core.DecodeService;
import org.ebookdroid.core.Page;
import org.ebookdroid.core.ViewState;
import org.ebookdroid.core.events.CurrentPageListener;
import org.ebookdroid.core.events.DecodingProgressListener;
import org.ebookdroid.core.models.DocumentModel;
import org.ebookdroid.core.models.ZoomModel;
import org.ebookdroid.droids.mupdf.codec.exceptions.MuPdfPasswordException;
import org.ebookdroid.ui.viewer.stubs.ActivityControllerStub;
import org.ebookdroid.ui.viewer.stubs.ViewContollerStub;
import org.emdev.ui.actions.ActionController;
import org.emdev.ui.actions.ActionEx;
import org.emdev.ui.actions.IActionController;
import org.emdev.ui.actions.params.EditableValue.PasswordEditable;
import org.emdev.ui.progress.IProgressIndicator;
import org.emdev.ui.tasks.BaseAsyncTask;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ViewerActivityController extends ActionController<VerticalViewActivity> implements IActivityController,
        DecodingProgressListener, CurrentPageListener, IBookSettingsChangeListener {

    private final AtomicReference<IViewController> ctrl = new AtomicReference<IViewController>(ViewContollerStub.STUB);

    private ZoomModel zoomModel;

    private DocumentModel documentModel;

    private BookType codecType;

    private final Intent intent;

    private int loadingCount = 0;

    // silent bilingual-reload markers (consumed once in afterCreate)
    private boolean silentReload;
    private String bilingualAnchorMd5;
    private int bilingualAnchorPage = -1;

    private String m_fileName;
    private String title;

    private DocumentWrapperUI wrapperControlls;

    private VerticalModeController controller;

    VerticalViewActivity viewerActivity;

    /**
     * Instantiates a new base viewer activity.
     */
    public ViewerActivityController(final VerticalViewActivity activity) {
        super(activity);
        this.viewerActivity = activity;
        this.intent = activity.getIntent();
        SettingsManager.addListener(this);

        controller = new VerticalModeController(activity, this);
        wrapperControlls = new DocumentWrapperUI(controller);
        LOG.d("ViewerActivityController create");
    }

    @Override public VerticalModeController getListener() {
        return controller;
    }

    public void beforeCreate(final VerticalViewActivity activity) {
        if (getManagedComponent() != activity) {
            setManagedComponent(activity);
        }
    }

    Runnable onBookLoaded;

    public void onBookLoaded(Runnable onBookLoaded) {
        this.onBookLoaded = onBookLoaded;
    }

    public void afterCreate(VerticalViewActivity a) {
        final VerticalViewActivity activity = getManagedComponent();

        DocumentController.chooseFullScreen(activity, AppState.get().fullScreenMode);

        // consume the silent bilingual-reload markers once (they travel on the
        // reused Intent): the reader skips its loading dialog and re-lands on
        // the same content via the paragraph anchor.
        silentReload = intent != null && intent.getBooleanExtra("bilingualSilentReload", false);
        bilingualAnchorMd5 = intent == null ? null : intent.getStringExtra("bilingualAnchorMd5");
        bilingualAnchorPage = intent == null ? -1 : intent.getIntExtra("bilingualAnchorPage", -1);
        if (intent != null) {
            intent.removeExtra("bilingualSilentReload");
            intent.removeExtra("bilingualAnchorMd5");
            intent.removeExtra("bilingualAnchorPage");
        }

        if (++loadingCount == 1) {
            documentModel = ActivityControllerStub.DM_STUB;

            if (intent == null || intent.getData() == null) {
                return;
            }

            String filePath = Apps.getBookPathFromActivity(activity);
            m_fileName = filePath;
            codecType = BookType.getByUri(m_fileName);

            // Fast DB-only lookup: the (possibly expensive) full metadata
            // extraction runs inside BookLoadTask on a background thread, so
            // an unscanned book no longer blocks the main thread here.
            FileMeta meta = AppDB.get().load(m_fileName);
            title = meta == null ? null : meta.getTitle();
            if (TxtUtils.isEmpty(title)) {
                title = ExtUtils.getFileName(m_fileName);
            }
            LOG.d("Book-title", title);

            if (codecType == null) {
                if (getActivity() != null) {
                    Toast.makeText(getActivity(),
                                 Apps.getApplicationName(getActivity()) + " " + getActivity().getString(
                                         R.string.application_cannot_open_the_book), Toast.LENGTH_LONG)
                         .show();
                    getActivity().finish();
                }
                return;
            }

            LOG.d("codecType last", codecType);
            documentModel = new DocumentModel(codecType, getView());
            documentModel.addListener(ViewerActivityController.this);

            controller.setCurrentBook(new File(filePath));

            wrapperControlls.hideShowEditIcon();

            // Recent list update writes DB + JSON; keep it off the main thread
            // (the horizontal mode path already calls this from a background
            // thread, so it is background-safe).
            final String recentPath = filePath;
            AppsConfig.executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        controller.addRecent(recentPath);
                    } catch (Throwable e) {
                        LOG.e(e);
                    }
                }
            });
            SettingsManager.getBookSettings(filePath);

            final AppBook.Diff diff = new AppBook.Diff(null, SettingsManager.getBookSettings());
            onBookSettingsChanged(null, SettingsManager.getBookSettings(), diff);

            if (intent.hasExtra("id2")) {
                wrapperControlls.showSelectTextMenu();
            }

            wrapperControlls.setTitle(title);
        }
        wrapperControlls.updateUI();

    }

    public void afterPostCreate() {

        if (loadingCount == 1 && documentModel != ActivityControllerStub.DM_STUB) {
            String stringExtra = intent.getStringExtra(DocumentController.EXTRA_PASSWORD);
            if (stringExtra == null) {
                stringExtra = "";
            }
            startDecoding(m_fileName, stringExtra);
        }

    }

    public int pageCount;

    /** True while the current load used the two-phase (progressive) layout. */
    private volatile boolean progressiveLoad;

    /**
     * The TOC (outline) load is deferred while a progressive phase-two layout
     * is running: getOutline() forces a full-document layout in native code,
     * which used to occupy the decode executor for 10s+ on large books and
     * left the second visible page blank after opening.
     */
    private boolean outlineLoaded;

    /**
     * Generation counter for the background phase-two layout: bumping it
     * cancels the running task (new open, activity teardown).
     */
    private final AtomicLong phase2Gen = new AtomicLong();

    /** Stops the background phase-two layout of this book, if running. */
    public void cancelPhase2() {
        phase2Gen.incrementAndGet();
    }

    /** Alias used when the reader goes to the background. */
    public void pausePhase2() {
        phase2Gen.incrementAndGet();
    }

    /** Continues a paused phase-two layout when the reader becomes visible again. */
    public void resumePhase2() {
        if (progressiveLoad && documentModel != null) {
            startPhaseTwoLayout(documentModel);
        }
    }

    public void startDecoding(final String fileName, final String password) {
        // A new load invalidates any still-running phase-two of a previous book.
        phase2Gen.incrementAndGet();
        getManagedComponent().view.getView()
                                  .post(new BookLoadTask(fileName, password, new Runnable() {

                                      @Override public void run() {

                                          intent.putExtra(HorizontalModeController.EXTRA_PASSWORD, password);

                                          if (onBookLoaded != null) {
                                              onBookLoaded.run();
                                          }

                                          pageCount = controller.getPageCount();



                                          float percent =
                                                  Intents.getFloatAndClear(intent, DocumentController.EXTRA_PERCENT);

                                          if (percent > 0f) {
                                              LOG.d("startDecoding-onGoToPage", percent, pageCount);
                                              controller.onGoToPage(Math.round(pageCount * percent));

                                          }

                                          if (progressiveLoad) {
                                              // deferred: loaded when phase-two finishes
                                              // (getOutline would force the full layout now)
                                          } else {
                                              loadOutlineOnce();
                                          }

                                      }
                                  }));
    }

    /** Loads the TOC once; later calls are no-ops. */
    public void loadOutlineOnce() {
        if (outlineLoaded) {
            return;
        }
        outlineLoaded = true;
        controller.loadOutline(new ResultResponse<List<OutlineLinkWrapper>>() {

            @Override public boolean onResultRecive(List<OutlineLinkWrapper> result) {
                wrapperControlls.showOutline(result, controller.getPageCount());

                return false;
            }
        });
    }

    public void onPause() {
        if (wrapperControlls != null) {
            wrapperControlls.onPause();
        }
        // Cover exits that skip closeActivityFinal (Home key, swipe from
        // recents): the document is still alive here, so the current position
        // can be flushed as-is.
        try {
            if (controller != null) {
                controller.saveCurrentPageNow();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public void onDestroy() {
        phase2Gen.incrementAndGet();
        progressiveLoad = false;
        FirstPaintGate.cancel();
        if (wrapperControlls != null) {
            wrapperControlls.onDestroy();
        }
        LOG.d("ViewerActivityController onDestroy");
    }

    public void beforeDestroy() {
        // Stop any running phase-two layout before the document handle is
        // recycled below (it would otherwise keep the native lock busy).
        phase2Gen.incrementAndGet();
        final boolean finishing = getManagedComponent().isFinishing();
        if (finishing) {
            // unsubscribe the gesture detector from the process-wide EventBus:
            // switchDocumentController (the only other destroyGestures call
            // site) never runs on a normal close, so every Back exit used to
            // leak the whole activity view graph
            final IViewController cur = ctrl.get();
            if (cur instanceof org.ebookdroid.core.AbstractViewController) {
                ((org.ebookdroid.core.AbstractViewController) cur).destroyGestures();
            }
            getManagedComponent().view.onDestroy();
            if (documentModel != null) {
                documentModel.recycle();
            }
            SettingsManager.removeListener(this);
        }
        LOG.d("ViewerActivityController beforeDestroy");

    }

    public void afterDestroy(boolean finishing) {
        getDocumentController().onDestroy();
        LOG.d("ViewerActivityController afterDestroy");
    }

    public void askPassword(final String fileName, final int promtId) {
        final EditText input = new EditText(getManagedComponent());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        AlertDialog.Builder dialog = new AlertDialog.Builder(getManagedComponent());
        dialog.setTitle(R.string.enter_password);
        dialog.setView(input);
        dialog.setCancelable(false);
        dialog.setNegativeButton(R.string.cancel, new OnClickListener() {

            @Override public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                controller.onCloseActivityAdnShowInterstial();
            }
        });
        dialog.setPositiveButton(R.string.open_file, new OnClickListener() {

            @Override public void onClick(DialogInterface dialog, int which) {
                String txt = input.getText()
                                  .toString();
                if (TxtUtils.isNotEmpty(txt)) {
                    dialog.dismiss();
                    startDecoding(fileName, input.getText()
                                                 .toString());
                } else {
                    controller.onCloseActivityAdnShowInterstial();
                }
            }
        });
        dialog.show();

        //
    }

    public void showErrorDlg(final int msgId, final Object... args) {
        Toast.makeText(getManagedComponent(), msgId, Toast.LENGTH_SHORT)
             .show();
    }

    protected IViewController switchDocumentController(final AppBook bs) {
        if (bs != null) {
            try {
                final IViewController newDc = DocumentViewMode.VERTICALL_SCROLL.create(this);
                if (newDc != null) {
                    final IViewController oldDc = ctrl.getAndSet(newDc);
                    if (oldDc instanceof org.ebookdroid.core.AbstractViewController) {
                        // unsubscribe the retired controller's EventBus
                        // listener (leak + ghost text-selection events)
                        ((org.ebookdroid.core.AbstractViewController) oldDc).destroyGestures();
                    }
                    getZoomModel().removeListener(oldDc);
                    getZoomModel().addListener(newDc);
                    return ctrl.get();
                }
            } catch (final Throwable e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override public void decodingProgressChanged(final int currentlyDecoding) {
    }

    @Override public void currentPageChanged(final int page, int pages) {
        // reading-speed stat: fires exactly once per page index change in
        // scroll mode; the initial restore happens before onResume so the
        // session guard inside onFlip() filters it out
        ReadingStats.onFlip();
        final int pageCount = documentModel.getPageCount();
        String pageText = "";
        if (pageCount > 0) {
            pageText = (page + 1) + "/" + pageCount;
        }
        android.util.Log.i("REMOTE", "page now " + (page + 1) + "/" + pageCount);

        wrapperControlls.updateUI();

        wrapperControlls.setTitle(title);
        controller.setTitle(title);

    }

    public void createWrapper(AdsFragmentActivity a) {
        try {
            String file = Apps.getBookPathFromActivity(a);



            LOG.d("createWrapper", file);
            if (ExtUtils.isTextFomat(file)) {
                AppSP.get().isLocked = true;
            } else {
                if (AppState.get().isLockPDF) {
                    AppSP.get().isLocked = true;
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }

        wrapperControlls.initUI(a);
    }

    public void onResume() {
        if (controller != null) {
            controller.onResume();
        }
        if (wrapperControlls != null) {
            wrapperControlls.onResume();
        }
    }

    public void onConfigChanged() {
        wrapperControlls.onConfigChanged();
    }

    @Override
    public ViewState jumpToPage(final int viewIndex, final float offsetX, final float offsetY, boolean addToHistory) {
        // getDocumentController().goToPage(viewIndex, x, y);
        ViewState goToPage;
        if (addToHistory) {
            int curY = getDocumentController().getView()
                                              .getScrollY();
            goToPage = getDocumentController().goToPage(viewIndex);
            controller.getLinkHistory()
                      .add(curY);
            wrapperControlls.showHideHistory();
        } else {
            // getDocumentController().goToPage(viewIndex, x, y);
            goToPage = getDocumentController().goToPage(viewIndex);
        }
        return goToPage;
    }

    public final void doSearch(final String text, final ResultResponse<Integer> result, int firstPage, int lastPage) {
        getDecodeService().searchText(text, documentModel.getPages(), result, new Runnable() {

            @Override public void run() {
                getView().redrawView();
            }
        }, firstPage, lastPage);
    }

    public void showDialog(final ActionEx action) {
        final Integer dialogId = action.getParameter("dialogId");
        getManagedComponent().showDialog(dialogId);

    }

    public void toggleNightMode() {
        getDocumentController().toggleRenderingEffects();
        currentPageChanged(documentModel.getCurrentIndex().docIndex, getDocumentController().getBase()
                                                                                            .getDocumentModel()
                                                                                            .getPageCount());
    }

    public void toggleCrop(boolean isCrop) {
        getDocumentController().toggleRenderingEffects();

        final IViewController newDc = switchDocumentController(SettingsManager.getBookSettings());
        newDc.init(null);
        newDc.show();

        currentPageChanged(documentModel.getCurrentIndex().docIndex, getDocumentController().getBase()
                                                                                            .getDocumentModel()
                                                                                            .getPageCount());

    }

    /**
     * Gets the z model.
     *
     * @return the z model
     */
    @Override public ZoomModel getZoomModel() {
        if (zoomModel == null) {
            zoomModel = new ZoomModel();
        }
        return zoomModel;
    }

    @Override public DecodeService getDecodeService() {
        return documentModel != null ? documentModel.decodeService : null;
    }

    /**
     * Gets the decoding progress model.
     *
     * @return the decoding progress model
     */

    @Override public DocumentModel getDocumentModel() {
        return documentModel;
    }

    @Override public IViewController getDocumentController() {
        return ctrl.get();
    }

    @Override public Context getContext() {
        return getManagedComponent();
    }

    @Override public IView getView() {
        return getManagedComponent().view;
    }

    @Override public Activity getActivity() {
        return getManagedComponent();
    }

    @Override public IActionController<?> getActionController() {
        return this;
    }

    public void closeActivity(final ActionEx action) {
        viewerActivity.showInterstitial();
        LOG.d("ViewerActivityController closeActivity");
    }

    public void closeActivityFinal(final Runnable action) {
        // fail in-flight remote reads now so documentModel.recycle() below
        // (running on the UI thread) never blocks on a stuck network read
        // holding the global native lock
        if (m_fileName != null && com.foobnix.remote.RemoteBook.isRemotePathLoose(m_fileName)) {
            com.foobnix.remote.RemoteSessionFactory.abortSession(m_fileName);
        }
        dismissRemoteSlowBanner();

        Safe.run(new Runnable() {

            @Override public void run() {

                TTSEngine.get()
                         .stop(null);
                TTSNotification.hideNotification();

                LOG.d("closeActivity 1");
                // Persist the last read position BEFORE the document is
                // recycled: after recycle() the page-count guard in
                // saveCurrentPageAsync drops the save and the book would
                // reopen one session behind.
                try {
                    if (controller != null) {
                        controller.saveCurrentPageNow();
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }
                if (documentModel != null) {
                    final long recycleT0 = android.os.SystemClock.elapsedRealtime();
                    documentModel.recycle();
                    final long recycleMs = android.os.SystemClock.elapsedRealtime() - recycleT0;
                    if (recycleMs > 500) {
                        android.util.Log.i("REMOTE", "codec recycle blocked UI " + recycleMs + "ms");
                    }
                }

                LOG.d("closeActivity 2");
                LOG.d("closeActivity 3");
                getManagedComponent().finish();

                System.gc();
                //BitmapManager.clear("finish");

                if (action != null) {
                    action.run();
                }
            }
        });

        LOG.d("closeActivity DONE");
    }

    // ---- remote slow-first-paint banner (vertical reader) ----
    private android.widget.LinearLayout remoteSlowBanner;
    private final android.os.Handler bannerUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable bannerTick;

    /**
     * The loading dialog lifted but the first page still has no bitmap (slow
     * network / sleeping NAS): keep informing the user — the cached-MB
     * counter keeps growing — and offer the proven whole-book-download
     * escape, instead of a silent blank page. The banner removes itself as
     * soon as the first page bitmap arrives.
     */
    private void showRemoteSlowBanner() {
        final VerticalViewActivity a = getManagedComponent();
        if (a == null || a.isFinishing() || !com.foobnix.remote.RemoteBook.isRemotePath(m_fileName)
                || remoteSlowBanner != null) {
            return;
        }
        android.util.Log.i("REMOTE", "slow first paint, showing progress banner: " + m_fileName);
        final android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6171717);
        final int pad = (int) (8 * a.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad);

        final android.widget.TextView msg = new android.widget.TextView(a);
        msg.setTextColor(0xFFFFFFFF);
        msg.setTextSize(13f);
        final android.widget.Button dl = new android.widget.Button(a);
        dl.setText(com.foobnix.pdf.info.R.string.remote_download_and_open);
        dl.setOnClickListener(v -> {
            android.util.Log.i("REMOTE", "banner: user chose full download: " + m_fileName);
            dismissRemoteSlowBanner();
            com.foobnix.remote.RemoteBookOpener.fetchToCache(a, m_fileName, 0, target -> {
                com.foobnix.pdf.info.ExtUtils.openFile(a,
                        com.foobnix.ui2.AppDB.get().getOrCreate(target.getPath()));
                a.finish();
            });
        });
        final android.widget.TextView close = new android.widget.TextView(a);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setPadding(pad, pad, pad, pad);
        close.setOnClickListener(v -> dismissRemoteSlowBanner());

        bar.addView(msg, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(dl);
        bar.addView(close);
        a.addContentView(bar, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        remoteSlowBanner = bar;

        final long bannerT0 = android.os.SystemClock.elapsedRealtime();
        bannerTick = new Runnable() {
            @Override public void run() {
                if (remoteSlowBanner == null || a.isFinishing()) {
                    return;
                }
                if (com.foobnix.sys.FirstPaintGate.hasFirstBitmap()) {
                    android.util.Log.i("REMOTE", "slow-paint banner: first bitmap arrived, hide");
                    dismissRemoteSlowBanner();
                    return;
                }
                try {
                    final int pct = com.foobnix.remote.BlockCacheStore.cachedPercent(m_fileName);
                    String text;
                    if (pct >= 0) {
                        final long size = com.foobnix.remote.BlockCacheStore.peekSize(m_fileName);
                        text = size > 0
                                ? a.getString(com.foobnix.pdf.info.R.string.remote_open_progress, pct,
                                        com.foobnix.remote.RemoteBookOpener.fmtMB(size * pct / 100),
                                        com.foobnix.remote.RemoteBookOpener.fmtMB(size))
                                : a.getString(com.foobnix.pdf.info.R.string.remote_open_progress_nosize, pct);
                    } else {
                        final long sec = (android.os.SystemClock.elapsedRealtime() - bannerT0) / 1000;
                        text = a.getString(com.foobnix.pdf.info.R.string.remote_open_waiting, sec);
                    }
                    text += "\n" + a.getString(com.foobnix.pdf.info.R.string.remote_loading_slow);
                    msg.setText(text);
                } catch (Throwable t) {
                    LOG.e(t);
                }
                bannerUi.postDelayed(this, 600);
            }
        };
        bannerUi.postDelayed(bannerTick, 600);
    }

    private void dismissRemoteSlowBanner() {
        bannerUi.removeCallbacks(bannerTick);
        bannerTick = null;
        final android.widget.LinearLayout bar = remoteSlowBanner;
        remoteSlowBanner = null;
        if (bar != null) {
            try {
                ((android.view.ViewGroup) bar.getParent()).removeView(bar);
            } catch (Throwable t) {
                LOG.e(t);
            }
        }
    }

    public void closeActivity1(final ActionEx action) {
        getManagedComponent().finish();

        System.gc();
        //BitmapManager.clear("finish");

        LOG.d("ViewerActivityController closeActivity1");
    }

    @Override
    public void onBookSettingsChanged(final AppBook oldSettings, final AppBook newSettings, final AppBook.Diff diff) {
        if (newSettings == null) {
            return;
        }

        boolean redrawn = false;
        if (diff.isSplitPagesChanged() || diff.isCropPagesChanged()) {
            redrawn = true;
            final IViewController newDc = switchDocumentController(newSettings);
            if (!diff.isFirstTime() && newDc != null) {
                newDc.init(null);
                newDc.show();
            }
        }

        if (diff.isFirstTime()) {
            getZoomModel().initZoom(newSettings.getZoom());
        }

        final IViewController dc = getDocumentController();

        if (!redrawn && (diff.isEffectsChanged())) {
            redrawn = true;
            dc.toggleRenderingEffects();
        }

        if (diff.isAnimationTypeChanged()) {
            dc.updateAnimationType();
        }

        // currentPageChanged(PageIndex.NULL, documentModel.getCurrentIndex());
        //currentPageChanged(newSettings.currentPage.do, -1);

    }

    public DocumentWrapperUI getWrapperControlls() {
        return wrapperControlls;
    }

    final class BookLoadTask extends BaseAsyncTask<String, Throwable> implements IProgressIndicator, Runnable {

        private String m_fileName;
        private final String m_password;
        private final Runnable onBookLoaded;
        private long benchT0;
        private volatile String metaTitle;

        public BookLoadTask(final String fileName, final String password, Runnable onBookLoaded) {
            super(getManagedComponent());
            m_fileName = fileName;
            m_password = password;
            this.onBookLoaded = onBookLoaded;
        }

        @Override public void run() {
            // Parallel executor: the process-wide serial queue could otherwise
            // delay the load behind unrelated background tasks. Native access
            // stays serialized by TempHolder.lock.
            executeOnExecutor(CopyAsyncTask.THREAD_POOL_EXECUTOR);
        }

        @Override public void onBookCancel() {
            super.onBookCancel();
            LOG.d("onBookCancel");
            closeActivity(null);
        }

        @Override protected void onPreExecute() {
            if (!silentReload) {
                // loading dialog + FirstPaintGate hold are only for real opens
                super.onPreExecute();
            }
            benchT0 = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i("BENCH", "load-begin silent=" + silentReload);
        }

        @Override protected Throwable doInBackground(final String... params) {
            try {
                //Thread.sleep(3000);
                m_fileName = Apps.getBookPathFromActivity(getActivity());
                android.util.Log.i("REMOTE", "openTask file=" + m_fileName + " model=" + documentModel);
                startRemoteProgress();

                // Full metadata extraction + hyphenation language detection,
                // both potentially O(file), run here on the background thread
                // (they used to run on the main thread in onCreate/afterCreate).
                try {
                    FileMeta meta = FileMetaCore.createMetaIfNeed(m_fileName, false);
                    if (meta != null) {
                        metaTitle = meta.getTitle();
                    }
                    BookCSS.get().detectLang(m_fileName);
                } catch (Throwable e) {
                    LOG.e(e);
                }

                android.util.Log.i("REMOTE", "calling documentModel.open");
                // fresh load: wipe a cancel flag left by a previous book
                TempHolder.get().loadingCancelled.set(false);
                // userOpenEnd runs in the task's finally: the page-size loop
                // AFTER open needs the priority window just as much
                com.foobnix.remote.RemoteSessionFactory.userOpenStart(m_fileName);
                documentModel.open(m_fileName, m_password);
                android.util.Log.i("BENCH", "doc-open-done " + (android.os.SystemClock.elapsedRealtime() - benchT0) + "ms");

                // Fast-open (two-phase layout): lay out only up to the saved
                // reading position (or the first pages of a fresh book) so the
                // first screen appears without the full-document layout; the
                // remaining chapters are laid out in the background afterwards.
                int uptoPage = -1;
                // Remote books always take the fast-open path (even with the
                // global switch off): a 200-300MB book must show its first
                // screen after only a few pages are laid out, with the rest
                // of the layout continuing in the background.
                final boolean remoteBook = com.foobnix.remote.RemoteBook.isRemotePath(m_fileName);
                if ((remoteBook || AppState.get().isFastOpen) && ExtUtils.isTextFomat(m_fileName)
                        && (intent == null || intent.getStringExtra(DocumentController.EXTRA_PERCENT) == null)) {
                    final AppBook bs = SettingsManager.getBookSettings();
                    if (bs != null) {
                        if (bs.pg >= 0) {
                            // remote: tiny first window (saved page + a few)
                            // so the first screen appears fast; the rest of
                            // the book is laid out and cached in the
                            // background (two-phase layout)
                            uptoPage = remoteBook ? bs.pg + Math.max(3, Math.min(10, bs.pg / 4))
                                    : bs.pg + Math.max(80, bs.pg / 4);
                        } else {
                            // Progress saved by an older version: estimate the
                            // target page from the library page count.
                            int dbPages = 0;
                            try {
                                final FileMeta meta = AppDB.get().load(m_fileName);
                                if (meta != null) {
                                    dbPages = meta.getPages();
                                }
                            } catch (Throwable t) {
                                LOG.e(t);
                            }
                            if (dbPages > 0 && bs.p > 0f) {
                                final int target = Math.round(dbPages * bs.p);
                                uptoPage = remoteBook ? target + 4
                                        : target + Math.max(120, target / 4);
                            } else if (bs.p <= 0f) {
                                uptoPage = remoteBook ? 4 : 150;
                            }
                        }
                    }
                }
                if (uptoPage > 0) {
                    progressiveLoad = true;
                    documentModel.setProgressiveUpto(uptoPage);
                }

                getDocumentController().init(this);
                return null;
            } catch (final MuPdfPasswordException pex) {
                return pex;
            } catch (final Exception e) {
                CacheZipUtils.createAllCacheDirs();
                LOG.e(e);
                return e;
            } catch (final Throwable th) {
                LOG.e(th);
                return th;
            } finally {
                com.foobnix.remote.RemoteSessionFactory.userOpenEnd();
            }
        }

        @Override protected void onPostExecute(Throwable result) {
            stopRemoteProgress();
            try {
                LOG.d("onPostExecute");
                android.util.Log.i("BENCH", "load-end " + (android.os.SystemClock.elapsedRealtime() - benchT0) + "ms");
                com.foobnix.remote.RemoteTimeline.mark("reader load task done (vertical)");
                if (TempHolder.get().loadingCancelled.get()) {
                    android.util.Log.i("REMOTE", "load cancelled-gate trips, silent close: " + m_fileName);
                    super.onPostExecute(result);
                    closeActivity(null);
                    return;
                }

                wrapperControlls.onLoadBookFinish();
                if (result == null && m_fileName != null
                        && com.foobnix.remote.RemoteBook.isRemotePathLoose(m_fileName)) {
                    // persist a real cover while the document is open and
                    // the current page is cached (local-only work)
                    final AppBook bsC = SettingsManager.getBookSettings();
                    final int coverPage = bsC == null ? 0 : Math.max(0, bsC.pg - 1);
                    com.foobnix.sys.ImageExtractor.maybeSaveRemoteCover(m_fileName, coverPage);
                }
                if (result == null) {
                    try {
                        // The real title (extracted on the background thread)
                        // replaces the filename placeholder used before load.
                        if (metaTitle != null && !metaTitle.equals(title)) {
                            title = metaTitle;
                            wrapperControlls.setTitle(title);
                        }
                        getDocumentController().show();

                        final DocumentModel dm = getDocumentModel();
                        currentPageChanged(dm.getCurrentIndex().docIndex, -1);

                        if (silentReload && TxtUtils.isNotEmpty(bilingualAnchorMd5)) {
                            // re-land on the same content as before the rebuild;
                            // the text layer may not be ready right after load in
                            // vertical fast-open, so retry shortly afterwards and
                            // only jump while the reader still sits on that page
                            final String anchor = bilingualAnchorMd5;
                            final int approx = bilingualAnchorPage;
                            getManagedComponent().view.getView().postDelayed(new Runnable() {
                                @Override public void run() {
                                    try {
                                        int anchor0 = BilingualSession.locateAnchorPage(controller, anchor, approx);
                                        android.util.Log.i("BENCH", "BilingualSilentReload anchorPage0=" + anchor0
                                                + " approx0=" + approx);
                                        if (anchor0 >= 0 && controller.getCurentPageFirst1() - 1 == approx) {
                                            controller.onGoToPage(anchor0 + 1);
                                        }
                                    } catch (Throwable t) {
                                        LOG.e(t);
                                    }
                                }
                            }, 600);
                        }

                        onBookLoaded.run();

                        if (progressiveLoad) {
                            startPhaseTwoLayout(dm);
                        }

                        if (!silentReload) {
                            // keep the loading dialog up until the first page
                            // bitmap is decoded, so no blank page flashes
                            holdProgressDialog = true;
                            if (com.foobnix.remote.RemoteBook.isRemotePath(m_fileName)) {
                                // the first screen crosses the network: lift the
                                // dialog after a short beat so the reader shell
                                // shows immediately (horizontal-parity), and let
                                // the slow-paint banner carry the cached-MB
                                // progress + download escape until it paints
                                FirstPaintGate.arm(progressDialog,
                                        com.foobnix.sys.FirstPaintGate.REMOTE_HARD_CAP_MS);
                                FirstPaintGate.setOnRelease(decoded -> {
                                    if (!decoded) {
                                        showRemoteSlowBanner();
                                    }
                                });
                            } else {
                                FirstPaintGate.arm(progressDialog);
                            }
                        }

                    } catch (final Throwable th) {
                        result = th;
                    }
                }

                super.onPostExecute(result);
                if (result instanceof MuPdfPasswordException) {
                    final MuPdfPasswordException pex = (MuPdfPasswordException) result;
                    final int promptId =
                            pex.isWrongPasswordEntered() ? R.string.msg_wrong_password : R.string.msg_password_required;

                    askPassword(m_fileName, promptId);

                } else if (result != null) {
                    if (com.foobnix.remote.RemoteBook.isRemotePath(m_fileName)) {
                        android.util.Log.i("REMOTE", "remote decode failed: " + result, result);
                        // remote book failed to decode (DRM / corrupt / engine
                        // error): offer the download fallback instead of the
                        // plain error dialog
                        final android.app.AlertDialog fallback = new android.app.AlertDialog.Builder(getActivity())
                                .setTitle(R.string.remote_open_failed)
                                .setMessage(getActivity().getString(R.string.remote_open_failed_msg,
                                        result.getMessage() == null ? "" : result.getMessage()))
                                .setPositiveButton(R.string.remote_download_and_open, (d, w) ->
                                        com.foobnix.remote.RemoteBookOpener.downloadAndOpen(getActivity(), m_fileName, 0))
                                .setNegativeButton(android.R.string.cancel, null)
                                .create();
                        fallback.setOnDismissListener(d -> closeActivity(null));
                        fallback.show();
                    } else {
                        final String msg = result.getMessage();
                        showErrorDlg(R.string.msg_unexpected_error, msg);
                    }
                }
            } catch (final Throwable th) {
                LOG.e(th);
            }

        }

        @Override public void setProgressDialogMessage(final int resourceID, final Object... args) {
            publishProgress(getManagedComponent().getString(resourceID, args));
        }

        /**
         * Remote books: the generic "loading" spinner gives no clue how far
         * the open is. While the document loads, poll the block cache and
         * show the cached share of the book directly in the loading dialog.
         * Stops itself once the dialog is dismissed; onPostExecute cancels
         * it explicitly.
         */
        private final android.os.Handler remoteUi = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable remoteTick;
        private long remoteStartMs;

        private void startRemoteProgress() {
            if (!com.foobnix.remote.RemoteBook.isRemotePath(m_fileName)) {
                return;
            }
            remoteStartMs = android.os.SystemClock.elapsedRealtime();
            remoteTick = new Runnable() {
                @Override public void run() {
                    final android.app.AlertDialog d = progressDialog;
                    if (d == null || !d.isShowing()) {
                        return;
                    }
                    try {
                        final android.widget.TextView msg =
                                (android.widget.TextView) d.findViewById(com.foobnix.pdf.info.R.id.text1);
                        if (msg != null) {
                            final int pct = com.foobnix.remote.BlockCacheStore.cachedPercent(m_fileName);
                            if (pct >= 0) {
                                final long size = com.foobnix.remote.BlockCacheStore.peekSize(m_fileName);
                                String text = size > 0
                                        ? getActivity().getString(
                                                com.foobnix.pdf.info.R.string.remote_open_progress, pct,
                                                com.foobnix.remote.RemoteBookOpener.fmtMB(size * pct / 100),
                                                com.foobnix.remote.RemoteBookOpener.fmtMB(size))
                                        : getActivity().getString(
                                                com.foobnix.pdf.info.R.string.remote_open_progress_nosize, pct);
                                if (pct < 100 && android.os.SystemClock.elapsedRealtime() - remoteStartMs > 30000) {
                                    text += "\n" + getActivity().getString(
                                            com.foobnix.pdf.info.R.string.remote_loading_slow);
                                }
                                msg.setText(text);
                            }
                        }
                    } catch (Throwable t) {
                        LOG.e(t);
                    }
                    remoteUi.postDelayed(this, 600);
                }
            };
            remoteUi.postDelayed(remoteTick, 600);
        }

        private void stopRemoteProgress() {
            if (remoteTick != null) {
                remoteUi.removeCallbacks(remoteTick);
                remoteTick = null;
            }
        }
    }

    /**
     * Fast-open phase two: finishes the full-document layout in the
     * background and grows the page canvas in place. Pages are appended at
     * the tail, so the current reading position stays visually stable; only
     * the page counters and the progress bar widen afterwards.
     * <p>
     * The layout is advanced in bounded chunks (~400 pages per native call):
     * between chunks the global native lock is released, so page-turn
     * decoding, opening other books and activity teardown never queue behind
     * a long full-document count. Bumping {@link #phase2Gen} cancels it.
     */
    private void startPhaseTwoLayout(final DocumentModel dm) {
        final long gen = phase2Gen.get();
        final int knownCount = dm.getPageCount();
        if (knownCount <= 0) {
            // nothing to grow: load the TOC right away
            loadOutlineOnce();
            return;
        }
        final long t0 = android.os.SystemClock.elapsedRealtime();
        android.util.Log.i("BENCH", "phase2-begin n1=" + knownCount);
        // dedicated thread: the shared 2-thread AppsConfig pool must stay
        // free for the decode consumer and other UI services
        new Thread(new Runnable() {
            @Override
            public void run() {
                // slightly below foreground priority, but NOT the background
                // cgroup (THREAD_PRIORITY_BACKGROUND) which MIUI throttles hard
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                try {
                    // Let the first-screen decode grab the native lock first,
                    // so content is on screen before we start filling in.
                    Thread.sleep(2000);

                    final android.app.Activity act = getActivity();
                    int total = knownCount;
                    int requested = knownCount + 400;
                    while (phase2Gen.get() == gen && act != null && !act.isDestroyed() && !act.isFinishing()) {
                        // Yield to UI/decode threads waiting on the global
                        // native lock: without this the non-fair lock can be
                        // re-acquired by this loop faster than a blocked main
                        // thread wakes up, starving input for the whole run.
                        int yield = 0;
                        while (phase2Gen.get() == gen && TempHolder.lock.hasQueuedThreads() && yield++ < 50) {
                            Thread.sleep(100);
                        }
                        if (phase2Gen.get() != gen) {
                            android.util.Log.i("BENCH", "phase2-cancelled at " + total);
                            return;
                        }
                        final int n = dm.decodeService.getPageCountProgressive(requested);
                        if (phase2Gen.get() != gen || act.isDestroyed() || act.isFinishing()) {
                            android.util.Log.i("BENCH", "phase2-cancelled at " + total);
                            return;
                        }
                        if (n <= total) {
                            break; // no growth (also covers recycled/failed: 0)
                        }
                        total = n;
                        if (n < requested) {
                            break; // document end reached
                        }
                        requested = total + 400;
                    }
                    android.util.Log.i("BENCH", "phase2-end n2=" + total + " "
                            + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                    if (phase2Gen.get() != gen || total <= knownCount || getActivity() == null) {
                        return;
                    }
                    final int fullCount = total;
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (TempHolder.get().loadingCancelled.get()) {
                                    return;
                                }
                                // Marker = last page of the first phase; it
                                // re-stacks itself plus every appended page.
                                // (Fetching the first NEW page here would
                                // return null: the array is not grown yet.)
                                final Page marker = dm.getPageObject(Math.max(0, knownCount - 1));
                                if (dm.appendPages(fullCount) && marker != null) {
                                    progressiveLoad = false;
                                    getDocumentController().invalidatePageSizes(
                                            IViewController.InvalidateSizeReason.PAGE_LOADED, marker);
                                    currentPageChanged(dm.getCurrentIndex().docIndex, -1);
                                    wrapperControlls.refreshPageCount();
                                }
                                // full layout is done: the TOC load is cheap now
                                loadOutlineOnce();
                            } catch (Throwable e) {
                                LOG.e(e);
                            }
                        }
                    });
                } catch (Throwable e) {
                    LOG.e(e);
                }
            }
        }, "@T phase2").start();
    }

}
