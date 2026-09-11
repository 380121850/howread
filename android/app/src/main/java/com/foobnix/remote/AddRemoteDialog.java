package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
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
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.ui2.fragment.PrefFragment2;
import com.foobnix.webdav.WebDavCredentials;

import java.util.List;

/**
 * Add / edit an SMB or SFTP server. A dedicated 测试连接 button verifies the
 * connection (works before the server is saved), and 浏览目录 opens a remote
 * folder picker that fills the share / start-directory fields.
 */
public class AddRemoteDialog {

    public static void showDialog(final Activity a, final String type, final Runnable onRefresh,
                                  final RemoteServer edit) {
        // PRO feature gate (same policy as WebDAV servers)
        if (!AppsConfig.isProFeaturesEnabled()) {
            PrefFragment2.proLockedToast(a);
            return;
        }

        final boolean isSftp = RemoteBook.TYPE_SFTP.equals(type);

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        final View dialog = LayoutInflater.from(a).inflate(R.layout.dialog_add_remote, null, false);

        final EditText name = dialog.findViewById(R.id.name);
        final EditText host = dialog.findViewById(R.id.host);
        final EditText port = dialog.findViewById(R.id.port);
        final EditText share = dialog.findViewById(R.id.share);
        final EditText domain = dialog.findViewById(R.id.domain);
        final EditText login = dialog.findViewById(R.id.login);
        final EditText password = dialog.findViewById(R.id.password);
        final EditText keyPath = dialog.findViewById(R.id.keyPath);
        final EditText keyPass = dialog.findViewById(R.id.keyPass);
        final EditText startDir = dialog.findViewById(R.id.startDir);
        final android.widget.CheckBox trustAll = dialog.findViewById(R.id.trustAll);
        final TextView testBtn = dialog.findViewById(R.id.remoteTestBtn);
        final TextView browseBtn = dialog.findViewById(R.id.remoteBrowseBtn);
        final TextView testResult = dialog.findViewById(R.id.remoteTestResult);
        final MyProgressBar progress = dialog.findViewById(R.id.MyProgressBarAddRemote);

        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        // per-protocol fields
        share.setVisibility(isSftp ? View.GONE : View.VISIBLE);
        domain.setVisibility(isSftp ? View.GONE : View.VISIBLE);
        keyPath.setVisibility(isSftp ? View.VISIBLE : View.GONE);
        keyPass.setVisibility(isSftp ? View.VISIBLE : View.GONE);

        if (edit != null) {
            name.setText(edit.title);
            host.setText(edit.host);
            port.setText(String.valueOf(edit.port));
            share.setText(edit.share);
            domain.setText(edit.domain);
            login.setText(edit.user);
            keyPath.setText(edit.keyPath);
            startDir.setText(edit.startDir);
            trustAll.setChecked(edit.trustAll);
            if (isSftp && TxtUtils.isNotEmpty(edit.keyPath)) {
                String[] kp = WebDavCredentials.load(a, RemoteStore.keyPassKey(edit.id));
                if (kp != null) {
                    keyPass.setText(kp[1]);
                }
            } else {
                String[] creds = WebDavCredentials.load(a, RemoteStore.credentialsKey(edit.id));
                if (creds != null) {
                    password.setText(creds[1]);
                }
            }
        } else {
            port.setText(isSftp ? "22" : "445");
            // secure default: verify the host key (TOFU), no trust-all
            trustAll.setChecked(false);
        }

        builder.setView(dialog);
        builder.setTitle(isSftp ? R.string.remote_add_sftp : R.string.remote_add_smb);
        builder.setPositiveButton(R.string.add, (d, id) -> {
            RemoteServer srv = buildServer(type, edit, isSftp, name, host, port, share, domain,
                    login, keyPath, startDir, trustAll);
            if (srv == null) {
                Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                return;
            }
            save(a, srv, isSftp, password, keyPass, onRefresh, (AlertDialog) d);
        });
        builder.setNegativeButton(R.string.close, (d, id) -> Keyboards.close(a));

        final AlertDialog infoDialog = builder.create();
        infoDialog.show();

        // 测试连接: verifies host/port/credentials before the server is saved
        final AsyncTask[] testTask = new AsyncTask[1];
        testBtn.setOnClickListener(v -> {
            final RemoteServer srv = buildServer(type, edit, isSftp, name, host, port, share,
                    domain, login, keyPath, startDir, trustAll);
            if (srv == null) {
                Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                return;
            }
            if (testTask[0] != null && AsyncTasks.isRunning(testTask[0])) {
                AsyncTasks.toastPleaseWait(a);
                return;
            }
            progress.setVisibility(View.VISIBLE);
            testResult.setVisibility(View.GONE);
            final String passwordText = password.getText().toString();
            final String keyPassText = keyPass.getText().toString();
            testTask[0] = new AsyncTask() {
                List res;

                @Override
                protected Object doInBackground(Object[] params) {
                    // "/" = share root (SMB, share empty → share list) / home (SFTP)
                    res = isSftp ? SftpClient.list(srv, "/", passwordText, keyPassText)
                            : SmbClient.list(srv, "/", passwordText);
                    return null;
                }

                @Override
                protected void onPostExecute(Object o) {
                    progress.setVisibility(View.GONE);
                    testResult.setVisibility(View.VISIBLE);
                    if (res != null) {
                        testResult.setText(a.getString(R.string.remote_test_ok) + " (" + res.size() + ")");
                    } else {
                        boolean auth = "auth".equals(isSftp ? SftpClient.lastError : SmbClient.lastError);
                        testResult.setText(auth ? a.getString(R.string.webdav_auth_failed)
                                : a.getString(R.string.webdav_connect_failed));
                    }
                }
            };
            testTask[0].execute();
        });

        // 浏览目录: pick a start folder (SMB: share list → folders; SFTP: home)
        browseBtn.setOnClickListener(v -> {
            final RemoteServer srv = buildServer(type, edit, isSftp, name, host, port, share,
                    domain, login, keyPath, startDir, trustAll);
            if (srv == null) {
                Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                return;
            }
            RemoteDirPicker.show(a, type, srv, password.getText().toString(),
                    keyPass.getText().toString(), (pickedShare, pickedDir) -> a.runOnUiThread(() -> {
                        if (!isSftp && TxtUtils.isNotEmpty(pickedShare)) {
                            share.setText(pickedShare);
                        }
                        startDir.setText(pickedDir == null ? "" : pickedDir);
                    }));
        });
    }

