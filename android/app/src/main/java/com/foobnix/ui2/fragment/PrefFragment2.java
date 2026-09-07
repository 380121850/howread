package com.foobnix.ui2.fragment;

import static com.foobnix.pdf.info.view.confline.ConfAction.of;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.buzzingandroid.ui.HSVColorPickerDialog;
import com.buzzingandroid.ui.HSVColorPickerDialog.OnColorSelectedListener;
import com.foobnix.LibreraBuildConfig;
import com.foobnix.StringResponse;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.IntegerResponse;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.ResultResponse2;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.android.utils.Views;
import com.foobnix.dao2.FileMeta;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AndroidWhatsNew;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.PasswordDialog;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.presentation.PathAdapter;
import com.foobnix.pdf.info.view.AboutSectionBinder;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.BrightnessHelper;
import com.foobnix.pdf.info.view.CustomSeek;
import com.foobnix.pdf.info.view.Dialogs;
import com.foobnix.pdf.info.view.DragingPopup;
import com.foobnix.pdf.info.view.KeyCodeDialog;
import com.foobnix.pdf.info.view.MultyDocSearchDialog;
import com.foobnix.pdf.info.view.MyPopupMenu;
import com.foobnix.pdf.info.view.confline.ConfLineView;
import com.foobnix.pdf.info.widget.ChooserDialogFragment;
import com.foobnix.pdf.info.ADS;
import com.foobnix.pdf.info.widget.ColorsDialog;
import com.foobnix.pdf.info.widget.ColorsDialog.ColorsDialogResult;
import com.foobnix.pdf.info.widget.DialogTranslateFromTo;
import com.foobnix.pdf.info.widget.PrefDialogs;
import com.foobnix.pdf.info.widget.RecentUpates;
import com.foobnix.pdf.info.widget.ShareDialog;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.PasswordState;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AdsFragmentActivity;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.BooksService;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.MyContextWrapper;
import com.foobnix.webdav.WebDavSyncDialog;
import com.foobnix.work.SearchAllBooksWorker;
import com.jmedeisis.draglinearlayout.DragLinearLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PrefFragment2 extends UIFragment {
    public static final Pair<Integer, Integer> PAIR =
            new Pair<>(R.string.preferences, R.drawable.glyphicons_5_settings);

    private static final String WWW_SITE = "https://github.com/380121850/LibreraReader";
    private static final String WWW_BETA_SITE = "https://github.com/380121850/LibreraReader/releases";
    private static final String WWW_WIKI_SITE = "https://github.com/380121850/LibreraReader";
    View section1, section2, section4, section8, section9, panelRecent, overlay,
            statusBarHack;
    private TextView curBrightness, themeColor, profileLetter;
    private CheckBox isRememberDictionary;
    private TextView nextKeys;
    private TextView prevKeys;
    OnCheckedChangeListener reverseListener = new OnCheckedChangeListener() {

        @Override public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
            AppState.get().isReverseKeys = isChecked;
            initKeys();
            saveChanges();
            LOG.d("Save Changes", 3);
        }
    };
    Runnable onCloseDialog = new Runnable() {

        @Override public void run() {
            initKeys();
        }
    };
    private SeekBar bar;
    private CheckBox autoSettings;
    private CheckBox ch;
    private TextView selectedOpenMode;
    private TextView textNigthColor;
    private TextView textDayColor;
    private TextView selectedDictionaly;
    private TextView screenOrientation;
    private View inflate;
    ConfLineView configSingleClick;

    @Override public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override public boolean isBackPressed() {
        return false;
    }

    @Override public void notifyFragment() {
    }

    @Override public void resetFragment() {
    }

    @Override public void onTintChanged() {

        TintUtil.setStatusBarColor(getActivity(), TintUtil.color);
        TintUtil.setBackgroundFillColor(section1, TintUtil.color);
        TintUtil.setBackgroundFillColor(section2, TintUtil.color);
        TintUtil.setBackgroundFillColor(section4, TintUtil.color);
        TintUtil.setBackgroundFillColor(section8, TintUtil.color);
        TintUtil.setBackgroundFillColor(section9, TintUtil.color);
        TintUtil.setBackgroundFillColor(panelRecent, TintUtil.color);
        if (statusBarHack != null) {
            statusBarHack.setBackgroundColor(TintUtil.color);
        }

        if (profileLetter != null && getActivity() != null) {
            final String p = AppProfile.getCurrent();
            profileLetter.setText(TxtUtils.getFirstLetter(p));
            profileLetter.setBackgroundDrawable(AppProfile.getProfileColorDrawable(getActivity(), TintUtil.color));
            profileLetter.setContentDescription(p + " " + getString(R.string.profile));
        }

        if (AppState.get().appTheme == AppState.THEME_INK) {
            TxtUtils.setInkTextView(inflate.getRootView());
        }
        TxtUtils.updateAllLinks(inflate,true);

    }

    @Override public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                                       final Bundle savedInstanceState) {
        inflate = inflater.inflate(R.layout.fragment_preferences, container, false);

        section8 = inflate.findViewById(R.id.section8);

        section9 = inflate.findViewById(R.id.section9);
        panelRecent = inflate.findViewById(R.id.panelRecent);

        // tabs position
        final DragLinearLayout dragLinearLayout = inflate.findViewById(R.id.dragLinearLayout);
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.setMargins(Dips.dpToPx(2), Dips.dpToPx(2), Dips.dpToPx(2), Dips.dpToPx(2));

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable ask2 = new Runnable() {

            @Override public void run() {
                if (getActivity() == null) {
                    return;
                }
                AlertDialogs.showDialog(getActivity(),
                        getActivity().getString(R.string.you_neet_to_apply_the_new_settings), getString(R.string.ok),
                        new Runnable() {

                            @Override public void run() {
                                inflate.findViewById(R.id.tabsApply)
                                       .performClick();
                            }
                        }, null);
            }
        };

        final int timeout = 1500;
        final CheckBox isshowPrefAsMenu = inflate.findViewById(R.id.isshowPrefAsMenu);
        isshowPrefAsMenu.setSaveEnabled(false);

        final Runnable dragLinear = new Runnable() {

            @Override public void run() {
                dragLinearLayout.removeAllViews();
                for (UITab tab : UITab.getOrdered()) {

                    // tabs fixed out of the tab bar have no toggle here
                    if (!tab.isVisible()) {
                        continue;
                    }

                    View library = LayoutInflater.from(getActivity())
                                                 .inflate(R.layout.item_tab_line, null, false);
                    if (AppState.get().appTheme == AppState.THEME_DARK_OLED || AppState.get().appTheme == AppState.THEME_DARK) {
                        library.setBackgroundColor(Color.BLACK);
                    }

                    ((TextView) library.findViewById(R.id.text1)).setText(tab.getName());
                    CheckBox isVisible = library.findViewById(R.id.isVisible);
                    isVisible.setSaveEnabled(false);
                    isVisible.setChecked(tab.isVisible());
                    isVisible.setOnCheckedChangeListener(new OnCheckedChangeListener() {

                        @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            handler.removeCallbacks(ask2);
                            handler.postDelayed(ask2, timeout);

                            if (tab == UITab.PrefFragment) {
                                isshowPrefAsMenu.setChecked(!isChecked);
                            }

                        }
                    });
                    ((ImageView) library.findViewById(R.id.image1)).setImageResource(tab.getIcon());
                    //TintUtil.setTintImageWithAlpha(library.findViewById(R.id.image1), TintUtil.COLOR_TINT_GRAY);
                    library.setTag(tab.getIndex());
                    dragLinearLayout.addView(library, layoutParams);
                }

                for (int i = 0; i < dragLinearLayout.getChildCount(); i++) {
                    View child = dragLinearLayout.getChildAt(i);
                    View handle = child.findViewById(R.id.imageDrag);
                    dragLinearLayout.setViewDraggable(child, handle);
                }
            }
        };
        dragLinear.run();

        TxtUtils.underlineTextView(inflate.findViewById(R.id.tabsApply))
                .setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {
                        handler.removeCallbacks(ask2);
                        synchronized (AppState.get().tabsOrder9) {
                            AppState.get().tabsOrder9 = "";
                            for (int i = 0; i < dragLinearLayout.getChildCount(); i++) {
                                View child = dragLinearLayout.getChildAt(i);
                                boolean isVisible = ((CheckBox) child.findViewById(R.id.isVisible)).isChecked();
                                AppState.get().tabsOrder9 += child.getTag() + "#" + (isVisible ? "1" : "0") + ",";
                            }
                            AppState.get().tabsOrder9 = TxtUtils.replaceLast(AppState.get().tabsOrder9, ",", "");
                            LOG.d("tabsApply", AppState.get().tabsOrder9);
                        }

                        if (UITab.isShowCloudsPreferences()) {
                            Clouds.get()
                                  .init(getActivity());
                        }
                        onTheme();
                    }
                });

        isshowPrefAsMenu.setChecked(AppState.get().tabsOrder9.contains(UITab.PrefFragment.index + "#0"));
        isshowPrefAsMenu.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                handler.removeCallbacks(ask2);
                handler.postDelayed(ask2, timeout);
                synchronized (AppState.get().tabsOrder9) {
                    if (isChecked) {
                        // 勾选"显示动画"时，保持 PrefFragment 可见（1=visible）
                        AppState.get().tabsOrder9 = AppState.get().tabsOrder9.replace(UITab.PrefFragment.index + "#0",
                                UITab.PrefFragment.index + "#1");
                    } else {
                        // 取消勾选时，隐藏 PrefFragment（0=invisible）
                        AppState.get().tabsOrder9 = AppState.get().tabsOrder9.replace(UITab.PrefFragment.index + "#1",
                                UITab.PrefFragment.index + "#0");
                    }
                }
                dragLinear.run();
            }
        });

        TxtUtils.underlineTextView(inflate.findViewById(R.id.tabsDefaul))
                .setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {
                        handler.removeCallbacks(ask2);

                        AlertDialogs.showOkDialog(getActivity(),
                                getActivity().getString(R.string.restore_defaults_full), new Runnable() {

                                    @Override public void run() {
                                        synchronized (AppState.get().tabsOrder9) {
                                            AppState.get().tabsOrder9 = AppState.DEFAULTS_TABS_ORDER;
                                        }
                                        onTheme();
                                    }
                                });

                    }
                });

        // tabs position

        section1 = inflate.findViewById(R.id.section1);
        section2 = inflate.findViewById(R.id.section2);
        section4 = inflate.findViewById(R.id.section4);

        // collapsible second-level groups: click header to toggle content
        final View tabsConfigHeader = inflate.findViewById(R.id.tabsConfigHeader);
        final View tabsConfigContainer = inflate.findViewById(R.id.tabsConfigContainer);
        tabsConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                tabsConfigContainer.setVisibility(
                        tabsConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        final View backupConfigHeader = inflate.findViewById(R.id.backupConfigHeader);
        final View backupConfigContainer = inflate.findViewById(R.id.backupConfigContainer);
        backupConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                backupConfigContainer.setVisibility(
                        backupConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        final View themeConfigHeader = inflate.findViewById(R.id.themeConfigHeader);
        final View themeConfigContainer = inflate.findViewById(R.id.themeConfigContainer);
        themeConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                themeConfigContainer.setVisibility(
                        themeConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });


        final View coversConfigHeader = inflate.findViewById(R.id.coversConfigHeader);
        final View coversConfigContainer = inflate.findViewById(R.id.coversConfigContainer);
        coversConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                coversConfigContainer.setVisibility(
                        coversConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });


        final View readingConfigHeader = inflate.findViewById(R.id.readingConfigHeader);
        final View readingConfigContainer = inflate.findViewById(R.id.readingConfigContainer);
        readingConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                readingConfigContainer.setVisibility(
                        readingConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });


        final View libraryDisplayConfigHeader = inflate.findViewById(R.id.libraryDisplayConfigHeader);
        final View libraryDisplayConfigContainer = inflate.findViewById(R.id.libraryDisplayConfigContainer);
        libraryDisplayConfigHeader.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                libraryDisplayConfigContainer.setVisibility(
                        libraryDisplayConfigContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });




        onTintChanged();

        final int max = Dips.pxToDp(Dips.screenMinWH() / 2) - 2 * 4;

        final CustomSeek coverSmallSize = inflate.findViewById(R.id.coverSmallSize);
        coverSmallSize.init(40, max, AppState.get().coverSmallSize);

        coverSmallSize.setOnSeekChanged(new IntegerResponse() {

            @Override public boolean onResultRecive(int result) {
                TempHolder.listHash++;
                AppState.get().coverSmallSize = result;
                return false;
            }
        });

        final CustomSeek coverBigSize = inflate.findViewById(R.id.coverBigSize);
        coverBigSize.init(40, Math.max(max, AppState.get().coverBigSize), AppState.get().coverBigSize);
        coverBigSize.setOnSeekChanged(new IntegerResponse() {

            @Override public boolean onResultRecive(int result) {
                TempHolder.listHash++;
                AppState.get().coverBigSize = result;
                return false;
            }
        });

        final TextView columsCount = inflate.findViewById(R.id.columsCount);
        columsCount.setText("" + Dips.screenWidthDP() / AppState.get().coverBigSize);
        TxtUtils.underlineTextView(columsCount);
        columsCount.setOnClickListener(new OnClickListener() {

            @SuppressLint("NewApi") @Override public void onClick(View v) {
                PopupMenu p = new PopupMenu(getContext(), columsCount);
                for (int i = 1; i <= 8; i++) {
                    final int k = i;
                    p.getMenu()
                     .add("" + k)
                     .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                         @Override public boolean onMenuItemClick(MenuItem item) {
                             int result = Dips.screenWidthDP() / k - 8;

                             TempHolder.listHash++;
                             AppState.get().coverBigSize = result;

                             columsCount.setText("" + k);
                             TxtUtils.underlineTextView(columsCount);

                             coverBigSize.init(40, Math.max(max, AppState.get().coverBigSize),
                                     AppState.get().coverBigSize);
                             return false;
                         }
                     });
                }

                p.show();
            }
        });
        final TextView columsDefaul = inflate.findViewById(R.id.columsDefaul);
        TxtUtils.underlineTextView(columsDefaul);
        columsDefaul.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                if (getActivity() == null) {
                    return;
                }

                AlertDialogs.showOkDialog(getActivity(), getActivity().getString(R.string.restore_defaults_full),
                        new Runnable() {

                            @Override public void run() {
                                IMG.clearDiscCache();
                                IMG.clearMemoryCache();
                                AppState.get().coverBigSize =
                                        (int) (((Dips.screenWidthDP() / (Dips.screenWidthDP() / 110)) - 8) * (
                                                Dips.isXLargeScreen() ? 1.5f : 1));
                                AppState.get().coverSmallSize = 80;
                                TempHolder.listHash++;

                                columsCount.setText("" + Dips.screenWidthDP() / AppState.get().coverBigSize);
                                TxtUtils.underlineTextView(columsCount);

                                coverSmallSize.init(40, max, AppState.get().coverSmallSize);
                                coverBigSize.init(40, Math.max(max, AppState.get().coverBigSize),
                                        AppState.get().coverBigSize);
                            }
                        });

            }
        });

        final ScrollView scrollView = inflate.findViewById(R.id.scroll);
        scrollView.setVerticalScrollBarEnabled(false);

        if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
            scrollView.setBackgroundColor(Color.BLACK);
        }

        // ((TextView) findViewById(R.id.appName)).setText(AppsConfig.APP_NAME);

        // collapsed 软件说明 row: click opens the drawer-style about dialog
        TxtUtils.underlineTextView(inflate.findViewById(R.id.aboutSoftware))
                .setOnClickListener(v -> AboutSectionBinder.showDialog(getActivity()));

        TextView onCloseApp = inflate.findViewById(R.id.onCloseApp);
        TxtUtils.underlineTextView(onCloseApp);
        onCloseApp.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                getActivity().finish();
            }
        });

        final TextView onFullScreen = inflate.findViewById(R.id.fullscreen);

        onFullScreen.setText(DocumentController.getFullScreenName(getActivity(), AppState.get().fullScreenMainMode));

        TxtUtils.underlineTextView(onFullScreen);

        onFullScreen.setOnClickListener(v -> {

            DocumentController.showFullScreenPopup(getActivity(), v, id -> {
                AppState.get().fullScreenMainMode = id;
                onFullScreen.setText(
                        DocumentController.getFullScreenName(getActivity(), AppState.get().fullScreenMainMode));
                TxtUtils.underlineTextView(onFullScreen);
                DocumentController.chooseFullScreen(getActivity(), AppState.get().fullScreenMainMode);
                return true;
            }, AppState.get().fullScreenMainMode);

        });

        final TextView tapPositionTop = inflate.findViewById(R.id.tapPositionTop);

        String tabText = AppState.get().tapPositionTop ? getString(R.string.top) : getString(R.string.bottom);
        tabText += AppState.get().tabWithNames ? "" : " - " + getString(R.string.icons_only);
        tapPositionTop.setText(tabText);

        TxtUtils.underlineTextView(tapPositionTop);

        tapPositionTop.setOnClickListener(v -> {

            MyPopupMenu popup = new MyPopupMenu(getActivity(), v);
            popup.getMenu()
                 .add(R.string.top)
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                     @Override public boolean onMenuItemClick(MenuItem item) {
                         AppState.get().tapPositionTop = true;
                         AppState.get().tabWithNames = true;
                         onTheme();
                         return false;
                     }
                 });

            popup.getMenu()
                 .add(R.string.bottom)
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                     @Override public boolean onMenuItemClick(MenuItem item) {
                         AppState.get().tapPositionTop = false;
                         AppState.get().tabWithNames = true;
                         onTheme();
                         return false;
                     }
                 });

            popup.getMenu()
                 .add(getString(R.string.top) + " - " + getString(R.string.icons_only))
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                     @Override public boolean onMenuItemClick(MenuItem item) {
                         AppState.get().tapPositionTop = true;
                         AppState.get().tabWithNames = false;
                         onTheme();
                         return false;
                     }
                 });

            popup.getMenu()
                 .add(getString(R.string.bottom) + " - " + getString(R.string.icons_only))
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                     @Override public boolean onMenuItemClick(MenuItem item) {
                         AppState.get().tapPositionTop = false;
                         AppState.get().tabWithNames = false;
                         onTheme();
                         return false;
                     }
                 });

            popup.show();

        });

        screenOrientation = inflate.findViewById(R.id.screenOrientation);
        screenOrientation.setText(DocumentController.getRotationText());
        TxtUtils.underlineTextView(screenOrientation);

        screenOrientation.setOnClickListener(new

                                                     OnClickListener() {

                                                         @Override public void onClick(View v) {
                                                             PopupMenu menu = new PopupMenu(v.getContext(), v);
                                                             for (int i =
                                                                  0; i < DocumentController.orientationIds.size(); i++) {
                                                                 final int j = i;
                                                                 final int name =
                                                                         DocumentController.orientationTexts.get(i);
                                                                 menu.getMenu()
                                                                     .add(name)
                                                                     .setOnMenuItemClickListener(
                                                                             new OnMenuItemClickListener() {

                                                                                 @Override
                                                                                 public boolean onMenuItemClick(
                                                                                         MenuItem item) {
                                                                                     AppState.get().orientation =
                                                                                             DocumentController.orientationIds.get(
                                                                                                     j);
                                                                                     screenOrientation.setText(
                                                                                             DocumentController.orientationTexts.get(
                                                                                                     j));
                                                                                     TxtUtils.underlineTextView(
                                                                                             screenOrientation);
                                                                                     DocumentController.doRotation(
                                                                                             getActivity());
                                                                                     return false;
                                                                                 }
                                                                             });
                                                             }
                                                             menu.show();
                                                         }
                                                     });

        // inflate.findViewById(R.id.onHelpTranslate).setOnClickListener(new
        // OnClickListener() {
        //
        // @Override
        // public void onClick(final View v) {
        // Urls.open(getActivity(),
        // "https://www.dropbox.com/sh/8el7kon2sbx46w8/xm3qoHYT7n");
        // }
        // });

        View closeMenu = inflate.findViewById(R.id.closeMenu);
        closeMenu.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                closeLeftMenu();
            }
        });
        closeMenu.setVisibility(TxtUtils.visibleIf(AppState.get().isEnableAccessibility));

        TextView adsSettigns = inflate.findViewById(R.id.adsSettigns);
        adsSettigns.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                ADS.get().showPrivacyOptions(getActivity());
            }
        });
        adsSettigns.setVisibility(TxtUtils.visibleIf(ADS.get().isPrivacyOptionsRequired()));
        TxtUtils.underlineTextView(adsSettigns);

        inflate.findViewById(R.id.onKeyCode)
               .

                       setOnClickListener(new OnClickListener() {

                   @Override public void onClick(final View v) {
                       new KeyCodeDialog(getActivity(), onCloseDialog);
                   }
               });

        CheckBox isEnableAccessibility = inflate.findViewById(R.id.isEnableAccessibility);

        isEnableAccessibility.setChecked(AppState.get().isEnableAccessibility);
        isEnableAccessibility.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppState.get().isEnableAccessibility = isChecked;

            if (isChecked) {
                AppState.get()
                        .accessibilityDefaults();
            } else {
                BookCSS.get().appFontScale = 1.0f;
            }

            onTheme();
        });

        themeColor = inflate.findViewById(R.id.themeColor);
        themeColor.setOnClickListener(new

                                              OnClickListener() {

                                                  @Override public void onClick(final View v) {

                                                      PopupMenu p = new PopupMenu(getContext(), themeColor);
                                                      p.getMenu()
                                                       .add(R.string.system)
                                                       .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                           @Override public boolean onMenuItemClick(MenuItem item) {
                                                               AppState.get().isSystemThemeColor = true;
                                                               AppState.get().appTheme =
                                                                       Dips.isDarkThemeOn() ? AppState.THEME_DARK :
                                                                               AppState.THEME_LIGHT;
                                                               // a day/night mode switch starts from clean
                                                               // display values (stale dim configs survived
                                                               // the switch and left the UI unusably dark)
                                                               AppState.get().resetBrightnessAndFilterToDefaults();
                                                               AppState.get().contrastImage = 0;
                                                               AppState.get().brigtnessImage = 0;
                                                               AppState.get().bolderTextOnImage = false;
                                                               AppState.get().isEnableBCOptional1 = false;

                                                               IMG.clearDiscCache();
                                                               IMG.clearMemoryCache();
                                                               onTheme();

                                                               return false;
                                                           }
                                                       });

                                                      p.getMenu()
                                                       .add(R.string.light)
                                                       .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                           @Override public boolean onMenuItemClick(MenuItem item) {
                                                               AppState.get().isSystemThemeColor = false;
                                                               AppState.get().appTheme = AppState.THEME_LIGHT;
                                                               AppState.get().resetBrightnessAndFilterToDefaults();
                                                               AppState.get().contrastImage = 0;
                                                               AppState.get().brigtnessImage = 0;
                                                               AppState.get().bolderTextOnImage = false;
                                                               AppState.get().isEnableBCOptional1 = false;

                                                               IMG.clearDiscCache();
                                                               IMG.clearMemoryCache();
                                                               onTheme();

                                                               return false;
                                                           }
                                                       });
                                                      p.getMenu()
                                                       .add(R.string.black)
                                                       .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                           @Override public boolean onMenuItemClick(MenuItem item) {
                                                               AppState.get().isSystemThemeColor = false;
                                                               AppState.get().appTheme = AppState.THEME_DARK;
                                                               AppState.get().resetBrightnessAndFilterToDefaults();
                                                               AppState.get().contrastImage = 0;
                                                               AppState.get().brigtnessImage = 0;
                                                               AppState.get().bolderTextOnImage = false;
                                                               AppState.get().isEnableBCOptional1 = false;

                                                               IMG.clearDiscCache();
                                                               IMG.clearMemoryCache();

                                                               onTheme();
                                                               return false;
                                                           }
                                                       });
                                                      p.getMenu()
                                                       .add(R.string.dark_oled)
                                                       .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                           @Override public boolean onMenuItemClick(MenuItem item) {
                                                               AppState.get().isSystemThemeColor = false;
                                                               AppState.get().appTheme = AppState.THEME_DARK_OLED;
                                                               AppState.get().resetBrightnessAndFilterToDefaults();

                                                               AppState.get().contrastImage = 0;
                                                               AppState.get().brigtnessImage = 0;
                                                               AppState.get().bolderTextOnImage = false;
                                                               AppState.get().isEnableBCOptional1 = false;
                                                               AppState.get().tintColor = Color.BLACK;
                                                               AppState.get().isUiTextColor = false;

                                                               IMG.clearDiscCache();
                                                               IMG.clearMemoryCache();

                                                               onTheme();
                                                               return false;
                                                           }
                                                       });
                                                      p.getMenu()
                                                       .add("Ink")
                                                       .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                           @Override public boolean onMenuItemClick(MenuItem item) {
                                                               AppState.get().isSystemThemeColor = false;
                                                               IMG.clearDiscCache();
                                                               IMG.clearMemoryCache();

                                                               onEink();
                                                               return false;
                                                           }
                                                       });
                                                      p.show();
                                                  }
                                              });

        TextView appEngine = inflate.findViewById(R.id.appEngine);
        appEngine.setText("" + AppsConfig.MUPDF_FZ_VERSION);
        TxtUtils.underlineTextView(appEngine);
        Views.visible(appEngine, false /**LOG.isEnable || AppsConfig.IS_PRO**/);

