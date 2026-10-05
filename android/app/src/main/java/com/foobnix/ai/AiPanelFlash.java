package com.foobnix.ai;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 对照翻译面板的"定位"反馈：在页面对应纵向位置闪现一条半透明高亮带。
 * topFrac 为段落首行相对页高的分数（TranslateSession.Slot.topY，-1 未知）。
 */
public final class AiPanelFlash {
    private static final long SHOW_MS = 1400;

    private AiPanelFlash() {
    }

    public static void flashPageBand(final Activity a, float topFrac) {
        if (a == null || a.isFinishing() || topFrac < 0) {
            return;
        }
        try {
            ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
            if (root == null) {
                return;
            }
            // 页面视图：纵向阅读器 documentView 的第一个子视图（PdfSurfaceView）；
            // 其它布局退化为内容根整体
            View page = a.findViewById(com.foobnix.pdf.info.R.id.documentView);
            if (page instanceof ViewGroup && ((ViewGroup) page).getChildCount() > 0) {
                page = ((ViewGroup) page).getChildAt(0);
            }
            int[] pLoc = new int[2];
            int[] rLoc = new int[2];
            int bandW;
            int bandH;
            int left;
            int top;
            if (page != null && page.getWidth() > 0 && page.getHeight() > 0) {
                page.getLocationOnScreen(pLoc);
                root.getLocationOnScreen(rLoc);
                bandW = page.getWidth();
                bandH = Math.max(60, (int) (page.getHeight() * 0.05f));
                left = pLoc[0] - rLoc[0];
                top = pLoc[1] - rLoc[1] + (int) (topFrac * page.getHeight()) - bandH / 2;
            } else {
                root.getLocationOnScreen(rLoc);
                bandW = root.getWidth();
                bandH = Math.max(60, (int) (root.getHeight() * 0.05f));
                left = 0;
                top = (int) (topFrac * root.getHeight()) - bandH / 2;
            }
            top = Math.max(0, Math.min(root.getHeight() - bandH, top));

            final ViewGroup froot = root;
            final View band = new View(a);
            band.setBackgroundColor(0x513D8BFF);
            band.setLayoutParams(new FrameLayout.LayoutParams(bandW, bandH));
            band.setTranslationX(left);
            band.setTranslationY(top);
            froot.addView(band);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        froot.removeView(band);
                    } catch (Throwable ignored) {
                    }
                }
            }, SHOW_MS);
        } catch (Throwable ignored) {
        }
    }
}
