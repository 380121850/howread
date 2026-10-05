package com.foobnix.pdf.info.widget;

import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.view.AnchorHelper;
import com.foobnix.pdf.info.view.DragingPopup;

public class DraggbleTouchListener implements OnTouchListener {
    PointF initLatout = new PointF();
    PointF initPoint = new PointF();
    private int width;
    private int heigh;
    private int sWidth;
    private int sHeigh;
    private View anchor;
    private View root;
    private DragingPopup popup;
    private OnClickListener onClickListener;
    private int rootTopOffset;
    private SelectionMagnifier magnifier;

    public DraggbleTouchListener(View anchor, DragingPopup popup, OnClickListener onClickListener) {
        this.anchor = anchor;
        this.popup = popup;
        this.onClickListener = onClickListener;
        this.root = (View) anchor.getParent();
    }

    public DraggbleTouchListener(View anchor, DragingPopup popup) {
        this.anchor = anchor;
        this.popup = popup;
        this.root = (View) anchor.getParent();
    }

    public DraggbleTouchListener(View anchor, View root) {
        this.anchor = anchor;
        this.root = root;
        anchor.setOnTouchListener(this);
    }

    public DraggbleTouchListener(View anchor, View root, OnClickListener onClickListener) {
        this.anchor = anchor;
        this.root = root;
        this.onClickListener = onClickListener;
        anchor.setOnTouchListener(this);
    }

    long time, time1;

    private Runnable onEventDetected;

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        LOG.d("DraggbleTouchListener", event);
        if (onEventDetected != null) {
            onEventDetected.run();
        }
        if (anchor == null || root == null) {
            LOG.d("anchor or root is null");
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            width = anchor.getWidth();
            heigh = anchor.getHeight();

            sWidth = root.getWidth();
            sHeigh = root.getHeight();
            try {
                int[] rl = new int[2];
                int[] wl = new int[2];
                root.getLocationOnScreen(rl);
                anchor.getRootView().getLocationOnScreen(wl);
                rootTopOffset = rl[1] - wl[1];
            } catch (Exception e) {
                rootTopOffset = 0;
            }
            // 放开 root 及其祖先的裁剪，拖出父容器边界的部分（如拖到屏幕最底）仍可见
            unclipChildChain(root);

            initLatout.x = anchor.getX();
            initLatout.y = anchor.getY();

            initPoint.x = event.getRawX();
            initPoint.y = event.getRawY();

            if (System.currentTimeMillis() - time < 250) {
                if (popup != null) {
                    popup.initState();
                }

            }
            time = System.currentTimeMillis();

        }

        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - initPoint.x;
            float dy = event.getRawY() - initPoint.y;
            float x = initLatout.x + dx;

            if (x < 0) {
                x = 0;
            }
            if (x > sWidth - width) {
                x = sWidth - width;
            }

            float y = initLatout.y + dy;
            if (y < 0) {
                y = 0;
            }
            // 下限取 root 底与窗口底（补偿 root 顶部在窗口内的偏移）的较大者：
            // root 可能止于导航栏上沿，弹出框/手柄至少能拖到屏幕最下面
            float maxY = Math.max(sHeigh, anchor.getRootView().getHeight() - rootTopOffset) - heigh;
            if (popup != null) {
                // 弹窗允许继续下沉、底部越出屏幕下边框，只留顶部标题栏在屏内可抓
                int headerH = popup.getHeaderHeight();
                if (headerH > 0) {
                    maxY = Math.max(maxY, anchor.getRootView().getHeight() - rootTopOffset - headerH);
                }
            }
            if (y > maxY) {
                y = maxY;
            }

            AnchorHelper.setXY(anchor, x, y);

            if (popup != null) {
                if (heigh > Dips.screenHeight() - Dips.DP_25) {
                    popup.getView().getLayoutParams().height = Dips.screenHeight() - Dips.DP_25;
                    popup.getView().requestLayout();
                }

                if (width > Dips.screenWidth() - Dips.DP_25) {
                    popup.getView().getLayoutParams().width = Dips.screenWidth() - Dips.DP_25;
                    popup.getView().requestLayout();
                }
            }

            if (onMove != null) {
                long d = System.currentTimeMillis() - time1;
                if (d > 200) {
                    time1 = System.currentTimeMillis();
                    onMove.run();
                }
            }

            if (magnifier != null) {
                magnifier.showAt(anchor);
            }

        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (magnifier != null) {
                magnifier.hide();
            }
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getRawX() - initPoint.x;
            float dy = event.getRawY() - initPoint.y;
            boolean isMove = (Math.abs(dx) + Math.abs(dy)) < Dips.dpToPx(10);
            if (onClickListener != null && isMove) {
                onClickListener.onClick(anchor);
            }
            if (onMoveFinish != null) {
                onMoveFinish.run();
            }
        }
        return true;
    }

    Runnable onMoveFinish, onMove;

    public void setOnMoveFinish(Runnable onMoveFinish) {
        this.onMoveFinish = onMoveFinish;

    }

    public void setOnMove(Runnable onMove) {
        this.onMove = onMove;

    }

    public void setMagnifier(SelectionMagnifier magnifier) {
        this.magnifier = magnifier;
    }

    // 放开 view 自身及其全部祖先的子视图裁剪（拖动与弹窗显示时都会调用）
    public static void unclipChildChain(View view) {
        try {
            View v = view;
            while (v != null) {
                if (v instanceof ViewGroup) {
                    ((ViewGroup) v).setClipChildren(false);
                    ((ViewGroup) v).setClipToPadding(false);
                }
                ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public Runnable getOnEventDetected() {
        return onEventDetected;
    }

    public void setOnEventDetected(Runnable onEventDetected) {
        this.onEventDetected = onEventDetected;
    }
}
