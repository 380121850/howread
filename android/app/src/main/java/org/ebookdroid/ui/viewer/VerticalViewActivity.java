package org.ebookdroid.ui.viewer;

import static com.foobnix.pdf.info.ExtUtils.finishOtherViewer;

import android.app.ActionBar.LayoutParams;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.foobnix.ai.BilingualHintUi;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.Intents;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.ReadingStats;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.PasswordDialog;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.view.BrightnessHelper;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.search.activity.HorizontalViewActivity;
import com.foobnix.pdf.search.activity.ViewBinder;
import com.foobnix.pdf.search.view.CloseAppDialog;
import com.foobnix.sys.FirstPaintGate;
import com.foobnix.sys.TempHolder;
import com.foobnix.tts.TTSNotification;
import com.foobnix.ui2.FileMetaCore;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.MyContextWrapper;

import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.ui.viewer.viewers.PdfSurfaceView;
import org.emdev.ui.AbstractActionActivity;

public class VerticalViewActivity extends AbstractActionActivity<VerticalViewActivity, ViewerActivityController> implements BilingualHintUi {
    public static final DisplayMetrics DM = new DisplayMetrics();

    IView view;

    private FrameLayout frameLayout;

    private TextView aiTranHint;

    /**
     * Instantiates a new base viewer activity.
     */
    public VerticalViewActivity() {
        super();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        finishOtherViewer(this, HorizontalViewActivity.class);
        LOG.d("VerticalViewActivity", "onNewIntent");
        if (TTSNotification.ACTION_TTS.equals(intent.getAction())) {
            return;
        }
        if (!intent.filterEquals(getIntent())) {
            finish();
            startActivity(intent);
        }

    }
    @Override
    public void onRewardLoaded() {
        super.onRewardLoaded();
        ViewBinder.hideShowRewardButton(this,findViewById(R.id.showRewardVideo));
    }

    @Override
    public void setBilingualHint(String text) {
        if (aiTranHint == null) {
            return;
        }
        if (text == null || text.length() == 0) {
            aiTranHint.setVisibility(View.GONE);
        } else {
            aiTranHint.setText(text);
            aiTranHint.setVisibility(View.VISIBLE);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see org.emdev.ui.AbstractActionActivity#createController()
     */
    @Override
    protected ViewerActivityController createController() {
        return new ViewerActivityController(this);
    }

    private Handler handler;

    /**
     * Called when the activity is first created.
     */

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        AppsConfig.ensureMuPdfLoaded();
        TempHolder.readerActive = true;
        finishOtherViewer(this, HorizontalViewActivity.class);
        DocumentController.doRotation(this);
        DocumentController.doContextMenu(this);

        // Metadata extraction moved to BookLoadTask (background): doing it on
        // the main thread here froze the UI for the full parse of unscanned
        // books before the loading dialog could appear.

        if (getIntent().getData() != null) {
            String path = getIntent().getData().getPath();
            final AppBook bs = SettingsManager.getBookSettings(path);
            // AppState.get().setNextScreen(bs.isNextScreen);
            if (bs != null) {
                // AppState.get().l = bs.l;
                AppState.get().autoScrollSpeed = bs.s;
                final boolean isTextFormat = ExtUtils.isTextFomat(bs.path);
                AppSP.get().isCut = isTextFormat ? false : bs.sp; //important!!!
                AppSP.get().isCrop = bs.cp;
                AppSP.get().isDouble = false;
                AppSP.get().isDoubleCoverAlone = false;
                AppSP.get().isLocked = bs.getLock(isTextFormat, AppState.get().lockBooksByDefault);
                TempHolder.get().pageDelta = bs.d;
                if (AppState.get().isCropPDF && !AppSP.get().isCut && !isTextFormat) {
                    AppSP.get().isCrop = true;
                }
            }

        }

        getController().beforeCreate(this);

        BrightnessHelper.applyBrigtness(this);

        if (AppState.get().isDayNotInvert) {
            setTheme(R.style.StyledIndicatorsWhite);
        } else {
            setTheme(R.style.StyledIndicatorsBlack);
        }
        super.onCreate(savedInstanceState);

        //FirebaseAnalytics.getInstance(this);

        if (PasswordDialog.isNeedPasswordDialog(this)) {
            return;
        }
        setContentView(R.layout.activity_vertical_view);
        DocumentController.applyEdgeToEdge(this);

        // the shared footer icon row keeps the tight XML margins for the
        // horizontal mode (its 10 icons already fill the width there); the
        // vertical mode has room for the wider right-aligned spacing
        spaceFooterIcons();

//        if (!Android6.canWrite(this)) {
//            Android6.checkPermissions(this, true);
//            return;
//        }

        getController().createWrapper(this);
        frameLayout = (FrameLayout) findViewById(R.id.documentView);

        aiTranHint = (TextView) findViewById(R.id.aiTranHint);

        view = new PdfSurfaceView(getController());

        frameLayout.addView(view.getView());

        getController().afterCreate(this);
        android.util.Log.i("BENCH", "vv-onCreate done");

        // ADS.activate(this, adView);

        handler = new Handler(Looper.getMainLooper());

        getController().onBookLoaded(new Runnable() {

            @Override
            public void run() {
                handler.postDelayed(new Runnable() {

                    @Override
                    public void run() {
                        isInitPosition = Dips.screenHeight() > Dips.screenWidth();
                        isInitOrientation = AppState.get().orientation;
                    }
                }, 1000);

            }
        });

    }

    /** Widen the inter-icon gap of the footer icon row (vertical mode only). */
    private void spaceFooterIcons() {
        ViewGroup row = (ViewGroup) findViewById(R.id.footerIconRow);
        if (row == null) {
            return;
        }
        int gap = Dips.dpToPx(4);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.leftMargin = gap;
            lp.rightMargin = gap;
            child.setLayoutParams(lp);
        }
    }

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(MyContextWrapper.wrap(context));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Android6.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReadingStats.onResume();
        DocumentController.doRotation(this);

