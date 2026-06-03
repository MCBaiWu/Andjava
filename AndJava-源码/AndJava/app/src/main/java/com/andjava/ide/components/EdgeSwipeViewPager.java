package com.andjava.ide.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.viewpager.widget.ViewPager;

/**
 * 边缘滑动 ViewPager：仅当触摸起始点位于屏幕左/右边缘区域内时，才允许横向滑动切换页面。
 */
public class EdgeSwipeViewPager extends ViewPager {

    private static final int EDGE_WIDTH_DP = 40;  // 边缘触发宽度（dp）
    private final int edgeWidthPx;

    private float initialX;
    private boolean isEdgeTouch = false;

    public EdgeSwipeViewPager(Context context) {
        this(context, null);
    }

    public EdgeSwipeViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        edgeWidthPx = dp2px(context, EDGE_WIDTH_DP);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                initialX = ev.getX();
                isEdgeTouch = (initialX < edgeWidthPx) || (initialX > getWidth() - edgeWidthPx);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isEdgeTouch) {
                    return false;
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isEdgeTouch) {
            return false;
        }
        return super.onTouchEvent(ev);
    }

    private int dp2px(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
