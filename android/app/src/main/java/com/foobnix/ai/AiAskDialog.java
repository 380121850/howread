package com.foobnix.ai;

import android.app.Activity;
import android.app.Dialog;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.AsyncTasks;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.MyProgressBar;

/**
 * "Send to AI" from the text-selection popup: a full-screen page with the
 * selected text (editable), an optional question, and the model answer in a
 * large, selectable, scrolling area (dialogs are too small for long replies).
 */
public class AiAskDialog {

    public static void show(final Activity a, final String selectedText) {
        show(a, selectedText, null, 0f);
    }

    public static void show(final Activity a, final String selectedText, final String bookPath) {
        show(a, selectedText, bookPath, 0f);
    }

    /** @param percent current reading position (0..1 fraction, same as bookmarks) */
    public static void show(final Activity a, final String selectedText, final String bookPath, final float percent) {
        if (a == null) {
            return;
        }
        // PRO feature gate (AI笔记/发送给AI): defense in depth — the selection
        // menu item is already gated
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }
        if (TxtUtils.isEmpty(AppState.get().aiBaseUrl) || TxtUtils.isEmpty(AppState.get().aiModel)
                || TxtUtils.isEmpty(AiCredentials.load(a))) {
            Toast.makeText(a, R.string.ai_ask_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_ai_ask, null, false);
        final EditText text = (EditText) view.findViewById(R.id.aiAskText);
        final EditText question = (EditText) view.findViewById(R.id.aiAskQuestion);
        final TextView send = (TextView) view.findViewById(R.id.aiAskSend);
        final TextView result = (TextView) view.findViewById(R.id.aiAskResult);
        final TextView saveNote = (TextView) view.findViewById(R.id.aiAskSaveNote);
        final MyProgressBar progress = (MyProgressBar) view.findViewById(R.id.aiAskProgress);
        final ImageView back = (ImageView) view.findViewById(R.id.aiAskBack);

        text.setText(selectedText == null ? "" : selectedText);

        final Dialog dialog = new Dialog(a) {
            @Override public void onBackPressed() {
                Keyboards.close(a);
                super.onBackPressed();
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();

        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Keyboards.close(a);
                dialog.dismiss();
            }
        });

        saveNote.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String answerText = result.getText().toString().trim();
                if (TxtUtils.isEmpty(answerText)) {
                    Toast.makeText(a, R.string.note_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                String originalText = text.getText().toString().trim();
                String q = question.getText().toString().trim();
                // Build the note text: selected text + optional question
                String noteText = TxtUtils.isEmpty(q)
                        ? originalText
                        : originalText + "\n\nQ: " + q;

                AppBookmark note = new AppBookmark();
                if (TxtUtils.isNotEmpty(bookPath)) {
                    note.setPath(bookPath);
                }
                note.text = noteText;
                note.aiAnswer = answerText;
                note.isAiNote = true;
                // Remember the reading position so the note can jump back to it
                note.p = percent;
                note.t = System.currentTimeMillis();
                BookmarksData.get().add(note);

                Toast.makeText(a, R.string.note_saved, Toast.LENGTH_SHORT).show();
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            // NOTE: the view tag is reserved by Librera styles (holds a String),
            // so the running task is kept in a field instead
            AsyncTask running;

            @Override public void onClick(View v) {
                final String content = text.getText().toString().trim();
                if (TxtUtils.isEmpty(content)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (AsyncTasks.isRunning(running)) {
                    AsyncTasks.toastPleaseWait(a);
                    return;
                }
                final String q = question.getText().toString().trim();
                // no extra question: default to explaining the selected text
                final String prompt = TxtUtils.isEmpty(q)
                        ? a.getString(R.string.ai_ask_default_prompt, content)
                        : content + "\n\n" + q;

                send.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                result.setVisibility(View.VISIBLE);
                result.setText(R.string.ai_ask_thinking);
                running = new AsyncTask() {
                    @Override protected Object doInBackground(Object[] params) {
                        return AiClient.ask(a, prompt);
                    }

                @Override protected void onPostExecute(Object r) {
                    progress.setVisibility(View.GONE);
                    send.setEnabled(true);
                    AiClient.TestResult res = (AiClient.TestResult) r;
                    if (res.ok && TxtUtils.isNotEmpty(res.reply)) {
                        result.setText(res.truncated
                                ? res.reply + "\n\n" + a.getString(R.string.ai_reply_truncated)
                                : res.reply);
                        saveNote.setVisibility(View.VISIBLE);
                    } else {
                        result.setText(AiConfigDialog.resultErrorText(a, res.error, res.detail));
                        saveNote.setVisibility(View.GONE);
                    }
                }
                };
                running.execute();
            }
        });
    }
}
