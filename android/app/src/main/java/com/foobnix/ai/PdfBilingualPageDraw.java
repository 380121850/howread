package com.foobnix.ai;

import com.foobnix.android.utils.LOG;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.ColorUtils;

import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.MagicHelper;

import org.ebookdroid.core.Page;
import org.ebookdroid.core.ViewState;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 双语"画进页面"渲染层（模式 2 原位替换 / 模式 3 段落双语）。
 * 由 EventDraw 在每页位图绘制完成后用同一画布调用 drawPage——译文与页面共用
 * 同一坐标系与缩放，随页面滚动、缩放、翻页天然一体，不是页面上叠加的独立浮层。
 * 每个已译段落：以页面底色抹平原 bbox，再在其原位排版译文
 * （模式 3 = 原文小字在上 + 译文在下；模式 2 = 仅译文）。未译段保持原文可见；
 * 流式 partial 到一段画一段。字号随 bbox 宽度自适应（随缩放等比），空间不足时
 * 逐级缩小至下限、仍放不下则在 bbox 底部截断（全文可回对照面板查看）。
 */
public final class PdfBilingualPageDraw {

    private static volatile boolean active;
    private static volatile int kind;
    private static WeakReference<TranslateSession> sessionRef =
            new WeakReference<TranslateSession>(null);
    private static WeakReference<Activity> activityRef = new WeakReference<Activity>(null);
    private static WeakReference<View> pageViewRef = new WeakReference<View>(null);

    /** StaticLayout 帧间缓存：滚动/流式重绘时免重复排版；宽度或字号变化自动失效。 */
    private static final Map<String, StaticLayout> LAYOUTS = new HashMap<String, StaticLayout>();

    private PdfBilingualPageDraw() {
    }

    /** 双语会话启动（模式 2/3）：记录会话与目标页面视图，页面开始随流重绘。 */
    public static synchronized void activate(TranslateSession s, int k, Activity a) {
        sessionRef = new WeakReference<TranslateSession>(s);
        activityRef = new WeakReference<Activity>(a);
        pageViewRef = new WeakReference<View>(findPageView(a));
        kind = k;
        LAYOUTS.clear();
        active = true;
        notifyChanged();
    }

    /** 退出双语：清引用并让页面回到纯原文。 */
    public static synchronized void deactivate() {
        active = false;
        sessionRef.clear();
        LAYOUTS.clear();
        notifyChanged();
    }

    /** 译文数据更新后让页面重绘（会话流式回调里调）。 */
    public static void notifyChanged() {
        View v = pageViewRef.get();
        if (v != null) {
            v.postInvalidate();
        }
    }

    private static View findPageView(Activity a) {
        if (a == null) {
            return null;
        }
        View dv = a.findViewById(R.id.documentView);
        if (dv instanceof ViewGroup && ((ViewGroup) dv).getChildCount() > 0) {
            return ((ViewGroup) dv).getChildAt(0); // PdfSurfaceView（纵向连续渲染画布）
        }
        return dv;
    }

    /**
     * EventDraw 每页收尾调用：把该页已译段落画进页面本体。
     * pageBounds 为页面文档坐标（含缩放），viewBase 为视口原点——
     * 段落 bbox（页宽/高分数）经 Page.getPageRegion 映射（自动处理裁边/双页拆分）。
     */
    public static void drawPage(Canvas canvas, Page page, RectF pageBounds, ViewState viewState) {
        if (!active) {
            return;
        }
        final TranslateSession session = sessionRef.get();
        final Activity act = activityRef.get();
        // 仅在激活它的那个阅读会话里绘制（换书/重开阅读器后旧会话自动失效）
        if (session == null || act == null || act.isFinishing() || viewState == null
                || viewState.ctrl == null || viewState.ctrl.getBase() == null
                || viewState.ctrl.getBase().getActivity() != act) {
            return;
        }
        final List<TranslateSession.Slot> slots;
        try {
            slots = session.snapshot(page.index.docIndex + 1); // Slot.page 为 1-based 阅读页
        } catch (Throwable t) {
            LOG.benchW("PBDraw snapshot fail idx=" + page.index.docIndex, t);
            return;
        }
        if (slots == null || slots.isEmpty()) {
            return;
        }
        final int k = kind;
        final int bg = MagicHelper.getBgColor();
        final int textColor = MagicHelper.getTextColor();
        final RectF frac = new RectF();
        final RectF r = new RectF();
        for (TranslateSession.Slot s : slots) {
            if (s.rect == null || s.rect.length < 4) {
                continue; // 未定位到原段的（如扫描页）不动页面
            }
            String text = s.tran != null ? s.tran : s.partial;
            if (text == null || text.trim().length() == 0) {
                continue; // 未译段保持原文可见（渐进对照）
            }
            frac.set(s.rect[0], s.rect[1], s.rect[2], s.rect[3]);
            RectF region;
            try {
                region = page.getPageRegion(pageBounds, frac);
            } catch (Throwable t) {
                continue;
            }
            if (region == null) {
                continue; // 双页拆分模式下段落属于另一半页
            }
            region.offset(-viewState.viewBase.x, -viewState.viewBase.y);
            r.set(region);
            if (r.width() < 24 || r.height() < 16) {
                continue;
            }
            // 屏外裁剪：不用 Canvas.quickReject（部分 EMUI 设备缺该重载，会 NoSuchMethodError），
            // 用 clip bounds 手动判交（getClipBounds 为 API 1 接口）
            android.graphics.Rect cb = canvas.getClipBounds();
            if (cb == null || r.right <= cb.left || r.left >= cb.right
                    || r.bottom <= cb.top || r.top >= cb.bottom) {
                continue;
            }
            // 以页面底色抹平原段，再在原位排版译文
            Paint fill = viewState.paint.fillPaint;
            fill.setColor(bg);
            canvas.drawRect(r, fill);
            if (k == PdfBilingualOverlay.KIND_REPLACE) {
                drawFitted(canvas, r, null, text, textColor);
            } else {
                drawFitted(canvas, r, s.orig, text, textColor);
            }
        }
    }

