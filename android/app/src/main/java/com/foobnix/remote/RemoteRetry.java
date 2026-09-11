package com.foobnix.remote;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;

/**
 * Retry with exponential backoff for transient network failures (tech-spec
 * §13). Only IO-level errors are retried; auth / not-found are final.
 */
public class RemoteRetry {

    public interface IoCall<T> {
        T run() throws Exception;
    }

    private RemoteRetry() {
    }

    /** Runs {@code call} with {@code remoteRetryCount} retries (base×2ⁿ backoff). */
    public static <T> T execute(IoCall<T> call) throws Exception {
        int retries = Math.max(0, AppState.get().remoteRetryCount);
        long base = Math.max(0, AppState.get().remoteRetryIntervalMs);
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return call.run();
            } catch (Exception e) {
                if (!isRetryable(e) || attempt >= retries) {
                    throw e;
                }
                last = e;
                long sleep = base * (1L << attempt);
                android.util.Log.i("REMOTE", "retry attempt " + (attempt + 1) + "/" + retries
                        + " after " + sleep + "ms: " + e.getMessage());
                Thread.sleep(sleep);
            }
        }
        throw last;
    }

    static boolean isRetryable(Throwable t) {
        while (t != null) {
            if (t instanceof java.io.IOException && !isFinalMessage(t.getMessage())) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** Failures that will never succeed on retry (auth / missing file). */
    private static boolean isFinalMessage(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase();
        return m.contains("auth failed") || m.contains("404") || m.contains("not found");
    }
}
