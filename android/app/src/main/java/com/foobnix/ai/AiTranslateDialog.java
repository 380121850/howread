package com.foobnix.ai;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.ui2.AppDB;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The AI translation entry point (replaces the old "replace text" button).
 * Lets the user pick the source language (auto-detected by default, editable)
 * and the target language (en / zh-CN / ja), then either
 * <ul>
 *   <li>in-page bilingual mode (default): translations are laid out inside the
 *       book page under their source paragraph (BilingualSession), or</li>
 *   <li>the legacy list panel (TranslatePanel).</li>
 * </ul>
 * When the in-page bilingual mode is already on for this book, an extra button
 * turns it off again.
 */
public class AiTranslateDialog {

    // spinner position -> BCP-47 code (kept in sync with the array resource)
    private static final String[] CODES = {LanguageDetector.EN, LanguageDetector.ZH, LanguageDetector.JA};

    /** In-page bilingual needs the epub rewrite chain; mobi/azw open natively. */
    public static boolean isBilingualFormat(String path) {
        if (!AiTranslator.isSupportedFormat(path)) {
            return false;
        }
        String p = path.toLowerCase(Locale.US);
        return !p.endsWith(".mobi") && !p.contains(".azw")
                && !p.endsWith(".pdb") && !p.endsWith(".prc");
    }

