package com.foobnix.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Objects;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Urls;

import java.io.File;

public class AppSP {

    private static AppSP instance = new AppSP();
    public String lastBookPath;

    public int lastBookPage = 0;
    public int lastBookPageCount = 0;
    public int tempBookPage = 0;
    public volatile int lastBookParagraph = 0;
    public String lastBookTitle;
    public int lastBookWidth = 0;
    public int lastBookHeight = 0;
    public int lastFontSize = 0;
    public String lastBookLang = "";
    public boolean isLocked = false;
    public boolean isFirstTimeVertical = true;
    public boolean isFirstTimeHorizontal = true;

    public int readingMode = AppState.READING_MODE_SCROLL;
    public long syncTime;
    public int syncTimeStatus;
    // Cumulative time (ms) spent in the reader, shown on the dashboard stats.
    public long readTimeMs = 0;
    // Reading stats: today's reading time ("yyyy-MM-dd" key) and cumulative
    // page flips, for the daily-time and reading-speed dashboard cards.
    public String readDayKey = "";
    public long readDayMs = 0;
    public long readPages = 0;
    // Reading time per calendar month, "{"yyyy-MM": ms}" — kept for the last
    // 13 months only. Filled by ReadingStats.onPause alongside readTimeMs.
    public String readMonthlyJson = "{}";
    // Reading time per calendar day, "{"yyyy-MM-dd": ms}" — kept for the last
    // 40 days (feeds the week/month views of the stats chart).
    public String readDailyJson = "{}";
    public String hypenLang = null;
    public boolean isCut = false;
    public boolean isDouble = false;
    public boolean isRTL = Urls.isRtl();
    public boolean isDoubleCoverAlone = false;
    public boolean isCrop = false;
    public boolean isCropSymetry = false;
    public boolean isSmartReflow = false;
    public String syncRootID;

    public String currentProfile = "";
    public String rootPath1 = getRootDir();

    transient SharedPreferences sp;

    public long interstitialLoadAdTime = 0;
    public long interstitialAdShowTime = 0;

    public long rewardedAdLoadedTime = 0;
    public long rewardShowTime = 0;

    // ---- IAP pro unlock (STUB — billing not integrated yet, see
    // mobi.librera.libgoogle.BillingManager). Device-local state on purpose:
    // stored in AppSP (SharedPreferences), never synced with the profile.
    // iapPurchaseTime/iapOrderId are stub purchase metadata shown on the
    // settings Pro card (channel is a stub constant, "本地 key").
    public boolean iapProUnlocked = false;
    public long iapPurchaseTime = 0;
    public String iapOrderId = "";


    public static AppSP get() {
        return instance;
    }

    public void init(Context c) {
        sp = c.getSharedPreferences("AppTemp", Context.MODE_PRIVATE);
        load(c);
        migrateLegacyRoot();
        getRootPath(c);
    }

    /**
     * The storage root was rebranded from /sdcard/Librera to /sdcard/HowRead.
     * Installs that still point at the old default follow automatically; a
     * user-chosen custom folder is never touched. The library DB file name is
     * derived from the root path hash, so the switch starts a fresh DB that
     * the next scan repopulates.
     */
    private void migrateLegacyRoot() {
        final String legacy = new File(Environment.getExternalStorageDirectory(), "Librera").toString();
        if (legacy.equals(rootPath1)) {
            LOG.d("migration", "rootPath1", rootPath1, "to", getRootDir());
            rootPath1 = getRootDir();
        }
        // the default profile follows the rebrand; the old profile folder
        // stays on disk and remains switchable from the profile picker
        if ("Librera".equals(currentProfile)) {
            LOG.d("migration", "currentProfile Librera to HowRead");
            currentProfile = "HowRead";
        }
    }

    public String getTempDir(Context c){
        return new File(c.getExternalFilesDir(null), "Demo").toString();
    }
    public String getRootDir(){
        return new File(Environment.getExternalStorageDirectory(), "HowRead").toString();
    }
    public File getTempDownloadBooks(Context c){
        return new File(c.getExternalFilesDir(null), "TempDownloads");
    }

    public String getRootPath(Context c){
        LOG.d("rootPath2","getRootPath-1",rootPath1, currentProfile);
        if(instance.currentProfile.isEmpty()) {
            if (!Android6.canWrite(c)) {
                instance.rootPath1 = getTempDir(c);
                instance.currentProfile = "Demo";
            } else {
                instance.rootPath1 =getRootDir();
                instance.currentProfile = AppsConfig.IS_LOG ? "BETA" : "HowRead";
            }

        }
        LOG.d("rootPath2","getRootPath-2",rootPath1, currentProfile);
        return instance.rootPath1;
    }

    public void load(Context c) {
        Objects.loadFromSp(instance, sp);
    }

    public void save() {
        Objects.saveToSP(instance, sp);
        LOG.d("rootPath2","save-1",rootPath1, currentProfile);
        LOG.d("rootPath2","save-2",get().rootPath1, get().currentProfile);

    }

}
