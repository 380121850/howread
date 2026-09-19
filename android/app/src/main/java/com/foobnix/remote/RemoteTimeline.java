package com.foobnix.remote;

import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Device-visible millisecond timeline for one remote open. One start() per
 * open attempt; every stage prints "[T+elapsed]" on the REMOTE tag, so a
 * single `logcat -s REMOTE` capture proves the full chain:
 *
 *   open start -> book info ok -> session ready -> first cache block
 *   -> MuPDF open done -> first page rendered -> (cumulative MB)
 *
 * Stage proofs for the "cache a small part, open fast" guarantee.
 */
public final class RemoteTimeline {

    private static volatile long t0;
    private static volatile String label = "";
    private static final Set<String> once = new HashSet<String>();

    private RemoteTimeline() {
    }

    public static void start(String what) {
        synchronized (RemoteTimeline.class) {
            once.clear();
        }
        t0 = SystemClock.elapsedRealtime();
        label = what;
        Log.i("REMOTE", "[T+0ms] open start: " + what);
    }

    public static void mark(String stage) {
        Log.i("REMOTE", "[T+" + (SystemClock.elapsedRealtime() - t0) + "ms] " + stage);
    }

    /** Logs the stage only the first time it is seen since start(). */
    public static void markOnce(String key, String stage) {
        synchronized (RemoteTimeline.class) {
            if (!once.add(key)) {
                return;
            }
        }
        Log.i("REMOTE", "[T+" + (SystemClock.elapsedRealtime() - t0) + "ms] " + stage);
    }
}
