package com.foobnix.sys;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.LOG;

/**
 * Holds the "please wait" loading dialog on screen until the first screen
 * is fully decoded, so the user never sees empty "Page N" placeholders
 * flash before the content appears. The dialog drops when no new page
 * bitmap has arrived for a short quiet period (the whole visible screen is
 * then painted), with a hard cap as a safety net.
 */
public class FirstPaintGate {

    private static final long QUIET_MS = 500;
    private static final long NO_DECODE_MS = 2000;
    private static final long HARD_CAP_MS = 8000;
    private static final long TICK_MS = 200;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static volatile AlertDialog dialog;
    private static volatile boolean armed;
    private static volatile long armAt;
    private static volatile long firstDecodeAt;
    private static volatile long lastDecodeAt;
    /** Remote books pass a short cap: the reader shell shows at once (same
     * feel as the horizontal deferred path) and the slow-paint banner takes
     * over the progress feedback while the first screen streams in. */
    public static final long REMOTE_HARD_CAP_MS = 500;
    private static volatile long hardCapMs = HARD_CAP_MS;
    /** True once any page bitmap has been set since {@link #arm}. */
    private static volatile boolean firstBitmapSeen;
    private static volatile ReleaseListener releaseListener;

    /** Notified on the UI thread when the gate releases. */
    public interface ReleaseListener {
        void onReleased(boolean decoded);
    }

    public static void setOnRelease(ReleaseListener l) {
        releaseListener = l;
    }

    /** True when at least one page bitmap has arrived since arm(). */
    public static boolean hasFirstBitmap() {
        return firstBitmapSeen;
    }

    private static final Runnable TICK = new Runnable() {

        @Override
        public void run() {
            tick();
        }
    };

    private static final Runnable HARD_CAP = new Runnable() {

        @Override
        public void run() {
            android.util.Log.i("BENCH", "FirstPaintGate hard cap");
            release();
        }
    };

    /** Keep the loading dialog visible until the first screen is decoded. */
    public static void arm(final AlertDialog loadingDialog) {
        arm(loadingDialog, HARD_CAP_MS);
    }

    public static void arm(final AlertDialog loadingDialog, final long capMs) {
        UI.removeCallbacks(TICK);
        UI.removeCallbacks(HARD_CAP);
        dialog = loadingDialog;
        armed = loadingDialog != null;
        hardCapMs = capMs;
        firstBitmapSeen = false;
        if (armed) {
            armAt = android.os.SystemClock.elapsedRealtime();
            firstDecodeAt = 0;
            lastDecodeAt = 0;
            android.util.Log.i("BENCH", "FirstPaintGate arm cap=" + capMs + "ms");
            UI.postDelayed(TICK, TICK_MS);
            UI.postDelayed(HARD_CAP, hardCapMs);
        }
    }

    /** Called on the UI thread when a page bitmap has been set. */
    public static void notifyDecoded() {
        firstBitmapSeen = true;
        com.foobnix.remote.RemoteTimeline.markOnce("firstRender", "first page bitmap rendered");
        if (armed) {
            final long now = android.os.SystemClock.elapsedRealtime();
            if (firstDecodeAt == 0) {
                firstDecodeAt = now;
            }
            lastDecodeAt = now;
        }
    }

    /** Disarm and dismiss the held dialog, if still showing. */
    public static void cancel() {
        release();
    }

    private static void tick() {
        if (!armed) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (firstDecodeAt > 0 && now - lastDecodeAt >= QUIET_MS) {
            android.util.Log.i("BENCH", "FirstPaintGate release (screen decoded "
                                                + (now - armAt) + "ms after open)");
            release();
            return;
        }
        if (firstDecodeAt == 0 && now - armAt >= NO_DECODE_MS) {
            // nothing is decoding (everything already rendered): show it
            android.util.Log.i("BENCH", "FirstPaintGate release (no decodes pending)");
            release();
            return;
        }
        UI.postDelayed(TICK, TICK_MS);
    }

    private static void release() {
        armed = false;
        UI.removeCallbacks(TICK);
        UI.removeCallbacks(HARD_CAP);
        final AlertDialog d = dialog;
        dialog = null;
        if (d != null && d.isShowing()) {
            try {
                d.dismiss();
            } catch (final Exception e) {
                LOG.e(e);
            }
        }
        final ReleaseListener l = releaseListener;
        releaseListener = null;
        if (l != null) {
            try {
                l.onReleased(firstDecodeAt > 0);
            } catch (final Exception e) {
                LOG.e(e);
            }
        }
    }
}
