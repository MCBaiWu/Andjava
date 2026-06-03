package com.andjava.ide.components;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;

/**
 * 顶部工具栏组件 - 完全使用原生 Toolbar 功能
 */
public class ToolBar extends Toolbar {

    private OnMenuClickListener menuClickListener;
    private Menu toolbarMenu;

    public interface OnMenuClickListener {
        void onMenuClick();
    }

    public ToolBar(Context context) {
        super(context);
        init();
    }

    private void init() {
        // 设置背景色
        setBackgroundColor(Color.parseColor("#1976D2"));
        // 设置内边距
        setContentInsetsAbsolute(dp2px(8), dp2px(8));
        // 设置高度
        setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp2px(56)));

        // 设置标题和副标题（原生API）
        setTitle("AndJava IDE");
        setTitleTextColor(Color.WHITE);
        setSubtitleTextColor(Color.parseColor("#E0E0E0"));

        // 设置导航图标（MD1风格）并着色白色
        Drawable navIcon = getResources().getDrawable(getMd1MenuIcon());
        navIcon = DrawableCompat.wrap(navIcon);
        DrawableCompat.setTint(navIcon, Color.WHITE);
        DrawableCompat.setTintMode(navIcon, PorterDuff.Mode.SRC_IN);
        setNavigationIcon(navIcon);

        // 导航点击监听
        setNavigationOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (menuClickListener != null) {
                        menuClickListener.onMenuClick();
                    }
                }
            });

        // 初始化菜单
        toolbarMenu = getMenu();
    }
    private int getMd1MenuIcon() {
    try {
    return androidx.appcompat.R.drawable.abc_menu_hardkey_panel_mtrl_mult;
     } catch (NoSuchFieldError e) {
     return android.R.drawable.ic_menu_sort_by_size;
    }
    }

    /**
     * 获取 MD1 菜单图标资源ID
     */
    

    /**
     * 设置标题（覆盖父类方法以保持接口一致，但父类已有此方法）
     */
    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
    }

    public void setTitle(String title) {
        super.setTitle(title);
    }

    /**
     * 设置副标题
     */
    @Override
    public void setSubtitle(CharSequence subtitle) {
        super.setSubtitle(subtitle);
    }

    public void setSubtitle(String subtitle) {
        super.setSubtitle(subtitle);
    }

    /**
     * 添加右侧按钮（使用原生菜单实现）
     */
    public void addRightButton(int iconRes, final OnClickListener listener) {
        if (toolbarMenu != null) {
            // 生成唯一ID
            final int itemId = View.generateViewId();
            MenuItem item = toolbarMenu.add(Menu.NONE, itemId, Menu.NONE, "");
            item.setIcon(iconRes);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            // 图标着色白色
            Drawable icon = item.getIcon();
            if (icon != null) {
                icon = DrawableCompat.wrap(icon);
                DrawableCompat.setTint(icon, Color.WHITE);
                DrawableCompat.setTintMode(icon, PorterDuff.Mode.SRC_IN);
                item.setIcon(icon);
            }

            // 设置点击监听
            setOnMenuItemClickListener(new OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem menuItem) {
                        if (menuItem.getItemId() == itemId && listener != null) {
                            listener.onClick(ToolBar.this);
                            return true;
                        }
                        return false;
                    }
                });
        }
    }

    public void setOnMenuClickListener(OnMenuClickListener listener) {
        this.menuClickListener = listener;
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
