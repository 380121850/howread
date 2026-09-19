package com.foobnix.remote;

/**
 * Coordinates remote cover probing vs. user-initiated opens: cover probes
 * pause while the user is opening a book (a probe chain used to hold the
 * global native lock for tens of seconds and starve the real open), and
 * probes never trigger the wide read-ahead window.
 */
public final class OpenGate {
    private OpenGate() {
    }

    public static final java.util.concurrent.atomic.AtomicBoolean userOpenPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Always mutate through RemoteSessionFactory.userOpenStart/userOpenEnd:
     * they also pause the background work of every OTHER live session so the
     * opening book's walk/decode owns the link. */

    private static final ThreadLocal<Boolean> probeActive = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void enterProbe() {
        probeActive.set(Boolean.TRUE);
    }

    public static void exitProbe() {
        probeActive.set(Boolean.FALSE);
    }

    public static boolean isProbe() {
        return probeActive.get().booleanValue();
    }

    /** Cover probing pauses while the user is opening a book (max 12s). */
    public static void waitIfUserOpen() {
        long t0 = android.os.SystemClock.elapsedRealtime();
        while (userOpenPending.get()
                && android.os.SystemClock.elapsedRealtime() - t0 < 12000) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
