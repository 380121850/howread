package com.foobnix.webdav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

/**
 * Add / edit a WebDAV server (URL, name, login, password). The URL is verified
 * with a PROPFIND on save; when the check fails the user can still force-add.
 * Credentials are stored encrypted via {@link WebDavCredentials}.
 */
public class AddWebDavDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh, final WebDavServer edit) {
        // PRO feature gate (covers every caller): locked/fdroid builds can't
        // add or edit WebDAV servers; saved servers stay browsable
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        final View dialog = LayoutInflater.from(a).inflate(R.layout.dialog_add_webdav, null, false);

        final EditText url = (EditText) dialog.findViewById(R.id.url);
        final EditText name = (EditText) dialog.findViewById(R.id.name);
        final EditText login = (EditText) dialog.findViewById(R.id.login);
        final EditText password = (EditText) dialog.findViewById(R.id.password);
        final EditText startDir = (EditText) dialog.findViewById(R.id.startDir);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final android.widget.CheckBox trustCerts = (android.widget.CheckBox) dialog.findViewById(R.id.trustCerts);
        final TextView testBtn = (TextView) dialog.findViewById(R.id.remoteTestBtn);
        final TextView browseBtn = (TextView) dialog.findViewById(R.id.remoteBrowseBtn);
        final TextView testResult = (TextView) dialog.findViewById(R.id.remoteTestResult);
        final MyProgressBar progress = (MyProgressBar) dialog.findViewById(R.id.MyProgressBarAddWebDav);

        final String editAppState = edit == null ? null : edit.appState;
        if (edit != null) {
            url.setText(edit.url);
            name.setText(edit.title);
            startDir.setText(edit.startDir);
            String[] creds = WebDavCredentials.load(a, edit.url);
            if (creds != null) {
                login.setText(creds[0]);
                password.setText(creds[1]);
            }
            if (trustCerts != null) {
                trustCerts.setChecked(WebDavCredentials.isTrustAll(a, edit.url));
            }
        } else {
            url.setText("http://");
            url.setSelection(url.getText().length());
        }

        builder.setView(dialog);
        builder.setTitle(R.string.add_webdav_server);
        builder.setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
            }
        });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                Keyboards.close(a);
            }
        });

        final AlertDialog infoDialog = builder.create();
        infoDialog.show();

        // 测试连接: verify the URL + credentials before the server is saved
        final AsyncTask[] testTask = new AsyncTask[1];
        testBtn.setOnClickListener(v -> {
            if (testTask[0] != null && AsyncTasks.isRunning(testTask[0])) {
                AsyncTasks.toastPleaseWait(a);
                return;
            }
            final String feedUrl = WebDavStore.trimSlash(url.getText().toString().trim());
            final String loginText = login.getText().toString().trim();
            final String passwordText = password.getText().toString().trim();
            final boolean trustAll = trustCerts != null && trustCerts.isChecked();
            if (TxtUtils.isEmpty(feedUrl) || "http://".equals(feedUrl)) {
                Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                return;
            }
            progress.setVisibility(View.VISIBLE);
            testResult.setVisibility(View.GONE);
            testTask[0] = new AsyncTask() {
                @Override
                protected Object doInBackground(Object... params) {
                    return WebDavClient.list(feedUrl, loginText, passwordText, trustAll);
                }

                @Override
                protected void onPostExecute(Object result) {
                    progress.setVisibility(View.GONE);
                    testResult.setVisibility(View.VISIBLE);
                    if (result != null) {
                        testResult.setText(a.getString(R.string.remote_test_ok));
                    } else {
                        testResult.setText(a.getString(webdavErrorText()));
                    }
                }
            }.execute();
        });

        // 浏览目录: pick the start folder below the server root
        browseBtn.setOnClickListener(v -> {
            final String feedUrl = WebDavStore.trimSlash(url.getText().toString().trim());
            final String loginText = login.getText().toString().trim();
            final String passwordText = password.getText().toString().trim();
            final boolean trustAll = trustCerts != null && trustCerts.isChecked();
            if (TxtUtils.isEmpty(feedUrl) || "http://".equals(feedUrl)) {
                Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                return;
            }
            com.foobnix.remote.RemoteDirPicker.showWebDav(a, feedUrl, loginText, passwordText, trustAll,
                    startDir.getText().toString().trim(),
                    pickedDir -> startDir.setText(pickedDir == null ? "" : pickedDir));
        });

        final boolean[] force = {false};
        infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override
            public void onClick(View v) {
                final String feedUrl = WebDavStore.trimSlash(url.getText().toString().trim());
                final String title = name.getText().toString().trim();
                final String loginText = login.getText().toString().trim();
                final String passwordText = password.getText().toString().trim();
                final boolean trustAll = trustCerts != null && trustCerts.isChecked();
                if (TxtUtils.isEmpty(feedUrl)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (force[0]) {
                    save(a, feedUrl, title, loginText, passwordText, trustAll, editAppState, onRefresh, infoDialog,
                            startDir.getText().toString().trim());
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                asyncTask = new AsyncTask() {
                    @Override
                    protected Object doInBackground(Object... params) {
                        return WebDavClient.list(feedUrl, loginText, passwordText, trustAll);
                    }

                    @Override
                    protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        if (result != null) {
                            save(a, feedUrl, title, loginText, passwordText, trustAll, editAppState, onRefresh, infoDialog,
                            startDir.getText().toString().trim());
                        } else {
                            force[0] = true;
                            infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.add_anyway);
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
    }

    /** Toast/error resource for the last {@link WebDavClient} failure kind. */
    private static int webdavErrorText() {
        String kind = WebDavClient.lastError;
        if ("auth".equals(kind)) {
            return R.string.webdav_auth_failed;
        }
        if ("ssl".equals(kind)) {
            return R.string.webdav_err_ssl;
        }
        if ("network".equals(kind)) {
            return R.string.webdav_err_network;
        }
        return R.string.webdav_connect_failed;
    }

    private static void save(Activity a, String url, String title, String login, String password, boolean trustAll,
                             String editAppState, Runnable onRefresh, AlertDialog dialog, String startDir) {
        if (editAppState != null) {
            AppState.get().allWebDavLinks = AppState.get().allWebDavLinks.replace(editAppState, "");
        }
        WebDavServer s = new WebDavServer(url, TxtUtils.isNotEmpty(title) ? title : url,
                TxtUtils.isEmpty(startDir) ? "" : startDir.trim());
        s.appState = WebDavServer.buildLine(url, s.title, s.startDir);
        WebDavStore.add(s);
        WebDavCredentials.save(a, url, login, password);
        WebDavCredentials.saveTrust(a, url, trustAll);
        AppProfile.save(a);
        Keyboards.close(a);
        dialog.dismiss();
        if (onRefresh != null) {
            onRefresh.run();
        }
    }
}
