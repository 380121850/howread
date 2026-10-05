package com.foobnix.ai;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * PDF 页面内双语对照浮层（三种渲染形态，同一容器，均在原 PDF 版面上、按原文字段
 * 自身位置显示，分栏页面天然安全——段落 bbox 来自其自身文字层字符）：
 * kind 1 原位浮层——每个已译段落在其纵向位置显示编号角标，点角标原位展开译文卡片；
 * kind 2 原位替换 / kind 3 段落双语——译文由 PdfBilingualPageDraw 画进页面渲染
 * 本体（EventDraw 同画布同坐标，随页面滚动缩放一体），本浮层不再叠加内容，
 * 仅保留右上角 ✕ 退出芯片。
 * 未译段落保持原文可见，译文到达后渐进变为对照/替换形态。点 ✕ 退出该模式。
 */
public class PdfBilingualOverlay extends View implements TranslateSession.Listener {
    public static final int KIND_OVERLAY = 1;
    public static final int KIND_REPLACE = 2;
    public static final int KIND_BILINGUAL = 3;

    private static PdfBilingualOverlay CURRENT;

    private final TranslateSession session;
    private final int kind;
    private final String tgtName;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    // 命中区（onDraw 时重建，onTouch 复用）
    private final List<RectF> badgeRects = new ArrayList<RectF>();
    private final List<Integer> badgeIndexes = new ArrayList<Integer>();
    private final RectF closeRect = new RectF();
    private int expanded = -1;

    private PdfBilingualOverlay(Activity a, TranslateSession s, int kind, String tgt) {
        super(a);
        this.session = s;
        this.kind = kind;
        this.tgtName = tgt;
    }

