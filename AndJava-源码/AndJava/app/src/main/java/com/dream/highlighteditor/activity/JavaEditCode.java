package com.dream.highlighteditor.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dream.highlighteditor.editor.TextEditor;

/**
 * Java代码编辑器组件 - 内置底部符号工具栏（深色模式）
 */
public class JavaEditCode extends LinearLayout {
    private TextEditor contentEdit;
    private HorizontalScrollView toolbarScrollView;
    private LinearLayout toolbarContainer;

    // 深色模式配色常量
    private static final int COLOR_TOOLBAR_BG = Color.parseColor("#1E1E1E");      // 工具栏背景深灰色
    private static final int COLOR_BUTTON_TEXT = Color.parseColor("#E0E0E0");     // 按钮文字浅灰白
    private static final int COLOR_BUTTON_NORMAL = Color.parseColor("#1E1E1E");   // 按钮正常背景（与工具栏一致）
    private static final String COLOR_BUTTON_PRESSED = "#3A3A3A";                 // 按钮按下时更亮的灰色
    private static final int COLOR_DIVIDER = Color.parseColor("#2D2D2D");         // 分割线深色

    // 常用符号和关键字（可自定义）
    private static final String[] TOOLBAR_ITEMS = {
        "{", "}", "(", ")", "[", "]", ";", ",", ".", "=",
        "+", "-", "*", "/", "%", "<", ">", "&", "|", "!",
        "?", ":", "\"", "'", "\\", "#", "@",
        "new", "return", "if", "else", "for", "while", "class", "public", "private"
    };

    public JavaEditCode(Context context) {
        super(context);
        init(context);
    }

    public JavaEditCode(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public JavaEditCode(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(LinearLayout.VERTICAL);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // 编辑器区域背景也设为深色（与编辑器内部一致）
        setBackgroundColor(Color.parseColor("#1E1E1E"));

        // 1. 创建编辑器（占据剩余空间）
        contentEdit = new TextEditor(context);
        LayoutParams editorParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f);
        contentEdit.setLayoutParams(editorParams);
        contentEdit.requestFocus();
        addView(contentEdit);

        // 2. 分割线（深色）
        View divider = new View(context);
        LayoutParams dividerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp2px(context, 1));
        divider.setLayoutParams(dividerParams);
        divider.setBackgroundColor(COLOR_DIVIDER);
        addView(divider);

        // 3. 底部工具栏（水平滚动，深色背景）
        toolbarScrollView = new HorizontalScrollView(context);
        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp2px(context, 46));
        toolbarScrollView.setLayoutParams(scrollParams);
        toolbarScrollView.setBackgroundColor(COLOR_TOOLBAR_BG);
        toolbarScrollView.setHorizontalScrollBarEnabled(false);
        toolbarScrollView.setVerticalScrollBarEnabled(false);
        toolbarScrollView.setFillViewport(true);

        toolbarContainer = new LinearLayout(context);
        toolbarContainer.setLayoutParams(new LinearLayout.LayoutParams(
                                             ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        toolbarContainer.setOrientation(LinearLayout.HORIZONTAL);
        toolbarContainer.setGravity(Gravity.CENTER_VERTICAL);
        toolbarContainer.setBackgroundColor(COLOR_TOOLBAR_BG);

        // 添加按钮
        for (String item : TOOLBAR_ITEMS) {
            TextView button = createToolbarButton(context, item);
            toolbarContainer.addView(button);
        }

        toolbarScrollView.addView(toolbarContainer);
        addView(toolbarScrollView);
    }

    /**
     * 创建工具栏按钮（深色风格：无圆角，深色背景，按下变亮）
     */
    private TextView createToolbarButton(Context context, final String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);

        int minWidth = dp2px(context, 56);
        button.setMinWidth(minWidth);
        button.setPadding(dp2px(context, 8), 0, dp2px(context, 8), 0);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        button.setLayoutParams(btnParams);

        button.setTextSize(16f);
        button.setTextColor(COLOR_BUTTON_TEXT);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);

        // 背景：无圆角，按下效果（深色）
        Drawable background = createRectButtonBackground(COLOR_BUTTON_NORMAL, COLOR_BUTTON_PRESSED);
        button.setBackground(background);

        button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (contentEdit != null) {
                        contentEdit.paste(text);
                    }
                }
            });

        return button;
    }

    /**
     * 创建矩形按钮背景（无圆角），深色主题
     */
    private Drawable createRectButtonBackground(int normalColor, String pressedColorHex) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(normalColor);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // API 21+ 使用 RippleDrawable 实现涟漪效果
            int[][] states = new int[][]{new int[]{android.R.attr.state_pressed}};
            int[] colors = new int[]{Color.parseColor(pressedColorHex)};
            ColorStateList rippleColorList = new ColorStateList(states, colors);
            return new RippleDrawable(rippleColorList, shape, null);
        } else {
            // 低版本使用 StateListDrawable 模拟按压效果
            StateListDrawable stateList = new StateListDrawable();
            GradientDrawable pressedShape = new GradientDrawable();
            pressedShape.setColor(Color.parseColor(pressedColorHex));
            stateList.addState(new int[]{android.R.attr.state_pressed}, pressedShape);
            stateList.addState(new int[]{}, shape);
            return stateList;
        }
    }

    private int dp2px(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // ---------- 公共方法 ----------

    public void setEditorText(String text) {
        if (contentEdit != null) {
            contentEdit.setText(text);
        }
    }

    public void paste(String text) {
        if (contentEdit != null) {
            contentEdit.paste(text);
        }
    }

    public String getEditorText() {
        return contentEdit != null ? contentEdit.getText().toString() : "";
    }

    public void undo() {
        if (contentEdit != null) {
            contentEdit.undo();
        }
    }

    public void redo() {
        if (contentEdit != null) {
            contentEdit.redo();
        }
    }

    public TextEditor geteditor() {
        return contentEdit;
    }

    public void copyToClipboard() {
        String content = getEditorText();
      
        if (content.isEmpty()) {
            Toast.makeText(getContext(), "代码为空，无需复制", Toast.LENGTH_SHORT).show();
        } else {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = ClipData.newPlainText("code", content);
            cm.setPrimaryClip(cd);
            
            Toast.makeText(getContext(), "复制成功", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 设置工具栏是否可见
     */
    public void setToolbarVisible(boolean visible) {
        if (toolbarScrollView != null) {
            toolbarScrollView.setVisibility(visible ? VISIBLE : GONE);
        }
    }
}