    /** Builds a transient RemoteServer from the dialog fields; null on invalid host. */
    private static RemoteServer buildServer(String type, RemoteServer edit, boolean isSftp,
                                            EditText name, EditText host, EditText port, EditText share,
                                            EditText domain, EditText login, EditText keyPath,
                                            EditText startDir, android.widget.CheckBox trustAll) {
        final String hostText = host.getText().toString().trim();
        if (TxtUtils.isEmpty(hostText)) {
            return null;
        }
        final String title = name.getText().toString().trim();
        int portNumber;
        try {
            portNumber = Integer.parseInt(port.getText().toString().trim());
        } catch (Exception e) {
            portNumber = RemoteBook.TYPE_SFTP.equals(type) ? 22 : 445;
        }
        final RemoteServer srv = edit == null ? new RemoteServer(type, title, hostText, portNumber) : edit;
        srv.title = TxtUtils.isNotEmpty(title) ? title : hostText;
        srv.host = hostText;
        srv.port = portNumber;
        srv.user = login.getText().toString().trim();
        srv.share = isSftp ? (edit == null ? "" : edit.share) : share.getText().toString().trim();
        srv.domain = isSftp ? (edit == null ? "" : edit.domain) : domain.getText().toString().trim();
        srv.keyPath = isSftp ? keyPath.getText().toString().trim() : "";
        srv.startDir = startDir.getText().toString().trim();
        srv.trustAll = trustAll.isChecked();
        return srv;
    }

    private static void save(Activity a, RemoteServer srv, boolean isSftp,
                             EditText password, EditText keyPass, Runnable onRefresh, AlertDialog dialog) {
        if (srv.appState != null) {
            // edit mode: replace the old persisted line
            RemoteStore.remove(srv);
        }
        srv.appState = RemoteServer.buildLine(srv);
        RemoteStore.add(srv);
        if (isSftp && TxtUtils.isNotEmpty(srv.keyPath)) {
            WebDavCredentials.save(a, RemoteStore.keyPassKey(srv.id), "", keyPass.getText().toString());
        } else {
            WebDavCredentials.save(a, RemoteStore.credentialsKey(srv.id), srv.user, password.getText().toString());
        }
        AppProfile.save(a);
        Keyboards.close(a);
        dialog.dismiss();
        if (onRefresh != null) {
            onRefresh.run();
        }
    }
}
