package com.foobnix.sys;

import com.foobnix.android.utils.LOG;

import com.foobnix.pdf.info.wrapper.UITab;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class TempHolder {
    private static final class DiagLock extends ReentrantLock {
        public Thread owner() {
            return getOwner();
        }
    }

    private static final DiagLock diag = new DiagLock();
    public static final ReentrantLock lock = diag;

    /** Diagnostic wrapper: logs when a thread waited >200ms for the native
     *  lock and names the thread that held it. Same semantics as lock(). */
    public static void lockDiag(final String where) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        Thread holder = null;
        final boolean contended = !lock.tryLock();
        if (contended) {
            holder = diag.owner();
            lock.lock();
        }
        final long waited = android.os.SystemClock.elapsedRealtime() - t0;
        if (waited > 200) {
            LOG.bench("lock-wait " + where + " waited=" + waited
                    + "ms holder=" + (holder != null ? holder.getName() : "?"));
        }
    }

    public static final TempHolder inst = new TempHolder();
    public static volatile int listHash = 0;

    public static volatile int listHashTemp;
    public static String loadingTime;

    public static boolean isListHashChange() {
        if (listHash == listHashTemp) {
            return false;
        }
        listHashTemp = listHash;
        return true;
    }

    public static volatile boolean isSeaching = false;
    public static volatile boolean isConverting = false;
    public static volatile boolean isRecordTTS = false;

    /** True while a reader activity is in the foreground; background warm-up tasks yield to it. */
    public static volatile boolean readerActive = false;

    public static int isRecordFrom = 1;
    public static int isRecordTo = 1;

    public static volatile AtomicBoolean isActiveSpeedRead = new AtomicBoolean(false);
    public String login = "", password = "";
    public int linkPage = -1;
    public int currentTab = UITab.SearchFragment.index;
    public long timerFinishTime = 0;

    public int pageDelta = 0;

    //public static volatile boolean loadingCancelled = false;

    public final AtomicBoolean loadingCancelled = new AtomicBoolean(false);
    public boolean forseAppLang = false;

    public volatile long lastRecycledDocument = 0;

    public int textFromPage = 0;
    public String copyFromPath = null;

    public int documentTitleBarHeight;



    public static TempHolder get() {
        return inst;
    }


}
