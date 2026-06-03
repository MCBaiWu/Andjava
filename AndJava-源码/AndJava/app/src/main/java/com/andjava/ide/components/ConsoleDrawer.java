package com.andjava.ide.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 自定义底部控制台抽屉
 * 特性：
 * - 默认完全隐藏（高度为0），只显示底部拖拽条
 * - 向上拖动拖拽条或点击可展开
 * - 展开后显示完整控制台（头部+可滚动内容）
 * - 自带半透明遮罩，点击遮罩关闭
 */
public class ConsoleDrawer extends FrameLayout {

    // 状态常量
    public static final int STATE_COLLAPSED = 0;
    public static final int STATE_EXPANDED = 1;

    // UI 组件
    private LinearLayout contentLayout;      // 控制台主体（头部+内容）
    private View headerView;
    private TextView consoleText;
    private ScrollView scrollView;
    private View maskView;                   // 半透明遮罩
    private View dragHandle;                 // 底部拖拽条

    // 状态
    private int currentState = STATE_COLLAPSED;
    private int expandedHeight;              // 展开高度（px）
    private int collapsedHeight = 0;         // 折叠高度固定为0
    private boolean isAnimating = false;

    // 拖动相关
    private float lastTouchY;
    private int dragStartHeight;

    // 监听器
    private OnStateChangeListener stateListener;

    // 动画
    private ValueAnimator animator;

    public interface OnStateChangeListener {
        void onStateChanged(int state);
    }

    public ConsoleDrawer(Context context) {
        super(context);
        init();
    }

