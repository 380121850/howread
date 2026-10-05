package com.foobnix.pdf.info.widget;

import android.os.Build;
import android.view.View;
import android.widget.Magnifier;

/**
 * 文字选择手柄拖动时的放大镜（系统 Magnifier，API 28+；低版本静默降级为无放大镜）。
 * contentView 提供被放大的内容（页面绘制视图），showAt 传入当前拖动的手柄。
 */
public class SelectionMagnifier {
    private final View contentView;
    private Magnifier magnifier;

    public SelectionMagnifier(View contentView) {
        this.contentView = contentView;
    }

    public void showAt(float srcX, float srcY) {
        if (Build.VERSION.SDK_INT < 28 || contentView == null) {
            return;
        }
        try {
            if (magnifier == null) {
                magnifier = new Magnifier(contentView);
            }
            magnifier.show(srcX, srcY);
        } catch (Exception e) {
            magnifier = null;
        }
    }

    public void showAt(View handle) {
        if (Build.VERSION.SDK_INT < 28 || contentView == null || handle == null) {
            return;
        }
        try {
            if (magnifier == null) {
                magnifier = new Magnifier(contentView);
            }
            int[] hl = new int[2];
            int[] cl = new int[2];
            handle.getLocationInWindow(hl);
            contentView.getLocationInWindow(cl);
            float srcX = hl[0] - cl[0] + handle.getWidth() / 2f;
            float srcY = hl[1] - cl[1] + handle.getHeight() / 2f;
            magnifier.show(srcX, srcY);
        } catch (Exception e) {
            magnifier = null;
        }
    }

    public void hide() {
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Exception e) {
                // 放大镜属增强体验，异常静默
            }
            magnifier = null;
        }
    }
}
