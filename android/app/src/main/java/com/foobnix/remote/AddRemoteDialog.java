package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.ui2.fragment.PrefFragment2;
import com.foobnix.webdav.WebDavCredentials;

import java.util.List;

/**
 * Add / edit an SMB or SFTP server. The connection is verified with a root
 * directory listing on save; when the check fails the user can still
 * force-add (same UX as the WebDAV dialog).
 */
public class AddRemoteDialog {

    public static void showDialog(final Activity a, final String type, final Runnable onRefresh,
                                  final RemoteServer edit) {
        // PRO feature gate (same policy as WebDAV servers)
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
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
        }

        builder.setView(dialog);
        builder.setTitle(isSftp ? R.string.remote_add_sftp : R.string.remote_add_smb);
        builder.setPositiveButton(R.string.add, null);
        builder.setNegativeButton(R.string.close, (d, id) -> Keyboards.close(a));

        final AlertDialog infoDialog = builder.create();
        infoDialog.show();

        final boolean[] force = {false};
        infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override
            public void onClick(View v) {
                final String hostText = host.getText().toString().trim();
                final String title = name.getText().toString().trim();
                if (TxtUtils.isEmpty(hostText)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                int portNumber;
                try {
                    portNumber = Integer.parseInt(port.getText().toString().trim());
                } catch (Exception e) {
                    portNumber = isSftp ? 22 : 445;
                }
                final RemoteServer srv = edit == null ? new RemoteServer(type, title, hostText, portNumber) : edit;
                srv.title = TxtUtils.isNotEmpty(title) ? title : hostText;
                srv.host = hostText;
                srv.port = portNumber;
                srv.user = login.getText().toString().trim();
                srv.share = isSftp ? (edit == null ? "" : edit.share) : share.getText().toString().trim();
                srv.domain = isSftp ? (edit == null ? "" : edit.domain) : domain.getText().toString().trim();
                srv.keyPath = isSftp ? keyPath.getText().toString().trim() : "";
                srv.trustAll = true;

                if (force[0]) {
                    save(a, srv, type, isSftp, password, keyPass, onRefresh, infoDialog);
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                final int portFinal = portNumber;
                asyncTask = new AsyncTask() {
                    @Override
                    protected Object doInBackground(Object[] params) {
                        RemoteServer probe = new RemoteServer(type, srv.title, hostText, portFinal);
                        probe.user = srv.user;
                        probe.share = srv.share;
                        probe.domain = srv.domain;
                        probe.keyPath = srv.keyPath;
                        WebDavCredentials.save(a, RemoteStore.credentialsKey(probe.id), srv.user,
                                password.getText().toString());
                        if (isSftp) {
                            WebDavCredentials.save(a, RemoteStore.keyPassKey(probe.id), "", keyPass.getText().toString());
                        }
                        List res = isSftp
                                ? SftpClient.list(RemoteBook.browseRoot(type, probe.id))
                                : SmbClient.list(RemoteBook.browseRoot(type, probe.id));
                        WebDavCredentials.clear(a, RemoteStore.credentialsKey(probe.id));
                        WebDavCredentials.clear(a, RemoteStore.keyPassKey(probe.id));
                        return res;
                    }

                    @Override
                    protected void onPostExecute(Object result) {
                        progress.setVisibility(View.GONE);
                        if (result != null) {
                            save(a, srv, type, isSftp, password, keyPass, onRefresh, infoDialog);
                        } else {
                            force[0] = true;
                            infoDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.add_anyway);
                            Toast.makeText(a, "auth".equals(isSftp ? SftpClient.lastError : SmbClient.lastError)
                                    ? R.string.webdav_auth_failed
                                    : R.string.webdav_connect_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }.execute();
            }
        });
    }

    private static void save(Activity a, RemoteServer srv, String type, boolean isSftp,
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
