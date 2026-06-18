package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;

import com.myopicmobile.textwarrior.android.EcjDiagnosticService.Diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FreeScrollingTextField 的子类，增加"波浪线"错误高亮。
 *
 * 不修改原 FreeScrollingTextField 任何代码。只需在布局文件中
 * 使用 {@code <com.myopicmobile.textwarrior.android.DiagnosticTextField/>}
 * 替换 {@code <com.myopicmobile.textwarrior.android.FreeScrollingTextField/>}。
 *
 * 通过 {@link #setDiagnostics(List)} 提供诊断项；列表为空时关闭绘制。
 *
 * 颜色：
 *   - 错误  -> 红色
 *   - 警告  -> 黄色
 *   - 信息  -> 青色
 */
public class DiagnosticTextField extends FreeScrollingTextField {

    private List<Diagnostic> diagnostics = Collections.emptyList();
    private final Paint squigglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path squigglePath = new Path();
    private boolean enabled = true;

    public DiagnosticTextField(Context context) {
        super(context);
        initSquiggle();
    }

    public DiagnosticTextField(Context context, AttributeSet attrs) {
        super(context, attrs);
        initSquiggle();
    }

    public DiagnosticTextField(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initSquiggle();
    }

    private void initSquiggle() {
        squigglePaint.setStyle(Paint.Style.STROKE);
        squigglePaint.setStrokeWidth(dp(1.4f));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setEnabled(boolean e) {
        this.enabled = e;
        invalidate();
    }

    public boolean isDiagnosticsEnabled() {
        return enabled;
    }

    public void setDiagnostics(List<Diagnostic> list) {
        if (list == null) list = Collections.emptyList();
        // 简单变更检测：长度或首尾诊断变化则重绘
        if (list.size() != diagnostics.size()) {
            this.diagnostics = new ArrayList<Diagnostic>(list);
            invalidate();
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Diagnostic a = list.get(i);
            Diagnostic b = diagnostics.get(i);
            if (a.startOffset != b.startOffset || a.endOffset != b.endOffset
                    || a.severity != b.severity) {
                this.diagnostics = new ArrayList<Diagnostic>(list);
                invalidate();
                return;
            }
        }
        this.diagnostics = list;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void clearDiagnostics() {
        setDiagnostics(Collections.<Diagnostic>emptyList());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (enabled && !diagnostics.isEmpty()) {
            drawSquiggles(canvas);
        }
    }

    private void drawSquiggles(Canvas canvas) {
        // paintX 起点（参考父类 onDraw 估算）：gutter 宽度（_leftOffset）
        // 我们没有 _leftOffset 的 getter，但 getRowWidth 给我们宽度。
        // 行高 = rowHeight() (protected)
        // 通过 reflect 不到的东西，统统不画。
        int rowH = rowHeight();
        if (rowH <= 0) return;
        for (Diagnostic d : diagnostics) {
            int color = colorForSeverity(d.severity);
            squigglePaint.setColor(color);
            drawOneSquiggle(canvas, d, rowH);
        }
    }

    private int colorForSeverity(EcjDiagnosticService.Severity s) {
        if (s == null) return Color.RED;
        switch (s) {
            case WARNING: return 0xFFE6B800; // 黄色
            case INFO:    return 0xFF3FA9F5; // 青色
            case ERROR:
            default:      return 0xFFE53935; // 红色
        }
    }

    private void drawOneSquiggle(Canvas canvas, Diagnostic d, int rowH) {
        if (d.startOffset < 0 || d.endOffset < d.startOffset) return;
        int line = d.startLine;
        if (line < 0) line = 0;
        int baselineY = getPaintBaseline(line);
        if (baselineY <= 0) return;

        int startX = xForOffset(d.startOffset);
        int endX = xForOffset(d.endOffset);
        if (endX <= startX) {
            // 兜底：至少画 1 个字符宽
            endX = startX + getAdvance(' ', 0);
        }

        int waveY = baselineY + 4;
        int step = 4;
        squigglePath.reset();
        boolean up = true;
        for (int x = startX; x < endX; x += step) {
            int y = waveY + (up ? -1 : 1) * 2;
            if (x == startX) {
                squigglePath.moveTo(x, y);
            } else {
                squigglePath.lineTo(x, y);
            }
            up = !up;
        }
        canvas.drawPath(squigglePath, squigglePaint);
    }

    /**
     * 粗略估算某字符偏移的 x 坐标。
     * 父类没有公开从 char offset 直接取 x 的 API，
     * 这里用 gutter 偏移 + 当前行 col * char advance 估算。
     */
    private int xForOffset(int offset) {
        if (offset <= 0) return getPaddingLeft() + 2;
        int line = lineFromOffset(offset);
        int col = colOnLineFromOffset(offset, line);
        int x = getPaddingLeft() + 2;
        // 用 'M' 近似为字符宽
        int charW = Math.max(1, getAdvance('M', 0));
        x += col * charW;
        return x;
    }

    private int lineFromOffset(int offset) {
        // 使用父类的 _hDoc 来计算行号
        try {
            return _hDoc.findRowNumber(offset);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int colOnLineFromOffset(int offset, int line) {
        // 计算该行的起始偏移，然后求差值得到列号
        try {
            int lineStartOffset = _hDoc.getLineOffset(line);
            return Math.max(0, offset - lineStartOffset);
        } catch (Throwable t) {
            return Math.max(0, offset % 80);
        }
    }
}
