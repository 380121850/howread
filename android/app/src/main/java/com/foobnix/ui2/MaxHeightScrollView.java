package com.foobnix.ui2;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * ScrollView that actually honors android:maxHeight. The platform ScrollView
 * silently ignores the attribute, so the "My files" root page's remote section
 * (OPDS/WebDAV/SMB/SFTP entries) could grow unbounded and squeeze the
 * library-folder list to zero height on small screens (2026-09-13).
 */
public class MaxHeightScrollView extends ScrollView {

    private int maxHeightPx = -1;

    public MaxHeightScrollView(Context context) {
        super(context);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        readMaxHeight(attrs);
    }

    public MaxHeightScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        readMaxHeight(attrs);
    }

    private void readMaxHeight(AttributeSet attrs) {
        if (attrs == null) {
            return;
        }
        TypedArray ta = getContext().obtainStyledAttributes(attrs, new int[] { android.R.attr.maxHeight });
        maxHeightPx = ta.getDimensionPixelSize(0, -1);
        ta.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeightPx > 0) {
            heightMeasureSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    maxHeightPx, android.view.View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
