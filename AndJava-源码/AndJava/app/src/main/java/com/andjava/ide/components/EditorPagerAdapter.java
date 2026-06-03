package com.andjava.ide.components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.viewpager.widget.PagerAdapter;
import com.andjava.ide.project.ProjectIndexService;
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

    private ProjectIndexService lastProjectIndex;

    public void clearAllEditors() {
        clearAll();
    }

    public interface OnTextChangeListener {
        void onTextChanged(int position, String text);
    }

    public EditorPagerAdapter(Context context) {
        this.context = context;
    }

    /**
     * 遍历所有编辑器，对每个编辑器注入项目索引
     * <p>
     * 注：当前 MainActivity 使用的是 com.dream.highlighteditor 的 TextEditor，
     * 其本身未对接 ProjectIndexService。这里保留入口以便未来切换到
     * myopicmobile.textwarrior.FreeScrollingTextField 后启用。
     */
    public void applyProjectIndexToEditors(ProjectIndexService index) {
        if (index == null) return;
        lastProjectIndex = index;
        // 暂时不强制注入；新编辑器接入时由 EditorPagerAdapter 实现
    }

    /**
     * 对单个编辑器注入项目索引（占位）
     */
    public void applyProjectIndexToEditor(JavaEditCode editor, ProjectIndexService index) {
        // 占位：未来 FreeScrollingTextField 接入后实现
        if (editor == null || index == null) return;
        lastProjectIndex = index;
    }

    /**
     * 获取最近一次设置的项目索引
     */
    public ProjectIndexService getLastProjectIndex() {
        return lastProjectIndex;
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