        if (AppState.get().fullScreenMode == AppState.FULL_SCREEN_FULLSCREEN) {
            Keyboards.hideNavigation(this);
        }
        getController().onResume();
        if (handler != null) {
            handler.removeCallbacks(closeRunnable);
        }
        if (AppState.get().inactivityTime != -1) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            LOG.d("FLAG clearFlags", "FLAG_KEEP_SCREEN_ON", "add", AppState.get().inactivityTime);
        }

        if (handler != null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (view != null) {
                        view.redrawView();
                    }
                }
            }, 50);
        }

    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            int
                    page =
                    Math.round(getController().getDocumentModel().getPageCount() * Intents.getFloatAndClear(data,
                            DocumentController.EXTRA_PERCENT));
            getController().getDocumentController().goToPage(page);
        }
    }

    boolean needToRestore = false;

    @Override
    protected void onPause() {
        super.onPause();
        LOG.d("onPause", this.getClass());
        ReadingStats.onPause();
        getController().onPause();
        needToRestore = AppState.get().isAutoScroll;
        AppState.get().isAutoScroll = false;
        AppProfile.save(this);
        TempHolder.isSeaching = false;
        TempHolder.isActiveSpeedRead.set(false);

        if (handler != null) {
          //  handler.postDelayed(closeRunnable, AppState.APP_CLOSE_AUTOMATIC);
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        android.util.Log.i("BENCH", "VV onStart");
        // Analytics.onStart(this);
        try {
            getController().getDocumentModel().decodeService.restore();
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            getController().resumePhase2();
        } catch (Exception e) {
            LOG.e(e);
        }

        if (needToRestore) {
            AppState.get().isAutoScroll = true;
            getController().getListener().onAutoScroll();
        }

    }

    @Override
    protected void onStop() {
        android.util.Log.i("BENCH", "VV onStop");
        try {
            // Pause the background phase-two layout while the reader is not
            // visible (resumed in onStart); it would otherwise keep the
            // global native lock busy with nobody watching.
            getController().pausePhase2();
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            getController().getDocumentModel().decodeService.shutdown();
        } catch (Exception e) {
            LOG.e(e);
        }
        super.onStop();
    }

    Runnable closeRunnable = new Runnable() {

        @Override
        public void run() {
            LOG.d("Close App");
            getController().closeActivityFinal(null);
            MainTabs2.closeApp(VerticalViewActivity.this);
        }
    };

    @Override
    protected void onDestroy() {
        TempHolder.readerActive = false;
        android.util.Log.i("BENCH", "VV onDestroy");
        // leaving the reader also leaves the in-page bilingual mode, so the
        // next session starts from the base book
        com.foobnix.ai.BilingualSession.exitOnReaderDestroy(this);
        FirstPaintGate.cancel();
        try {
            getController().cancelPhase2();
        } catch (Exception e) {
            LOG.e(e);
        }
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

    }

    Dialog rotationDialog;
    Boolean isInitPosition;
    int isInitOrientation;

    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        TempHolder.isActiveSpeedRead.set(false);
        if (isInitPosition == null) {
            return;
        }

        final boolean currentPosition = Dips.screenHeight() > Dips.screenWidth();

        if (ExtUtils.isTextFomat(getIntent()) && isInitOrientation == AppState.get().orientation) {

            if (rotationDialog != null) {
                try {
                    rotationDialog.dismiss();
                } catch (Exception e) {
                    LOG.e(e);
                }
            }

            if (isInitPosition != currentPosition) {
                AlertDialog.Builder dialog = new AlertDialog.Builder(this);
                dialog.setCancelable(false);
                dialog.setMessage(R.string.apply_a_new_screen_orientation_);
                dialog.setPositiveButton(R.string.yes, new AlertDialog.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doConfigChange();
                        isInitPosition = currentPosition;
                    }
                });
                rotationDialog = dialog.show();
                rotationDialog.getWindow().setLayout((int) (Dips.screenMinWH() * 0.8f), LayoutParams.WRAP_CONTENT);

            }
        } else {
            doConfigChange();
        }

        isInitOrientation = AppState.get().orientation;
    }

    public void doConfigChange() {
        try {
            if (!getController().getDocumentController().isInitialized()) {
                LOG.d("Skip onConfigurationChanged");
                return;
            }
        } catch (Exception e) {
            LOG.e(e);
            return;
        }

        AppProfile.save(this);

        if (ExtUtils.isTextFomat(getIntent())) {

            //float value = getController().getDocumentModel().getPercentRead();
            //Intents.putFloat(getIntent(),DocumentController.EXTRA_PERCENT, value);

            //LOG.d("READ PERCEnt", value);

            getController().closeActivityFinal(new Runnable() {

                @Override
                public void run() {
                    startActivity(getIntent());
                }
            });

        } else {
            getController().onConfigChanged();

        }
    }

    @Override
    protected void onPostCreate(final Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        getController().afterPostCreate();
    }

    @Override
    public boolean onGenericMotionEvent(final MotionEvent event) {
        if (Build.VERSION.SDK_INT >= 12) {
            return GenericMotionEvent12.onGenericMotionEvent(event, this);
        }
        return false;
    }

    @Override
    public boolean onKeyLongPress(final int keyCode, final KeyEvent event) {
        // Toast.makeText(this, "onKeyLongPress", Toast.LENGTH_SHORT).show();
        if (CloseAppDialog.checkLongPress(this, event)) {
            CloseAppDialog.showOnLongClickDialog(getController().getActivity(), null, getController().getListener());
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public void onBackPressedFinishImpl() {
        //getController().closeActivityFinal(null);
        showInterstitial();
    }

    @Override
    public void onBackPressedImpl() {

        if (getController()
                .getWrapperControlls()
                .checkBack(new KeyEvent(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK))) {
            return;
        }

        try {
            if (AppState.get().isShowLongBackDialog) {
                CloseAppDialog.showOnLongClickDialog(getController().getActivity(),
                        null,
                        getController().getListener());
            } else {
                //showInterstial();
                getController().getListener().onCloseActivityAdnShowInterstial();
            }
        } catch (Exception e) {
            LOG.e(e);
        }

    }

    private volatile boolean isMyKey = false;

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent event) {
        LOG.d("onKeyUp");
        if (isMyKey) {
            return true;
        }

        if (getController().getWrapperControlls().dispatchKeyEventUp(event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    long keyTimeout = 0;

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        LOG.d("onKeyDown");
        isMyKey = false;
        int repeatCount = event.getRepeatCount();
        if (repeatCount >= 1 && repeatCount < DocumentController.REPEAT_SKIP_AMOUNT) {
            isMyKey = true;
            return true;
        }

        if (repeatCount == 0 && System.currentTimeMillis() - keyTimeout < 250) {
            LOG.d("onKeyDown timeout", System.currentTimeMillis() - keyTimeout);
            isMyKey = true;
            return true;
        }

        keyTimeout = System.currentTimeMillis();

        if (getController().getWrapperControlls().dispatchKeyEventDown(event)) {
            isMyKey = true;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onFinishActivity() {
        getController().closeActivityFinal(null);

    }

}
