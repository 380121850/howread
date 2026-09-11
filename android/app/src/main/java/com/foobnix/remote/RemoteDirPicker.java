package com.foobnix.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.AsyncTask;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.webdav.WebDavItem;

import java.util.List;

/**
 * Remote start-folder chooser for the SMB / SFTP server dialog. Navigation
 * works on servers that are not persisted yet (credentials passed
 * explicitly): SMB lists the shares first, then folders inside the picked
 * share; SFTP starts at the login home.
 */
public class RemoteDirPicker {


    public interface Callback {
        /**
         * @param share SMB share name ("" for SFTP)
         * @param dir   path inside the share (SMB) or home-relative (SFTP),
         *              "" = root
         */
        void onPicked(String share, String dir);
    }

    public static void show(final Activity a, final String type, final RemoteServer srv,
                            final String password, final String keyPass, final Callback cb) {
        final boolean isSftp = RemoteBook.TYPE_SFTP.equals(type);

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        final ListView list = new ListView(a);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final MyProgressBar progress = new MyProgressBar(a);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(a,
                android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setView(root);
        builder.setTitle(R.string.remote_pick_dir_title);
        builder.setPositiveButton(R.string.remote_pick_here, null);
        builder.setNeutralButton(R.string.remote_up, null);
        builder.setNegativeButton(R.string.close, null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        // navigation state: SMB — shareScope ("" = share list) + sub; SFTP — cur
        final String[] shareScope = {srv.share == null ? "" : srv.share};
        final String[] sub = {""};
        final String[] cur = {""};
        final boolean[] loading = {false};

        final Runnable navigate = () -> {
            if (loading[0]) {
                return;
            }
            final String share = shareScope[0];
            final String dir = isSftp ? cur[0] : sub[0];
            dialog.setTitle(locationText(a, isSftp, shareScope[0], isSftp ? cur[0] : sub[0]));
            progress.setVisibility(View.VISIBLE);
            loading[0] = true;
            new AsyncTask() {
                List<WebDavItem> res;

                @Override
                protected Object doInBackground(Object[] params) {
                    if (isSftp) {
                        res = SftpClient.list(srv, dir.isEmpty() ? "/" : "/" + dir, password, keyPass);
                    } else {
                        res = SmbClient.list(smbWithShare(srv, share),
                                dir.isEmpty() ? "/" : "/" + dir, password);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Object o) {
                    loading[0] = false;
                    progress.setVisibility(View.GONE);
                    adapter.clear();
                    if (res == null) {
                        boolean auth = "auth".equals(isSftp ? SftpClient.lastError : SmbClient.lastError);
                        Toast.makeText(a, auth ? R.string.webdav_auth_failed
                                : R.string.webdav_connect_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    int dirs = 0;
                    for (WebDavItem it : res) {
                        if (it.isDir) {
                            adapter.add(it.name);
                            dirs++;
                        }
                    }
                    if (dirs == 0) {
                        Toast.makeText(a, R.string.remote_no_subdirs, Toast.LENGTH_SHORT).show();
                    }
                    // SMB: a share must be entered before a folder can be picked
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setEnabled(isSftp || !shareScope[0].isEmpty());
                }
            }.execute();
        };

        list.setOnItemClickListener((parent, view, pos, id) -> {
            String name = adapter.getItem(pos);
            if (name == null) {
                return;
            }
            if (isSftp) {
                cur[0] = cur[0].isEmpty() ? name : cur[0] + "/" + name;
            } else if (shareScope[0].isEmpty()) {
                shareScope[0] = name;
            } else {
                sub[0] = sub[0].isEmpty() ? name : sub[0] + "/" + name;
            }
            navigate.run();
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (isSftp) {
                cb.onPicked("", cur[0]);
            } else {
                cb.onPicked(shareScope[0], sub[0]);
            }
            dialog.dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (isSftp) {
                int i = cur[0].lastIndexOf('/');
                cur[0] = i < 0 ? "" : cur[0].substring(0, i);
            } else if (!sub[0].isEmpty()) {
                int i = sub[0].lastIndexOf('/');
                sub[0] = i < 0 ? "" : sub[0].substring(0, i);
            } else {
                shareScope[0] = "";
            }
            navigate.run();
        });

        navigate.run();
    }

    private static String locationText(Activity a, boolean isSftp, String share, String dir) {
        if (isSftp) {
            return "/" + dir;
        }
        return share.isEmpty() ? a.getString(R.string.remote_pick_shares)
                : share + (dir.isEmpty() ? "" : "/" + dir);
    }

    /** A transient copy of the server with the given share (credentials by id). */
    private static RemoteServer smbWithShare(RemoteServer s, String share) {
        RemoteServer c = new RemoteServer(RemoteBook.TYPE_SMB, s.title, s.host, s.port);
        c.id = s.id;
        c.user = s.user;
        c.domain = s.domain;
        c.share = share == null ? "" : share;
        c.trustAll = s.trustAll;
        return c;
    }
}
