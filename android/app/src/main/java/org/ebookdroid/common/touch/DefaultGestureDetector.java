package org.ebookdroid.common.touch;

import android.content.Context;
import android.view.GestureDetector;

public class DefaultGestureDetector extends GestureDetector implements IGestureDetector {

    public DefaultGestureDetector(final Context context, final OnGestureListener listener) {
        super(context, listener);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean onTouchEvent(final android.view.MotionEvent ev) {
        final int a = ev.getAction();
        if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_UP
                || a == android.view.MotionEvent.ACTION_CANCEL) {
            com.foobnix.android.utils.LOG.bench("TouchDG a=" + a);
        }
        return super.onTouchEvent(ev);
    }
}