    private void init() {
        setClipChildren(true);
        setClipToPadding(true);

        expandedHeight = dp2px(400);
        collapsedHeight = 0;

        // 1. 半透明遮罩（默认隐藏）
        maskView = new View(getContext());
        maskView.setBackgroundColor(Color.parseColor("#80000000"));
        maskView.setVisibility(View.GONE);
        maskView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    collapse();
                }
            });
        addView(maskView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. 控制台主体容器
        contentLayout = new LinearLayout(getContext());
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setBackgroundColor(Color.parseColor("#1E272C"));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.BOTTOM;
        contentLayout.setLayoutParams(contentParams);

        // 头部（标题栏 + 箭头）
        headerView = createHeader();
        contentLayout.addView(headerView);

        // 可滚动内容区域
        scrollView = new ScrollView(getContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                                       ViewGroup.LayoutParams.MATCH_PARENT,
                                       0, 1.0f));
        scrollView.setFillViewport(true);

        consoleText = new TextView(getContext());
        consoleText.setLayoutParams(new ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT));
        consoleText.setTextColor(Color.parseColor("#AED581"));
        consoleText.setTextSize(12);
        consoleText.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleText.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
        consoleText.setText("控制台已就绪...");

        scrollView.addView(consoleText);
        contentLayout.addView(scrollView);

        addView(contentLayout);

        // 3. 底部拖拽条（始终可见，用于触发展开）
        dragHandle = new View(getContext());
        dragHandle.setBackgroundColor(Color.parseColor("#33FFFFFF")); // 半透明白
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp2px(8));
        handleParams.gravity = Gravity.BOTTOM;
        dragHandle.setLayoutParams(handleParams);
        dragHandle.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // 将触摸事件转发给头部处理，实现统一的拖拽逻辑
                    return headerView.dispatchTouchEvent(event);
                }
            });
        dragHandle.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    expand();
                }
            });
        addView(dragHandle);

        // 初始状态：折叠（高度为0）
        post(new Runnable() {
                @Override
                public void run() {
                    setHeight(collapsedHeight);
                    updateArrow();
                    updateDragHandleVisibility();
                }
            });
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(Color.parseColor("#37474F"));
        header.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClickable(true);
        header.setFocusable(true);

        TextView title = new TextView(getContext());
        title.setText("控制台");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                                  0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(title);

        TextView arrow = new TextView(getContext());
        arrow.setText("▲");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(16);
        arrow.setTag("arrow");
        header.addView(arrow);

        // 点击头部切换
        header.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle();
                }
            });

        // 触摸拖动
        header.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            lastTouchY = event.getRawY();
                            dragStartHeight = getCurrentHeight();
                            if (animator != null && animator.isRunning()) {
                                animator.cancel();
                            }
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float deltaY = event.getRawY() - lastTouchY;
                            int newHeight = (int) (dragStartHeight - deltaY);
                            newHeight = Math.max(collapsedHeight, Math.min(expandedHeight, newHeight));
                            setHeight(newHeight);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            int currentHeight = getCurrentHeight();
                            int midPoint = (collapsedHeight + expandedHeight) / 2;
                            if (currentHeight > midPoint) {
                                expand();
                            } else {
                                collapse();
                            }
                            return true;
                    }
                    return false;
                }
            });

        return header;
    }

    private int getCurrentHeight() {
        return contentLayout.getLayoutParams().height;
    }

    private void setHeight(int height) {
        ViewGroup.LayoutParams params = contentLayout.getLayoutParams();
        if (params != null) {
            params.height = height;
            contentLayout.setLayoutParams(params);
        }
        // 根据高度决定状态
        if (height >= expandedHeight - dp2px(10)) {
            setState(STATE_EXPANDED);
        } else if (height <= collapsedHeight + dp2px(10)) {
            setState(STATE_COLLAPSED);
        }
        updateMaskVisibility();
        updateDragHandleVisibility();
    }

    private void updateArrow() {
        TextView arrow = (TextView) headerView.findViewWithTag("arrow");
        if (arrow != null) {
            arrow.setText(currentState == STATE_EXPANDED ? "▼" : "▲");
        }
    }

    private void updateMaskVisibility() {
        maskView.setVisibility(currentState == STATE_EXPANDED ? View.VISIBLE : View.GONE);
    }

    /**
     * 折叠时显示底部拖拽条，展开时隐藏（因为头部已可拖拽）
     */
    private void updateDragHandleVisibility() {
        dragHandle.setVisibility(currentState == STATE_COLLAPSED ? View.VISIBLE : View.GONE);
    }

    private void setState(int state) {
        if (currentState == state) return;
        currentState = state;
        updateArrow();
        updateMaskVisibility();
        updateDragHandleVisibility();
        if (stateListener != null) {
            stateListener.onStateChanged(state);
        }
    }

    // ---------- 动画控制 ----------
    public void expand() {
        animateToHeight(expandedHeight, STATE_EXPANDED);
    }

    public void collapse() {
        animateToHeight(collapsedHeight, STATE_COLLAPSED);
    }

    public void toggle() {
        if (currentState == STATE_EXPANDED) {
            collapse();
        } else {
            expand();
        }
    }

    private void animateToHeight(int targetHeight, final int targetState) {
        if (isAnimating) return;
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }

        final int startHeight = getCurrentHeight();
        animator = ValueAnimator.ofInt(startHeight, targetHeight);
        animator.setDuration(250);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    int value = (Integer) animation.getAnimatedValue();
                    ViewGroup.LayoutParams params = contentLayout.getLayoutParams();
                    params.height = value;
                    contentLayout.setLayoutParams(params);
                    // 动态显示遮罩和拖拽条
                    if (value > collapsedHeight + dp2px(20)) {
                        maskView.setVisibility(View.VISIBLE);
                        dragHandle.setVisibility(View.GONE);
                    } else {
                        maskView.setVisibility(View.GONE);
                        dragHandle.setVisibility(View.VISIBLE);
                    }
                }
            });
        animator.start();

        postDelayed(new Runnable() {
                @Override
                public void run() {
                    setState(targetState);
                    isAnimating = false;
                }
            }, 260);
        isAnimating = true;
    }

    // ---------- 公开方法 ----------
    public void log(String message) {
        if (consoleText == null) return;
        consoleText.append("\n" + message);
        scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
    }

    public void clear() {
        if (consoleText != null) {
            consoleText.setText("");
        }
    }

    public void setText(String text) {
        if (consoleText != null) {
            consoleText.setText(text);
        }
    }

    public boolean isExpanded() {
        return currentState == STATE_EXPANDED;
    }

    public void setOnStateChangeListener(OnStateChangeListener listener) {
        this.stateListener = listener;
    }

    public void setExpandedHeightDp(int dp) {
        expandedHeight = dp2px(dp);
        if (currentState == STATE_EXPANDED) {
            ViewGroup.LayoutParams params = contentLayout.getLayoutParams();
            params.height = expandedHeight;
            contentLayout.setLayoutParams(params);
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density);
    }
}
