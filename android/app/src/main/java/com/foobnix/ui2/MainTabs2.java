package com.foobnix.ui2;

import static com.foobnix.pdf.info.Android6.MY_PERMISSIONS_REQUEST_WES;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.drawerlayout.widget.DrawerLayout.DrawerListener;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager.widget.ViewPager.OnPageChangeListener;

import com.cloudrail.si.CloudRail;
import com.foobnix.LibreraBuildConfig;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Safe;
import com.foobnix.android.utils.StringDB;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.webdav.WebDavSyncer;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils.CacheDir;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.SlidingTabLayout;
import com.foobnix.pdf.info.ADS;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.AndroidWhatsNew;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.PasswordDialog;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.view.AboutSectionBinder;
import com.foobnix.pdf.info.view.BrightnessHelper;
import com.foobnix.pdf.info.view.Dialogs;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.pdf.search.activity.HorizontalViewActivity;
import com.foobnix.pdf.search.activity.msg.MessageSync;
import com.foobnix.pdf.search.activity.msg.MessegeBrightness;
import com.foobnix.pdf.search.activity.msg.MsgCloseMainTabs;
import com.foobnix.pdf.search.activity.msg.SearchMetaMsg;
import com.foobnix.pdf.search.view.CloseAppDialog;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.adapter.TabsAdapter2;
import com.foobnix.ui2.fragment.BookmarksFragment2;
import com.foobnix.ui2.fragment.BrowseFragment2;
import com.foobnix.ui2.fragment.DashboardFragment2;
import com.foobnix.ui2.fragment.OpdsFragment2;
import com.foobnix.ui2.fragment.PrefFragment2;
import com.foobnix.ui2.fragment.RecentFragment2;
import com.foobnix.ui2.fragment.SearchFragment2;
import com.foobnix.ui2.fragment.UIFragment;
import com.foobnix.work.SearchAllBooksWorker;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.ebookdroid.ui.viewer.VerticalViewActivity;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import mobi.librera.libgooglepro.RefiewForm;

