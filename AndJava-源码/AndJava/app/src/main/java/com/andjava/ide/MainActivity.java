package com.andjava.ide;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseExpandableListAdapter;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.andjava.ide.Compiler.JavaRunner;
import com.andjava.ide.components.ConsoleDrawer;
import com.andjava.ide.components.EdgeSwipeViewPager;
import com.andjava.ide.components.EditorPagerAdapter;
import com.andjava.ide.components.FileSidebar;
import com.andjava.ide.components.FileTabBar;
import com.andjava.ide.project.ProjectIndexService;
import com.andjava.ide.project.ProjectManager;
import com.andjava.ide.project.TemplateManager;
import com.myopicmobile.textwarrior.android.EcjCompletionInstaller;
import com.myopicmobile.textwarrior.android.EcjDiagnosticService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import android.app.ProgressDialog;
import com.andjava.ide.Compiler.CompileMode;
import com.sun.tools.javac.main.JavaCompiler;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final int REQUEST_NETWORK_PERMISSION = 1002;

    private static final String TAG = "MainActivity";

    // 视图模式
    private static final String VIEW_MODE_PROJECT = "project";
    private static final String VIEW_MODE_FILE = "file";

    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private ActionBarDrawerToggle drawerToggle;
    private FileTabBar fileTabBar;
    private EdgeSwipeViewPager viewPager;
    private EditorPagerAdapter pagerAdapter;
    private ConsoleDrawer consoleDrawer;
    private FileSidebar fileSidebar;

    private final List<OpenFile> openFiles = new ArrayList<OpenFile>();
    private File currentDirectory;
    private File currentProjectDir;      // 当前打开的项目根目录
    private String currentViewMode = VIEW_MODE_PROJECT;

    private ProjectManager projectManager;
    private TemplateManager templateManager;
    private AppStateManager stateManager;

    static class OpenFile {
        File file;
        String name;
        boolean modified;

        OpenFile(File file, String name) {
            this.file = file;
            this.name = name;
            this.modified = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        projectManager = new ProjectManager(this);
        templateManager = new TemplateManager(this);
        stateManager = AppStateManager.getInstance(this);

        // 创建主布局 DrawerLayout
        drawerLayout = new DrawerLayout(this);
        drawerLayout.setLayoutParams(new ViewGroup.LayoutParams(
                                         ViewGroup.LayoutParams.MATCH_PARENT,
                                         ViewGroup.LayoutParams.MATCH_PARENT));
        drawerLayout.setBackgroundColor(Color.parseColor("#263238"));

        FrameLayout mainContainer = new FrameLayout(this);
        mainContainer.setLayoutParams(new DrawerLayout.LayoutParams(
                                          ViewGroup.LayoutParams.MATCH_PARENT,
                                          ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout editorContainer = new LinearLayout(this);
        editorContainer.setOrientation(LinearLayout.VERTICAL);
        editorContainer.setLayoutParams(new FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT));

        // 工具栏 Toolbar
        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(Color.parseColor("#1976D2"));
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(Color.WHITE);
        toolbar.setTitle("AndJava IDE");
        toolbar.setTitleTextAppearance(this, R.style.ToolbarTitleStyle);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp2px(56)));
        editorContainer.addView(toolbar);
        setSupportActionBar(toolbar);

        // 标签栏 FileTabBar
        fileTabBar = new FileTabBar(this);
        editorContainer.addView(fileTabBar);

        // ViewPager 编辑器区域
        viewPager = new EdgeSwipeViewPager(this);
        viewPager.setLayoutParams(new LinearLayout.LayoutParams(
                                      ViewGroup.LayoutParams.MATCH_PARENT,
                                      0, 1.0f));
        pagerAdapter = new EditorPagerAdapter(this);
        pagerAdapter.setOnTextChangeListener(new EditorPagerAdapter.OnTextChangeListener() {
                @Override
                public void onTextChanged(int position, String text) {
                    if (position >= 0 && position < openFiles.size()) {
                        OpenFile file = openFiles.get(position);
                        if (!file.modified) {
                            file.modified = true;
                            pagerAdapter.updateTitle(position, file.name + " ●");
                            saveOpenFilesState();
                        }
                    }
                }
            });
        viewPager.setAdapter(pagerAdapter);
        viewPager.addOnPageChangeListener(new EdgeSwipeViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int pos, float offset, int pixels) {
                }

                @Override
                public void onPageSelected(int pos) {
                    if (pos >= 0 && pos < openFiles.size()) {
                        setViewMode(VIEW_MODE_FILE);
                        OpenFile file = openFiles.get(pos);
                        updateTitleForCurrentContext(file);
                        stateManager.saveActiveFileIndex(pos);
                    } else {
                        setViewMode(VIEW_MODE_PROJECT);
                        updateTitleForCurrentContext(null);
                    }
                    invalidateOptionsMenu();
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                }
            });
        editorContainer.addView(viewPager);
        fileTabBar.setupWithViewPager(viewPager);
        mainContainer.addView(editorContainer);

        // 控制台 ConsoleDrawer
        consoleDrawer = new ConsoleDrawer(this);
        consoleDrawer.setExpandedHeightDp(400);
        FrameLayout.LayoutParams consoleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        consoleDrawer.setLayoutParams(consoleParams);
        mainContainer.addView(consoleDrawer);
        drawerLayout.addView(mainContainer);

        // 侧边栏 FileSidebar
        fileSidebar = new FileSidebar(this);
        DrawerLayout.LayoutParams sidebarParams = new DrawerLayout.LayoutParams(
            dp2px(280), ViewGroup.LayoutParams.MATCH_PARENT);
        sidebarParams.gravity = GravityCompat.START;
        fileSidebar.setLayoutParams(sidebarParams);

        fileSidebar.setOnFileClickListener(new FileSidebar.OnFileClickListener() {
                @Override
                public void onFileClick(FileSidebar.FileItem item, int pos) {
                    if (item.isDirectory) {
                        File dir = item.file;
                        fileSidebar.setCurrentDirectory(dir);
                        boolean isProject = projectManager.isProjectDirectory(dir);
                        fileSidebar.setProjectRoot(isProject);
                    } else {
                        drawerLayout.closeDrawer(GravityCompat.START);
                        openFile(item.file);
                    }
                }
            });

        fileSidebar.setOnFileLongClickListener(new FileSidebar.OnFileLongClickListener() {
                @Override
                public void onFileLongClick(FileSidebar.FileItem item, int pos) {
                    showFileOptions(item);
                }
            });

        fileSidebar.setOnMenuActionListener(new FileSidebar.OnMenuActionListener() {
                @Override
                public void onNewFile(File currentDir) {
                    showNewFileDialog(currentDir);
                }

                @Override
                public void onNewFolder(File currentDir) {
                    showNewFolderDialog(currentDir);
                }

                @Override
                public void onRefresh() {
                    fileSidebar.refresh();
                    boolean isProject = projectManager.isProjectDirectory(currentDirectory);
                    fileSidebar.setProjectRoot(isProject);
                }
            });

        fileSidebar.setOnProjectActionListener(new FileSidebar.OnProjectActionListener() {
                @Override
                public void onOpenProject(File projectDir) {
                    openProject(projectDir);
                }

                @Override
                public void onNewProject(File parentDir) {
                    showTemplateSelectionDialog(parentDir);
                }
            });

        drawerLayout.addView(fileSidebar);

        // 配置 DrawerToggle
        drawerToggle = new ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.app_name,
            R.string.app_name
        );
        drawerLayout.addDrawerListener(drawerToggle);
        drawerToggle.syncState();
        drawerToggle.getDrawerArrowDrawable().setColor(Color.WHITE);

        setContentView(drawerLayout);

        // 权限检查
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                                              new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                              REQUEST_STORAGE_PERMISSION);
        } else {
            checkNetworkPermission();
        }
        consoleDrawer.logInfo("AndJava IDE 已启动");
        autoJavaCompilermode();
    }
    public void autoJavaCompilermode(){
        String  med  =SettingsActivity.getJavaVersion(this);
        switch (med){
            case "7":
                CompileMode.JavaCompileMode=  CompileMode.Mode.JAVA7;
                break;
            case "8":
                CompileMode.JavaCompileMode=  CompileMode.Mode.JAVA8;
                break;


        }
    }

    private void checkNetworkPermission() {
        // 检查网络权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED) {
            // 请求网络权限
            ActivityCompat.requestPermissions(this,
                                              new String[]{Manifest.permission.INTERNET},
                                              REQUEST_NETWORK_PERMISSION);
        } else {
            onPermissionGranted();
        }
    }

    private void onPermissionGranted() {
        // 恢复上次视图模式
        currentViewMode = stateManager.getViewMode();
        // 尝试恢复上次打开的项目
        String lastProjectPath = stateManager.getCurrentProjectPath();
        if (lastProjectPath != null) {
            File lastProjectDir = new File(lastProjectPath);
            if (lastProjectDir.exists() && lastProjectDir.isDirectory()) {
                openProject(lastProjectDir);
                restoreOpenFiles();
                return;
            } else {
                stateManager.clearCurrentProjectPath();
            }
        }
        initFileSystem();
    }

    private void initFileSystem() {
        File andJavaDir = new File(Environment.getExternalStorageDirectory(), "AndJava");
        if (!andJavaDir.exists()) {
            andJavaDir.mkdirs();
        }
        currentDirectory = andJavaDir;
        fileSidebar.setCurrentDirectory(currentDirectory);
        // 启动时没有打开的项目,清空侧滑栏对"已打开项目"的过滤
        if (fileSidebar != null) {
            try { fileSidebar.setOpenedProject(null); } catch (Throwable ignored) {}
        }
        boolean isProject = projectManager.isProjectDirectory(currentDirectory);
        fileSidebar.setProjectRoot(isProject);
        setViewMode(VIEW_MODE_PROJECT);
        updateTitleForCurrentContext(null);
    }

    /**
     * 打开一个已存在的项目目录。
     * 进入新项目前先清空所有已打开的 tab 与 openFiles，避免旧项目残留。
     */
    private void openProject(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            Toast.makeText(this, "无效的项目目录: " + projectDir, Toast.LENGTH_SHORT).show();
            return;
        }
        // 进入新项目：先清空旧的 tab/编辑器/控制台
        if (pagerAdapter != null) {
            try { pagerAdapter.clearAll(); } catch (Throwable ignored) {}
        }
        openFiles.clear();
        try { if (consoleDrawer != null) consoleDrawer.collapse(); } catch (Throwable ignored) {}
        try { if (consoleDrawer != null) consoleDrawer.clear(); } catch (Throwable ignored) {}

        projectManager.setWorkspaceRoot(projectDir);
        currentDirectory = projectDir;
        currentProjectDir = projectDir;
        fileSidebar.setCurrentDirectory(projectDir);
        boolean isProject = projectManager.isProjectDirectory(projectDir);
        fileSidebar.setProjectRoot(isProject);
        // 已打开项目: 通知侧滑栏过滤掉这个目录(避免重复显示)
        try { fileSidebar.setOpenedProject(projectDir); } catch (Throwable ignored) {}
        try { drawerLayout.closeDrawer(GravityCompat.START); } catch (Throwable ignored) {}

        // 构建并缓存项目索引(供代码补全/高亮使用)
        ProjectIndexService index = null;
        try {
            index = projectManager.getOrCreateIndex(projectDir);
            if (pagerAdapter != null && index != null) {
                pagerAdapter.applyProjectIndexToEditors(index);
            }
        } catch (Throwable t) {
            Log.w(TAG, "初始化项目索引失败", t);
        }

        // 给所有编辑器的自动补全面板安装 ECJ 引擎(不动原 CompletionEngine)
        try {
            if (pagerAdapter != null && index != null) {
                pagerAdapter.applyEcjCompletionToAll(this, index);
            }
        } catch (Throwable t) {
            Log.w(TAG, "安装 ECJ 补全失败", t);
        }

        // 启动后台诊断
        try {
            startBackgroundDiagnostics();
        } catch (Throwable t) {
            Log.w(TAG, "启动诊断失败", t);
        }

        // 进入项目模式视图
        setViewMode(VIEW_MODE_PROJECT);
        updateTitleForCurrentContext(null);

        consoleDrawer.logSuccess("已打开项目: " + ProjectManager.getProjectDisplayName(projectDir) +
                          " (" + ProjectManager.getProjectType(projectDir) + ")");
        Toast.makeText(this, "已打开项目: " + ProjectManager.getProjectDisplayName(projectDir), Toast.LENGTH_SHORT).show();

        stateManager.saveCurrentProjectPath(projectDir.getAbsolutePath());
    }

    private void restoreOpenFiles() {
        List<AppStateManager.OpenFileInfo> savedFiles = stateManager.loadOpenFiles();
        if (savedFiles.isEmpty()) {
            setViewMode(VIEW_MODE_PROJECT);
            updateTitleForCurrentContext(null);
            return;
        }

        for (AppStateManager.OpenFileInfo info : savedFiles) {
            File file = info.file;
            if (!file.exists()) continue;

            StringBuilder content = new StringBuilder();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException e) {
                continue;
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }

            OpenFile openFile = new OpenFile(file, file.getName());
            openFile.modified = info.modified;
            openFiles.add(openFile);
            String title = openFile.modified ? file.getName() + " ●" : file.getName();
            pagerAdapter.addEditor(file.getAbsolutePath(), title, content.toString());
        }

        int activeIndex = stateManager.getActiveFileIndex();
        if (activeIndex >= 0 && activeIndex < openFiles.size()) {
            viewPager.setCurrentItem(activeIndex, false);
        } else if (!openFiles.isEmpty()) {
            viewPager.setCurrentItem(0, false);
        }
    }

    private void saveOpenFilesState() {
        stateManager.saveOpenFiles(openFiles);
    }

    /**
     * 把所有"被修改"过的已打开文件都保存到磁盘。
     * 在编译 / 打包 / 运行 之前调用，确保磁盘内容与编辑器一致。
     */
    private int saveAllModifiedOpenFiles() {
        int saved = 0;
        if (pagerAdapter == null) return 0;
        for (int i = 0; i < openFiles.size(); i++) {
            OpenFile of = openFiles.get(i);
            if (of == null || !of.modified) continue;
            try {
                String content = pagerAdapter.getEditorContent(i);
                File parent = of.file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileWriter writer = null;
                try {
                    writer = new FileWriter(of.file);
                    writer.write(content == null ? "" : content);
                    writer.flush();
                    of.modified = false;
                    pagerAdapter.updateTitle(i, of.name);
                    saved++;
                } finally {
                    if (writer != null) {
                        try { writer.close(); } catch (IOException ignored) {}
                    }
                }
            } catch (Throwable t) {
                consoleDrawer.logError("保存失败: " + of.file.getAbsolutePath() + " - " + t.getMessage());
            }
        }
        saveOpenFilesState();
        return saved;
    }

    private void setViewMode(String mode) {
        currentViewMode = mode;
        stateManager.saveViewMode(mode);
    }

    /**
     * 根据上下文智能更新 Toolbar 标题
     * @param file 当前打开的文件，为 null 时显示项目信息
     */
    private void updateTitleForCurrentContext(OpenFile file) {
        if (file != null && VIEW_MODE_FILE.equals(currentViewMode)) {
            toolbar.setTitle(file.name);
            toolbar.setSubtitle(currentProjectDir != null ?
                                ProjectManager.getProjectDisplayName(currentProjectDir) : "");
        } else {
            if (currentProjectDir != null) {
                toolbar.setTitle(ProjectManager.getProjectDisplayName(currentProjectDir));
                toolbar.setSubtitle(ProjectManager.getProjectType(currentProjectDir));
            } else {
                toolbar.setTitle("AndJava IDE");
                toolbar.setSubtitle(null);
            }
        }
    }

    /**
     * 获取当前打开的项目根目录，若未打开项目则返回 null
     */
    public File getCurrentProjectDirectory() {
        return currentProjectDir;
    }

    /**
     * 获取当前打开项目的类型字符串，若未打开项目则返回 null
     */
    public String getCurrentProjectType() {
        if (currentProjectDir == null) return null;
        return ProjectManager.getProjectType(currentProjectDir);
    }

    /**
     * 当前打开的项目是否为 Android 项目
     */
    public boolean isCurrentAndroidProject() {
        if (currentProjectDir == null) return false;
        return ProjectManager.isAndroidJavaProject(currentProjectDir);
    }

    /**
     * 当前打开的项目是否为纯 Java 项目(.classpath)
     */
    public boolean isCurrentJavaConsoleProject() {
        if (currentProjectDir == null) return false;
        return ProjectManager.isJavaClasspathProject(currentProjectDir);
    }

    // ========== 新建项目对话框 ==========
    private void showTemplateSelectionDialog(final File parentDir) {
        final List<TemplateManager.TemplateCategory> categories = templateManager.getCategories();
        if (categories == null || categories.isEmpty()) {
            Toast.makeText(this, "未找到可用模板", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.setTitle("选择项目类型");
        dialog.setContentView(createDialogContentView(dialog.getContext()));

        ExpandableListView expandableListView = (ExpandableListView) dialog.findViewById(android.R.id.list);
        final TemplateExpandableAdapter adapter = new TemplateExpandableAdapter(categories);
        expandableListView.setAdapter(adapter);

        for (int i = 0; i < adapter.getGroupCount(); i++) {
            expandableListView.expandGroup(i);
        }

        expandableListView.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v,
                                            int groupPosition, int childPosition, long id) {
                    dialog.dismiss();
                    TemplateManager.TemplateCategory category = categories.get(groupPosition);
                    TemplateManager.Template template = category.templates.get(childPosition);
                    showProjectConfigDialog(parentDir, template);
                    return true;
                }
            });

        dialog.show();
    }

    private View createDialogContentView(Context context) {
        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp2px(16);
        rootLayout.setPadding(padding, padding, padding, padding);

        TextView titleView = new TextView(context);
        titleView.setText("请选择项目模板");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setPadding(0, 0, 0, dp2px(12));
        rootLayout.addView(titleView);

        ExpandableListView expandableListView = new ExpandableListView(context);
        expandableListView.setId(android.R.id.list);
        rootLayout.addView(expandableListView);

        return rootLayout;
    }

    private class TemplateExpandableAdapter extends BaseExpandableListAdapter {
        private List<TemplateManager.TemplateCategory> categories;

        TemplateExpandableAdapter(List<TemplateManager.TemplateCategory> categories) {
            this.categories = categories;
        }

        @Override
        public int getGroupCount() {
            return categories.size();
        }

        @Override
        public int getChildrenCount(int groupPosition) {
            return categories.get(groupPosition).templates.size();
        }

        @Override
        public Object getGroup(int groupPosition) {
            return categories.get(groupPosition);
        }

        @Override
        public Object getChild(int groupPosition, int childPosition) {
            return categories.get(groupPosition).templates.get(childPosition);
        }

        @Override
        public long getGroupId(int groupPosition) {
            return groupPosition;
        }

        @Override
        public long getChildId(int groupPosition, int childPosition) {
            return childPosition;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded,
                                 View convertView, ViewGroup parent) {
            Context ctx = parent.getContext();
            TextView textView;
            if (convertView == null) {
                textView = new TextView(ctx);
                textView.setTextSize(18);
                textView.setTextColor(Color.WHITE);
                textView.setPadding(dp2px(8), dp2px(12), dp2px(8), dp2px(12));
            } else {
                textView = (TextView) convertView;
            }
            TemplateManager.TemplateCategory category = categories.get(groupPosition);
            textView.setText("    " + category.name);
            return textView;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition,
                                 boolean isLastChild, View convertView, ViewGroup parent) {
            Context ctx = parent.getContext();
            LinearLayout layout;
            if (convertView == null) {
                layout = new LinearLayout(ctx);
                layout.setOrientation(LinearLayout.HORIZONTAL);
                layout.setPadding(dp2px(32), dp2px(8), dp2px(8), dp2px(8));

                ImageView iconView = new ImageView(ctx);
                iconView.setId(android.R.id.icon);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    dp2px(40), dp2px(40));
                iconParams.rightMargin = dp2px(12);
                layout.addView(iconView, iconParams);

                LinearLayout textLayout = new LinearLayout(ctx);
                textLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                textParams.gravity = Gravity.CENTER_VERTICAL;
                layout.addView(textLayout, textParams);

                TextView nameView = new TextView(ctx);
                nameView.setId(android.R.id.text1);
                nameView.setTextSize(16);
                nameView.setTextColor(Color.WHITE);
                textLayout.addView(nameView);

                TextView descView = new TextView(ctx);
                descView.setId(android.R.id.text2);
                descView.setTextSize(13);
                descView.setTextColor(Color.GRAY);
                textLayout.addView(descView);
            } else {
                layout = (LinearLayout) convertView;
            }

            TemplateManager.Template template = categories.get(groupPosition).templates.get(childPosition);
            ImageView iconView = (ImageView) layout.findViewById(android.R.id.icon);
            TextView nameView = (TextView) layout.findViewById(android.R.id.text1);
            TextView descView = (TextView) layout.findViewById(android.R.id.text2);

            nameView.setText(template.name);
            descView.setText(template.description);
            if (template.iconBitmap != null) {
                iconView.setImageBitmap(template.iconBitmap);
            } else {
                iconView.setImageResource(R.drawable.ic_file);
            }

            return layout;
        }

        @Override
        public boolean isChildSelectable(int groupPosition, int childPosition) {
            return true;
        }
    }

    private void showProjectConfigDialog(final File parentDir, final TemplateManager.Template template) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("配置项目");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp2px(20), dp2px(20), dp2px(20), dp2px(20));

        final EditText nameInput = new EditText(this);
        nameInput.setHint("项目名称");
        layout.addView(nameInput);

        final EditText packageInput = new EditText(this);
        packageInput.setHint("包名 (例如: com.example.myapp)");
        layout.addView(packageInput);

        final boolean isJavaTemplate = template.category != null &&
            template.category.toLowerCase().contains("java") &&
            !template.category.toLowerCase().contains("android");
        if (isJavaTemplate) {
            packageInput.setVisibility(View.GONE);
        } else {
            nameInput.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (!hasFocus) {
                            String projName = nameInput.getText().toString().trim();
                            if (!projName.isEmpty() && packageInput.getText().toString().trim().isEmpty()) {
                                String defaultPkg = "com.example." + projName.toLowerCase().replaceAll("[^a-z0-9]", "");
                                packageInput.setText(defaultPkg);
                            }
                        }
                    }
                });
        }

        builder.setView(layout);

        builder.setPositiveButton("创建", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String projectName = nameInput.getText().toString().trim();
                    String packageName = packageInput.getText().toString().trim();

                    if (projectName.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请输入项目名称", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!isJavaTemplate && packageName.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请输入包名", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!isJavaTemplate && !isValidPackageName(packageName)) {
                        Toast.makeText(MainActivity.this, "包名格式不正确", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    createProject(parentDir, projectName, packageName, template);
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private boolean isValidPackageName(String pkg) {
        if (pkg.startsWith(".") || pkg.endsWith(".")) return false;
        if (!pkg.contains(".")) return false;
        for (String part : pkg.split("\\.")) {
            if (part.isEmpty()) return false;
            if (!part.matches("[a-zA-Z][a-zA-Z0-9_]*")) return false;
        }
        return true;
    }

    private void createProject(File parentDir, String projectName, String packageName,
                               TemplateManager.Template template) {
        // 进度对话框
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("正在创建项目");
        dialog.setMessage("正在从模板 " + template.name + " 创建项目...");
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setCancelable(false);
        dialog.show();

        projectManager.createProjectFromTemplate(parentDir, projectName, packageName, template,
            new ProjectManager.ProjectCreateCallback() {
                @Override
                public void onSuccess(File projectDir) {
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();
                                consoleDrawer.logSuccess("项目创建成功: " + projectDir.getAbsolutePath());
                                fileSidebar.refresh();
                                Toast.makeText(MainActivity.this,
                                    "项目创建成功: " + projectName, Toast.LENGTH_LONG).show();
                                // 自动打开新项目
                                openProject(projectDir);
                            }
                        });
                }

                @Override
                public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();
                                consoleDrawer.logError("项目创建失败: " + message);
                                consoleDrawer.expand();
                                Toast.makeText(MainActivity.this, "创建失败: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
                }
            });
    }

    private Thread diagnosticThread;
    private volatile boolean diagnosticRunning = false;

    /**
     * 启动后台线程，每隔 2 秒对当前打开的 java 文件做 ECJ 诊断，
     * 并把结果发到主线程更新到编辑器上。
     */
    private void startBackgroundDiagnostics() {
        if (diagnosticRunning) return;
        diagnosticRunning = true;
        diagnosticThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (diagnosticRunning) {
                        try {
                            Thread.sleep(2000);
                            runDiagnosticsOnce();
                        } catch (InterruptedException e) {
                            break;
                        } catch (Throwable t) {
                            // 不让诊断线程死亡
                        }
                    }
                }
            }, "ecj-diagnostics");
        diagnosticThread.setDaemon(true);
        diagnosticThread.start();
    }

    private void stopBackgroundDiagnostics() {
        diagnosticRunning = false;
        if (diagnosticThread != null) diagnosticThread.interrupt();
        diagnosticThread = null;
    }

    private void runDiagnosticsOnce() {
        if (pagerAdapter == null || openFiles.isEmpty()) return;
        int posTemp = -1;
        try {
            posTemp = viewPager.getCurrentItem();
        } catch (Throwable ignored) {}
        if (posTemp < 0 || posTemp >= openFiles.size()) return;
        OpenFile of = openFiles.get(posTemp);
        if (of == null || of.file == null) return;
        if (!of.file.getName().endsWith(".java")) return;
        final String text;
        try {
            text = pagerAdapter.getEditorContent(posTemp);
        } catch (Throwable t) {
            return;
        }
        final int pos = posTemp;
        final java.util.List<EcjDiagnosticService.Diagnostic> list =
                EcjDiagnosticService.analyze(text, of.file.getName(), null);
        // 把结果发回主线程
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        pagerAdapter.applyDiagnosticsToEditor(pos, list);
                    } catch (Throwable t) {
                        // 静默失败
                    }
                }
            });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBackgroundDiagnostics();
    }

    // ---------- 新建文件/文件夹 ----------
    private void showNewFileDialog(final File parentDir) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("新建文件");

        final EditText input = new EditText(this);
        input.setHint("文件名 (例如: Main.java)");
        builder.setView(input);

        builder.setPositiveButton("创建", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请输入文件名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File newFile = new File(parentDir, name);
                    try {
                        if (newFile.createNewFile()) {
                            fileSidebar.refresh();
                            consoleDrawer.logSuccess("新建文件: " + name);
                        } else {
                            Toast.makeText(MainActivity.this, "文件已存在或创建失败", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        consoleDrawer.logError("创建文件失败: " + e.getMessage());
                    }
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showNewFolderDialog(final File parentDir) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("新建文件夹");

        final EditText input = new EditText(this);
        input.setHint("文件夹名");
        builder.setView(input);

        builder.setPositiveButton("创建", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请输入文件夹名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File newDir = new File(parentDir, name);
                    if (newDir.mkdir()) {
                        fileSidebar.refresh();
                        consoleDrawer.logSuccess("新建文件夹: " + name);
                    } else {
                        Toast.makeText(MainActivity.this, "文件夹已存在或创建失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ---------- 文件操作 ----------
    private void openFile(File file) {
        int existing = pagerAdapter.findPositionByPath(file.getAbsolutePath());
        if (existing != -1) {
            viewPager.setCurrentItem(existing);
            return;
        }

        StringBuilder content = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            consoleDrawer.logError("打开失败: " + e.getMessage());
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show();
            return;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }

        OpenFile openFile = new OpenFile(file, file.getName());
        openFiles.add(openFile);
        int pos = pagerAdapter.addEditor(file.getAbsolutePath(), file.getName(), content.toString());
        viewPager.setCurrentItem(pos);
        setViewMode(VIEW_MODE_FILE);
        updateTitleForCurrentContext(openFile);
        consoleDrawer.logInfo("打开 " + file.getAbsolutePath());
        saveOpenFilesState();
    }

    private void saveCurrentFile() {
        int pos = viewPager.getCurrentItem();
        if (pos < 0 || pos >= openFiles.size()) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }

        OpenFile file = openFiles.get(pos);
        String content = pagerAdapter.getEditorContent(pos);

        File parent = file.file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        FileWriter writer = null;
        try {
            writer = new FileWriter(file.file);
            writer.write(content);
            writer.flush();
            file.modified = false;
            pagerAdapter.updateTitle(pos, file.name);
            consoleDrawer.logSuccess("保存 " + file.file.getAbsolutePath());
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            saveOpenFilesState();
        } catch (IOException e) {
            consoleDrawer.logError("保存失败: " + e.getMessage());
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void undo() {
        try {
            int pos = viewPager.getCurrentItem();
            if (pos >= 0 && pos < openFiles.size()) {
                pagerAdapter.getEditor(pos).undo();
                consoleDrawer.logInfo("已撤销");
            } else {
                consoleDrawer.logWarn("没有可撤销的操作");
            }
        } catch (Throwable t) {
            consoleDrawer.logWarn("撤销失败: " + t.getMessage());
        }
    }

    private void redo() {
        try {
            int pos = viewPager.getCurrentItem();
            if (pos >= 0 && pos < openFiles.size()) {
                pagerAdapter.getEditor(pos).redo();
                consoleDrawer.logInfo("已重做");
            } else {
                consoleDrawer.logWarn("没有可重做的操作");
            }
        } catch (Throwable t) {
            consoleDrawer.logWarn("重做失败: " + t.getMessage());
        }
    }

    private void compileCode() {
        if (currentProjectDir == null) {
            consoleDrawer.logWarn("请先打开一个项目");
            consoleDrawer.expand();
            return;
        }
        int pos = viewPager.getCurrentItem();
        if (pos < 0 || pos >= openFiles.size()) {
            consoleDrawer.logWarn("没有打开的文件");
            consoleDrawer.expand();
            return;
        }
        OpenFile file = openFiles.get(pos);
        // 1) 只允许 .java 源文件编译
        if (file == null || file.file == null ||
            !file.file.getName().toLowerCase().endsWith(".java")) {
            String msg = "请在 .java 源文件上运行编译，当前文件: " +
                (file != null && file.file != null ? file.file.getName() : "(无)");
            consoleDrawer.logWarn(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            consoleDrawer.expand();
            return;
        }
        // 2) 先保存所有打开的文件
        int saved = saveAllModifiedOpenFiles();
        if (saved > 0) {
            consoleDrawer.logInfo("已保存 " + saved + " 个文件到磁盘");
        }
        // 3) Android 项目 → 构建 APK；Java 项目 → 直接 javac 运行
        if (isCurrentAndroidProject()) {
            consoleDrawer.logInfo("开始编译 Android 项目: " + currentProjectDir.getName());
            consoleDrawer.expand();
            buildApk();
        } else if (isCurrentJavaConsoleProject()) {
            consoleDrawer.logInfo("开始编译 Java 项目: " + currentProjectDir.getName());
            consoleDrawer.expand();
            runJavaConsole();
        } else {
            // 未知项目类型时按 Java 项目处理
            consoleDrawer.logInfo("开始编译 (按 Java 处理): " + currentProjectDir.getName());
            consoleDrawer.expand();
            runJavaConsole();
        }
    }

    /**
     * 纯 Java 项目编译并运行。
     * 与原 runCode 行为一致：javac -> dex -> DexClassLoader 执行。
     */
    private void runJavaConsole() {
        int pos = viewPager.getCurrentItem();
        if (pos < 0 || pos >= openFiles.size()) {
            consoleDrawer.logWarn("没有打开的文件");
            consoleDrawer.expand();
            return;
        }
        final OpenFile file = openFiles.get(pos);
        // 1) 只允许 .java 源文件运行
        if (file == null || file.file == null ||
            !file.file.getName().toLowerCase().endsWith(".java")) {
            String msg = "请在 .java 源文件上运行，当前文件: " +
                (file != null && file.file != null ? file.file.getName() : "(无)");
            consoleDrawer.logWarn(msg);
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            consoleDrawer.expand();
            return;
        }
        int saved = saveAllModifiedOpenFiles();
        if (saved > 0) {
            consoleDrawer.logInfo("已保存 " + saved + " 个文件到磁盘");
        }
        consoleDrawer.logInfo("运行: " + file.file.getAbsolutePath());
        new Thread(new Runnable() {
                @Override
                public void run() {
                    final String result = JavaRunner.runJavaFromFile(MainActivity.this, file.file);
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                consoleDrawer.log(result);
                            }
                        });
                }
            }).start();
        consoleDrawer.expand();
    }

    /**
     * 运行入口：已废弃兼容保留，内部按项目类型分发到正确流程
     */
    private void runCode() {
        if (isCurrentAndroidProject()) {
            compileCode();
        } else {
            runJavaConsole();
        }
    }

    private void exportProject() {
        Toast.makeText(this, "导出项目功能待实现", Toast.LENGTH_SHORT).show();
        consoleDrawer.logWarn("导出项目功能尚未实现");
    }

    private void showFileOptions(final FileSidebar.FileItem item) {
        new android.app.AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(new String[]{"重命名", "删除"}, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    if (w == 0) {
                        Toast.makeText(MainActivity.this, "重命名待实现", Toast.LENGTH_SHORT).show();
                    } else {
                        if (item.file.delete()) {
                            fileSidebar.refresh();
                            consoleDrawer.logSuccess("删除 " + item.name);
                        } else {
                            consoleDrawer.logWarn("无法删除 " + item.name);
                        }
                    }
                }
            }).show();
    }

    // ---------- 菜单 ----------
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_toolbar, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean hasFile = viewPager.getCurrentItem() >= 0 && !openFiles.isEmpty();
        MenuItem saveItem = menu.findItem(R.id.action_save);
        if (saveItem != null) saveItem.setVisible(hasFile);

        MenuItem undoItem = menu.findItem(R.id.action_undo);
        if (undoItem != null) undoItem.setEnabled(hasFile);

        MenuItem redoItem = menu.findItem(R.id.action_redo);
        if (redoItem != null) redoItem.setEnabled(hasFile);

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_run) {
            View anchor = findViewById(R.id.action_run);
            if (anchor == null) anchor = toolbar;
            showRunPopupMenu(anchor);
            return true;
        } else if (id == R.id.action_save) {
            saveCurrentFile();
            return true;
        } else if (id == R.id.action_undo) {
            undo();
            return true;
        } else if (id == R.id.action_redo) {
            redo();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_search) {
            Toast.makeText(this, "搜索", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_goto) {
            Toast.makeText(this, "跳转", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_refactor) {
            Toast.makeText(this, "重构", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_code_template_android) {
            insertCodeTemplate(CODE_TEMPLATE_ANDROID);
            return true;
        } else if (id == R.id.action_code_template_java) {
            insertCodeTemplate(CODE_TEMPLATE_JAVA);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showRunPopupMenu(View anchor) {
        final boolean isAndroid = isCurrentAndroidProject();
        final boolean isJava = isCurrentJavaConsoleProject();
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenu().add(0, R.id.popup_compile, 0,
                             isAndroid ? "构建 APK" : (isJava ? "编译/运行" : "编译"));
        // 纯 Android 项目本身没有"运行"概念(需安装到设备)，不显示 run
        if (!isAndroid) {
            popup.getMenu().add(0, R.id.popup_run, 1, "运行");
        }
        popup.getMenu().add(0, R.id.popup_export, 2, "导出项目");
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    int id = item.getItemId();
                    if (id == R.id.popup_compile) {
                        compileCode();
                        return true;
                    } else if (id == R.id.popup_run) {
                        runJavaConsole();
                        return true;
                    } else if (id == R.id.popup_export) {
                        exportProject();
                        return true;
                    }
                    return false;
                }
            });
        popup.show();
    }

    private void insertCodeTemplate(String template) {
        int pos = viewPager.getCurrentItem();
        if (pos >= 0 && pos < openFiles.size()) {
            pagerAdapter.getEditor(pos).paste(template);
            consoleDrawer.logInfo("已插入代码模板");
        } else {
            Toast.makeText(this, "请先打开或新建文件", Toast.LENGTH_SHORT).show();
        }
    }

    // 调用 ApkBuilder 构建 APK
    private void buildApk() {
        if (currentProjectDir == null) {
            Toast.makeText(this, "请先打开一个项目", Toast.LENGTH_SHORT).show();
            return;
        }
        // 编译前先把所有打开的文件保存到磁盘
        int saved = saveAllModifiedOpenFiles();
        if (saved > 0) {
            consoleDrawer.logInfo("已保存 " + saved + " 个文件到磁盘后再编译");
        }
        consoleDrawer.clear();
        consoleDrawer.logInfo("=== 编译开始 ===");

        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("正在构建 APK");
        dialog.setMax(100);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        ApkBuilder.buildApk(this, currentProjectDir, new ApkBuilder.BuildCallback() {
                @Override
                public void onProgress(final int percent, final String step, final String message) {
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.setProgress(percent);
                                dialog.setMessage(step + " - " + message);
                                if (message != null && message.toLowerCase().contains("error")) {
                                    consoleDrawer.logError(step + ": " + message);
                                } else if (message != null && message.toLowerCase().contains("warn")) {
                                    consoleDrawer.logWarn(step + ": " + message);
                                } else {
                                    consoleDrawer.logInfo(step + ": " + message);
                                }
                            }
                        });
                }

                @Override
                public void onSuccess(final File apkFile) {
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();
                                consoleDrawer.logSuccess("构建成功: " + apkFile.getName());
                                consoleDrawer.expand();
                                ApkBuilder.installApk(MainActivity.this, apkFile);
                            }
                        });
                }

                @Override
                public void onError(final String message) {
                    runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.dismiss();
                                consoleDrawer.logError("构建失败: " + message);
                                consoleDrawer.expand();
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        });
                }
            });
    }

    private static final String CODE_TEMPLATE_ANDROID =
    "package com.example.myapp;\n\n" +
    "import android.app.Activity;\n" +
    "import android.os.Bundle;\n\n" +
    "public class MainActivity extends Activity {\n" +
    "    @Override\n" +
    "    protected void onCreate(Bundle savedInstanceState) {\n" +
    "        super.onCreate(savedInstanceState);\n" +
    "        setContentView(R.layout.activity_main);\n" +
    "    }\n" +
    "}\n";

    private static final String CODE_TEMPLATE_JAVA =
    "public class Main {\n" +
    "    public static void main(String[] args) {\n" +
    "        System.out.println(\"Hello, AndJava!\");\n" +
    "    }\n" +
    "}\n";

    // ---------- 返回键与生命周期 ----------
    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (consoleDrawer.isExpanded()) {
            consoleDrawer.collapse();
        } else if (VIEW_MODE_FILE.equals(currentViewMode)) {
            // 如果当前是文件模式，按返回键关闭所有文件，回到项目视图
            closeAllFiles();
        } else {
            super.onBackPressed();
        }
    }

    private void closeAllFiles() {
        openFiles.clear();
        // 需要 EditorPagerAdapter 提供清除所有编辑器的接口，此处调用示意
        // 若当前适配器没有该方法，可手动移除所有视图并通知更新
        // 此处假设有 clearAllEditors 方法
        pagerAdapter.clearAllEditors();
        setViewMode(VIEW_MODE_PROJECT);
        updateTitleForCurrentContext(null);
        saveOpenFilesState();
        fileTabBar.setupWithViewPager(viewPager); // 重新关联 TabLayout
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveOpenFilesState();
        
        if (currentProjectDir != null) {
            stateManager.saveCurrentProjectPath(currentProjectDir.getAbsolutePath());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkNetworkPermission();
            } else {
                Toast.makeText(this, "需要存储权限才能正常使用", Toast.LENGTH_LONG).show();
                initFileSystem();
            }
        } else if (requestCode == REQUEST_NETWORK_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted();
            } else {
                Toast.makeText(this, "需要网络权限才能正常使用", Toast.LENGTH_LONG).show();
                onPermissionGranted();
            }
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
