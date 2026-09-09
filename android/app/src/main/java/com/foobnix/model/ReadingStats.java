package com.foobnix.model;

import android.os.SystemClock;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard reading-stats accounting, shared by both reader activities.
 *
 * onResume() anchors a monotonic clock; onFlip() counts a page turn while a
 * session is active; onPause() folds the elapsed time into the cumulative
 * counter, the today counter (reset when the calendar day changed) and the
 * cumulative page-flip counter. Persistence happens through the caller's
 * existing AppProfile.save() call — onPause() itself only mutates AppSP.
 *
 * A session spanning midnight books its whole duration into the new day; a
 * process kill loses only the un-flushed tail (same trade-off as readTimeMs).
 *
 * Manual state changes (BookStateStore.markRead/markUnread/markReading) never
 * pass through this class, so these counters only accumulate real reader
 * sessions — marking a book finished by hand adds no time or pages.
 */
public final class ReadingStats {

    private static long resumeAt = 0;
    private static long pendingFlips = 0;

    private ReadingStats() {
    }

    public static void onResume() {
        resumeAt = SystemClock.elapsedRealtime();
    }

    /** Count one page turn. No-op outside an active reading session, which
     *  also excludes the initial position restore that happens before onResume.
     *  PRO feature: when locked (fdroid / pro without the IAP unlock) nothing
     *  is recorded — existing stats are kept but never grow. */
    public static void onFlip() {
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            return;
        }
        if (resumeAt > 0) {
            pendingFlips++;
        }
    }

    public static void onPause() {
        if (resumeAt <= 0) {
            return;
        }
        long delta = SystemClock.elapsedRealtime() - resumeAt;
        resumeAt = 0;
        pendingFlips = 0;

        // PRO feature gate: locked builds keep the recorded history but stop
        // accumulating new time/pages
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            return;
        }

        AppSP sp = AppSP.get();
        sp.readTimeMs += delta;

        String key = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!key.equals(sp.readDayKey)) {
            sp.readDayKey = key;
            sp.readDayMs = 0;
        }
        sp.readDayMs += delta;

        sp.readPages += pendingFlips;
        pendingFlips = 0;

        addBucket(sp, "readMonthlyJson", "yyyy-MM", delta, 13);
        addBucket(sp, "readDailyJson", "yyyy-MM-dd", delta, 40);
    }

    /**
     * Fold the session delta into a "key pattern -> ms" JSON bucket on AppSP,
     * keeping only the most recent {@code keep} keys so the string stays
     * bounded. A malformed stored JSON resets the bucket instead of throwing.
     */
    private static void addBucket(AppSP sp, String field, String pattern, long delta, int keep) {
        try {
            java.lang.reflect.Field f = AppSP.class.getField(field);
            String raw = (String) f.get(sp);
            JSONObject buckets = new JSONObject(raw == null ? "{}" : raw);
            String key = new SimpleDateFormat(pattern, Locale.US).format(new Date());
            buckets.put(key, buckets.optLong(key) + delta);
            if (buckets.length() > keep) {
                List<String> keys = new ArrayList<>();
                Iterator<String> it = buckets.keys();
                while (it.hasNext()) {
                    keys.add(it.next());
                }
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - keep; i++) {
                    buckets.remove(keys.get(i));
                }
            }
            f.set(sp, buckets.toString());
        } catch (Exception e) {
            // never let accounting break the reading session teardown
            try {
                java.lang.reflect.Field f = AppSP.class.getField(field);
                f.set(sp, "{}");
            } catch (Exception ignored) {
            }
        }
    }
}
