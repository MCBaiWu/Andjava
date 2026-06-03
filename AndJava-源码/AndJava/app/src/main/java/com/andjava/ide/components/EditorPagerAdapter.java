package com.andjava.ide.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.viewpager.widget.PagerAdapter;
import com.dream.highlighteditor.activity.JavaEditCode;
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器页面适配器 - 管理多个 CodeEditorView 实例
 */
public class EditorPagerAdapter extends PagerAdapter {

    private Context context;
    private List<JavaEditCode> editors = new ArrayList<JavaEditCode>();
    private List<String> titles = new ArrayList<String>();
    private List<String> filePaths = new ArrayList<String>();

    private OnTextChangeListener textChangeListener;

    public void clearAllEditors() {
        clearAll();
    }

    public interface OnTextChangeListener {
        void onTextChanged(int position, String text);
    }

    public EditorPagerAdapter(Context context) {
        this.context = context;
    }

    public void setOnTextChangeListener(OnTextChangeListener listener) {
        this.textChangeListener = listener;
    }

    /**
     * 添加一个新页面
     * @param filePath 文件路径
     * @param title 标签标题
     * @param initialContent 初始内容
     * @return 新页面的位置
     */
    public int addEditor(String filePath, String title, String initialContent) {
        JavaEditCode editor = new JavaEditCode(context);
        editor.setLayoutParams(new ViewGroup.LayoutParams(
                                   ViewGroup.LayoutParams.MATCH_PARENT,
                                   ViewGroup.LayoutParams.MATCH_PARENT));
        editor.setEditorText(initialContent != null ? initialContent : "");

        final int position = editors.size();
        
        editors.add(editor);
        titles.add(title);
        filePaths.add(filePath);
        notifyDataSetChanged();
        return position;
    }

    /**
     * 移除指定位置的页面
     */
    public void removeEditor(int position) {
        if (position < 0 || position >= editors.size()) return;
        editors.remove(position);
        titles.remove(position);
        filePaths.remove(position);
        notifyDataSetChanged();
    }

    /**
     * 获取指定位置的编辑器实例
     */
    public JavaEditCode getEditor(int position) {
        if (position < 0 || position >= editors.size()) return null;
        return editors.get(position);
    }

    /**
     * 获取指定位置的文件路径
     */
    public String getFilePath(int position) {
        if (position < 0 || position >= filePaths.size()) return null;
        return filePaths.get(position);
    }

    /**
     * 获取指定位置的编辑器当前文本内容
     */
    public String getEditorContent(int position) {
        JavaEditCode editor = getEditor(position);
        if (editor != null) {
            return editor.getEditorText().toString();
        }
        return "";
    }

    /**
     * 更新指定位置的文件标题（用于修改标记显示）
     */
    public void updateTitle(int position, String newTitle) {
        if (position < 0 || position >= titles.size()) return;
        titles.set(position, newTitle);
        notifyDataSetChanged();
    }

    /**
     * 查找文件路径对应的页面位置
     */
    public int findPositionByPath(String path) {
        for (int i = 0; i < filePaths.size(); i++) {
            if (filePaths.get(i).equals(path)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 清空所有页面
     */
    public void clearAll() {
        editors.clear();
        titles.clear();
        filePaths.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return editors.size();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        JavaEditCode editor = editors.get(position);
        container.addView(editor);
        return editor;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if (position < 0 || position >= titles.size()) return "";
        return titles.get(position);
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }
}
