package com.andjava.ide.components;

import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import com.google.android.material.tabs.TabLayout;

import androidx.viewpager.widget.ViewPager;

/**
 * 文件标签栏 - 简单包装 TabLayout，与 ViewPager 联动
 */
public class FileTabBar extends HorizontalScrollView {

    private TabLayout tabLayout;

    public FileTabBar(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(Color.parseColor("#1976D2"));

        tabLayout = new TabLayout(context);
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabLayout.setBackgroundColor(Color.parseColor("#1976D2"));
        tabLayout.setSelectedTabIndicatorColor(Color.WHITE);
        tabLayout.setSelectedTabIndicatorHeight(dp2px(3));
        tabLayout.setTabTextColors(Color.parseColor("#B0BEC5"), Color.WHITE);

        addView(tabLayout, new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 与 ViewPager 关联，自动同步标签标题和选中状态
     */
    public void setupWithViewPager(ViewPager viewPager) {
        tabLayout.setupWithViewPager(viewPager);
    }

    /**
     * 获取内部的 TabLayout，方便外部直接操作（如设置自定义标签视图）
     */
    public TabLayout getTabLayout() {
        return tabLayout;
    }

    private int dp2px(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density);
    }
}
