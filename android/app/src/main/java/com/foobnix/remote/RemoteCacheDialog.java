package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.Dips;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;

/**
 * Online-reading settings: the "online first" click switch, the cache size
 * cap, the WiFi-only rule (single gate for prefetch AND whole-book fill on
 * metered networks) and the whole-book threshold. Cache usage display and
 * the clear-all action moved to the 常规设置 缓存管理 entry (2026-10-05).
 */
public class RemoteCacheDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh) {
        final AppState st = AppState.get();
        final boolean pro = AppsConfig.isProFeaturesEnabled();
        final EditText threshold;
        final EditText retryCount;
        final EditText retryInterval;
        final EditText windowBefore;
        final EditText windowAfter;

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Dips.dpToPx(16);
        body.setPadding(pad, pad, pad, pad);

        if (pro) {
            Switch onlineFirst = new Switch(a);
            onlineFirst.setText(R.string.remote_online_first);
            onlineFirst.setChecked(st.remoteOnlineFirst);
            onlineFirst.setOnCheckedChangeListener((b, isChecked) -> {
                st.remoteOnlineFirst = isChecked;
                AppProfile.save(a);
            });
            body.addView(onlineFirst, row());
            onlineFirst.setPadding(0, Dips.dpToPx(8), 0, Dips.dpToPx(8));

            Switch wifiOnly = new Switch(a);
            wifiOnly.setText(R.string.remote_wifi_only);
            wifiOnly.setChecked(st.remotePrefetchWifiOnly);
            wifiOnly.setOnCheckedChangeListener((b, isChecked) -> {
                st.remotePrefetchWifiOnly = isChecked;
                AppProfile.save(a);
            });
            body.addView(wifiOnly, row());
            wifiOnly.setPadding(0, Dips.dpToPx(8), 0, Dips.dpToPx(8));

            body.addView(label(a, R.string.remote_whole_book_hint));
            threshold = numberField(a, st.remoteWholeBookThresholdMB);
            body.addView(threshold, row());

            // windowed caching of big books (percent around the position)
            // and the retry policy: two settings per row keeps the dialog
            // short (each half is a compact label-over-field column)
            windowBefore = numberField(a, st.remoteWindowBeforePct);
            windowAfter = numberField(a, st.remoteWindowAfterPct);
            body.addView(paired(a, label(a, R.string.remote_window_before), windowBefore,
                    label(a, R.string.remote_window_after), windowAfter));

            retryCount = numberField(a, st.remoteRetryCount);
            retryInterval = numberField(a, st.remoteRetryIntervalMs);
            body.addView(paired(a, label(a, R.string.remote_retry_count), retryCount,
                    label(a, R.string.remote_retry_interval), retryInterval));
        } else {
            threshold = null;
            retryCount = null;
            retryInterval = null;
            windowBefore = null;
            windowAfter = null;
        }

        // constraints at a glance: network (metered disables background
        // whole-book caching); usage and clear moved to 缓存管理
        body.addView(label(a, com.foobnix.remote.RemoteBookSession.isNetworkMetered()
                ? R.string.remote_cache_net_metered : R.string.remote_cache_net_wifi));

        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_settings_title)
                .setView(scroll(body))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (pro) {
                        try {
                            st.remoteWholeBookThresholdMB = Math.max(0,
                                    Integer.parseInt(threshold.getText().toString().trim()));
                        } catch (Exception e) {
                            st.remoteWholeBookThresholdMB = 20;
                        }
                        try {
                            st.remoteWindowBeforePct = Math.max(1, Math.min(90,
                                    Integer.parseInt(windowBefore.getText().toString().trim())));
                        } catch (Exception e) {
                            st.remoteWindowBeforePct = 20;
                        }
                        try {
                            st.remoteWindowAfterPct = Math.max(1, Math.min(90,
                                    Integer.parseInt(windowAfter.getText().toString().trim())));
                        } catch (Exception e) {
                            st.remoteWindowAfterPct = 30;
                        }
                        try {
                            st.remoteRetryCount = Math.max(0, Math.min(10,
                                    Integer.parseInt(retryCount.getText().toString().trim())));
                        } catch (Exception e) {
                            st.remoteRetryCount = 3;
                        }
                        try {
                            st.remoteRetryIntervalMs = Math.max(0, Math.min(30000,
                                    Integer.parseInt(retryInterval.getText().toString().trim())));
                        } catch (Exception e) {
                            st.remoteRetryIntervalMs = 100;
                        }
                        AppProfile.save(a);
                    }
                    if (onRefresh != null) {
                        onRefresh.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Two label-over-field columns side by side (50/50). */
    private static View paired(Activity a, View leftLabel, View leftField,
            View rightLabel, View rightField) {
        android.widget.LinearLayout rowL = new android.widget.LinearLayout(a);
        rowL.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout left = new android.widget.LinearLayout(a);
        android.widget.LinearLayout right = new android.widget.LinearLayout(a);
        android.widget.LinearLayout.LayoutParams half = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int gap = Dips.dpToPx(10);
        half.setMargins(0, 0, gap / 2, 0);
        android.widget.LinearLayout.LayoutParams halfR = new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfR.setMargins(gap / 2, 0, 0, 0);
        left.setOrientation(android.widget.LinearLayout.VERTICAL);
        right.setOrientation(android.widget.LinearLayout.VERTICAL);
        left.addView(leftLabel);
        left.addView(leftField);
        right.addView(rightLabel);
        right.addView(rightField);
        rowL.addView(left, half);
        rowL.addView(right, halfR);
        return rowL;
    }

    private static ViewGroup.LayoutParams row() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static View scroll(View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(v.getContext());
        sv.addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static TextView label(Activity a, int res) {
        TextView t = new TextView(a);
        t.setText(res);
        t.setTextSize(15);
        t.setPadding(0, Dips.dpToPx(10), 0, Dips.dpToPx(2));
        return t;
    }

    private static EditText numberField(Activity a, int value) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelection(e.getText().length());
        return e;
    }
}
