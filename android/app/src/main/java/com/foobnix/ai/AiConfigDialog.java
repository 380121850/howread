package com.foobnix.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
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

/**
 * AI provider settings (Anx Reader style: unified fields + test-then-save):
 * protocol picker (OpenAI-compatible / Claude / Gemini), base URL, API key
 * (stored encrypted via AiCredentials) and model name, with a real minimal
 * "ping" request as the connection test.
 */
public class AiConfigDialog {

    public static void showDialog(final Activity a, final Runnable onRefresh) {

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_ai_config, null, false);
        final TextView protocolValue = (TextView) view.findViewById(R.id.aiProtocolValue);
        final EditText url = (EditText) view.findViewById(R.id.aiBaseUrl);
        final EditText apiKey = (EditText) view.findViewById(R.id.aiApiKey);
        final EditText model = (EditText) view.findViewById(R.id.aiModel);
        final EditText maxTokens = (EditText) view.findViewById(R.id.aiMaxTokens);
        final CheckBox thinking = (CheckBox) view.findViewById(R.id.aiThinking);
        final TextView modelList = (TextView) view.findViewById(R.id.aiModelList);
        final TextView test = (TextView) view.findViewById(R.id.aiTestConnection);
        final TextView status = (TextView) view.findViewById(R.id.aiTestStatus);
        final EditText chatInput = (EditText) view.findViewById(R.id.aiChatInput);
        final TextView chatSend = (TextView) view.findViewById(R.id.aiChatSend);
        final android.view.View chatResultScroll = view.findViewById(R.id.aiChatResultScroll);
        final TextView chatResult = (TextView) view.findViewById(R.id.aiChatResult);
        final MyProgressBar progress = (MyProgressBar) view.findViewById(R.id.aiProgress);
        final TextView profileValue = (TextView) view.findViewById(R.id.aiProfileValue);

        TxtUtils.underlineTextView(protocolValue);
        TxtUtils.underlineTextView(test);
        TxtUtils.underlineTextView(modelList);
        TxtUtils.underlineTextView(profileValue);

