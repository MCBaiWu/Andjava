package com.andjava.ide.components;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import com.andjava.ide.project.ProjectManager;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FileSidebar extends LinearLayout {

    private LinearLayout toolbarLayout;
    private TextView titleView;
    private TextView pathView;
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<FileItem> items = new ArrayList<FileItem>();
    private File currentDirectory;
    private File openedProjectDir;       // 当前已经打开的项目目录(在侧边栏列表中隐藏)
    private OnFileClickListener fileClickListener;
    private OnFileLongClickListener fileLongClickListener;
    private OnMenuActionListener menuActionListener;
    private OnProjectActionListener projectActionListener;

    // 外部可设置的项目根目录标志，若为 null 则自动检测
    private Boolean isProjectRootOverride = null;

    public static class FileItem {
        public File file;
        public String name;
        public boolean isDirectory;
        public long size;
        public boolean isParentLink;
        public boolean isProjectAction;      // 是否为项目操作项（打开/新建）
        public int actionType;               // 0: 无, 1: 打开项目, 2: 新建项目

        public FileItem(File f) {
            this.file = f;
            this.name = f.getName();
            this.isDirectory = f.isDirectory();
            this.size = f.length();
            this.isParentLink = false;
            this.isProjectAction = false;
        }

        public FileItem(File f, String displayName, boolean isParentLink) {
            this.file = f;
            this.name = displayName;
            this.isDirectory = true;
            this.isParentLink = isParentLink;
            this.isProjectAction = false;
        }

        public static FileItem createProjectActionItem(File dir, String label, int actionType) {
            FileItem item = new FileItem(dir);
            item.name = label;
            item.isDirectory = false;
            item.isProjectAction = true;
            item.actionType = actionType;
            return item;
        }
    }

    public interface OnFileClickListener {
        void onFileClick(FileItem item, int position);
    }

    public interface OnFileLongClickListener {
        void onFileLongClick(FileItem item, int position);
    }

    public interface OnMenuActionListener {
        void onNewFile(File currentDir);
        void onNewFolder(File currentDir);
        void onRefresh();
    }

    public interface OnProjectActionListener {
        void onOpenProject(File projectDir);
        void onNewProject(File parentDir);
    }

    public FileSidebar(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(Color.parseColor("#37474F"));
        setLayoutParams(new ViewGroup.LayoutParams(
                            dp2px(280),
                            ViewGroup.LayoutParams.MATCH_PARENT));

        // ----- 顶部工具栏 -----
        toolbarLayout = new LinearLayout(getContext());
        toolbarLayout.setOrientation(HORIZONTAL);
        toolbarLayout.setBackgroundColor(Color.parseColor("#1976D2"));
        toolbarLayout.setPadding(dp2px(16), dp2px(8), dp2px(8), dp2px(8));
        toolbarLayout.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(getContext());
        titleView.setText("项目");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleView.setLayoutParams(titleParams);

        TextView menuButton = new TextView(getContext());
        menuButton.setText("⋮");
        menuButton.setTextColor(Color.WHITE);
        menuButton.setTextSize(24);
        menuButton.setPadding(dp2px(12), 0, dp2px(8), 0);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPopupMenu(v);
                }
            });

        toolbarLayout.addView(titleView);
        toolbarLayout.addView(menuButton);
        addView(toolbarLayout);

        // 路径显示
        pathView = new TextView(getContext());
        pathView.setText("/");
        pathView.setTextColor(Color.LTGRAY);
        pathView.setTextSize(12);
        pathView.setBackgroundColor(Color.parseColor("#263238"));
        pathView.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(8));
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        addView(pathView);

        // RecyclerView
        recyclerView = new RecyclerView(getContext());
        recyclerView.setLayoutParams(new LayoutParams(
                                         ViewGroup.LayoutParams.MATCH_PARENT,
                                         0, 1.0f));
        recyclerView.setBackgroundColor(Color.parseColor("#37474F"));
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);
        addView(recyclerView);
    }

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(getContext(), anchor);
        popup.getMenu().add("新建文件");
        popup.getMenu().add("新建文件夹");
        popup.getMenu().add("刷新");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    if (menuActionListener == null) return false;
                    String title = item.getTitle().toString();
                    if ("新建文件".equals(title)) {
                        menuActionListener.onNewFile(currentDirectory);
                        return true;
                    } else if ("新建文件夹".equals(title)) {
                        menuActionListener.onNewFolder(currentDirectory);
                        return true;
                    } else if ("刷新".equals(title)) {
                        menuActionListener.onRefresh();
                        return true;
                    }
                    return false;
                }
            });
        popup.show();
    }

    /**
     * 设置当前目录，会自动重置项目根标志为未覆盖状态，触发自动检测
     */
    public void setCurrentDirectory(File dir) {
        this.currentDirectory = dir;
        // 每次切换目录时，清除外部强制覆盖，让自动检测生效
        this.isProjectRootOverride = null;
        refresh();
    }

    public File getCurrentDirectory() {
        return currentDirectory;
    }

    /**
     * 外部强制设置当前目录是否为项目根目录
     * 若传入 true/false，则覆盖自动检测结果
     * 若传入 null，则恢复自动检测
     */
    public void setProjectRoot(Boolean isRoot) {
        this.isProjectRootOverride = isRoot;
        refresh();
    }

    /**
     * 设置当前已经打开的项目目录。打开后该目录会从侧滑栏列表中过滤掉(避免重复显示)。
     * 传入 null 可清除过滤。
     */
    public void setOpenedProject(File projectDir) {
        this.openedProjectDir = projectDir;
        refresh();
    }

    /**
     * 自动检测当前目录是否为项目根目录
     * 规则：
     *   - Android 项目：<dir>/app/src 目录 + <dir>/app/build.gradle 文件同时存在
     *   - 纯 Java 项目：<dir>/.classpath 文件存在
     */
    private boolean detectIsProjectRoot(File dir) {
        if (dir == null) return false;
        // 1. Android 项目: app/src 目录 + app/build.gradle 文件同时存在
        if (new File(dir, "app/src").isDirectory()
            && new File(dir, "app/build.gradle").isFile()) {
            return true;
        }
        // 2. 纯 Java 项目: .classpath 文件存在
        if (new File(dir, ".classpath").isFile()) {
            return true;
        }
        return false;
    }

    /**
     * 递归判断目录中是否含 .java 源文件
     */
    private boolean containsJavaFile(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsJavaFile(f)) return true;
            } else if (f.getName().toLowerCase().endsWith(".java")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前目录是否为 Android 项目(规则: app/src 目录 + app/build.gradle 文件同时存在)
     */
    public static boolean isAndroidJavaProject(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return new File(dir, "app/src").isDirectory()
            && new File(dir, "app/build.gradle").isFile();
    }

    /**
     * 判断当前目录是否为纯 Java 项目(规则: .classpath 文件存在)
     */
    public static boolean isJavaClasspathProject(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        return new File(dir, ".classpath").isFile();
    }

    /**
     * 判断当前目录是否允许显示“新建项目”
     * 例如目录名为 AndJava 或 AndJavaProjects
     */
    private boolean isProjectCreationRoot(File dir) {
        if (dir == null) return false;
        String name = dir.getName();
        return name.equalsIgnoreCase("AndJava") || name.equalsIgnoreCase("AndJavaProjects");
    }

    public void refresh() {
        if (currentDirectory == null) return;

        pathView.setText(currentDirectory.getAbsolutePath());
        items.clear();

        // 1. 添加返回上级 ".." （始终第一个，只要父目录存在且可读）
        File parent = currentDirectory.getParentFile();
        if (parent != null && parent.canRead()) {
            items.add(new FileItem(parent, "..", true));
        }

        // 2. 确定是否为项目根目录（优先外部覆盖，否则自动检测）
        boolean isRoot;
        if (isProjectRootOverride != null) {
            isRoot = isProjectRootOverride;
        } else {
            isRoot = detectIsProjectRoot(currentDirectory);
        }

        // 3. 只在 AndJava/AndJavaProjects 根目录显示"新建项目"项。
        //    已经在编辑器中打开的项目不再显示"打开项目"项，
        //    避免在侧滑栏根目录里重复出现入口。
        if (isProjectCreationRoot(currentDirectory)) {
            items.add(FileItem.createProjectActionItem(currentDirectory, "新建项目", 2));
        }

        // 4. 列出目录中的真实文件和文件夹
        File[] files = currentDirectory.listFiles();
        if (files != null) {
            List<FileItem> dirs = new ArrayList<FileItem>();
            List<FileItem> fls = new ArrayList<FileItem>();

            for (File f : files) {
                if (!f.isHidden()) {
                    // 过滤掉已经打开的项目目录(避免重复显示)
                    if (openedProjectDir != null && isSamePath(f, openedProjectDir)) {
                        continue;
                    }
                    // 过滤掉 build 输出目录
                    if (f.isDirectory() && "build".equals(f.getName())) {
                        continue;
                    }
                    // 过滤掉 bin 输出目录（.classpath 项目的编译输出）
                    if (f.isDirectory() && "bin".equals(f.getName())) {
                        continue;
                    }
                    FileItem item = new FileItem(f);
                    if (f.isDirectory()) {
                        dirs.add(item);
                    } else {
                        fls.add(item);
                    }
                }
            }

            Collections.sort(dirs, new Comparator<FileItem>() {
                    @Override
                    public int compare(FileItem a, FileItem b) {
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });

            Collections.sort(fls, new Comparator<FileItem>() {
                    @Override
                    public int compare(FileItem a, FileItem b) {
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });

            items.addAll(dirs);
            items.addAll(fls);
        }

        adapter.notifyDataSetChanged();
    }

    public void navigateUp() {
        if (currentDirectory != null) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && parent.canRead()) {
                setCurrentDirectory(parent);
            }
        }
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.fileClickListener = listener;
    }

    public void setOnFileLongClickListener(OnFileLongClickListener listener) {
        this.fileLongClickListener = listener;
    }

    public void setOnMenuActionListener(OnMenuActionListener listener) {
        this.menuActionListener = listener;
    }

    public void setOnProjectActionListener(OnProjectActionListener listener) {
        this.projectActionListener = listener;
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout layout = new LinearLayout(getContext());
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
            layout.setLayoutParams(new RecyclerView.LayoutParams(
                                       ViewGroup.LayoutParams.MATCH_PARENT,
                                       ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView icon = new TextView(getContext());
            icon.setTextSize(20);
            icon.setLayoutParams(new LinearLayout.LayoutParams(
                                     dp2px(32), dp2px(32)));
            icon.setTag("icon");
            layout.addView(icon);

            TextView name = new TextView(getContext());
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setPadding(dp2px(12), 0, 0, 0);
            name.setTag("name");
            layout.addView(name);

            return new ViewHolder(layout);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, final int position) {
            final FileItem item = items.get(position);

            TextView icon = (TextView) holder.itemView.findViewWithTag("icon");
            TextView name = (TextView) holder.itemView.findViewWithTag("name");

            if (item.isParentLink) {
                icon.setText("📂");
                name.setText("..");
            } else if (item.isProjectAction) {
                icon.setText("📄");
                name.setText(item.name);
            } else {
                icon.setText(item.isDirectory ? "📁" : "📄");
                name.setText(item.name);
            }

            holder.itemView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (item.isParentLink) {
                            navigateUp();
                        } else if (item.isProjectAction) {
                            if (projectActionListener != null) {
                                if (item.actionType == 1) {
                                    projectActionListener.onOpenProject(currentDirectory);
                                } else if (item.actionType == 2) {
                                    projectActionListener.onNewProject(currentDirectory);
                                }
                            }
                        } else {
                            if (fileClickListener != null) {
                                fileClickListener.onFileClick(item, position);
                            }
                        }
                    }
                });

            holder.itemView.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (item.isProjectAction || item.isParentLink) {
                            return false;
                        }
                        if (fileLongClickListener != null) {
                            fileLongClickListener.onFileLongClick(item, position);
                            return true;
                        }
                        return false;
                    }
                });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View itemView) {
                super(itemView);
            }
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /**
     * 比较两个 File 路径是否相同(忽略大小写、规范化路径)
     */
    private static boolean isSamePath(File a, File b) {
        if (a == null || b == null) return false;
        try {
            String pa = a.getCanonicalPath();
            String pb = b.getCanonicalPath();
            return pa.equalsIgnoreCase(pb);
        } catch (Throwable t) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
    }
}
