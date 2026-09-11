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
 * cap, the WiFi-only prefetch rule and the small-book whole-cache threshold,
 * plus the current cache usage with a clear-all action. The switches are a
 * Pro feature: locked builds (fdroid / pro without IAP) only see the usage.
 */
public class RemoteCacheDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh) {
        final AppState st = AppState.get();
        final boolean pro = AppsConfig.isProFeaturesEnabled();
        final EditText cacheSize;
        final EditText threshold;

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

            body.addView(label(a, R.string.remote_cache_size));
            cacheSize = numberField(a, st.remoteCacheMaxMB);
            body.addView(cacheSize, row());

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
        } else {
            cacheSize = null;
            threshold = null;
        }

        body.addView(label(a, R.string.remote_cache_usage));
        TextView usage = new TextView(a);
        usage.setText(com.foobnix.pdf.info.ExtUtils.readableFileSize(BlockCacheStore.totalBytes()));
        body.addView(usage, row());

        new AlertDialog.Builder(a)
                .setTitle(R.string.remote_settings_title)
                .setView(scroll(body))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (pro) {
                        try {
                            st.remoteCacheMaxMB = Math.max(50,
                                    Integer.parseInt(cacheSize.getText().toString().trim()));
                        } catch (Exception e) {
                            st.remoteCacheMaxMB = 500;
                        }
                        try {
                            st.remoteWholeBookThresholdMB = Math.max(0,
                                    Integer.parseInt(threshold.getText().toString().trim()));
                        } catch (Exception e) {
                            st.remoteWholeBookThresholdMB = 20;
                        }
                        AppProfile.save(a);
                    }
                    if (onRefresh != null) {
                        onRefresh.run();
                    }
                })
                .setNeutralButton(R.string.remote_clear_cache, (d, w) -> {
                    BlockCacheStore.clearAll();
                    Toast.makeText(a, R.string.remote_cache_cleared, Toast.LENGTH_SHORT).show();
                    if (onRefresh != null) {
                        onRefresh.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