@SuppressLint("NewApi")
public class MainTabs2 extends AdsFragmentActivity {
    public static final int REQUEST_CODE_ADD_RESOURCE = 123;
    public static final String EXTRA_EXIT = "EXTRA_EXIT";
    public static final String EXTRA_SHOW_TABS = "EXTRA_SHOW_TABS";
    private static final String TAG = "MainTabs";
    public static String EXTRA_PAGE_NUMBER = "EXTRA_PAGE_NUMBER";
    public static String EXTRA_SEACH_TEXT = "EXTRA_SEACH_TEXT";
    public static String EXTRA_NOTIFY_REFRESH = "EXTRA_NOTIFY_REFRESH";
    public boolean isEink = false;
    ViewPager pager;
    List<UIFragment> tabFragments;
    TabsAdapter2 adapter;
    ImageView imageMenu;
    ImageView onImportBooks;
    TextView topBarTitle;
    View imageMenuParent, overlay;
    TextView toastBrightnessText, onSubscribe;
    Handler handler;
    MyProgressBar fab;
    ImageView fabLastBook;
    SwipeRefreshLayout swipeRefreshLayout;
    TextView drawerQuote;
    List<String> drawerQuotes;
    Random drawerQuoteRandom = new Random();
    boolean isMyKey = false;
    OnPageChangeListener onPageChangeListener = new OnPageChangeListener() {
        UIFragment uiFragment = null;

        @Override
        public void onPageSelected(int pos) {
            // This listener is registered on the ViewPager directly, so it
            // gets RAW virtual positions (0 and N+1 are edge ghost clones in
            // looping mode) — translate to the real tab index first.
            if (adapter != null) {
                pos = adapter.toReal(pos);
            }
            // picking any real tab leaves the temporary drawer overlay
            hideTabOverlay();
            uiFragment = tabFragments.get(pos);
            uiFragment.onSelectFragment();
            TempHolder.get().currentTab = pos;

            LOG.d("onPageSelected", uiFragment);
            // every page shows its own tab name, the dashboard included
            CharSequence title = adapter.getRealPageTitle(pos);
            if (topBarTitle != null && topBarTitle.getVisibility() == View.VISIBLE) {
                topBarTitle.setText(title.toString());
            }
            updateLastBookFabVisibility(uiFragment);
            Apps.accessibilityText(MainTabs2.this, title.toString() + " " + getString(R.string.tab_selected));
        }

        @Override
        public void onPageScrolled(int arg0, float arg1, int arg2) {

        }

        @Override
        public void onPageScrollStateChanged(int state) {
            if (isPullToRefreshEnable()) {
                swipeRefreshLayout.setEnabled(state == ViewPager.SCROLL_STATE_IDLE);
            }
            LOG.d("onPageSelected onPageScrollStateChanged", state);
            if (state == ViewPager.SCROLL_STATE_IDLE) {
                check();
            }
        }

        public void check() {
            if (isPullToRefreshEnable()) {
                swipeRefreshLayout.setEnabled(!(uiFragment instanceof PrefFragment2));
            }
        }
    };
    Runnable closeActivityRunnable = new Runnable() {
        @Override
        public void run() {
            if (drawerLayout != null) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    onDestroyBanner();
                    finish();
                } else {
                    drawerLayout.openDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
                }

            }
        }
    };
    boolean once = true;
    private SlidingTabLayout indicator;
    private DrawerLayout drawerLayout;

    public static boolean isPullToRefreshEnable(Context a, View swipeRefreshLayout) {
        // Google Drive sync was removed from all builds (2026-09), so the
        // pull-to-refresh sync is never enabled.
        return false;
    }

    public static void startActivity(Activity c, int tab) {
        final Intent intent = new Intent(c, MainTabs2.class);
        intent.putExtra(MainTabs2.EXTRA_SHOW_TABS, true);
        intent.putExtra(MainTabs2.EXTRA_PAGE_NUMBER, tab);
        intent.putExtra(PasswordDialog.EXTRA_APP_PASSWORD, c.getIntent()
                                                            .getStringExtra(PasswordDialog.EXTRA_APP_PASSWORD));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        c.startActivity(intent);
        c.overridePendingTransition(0, 0);
    }

    public static void closeApp(Context c) {
        if (c == null) {
            return;
        }
        EventBus.getDefault().post(new MsgCloseMainTabs());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        LOG.d(TAG, "onNewIntent");
        // testIntentHandler();
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) {
            finish();
            return;
        }
        if (intent.getCategories() != null && intent.getCategories().contains("android.intent.category.BROWSABLE")) {
            CloudRail.setAuthenticationResponse(intent);
            LOG.d("CloudRail response", intent);

            Intent intent1 = new Intent(UIFragment.INTENT_TINT_CHANGE)//
                                                                      .putExtra(MainTabs2.EXTRA_PAGE_NUMBER, UITab.getCurrentTabIndex(UITab.BrowseFragment));//

            LocalBroadcastManager.getInstance(this).sendBroadcast(intent1);
        }

        checkGoToPage(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults,
                                           int deviceId) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId);
        if (requestCode == MY_PERMISSIONS_REQUEST_WES) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                LOG.d("PermissionGranted","Granted");
                SearchAllBooksWorker.run(this);
            }else{
                LOG.d("PermissionGranted","NOT Granted");
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        LOG.d("onActivityResult", "requestCode:",requestCode,"resultCode:",resultCode,data);
        if(requestCode==MY_PERMISSIONS_REQUEST_WES){
            // only rescan when access was ACTUALLY granted: the user backing
            // out of the All-Files-Access screen used to trigger a full
            // deleteAllData()+rescan with no read permission → the library
            // was rebuilt from the fallback demo books (DB wipe)
            boolean granted = Build.VERSION.SDK_INT >= 30
                    ? android.os.Environment.isExternalStorageManager()
                    : androidx.core.content.ContextCompat.checkSelfPermission(this,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted) {
                AppSP.get().currentProfile="";
                AppSP.get().save();
                AppProfile.init(this);
                SearchAllBooksWorker.run(this);
            } else {
                Toast.makeText(this, R.string.you_need_grant_permission5, Toast.LENGTH_LONG).show();
            }
        }
        if (Android6.isNeedToGrantAccess(this, requestCode)) {
            Toast.makeText(this, R.string.you_need_grant_permission5, Toast.LENGTH_LONG).show();
            //Android6.checkPermissions(this, false);
            //return;

        }


        if (Build.VERSION.SDK_INT < Android6.ANDROID_12_INT && resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, R.string.fail, Toast.LENGTH_SHORT).show();
            return;
        }

        if (requestCode == REQUEST_CODE_ADD_RESOURCE && resultCode == Activity.RESULT_OK) {
            getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Uri uri = data.getData();

            String pathSAF = uri.toString();

            StringDB.add(BookCSS.get().pathSAF, pathSAF, (db) -> BookCSS.get().pathSAF = db);

            LOG.d("REQUEST_CODE_ADD_RESOURCE", pathSAF, BookCSS.get().pathSAF);

            UIFragment uiFragment = tabFragments.get(getCurrentRealIndex());
            if (uiFragment instanceof BrowseFragment2) {
                BrowseFragment2 fr = (BrowseFragment2) uiFragment;
                fr.displayAnyPath(pathSAF);
            }
        }
    }

    public boolean isPullToRefreshEnable() {
        return isPullToRefreshEnable(MainTabs2.this, swipeRefreshLayout);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // testIntentHandler();

       // if (Android6.canWrite(this)) {
            BrightnessHelper.applyBrigtness(this);
            BrightnessHelper.updateOverlay(overlay);
    //    }
    }

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(MyContextWrapper.wrap(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isTaskRoot() && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            finish();
            return;
        }

//        if (!Android6.canWrite(this) ) {
//            Android6.checkPermissions(this, true);
//            //return;
//        }

        Clouds.get().init(this);

        //import settings

        if (PasswordDialog.isNeedPasswordDialog(this)) {
            return;
        }

        LOG.d(TAG, "onCreate");

        LOG.d("EXTRA_EXIT", EXTRA_EXIT);
        if (getIntent().getBooleanExtra(EXTRA_EXIT, false)) {
            finish();
            return;
        }

        handler = new Handler(Looper.getMainLooper());
        isEink = Dips.isEInk();

        TintUtil.setStatusBarColor(this);
        DocumentController.doRotation(this);
        DocumentController.doContextMenu(this);

        setContentView(R.layout.main_tabs);
        DocumentController.applyEdgeToEdge(this);

        imageMenu = findViewById(R.id.imageMenu1);
        imageMenuParent = findViewById(R.id.imageParent1);
        imageMenuParent.setBackgroundColor(TintUtil.color);

        topBarTitle = findViewById(R.id.topBarTitle);
        onImportBooks = findViewById(R.id.onImportBooks);
        // The "+" import button in the top bar is removed; importing is still
        // reachable from the Library page and the drawer.
        onImportBooks.setVisibility(View.GONE);

        fab = findViewById(R.id.fab);
        fab.setVisibility(View.GONE);
        fab.setBackgroundResource(R.drawable.bg_circular);
        TintUtil.setDrawableTint(fab.getBackground().getCurrent(), TintUtil.color);

        // "continue last book" floating button: shown on the Home and Library
        // tabs only; visibility is re-evaluated per tab and per resume
        fabLastBook = findViewById(R.id.fabLastBook);
        if (fabLastBook != null) {
            TintUtil.setTintImageNoAlpha(fabLastBook, Color.WHITE);
            tintLastBookFab();
            fabLastBook.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLastBook();
                }
            });
        }

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setColorSchemeColors(TintUtil.color);

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // Google Drive sync removed (2026-09); pull-to-refresh is inert.
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        overlay = findViewById(R.id.overlay);

        toastBrightnessText = findViewById(R.id.toastBrightnessText);
        toastBrightnessText.setVisibility(View.GONE);
        TintUtil.setDrawableTint(toastBrightnessText.getCompoundDrawables()[0], Color.WHITE);

        tabFragments = new ArrayList<UIFragment>();

        try {

            for (UITab tab : UITab.getOrdered()) {
                LOG.d("getOrdered", tab, tab.isVisible());
                if (tab.isVisible()) {
                    tabFragments.add(tab.getClazz().newInstance());
                }
            }
            if (tabFragments.size() == 0) {
                synchronized (AppState.get().tabsOrder9) {
                    AppState.get().tabsOrder9 = AppState.DEFAULTS_TABS_ORDER;
                }
                for (UITab tab : UITab.getOrdered()) {
                    if (tab.isVisible()) {
                        tabFragments.add(tab.getClazz().newInstance());
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
            Toast.makeText(MainTabs2.this, R.string.msg_unexpected_error, Toast.LENGTH_LONG).show();
            tabFragments.add(new DashboardFragment2());
            tabFragments.add(new SearchFragment2());
            tabFragments.add(new BrowseFragment2());
            tabFragments.add(new PrefFragment2());
            //tabFragments.add(new CloudsFragment2());
        }
        drawerLayout = findViewById(R.id.drawer_layout);
        drawerQuote = findViewById(R.id.drawerQuote);
        showRandomQuote();

        imageMenu.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
                } else {
                    drawerLayout.openDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
                }
            }
        });

        imageMenu.setVisibility(View.VISIBLE);

        buildDrawerNavHeader((LinearLayout) findViewById(R.id.drawerNavHeader));

        // ((BrigtnessDraw)
        // findViewById(R.id.brigtnessProgressView)).setActivity(this);

        adapter = new TabsAdapter2(this, tabFragments);
        // Moon+ style: swiping past either edge of the tab bar wraps around.
        // Needs at least two tabs; with one there is nothing to wrap to.
        adapter.setLooping(tabFragments.size() >= 2);
        pager = findViewById(R.id.pager);
        pager.setAccessibilityDelegate(new View.AccessibilityDelegate());

        //if (Android6.canWrite(this) ) {
            pager.setAdapter(adapter);
       // }

        if (adapter.isLooping()) {
            // Real tabs live at virtual positions 1..N; start on the first one.
            pager.setCurrentItem(1, false);
            // onPageSelected never fires for this initial (no-animation) set,
            // and TempHolder is a process-wide static that would otherwise keep
            // the previous session's tab — pin the real start index here.
            TempHolder.get().currentTab = 0;
            // A raw-position listener (SlidingTabLayout's is already mapped to
            // real indices): when the swipe settles on an edge ghost clone,
            // teleport to the matching real page without animation so the wrap
            // reads as a single continuous gesture.
            pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                }

                @Override
                public void onPageSelected(int position) {
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    if (state == ViewPager.SCROLL_STATE_IDLE && adapter.isLooping()) {
                        int cur = pager.getCurrentItem();
                        int n = adapter.getRealCount();
                        if (cur <= 0 || cur > n) {
                            pager.setCurrentItem(adapter.toVirtual(adapter.toReal(cur)), false);
                        }
                    }
                }
            });
        }

        if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
            pager.setBackgroundColor(Color.BLACK);
        }

        // Keep only the current page + one neighbour page alive. The old
        // limit of 10 created the views of ALL tabs during the first layout,
        // which added ~900ms of fragment inflation before the first frame on
        // cold start. Pages further away are created lazily on switch.
        pager.setOffscreenPageLimit(1);
        pager.addOnPageChangeListener(onPageChangeListener);

        // onPageSelected does not fire for the initial page, so set the bar
        // title to the first tab explicitly (e.g. "Home" on a fresh install).
        if (topBarTitle != null && adapter.getRealCount() > 0) {
            topBarTitle.setText(adapter.getRealPageTitle(0).toString());
        }

        // same for the last-book FAB: it starts on the first (Home) tab
        if (adapter.getRealCount() > 0) {
            updateLastBookFabVisibility(tabFragments.get(0));
        }


        drawerLayout.addDrawerListener(new DrawerListener() {
            @Override
            public void onDrawerStateChanged(int arg0) {
                LOG.d("drawerLayout-onDrawerStateChanged", arg0);
            }

            @Override
            public void onDrawerSlide(View arg0, float arg1) {
                LOG.d("drawerLayout-onDrawerSlide");
            }

            @Override
            public void onDrawerOpened(View arg0) {
                LOG.d("drawerLayout-onDrawerOpened");
                showRandomQuote();
            }

            @Override
            public void onDrawerClosed(View arg0) {
                LOG.d("drawerLayout-onDrawerClosed");
                try {
                    tabFragments.get(getCurrentRealIndex()).onSelectFragment();

                    if (isPullToRefreshEnable(MainTabs2.this, swipeRefreshLayout)) {
                        swipeRefreshLayout.setEnabled(true);
                        swipeRefreshLayout.setColorSchemeColors(TintUtil.color);
                    }
                    TintUtil.setDrawableTint(fab.getBackground().getCurrent(), TintUtil.color);
                } catch (Exception e) {
                    LOG.e(e);
                }
            }
        });

        if (AppState.get().tapPositionTop) {
            indicator = findViewById(R.id.slidingTabs1);
            topBarTitle.setVisibility(View.GONE);
            onImportBooks.setVisibility(View.GONE);
        } else {
            indicator = findViewById(R.id.slidingTabs2);
        }
        indicator.setVisibility(View.VISIBLE);
        indicator.init();

        indicator.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                LOG.d("OnFocusChangeListener", hasFocus);
            }
        });

        indicator.setViewPager(pager);

        indicator.setDividerColors(getResources().getColor(R.color.tint_divider));
        indicator.setSelectedIndicatorColors(Color.WHITE);
        indicator.setBackgroundColor(TintUtil.color);

        if (!AppState.get().tapPositionTop || !AppState.get().tabWithNames) {
            indicator.setDividerColors(Color.TRANSPARENT);
            indicator.setSelectedIndicatorColors(Color.TRANSPARENT);
            for (int i = 0; i < indicator.getmTabStrip().getChildCount(); i++) {
                View child = indicator.getmTabStrip().getChildAt(i);
                child.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        imageMenu.performClick();
                        return true;
                    }
                });
            }
        }
        indicator.setOnDoubleClickAction(index -> {
            try {
                tabFragments.get(index).onDoubleClick();
            } catch (Exception e) {
                LOG.e(e);
            }

            return false;
        });

        indicator.setOnTabReselect(index -> {
            try {
                // Re-tapping the active tab must first close any temporary
                // overlay page (最近阅读/书签笔记/我的珍藏/网上书库 open as
                // overlays on top of the current tab), then let the page handle
                // the reselect (e.g. OPDS -> root). hideTabOverlay() is a no-op
                // when no overlay is showing.
                hideTabOverlay();
                tabFragments.get(index).onTabReselect();
            } catch (Exception e) {
                LOG.e(e);
            }

            return false;
        });

        if (AppState.get().appTheme == AppState.THEME_INK) {
            TintUtil.setTintImageNoAlpha(imageMenu, TintUtil.color);
            TintUtil.setTintImageNoAlpha(onImportBooks, TintUtil.color);
            indicator.setSelectedIndicatorColors(TintUtil.color);
            indicator.setDividerColors(TintUtil.color);
            indicator.setBackgroundColor(Color.TRANSPARENT);
            imageMenuParent.setBackgroundColor(Color.TRANSPARENT);
        }

        //Android6.checkPermissions(this, true);
        // Analytics.onStart(this);

        List<String>
                actions =
                Arrays.asList("android.intent.action.PROCESS_TEXT", "android.intent.action.SEARCH", "android.intent.action.SEND");
        List<String>
                extras =
                Arrays.asList(Intent.EXTRA_PROCESS_TEXT_READONLY, Intent.EXTRA_PROCESS_TEXT, SearchManager.QUERY, Intent.EXTRA_TEXT);
        if (getIntent() != null && getIntent().getAction() != null) {
            if (actions.contains(getIntent().getAction())) {
                for (String extra : extras) {
                    final String text = getIntent().getStringExtra(extra);
                    if (TxtUtils.isNotEmpty(text)) {
                        pager.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // tab order is user-configurable and tab 0 is the
                                // dashboard; resolve the library tab by class instead
                                for (UIFragment uiFragment : tabFragments) {
                                    if (uiFragment instanceof SearchFragment2) {
                                        ((SearchFragment2) uiFragment).searchAndOrderExteral(text);
                                        break;
                                    }
                                }
                            }
                        }, 250);
                        break;
                    }
                }
            }
        }



        EventBus.getDefault().register(this);
        // early-return paths in onCreate (not task root / password gate /
        // EXTRA_EXIT) skip this, so onDestroy must not unregister blindly
        eventBusRegistered = true;

        boolean showTabs = getIntent().getBooleanExtra(EXTRA_SHOW_TABS, false);

        if (!showTabs && AppState.get().isOpenLastBook) {
            LOG.d("Open lastBookPath", AppSP.get().lastBookPath);

            if (AppSP.get().lastBookPath == null || !new File(AppSP.get().lastBookPath).isFile()) {
                LOG.d("Open Last book not found");
                return;
            }

            try {
                AppBook book = SharedBooks.load(AppSP.get().lastBookPath);
                if (book.p > 0.9999) {
                    LOG.d("Open Last book skipped", book.p);
                    Toast.makeText(MainTabs2.this, R.string.the_book_is_complete, Toast.LENGTH_LONG).show();
                    return;
                }
            }catch (Exception e){
                LOG.e(e);
            }

            boolean isEasyMode = AppSP.get().readingMode == AppState.READING_MODE_BOOK;
            Safe.run(() -> {

                Intent
                        intent =
                        new Intent(MainTabs2.this, isEasyMode ? HorizontalViewActivity.class : VerticalViewActivity.class);
                intent.putExtra(PasswordDialog.EXTRA_APP_PASSWORD, getIntent().getStringExtra(PasswordDialog.EXTRA_APP_PASSWORD));
                intent.setData(Uri.fromFile(new File(AppSP.get().lastBookPath)));
                startActivity(intent);
            });
        }

        checkGoToPage(getIntent());

        // WebDAV reading-data sync: run silently in the background shortly
        // after launch when enabled, so progress/bookmarks from other devices
        // appear without opening the settings dialog.
        if (AppState.get().webdavSyncEnabled && TxtUtils.isNotEmpty(AppState.get().webdavSyncServer)) {
            handler.postDelayed(() -> {
                if (AppState.get().webdavSyncEnabled) {
                    WebDavSyncer.syncAsync(MainTabs2.this, null);
                }
            }, 4000);
        }
        // …and arm the periodic background sync (no-op while the configured
        // interval is 0/"off")
        WebDavSyncer.scheduleNextPeriodic(MainTabs2.this);

        // Warm the MuPDF accelerator for the last-read book shortly after
        // launch, so its next open skips the full-document layout. Repeats are
        // deduplicated inside BookWarmer.
        handler.postDelayed(() -> {
            com.foobnix.pdf.info.BookWarmer.warmAsync(
                    java.util.Collections.singletonList(com.foobnix.model.AppSP.get().lastBookPath));
        }, 9000);

        if (!AppState.get().isEnableAccessibility && once) {
            once = false;
            handler.postDelayed(() -> {
                Apps.accessibilityText(MainTabs2.this, getString(R.string.welcome_accessibility));
            }, 5000);
        }

        // Defer UMP consent / ads init until after the first frame so its Play
        // Services class loading and async network call don't delay the first frame.
        // handler is guaranteed non-null here: every early-return path above
        // (password gate, EXTRA_EXIT) returns before handler is assigned.
        handler.post(() -> {
            try {
                //ads: the UMP consent flow lives inside the ad provider
                //compiled into this variant (no-op on GMS-free builds)
                LOG.d(this, "TEST-ads-device ...");
                if (AppsConfig.isShowAdsInApp(this)) {
                    ADS.get().requestConsent(this);
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onShowSycn(MessageSync msg) {

        try {
            if (msg.state == MessageSync.STATE_VISIBLE) {
                if (BookCSS.get().isSyncAnimation) {
                    fab.setVisibility(View.VISIBLE);
                }
                swipeRefreshLayout.setRefreshing(false);
            } else if (msg.state == MessageSync.STATE_FAILE) {
                fab.setVisibility(View.GONE);
                swipeRefreshLayout.setRefreshing(false);
                //Toast.makeText(this, getString(R.string.sync_error), Toast.LENGTH_LONG).show();
            } else {
                fab.setVisibility(View.GONE);
                swipeRefreshLayout.setRefreshing(false);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    @Subscribe
    public void onMessegeBrightness(MessegeBrightness msg) {
        BrightnessHelper.onMessegeBrightness(handler, msg, toastBrightnessText, overlay);
    }

    @Subscribe
    public void goToPageMsg(SearchMetaMsg msg){
        // resolve by class: UITab.getCurrentTabIndex returns a wrong index when
        // the target tab itself is hidden
        navigateToTab(UITab.SearchFragment);
    }

    // Moon+ style drawer: banner on top (XML), five nav rows here, then the
    // fixed bottom bar with 设置选项/软件说明/晚上模式/退出.
    private static final int DRAWER_ICON_GRAY = Color.parseColor("#737373");

    // per-theme drawer colors, resolved in buildDrawerNavHeader
    private int drawerTextColor = Color.parseColor("#212121");
    private int drawerIconColor = DRAWER_ICON_GRAY;
    private boolean drawerDarkTheme = false;

    private void buildDrawerNavHeader(LinearLayout parent) {
        if (parent == null) {
            return;
        }

        // the drawer layout itself is theme-agnostic; resolve colors here so the
        // panel, labels and icons stay readable in dark/OLED/ink modes
        drawerDarkTheme = AppState.get().appTheme == AppState.THEME_DARK || AppState.get().appTheme == AppState.THEME_DARK_OLED;
        View drawer = findViewById(R.id.left_drawer);
        if (drawer != null) {
            int bg = Color.WHITE;
            if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
                bg = Color.BLACK;
            } else if (drawerDarkTheme) {
                bg = Color.parseColor("#1e1e1e");
            }
            drawer.setBackgroundColor(bg);
        }
        drawerIconColor = drawerDarkTheme ? Color.parseColor("#9e9e9e") : DRAWER_ICON_GRAY;
        drawerTextColor = drawerDarkTheme ? Color.parseColor("#e0e0e0") : Color.parseColor("#212121");

        addDrawerNavRow(parent, R.string.moon_home_recent, R.drawable.glyphicons_55_clock, UITab.RecentFragment);
        addDrawerNavRow(parent, R.string.moon_drawer_library, R.drawable.glyphicons_589_book_open, UITab.SearchFragment);
        addDrawerNavRow(parent, R.string.moon_home_files, R.drawable.glyphicons_145_folder_open, UITab.BrowseFragment);
        addDrawerNavRow(parent, R.string.moon_home_net, R.drawable.glyphicons_417_globe, UITab.OpdsFragment);
        addDrawerNavRow(parent, R.string.bookmarks_and_notes, R.drawable.glyphicons_73_bookmark, UITab.BookmarksFragment);

        buildDrawerBottomBar();
    }

    private void addDrawerNavRow(LinearLayout parent, int labelRes, int iconRes, final UITab tab) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dips.dpToPx(52));
        row.setPadding(Dips.dpToPx(16), 0, Dips.dpToPx(16), 0);
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        TintUtil.setTintImageWithAlpha(icon, drawerIconColor);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Dips.dpToPx(24), Dips.dpToPx(24));
        iconParams.rightMargin = Dips.dpToPx(18);
        row.addView(icon, iconParams);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(16);
        label.setTextColor(drawerTextColor);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateToTab(tab);
            }
        });
        parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildDrawerBottomBar() {
        View bar = findViewById(R.id.drawerBottomBar);
        if (bar == null) {
            return;
        }

        // jump to the preferences tab in the bottom tab bar (Moon+ style:
        // the drawer itself no longer embeds the settings page)
        bindDrawerBottomButton(bar, R.id.drawerBtnSettings, R.id.drawerBtnSettingsIcon, R.id.drawerBtnSettingsLabel, R.string.moon_drawer_settings, new OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateToTab(UITab.PrefFragment);
            }
        });

        bindDrawerBottomButton(bar, R.id.drawerBtnAbout, R.id.drawerBtnAboutIcon, R.id.drawerBtnAboutLabel, R.string.moon_drawer_about, new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAboutDialog();
            }
        });

        bindDrawerBottomButton(bar, R.id.drawerBtnNight, R.id.drawerBtnNightIcon, R.id.drawerBtnNightLabel, R.string.moon_night_mode, new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean night = AppState.get().appTheme == AppState.THEME_DARK || AppState.get().appTheme == AppState.THEME_DARK_OLED;
                applyDayNight(!night);
            }
        });

        bindDrawerBottomButton(bar, R.id.drawerBtnExit, R.id.drawerBtnExitIcon, R.id.drawerBtnExitLabel, R.string.moon_drawer_exit, new OnClickListener() {
            @Override
            public void onClick(View v) {
                onDestroyBanner();
                finish();
            }
        });
    }

    private void bindDrawerBottomButton(View bar, int buttonId, int iconId, int labelId, int labelRes, OnClickListener listener) {
        View button = bar.findViewById(buttonId);
        if (button == null) {
            return;
        }
        ImageView icon = (ImageView) button.findViewById(iconId);
        TextView label = (TextView) button.findViewById(labelId);
        TintUtil.setTintImageWithAlpha(icon, drawerIconColor);
        label.setText(labelRes);
        label.setTextColor(drawerTextColor);
        // first child of the button is the circular icon background
        if (button instanceof ViewGroup && ((ViewGroup) button).getChildCount() > 0) {
            ((ViewGroup) button).getChildAt(0)
                    .setBackgroundResource(drawerDarkTheme ? R.drawable.drawer_circle_bg_dark : R.drawable.drawer_circle_bg);
        }
        button.setOnClickListener(listener);
    }

    /**
     * 软件说明 (Moon+ style): only the bottom "about" block of the preferences
     * page — version header "Librera: x.y.z", Librera pro, changelog, licence,
     * support mail, web and rate links — NOT the settings sections themselves.
     */
    private void showAboutDialog() {
        AboutSectionBinder.showDialog(this);
    }

    private boolean eventBusRegistered = false;

    /**
     * Drawer bottom-bar toggle: flip the whole-app day/night theme and restart,
     * the same way the settings page does (PrefFragment2.onTheme). Keeping the
     * reader flag isDayNotInvert in sync so books open in the matching mode.
     */
    private void applyDayNight(boolean night) {
        AppState.get().isSystemThemeColor = false;
        if (night) {
            // preserve a user-chosen OLED black over plain dark
            AppState.get().appTheme = AppState.get().appTheme == AppState.THEME_DARK_OLED ? AppState.THEME_DARK_OLED : AppState.THEME_DARK;
            AppState.get().isDayNotInvert = false;
        } else {
            // mirror the OLED handling: an e-ink user stays on the paper theme
            AppState.get().appTheme = AppState.get().appTheme == AppState.THEME_INK ? AppState.THEME_INK : AppState.THEME_LIGHT;
            AppState.get().isDayNotInvert = true;
        }
        if (AppState.get().appTheme != AppState.THEME_INK) {
            // same image-adjustment reset the settings page performs on theme change
            AppState.get().contrastImage = 0;
            AppState.get().brigtnessImage = 0;
            AppState.get().bolderTextOnImage = false;
            AppState.get().isEnableBCOptional1 = false;
        }
        IMG.clearDiscCache();
        IMG.clearMemoryCache();
        AppProfile.save(this);
        AppProfile.clear();
        finish();
        // getCurrentRealIndex, not TempHolder.currentTab: on a fresh session
        // before the first swipe the holder still carries a stale static value
        MainTabs2.startActivity(this, getCurrentRealIndex());
    }

    public void navigateToTab(UITab tab) {
        // a previously opened overlay page (e.g. 网上书库) sits ON TOP of the
        // pager: without hiding it first, drawer navigation flipped the pager
        // behind a still-visible overlay and looked frozen
        hideTabOverlay();
        boolean found = false;
        for (int i = 0; i < tabFragments.size(); i++) {
            if (tab.getClazz().isInstance(tabFragments.get(i))) {
                pager.setCurrentItem(adapter.toVirtual(i));
                found = true;
                break;
            }
        }
        if (!found) {
            // Drawer navigation is decoupled from the tab bar: a page that is
            // disabled there opens as a temporary overlay instead of showing
            // "tab is hidden". The tab bar configuration is not modified.
            showTabOverlay(tab);
        }
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
        }
    }

    /**
     * Shows a random reading quote in the drawer banner's top-left corner.
     * The 1000+ quotes ship in assets/reading_quotes.txt (Chinese, one per line,
     * "text —— source"); when the app display language is English,
     * assets/reading_quotes_en.txt ("text — Author, Work") is used instead.
     * Loaded once and kept in memory.
     */
    private void showRandomQuote() {
        if (drawerQuote == null) {
            return;
        }
        try {
            if (drawerQuotes == null) {
                String file = "reading_quotes.txt";
                if ("en".equals(AppState.get().getAppLang())) {
                    file = "reading_quotes_en.txt";
                }
                try {
                    drawerQuotes = loadQuotes(file);
                } catch (Exception e) {
                    LOG.e(e);
                }
                if ((drawerQuotes == null || drawerQuotes.isEmpty()) && !file.equals("reading_quotes.txt")) {
                    drawerQuotes = loadQuotes("reading_quotes.txt");
                }
            }
            if (drawerQuotes != null && !drawerQuotes.isEmpty()) {
                drawerQuote.setTextColor(TintUtil.getColorInDayNighth());
                drawerQuote.setText(drawerQuotes.get(drawerQuoteRandom.nextInt(drawerQuotes.size())));
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Reads a quotes asset file (one quote per line, blank lines skipped).
     */
    private List<String> loadQuotes(String file) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(file), StandardCharsets.UTF_8));
        try {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    /**
     * Opens a detached network page on a specific OPDS catalog or WebDAV
     * server ("url" is the catalog href / server url, "title" the display
     * name for the top bar). The page is an independent OpdsFragment2 shown
     * in the overlay container — the Network tab itself is never touched.
     */
    public void openNetworkPage(final boolean webDav, final String targetUrl, final String title) {
        OpdsFragment2 fragment = new OpdsFragment2();
        showFragmentOverlay(fragment,
                TxtUtils.isEmpty(title) ? getString(UITab.OpdsFragment.getName()) : title);
        fragment.openExternal(webDav, targetUrl);
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
        }
    }

    /**
     * Detached folder page opened from the "My files" root: a separate
     * BrowseFragment2 instance browsing that path, so the tab itself keeps
     * its root view (decoupled).
     */
    public void openFolderPage(final String folderPath) {
        String name = new File(folderPath).getName();
        if (TxtUtils.isEmpty(name)) {
            name = folderPath;
        }
        showFragmentOverlay(BrowseFragment2.newFolderInstance(folderPath), name);
    }

    /**
     * Opens the library tab with a read-state filter pre-selected ("",
     * "unread", "reading" or "read"). Falls back to the temporary overlay when
     * the tab is disabled in the tab bar.
     */
    public void openLibraryWithReadState(final String readState) {
        boolean found = false;
        for (int i = 0; i < tabFragments.size(); i++) {
            if (tabFragments.get(i) instanceof SearchFragment2) {
                ((SearchFragment2) tabFragments.get(i)).setReadStateFilter(readState);
                pager.setCurrentItem(adapter.toVirtual(i));
                found = true;
                break;
            }
        }
        if (!found) {
            showTabOverlay(UITab.SearchFragment);
            if (overlayFragment instanceof SearchFragment2) {
                ((SearchFragment2) overlayFragment).setReadStateFilter(readState);
            }
        }
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
        }
    }

    /** The fragment currently shown in the pager (first real tab on failure). */
    public UIFragment getCurrentFragment() {
        try {
            return tabFragments.get(getCurrentRealIndex());
        } catch (Exception e) {
            LOG.e(e);
            return tabFragments.isEmpty() ? null : tabFragments.get(0);
        }
    }

    private UITab overlayTab = null;
    private UIFragment overlayFragment = null;

    private void showTabOverlay(UITab tab) {
        try {
            ViewGroup container = findViewById(R.id.overlayContainer);
            if (container == null) {
                Toast.makeText(this, R.string.moon_tab_hidden, Toast.LENGTH_SHORT).show();
                return;
            }
            if (overlayTab == tab && container.getVisibility() == View.VISIBLE) {
                return;
            }
            UIFragment fragment = tab.getClazz().newInstance();
            showFragmentOverlay(fragment, getString(tab.getName()));
            overlayTab = tab;
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Shows an arbitrary fragment as a temporary page in the overlay container
     * ("title" goes to the top bar). Used for hidden tabs and for detached
     * pages (e.g. the network browser opened from "My files") that run their
     * own instance and never share state with the tab of the same class.
     */
    public void showFragmentOverlay(UIFragment fragment, String title) {
        try {
            ViewGroup container = findViewById(R.id.overlayContainer);
            if (container == null) {
                return;
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.overlayContainer, fragment)
                    .commitAllowingStateLoss();
            overlayFragment = fragment;
            overlayTab = null;

            int bg = Color.WHITE;
            if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
                bg = Color.BLACK;
            } else if (AppState.get().appTheme == AppState.THEME_DARK) {
                bg = Color.parseColor("#1e1e1e");
            }
            container.setBackgroundColor(bg);
            container.setVisibility(View.VISIBLE);

            if (fabLastBook != null) {
                fabLastBook.setVisibility(View.GONE);
            }
            if (topBarTitle != null) {
                topBarTitle.setText(title);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public void hideTabOverlay() {
        try {
            ViewGroup container = findViewById(R.id.overlayContainer);
            if (container == null || container.getVisibility() != View.VISIBLE) {
                return;
            }
            if (overlayFragment != null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .remove(overlayFragment)
                        .commitAllowingStateLoss();
            }
            overlayFragment = null;
            overlayTab = null;
            container.setVisibility(View.GONE);

            syncTopBarTitle();
            updateLastBookFabVisibility(getCurrentFragment());
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private void syncTopBarTitle() {
        try {
            if (topBarTitle == null || adapter == null || adapter.getRealCount() <= 0) {
                return;
            }
            int idx = getCurrentRealIndex();
            CharSequence title = adapter.getRealPageTitle(idx);
            topBarTitle.setText(title.toString());
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public void checkGoToPage(Intent intent) {
        try {
            int pos = intent.getIntExtra(EXTRA_PAGE_NUMBER, -1);
            if (pos != -1 && adapter != null && pos < adapter.getRealCount()) {
                pager.setCurrentItem(adapter.toVirtual(pos));
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        AppProfile.save(this);
        IMG.pauseRequests(this);

        if (Dips.isEInk()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        //AppsConfig.isCloudsEnable = UITab.isShowCloudsPreferences();
        AppsConfig.isCloudsEnable = false;

        LOG.d(TAG, "onResume");
        if (Dips.isEInk()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        LOG.d("FLAG clearFlags", "FLAG_KEEP_SCREEN_ON", "clear");

        DocumentController.chooseFullScreen(this, AppState.get().fullScreenMainMode);
        TintUtil.updateAll();

        LocalBroadcastManager.getInstance(this)
                             .registerReceiver(broadcastReceiver, new IntentFilter(UIFragment.INTENT_TINT_CHANGE));
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(isPullToRefreshEnable());
        }

        try {
            if (pager != null) {
                final UIFragment uiFragment = tabFragments.get(getCurrentRealIndex());
                uiFragment.onSelectFragment();
                // the last-read book may have changed while we were away
                updateLastBookFabVisibility(uiFragment);
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }

        IMG.resumeRequests(this);
        //AppSP.get().lastClosedActivity = MainTabs2.class.getSimpleName();
        //LOG.d("lasta save", AppSP.get().lastClosedActivity);

    }

    public void updateCurrentFragment() {
        tabFragments.get(getCurrentRealIndex()).onSelectFragment();
    }

    /**
     * Real (non-looping) index of the selected tab. With looping enabled the
     * ViewPager position is virtual (real+1); every tabFragments lookup must
     * go through this helper or it is off by one — and out of bounds on the
     * last tab.
     */
    private int getCurrentRealIndex() {
        if (tabFragments == null || tabFragments.isEmpty()) {
            return 0;
        }
        int pos = pager != null ? pager.getCurrentItem() : 0;
        if (adapter != null && adapter.isLooping()) {
            pos = adapter.toReal(pos);
        }
        return Math.max(0, Math.min(pos, tabFragments.size() - 1));
    }

    // ---------------------------------------------------------------- last-book FAB

    private void tintLastBookFab() {
        if (fabLastBook != null) {
            TintUtil.setDrawableTint(fabLastBook.getBackground().getCurrent(), TintUtil.color);
        }
    }

    /** Show the floating button only on Home (dashboard) and Library pages. */
    private void updateLastBookFabVisibility(UIFragment current) {
        if (fabLastBook == null) {
            return;
        }
        boolean show = current instanceof DashboardFragment2 || current instanceof SearchFragment2;
        if (show) {
            String path = AppSP.get().lastBookPath;
            show = TxtUtils.isNotEmpty(path) && new File(path).isFile();
        }
        fabLastBook.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * FAB click: reopen the last-read book at its saved position (restored from
     * the AppBook percent in DocumentController.onResume). If that book is
     * finished, fall back to the most recent unfinished one.
     */
    private void openLastBook() {
        String path = AppSP.get().lastBookPath;
        if (TxtUtils.isNotEmpty(path) && new File(path).isFile()) {
            try {
                AppBook book = SharedBooks.load(path);
                if (book.p > 0.9999) {
                    path = null; // finished — look for the next unfinished one
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        } else {
            path = null;
        }
        if (path == null) {
            path = findRecentUnfinishedPath();
        }
        if (path == null) {
            Toast.makeText(this, R.string.moon_no_recent_book, Toast.LENGTH_SHORT).show();
            return;
        }
        final String bookPath = path;
        boolean isEasyMode = AppSP.get().readingMode == AppState.READING_MODE_BOOK;
        Safe.run(() -> {
            Intent intent = new Intent(MainTabs2.this, isEasyMode ? HorizontalViewActivity.class : VerticalViewActivity.class);
            intent.putExtra(PasswordDialog.EXTRA_APP_PASSWORD, getIntent().getStringExtra(PasswordDialog.EXTRA_APP_PASSWORD));
            intent.setData(Uri.fromFile(new File(bookPath)));
            startActivity(intent);
        });
    }

    private String findRecentUnfinishedPath() {
        try {
            // false: never backfill titles here — updateProgress=true makes
            // getAllRecent run a FULL ebook parse per untitled entry on the
            // UI thread (multi-second freeze right after a DB wipe, exactly
            // when the FAB is most used)
            List<FileMeta> recent = AppData.get().getAllRecent(false);
            if (recent != null) {
                for (FileMeta m : recent) {
                    String p = m.getPath();
                    if (TxtUtils.isNotEmpty(p) && new File(p).isFile()) {
                        Float progress = m.getIsRecentProgress();
                        if (progress == null || progress < 0.9999f) {
                            return p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return null;
    }

    @Override
    public boolean onKeyDown(int keyCode1, KeyEvent event) {
        if (!isEink) {
            return super.onKeyDown(keyCode1, event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == 0) {
            keyCode = event.getScanCode();
        }
        isMyKey = false;
        if (tabFragments.get(getCurrentRealIndex()).onKeyDown(keyCode)) {
            isMyKey = true;
            return true;
        }

        return super.onKeyDown(keyCode1, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isEink) {
            return super.onKeyUp(keyCode, event);
        }

        if (isMyKey) {
            return true;
        }
        // TODO Auto-generated method stub
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        SharedBooks.cache.clear();
    }

    @Override
    public void onDestroy() {

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        LOG.d(TAG, "onDestroy");
        if (pager != null) {
            try {
                pager.setAdapter(null);
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        // Analytics.onStop(this);
        CacheDir.ZipApp.removeCacheContent();
        // ImageExtractor.clearErrors();
        // ImageExtractor.clearCodeDocument();

        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this);
            eventBusRegistered = false;
        }
        //IMG.clearMemoryCache();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        String language = newConfig.locale.getLanguage();
        float fontScale = newConfig.fontScale;

        LOG.d("ContextWrapper ConfigChanged", language, fontScale);

        if (pager != null) {
            int currentItem = pager.getCurrentItem();
            //pager.setAdapter(adapter); //WHY???
            pager.setCurrentItem(currentItem);
            IMG.clearMemoryCache();
        }
        //showBannerAds();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Android6.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    @Override
    public boolean onKeyLongPress(final int keyCode, final KeyEvent event) {
        if (CloseAppDialog.checkLongPress(this, event)) {
            CloseAppDialog.show(this, closeActivityRunnable);
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public void onFinishActivity() {
        finish();
    }

    @Override
    public void onBackPressedImpl() {
        // leave the temporary drawer overlay first; the page consumes BACK for
        // its own internal navigation (OPDS/WebDAV levels) until at its root
        ViewGroup overlayContainer = findViewById(R.id.overlayContainer);
        if (overlayContainer != null && overlayContainer.getVisibility() == View.VISIBLE) {
            if (overlayFragment != null && overlayFragment.isBackPressed()) {
                return;
            }
            hideTabOverlay();
            return;
        }

        // close the drawer first instead of popping the review/exit dialog over it
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START, AppState.get().appTheme != AppState.THEME_INK);
            return;
        }

        if (tabFragments != null) {
            int realPos = adapter.toReal(pager.getCurrentItem());
            if (!tabFragments.isEmpty() && realPos >= 0 && realPos < tabFragments.size() && tabFragments.get(realPos).isBackPressed()) {
                return;
            }
            RefiewForm.show(this, closeActivityRunnable);
        } else {
            closeActivityRunnable.run();

        }
    }

    @Override
    public void onBackPressedFinishImpl() {
        closeActivityRunnable.run();
    }

    @Subscribe
    public void onCloseAppMsg(MsgCloseMainTabs event) {
        onFinishActivity();
    }

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int pos = intent.getIntExtra(EXTRA_PAGE_NUMBER, -1);
            if (pos != -1) {
                if (pos >= 0 && adapter != null && pos < adapter.getRealCount()) {
                    pager.setCurrentItem(adapter.toVirtual(pos));
                }

                if (intent.getBooleanExtra(EXTRA_NOTIFY_REFRESH, false)) {
                    onResume();
                }
            } else {
                if (AppState.get().appTheme == AppState.THEME_INK) {
                    TintUtil.setTintImageNoAlpha(imageMenu, TintUtil.color);
                    indicator.setSelectedIndicatorColors(TintUtil.color);
                    indicator.setDividerColors(TintUtil.color);
                    indicator.updateIcons(adapter.toReal(pager.getCurrentItem()));
                } else {
                    indicator.setBackgroundColor(TintUtil.color);
                    imageMenuParent.setBackgroundColor(TintUtil.color);
                }
                tintLastBookFab();
            }
        }
    };
}
