package com.andjava.ide.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 自定义底部控制台抽屉。
 *
 * 与旧版本相比：
 * 1. 支持 4 类消息：ERROR / WARNING / INFO / SUCCESS，分别用红/黄/青/绿色文字
 * 2. 自动时间戳
 * 3. 不再用绿色 "AED581" 当默认色，而是淡灰色
 * 4. 提供更细粒度的 logError / logWarn / logInfo / logSuccess API
 * 5. 显示正在编译 / 警告 / 错误计数小标签
 */
public class ConsoleDrawer extends FrameLayout {

    public static final int STATE_COLLAPSED = 0;
    public static final int STATE_EXPANDED = 1;

    private LinearLayout contentLayout;
    private View headerView;
    private TextView consoleText;
    private TextView counterView;
    private ScrollView scrollView;
    private View maskView;
    private View dragHandle;

    private int currentState = STATE_COLLAPSED;
    private int expandedHeight;
    private int collapsedHeight = 0;
    private boolean isAnimating = false;

    private float lastTouchY;
    private int dragStartHeight;

    private OnStateChangeListener stateListener;
    private ValueAnimator animator;

    private int errorCount = 0;
    private int warningCount = 0;
    private int infoCount = 0;
    private int successCount = 0;

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public enum Level {
        INFO(0xFFB0BEC5, "[INFO] "),
        WARNING(0xFFE6B800, "[WARN] "),
        ERROR(0xFFE53935, "[ERROR] "),
        SUCCESS(0xFF81C784, "[OK] ");

        public final int color;
        public final String tag;
        Level(int color, String tag) { this.color = color; this.tag = tag; }
    }

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

        maskView = new View(getContext());
        maskView.setBackgroundColor(Color.parseColor("#80000000"));
        maskView.setVisibility(View.GONE);
        maskView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { collapse(); }
            });
        addView(maskView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

        contentLayout = new LinearLayout(getContext());
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setBackgroundColor(Color.parseColor("#1E272C"));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.BOTTOM;
        contentLayout.setLayoutParams(contentParams);

        headerView = createHeader();
        contentLayout.addView(headerView);

        scrollView = new ScrollView(getContext());
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1.0f));
        scrollView.setFillViewport(true);

        consoleText = new TextView(getContext());
        consoleText.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        consoleText.setTextColor(Color.parseColor("#CFD8DC"));
        consoleText.setTextSize(11);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
        consoleText.setText("");

        scrollView.addView(consoleText);
        contentLayout.addView(scrollView);

        addView(contentLayout);

        dragHandle = new View(getContext());
        dragHandle.setBackgroundColor(Color.parseColor("#33FFFFFF"));
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp2px(8));
        handleParams.gravity = Gravity.BOTTOM;
        dragHandle.setLayoutParams(handleParams);
        dragHandle.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return headerView.dispatchTouchEvent(event);
                }
            });
        dragHandle.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { expand(); }
            });
        addView(dragHandle);

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

        counterView = new TextView(getContext());
        counterView.setTextSize(11);
        counterView.setTextColor(Color.parseColor("#90A4AE"));
        counterView.setPadding(0, 0, dp2px(12), 0);
        counterView.setText("0 E / 0 W");
        header.addView(counterView);

        TextView arrow = new TextView(getContext());
        arrow.setText("▲");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(16);
        arrow.setTag("arrow");
        header.addView(arrow);

        header.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { toggle(); }
            });

        header.setOnTouchListener(new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            lastTouchY = event.getRawY();
                            dragStartHeight = getCurrentHeight();
                            if (animator != null && animator.isRunning()) animator.cancel();
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
                            if (currentHeight > midPoint) expand(); else collapse();
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

    private void updateDragHandleVisibility() {
        dragHandle.setVisibility(currentState == STATE_COLLAPSED ? View.VISIBLE : View.GONE);
    }

    private void setState(int state) {
        if (currentState == state) return;
        currentState = state;
        updateArrow();
        updateMaskVisibility();
        updateDragHandleVisibility();
        if (stateListener != null) stateListener.onStateChanged(state);
    }

    public void expand() { animateToHeight(expandedHeight, STATE_EXPANDED); }
    public void collapse() { animateToHeight(collapsedHeight, STATE_COLLAPSED); }
    public void toggle() {
        if (currentState == STATE_EXPANDED) collapse(); else expand();
    }

    private void animateToHeight(int targetHeight, final int targetState) {
        if (isAnimating) return;
        if (animator != null && animator.isRunning()) animator.cancel();
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

    // ============== 公开 API ==============

    /**
     * 兼容旧接口：默认 INFO 级别。
     */
    public void log(String message) {
        log(Level.INFO, message);
    }

    public void logError(String message) { log(Level.ERROR, message); }
    public void logWarn(String message)  { log(Level.WARNING, message); }
    public void logInfo(String message)  { log(Level.INFO, message); }
    public void logSuccess(String message) { log(Level.SUCCESS, message); }

    /**
     * 主入口：把消息以指定级别加入控制台
     */
    public void log(Level level, String message) {
        if (consoleText == null || message == null) return;
        switch (level) {
            case ERROR: errorCount++; break;
            case WARNING: warningCount++; break;
            case INFO: infoCount++; break;
            case SUCCESS: successCount++; break;
        }
        updateCounter();

        String time = TIME_FMT.format(new Date());
        String tag = level.tag;
        String line = time + " " + tag + message + "\n";
        Spannable span = new SpannableString(line);
        int start = 0;
        int end = line.length();
        span.setSpan(new ForegroundColorSpan(level.color), start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        // 时间戳弱化
        span.setSpan(new ForegroundColorSpan(0xFF607D8B), 0, time.length() + 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE);

        appendSpan(span);
        scrollView.post(new Runnable() {
                @Override
                public void run() { scrollView.fullScroll(View.FOCUS_DOWN); }
            });
    }

    private void appendSpan(Spannable s) {
        if (consoleText.length() == 0) {
            consoleText.setText(s);
        } else {
            SpannableStringBuilder sb = new SpannableStringBuilder(consoleText.getText());
            sb.append(s);
            consoleText.setText(sb);
        }
    }

    private void updateCounter() {
        if (counterView == null) return;
        StringBuilder sb = new StringBuilder();
        if (errorCount > 0) {
            sb.append(errorCount).append('E');
        }
        if (warningCount > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(warningCount).append('W');
        }
        if (errorCount == 0 && warningCount == 0) {
            sb.append("0 E / 0 W");
        }
        counterView.setText(sb.toString());
        if (errorCount > 0) {
            counterView.setTextColor(0xFFE57373);
        } else if (warningCount > 0) {
            counterView.setTextColor(0xFFFFD54F);
        } else {
            counterView.setTextColor(0xFF81C784);
        }
    }

    public void resetCounters() {
        errorCount = 0;
        warningCount = 0;
        infoCount = 0;
        successCount = 0;
        updateCounter();
    }

    public void clear() {
        if (consoleText != null) consoleText.setText("");
        resetCounters();
    }

    public void setText(String text) {
        if (consoleText != null) consoleText.setText(text == null ? "" : text);
    }

    public boolean isExpanded() { return currentState == STATE_EXPANDED; }

    public void setOnStateChangeListener(OnStateChangeListener listener) { this.stateListener = listener; }

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