    /** bbox 内排版：译文基准约 24 字/行（随缩放等比），原文小一号置上；放不下逐级缩小。 */
    private static void drawFitted(Canvas canvas, RectF r, String orig, String tran, int textColor) {
        float w = r.width();
        float h = r.height();
        float pad = Math.max(2f, w * 0.01f);
        float barW = Math.max(2f, w * 0.007f);
        float innerW = w - pad * 2 - barW;
        float innerH = h - pad * 2;
        if (innerW < 40 || innerH < 10) {
            return;
        }
        int dimColor = ColorUtils.setAlphaComponent(textColor, 150);
        int accentColor = ColorUtils.setAlphaComponent(0xFF3D8BFF, 220);

        float base = w / 24f;
        float scale = 1f;
        StaticLayout tranL = null;
        StaticLayout origL = null;
        float f = base;
        while (true) {
            f = Math.max(base * scale, 6f);
            tranL = layout(tran, textColor, f, innerW);
            origL = orig == null ? null : layout(orig, dimColor, Math.max(f * 0.72f, 5f), innerW);
            float total = tranL.getHeight()
                    + (origL != null ? origL.getHeight() + Math.max(2f, f * 0.2f) : 0);
            if (total <= innerH || f <= 6f || scale <= 0.45f) {
                break;
            }
            scale *= 0.88f;
        }

        float textH = tranL.getHeight()
                + (origL != null ? origL.getHeight() + Math.max(2f, f * 0.2f) : 0);
        float barBottom = Math.min(r.bottom, r.top + textH + pad * 2);
        canvas.save();
        try {
            canvas.clipRect(r.left, r.top, r.right, r.bottom);
            // 段左标识条：只随实际文字高度（bbox 可能远高于文字，如页脚并入段落的书）
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(accentColor);
            canvas.drawRect(r.left, r.top, r.left + barW, barBottom, p);
            float x = r.left + barW + pad;
            float y = r.top + pad;
            if (origL != null) {
                canvas.save();
                canvas.translate(x, y);
                origL.draw(canvas);
                canvas.restore();
                y += origL.getHeight() + Math.max(2f, f * 0.2f);
            }
            canvas.save();
            canvas.translate(x, y);
            tranL.draw(canvas);
            canvas.restore();
        } finally {
            canvas.restore();
        }
    }

    private static StaticLayout layout(String text, int color, float size, float width) {
        String t = text == null ? "" : text.trim();
        if (t.length() > 4000) {
            t = t.substring(0, 4000) + "…";
        }
        String key = (int) size + "|" + (int) width + "|" + color + "|" + t.length()
                + "|" + t.hashCode();
        synchronized (LAYOUTS) {
            StaticLayout hit = LAYOUTS.get(key);
            if (hit != null) {
                return hit;
            }
            TextPaint tp = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            tp.setColor(color);
            tp.setTextSize(size);
            StaticLayout l = new StaticLayout(t, tp, Math.max(60, (int) width),
                    Layout.Alignment.ALIGN_NORMAL, 1.18f, 0f, false);
            if (LAYOUTS.size() > 96) {
                LAYOUTS.clear();
            }
            LAYOUTS.put(key, l);
            return l;
        }
    }
}