//        appEngine.setOnClickListener(v -> {
//            if (BooksService.isRunning) {
//                Toast.makeText(getActivity(), R.string.please_wait_books_are_being_processed_, Toast.LENGTH_SHORT).show();
//                return;
//            }
//
//            PopupMenu p = new PopupMenu(getContext(), appEngine);
//
//            p.getMenu().add(AppsConfig.ENGINE_MuPDF_1_11).setOnMenuItemClickListener(item -> {
//                AlertDialogs.showDialog(getActivity(), getString(R.string.restart_manually), getString(R.string.ok), new Runnable() {
//                    @Override
//                    public void run() {
//                        AppsConfig.setEngine(getActivity(), AppsConfig.ENGINE_MuPDF_1_11);
//                        android.os.Process.killProcess(android.os.Process.myPid());
//                    }
//                });
//
//                return false;
//            });
//            p.getMenu().add(AppsConfig.ENGINE_MuPDF_LATEST).setOnMenuItemClickListener(item -> {
//                AlertDialogs.showDialog(getActivity(), getString(R.string.restart_manually), getString(R.string.ok), new Runnable() {
//                    @Override
//                    public void run() {
//                        AppsConfig.setEngine(getActivity(), AppsConfig.ENGINE_MuPDF_LATEST);
//                        android.os.Process.killProcess(android.os.Process.myPid());
//                    }
//                });
//
//                return false;
//            });
//
//            p.show();
//        });

        final TextView hypenLang = inflate.findViewById(R.id.appLang);
        hypenLang.setText(DialogTranslateFromTo.getLanuageByCode(AppState.get().appLang));
        TxtUtils.underlineTextView(hypenLang);

        hypenLang.setOnClickListener(new

                                             OnClickListener() {

                                                 @Override public void onClick(View v) {

                                                     final PopupMenu popupMenu = new PopupMenu(v.getContext(), v);

                                                     List<String> langs = new ArrayList<>();
                                                     for (String code : AppState.langCodes) {
                                                         langs.add(DialogTranslateFromTo.getLanuageByCode(
                                                                 code) + ":" + code);
                                                     }
                                                     Collections.sort(langs);

                                                     popupMenu.getMenu()
                                                              .add(R.string.system_language)
                                                              .setOnMenuItemClickListener(
                                                                      new OnMenuItemClickListener() {

                                                                          @Override public boolean onMenuItemClick(
                                                                                  MenuItem item) {
                                                                              TxtUtils.underlineTextView(hypenLang);
                                                                              AppState.get().appLang =
                                                                                      AppState.MY_SYSTEM_LANG;
                                                                              TempHolder.get().forseAppLang = true;
                                                                              MyContextWrapper.wrap(getContext());
                                                                              onTheme();
                                                                              return false;
                                                                          }
                                                                      });

                                                     for (int i = 0; i < langs.size(); i++) {
                                                         String[] all = langs.get(i)
                                                                             .split(":");
                                                         String name = all[0];
                                                         final String code = all[1];

                                                         if (AppsConfig.IS_LOG) {
                                                             name += " [" + code + "]";
                                                         }
                                                         popupMenu.getMenu()
                                                                  .add(name)
                                                                  .setOnMenuItemClickListener(
                                                                          new OnMenuItemClickListener() {

                                                                              @Override public boolean onMenuItemClick(
                                                                                      MenuItem item) {
                                                                                  AppState.get().appLang = code;
                                                                                  TxtUtils.underlineTextView(hypenLang);
                                                                                  onTheme();
                                                                                  return false;
                                                                              }
                                                                          });
                                                     }
                                                     popupMenu.show();

                                                 }
                                             });

        // WebDAV reading-data sync: row shows On/Off, click opens the config dialog
        final TextView webdavSyncValue = inflate.findViewById(R.id.webdavSyncValue);
        refreshWebdavSyncRow(webdavSyncValue);
        TxtUtils.underlineTextView(webdavSyncValue);
        webdavSyncValue.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                WebDavSyncDialog.showDialog(getActivity(), () -> refreshWebdavSyncRow(webdavSyncValue));
            }
        });

        // AI 大模型接入: row shows the model name, click opens the config dialog
        final TextView aiConfigValue = inflate.findViewById(R.id.aiConfigValue);
        refreshAiConfigRow(aiConfigValue);
        TxtUtils.underlineTextView(aiConfigValue);
        aiConfigValue.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                com.foobnix.ai.AiConfigDialog.showDialog(getActivity(), () -> refreshAiConfigRow(aiConfigValue));
            }
        });

        final TextView appFontScale = inflate.findViewById(R.id.appFontScale);
        appFontScale.setText(

                getFontName(BookCSS.get().appFontScale));
        TxtUtils.underlineTextView(appFontScale);
        appFontScale.setOnClickListener(new

                                                OnClickListener() {

                                                    @Override public void onClick(View v) {
                                                        final PopupMenu popupMenu = new PopupMenu(v.getContext(), v);
                                                        for (float i = 0.7f; i < 2.1f; i += 0.1) {
                                                            final float number = i;
                                                            popupMenu.getMenu()
                                                                     .add(getFontName(number))
                                                                     .setOnMenuItemClickListener(
                                                                             new OnMenuItemClickListener() {

                                                                                 @Override
                                                                                 public boolean onMenuItemClick(
                                                                                         MenuItem item) {
                                                                                     BookCSS.get().appFontScale =
                                                                                             number;
                                                                                     onTheme();
                                                                                     return false;
                                                                                 }
                                                                             });
                                                        }
                                                        popupMenu.show();
                                                    }
                                                });

        // onMailSupport / whatIsNew / rate / web / pro / licences rows moved
        // into the 软件说明 dialog (AboutSectionBinder)
        ((ConfLineView) inflate.findViewById(R.id.configLongClick)).init(//
                () -> AppState.get().defaultLongClick,//
                value -> AppState.get().defaultLongClick = value,//
                of(R.string.file_info, AppState.ACTION_BOOK_INFORMATION),//
                of(R.string.book_menu, AppState.ACTION_BOOK_MENU));

        configSingleClick = (ConfLineView) inflate.findViewById(R.id.configSingeClick);
        configSingleClick.init(//
                () -> AppState.get().isRememberMode ? AppSP.get().readingMode:AppState.READING_MODE_SELECT_MODE,//
                value -> {
                    AppState.get().isRememberMode = value != AppState.READING_MODE_SELECT_MODE;
                    AppSP.get().readingMode = value;
                    // an explicit single-tap choice must stick: the hidden
                    // per-format override (isPrefFormatMode, its UI entry was
                    // removed) otherwise silently rewrites readingMode on
                    // every book open and the setting "reverts"
                    AppState.get().isPrefFormatMode = false;
                },//
                of(getString(R.string.select_mode), AppState.READING_MODE_SELECT_MODE),//
                of(AppState.get().nameVerticalMode, AppState.READING_MODE_SCROLL),//
                of(AppState.get().nameHorizontalMode, AppState.READING_MODE_BOOK),//
                of(AppState.get().nameMusicianMode, AppState.READING_MODE_MUSICIAN),//
                of(getString(R.string.tag_manager), AppState.READING_MODE_TAG_MANAGER),//
                of(getString(R.string.open_with), AppState.READING_MODE_OPEN_WITH)//
                              );

        inflate.findViewById(R.id.moreModeSettings)
               .

                       setOnClickListener(new OnClickListener() {
                   @Override public void onClick(View v) {
                       AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                       View view = LayoutInflater.from(getActivity())
                                                 .inflate(R.layout.dialog_custom_reading_modes, null, false);
                       builder.setView(view);

                       EditText prefScrollMode = view.findViewById(R.id.prefScrollMode);
                       EditText prefBookMode = view.findViewById(R.id.prefBookMode);
                       EditText prefMusicianMode = view.findViewById(R.id.prefMusicianMode);

                       prefScrollMode.setText(AppState.get().prefScrollMode);
                       prefBookMode.setText(AppState.get().prefBookMode);
                       prefMusicianMode.setText(AppState.get().prefMusicianMode);

                       CheckBox isPrefFormatMode = view.findViewById(R.id.isPrefFormatMode);
                       isPrefFormatMode.setChecked(AppState.get().isPrefFormatMode);

                       view.findViewById(R.id.prefRestore)
                           .setOnClickListener(new OnClickListener() {
                               @Override public void onClick(View v) {
                                   AlertDialogs.showDialog(getActivity(),
                                           getActivity().getString(R.string.restore_defaults_full),
                                           getString(R.string.ok), new Runnable() {

                                               @Override public void run() {
                                                   AppState.get().isPrefFormatMode = false;
                                                   AppState.get().prefScrollMode = AppState.PREF_SCROLL_MODE;
                                                   AppState.get().prefBookMode = AppState.PREF_BOOK_MODE;
                                                   AppState.get().prefMusicianMode = AppState.PREF_MUSIC_MODE;

                                                   isPrefFormatMode.setChecked(AppState.get().isPrefFormatMode);
                                                   prefScrollMode.setText(AppState.get().prefScrollMode);
                                                   prefBookMode.setText(AppState.get().prefBookMode);
                                                   prefMusicianMode.setText(AppState.get().prefMusicianMode);
                                               }
                                           }, null);

                               }
                           });

                       builder.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {

                           @Override public void onClick(final DialogInterface dialog, final int id) {
                               Keyboards.close(prefScrollMode);
                               AppState.get().isPrefFormatMode = isPrefFormatMode.isChecked();
                               AppState.get().prefScrollMode = prefScrollMode.getText()
                                                                             .toString();
                               AppState.get().prefBookMode = prefBookMode.getText()
                                                                         .toString();
                               AppState.get().prefMusicianMode = prefMusicianMode.getText()
                                                                                 .toString();
                           }
                       });
                       builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {

                           @Override public void onClick(final DialogInterface dialog, final int id) {

                           }
                       });
                       builder.show();
                   }
               });

        LOG.d("CONF-init", 2);

        final CheckBox isCropBookCovers = inflate.findViewById(R.id.isCropBookCovers);
        isCropBookCovers.setOnCheckedChangeListener(null);
        isCropBookCovers.setChecked(AppState.get().isCropBookCovers);
        isCropBookCovers.setOnCheckedChangeListener(new

                                                            OnCheckedChangeListener() {

                                                                @Override public void onCheckedChanged(
                                                                        final CompoundButton buttonView,
                                                                        final boolean isChecked) {
                                                                    AppState.get().isCropBookCovers = isChecked;
                                                                    TempHolder.listHash++;

                                                                }
                                                            });

        final CheckBox isBookCoverEffect = inflate.findViewById(R.id.isBookCoverEffect);
        isBookCoverEffect.setOnCheckedChangeListener(null);
        isBookCoverEffect.setChecked(AppState.get().isBookCoverEffect);
        isBookCoverEffect.setOnCheckedChangeListener(new

                                                             OnCheckedChangeListener() {

                                                                 @Override public void onCheckedChanged(
                                                                         final CompoundButton buttonView,
                                                                         final boolean isChecked) {
                                                                     AppState.get().isBookCoverEffect = isChecked;
                                                                     IMG.clearMemoryCache();
                                                                     IMG.clearDiscCache();

                                                                     TempHolder.listHash++;
                                                                     if (isChecked) {
                                                                         isCropBookCovers.setEnabled(false);
                                                                         isCropBookCovers.setChecked(true);
                                                                     } else {
                                                                         isCropBookCovers.setEnabled(true);
                                                                     }
                                                                 }
                                                             });

        final CheckBox isBorderAndShadow = inflate.findViewById(R.id.isBorderAndShadow);
        isBorderAndShadow.setOnCheckedChangeListener(null);
        isBorderAndShadow.setChecked(AppState.get().isBorderAndShadow);
        isBorderAndShadow.setOnCheckedChangeListener(new

                                                             OnCheckedChangeListener() {

                                                                 @Override public void onCheckedChanged(
                                                                         final CompoundButton buttonView,
                                                                         final boolean isChecked) {
                                                                     AppState.get().isBorderAndShadow = isChecked;
                                                                     TempHolder.listHash++;

                                                                 }
                                                             });

        final CheckBox isShowImages = inflate.findViewById(R.id.isShowImages);
        isShowImages.setOnCheckedChangeListener(null);
        isShowImages.setChecked(AppState.get().isShowImages);
        isShowImages.setOnCheckedChangeListener(new

                                                        OnCheckedChangeListener() {

                                                            @Override public void onCheckedChanged(
                                                                    final CompoundButton buttonView,
                                                                    final boolean isChecked) {
                                                                AppState.get().isShowImages = isChecked;
                                                                TempHolder.listHash++;
                                                                isCropBookCovers.setEnabled(
                                                                        AppState.get().isShowImages);
                                                                isBookCoverEffect.setEnabled(
                                                                        AppState.get().isShowImages);
                                                                isBorderAndShadow.setEnabled(
                                                                        AppState.get().isShowImages);

                                                            }
                                                        });
        isCropBookCovers.setEnabled(AppState.get().isShowImages);
        isBookCoverEffect.setEnabled(AppState.get().isShowImages);
        isBorderAndShadow.setEnabled(AppState.get().isShowImages);

        CheckBox isLoopAutoplay = inflate.findViewById(R.id.isLoopAutoplay);
        isLoopAutoplay.setChecked(AppState.get().isLoopAutoplay);
        isLoopAutoplay.setOnCheckedChangeListener(new

                                                          OnCheckedChangeListener() {

                                                              @Override public void onCheckedChanged(
                                                                      final CompoundButton buttonView,
                                                                      final boolean isChecked) {
                                                                  AppState.get().isLoopAutoplay = isChecked;
                                                              }
                                                          });

        CheckBox isOpenLastBook = inflate.findViewById(R.id.isOpenLastBook);
        isOpenLastBook.setChecked(AppState.get().isOpenLastBook);
        isOpenLastBook.setOnCheckedChangeListener(new

                                                          OnCheckedChangeListener() {

                                                              @Override public void onCheckedChanged(
                                                                      final CompoundButton buttonView,
                                                                      final boolean isChecked) {
                                                                  AppState.get().isOpenLastBook = isChecked;
                                                              }
                                                          });

        CheckBox isRestoreSearchQuery = inflate.findViewById(R.id.isRestoreSearchQuery);
        isRestoreSearchQuery.setChecked(AppState.get().isRestoreSearchQuery);
        isRestoreSearchQuery.setOnCheckedChangeListener(new

                                                                OnCheckedChangeListener() {

                                                                    @Override public void onCheckedChanged(
                                                                            final CompoundButton buttonView,
                                                                            final boolean isChecked) {
                                                                        AppState.get().isRestoreSearchQuery = isChecked;
                                                                    }
                                                                });

        CheckBox lockBooksByDefault = inflate.findViewById(R.id.lockBooksByDefault);
        lockBooksByDefault.setChecked(AppState.get().lockBooksByDefault);
        lockBooksByDefault.setOnCheckedChangeListener(new

                                                              OnCheckedChangeListener() {

                                                                  @Override public void onCheckedChanged(
                                                                          final CompoundButton buttonView,
                                                                          final boolean isChecked) {
                                                                      AppState.get().lockBooksByDefault = isChecked;
                                                                  }
                                                              });

        CheckBox isShowCloseAppDialog = inflate.findViewById(R.id.isShowCloseAppDialog);
        isShowCloseAppDialog.setChecked(AppState.get().isShowCloseAppDialog);
        isShowCloseAppDialog.setOnCheckedChangeListener(new

                                                                OnCheckedChangeListener() {

                                                                    @Override public void onCheckedChanged(
                                                                            final CompoundButton buttonView,
                                                                            final boolean isChecked) {
                                                                        AppState.get().isShowCloseAppDialog = isChecked;
                                                                    }
                                                                });

        final Runnable ask = new Runnable() {

            @Override public void run() {
                LOG.d("timer ask");
                if (getActivity() == null) {
                    return;
                }

                AlertDialogs.showDialog(getActivity(), getActivity().getString(R.string.you_need_to_update_the_library),
                        getString(R.string.ok), new Runnable() {

                            @Override public void run() {
                                onScan();
                            }
                        }, null);
            }
        };