    /**
     * 挂到活动内容顶层；替换掉既有的浮层实例。
     *
     * @return false 表示当前活动挂不上（非竖屏阅读器：没有 documentView
     * 页面视图，画不出内容也点不到退出芯片）——调用方应退回列表面板模式
     */
    public static synchronized boolean show(Activity a, TranslateSession s, int kind, String tgtName) {
        dismissCurrent();
        if (a == null || a.isFinishing() || s == null) {
            return false;
        }
        ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        if (root == null) {
            return false;
        }
        // 页面定位依赖竖屏阅读器的 documentView；找不到就不挂（横屏布局
        // 没有该视图，onDraw 会整帧空转，连退出芯片都画不出）
        if (a.findViewById(R.id.documentView) == null) {
            return false;
        }
        PdfBilingualOverlay o = new PdfBilingualOverlay(a, s, kind, tgtName);
        root.addView(o, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        CURRENT = o;
        s.setListener(o);
        PdfBilingualPageDraw.activate(s, kind, a);
        return true;
    }

    public static synchronized void dismissCurrent() {
        if (CURRENT != null) {
            CURRENT.dismissSelf();
        }
    }

    private void dismissSelf() {
        PdfBilingualPageDraw.deactivate();
        if (session != null) {
            session.cancel();
        }
        ui.removeCallbacksAndMessages(null);
        ViewGroup p = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
        if (p != null) {
            p.removeView(this);
        }
        if (CURRENT == this) {
            CURRENT = null;
        }
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** 页面视图：纵向阅读器 documentView 的第一个子视图（PdfSurfaceView）。 */
    private View pageView() {
        View dv = getRootView().findViewById(R.id.documentView);
        if (dv instanceof ViewGroup && ((ViewGroup) dv).getChildCount() > 0) {
            return ((ViewGroup) dv).getChildAt(0);
        }
        return dv;
    }

    private boolean night() {
        return !AppState.get().isDayNotInvert;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        badgeRects.clear();
        badgeIndexes.clear();
        closeRect.setEmpty();
        try {
            View page = pageView();
            if (page == null || page.getWidth() <= 0 || page.getHeight() <= 0 || session == null) {
                return;
            }
            int[] pl = new int[2];
            int[] rl = new int[2];
            page.getLocationOnScreen(pl);
            getLocationOnScreen(rl);
            float pageLeft = pl[0] - rl[0];
            float pageTop = pl[1] - rl[1];
            float pageW = page.getWidth();
            float pageH = page.getHeight();
            if (pageTop > getHeight() || pageTop + pageH < 0) {
                return;
            }

            // ✕ 退出按钮（页面右上角）
            float chipR = dp(14);
            float chipCx = pageLeft + pageW - chipR - dp(8);
            float chipCy = pageTop + chipR + dp(8);
            closeRect.set(chipCx - chipR, chipCy - chipR, chipCx + chipR, chipCy + chipR);
            paint.setColor(0x66000000);
            canvas.drawCircle(chipCx, chipCy, chipR, paint);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(sp(13));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("✕", chipCx, chipCy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);

            // 模式 2/3：内容由 PdfBilingualPageDraw 画进页面本体，这里只画 ✕ 芯片
            List<TranslateSession.Slot> slots = kind == KIND_OVERLAY
                    ? alignedSlots(session.getDisplayedPage())
                    : new ArrayList<TranslateSession.Slot>();
            boolean inv = night();
            int maskColor = inv ? 0xF2121212 : 0xFFFFFFFF;
            int textColor = inv ? 0xFFE0E0E0 : 0xFF222222;
            int dimColor = inv ? 0xFF9A9A9A : 0xFF8A8A8A;
            int accent = inv ? 0xFF8AB4F8 : 0xFF3D8BFF;

            for (TranslateSession.Slot s : slots) {
                float bandTop = pageTop + s.topY * pageH;
                if (kind == KIND_OVERLAY) {
                    drawOverlayBadge(canvas, s, pageLeft, pageTop, pageW, pageH, accent, textColor);
                    continue;
                }
                // 段落 bbox：优先精确包围盒，无则用 topY 全宽条带回退
                float left;
                float top;
                float right;
                float bottom;
                if (s.rect != null && s.rect.length >= 4) {
                    left = pageLeft + s.rect[0] * pageW - dp(4);
                    top = pageTop + s.rect[1] * pageH - dp(3);
                    right = pageLeft + s.rect[2] * pageW + dp(4);
                    bottom = pageTop + s.rect[3] * pageH + dp(3);
                } else {
                    left = pageLeft + dp(8);
                    top = bandTop;
                    right = pageLeft + pageW - dp(8);
                    bottom = pageTop + pageH - dp(8);
                }
                if (kind == KIND_BILINGUAL) {
                    drawBilingualBlock(canvas, s, left, top, right, bottom, maskColor, textColor, dimColor, accent);
                } else {
                    drawReplaceBlock(canvas, s, left, top, right, bottom, maskColor, textColor, accent);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 模式一：角标 + 点击展开的译文卡片。 */
    private void drawOverlayBadge(Canvas canvas, TranslateSession.Slot s, float pageLeft,
            float pageTop, float pageW, float pageH, int accent, int textColor) {
        float cy = pageTop + s.topY * pageH + dp(2);
        float r = dp(9);
        float cx = pageLeft + dp(16) + r;
        RectF badge = new RectF(cx - r, cy - r, cx + r, cy + r);
        boolean active = s.hashCode() == expanded;
        paint.setColor(active ? accent : 0x663D8BFF);
        canvas.drawCircle(cx, cy, r, paint);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(sp(11));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("•", cx, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        badgeRects.add(badge);
        badgeIndexes.add(System.identityHashCode(s));
        // 占位保持原有展开逻辑可用
        if (active && s.tran != null) {
            float cardLeft = pageLeft + dp(34);
            float cardW = pageW - dp(48);
            StaticLayout body = buildLayout(s.tran, textColor, sp(14), (int) cardW, 1.1f);
            StaticLayout headL = buildLayout(headOf(s.orig), 0xFF888888, sp(11), (int) cardW, 1.1f);
            float pad = dp(8);
            float cardH = headL.getHeight() + body.getHeight() + pad * 2 + dp(4);
            RectF card = new RectF(cardLeft, cy + r + dp(3), cardLeft + cardW, cy + r + dp(3) + cardH);
            paint.setColor(0xF7FFFFFF);
            canvas.drawRoundRect(card, dp(6), dp(6), paint);
            paint.setColor(0xFF3D8BFF);
            paint.setStrokeWidth(dp(1));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(card, dp(6), dp(6), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.save();
            canvas.translate(cardLeft + pad, card.top + pad);
            headL.draw(canvas);
            canvas.translate(0, headL.getHeight() + dp(4));
            body.draw(canvas);
            canvas.restore();
        }
    }

    private static String headOf(String orig) {
        String t = orig == null ? "" : orig.trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /** 模式三：段落 bbox 内 原文（小字灰，上）/ 译文（大字深，下）上下对照。 */
    private void drawBilingualBlock(Canvas canvas, TranslateSession.Slot s,
            float left, float top, float right, float bottom,
            int maskColor, int textColor, int dimColor, int accent) {
        if (s.tran == null) {
            return; // 未译段保持原文可见（渐进双语）
        }
        float blockW = right - left - dp(8);
        float blockH = bottom - top - dp(6);
        if (blockW < dp(60) || blockH < dp(20)) {
            return;
        }
        float leftX = left + dp(4);
        float topY = top + dp(3);
        // 遮罩 + 段左标识
        paint.setColor(maskColor);
        canvas.drawRect(left, top, right, Math.max(bottom, top + dp(20)), paint);
        paint.setColor(accent);
        canvas.drawRect(left, top, left + dp(3), Math.max(bottom, top + dp(20)), paint);
        // 字号自适应：原文/译文成对缩放直到放进 bbox（下限 0.45）
        float[] scales = new float[]{1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.45f};
        StaticLayout origL = null;
        StaticLayout tranL = null;
        float total = 0;
        float usedScale = scales[scales.length - 1];
        for (float sc : scales) {
            origL = buildLayout(cap(s.orig), dimColor, sp(10) * sc, (int) blockW, 1.15f);
            tranL = buildLayout(s.tran, textColor, sp(13) * sc, (int) blockW, 1.2f);
            total = origL.getHeight() + dp(2) + tranL.getHeight() + dp(4);
            usedScale = sc;
            if (total <= blockH) {
                break;
            }
        }
        canvas.save();
        canvas.clipRect(left, top, right, Math.max(bottom, top + total + dp(6)));
        float y = topY;
        canvas.save();
        canvas.translate(leftX, y);
        origL.draw(canvas);
        canvas.restore();
        y += origL.getHeight() + dp(2);
        if (tranL != null) {
            canvas.save();
            canvas.translate(leftX, y);
            tranL.draw(canvas);
            canvas.restore();
            y += tranL.getHeight();
        }
        canvas.restore();
    }

    /** 模式四：段落 bbox 内遮罩 + 译文替换（原文被覆盖）。 */
    private void drawReplaceBlock(Canvas canvas, TranslateSession.Slot s,
            float left, float top, float right, float bottom,
            int maskColor, int textColor, int accent) {
        if (s.tran == null) {
            return; // 未译段保持原文可见（渐进替换）
        }
        float blockW = right - left - dp(8);
        float blockH = bottom - top - dp(6);
        if (blockW < dp(60)) {
            return;
        }
        float leftX = left + dp(4);
        float topY = top + dp(3);
        paint.setColor(maskColor);
        canvas.drawRect(left, top, right, Math.max(bottom, top + dp(20)), paint);
        paint.setColor(accent);
        canvas.drawRect(left, top, left + dp(3), Math.max(bottom, top + dp(20)), paint);
        float[] scales = new float[]{1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.45f};
        StaticLayout tranL = null;
        float total = 0;
        for (float sc : scales) {
            tranL = buildLayout(s.tran, textColor, sp(13) * sc, (int) blockW, 1.2f);
            total = tranL.getHeight() + dp(6);
            if (total <= blockH) {
                break;
            }
        }
        canvas.save();
        canvas.clipRect(left, top, right, Math.max(bottom, top + total + dp(6)));
        canvas.translate(leftX, topY);
        tranL.draw(canvas);
        canvas.restore();
    }

    private static String cap(String s, int n) {
        String t = s == null ? "" : s.trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    private String cap(String s) {
        return cap(s, 1200);
    }

    private StaticLayout buildLayout(String text, int color, float size, int width, float spacing) {
        TextPaint tp = new TextPaint(textPaint);
        tp.setColor(color);
        tp.setTextSize(size);
        return new StaticLayout(cap(text, 4000), tp, Math.max(100, width),
                Layout.Alignment.ALIGN_NORMAL, spacing, 0f, false);
    }

    private List<TranslateSession.Slot> alignedSlots(int page) {
        List<TranslateSession.Slot> out = new ArrayList<TranslateSession.Slot>();
        if (session == null || page <= 0) {
            return out;
        }
        List<TranslateSession.Slot> slots = session.snapshot(page);
        for (TranslateSession.Slot s : slots) {
            if (s.topY >= 0) {
                out.add(s);
            }
        }
        Collections.sort(out, new Comparator<TranslateSession.Slot>() {
            @Override public int compare(TranslateSession.Slot a, TranslateSession.Slot b) {
                return Float.compare(a.topY, b.topY);
            }
        });
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN) {
            return false;
        }
        float x = ev.getX();
        float y = ev.getY();
        if (closeRect.contains(x, y)) {
            dismissSelf();
            return true;
        }
        if (kind != KIND_OVERLAY) {
            return false; // 段落双语/替换无交互元素
        }
        for (int i = 0; i < badgeRects.size(); i++) {
            RectF r = badgeRects.get(i);
            if (r.contains(x, y)) {
                expanded = expanded == badgeIndexes.get(i) ? -1 : badgeIndexes.get(i);
                invalidate();
                return true;
            }
        }
        return false; // 非命中区放行给页面（缩放/翻页/长按选择不受影响）
    }

    @Override
    public void onSessionChanged() {
        ui.post(new Runnable() {
            @Override public void run() {
                invalidate();
                PdfBilingualPageDraw.notifyChanged();
            }
        });
    }

    @Override
    public void onSlotPartial(final TranslateSession.Slot slot) {
        ui.post(new Runnable() {
            @Override public void run() {
                invalidate();
                PdfBilingualPageDraw.notifyChanged();
            }
        });
    }
}
