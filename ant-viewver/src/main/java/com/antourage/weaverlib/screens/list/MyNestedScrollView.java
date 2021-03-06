package com.antourage.weaverlib.screens.list;

import android.content.Context;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import android.util.AttributeSet;
import android.view.View;
import android.widget.OverScroller;

import java.lang.reflect.Field;

@SuppressWarnings("unused")
class MyNestedScrollView extends NestedScrollView {

    private final OverScroller mScroller;
    public boolean isFling = false;
    private OnBottomReachedListener listener;

    public MyNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mScroller = getOverScroller();
    }

    @Override
    public void fling(int velocityY) {
        super.fling(velocityY);

        // here we effectively extend the super class functionality for backwards compatibility and just call invalidateOnAnimation()
        if (getChildCount() > 0) {
            ViewCompat.postInvalidateOnAnimation(this);

            // Initializing isFling to true to track fling action in onScrollChanged() method
            isFling = true;
        }
    }


    @Override
    protected void onScrollChanged(int l, final int t, final int oldl, int oldt) {
        View view = getChildAt(getChildCount() - 1);
        int diff = view.getBottom() - (getHeight() + getScrollY());
        if (diff == 0 && listener != null) {
            listener.onBottomReached(this);
        }
        super.onScrollChanged(l, t, oldl, oldt);

        if (isFling) {
            if (Math.abs(t - oldt) <= 3 || t == 0 || t == (getChildAt(0).getMeasuredHeight() - getMeasuredHeight())) {
                isFling = false;

                // This forces the mFinish variable in scroller to true (as explained the
                //    mentioned link above) and does the trick
                if (mScroller != null) {
                    mScroller.abortAnimation();
                }
            }
        }
    }

    private OverScroller getOverScroller() {
        Field fs;
        try {
            fs = this.getClass().getSuperclass().getDeclaredField("mScroller");
            fs.setAccessible(true);
            return (OverScroller) fs.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    public OnBottomReachedListener getBottomChangedListener() {
        return listener;
    }

    public void setBottomReachesListener(OnBottomReachedListener onBottomReachedListener) {
        this.listener = onBottomReachedListener;
    }

    public interface OnBottomReachedListener {
        void onBottomReached(View view);
    }
}