package com.foobnix.ai;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.foobnix.pdf.info.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bottom-sheet overlay showing the AI translation of the page the user is
 * reading (the title carries the page number), one PAIR per paragraph: a dim
 * line with the original's first line followed by the translation, so every
 * translation is visibly paired with its source. Later pages keep
 * translating in the background (status line at the bottom) and the panel
 * follows the reader to the new page. Cards: finished paragraphs render
 * instantly, in-flight ones grow live, queued ones show a placeholder.
 * Right side in landscape, bottom in portrait.
 */
public class TranslatePanel {

    private final Activity a;
    private final ViewGroup host;
    private final View panel;
    private final LinearLayout blocks;
    private final ScrollView scroll;
    private final TextView progress;
    private final TextView count;
    private final TextView title;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TranslateSession session;
    private String baseTitle = "";
    private int shownPage = -1;
    /** slot -> translation text view of the displayed page */
    private final Map<TranslateSession.Slot, TextView> slotViews =
            new HashMap<TranslateSession.Slot, TextView>();

    private final Runnable renderRunnable = new Runnable() {
        @Override public void run() {
            render();
        }
    };

    private final Runnable toBottom = new Runnable() {
        @Override public void run() {
            scroll.fullScroll(ScrollView.FOCUS_DOWN);
        }
    };

    public TranslatePanel(Activity a) {
        this.a = a;
        this.host = (ViewGroup) a.getWindow().getDecorView();
        this.panel = LayoutInflater.from(a).inflate(R.layout.ai_translate_panel, host, false);
        this.blocks = (LinearLayout) panel.findViewById(R.id.aiTranslateBlocks);
        this.scroll = (ScrollView) panel.findViewById(R.id.aiTranslateScroll);
        this.progress = (TextView) panel.findViewById(R.id.aiTranslateProgress);
        this.count = (TextView) panel.findViewById(R.id.aiTranslateCount);
        this.title = (TextView) panel.findViewById(R.id.aiTranslateTitle);

        ImageView close = (ImageView) panel.findViewById(R.id.aiTranslateClose);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
            }
        });

        boolean landscape = a.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int h = a.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp;
        if (landscape) {
            lp = new FrameLayout.LayoutParams((int) (w * 0.55), FrameLayout.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.END;
        } else {
            lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (int) (h * 0.55));
            lp.gravity = Gravity.BOTTOM;
        }
        panel.setLayoutParams(lp);
        host.addView(panel);
    }

    /** Attach to the session driving this panel. */
    public void bind(TranslateSession s) {
        this.session = s;
    }

    public void setTitle(String s) {
        this.baseTitle = s == null ? "" : s;
        updateTitle();
    }

    /** Session state changed (page switch / slot finished / queue moved):
     *  re-render, coalescing bursts. Worker threads ok. */
    public void onSessionChanged() {
        ui.removeCallbacks(renderRunnable);
        ui.postDelayed(renderRunnable, 80);
    }

    /** Live partial text of a running slot. Worker threads ok. */
    public void onSlotPartial(final TranslateSession.Slot slot) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (session == null || slot.page != shownPage) {
                    return;
                }
                TextView tv = slotViews.get(slot);
                if (tv != null && slot.partial != null) {
                    tv.setText(slot.partial);
                    scroll.post(toBottom);
                }
            }
        });
    }

    private void updateTitle() {
        title.setText(shownPage > 0 ? baseTitle + " · 第 " + shownPage + " 页" : baseTitle);
    }

    /** First line of the original paragraph, for the pair header. */
    private static String firstLine(String orig) {
        if (orig == null) {
            return "";
        }
        String t = orig.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == '。' || c == '！' || c == '？') {
                if (i + 1 < t.length()) {
                    return t.substring(0, i + 1);
                }
            }
        }
        return t;
    }

    private void render() {
        TranslateSession s = session;
        if (s == null) {
            return;
        }
        int page = s.getDisplayedPage();
        boolean pageSwitch = page != shownPage;
        shownPage = page;
        updateTitle();

        final int keepY = pageSwitch ? -1 : scroll.getScrollY();
        blocks.removeAllViews();
        slotViews.clear();

        List<TranslateSession.Slot> slots = s.snapshot(page);
        for (final TranslateSession.Slot slot : slots) {
            View pair = LayoutInflater.from(a).inflate(R.layout.ai_translate_pair, blocks, false);
            TextView orig = (TextView) pair.findViewById(R.id.aiTranslatePairOrig);
            TextView tv = (TextView) pair.findViewById(R.id.aiTranslateBlockText);
            orig.setText("▎" + firstLine(slot.orig));
            switch (slot.state()) {
                case 2:
                    tv.setText(slot.tran);
                    break;
                case 3:
                    tv.setText(a.getString(R.string.ai_translate_failed));
                    tv.setTextColor(Color.RED);
                    break;
                case 1:
                    tv.setText(slot.partial == null ? "…" : slot.partial);
                    slotViews.put(slot, tv);
                    break;
                default:
                    tv.setText("…");
                    slotViews.put(slot, tv);
                    break;
            }
            blocks.addView(pair);
        }
        if (slots.isEmpty()) {
            TextView tv = (TextView) LayoutInflater.from(a)
                    .inflate(R.layout.ai_translate_block, blocks, false);
            tv.setText("本页没有可翻译的文本");
            tv.setAlpha(0.4f);
            blocks.addView(tv);
        }
        count.setText(String.valueOf(slots.size()));

        int bg = s.backgroundPending();
        if (bg > 0) {
            progress.setText("后台翻译中 " + bg + " 段…");
            progress.setVisibility(View.VISIBLE);
        } else {
            progress.setVisibility(View.GONE);
        }

        if (keepY >= 0) {
            scroll.post(new Runnable() {
                @Override public void run() {
                    scroll.scrollTo(0, keepY);
                }
            });
        } else {
            scroll.post(new Runnable() {
                @Override public void run() {
                    scroll.fullScroll(ScrollView.FOCUS_UP);
                }
            });
        }
    }

    public void dismiss() {
        if (session != null) {
            session.cancel();
            session = null;
        }
        ui.removeCallbacks(renderRunnable);
        try {
            host.removeView(panel);
        } catch (Exception ignored) {
        }
        slotViews.clear();
    }
}
