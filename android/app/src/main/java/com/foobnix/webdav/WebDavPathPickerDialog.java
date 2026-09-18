package com.foobnix.webdav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server-side folder browser for the WebDAV sync path: PROPFIND listing of
 * directories only, tap to enter, "select this folder" confirms, plus a
 * create-folder action (MKCOL). Purely a picker — it never uploads anything.
 */
public class WebDavPathPickerDialog {

    private static final String UP = "..";

    public static void showDialog(final Activity a, final String url, final String login,
            final String password, final boolean trustAll, String startRel,
            final ResultResponse<String> onSelected) {

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_webdav_picker, null, false);
        final TextView pathView = (TextView) view.findViewById(R.id.webdavPickerPath);
        final ListView list = (ListView) view.findViewById(R.id.webdavPickerList);
        final MyProgressBar progress = (MyProgressBar) view.findViewById(R.id.webdavPickerProgress);

        final List<String> names = new ArrayList<String>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);

        final String base = WebDavStore.trimSlash(url);
        // relative path state: "" = server root
        final String[] rel = { TxtUtils.isEmpty(startRel) ? "" : startRel };

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.webdav_path_pick_title);
        builder.setView(view);
        builder.setNeutralButton(R.string.webdav_path_new_folder, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
            }
        });
        builder.setNegativeButton(R.string.close, null);
        builder.setPositiveButton(R.string.webdav_path_select, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                onSelected.onResultRecive(rel[0]);
            }
        });
        final AlertDialog dialog = builder.create();
        dialog.show();

        // keep the picker open on "new folder" (default would dismiss)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final EditText edit = new EditText(a);
                new AlertDialog.Builder(a)
                        .setTitle(R.string.webdav_path_new_folder)
                        .setView(edit)
                        .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                final String name = edit.getText().toString().trim();
                                if (TxtUtils.isEmpty(name)) {
                                    return;
                                }
                                if (AsyncTasks.isRunning(asyncTask)) {
                                    return;
                                }
                                final String target = base + "/" + encodePath(join(rel[0], name));
                                asyncTask = new AsyncTask() {
                                    @Override protected Object doInBackground(Object[] params) {
                                        try {
                                            WebDavClient.sardine(login, password, trustAll).createDirectory(target);
                                            return Boolean.TRUE;
                                        } catch (Exception e) {
                                            return e;
                                        }
                                    }

                                    @Override protected void onPostExecute(Object result) {
                                        if (Boolean.TRUE.equals(result)) {
                                            rel[0] = join(rel[0], name);
                                            load(a, base, login, password, trustAll, rel, names, adapter, pathView, progress, list);
                                        } else {
                                            Toast.makeText(a, R.string.webdav_connect_failed, Toast.LENGTH_LONG).show();
                                        }
                                    }
                                }.execute();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                String name = names.get(position);
                if (UP.equals(name)) {
                    int idx = rel[0].lastIndexOf('/');
                    rel[0] = idx <= 0 ? "" : rel[0].substring(0, idx);
                } else {
                    rel[0] = join(rel[0], name);
                }
                load(a, base, login, password, trustAll, rel, names, adapter, pathView, progress, list);
            }
        });

        load(a, base, login, password, trustAll, rel, names, adapter, pathView, progress, list);
    }

    private static String join(String rel, String name) {
        return TxtUtils.isEmpty(rel) ? name : rel + "/" + name;
    }

    /** Navigation sequence: only the newest directory request may paint its
     * result (a slow older response used to overwrite the newer list). */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Percent-encodes every path segment (server-provided names may contain
     * spaces, '?', '#', '%'); the '/' separators are kept. */
    private static String encodePath(String rel) {
        final StringBuilder sb = new StringBuilder();
        for (String seg : rel.split("/")) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(android.net.Uri.encode(seg));
        }
        return sb.toString();
    }

    private static void load(final Activity a, final String base, final String login, final String password,
            final boolean trustAll, final String[] rel, final List<String> names, final ArrayAdapter<String> adapter,
            final TextView pathView, final MyProgressBar progress, final ListView list) {

        pathView.setText("/" + rel[0]);
        names.clear();
        names.add(UP);
        adapter.notifyDataSetChanged();
        progress.setVisibility(View.VISIBLE);

        final String target = base + (TxtUtils.isEmpty(rel[0]) ? "/" : "/" + encodePath(rel[0]));
        final int seq = SEQ.incrementAndGet();
        new AsyncTask() {
            @Override protected Object doInBackground(Object[] params) {
                return WebDavClient.list(target, login, password, trustAll);
            }

            @Override protected void onPostExecute(Object result) {
                if (seq != SEQ.get()) {
                    // a newer navigation superseded this request: applying the
                    // stale response painted list and path out of sync
                    return;
                }
                progress.setVisibility(View.GONE);
                List<WebDavItem> items = (List<WebDavItem>) result;
                if (items == null) {
                    String kind = WebDavClient.lastError;
                    int msg = "auth".equals(kind) ? R.string.webdav_auth_failed
                            : "ssl".equals(kind) ? R.string.webdav_err_ssl
                            : "network".equals(kind) ? R.string.webdav_err_network
                            : R.string.webdav_connect_failed;
                    Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                    return;
                }
                List<String> dirs = new ArrayList<String>();
                for (WebDavItem item : items) {
                    if (item.isDir) {
                        dirs.add(item.name);
                    }
                }
                Collections.sort(dirs, String.CASE_INSENSITIVE_ORDER);
                names.addAll(dirs);
                adapter.notifyDataSetChanged();
                list.invalidateViews();
            }
        }.execute();
    }
}
