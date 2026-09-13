package com.foobnix.ai;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
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

/**
 * Scrollable overlay that shows the AI translation, paragraph by paragraph,
 * while the original page stays visible. Right side in landscape, bottom in
 * portrait. Uses dp spacing, wrap_content heights and a light rounded block
 * background — no fixed pixel heights, no JS.
 */
public class TranslatePanel {

    private final Activity a;
    private final ViewGroup host;
    private final View panel;
    private final LinearLayout blocks;
    private final ScrollView scroll;
    private final TextView progress;
    private final TextView count;

    public TranslatePanel(Activity a) {
        this.a = a;
        this.host = (ViewGroup) a.getWindow().getDecorView();
        this.panel = LayoutInflater.from(a).inflate(R.layout.ai_translate_panel, host, false);
        this.blocks = (LinearLayout) panel.findViewById(R.id.aiTranslateBlocks);
        this.scroll = (ScrollView) panel.findViewById(R.id.aiTranslateScroll);
        this.progress = (TextView) panel.findViewById(R.id.aiTranslateProgress);
        this.count = (TextView) panel.findViewById(R.id.aiTranslateCount);

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

    /** Add one translated paragraph block and keep the newest visible. */
    public void addParagraph(String orig, String tran, String status) {
        View block = LayoutInflater.from(a).inflate(R.layout.ai_translate_block, blocks, false);
        TextView tv = (TextView) block.findViewById(R.id.aiTranslateBlockText);
        if ("failed".equals(status)) {
            tv.setText(a.getString(R.string.ai_translate_failed));
            tv.setTextColor(Color.RED);
        } else {
            tv.setText(tran);
        }
        blocks.addView(block);
        count.setText(String.valueOf(blocks.getChildCount()));
        scroll.post(new Runnable() {
            @Override public void run() {
                scroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    public void setTranslating(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    public void setTitle(String s) {
        TextView t = (TextView) panel.findViewById(R.id.aiTranslateTitle);
        if (t != null) {
            t.setText(s);
        }
    }

    public void dismiss() {
        // removing the view is not enough: stop the background translation
        // thread so it stops spending API quota for a dead panel
        AiTranslator.cancel();
        try {
            host.removeView(panel);
        } catch (Exception ignored) {
        }
    }
}
