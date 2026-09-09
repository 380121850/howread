package com.foobnix.webdav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

import java.util.Date;

/**
 * WebDAV reading-data sync settings, Anx Reader style: its own server
 * URL / login / password / trust-cert (fully independent from the browsing
 * WebDAV servers configured on the My files page), a test-connection check,
 * a progress conflict policy, a manual "sync now" trigger and the last sync
 * time + statistics.
 */
public class WebDavSyncDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh) {
        // PRO feature gate: locked/fdroid builds can't open the sync config
        // (defense in depth — the settings row is already gated)
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_webdav_sync, null, false);
        final CheckBox enable = (CheckBox) view.findViewById(R.id.webdavSyncEnabled);
        final EditText url = (EditText) view.findViewById(R.id.webdavSyncUrl);
        final EditText login = (EditText) view.findViewById(R.id.webdavSyncLogin);
        final EditText password = (EditText) view.findViewById(R.id.webdavSyncPassword);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final CheckBox trustCerts = (CheckBox) view.findViewById(R.id.webdavSyncTrustCerts);
        final TextView remoteValue = (TextView) view.findViewById(R.id.webdavSyncRemoteValue);
        final TextView policyValue = (TextView) view.findViewById(R.id.webdavSyncPolicyValue);
        final TextView intervalValue = (TextView) view.findViewById(R.id.webdavSyncIntervalValue);
        final TextView test = (TextView) view.findViewById(R.id.webdavSyncTest);
        final TextView syncNow = (TextView) view.findViewById(R.id.webdavSyncNow);
        final TextView logLink = (TextView) view.findViewById(R.id.webdavSyncLog);
        final TextView status = (TextView) view.findViewById(R.id.webdavSyncStatus);
        final MyProgressBar progress = (MyProgressBar) view.findViewById(R.id.webdavSyncProgress);

        TxtUtils.underlineTextView(test);
        TxtUtils.underlineTextView(syncNow);
        TxtUtils.underlineTextView(logLink);
        TxtUtils.underlineTextView(policyValue);
        TxtUtils.underlineTextView(remoteValue);
        TxtUtils.underlineTextView(intervalValue);

        // load the current config into the fields; when the sync server is
        // not configured yet, the fields are pre-filled (as defaults only)
        // from the first WebDAV server set up on the My files page
        enable.setChecked(AppState.get().webdavSyncEnabled);
        String savedUrl = AppState.get().webdavSyncServer;
        String inheritUrl = savedUrl;
        if (TxtUtils.isEmpty(inheritUrl)) {
            String[] inherited = WebDavSyncer.resolveConfig(a);
            if (inherited != null) {
                inheritUrl = inherited[0];
                url.setText(inherited[0]);
                login.setText(inherited[1]);
                password.setText(inherited[2]);
            }
        } else {
            url.setText(savedUrl);
            String[] creds = WebDavCredentials.load(a, savedUrl);
            if (creds != null) {
                login.setText(creds[0]);
                password.setText(creds[1]);
            }
        }
        if (TxtUtils.isNotEmpty(inheritUrl)) {
            trustCerts.setChecked(WebDavCredentials.isTrustAll(a, inheritUrl));
        }
        refreshRemoteLabel(remoteValue);
        refreshPolicyLabel(policyValue);
        refreshIntervalLabel(intervalValue);
        refreshStatusLabel(a, status);

        enable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppState.get().webdavSyncEnabled = isChecked;
                AppProfile.save(a);
                if (onRefresh != null) {
                    onRefresh.run();
                }
            }
        });

        policyValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                PopupMenu popup = new PopupMenu(a, v);
                popup.getMenu().add(R.string.webdav_sync_policy_newer);
                popup.getMenu().add(R.string.webdav_sync_policy_farther);
                popup.getMenu().add(R.string.webdav_sync_policy_local);
                popup.getMenu().add(R.string.webdav_sync_policy_server);
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                        if (item.getTitle().equals(a.getString(R.string.webdav_sync_policy_farther))) {
                            AppState.get().webdavSyncPolicy = "farther";
                        } else if (item.getTitle().equals(a.getString(R.string.webdav_sync_policy_local))) {
                            AppState.get().webdavSyncPolicy = "local";
                        } else if (item.getTitle().equals(a.getString(R.string.webdav_sync_policy_server))) {
                            AppState.get().webdavSyncPolicy = "server";
                        } else {
                            AppState.get().webdavSyncPolicy = "newer";
                        }
                        AppProfile.save(a);
                        refreshPolicyLabel(policyValue);
                        return true;
                    }
                });
                popup.show();
            }
        });

        intervalValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu popup = new PopupMenu(a, v);
                popup.getMenu().add(0, 0, 0, R.string.webdav_sync_interval_off);
                popup.getMenu().add(0, 5, 1, R.string.webdav_sync_interval_5);
                popup.getMenu().add(0, 15, 2, R.string.webdav_sync_interval_15);
                popup.getMenu().add(0, 30, 2, R.string.webdav_sync_interval_30);
                popup.getMenu().add(0, 60, 3, R.string.webdav_sync_interval_60);
                popup.getMenu().add(0, 180, 4, R.string.webdav_sync_interval_180);
                popup.getMenu().add(0, 360, 5, R.string.webdav_sync_interval_360);
                popup.getMenu().add(0, 1440, 6, R.string.webdav_sync_interval_1440);
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                        AppState.get().webdavSyncIntervalMin = item.getItemId();
                        AppProfile.save(a);
                        // re-arm with the new interval; <= 0 stops the cycle
                        WebDavSyncer.scheduleNextPeriodic(a);
                        refreshIntervalLabel(intervalValue);
                        return true;
                    }
                });
                popup.show();
            }
        });

        /** Persist what is currently typed so test/sync use exactly these values. */
        final Runnable saveTyped = () -> {
            String u = url.getText().toString().trim();
            AppState.get().webdavSyncServer = u;
            WebDavCredentials.save(a, u, login.getText().toString().trim(), password.getText().toString());
            WebDavCredentials.saveTrust(a, u, trustCerts.isChecked());
            AppProfile.save(a);
            if (onRefresh != null) {
                onRefresh.run();
            }
        };

        remoteValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                if (TxtUtils.isEmpty(u)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                WebDavPathPickerDialog.showDialog(a, u, login.getText().toString().trim(),
                        password.getText().toString(), trustCerts.isChecked(),
                        AppState.get().webdavSyncRemoteDir,
                        rel -> {
                            AppState.get().webdavSyncRemoteDir = rel;
                            AppProfile.save(a);
                            refreshRemoteLabel(remoteValue);
                            return false;
                        });
            }
        });

        test.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                if (TxtUtils.isEmpty(u)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return WebDavClient.list(u, login.getText().toString().trim(),
                                password.getText().toString(), trustCerts.isChecked());
                    }

                    @Override protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        if (result != null) {
                            Toast.makeText(a, R.string.webdav_sync_test_ok, Toast.LENGTH_SHORT).show();
                        } else {
                            String kind = WebDavClient.lastError;
                            int msg;
                            if ("auth".equals(kind)) {
                                msg = R.string.webdav_auth_failed;
                            } else if ("ssl".equals(kind)) {
                                msg = R.string.webdav_err_ssl;
                            } else if ("network".equals(kind)) {
                                msg = R.string.webdav_err_network;
                            } else {
                                msg = R.string.webdav_connect_failed;
                            }
                            Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                        }
                    }
                }.execute();
            }
        });

        syncNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!AppState.get().webdavSyncEnabled) {
                    Toast.makeText(a, R.string.webdav_sync_enable, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TxtUtils.isEmpty(url.getText().toString().trim())) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                saveTyped.run();
                progress.setVisibility(View.VISIBLE);
                syncNow.setEnabled(false);
                status.setText(R.string.webdav_syncing);
                WebDavSyncer.syncAsync(a, new WebDavSyncer.Listener() {
                    @Override public void onStep(String step) {
                        status.setText(R.string.webdav_syncing);
                    }

                    @Override public void onFinish(WebDavSyncer.SyncResult r) {
                        progress.setVisibility(View.GONE);
                        syncNow.setEnabled(true);
                        // the sync applied imported config to the singletons:
                        // refresh this dialog's rows and the settings row
                        // behind it immediately instead of on the next visit
                        if (onRefresh != null) {
                            onRefresh.run();
                        }
                        if (r.ok) {
                            status.setText(a.getString(R.string.webdav_sync_ok,
                                    r.progressUp, r.progressDown, r.bookmarksUp, r.bookmarksDown));
                        } else if ("no_server".equals(r.error)) {
                            status.setText(R.string.webdav_sync_no_server);
                        } else if ("auth".equals(r.error)) {
                            status.setText(R.string.webdav_auth_failed);
                        } else if ("ssl".equals(r.error)) {
                            status.setText(R.string.webdav_err_ssl);
                        } else if ("network".equals(r.error)) {
                            status.setText(R.string.webdav_err_network);
                        } else {
                            status.setText(R.string.webdav_connect_failed);
                        }
                    }
                });
            }
        });

        // sync change log: which config fields the recent syncs merged down
        // from the server or published to it (old → new per item)
        logLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String text = SyncChangeLog.recentText(10);
                final TextView content = new TextView(a);
                content.setText(TxtUtils.isEmpty(text)
                        ? a.getString(R.string.webdav_sync_log_empty) : text);
                content.setTextSize(12);
                content.setPadding(24, 16, 24, 16);
                content.setTextIsSelectable(true);
                final android.widget.ScrollView scroll = new android.widget.ScrollView(a);
                scroll.addView(content);
                new AlertDialog.Builder(a)
                        .setTitle(R.string.webdav_sync_log)
                        .setView(scroll)
                        .setPositiveButton(R.string.close, null)
                        .show();
            }
        });

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.webdav_sync_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.webdav_sync_save, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                saveTyped.run();
                Keyboards.close(a);
            }
        });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                Keyboards.close(a);
            }
        });
        builder.show();
    }

    private static void refreshRemoteLabel(TextView remoteValue) {
        remoteValue.setText("/" + WebDavSyncer.remoteDir());
    }

    private static void refreshPolicyLabel(TextView policyValue) {
        if ("farther".equals(AppState.get().webdavSyncPolicy)) {
            policyValue.setText(R.string.webdav_sync_policy_farther);
        } else if ("local".equals(AppState.get().webdavSyncPolicy)) {
            policyValue.setText(R.string.webdav_sync_policy_local);
        } else if ("server".equals(AppState.get().webdavSyncPolicy)) {
            policyValue.setText(R.string.webdav_sync_policy_server);
        } else {
            policyValue.setText(R.string.webdav_sync_policy_newer);
        }
    }

    /** The periodic-sync interval as a human label ("Off", "Every hour"…). */
    private static void refreshIntervalLabel(TextView intervalValue) {
        switch (AppState.get().webdavSyncIntervalMin) {
        case 5:
            intervalValue.setText(R.string.webdav_sync_interval_5);
            break;
        case 15:
            intervalValue.setText(R.string.webdav_sync_interval_15);
            break;
        case 30:
            intervalValue.setText(R.string.webdav_sync_interval_30);
            break;
        case 60:
            intervalValue.setText(R.string.webdav_sync_interval_60);
            break;
        case 180:
            intervalValue.setText(R.string.webdav_sync_interval_180);
            break;
        case 360:
            intervalValue.setText(R.string.webdav_sync_interval_360);
            break;
        case 1440:
            intervalValue.setText(R.string.webdav_sync_interval_1440);
            break;
        default:
            intervalValue.setText(R.string.webdav_sync_interval_off);
            break;
        }
    }

    private static void refreshStatusLabel(Activity a, TextView status) {
        long t = AppState.get().webdavLastSyncTime;
        if (t <= 0) {
            status.setText(R.string.webdav_sync_last_never);
        } else {
            String when = DateFormat.getDateFormat(a).format(new Date(t)) + " "
                    + DateFormat.getTimeFormat(a).format(new Date(t));
            String info = AppState.get().webdavLastSyncInfo;
            if (TxtUtils.isNotEmpty(info)) {
                status.setText(a.getString(R.string.webdav_sync_last, when) + " · " + info);
            } else {
                status.setText(a.getString(R.string.webdav_sync_last, when));
            }
        }
    }
}