    public static void show(final Activity a, final DocumentController dc) {
        if (a == null || dc == null) {
            return;
        }
        // PRO feature gate: AI translation (incl. in-page bilingual mode) is
        // locked in fdroid and in pro builds without the IAP unlock
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }
        final File book = dc.getCurrentBook();
        if (!AiTranslator.isSupportedFormat(book == null ? null : book.getPath())) {
            Toast.makeText(a, R.string.ai_translate_unsupported_format, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TxtUtils.isEmpty(AppState.get().aiBaseUrl) || TxtUtils.isEmpty(AppState.get().aiModel)
                || TxtUtils.isEmpty(AiCredentials.load(a))) {
            Toast.makeText(a, R.string.ai_translate_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        final View view = LayoutInflater.from(a).inflate(R.layout.ai_translate_dialog, null, false);
        final Spinner srcSpinner = (Spinner) view.findViewById(R.id.aiTranslateSrc);
        final Spinner tgtSpinner = (Spinner) view.findViewById(R.id.aiTranslateTgt);
        final TextView start = (TextView) view.findViewById(R.id.aiTranslateStart);
        final TextView cancel = (TextView) view.findViewById(R.id.aiTranslateCancel);
        final CheckBox modeBox = (CheckBox) view.findViewById(R.id.aiTranslateMode);
        final CheckBox saveBox = (CheckBox) view.findViewById(R.id.aiTranslateSave);
        final TextView offView = (TextView) view.findViewById(R.id.aiTranslateOff);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(a,
                R.array.ai_translate_langs, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        srcSpinner.setAdapter(adapter);
        tgtSpinner.setAdapter(adapter);
        // restore the last used configuration; fall back to the previous
        // defaults (source auto-detect below, target Chinese) when nothing
        // was saved yet
        final String savedSrc = AppState.get().aiBilingualSrc;
        final String savedTgt = AppState.get().aiBilingualTgt;
        final boolean hasSavedSrc = isValidCode(savedSrc);
        final boolean hasSavedTgt = isValidCode(savedTgt);
        if (hasSavedTgt) {
            tgtSpinner.setSelection(indexOf(savedTgt));
        } else {
            // default target: Chinese (the common case for this app's users)
            tgtSpinner.setSelection(1);
        }

        // Use AlertDialog (like AiConfigDialog / WebDavSyncDialog) rather than a
        // bare Dialog: a bare Dialog sizes to wrap_content, which is only as wide
        // as the spinners, so the two 96dp-min buttons (取消 + 开始翻译) overflow
        // and the 开始翻译 button gets clipped off the edge — leaving the user
        // with no way to start. AlertDialog sizes the window to a proper width.
        final Dialog dialog = new android.app.AlertDialog.Builder(a)
                .setView(view)
                .create();
        dialog.show();

        final boolean isBilingualActive = AppState.get().aiBilingual
                && TxtUtils.isNotEmpty(AppState.get().aiBilingualBook)
                && AppState.get().aiBilingualBook.equals(book.getPath());
        final boolean bilingualPossible = isBilingualFormat(book.getPath());
        if (modeBox != null) {
            if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
                // PRO feature: in-page bilingual is locked — dim the checkbox
                modeBox.setChecked(false);
                modeBox.setEnabled(false);
                modeBox.setAlpha(0.3f);
            } else if (!bilingualPossible) {
                // only the list panel works for this format
                modeBox.setChecked(false);
                modeBox.setVisibility(View.GONE);
            } else if (isBilingualActive) {
                // mode is ON for this book: show it truthfully and lock the
                // dialog down to "cancel" or the red "关闭页内双语模式" button
                modeBox.setChecked(true);
                modeBox.setEnabled(false);
                start.setEnabled(false);
                start.setAlpha(0.4f);
            } else {
                // bilingual is opt-in: the checkbox is never pre-selected,
                // the user turns it on explicitly each time
                modeBox.setChecked(false);
            }
        }
        if (offView != null) {
            offView.setVisibility(isBilingualActive ? View.VISIBLE : View.GONE);
            offView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    BilingualSession.stop(book.getPath());
                    AppState.get().aiBilingual = false;
                    AppState.get().aiBilingualBook = "";
                    AppProfile.save(a);
                    dc.restartActivity();
                }
            });
        }
        if (saveBox != null) {
            if (!bilingualPossible) {
                // saving results belongs to the bilingual flow; the legacy list
                // panel keeps whatever value was last set
                saveBox.setVisibility(View.GONE);
            } else {
                saveBox.setChecked(AppState.get().aiSaveTranslation);
                // persist immediately: "start" may be disabled (bilingual
                // already active) and the list-panel path reads the value too
                saveBox.setOnCheckedChangeListener(
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                            @Override public void onCheckedChanged(
                                    android.widget.CompoundButton buttonView, boolean isChecked) {
                                AppState.get().aiSaveTranslation = isChecked;
                                AppProfile.save(a);
                            }
                        });
            }
        }

        // detect the source language in the background (metadata, then sampling);
        // skipped when the user already has a saved source language
        if (!hasSavedSrc) {
            new Thread(new Runnable() {
                @Override public void run() {
                    String detected = LanguageDetector.EN;
                    try {
                        FileMeta meta = AppDB.get().getOrCreate(book.getPath());
                        List<String> samples = samplePages(dc);
                        detected = LanguageDetector.detect(meta, samples);
                    } catch (Throwable t) {
                        // keep the default
                    }
                    final int idx = indexOf(detected);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (dialog.isShowing()) {
                                srcSpinner.setSelection(idx);
                            }
                        }
                    });
                }
            }, "AiLangDetect").start();
        } else {
            srcSpinner.setSelection(indexOf(savedSrc));
        }

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });

        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String src = CODES[srcSpinner.getSelectedItemPosition()];
                String tgt = CODES[tgtSpinner.getSelectedItemPosition()];
                if (src.equals(tgt)) {
                    Toast.makeText(a, R.string.ai_translate_same_lang, Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean bilingual = modeBox != null && bilingualPossible && modeBox.isChecked();
                // remember the language pair so the dialog re-opens with it
                AppState.get().aiBilingualSrc = src;
                AppState.get().aiBilingualTgt = tgt;
                dialog.dismiss();
                if (bilingual) {
                    startBilingual(a, dc, src, tgt);
                } else {
                    AppProfile.save(a);
                    startTranslation(a, dc, src, tgt);
                }
            }
        });
    }

    private static void startBilingual(final Activity a, final DocumentController dc,
            final String src, final String tgt) {
        // PRO feature gate (in-page bilingual): defense in depth — the
        // checkbox is already disabled for locked builds
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }
        if (dc.getCurrentBook() == null) {
            return;
        }
        final String path = dc.getCurrentBook().getPath();
        BilingualSession.stop(path);
        AppState st = AppState.get();
        st.aiBilingual = true;
        st.aiBilingualBook = path;
        st.aiBilingualSrc = src;
        st.aiBilingualTgt = tgt;
        AppProfile.save(a);
        android.util.Log.i("BENCH", "AiTranslateDialog enable bilingual book=" + path
                + " src=" + src + " tgt=" + tgt);
        // programmatic restart: keep the mode on across the activity re-create
        BilingualSession.suppressExitOnDestroy = true;
        // re-open the book: the context chain now opens (or builds) the
        // bilingual edition and the activity attaches a BilingualSession
        dc.restartActivity();
    }

    private static int indexOf(String code) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isValidCode(String code) {
        for (String c : CODES) {
            if (c.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** First few pages' text, for the language-detection fallback. */
    private static List<String> samplePages(DocumentController dc) {
        List<String> out = new ArrayList<String>();
        int n = Math.min(3, dc.getPageCount());
        for (int i = 1; i <= n; i++) {
            String[] paras = dc.getPageParagraphs(i - 1);
            if (paras != null) {
                for (String p : paras) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static void startTranslation(final Activity a, final DocumentController dc,
            final String src, final String tgt) {
        final TranslatePanel panel = new TranslatePanel(a);
        panel.setTitle(a.getString(R.string.ai_translate) + " → "
                + AiTranslator.targetLangName(tgt));
        panel.setTranslating(true);
        AiTranslator.translate(a, dc, src, tgt, new AiTranslator.Listener() {
            @Override public void onParagraph(String pid, String orig, String tran, String status) {
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        panel.addParagraph(orig, tran, status);
                    }
                });
            }

            @Override public void onFinished(boolean ok) {
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        panel.setTranslating(false);
                    }
                });
            }
        });
    }
}
