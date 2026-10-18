package com.foobnix.pdf.info.view;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppBookmark;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen note editor behind the reader toolbar pencil icon (replaces the
 * old "annotations & handwriting" panel entry). The first line shows the
 * creation time and the page the note is anchored to; the rest of the screen
 * is a large text field with cancel/save at the bottom. A saved note is an
 * AppBookmark (isAiNote) anchored to the current position, so it shows up in
 * the 书签笔记 list — where tapping it jumps back to the page — and syncs
 * over WebDAV together with the bookmarks.
 */
public class NoteEditDialog {

    public static void show(final Activity a, final DocumentController dc) {
        show(a, dc, null);
    }

    public static void show(final Activity a, final DocumentController dc, final String prefill) {
        if (a == null || dc == null || dc.getCurrentBook() == null) {
            return;
        }
        final String bookPath = dc.getCurrentBook().getPath();
        final float percent = dc.getPercentage();
        final int page = dc.getCurentPageFirst1();
        final int pages = dc.getPageCount();

        final View view = LayoutInflater.from(a).inflate(R.layout.dialog_note_edit, null, false);
        final TextView meta = (TextView) view.findViewById(R.id.noteEditMeta);
        final EditText text = (EditText) view.findViewById(R.id.noteEditText);
        final TextView cancel = (TextView) view.findViewById(R.id.noteEditCancel);
        final TextView save = (TextView) view.findViewById(R.id.noteEditSave);

        if (!TxtUtils.isEmpty(prefill)) {
            text.setText(prefill);
            text.setSelection(prefill.length());
        }

        String metaText = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        if (pages > 0) {
            metaText += " · " + a.getString(R.string.note_edit_page_fmt, page, pages);
        }
        meta.setText(metaText);

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

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Keyboards.close(a);
                dialog.dismiss();
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String noteText = text.getText().toString().trim();
                if (TxtUtils.isEmpty(noteText)) {
                    Toast.makeText(a, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    return;
                }
                AppBookmark note = new AppBookmark(bookPath, noteText, percent);
                note.isAiNote = true;
                // absolute anchor for the fast-open window: percent alone is
                // inflated against the partial page count (pgs = PGS_WINDOW
                // marks a window capture; the phase-two finisher repairs it)
                note.pg = Math.max(0, dc.getCurentPageFirst1() - 1);
                note.pgs = com.foobnix.sys.TempHolder.layoutComplete
                        ? dc.getPageCount() : AppBookmark.PGS_WINDOW;
                BookmarksData.get().add(note);
                Toast.makeText(a, R.string.note_saved, Toast.LENGTH_SHORT).show();
                Keyboards.close(a);
                dialog.dismiss();
            }
        });
    }
}