        // API key masked by default; the toggle shows/hides it in place
        final TextView keyToggle = (TextView) view.findViewById(R.id.aiKeyToggle);
        TxtUtils.underlineTextView(keyToggle);
        apiKey.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        keyToggle.setText(R.string.ai_key_show);
        keyToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean masked = apiKey.getTransformationMethod() != null;
                apiKey.setTransformationMethod(masked
                        ? android.text.method.HideReturnsTransformationMethod.getInstance()
                        : android.text.method.PasswordTransformationMethod.getInstance());
                keyToggle.setText(masked ? R.string.ai_key_hide : R.string.ai_key_show);
                apiKey.setSelection(apiKey.getText() == null ? 0 : apiKey.getText().length());
            }
        });

        // the named config chosen in the dialog; persisted on save only.
        // The active vendor is resolved through AiVendors (pointer model).
        final AiVendors.Entry cur = AiVendors.active(a);
        final String[] selectedName = {cur.found ? cur.name : ""};
        refreshProfileLabel(profileValue, selectedName[0]);

        // in-flight dialog tasks (list models / test / chat): cancelled when
        // the dialog is dismissed; their callbacks also guard isFinishing
        final java.util.List<AsyncTask> dialogTasks = new java.util.ArrayList<AsyncTask>();

        profileValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu popup = new PopupMenu(a, v);
                org.json.JSONArray arr = parseProfiles(AppState.get().aiConfigs);
                for (int i = 0; i < arr.length(); i++) {
                    final org.json.JSONObject e = arr.optJSONObject(i);
                    if (e == null || TxtUtils.isEmpty(e.optString("name"))) {
                        continue;
                    }
                    final String name = e.optString("name");
                    popup.getMenu().add(name).setOnMenuItemClickListener(item -> {
                        // picking a config loads ALL of its parameters into
                        // the dialog fields (persisted on save only)
                        savedLocal = e.optString("protocol", AiClient.PROTOCOL_OPENAI);
                        url.setText(e.optString("baseUrl", ""));
                        apiKey.setText(AiVendors.plainKey(e.optString("apiKey", "")));
                        model.setText(e.optString("model", ""));
                        int mt = e.optInt("maxTokens", 4096);
                        maxTokens.setText(String.valueOf(mt > 0 ? mt : 4096));
                        thinking.setChecked(e.optBoolean("thinking", false));
                        selectedName[0] = name;
                        refreshProtocolLabel(protocolValue);
                        refreshProfileLabel(profileValue, name);
                        return true;
                    });
                }
                popup.getMenu().add(R.string.ai_profile_delete).setOnMenuItemClickListener(item -> {
                    if (TxtUtils.isEmpty(selectedName[0])) {
                        return true;
                    }
                    // deletion must survive the AI config union sync
                    com.foobnix.remote.RemoteTombstones.add(
                            com.foobnix.remote.RemoteTombstones.TOMB_AI + selectedName[0]);
                    AppState.get().aiConfigs = removeProfile(AppState.get().aiConfigs, selectedName[0]);
                    // deleting the ACTIVE vendor must also clear the effective
                    // config (AiClient reads the AppState fields + encrypted
                    // key, not the profile list) — otherwise the deleted
                    // vendor's endpoint/key silently stay in effect
                    if (selectedName[0].equals(AppState.get().aiConfigName)) {
                        // the pointer is the only effective-config state
                        AppState.get().aiConfigName = "";
                    }
                    AppProfile.save(a);
                    selectedName[0] = "";
                    // reset the dialog fields to a clean sheet (same as adding
                    // a new vendor) so a later 保存 cannot re-persist the
                    // deleted vendor's values
                    savedLocal = AiClient.PROTOCOL_OPENAI;
                    url.setText(AiClient.defaultUrl(savedLocal));
                    apiKey.setText("");
                    model.setText("");
                    maxTokens.setText(String.valueOf(PROFILE_BUDGET_DEFAULT));
                    thinking.setChecked(false);
                    refreshProtocolLabel(protocolValue);
                    refreshProfileLabel(profileValue, "");
                    return true;
                });
                // last entry: add a new vendor — asks for the name, then clears
                // the fields for a fresh config; pressing the dialog's 保存
                // stores it under that name
                popup.getMenu().add(R.string.ai_profile_add).setOnMenuItemClickListener(item -> {
                    final EditText nameEdit = new EditText(a);
                    nameEdit.setHint(R.string.ai_profile_name_hint);
                    nameEdit.setSingleLine(true);
                    new AlertDialog.Builder(a)
                            .setTitle(R.string.ai_profile_add)
                            .setView(nameEdit)
                            .setPositiveButton(R.string.webdav_sync_save,
                                    (d, w) -> {
                                        String name = nameEdit.getText().toString().trim();
                                        if (TxtUtils.isEmpty(name)) {
                                            Toast.makeText(a, R.string.ai_profile_name_empty,
                                                    Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // clean sheet: protocol default endpoint,
                                        // everything else empty
                                        url.setText(AiClient.defaultUrl(savedLocal));
                                        apiKey.setText("");
                                        model.setText("");
                                        maxTokens.setText(String.valueOf(PROFILE_BUDGET_DEFAULT));
                                        thinking.setChecked(false);
                                        selectedName[0] = name;
                                        refreshProtocolLabel(protocolValue);
                                        refreshProfileLabel(profileValue, name);
                                    })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                });
                popup.show();
            }
        });

        // protocol chosen inside the dialog; persisted on save only
        savedLocal = cur.found ? cur.protocol : AiClient.PROTOCOL_OPENAI;
        final String openProtocol = savedLocal;
        if (cur.found && TxtUtils.isNotEmpty(cur.baseUrl)) {
            url.setText(cur.baseUrl);
        } else {
            url.setText(AiClient.defaultUrl(openProtocol));
        }
        apiKey.setText(cur.apiKey);
        model.setText(cur.model);
        maxTokens.setText(String.valueOf(cur.maxTokens));
        thinking.setChecked(cur.thinking);
        refreshProtocolLabel(protocolValue);

        protocolValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu popup = new PopupMenu(a, v);
                popup.getMenu().add(R.string.ai_protocol_openai);
                popup.getMenu().add(R.string.ai_protocol_anthropic);
                popup.getMenu().add(R.string.ai_protocol_google);
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                        String protocol;
                        if (item.getTitle().equals(a.getString(R.string.ai_protocol_anthropic))) {
                            protocol = AiClient.PROTOCOL_ANTHROPIC;
                        } else if (item.getTitle().equals(a.getString(R.string.ai_protocol_google))) {
                            protocol = AiClient.PROTOCOL_GOOGLE;
                        } else {
                            protocol = AiClient.PROTOCOL_OPENAI;
                        }
                        // switching protocol pre-fills its default endpoint
                        String current = url.getText().toString().trim();
                        if (TxtUtils.isEmpty(current) || current.equals(AiClient.defaultUrl(openProtocol))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_ANTHROPIC))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_GOOGLE))
                                || current.equals(AiClient.defaultUrl(AiClient.PROTOCOL_OPENAI))) {
                            url.setText(AiClient.defaultUrl(protocol));
                        }
                        savedLocal = protocol;
                        refreshProtocolLabel(protocolValue);
                        return true;
                    }
                });
                popup.show();
            }
        });

        // fetch the provider's model list and let the user pick one; manual
        // typing stays possible when the endpoint has no /models
        modelList.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
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
                        final StringBuilder err = new StringBuilder();
                        java.util.List<String> ids = AiClient.listModels(savedLocal, u, k, err);
                        return new Object[]{ids, err.toString()};
                    }

                    @Override protected void onPostExecute(Object result) {
                        if (a.isFinishing() || a.isDestroyed()) {
                            return; // a dead activity must not show the popup
                        }
                        progress.setVisibility(View.GONE);
                        Object[] rr = (Object[]) result;
                        java.util.List<String> ids = (java.util.List<String>) rr[0];
                        if (ids == null || ids.isEmpty()) {
                            String err = (String) rr[1];
                            String kind = err == null || err.isEmpty() ? "other" : err.split(" ")[0];
                            Toast.makeText(a, resultErrorText(a, kind, err), Toast.LENGTH_LONG).show();
                            return;
                        }
                        PopupMenu popup = new PopupMenu(a, v);
                        for (final String id : ids) {
                            popup.getMenu().add(id).setOnMenuItemClickListener(item -> {
                                model.setText(id);
                                return true;
                            });
                        }
                        popup.show();
                    }
                }.execute();
                dialogTasks.add(asyncTask);
            }
        });

        test.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
                final String m = model.getText().toString().trim();
                if (TxtUtils.isEmpty(u) || TxtUtils.isEmpty(k) || TxtUtils.isEmpty(m)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                progress.setVisibility(View.VISIBLE);
                status.setText(R.string.webdav_syncing);
                final boolean th = thinking.isChecked();
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.chat(a, savedLocal, u, k, m, "ping", 5, th);
                    }

                    @Override protected void onPostExecute(Object result) {
                        if (a.isFinishing() || a.isDestroyed()) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        AiClient.TestResult r = (AiClient.TestResult) result;
                        if (r.ok) {
                            status.setText(R.string.webdav_sync_test_ok);
                        } else {
                            status.setText(resultErrorText(a, r.error, r.detail));
                        }
                    }
                }.execute();
                dialogTasks.add(asyncTask);
            }
        });

        // chat test: type arbitrary text and see whether the configured model
        // responds — uses the values currently typed in the fields, so the
        // config can be validated before saving
        chatSend.setOnClickListener(new View.OnClickListener() {
            AsyncTask asyncTask;

            @Override public void onClick(View v) {
                final String u = url.getText().toString().trim();
                final String k = apiKey.getText().toString().trim();
                final String m = model.getText().toString().trim();
                final String text = chatInput.getText().toString().trim();
                if (TxtUtils.isEmpty(u) || TxtUtils.isEmpty(k) || TxtUtils.isEmpty(m)
                        || TxtUtils.isEmpty(text)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(asyncTask)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                int budget = 4096;
                try {
                    budget = Integer.parseInt(maxTokens.getText().toString().trim());
                } catch (Exception ignored) {
                }
                if (budget <= 0) {
                    budget = 4096;
                }
                final int b = budget;
                final boolean th = thinking.isChecked();
                // free the dialog from the keyboard so the result is visible;
                // must use the dialog view's token (the activity token is ignored)
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) a.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
                progress.setVisibility(View.VISIBLE);
                chatSend.setEnabled(false);
                chatResultScroll.setVisibility(View.VISIBLE);
                chatResult.setText(R.string.ai_ask_thinking);
                asyncTask = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.chat(a, savedLocal, u, k, m, text, b, th);
                    }

                    @Override protected void onPostExecute(Object result) {
                        if (a.isFinishing() || a.isDestroyed()) {
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        chatSend.setEnabled(true);
                        AiClient.TestResult r = (AiClient.TestResult) result;
                        if (r.ok && TxtUtils.isNotEmpty(r.reply)) {
                            chatResult.setText(r.truncated
                                    ? r.reply + "\n\n" + a.getString(R.string.ai_reply_truncated)
                                    : r.reply);
                        } else {
                            chatResult.setText(resultErrorText(a, r.error, r.detail));
                        }
                    }
                }.execute();
                dialogTasks.add(asyncTask);
            }
        });

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.ai_config_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.webdav_sync_save, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                int budget = 4096;
                try {
                    budget = Integer.parseInt(maxTokens.getText().toString().trim());
                } catch (Exception ignored) {
                }
                if (budget <= 0) {
                    budget = 4096;
                }
                // unnamed config (legacy style): keep it usable under a
                // stable name instead of the removed flat-field copy
                if (TxtUtils.isEmpty(selectedName[0])
                        && (TxtUtils.isNotEmpty(url.getText().toString().trim())
                        || TxtUtils.isNotEmpty(apiKey.getText().toString()))) {
                    selectedName[0] = "default";
                    refreshProfileLabel(profileValue, selectedName[0]);
                }
                // a named config is selected: store the current field values
                // back into it (edit-in-place) and mark it active — the entry
                // IS the effective config, resolved through the aiConfigName
                // pointer at request time
                if (TxtUtils.isNotEmpty(selectedName[0])) {
                    AppState.get().aiConfigs = upsertProfile(AppState.get().aiConfigs,
                            profileJson(selectedName[0], savedLocal,
                                    url.getText().toString().trim(),
                                    apiKey.getText().toString(),
                                    model.getText().toString().trim(),
                                    budget, thinking.isChecked()));
                    // (re)saving a vendor clears its deletion marker
                    com.foobnix.remote.RemoteTombstones.clear(
                            com.foobnix.remote.RemoteTombstones.TOMB_AI + selectedName[0]);
                    AppState.get().aiConfigName = selectedName[0];
                }
                AppProfile.save(a);
                Keyboards.close(a);
                if (onRefresh != null) {
                    onRefresh.run();
                }
            }
        });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                Keyboards.close(a);
            }
        });
        final android.app.AlertDialog aiDialog = builder.show();
        aiDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                for (AsyncTask t : dialogTasks) {
                    try {
                        t.cancel(true);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /** protocol chosen inside the dialog (persisted on save only) */
    private static String savedLocal = AiClient.PROTOCOL_OPENAI;

    // -------------------------------------------------------- saved AI profiles

    private static final int PROFILE_BUDGET_DEFAULT = 4096;

    private static org.json.JSONArray parseProfiles(String json) {
        try {
            return new org.json.JSONArray(json);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    private static org.json.JSONObject findProfile(String json, String name) {
        org.json.JSONArray arr = parseProfiles(json);
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject e = arr.optJSONObject(i);
            if (e != null && name.equals(e.optString("name"))) {
                return e;
            }
        }
        return null;
    }

    private static org.json.JSONObject profileJson(String name, String protocol, String baseUrl,
            String apiKey, String model, int maxTokens, boolean thinking) {
        org.json.JSONObject e = new org.json.JSONObject();
        try {
            e.put("name", name);
            e.put("protocol", protocol);
            e.put("baseUrl", baseUrl);
            // key stored PLAINTEXT since 0926 (the dialog masks it); the
            // sync export strips it before anything leaves the device
            e.put("apiKey", apiKey == null ? "" : apiKey);
            e.put("model", model);
            e.put("maxTokens", maxTokens);
            e.put("thinking", thinking);
        } catch (Exception ignored) {
        }
        return e;
    }

    /** Replace the same-name entry or append; keeps list order stable. */
    private static String upsertProfile(String json, org.json.JSONObject entry) {
        try {
            String name = entry.optString("name");
            org.json.JSONArray arr = parseProfiles(json);
            org.json.JSONArray out = new org.json.JSONArray();
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject e = arr.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                if (name.equals(e.optString("name"))) {
                    out.put(entry);
                    replaced = true;
                } else {
                    out.put(e);
                }
            }
            if (!replaced) {
                out.put(entry);
            }
            return out.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String removeProfile(String json, String name) {
        try {
            org.json.JSONArray arr = parseProfiles(json);
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject e = arr.optJSONObject(i);
                if (e != null && !name.equals(e.optString("name"))) {
                    out.put(e);
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static int parseBudget(EditText maxTokens) {
        try {
            int budget = Integer.parseInt(maxTokens.getText().toString().trim());
            return budget > 0 ? budget : PROFILE_BUDGET_DEFAULT;
        } catch (Exception e) {
            return PROFILE_BUDGET_DEFAULT;
        }
    }

    private static void refreshProfileLabel(TextView v, String name) {
        if (TxtUtils.isEmpty(name)) {
            v.setText(R.string.ai_profile_none);
        } else {
            v.setText(name);
        }
    }

    /** AI-specific error text (never reuse the WebDAV strings) + raw detail. */
    public static String resultErrorText(Activity a, String error, String detail) {
        int res;
        if ("no_config".equals(error)) {
            res = R.string.ai_err_no_config;
        } else if ("auth".equals(error)) {
            res = R.string.ai_err_auth;
        } else if ("rate".equals(error)) {
            res = R.string.ai_err_rate;
        } else if ("timeout".equals(error)) {
            res = R.string.ai_err_timeout;
        } else if ("network".equals(error)) {
            res = R.string.ai_err_network;
        } else if ("model".equals(error)) {
            res = R.string.ai_err_model;
        } else if ("empty".equals(error)) {
            res = R.string.ai_err_empty;
        } else {
            res = R.string.ai_err_other;
        }
        String text = a.getString(res);
        if (TxtUtils.isNotEmpty(detail)) {
            text += "\n" + detail;
        }
        return text;
    }

    public static String testErrorText(Activity a, String error) {
        return resultErrorText(a, error, "");
    }

    private static void refreshProtocolLabel(TextView protocolValue) {
        if (AiClient.PROTOCOL_ANTHROPIC.equals(savedLocal)) {
            protocolValue.setText(R.string.ai_protocol_anthropic);
        } else if (AiClient.PROTOCOL_GOOGLE.equals(savedLocal)) {
            protocolValue.setText(R.string.ai_protocol_google);
        } else {
            protocolValue.setText(R.string.ai_protocol_openai);
        }
    }
}