View libPrefView = inflate.findViewById(R.id.moreLybraryettings);
        final LinearLayout moreLibraryConfigContainer = inflate.findViewById(R.id.moreLibraryConfigContainer);
        TxtUtils.underlineTextView(libPrefView).setOnClickListener(v -> {
            boolean expand = moreLibraryConfigContainer.getVisibility() != View.VISIBLE;
            moreLibraryConfigContainer.setVisibility(expand ? View.VISIBLE : View.GONE);
            if (expand) {
                populateLibrarySettings(moreLibraryConfigContainer, handler, ask, timeout);
            }
        });

        ////
        View formatsSettings = inflate.findViewById(R.id.formatsSettings);
        final LinearLayout formatsConfigContainer = inflate.findViewById(R.id.formatsConfigContainer);
        TxtUtils.underlineTextView(formatsSettings).setOnClickListener(v -> {
            boolean expand = formatsConfigContainer.getVisibility() != View.VISIBLE;
            formatsConfigContainer.setVisibility(expand ? View.VISIBLE : View.GONE);
            if (expand) {
                populateFormats(formatsConfigContainer, handler, ask, timeout);
            }
        });

        CheckBox isDisplayAllFilesInFolder = inflate.findViewById(R.id.isDisplayAllFilesInFolder);
        isDisplayAllFilesInFolder.setChecked(AppState.get().isDisplayAllFilesInFolder);
        isDisplayAllFilesInFolder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppState.get().isDisplayAllFilesInFolder = isChecked;
            TempHolder.listHash++;
        });

        CheckBox isAlwaysOpenOnPage1 = inflate.findViewById(R.id.isAlwaysOpenOnPage1);
        isAlwaysOpenOnPage1.setChecked(AppState.get().isAlwaysOpenOnPage1);
        isAlwaysOpenOnPage1.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppState.get().isAlwaysOpenOnPage1 = isChecked;
        });
        // app password
        final CheckBox isAppPassword = inflate.findViewById(R.id.isAppPassword);
        isAppPassword.setChecked(PasswordState.get()
                                              .hasPassword() && AppState.get().isAppPassword);
        isAppPassword.setOnCheckedChangeListener((buttonView, isChecked) -> {

            if (isChecked && PasswordState.get()
                                          .hasPassword()) {
                AppState.get().isAppPassword = true;
            } else if (!PasswordState.get()
                                     .hasPassword()) {
                PasswordDialog.showDialog(getActivity(), true, () -> isAppPassword.setChecked(PasswordState.get()
                                                                                                           .hasPassword()));
            } else {
                AppState.get().isAppPassword = false;
                isAppPassword.setChecked(false);
            }
        });

        TxtUtils.underlineTextView(inflate.findViewById(R.id.appPassword))
                .setOnClickListener(v -> PasswordDialog.showDialog(getActivity(), true, () -> {
                    if (PasswordState.get()
                                     .hasPassword()) {
                        isAppPassword.setChecked(true);
                        AppState.get().isAppPassword = true;
                    }
                }));

        // What is new
        CheckBox showWhatIsNew = inflate.findViewById(R.id.isShowWhatIsNewDialog);
        showWhatIsNew.setChecked(AppState.get().isShowWhatIsNewDialog);
        showWhatIsNew.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                AppState.get().isShowWhatIsNewDialog = isChecked;
            }
        });

        CheckBox isMenuIntegration = inflate.findViewById(R.id.isMenuIntegration);
        isMenuIntegration.setVisibility(TxtUtils.visibleIf(Build.VERSION.SDK_INT >= 23));
        isMenuIntegration.setChecked(AppState.get().isMenuIntegration);
        isMenuIntegration.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                AppState.get().isMenuIntegration = isChecked;
                DocumentController.doContextMenu(getActivity());
            }
        });

        ///

        // BrightnessHelper.controlsWrapper(inflate, getActivity());

        nextKeys = inflate.findViewById(R.id.textNextKeys);
        prevKeys = inflate.findViewById(R.id.textPrevKeys);

        ch = inflate.findViewById(R.id.onReverse);
        ch.setOnCheckedChangeListener(null);
        ch.setChecked(AppState.get().isReverseKeys);
        ch.setOnCheckedChangeListener(reverseListener);

        inflate.findViewById(R.id.onColorChoser)
               .

                       setOnClickListener(new OnClickListener() {

                   @Override public void onClick(final View v) {
                   }
               });

        initKeys();

        TxtUtils.underlineTextView(inflate.findViewById(R.id.importButton))
                .setOnClickListener(v -> PrefDialogs.importDialog(getActivity()));

        TxtUtils.underlineTextView(inflate.findViewById(R.id.exportButton))
                .setOnClickListener(v -> PrefDialogs.exportDialog(getActivity()));

        TxtUtils.underlineTextView(inflate.findViewById(R.id.migrationButton))
                .setOnClickListener(v -> PrefDialogs.migrationDialog(getActivity()));

        // folders

        final TextView rootFolder = inflate.findViewById(R.id.rootFolder);
        TxtUtils.underline(rootFolder, TxtUtils.smallPathFormat(AppSP.get().getRootPath(getActivity())));
        rootFolder.setOnClickListener(v -> ChooserDialogFragment.chooseFolder(getActivity(),
                                                                        AppSP.get().getRootPath(getActivity()))
                                                                .setOnSelectListener(
                                                                        new ResultResponse2<String, Dialog>() {
                                                                            @Override
                                                                            public boolean onResultRecive(String nPath,
                                                                                                          Dialog dialog) {
                                                                                if (new File(nPath).canWrite()) {
                                                                                    AppSP.get().rootPath1 = nPath;
                                                                                    new File(nPath, "Fonts").mkdirs();
                                                                                    TxtUtils.underline(rootFolder,
                                                                                            TxtUtils.smallPathFormat(
                                                                                                    nPath));
                                                                                    onTheme();
                                                                                } else {
                                                                                    Toast.makeText(getActivity(),
                                                                                                 R.string.msg_unexpected_error,
                                                                                                 Toast.LENGTH_LONG)
                                                                                         .show();
                                                                                }
                                                                                dialog.dismiss();
                                                                                return false;
                                                                            }
                                                                        }));

        final TextView fontFolder = inflate.findViewById(R.id.fontFolder);
        TxtUtils.underline(fontFolder, TxtUtils.smallPathFormat(BookCSS.get().fontFolder));
        fontFolder.setOnClickListener(new

                                              OnClickListener() {

                                                  @Override public void onClick(View v) {
                                                      ChooserDialogFragment.chooseFolder(getActivity(),
                                                                                   BookCSS.get().fontFolder)
                                                                           .setOnSelectListener(
                                                                                   new ResultResponse2<String, Dialog>() {
                                                                                       @Override
                                                                                       public boolean onResultRecive(
                                                                                               String nPath,
                                                                                               Dialog dialog) {
                                                                                           BookCSS.get().fontFolder =
                                                                                                   nPath;
                                                                                           TxtUtils.underline(
                                                                                                   fontFolder,
                                                                                                   TxtUtils.smallPathFormat(
                                                                                                           BookCSS.get().fontFolder));
                                                                                           dialog.dismiss();
                                                                                           return false;
                                                                                       }
                                                                                   });
                                                  }
                                              });

        final TextView downloadFolder = inflate.findViewById(R.id.downloadFolder);
        TxtUtils.underline(downloadFolder, TxtUtils.smallPathFormat(BookCSS.get().downlodsPath));
        downloadFolder.setOnClickListener(new

                                                  OnClickListener() {

                                                      @Override public void onClick(View v) {
                                                          ChooserDialogFragment.chooseFolder(getActivity(),
                                                                                       BookCSS.get().downlodsPath)
                                                                               .setOnSelectListener(
                                                                                       new ResultResponse2<String, Dialog>() {
                                                                                           @Override
                                                                                           public boolean onResultRecive(
                                                                                                   String nPath,
                                                                                                   Dialog dialog) {
                                                                                               BookCSS.get().downlodsPath =
                                                                                                       nPath;
                                                                                               TxtUtils.underline(
                                                                                                       downloadFolder,
                                                                                                       TxtUtils.smallPathFormat(
                                                                                                               BookCSS.get().downlodsPath));
                                                                                               dialog.dismiss();
                                                                                               return false;
                                                                                           }
                                                                                       });
                                                      }
                                                  });

        final TextView syncPath = inflate.findViewById(R.id.syncPath);
        TxtUtils.underline(syncPath, TxtUtils.smallPathFormat(BookCSS.get().syncDropboxPath));
        syncPath.setOnClickListener(new

                                            OnClickListener() {

                                                @Override public void onClick(View v) {
                                                    ChooserDialogFragment.chooseFolder(getActivity(),
                                                                                 BookCSS.get().syncDropboxPath)
                                                                         .setOnSelectListener(
                                                                                 new ResultResponse2<String, Dialog>() {
                                                                                     @Override
                                                                                     public boolean onResultRecive(
                                                                                             String nPath,
                                                                                             Dialog dialog) {
                                                                                         BookCSS.get().syncDropboxPath =
                                                                                                 nPath;
                                                                                         TxtUtils.underline(
                                                                                                 downloadFolder,
                                                                                                 TxtUtils.smallPathFormat(
                                                                                                         BookCSS.get().syncDropboxPath));
                                                                                         dialog.dismiss();
                                                                                         return false;
                                                                                     }
                                                                                 });
                                                }
                                            });

        final TextView ttsFolder = inflate.findViewById(R.id.ttsFolder);
        TxtUtils.underline(ttsFolder, TxtUtils.smallPathFormat(BookCSS.get().ttsSpeakPath));
        ttsFolder.setOnClickListener(new

                                             OnClickListener() {

                                                 @Override public void onClick(View v) {
                                                     ChooserDialogFragment.chooseFolder(getActivity(),
                                                                                  BookCSS.get().ttsSpeakPath)
                                                                          .setOnSelectListener(
                                                                                  new ResultResponse2<String, Dialog>() {
                                                                                      @Override
                                                                                      public boolean onResultRecive(
                                                                                              String nPath,
                                                                                              Dialog dialog) {
                                                                                          BookCSS.get().ttsSpeakPath =
                                                                                                  nPath;
                                                                                          TxtUtils.underline(ttsFolder,
                                                                                                  TxtUtils.smallPathFormat(
                                                                                                          BookCSS.get().ttsSpeakPath));
                                                                                          dialog.dismiss();
                                                                                          return false;
                                                                                      }
                                                                                  });
                                                 }
                                             });

        final TextView backupPath = inflate.findViewById(R.id.backupFolder);
        TxtUtils.underline(backupPath, TxtUtils.smallPathFormat(BookCSS.get().backupPath));
        backupPath.setOnClickListener(new

                                              OnClickListener() {

                                                  @Override public void onClick(View v) {
                                                      ChooserDialogFragment.chooseFolder(getActivity(),
                                                                                   BookCSS.get().backupPath)
                                                                           .setOnSelectListener(
                                                                                   new ResultResponse2<String, Dialog>() {
                                                                                       @Override
                                                                                       public boolean onResultRecive(
                                                                                               String nPath,
                                                                                               Dialog dialog) {
                                                                                           BookCSS.get().backupPath =
                                                                                                   nPath;
                                                                                           TxtUtils.underline(
                                                                                                   backupPath,
                                                                                                   TxtUtils.smallPathFormat(
                                                                                                           BookCSS.get().backupPath));
                                                                                           dialog.dismiss();
                                                                                           return false;
                                                                                       }
                                                                                   });
                                                  }
                                              });

        // Widget Configuration

        final TextView widgetLayout = inflate.findViewById(R.id.widgetLayout);
        widgetLayout.setText(AppState.get().widgetType == AppState.WIDGET_LIST ? R.string.list : R.string.grid);
        TxtUtils.underlineTextView(widgetLayout);

        widgetLayout.setOnClickListener(new

                                                OnClickListener() {

                                                    @Override public void onClick(View v) {
                                                        final PopupMenu popupMenu = new PopupMenu(v.getContext(), v);

                                                        final MenuItem recent = popupMenu.getMenu()
                                                                                         .add(R.string.list);
                                                        recent.setOnMenuItemClickListener(
                                                                new OnMenuItemClickListener() {

                                                                    @Override public boolean onMenuItemClick(
                                                                            final MenuItem item) {
                                                                        AppState.get().widgetType =
                                                                                AppState.WIDGET_LIST;
                                                                        widgetLayout.setText(R.string.list);
                                                                        TxtUtils.underlineTextView(widgetLayout);
                                                                        RecentUpates.updateAll();
                                                                        return false;
                                                                    }
                                                                });

                                                        final MenuItem starred = popupMenu.getMenu()
                                                                                          .add(R.string.grid);
                                                        starred.setOnMenuItemClickListener(
                                                                new OnMenuItemClickListener() {

                                                                    @Override public boolean onMenuItemClick(
                                                                            final MenuItem item) {
                                                                        AppState.get().widgetType =
                                                                                AppState.WIDGET_GRID;
                                                                        widgetLayout.setText(R.string.grid);
                                                                        TxtUtils.underlineTextView(widgetLayout);
                                                                        RecentUpates.updateAll();
                                                                        return false;
                                                                    }
                                                                });

                                                        popupMenu.show();

                                                    }

                                                });

        final TextView widgetForRecent = inflate.findViewById(R.id.widgetForRecent);
        widgetForRecent.setText(AppState.get().isStarsInWidget ? R.string.starred : R.string.recent);
        TxtUtils.underlineTextView(widgetForRecent);

        widgetForRecent.setOnClickListener(new

                                                   OnClickListener() {

                                                       @Override public void onClick(View v) {
                                                           final PopupMenu popupMenu =
                                                                   new PopupMenu(widgetForRecent.getContext(),
                                                                           widgetForRecent);

                                                           final MenuItem recent = popupMenu.getMenu()
                                                                                            .add(R.string.recent);
                                                           recent.setOnMenuItemClickListener(
                                                                   new OnMenuItemClickListener() {

                                                                       @Override public boolean onMenuItemClick(
                                                                               final MenuItem item) {
                                                                           AppState.get().isStarsInWidget = false;
                                                                           widgetForRecent.setText(
                                                                                   AppState.get().isStarsInWidget ?
                                                                                           R.string.starred :
                                                                                           R.string.recent);
                                                                           TxtUtils.underlineTextView(widgetForRecent);

                                                                           RecentUpates.updateAll();
                                                                           return false;
                                                                       }
                                                                   });

                                                           final MenuItem starred = popupMenu.getMenu()
                                                                                             .add(R.string.starred);
                                                           starred.setOnMenuItemClickListener(
                                                                   new OnMenuItemClickListener() {

                                                                       @Override public boolean onMenuItemClick(
                                                                               final MenuItem item) {
                                                                           AppState.get().isStarsInWidget = true;
                                                                           widgetForRecent.setText(
                                                                                   AppState.get().isStarsInWidget ?
                                                                                           R.string.starred :
                                                                                           R.string.recent);
                                                                           TxtUtils.underlineTextView(widgetForRecent);

                                                                           RecentUpates.updateAll();
                                                                           return false;
                                                                       }
                                                                   });

                                                           popupMenu.show();

                                                       }

                                                   });

        final TextView widgetItemsCount = inflate.findViewById(R.id.widgetItemsCount);
        widgetItemsCount.setText("" + AppState.get().widgetItemsCount);
        TxtUtils.underlineTextView(widgetItemsCount);
        widgetItemsCount.setOnClickListener(new

                                                    OnClickListener() {

                                                        @SuppressLint("NewApi") @Override public void onClick(View v) {
                                                            PopupMenu p = new PopupMenu(getContext(), columsCount);
                                                            for (int i = 1; i <= 50; i++) {
                                                                final int k = i;
                                                                p.getMenu()
                                                                 .add("" + k)
                                                                 .setOnMenuItemClickListener(
                                                                         new OnMenuItemClickListener() {

                                                                             @Override public boolean onMenuItemClick(
                                                                                     MenuItem item) {
                                                                                 AppState.get().widgetItemsCount = k;
                                                                                 widgetItemsCount.setText("" + k);
                                                                                 TxtUtils.underlineTextView(
                                                                                         widgetItemsCount);
                                                                                 RecentUpates.updateAll();
                                                                                 return false;
                                                                             }
                                                                         });
                                                            }

                                                            p.show();
                                                        }
                                                    });

        // dictionary
        isRememberDictionary = inflate.findViewById(R.id.isRememberDictionary);
        isRememberDictionary.setChecked(AppState.get().isRememberDictionary);
        isRememberDictionary.setOnCheckedChangeListener(new

                                                                OnCheckedChangeListener() {

                                                                    @Override public void onCheckedChanged(
                                                                            final CompoundButton buttonView,
                                                                            final boolean isChecked) {
                                                                        AppState.get().isRememberDictionary = isChecked;
                                                                    }
                                                                });

        selectedDictionaly = inflate.findViewById(R.id.selectedDictionaly);
        selectedDictionaly.setText(DialogTranslateFromTo.getSelectedDictionaryUnderline());
        selectedDictionaly.setOnClickListener(new

                                                      OnClickListener() {

                                                          @Override public void onClick(View v) {
                                                              DialogTranslateFromTo.show(getActivity(), false,
                                                                      new Runnable() {

                                                                          @Override public void run() {
                                                                              selectedDictionaly.setText(
                                                                                      DialogTranslateFromTo.getSelectedDictionaryUnderline());
                                                                          }
                                                                      }, false);
                                                          }
                                                      });

        textDayColor = inflate.findViewById(R.id.onDayColor);
        textDayColor.setOnClickListener(new

                                                OnClickListener() {

                                                    @Override public void onClick(View v) {
                                                        new ColorsDialog(getActivity(), true,
                                                                AppState.get().colorDayText, AppState.get().colorDayBg,
                                                                AppState.get().colorDayForeground, false, true,
                                                                new ColorsDialogResult() {

                                                                    @Override public void onChooseColor(int colorText,
                                                                                                        int colorBg,
                                                                                                        int colorForeground) {
                                                                        textDayColor.setTextColor(colorText);
                                                                        textDayColor.setBackgroundColor(colorBg);

                                                                        AppState.get().colorDayText = colorText;
                                                                        AppState.get().colorDayBg = colorBg;
                                                                        AppState.get().colorDayForeground =
                                                                                colorForeground;

                                                                        IMG.clearDiscCache();
                                                                        IMG.clearMemoryCache();
                                                                    }
                                                                });
                                                    }
                                                });

        textNigthColor = inflate.findViewById(R.id.onNigthColor);
        textNigthColor.setOnClickListener(new

                                                  OnClickListener() {

                                                      @Override public void onClick(View v) {
                                                          new ColorsDialog(getActivity(), false,
                                                                  AppState.get().colorNigthText,
                                                                  AppState.get().colorNigthBg,
                                                                  AppState.get().colorNigthForeground, false, true,
                                                                  new ColorsDialogResult() {

                                                                      @Override public void onChooseColor(int colorText,
                                                                                                          int colorBg,
                                                                                                          int colorForeground) {
                                                                          textNigthColor.setTextColor(colorText);
                                                                          textNigthColor.setBackgroundColor(colorBg);

                                                                          AppState.get().colorNigthText = colorText;
                                                                          AppState.get().colorNigthBg = colorBg;
                                                                          AppState.get().colorNigthForeground =
                                                                                  colorForeground;

                                                                      }
                                                                  });
                                                      }
                                                  });

        TextView onDefalt = TxtUtils.underlineTextView(inflate.findViewById(R.id.onDefaultColor));
        onDefalt.setOnClickListener(new

                                            OnClickListener() {

                                                @Override public void onClick(View v) {
                                                    AppState.get().colorDayText = AppState.COLOR_BLACK;
                                                    AppState.get().colorDayBg = AppState.COLOR_WHITE;

                                                    textDayColor.setTextColor(AppState.COLOR_BLACK);
                                                    textDayColor.setBackgroundColor(AppState.COLOR_WHITE);

                                                    AppState.get().colorNigthText = AppState.COLOR_WHITE;
                                                    AppState.get().colorNigthBg = AppState.COLOR_BLACK;

                                                    textNigthColor.setTextColor(AppState.COLOR_WHITE);
                                                    textNigthColor.setBackgroundColor(AppState.COLOR_BLACK);
                                                }
                                            });

        //color
        {
            LinearLayout colorsLine = inflate.findViewById(R.id.colorsLine);
            colorsLine.removeAllViews();

            for (String color : AppState.STYLE_COLORS) {
                View view = inflater.inflate(R.layout.item_color, (ViewGroup) inflate, false);
                view.setBackgroundColor(Color.TRANSPARENT);
                final int intColor = Color.parseColor(color);
                final View img = view.findViewById(R.id.itColor);
                img.setBackgroundColor(intColor);
                img.setContentDescription(getString(R.string.color));

                colorsLine.addView(view, new LayoutParams(Dips.dpToPx(30), Dips.dpToPx(30)));

                view.setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {
                        TintUtil.color = intColor;
                        AppState.get().tintColor = intColor;
                        TempHolder.listHash++;

                        onTintChanged();
                        sendNotifyTintChanged();

                        AppProfile.save(getActivity());

                    }
                });
            }

            View view = inflater.inflate(R.layout.item_color, (ViewGroup) inflate, false);
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setContentDescription(getString(R.string.color));
            final ImageView img = view.findViewById(R.id.itColor);
            img.setColorFilter(

                    getResources().

                                          getColor(R.color.tint_gray));
            img.setImageResource(R.drawable.glyphicons_371_plus);
            img.setBackgroundColor(AppState.get().userColor);
            colorsLine.addView(view, new

                    LayoutParams(Dips.dpToPx(30), Dips.

                                                              dpToPx(30)));

            view.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    new HSVColorPickerDialog(getContext(), AppState.get().userColor, new OnColorSelectedListener() {

                        @Override public void colorSelected(Integer color) {
                            AppState.get().userColor = color;
                            AppState.get().tintColor = color;
                            TintUtil.color = color;
                            img.setBackgroundColor(color);

                            onTintChanged();
                            sendNotifyTintChanged();

                            AppProfile.save(getActivity());

                            TempHolder.listHash++;

                        }
                    }).show();

                }
            });
        }
        ///end colors
        ////
        {
            Runnable onAccent = new Runnable() {
                @Override public void run() {
                    if (AppState.get().isUiTextColor && AppState.get().uiTextColorUser != AppState.get().tintColor) {
                        AppState.get().statusBarColorDay = AppState.get().uiTextColorUser;
                        AppState.get().statusBarColorNight = AppState.get().uiTextColorUser;
                    } else {
                        AppState.get().statusBarColorDay = Color.parseColor(AppState.TEXT_COLOR_DAY);
                        AppState.get().statusBarColorNight = Color.parseColor(AppState.TEXT_COLOR_NIGHT);
                    }

                    TempHolder.listHash++;
                    onTintChanged();
                    sendNotifyTintChanged();
                    ((MainTabs2) getActivity()).updateCurrentFragment();

                    //TxtUtils.updateAllLinks(inflate, true);
                }
            };

            LinearLayout colorsLine = inflate.findViewById(R.id.colorsLine_a);
            colorsLine.removeAllViews();

            CheckBox isAccentTextColor = inflate.findViewById(R.id.isAccentTextColor);

            isAccentTextColor.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    AppState.get().isUiTextColor = b;
                    onAccent.run();
                }
            });
            isAccentTextColor.setChecked(AppState.get().isUiTextColor);

            for (String color : AppState.ACCENT_COLORS) {
                final int intColor = Color.parseColor(color);
                if (AppState.get().appTheme == AppState.THEME_LIGHT || AppState.get().appTheme == AppState.THEME_INK) {
                    if (intColor == Color.WHITE) {
                        continue;
                    }
                }
                if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
                    if (intColor == Color.BLACK) {
                        continue;
                    }
                }

                View view = inflater.inflate(R.layout.item_color, (ViewGroup) inflate, false);
                view.setBackgroundColor(Color.TRANSPARENT);

                final View img = view.findViewById(R.id.itColor);
                img.setBackgroundColor(intColor);
                img.setContentDescription(getString(R.string.color));

                colorsLine.addView(view, new LayoutParams(Dips.dpToPx(30), Dips.dpToPx(30)));

                view.setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {

                        AppState.get().isUiTextColor = true;
                        AppState.get().uiTextColor = intColor;
                        AppState.get().uiTextColorUser = intColor;

                        isAccentTextColor.setChecked(AppState.get().isUiTextColor);

                        onAccent.run();

                    }
                });
            }

            View view = inflater.inflate(R.layout.item_color, (ViewGroup) inflate, false);
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setContentDescription(getString(R.string.color));
            final ImageView img = view.findViewById(R.id.itColor);
            img.setColorFilter(getResources().getColor(R.color.tint_gray));
            img.setImageResource(R.drawable.glyphicons_371_plus);
            img.setBackgroundColor(AppState.get().userColor);
            colorsLine.addView(view, new LayoutParams(Dips.dpToPx(30), Dips.dpToPx(30)));

            view.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    new HSVColorPickerDialog(getContext(), AppState.get().userColor, new OnColorSelectedListener() {

                        @Override public void colorSelected(Integer color) {
                            AppState.get().isUiTextColor = true;
                            AppState.get().uiTextColor = color;
                            AppState.get().uiTextColorUser = color;

                            img.setBackgroundColor(color);
                            isAccentTextColor.setChecked(AppState.get().isUiTextColor);

                            onAccent.run();

                        }
                    }).show();

                }
            });
        }
        ////

        underline(inflate.findViewById(R.id.linksColor)).

                                                                setOnClickListener(new OnClickListener() {

            @Override public void onClick(final View v) {
                closeLeftMenu();
                Dialogs.showLinksColorDialog(getActivity(), new Runnable() {

                    @Override public void run() {
                        TempHolder.listHash++;
                        onTintChanged();
                        sendNotifyTintChanged();
                        ((MainTabs2) getActivity()).updateCurrentFragment();

                        //TxtUtils.updateAllLinks(inflate, true);

                    }
                });
            }
        });

        ///link colors

        ////

        underline(inflate.findViewById(R.id.onContrast)).

                                                                setOnClickListener(new OnClickListener() {

            @Override public void onClick(final View v) {
                Dialogs.showContrastDialogByUrl(getActivity(), new Runnable() {

                    @Override public void run() {
                        IMG.clearDiscCache();
                        IMG.clearMemoryCache();
                        TempHolder.listHash++;
                        notifyFragment();

                    }
                });
            }
        });

        inflate.findViewById(R.id.cleanRecent)
               .

                       setOnClickListener(new View.OnClickListener() {

                   @Override public void onClick(final View v) {
                       final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

                       builder.setMessage(getString(R.string.clear_all_recent) + "?");
                       builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {

                           @Override public void onClick(DialogInterface dialog, int which) {
                               //BookmarksData.get().cleanRecent();
                           }
                       });
                       builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {

                           @Override public void onClick(DialogInterface dialog, int which) {
                               // TODO Auto-generated method stub

                           }
                       });
                       builder.show();
                   }
               });

        inflate.findViewById(R.id.cleanBookmarks)
               .

                       setOnClickListener(new View.OnClickListener() {

                   @Override public void onClick(final View v) {
                       final AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

                       builder.setMessage(getString(R.string.clear_all_bookmars) + "?");
                       builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {

                           @Override public void onClick(DialogInterface dialog, int which) {
                               BookmarksData.get()
                                            .cleanBookmarks();

                           }
                       });
                       builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {

                           @Override public void onClick(DialogInterface dialog, int which) {
                               // TODO Auto-generated method stub

                           }
                       });
                       builder.show();

                   }
               });

        // convert
        final TextView docConverter = inflate.findViewById(R.id.docConverter);
        TxtUtils.underlineTextView(docConverter);
        docConverter.setOnClickListener(new

                                                OnClickListener() {

                                                    @Override public void onClick(View v) {
                                                        PopupMenu p = new PopupMenu(getContext(), v);
                                                        for (final String id : AppState.CONVERTERS.keySet()) {
                                                            p.getMenu()
                                                             .add("" + getActivity().getString(
                                                                     R.string.convert_to) + " " + id)
                                                             .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                                                 @Override
                                                                 public boolean onMenuItemClick(MenuItem item) {
                                                                     ShareDialog.showsItemsDialog(getActivity(),
                                                                             getActivity().getString(
                                                                                     R.string.convert_to) + " " + id,
                                                                             AppState.CONVERTERS.get(id));

                                                                     return false;
                                                                 }
                                                             });

                                                        }
                                                        p.show();
                                                    }
                                                });

        statusBarHack = getActivity().findViewById(R.id.systemBarHack);

        overlay =

                getActivity().

                                     findViewById(R.id.overlay);

        TextView onProfile = inflate.findViewById(R.id.onProfile);
        TextView restoreDefaultProfile = inflate.findViewById(R.id.restoreDefaultProfile);

        profileLetter = inflate.findViewById(R.id.profileLetter);

        final String p = AppProfile.getCurrent();

        profileLetter.setText(TxtUtils.getFirstLetter(p));
        profileLetter.setBackgroundDrawable(AppProfile.getProfileColorDrawable(

                getActivity(), p));

        onProfile.setText(p);

        profileLetter.setContentDescription(p + " " + getString(R.string.profile));
        onProfile.setContentDescription(p + " " + getString(R.string.profile));

        TxtUtils.underlineTextView(onProfile);
        TxtUtils.underlineTextView(restoreDefaultProfile);
        onProfile.setOnClickListener(v ->

        {

            if (BooksService.isRunning) {
                Toast.makeText(getActivity(), R.string.please_wait_books_are_being_processed_, Toast.LENGTH_SHORT)
                     .show();
                return;
            }

            MyPopupMenu popup = new MyPopupMenu(getActivity(), v);

            List<String> all = AppProfile.getAllProfiles();
            for (String profile : all) {

                popup.getMenu()
                     .setDrawable(TxtUtils.getFirstLetter(profile),
                             AppProfile.getProfileColorDrawable(getActivity(), profile))
                     .add(profile)
                     .setOnMenuItemClickListener(menu -> {
                         {
                             if (!profile.equals(AppProfile.getCurrent())) {

                                 AlertDialogs.showOkDialog(getActivity(),
                                         getActivity().getString(R.string.do_you_want_to_switch_profile_),
                                         new Runnable() {

                                             @Override public void run() {
                                                 AppProfile.saveCurrent(getActivity(), profile);
                                                 RecentUpates.updateAll();
                                                 onTheme();
                                             }
                                         });
                             }

                             return false;
                         }
                     });
            }
            popup.show();

        });
        profileLetter.setOnClickListener(v -> onProfile.performClick());

        final View.OnLongClickListener onDefaultProfile = v -> {

            if (BooksService.isRunning) {
                Toast.makeText(getActivity(), R.string.please_wait_books_are_being_processed_, Toast.LENGTH_SHORT)
                     .show();
                return true;
            }

            AlertDialogs.showOkDialog(getActivity(), getString(R.string.restore_defaults_full), new Runnable() {
                @Override public void run() {
                    //AppProfile.clear();
                    DragingPopup.resetCache(getActivity());

                    CacheZipUtils.emptyAllCacheDirs();


                    final BookCSS b = new BookCSS();
                    b.resetToDefault(getActivity());
                    IO.writeObjSync(AppProfile.syncCSS, b);

                    final AppState o = new AppState();
                    o.defaults(getActivity());

                    IO.writeObjSync(AppProfile.syncState, o);

                    AppProfile.syncExclude.delete();

                    File rootFiles = AppProfile.SYNC_FOLDER_DEVICE_PROFILE;
                    if (rootFiles != null && rootFiles.listFiles()!=null) {
                        for (File file : rootFiles.listFiles()) {
                            String name = file.getName();
                            if (name.endsWith(".css")) {
                                file.delete();
                                LOG.d("Delete-css", file);

                            }
                        }
                    }

                    //AppProfile.init(getActivity());
                    //BooksService.startForeground(getActivity(), BooksService.ACTION_SEARCH_ALL);
                    SearchAllBooksWorker.run(getActivity());
                    onTheme();

                }
            });

            return true;
        };
        onProfile.setOnLongClickListener(onDefaultProfile);
        restoreDefaultProfile.setOnClickListener(v -> onDefaultProfile.onLongClick(v));
        profileLetter.setOnLongClickListener(onDefaultProfile);

        inflate.findViewById(R.id.onProfileEdit)
               .

                       setOnClickListener(v ->

               {

                   if (BooksService.isRunning) {
                       Toast.makeText(getActivity(), R.string.please_wait_books_are_being_processed_,
                                    Toast.LENGTH_SHORT)
                            .show();
                       return;
                   }

                   AppProfile.showDialog(getActivity(), profile -> {
                       if (!profile.equals(AppProfile.getCurrent())) {
                           AlertDialogs.showOkDialog(getActivity(),
                                   getActivity().getString(R.string.do_you_want_to_switch_profile_), new Runnable() {

                                       @Override public void run() {
                                           AppProfile.saveCurrent(getActivity(), profile);
                                           onTheme();
                                       }
                                   });
                       }
                       return false;
                   });
               });

        TxtUtils.updateAllLinks(inflate, true);
        TintUtil.setBackgroundFillColor(panelRecent, TintUtil.color);
        return inflate;

    }

    private void populateFormats(final LinearLayout root, final Handler handler, final Runnable ask, final int timeout) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        root.removeAllViews();
        final List<FormatEntry> entries = formatEntries(activity);
        final List<View> rows = new ArrayList<View>();
        final List<TextView> countViews = new ArrayList<TextView>();

        for (final FormatEntry entry : entries) {
            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Dips.dpToPx(5), Dips.dpToPx(2), Dips.dpToPx(5), Dips.dpToPx(2));

            final CheckBox check = new CheckBox(activity);
            check.setText(entry.label);
            check.setChecked(entry.getter.get());
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                entry.setter.accept(isChecked);
                ExtUtils.updateSearchExts();
                handler.removeCallbacks(ask);
                handler.postDelayed(ask, timeout);
            });

            final TextView count = new TextView(activity);
            count.setGravity(Gravity.RIGHT);
            count.setTextColor(Color.GRAY);
            count.setPadding(Dips.dpToPx(5), 0, Dips.dpToPx(5), 0);

            row.addView(check, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(count, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rows.add(row);
            countViews.add(count);
        }

        for (View row : rows) {
            root.addView(row);
        }

        AppsConfig.executorService.execute(() -> {
            final Map<String, Long> extCounts = AppDB.get().getExtCounts();
            activity.runOnUiThread(() -> {
                for (int i = 0; i < entries.size(); i++) {
                    long sum = 0;
                    for (String ext : entries.get(i).exts) {
                        Long c = extCounts.get(ext);
                        if (c != null) {
                            sum += c;
                        }
                    }
                    countViews.get(i).setText("" + sum);
                }
            });
        });
    }

    private static List<FormatEntry> formatEntries(final Context context) {
        List<FormatEntry> entries = new ArrayList<FormatEntry>();
        entries.add(new FormatEntry("PDF", Arrays.asList("pdf"),
                () -> AppState.get().supportPDF, v -> AppState.get().supportPDF = v));
        entries.add(new FormatEntry("DJVU", Arrays.asList("djvu"),
                () -> AppState.get().supportDJVU, v -> AppState.get().supportDJVU = v));
        entries.add(new FormatEntry("FB2", Arrays.asList("fb2"),
                () -> AppState.get().supportFB2, v -> AppState.get().supportFB2 = v));
        entries.add(new FormatEntry("MOBI/AZW", Arrays.asList("mobi", "azw", "azw3"),
                () -> AppState.get().supportMOBI, v -> AppState.get().supportMOBI = v));
        entries.add(new FormatEntry("EPUB", Arrays.asList("epub"),
                () -> AppState.get().supportEPUB, v -> AppState.get().supportEPUB = v));
        entries.add(new FormatEntry("XPS", Arrays.asList("xps"),
                () -> AppState.get().supportXPS, v -> AppState.get().supportXPS = v));
        entries.add(new FormatEntry("DOC/DOCX", Arrays.asList("doc", "docx"),
                () -> AppState.get().supportDOCX, v -> AppState.get().supportDOCX = v));
        entries.add(new FormatEntry("RTF", Arrays.asList("rtf"),
                () -> AppState.get().supportRTF, v -> AppState.get().supportRTF = v));
        entries.add(new FormatEntry("ODT", Arrays.asList("odt"),
                () -> AppState.get().supportODT, v -> AppState.get().supportODT = v));
        entries.add(new FormatEntry("CBZ/CBR", Arrays.asList("cbz", "cbr"),
                () -> AppState.get().supportCBZ, v -> AppState.get().supportCBZ = v));
        entries.add(new FormatEntry("ZIP", Arrays.asList("zip", "okular"),
                () -> AppState.get().supportZIP, v -> AppState.get().supportZIP = v));
        entries.add(new FormatEntry("HTML/TXT", Arrays.asList("txt", "html", "xhtml", "mhtml", "shtml", "md"),
                () -> AppState.get().supportTXT, v -> AppState.get().supportTXT = v));
        entries.add(new FormatEntry(context.getString(R.string.archives), stripDots(ExtUtils.archiveExts),
                () -> AppState.get().supportArch, v -> AppState.get().supportArch = v));

        List<String> otherExts = new ArrayList<String>(stripDots(ExtUtils.otherExts));
        otherExts.addAll(stripDots(ExtUtils.lirbeExt));
        otherExts.add("prc");
        otherExts.add("pdb");
        entries.add(new FormatEntry(context.getString(R.string.other), otherExts,
                () -> AppState.get().supportOther, v -> AppState.get().supportOther = v));
        return entries;
    }

    private static List<String> stripDots(List<String> exts) {
        List<String> result = new ArrayList<String>();
        for (String ext : exts) {
            result.add(ext.startsWith(".") ? ext.substring(1) : ext);
        }
        return result;
    }

    private static class FormatEntry {
        final String label;
        final List<String> exts;
        final Supplier<Boolean> getter;
        final Consumer<Boolean> setter;

        FormatEntry(String label, List<String> exts, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            this.label = label;
            this.exts = exts;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private void onEink() {
        AppState.get().appTheme = AppState.THEME_INK;
        AppState.get().blueLightAlpha = 0;
        AppState.get().tintColor = Color.BLACK;
        AppState.get().uiTextColor = Color.BLACK;
        AppState.get().isUiTextColor = true;
        TintUtil.color = Color.BLACK;

        onTintChanged();
        sendNotifyTintChanged();

        AppProfile.save(getActivity());

        getActivity().finish();
        MainTabs2.startActivity(getActivity(), TempHolder.get().currentTab);

    }

    public View underline(View text) {
        CharSequence myText = ((TextView) text).getText();
        ((TextView) text).setText(Html.fromHtml("<u>" + myText + "</u>", Html.FROM_HTML_MODE_LEGACY));
        return text;
    }

    private void populateLibrarySettings(final LinearLayout root, final Handler handler, final Runnable ask, final int timeout) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        root.removeAllViews();

        final CheckBox isScanOnLaunch = new CheckBox(activity);
        isScanOnLaunch.setText(getString(R.string.scan_for_new_books_at_launch));

        final CheckBox isFirstSurname = new CheckBox(activity);
        isFirstSurname.setText(getString(R.string.in_the_author_s_name_first_the_surname));

        final CheckBox isSkipFolderWithNOMEDIA = new CheckBox(activity);
        isSkipFolderWithNOMEDIA.setText(getString(R.string.ignore_folder_scan_if_nomedia_file_exists));

        final CheckBox isAuthorTitleFromMetaPDF = new CheckBox(activity);
        isAuthorTitleFromMetaPDF.setText(
                R.string.displaying_the_author_and_title_of_the_pdf_book_from_the_meta_tags);

        final CheckBox isShowOnlyOriginalFileNames = new CheckBox(activity);
        isShowOnlyOriginalFileNames.setText(R.string.display_original_file_names_without_metadata);

        final CheckBox isUseCalibreOpf = new CheckBox(activity);
        isUseCalibreOpf.setText(getString(R.string.use_calibre_metadata));

        final CheckBox isDisplayAnnotation = new CheckBox(activity);
        isDisplayAnnotation.setText(getString(R.string.show_book_description));

        final CheckBox isHideReadBook = new CheckBox(activity);
        isHideReadBook.setText(getString(R.string.hide_read_books));

        final CheckBox isShowSeriesNumberInTitle = new CheckBox(activity);
        isShowSeriesNumberInTitle.setText(getString(R.string.show_series_number_in_title));

        root.addView(isScanOnLaunch);
        root.addView(isFirstSurname);
        root.addView(isSkipFolderWithNOMEDIA);
        root.addView(isAuthorTitleFromMetaPDF);
        root.addView(isShowOnlyOriginalFileNames);
        root.addView(isUseCalibreOpf);
        root.addView(isDisplayAnnotation);
        root.addView(isHideReadBook);
        root.addView(isShowSeriesNumberInTitle);

        isScanOnLaunch.setChecked(AppState.get().isScanOnLaunch);
        isFirstSurname.setChecked(AppState.get().isFirstSurname);
        isSkipFolderWithNOMEDIA.setChecked(AppState.get().isSkipFolderWithNOMEDIA);
        isAuthorTitleFromMetaPDF.setChecked(AppState.get().isAuthorTitleFromMetaPDF);
        isShowOnlyOriginalFileNames.setChecked(AppState.get().isShowOnlyOriginalFileNames);
        isUseCalibreOpf.setChecked(AppState.get().isUseCalibreOpf);
        isDisplayAnnotation.setChecked(AppState.get().isDisplayAnnotation);
        isHideReadBook.setChecked(AppState.get().isHideReadBook);
        isShowSeriesNumberInTitle.setChecked(AppState.get().isShowSeriesNumberInTitle);

        isScanOnLaunch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppState.get().isScanOnLaunch = isChecked;
        });

        final OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            AppState.get().isFirstSurname = isFirstSurname.isChecked();
            AppState.get().isSkipFolderWithNOMEDIA = isSkipFolderWithNOMEDIA.isChecked();
            AppState.get().isAuthorTitleFromMetaPDF = isAuthorTitleFromMetaPDF.isChecked();
            AppState.get().isShowOnlyOriginalFileNames = isShowOnlyOriginalFileNames.isChecked();
            AppState.get().isUseCalibreOpf = isUseCalibreOpf.isChecked();
            AppState.get().isDisplayAnnotation = isDisplayAnnotation.isChecked();
            AppState.get().isShowSeriesNumberInTitle = isShowSeriesNumberInTitle.isChecked();

            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(ask, timeout);
        };
        isFirstSurname.setOnCheckedChangeListener(listener);
        isAuthorTitleFromMetaPDF.setOnCheckedChangeListener(listener);
        isSkipFolderWithNOMEDIA.setOnCheckedChangeListener(listener);
        isShowOnlyOriginalFileNames.setOnCheckedChangeListener(listener);
        isUseCalibreOpf.setOnCheckedChangeListener(listener);
        isDisplayAnnotation.setOnCheckedChangeListener(listener);
        isHideReadBook.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                AppState.get().isHideReadBook = isHideReadBook.isChecked();
                TempHolder.listHash++;
                notifyFragment();
            }
        });
        isShowSeriesNumberInTitle.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                AppState.get().isShowSeriesNumberInTitle = isShowSeriesNumberInTitle.isChecked();
                TempHolder.listHash++;
                notifyFragment();
            }
        });
    }

    @Override public void onResume() {
        super.onResume();

        BrightnessHelper.updateOverlay(overlay);
        BrightnessHelper.showBlueLigthDialogAndBrightness(getActivity(), inflate, new Runnable() {

            @Override public void run() {
                BrightnessHelper.updateOverlay(overlay);
            }
        });

        rotationText();

        ch.setOnCheckedChangeListener(null);
        ch.setChecked(AppState.get().isReverseKeys);
        ch.setOnCheckedChangeListener(reverseListener);

        configSingleClick.update();

        textNigthColor.setTextColor(AppState.get().colorNigthText);
        textNigthColor.setBackgroundColor(AppState.get().colorNigthBg);

        textDayColor.setTextColor(AppState.get().colorDayText);
        textDayColor.setBackgroundColor(AppState.get().colorDayBg);

        isRememberDictionary.setChecked(AppState.get().isRememberDictionary);
        selectedDictionaly.setText(DialogTranslateFromTo.getSelectedDictionaryUnderline());

    }

    public void onColorChoose() {

    }

    public void initKeys() {
        nextKeys.setText(String.format("%s: %s", getActivity().getString(R.string.next_keys),
                AppState.keyToString(AppState.get().nextKeys)));
        prevKeys.setText(String.format("%s: %s", getActivity().getString(R.string.prev_keys),
                AppState.keyToString(AppState.get().prevKeys)));
    }

    @Override public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void onEmail() {
        final Intent emailIntent = new Intent(android.content.Intent.ACTION_SEND);

        String string = getResources().getString(R.string.my_email)
                                      .replace("<u>", "")
                                      .replace("</u>", "");
        final String[] aEmailList = {string};
        emailIntent.putExtra(android.content.Intent.EXTRA_EMAIL, aEmailList);
        emailIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                Apps.getApplicationName(getContext()) + " " + Apps.getVersionName(getContext()));
        emailIntent.setType("plain/text");
        emailIntent.putExtra(android.content.Intent.EXTRA_TEXT, "Hi Support, ");

        try {
            startActivity(Intent.createChooser(emailIntent, getActivity().getString(R.string.send_mail)));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(getContext(), R.string.there_are_no_email_applications_installed_, Toast.LENGTH_SHORT)
                 .show();
        }
    }

    public String getFullDeviceInfo() {
        return "(" + Build.BRAND + ", " + Build.MODEL + ", " + android.os.Build.VERSION.RELEASE + ", " + Dips.screenWidthDP() + "dp" + ")";
    }

    /** 常规设置 WebDAV 同步行的值：关闭 / 已配置服务器的 host */
    private void refreshWebdavSyncRow(TextView webdavSyncValue) {
        if (webdavSyncValue == null) {
            return;
        }
        if (!AppState.get().webdavSyncEnabled || TxtUtils.isEmpty(AppState.get().webdavSyncServer)) {
            webdavSyncValue.setText(R.string.webdav_sync_state_off);
            return;
        }
        webdavSyncValue.setText(AppState.get().webdavSyncServer);
    }

    /** 常规设置 AI 大模型行的值：未配置 / 模型名 */
    private void refreshAiConfigRow(TextView aiConfigValue) {
        if (aiConfigValue == null) {
            return;
        }
        if (TxtUtils.isEmpty(AppState.get().aiBaseUrl) || TxtUtils.isEmpty(AppState.get().aiModel)) {
            aiConfigValue.setText(R.string.ai_state_unconfigured);
            return;
        }
        aiConfigValue.setText(AppState.get().aiModel);
    }

    public void onTheme() {

        Apps.accessibilityText(getActivity(), R.string.apply);
        IMG.clearMemoryCache();
        AppProfile.save(getActivity());
        AppProfile.clear();
        getActivity().finish();
        MainTabs2.startActivity(getActivity(), TempHolder.get().currentTab);
    }

    public void onScan() {
        if (getActivity() == null) {
            return;
        }
        AppProfile.save(getActivity());
        closeLeftMenu();

        //BooksService.startForeground(getActivity(), BooksService.ACTION_SEARCH_ALL);
        SearchAllBooksWorker.run(getActivity());

        Intent intent = new Intent(UIFragment.INTENT_TINT_CHANGE)//
                                                                 .putExtra(MainTabs2.EXTRA_PAGE_NUMBER,
                                                                         UITab.getCurrentTabIndex(
                                                                                 UITab.SearchFragment));//

        LocalBroadcastManager.getInstance(getActivity())
                             .sendBroadcast(intent);

        ((AdsFragmentActivity) PrefFragment2.this.getActivity()).showInterstitialNoFinish();
    }

    private void closeLeftMenu() {
        try {
            final DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START, !Dips.isEInk());
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public void rotationText() {
        screenOrientation.setText(DocumentController.getRotationText());
        TxtUtils.underlineTextView(screenOrientation);
        DocumentController.doRotation(getActivity());
    }

    @Override public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        rotationText();

        if (AppState.get().isSystemThemeColor) {
            themeColor.setText(TxtUtils.underline(getString(R.string.system)));
        } else if (AppState.get().appTheme == AppState.THEME_INK) {
            themeColor.setText(TxtUtils.underline("Ink"));
        } else if (AppState.get().appTheme == AppState.THEME_LIGHT) {
            themeColor.setText(TxtUtils.underline(getString(R.string.light)));
        } else if (AppState.get().appTheme == AppState.THEME_DARK) {
            themeColor.setText(TxtUtils.underline(getString(R.string.black)));
        } else if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
            themeColor.setText(TxtUtils.underline(getString(R.string.dark_oled)));
        } else {
            themeColor.setText("unknown");

        }
    }

    private void saveChanges() {
        if (getActivity() != null) {
            AppProfile.save(getActivity());
        }
    }

    public String getFontName(float number) {
        String prefix = getActivity().getString(R.string.normal);
        float f1 = (number - 1f) * 10;
        float f2 = (1f - number) * 10 + 0.01f;
        if (number < 1) {
            prefix = getActivity().getString(R.string.small) + " (-" + (int) f2 + ")";
        } else if (number > 1) {
            prefix = getActivity().getString(R.string.large) + " (+" + (int) f1 + ")";
        }
        return prefix;
    }

}
