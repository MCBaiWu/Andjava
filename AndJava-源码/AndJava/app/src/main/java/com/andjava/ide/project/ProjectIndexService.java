package com.andjava.ide.project;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.andjava.ide.project.IndexModel.ClassInfo;
import com.andjava.ide.project.IndexModel.MemberInfo;
import com.andjava.ide.project.IndexModel.Diagnostic;

/**
 * 项目索引服务：代码补全/高亮的统一数据源
 * <p>
 * 提供：
 *   ① ProjectConfig  (Gradle 解析结果)
 *   ② Jar classpath  (libs/*.jar)
 *   ③ 类元数据       (用户源码 + 生成代码 + jar)
 *   ④ 诊断信息       (解析错误、警告)
 *   ⑤ 异步刷新 + 监听
 */
public class ProjectIndexService {

    /** 索引变化监听 */
    public interface IndexListener {
        void onIndexUpdated(ProjectIndexService index);
    }

    private final ProjectConfig config;
    private ClassSourceLoader loader;
    private final CopyOnWriteArrayList<IndexListener> listeners = new CopyOnWriteArrayList<IndexListener>();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ExecutorService refresher = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    public ProjectIndexService(ProjectConfig config) {
        this.config = config;
        this.loader = new ClassSourceLoader();
    }

    public ProjectConfig getConfig() {
        return config;
    }

    public List<File> getJarClasspath() {
        return config == null ? Collections.<File>emptyList() : config.getJarClasspath();
    }

    /**
     * 同步全量刷新（在调用线程执行）
     */
    public void refresh() {
        ClassSourceLoader newLoader = new ClassSourceLoader();
        try {
            newLoader.loadAll(config);
        } catch (Throwable t) {
            newLoader.getDiagnostics().add(
                new Diagnostic(Diagnostic.SEVERITY_ERROR, "刷新索引失败: " + t.getMessage()));
        }
        synchronized (lock) {
            this.loader = newLoader;
        }
        notifyListeners();
    }

    /**
     * 异步刷新（后台线程）
     */
    public void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refresher.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    refresh();
                } finally {
                    refreshing.set(false);
                }
            }
        });
    }

    public void addListener(IndexListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(IndexListener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (IndexListener l : listeners) {
            try {
                l.onIndexUpdated(this);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 查询接口 ----------

    private ClassSourceLoader currentLoader() {
        synchronized (lock) {
            return loader;
        }
    }

    /**
     * 获取所有已知类的全限定名
     */
    public List<String> getAllClassNames() {
        Map<String, ClassInfo> map = currentLoader().getClassMap();
        List<String> list = new ArrayList<String>(map.keySet());
        Collections.sort(list);
        return list;
    }

    /**
     * 根据前缀建议类名（用于自动 import / 完整类名补全）
     */
    public List<String> suggestClassNames(String prefix, int limit) {
        if (prefix == null) prefix = "";
        prefix = prefix.trim();
        List<String> out = new ArrayList<String>();
        Map<String, ClassInfo> map = currentLoader().getClassMap();
        for (String name : map.keySet()) {
            if (name.startsWith(prefix)) {
                out.add(name);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * 简单名补全：用户输入 "View" → 返回 "android.view.View" 等候选
     */
    public List<String> suggestBySimpleName(String simpleName, int limit) {
        if (simpleName == null || simpleName.length() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        Map<String, ClassInfo> map = currentLoader().getClassMap();
        for (Map.Entry<String, ClassInfo> e : map.entrySet()) {
            ClassInfo ci = e.getValue();
            if (simpleName.equals(ci.simpleName) || ci.fullName.endsWith("." + simpleName)) {
                out.add(e.getKey());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * 获取类的完整信息（用户类优先于 jar）
     */
    public ClassInfo getClassInfo(String fullName) {
        if (fullName == null) return null;
        Map<String, ClassInfo> map = currentLoader().getClassMap();
        return map.get(fullName);
    }

    /**
     * 获取类的所有公开成员
     */
    public List<MemberInfo> getMembers(String fullName) {
        ClassInfo ci = getClassInfo(fullName);
        if (ci == null) return Collections.emptyList();
        List<MemberInfo> out = new ArrayList<MemberInfo>();
        for (MemberInfo m : ci.members) {
            out.add(m);
        }
        return out;
    }

    /**
     * 获取所有 public static 成员（用于 R.xxx、MyClass.staticField 补全）
     */
    public List<MemberInfo> getStaticMembers(String fullName) {
        ClassInfo ci = getClassInfo(fullName);
        if (ci == null) return Collections.emptyList();
        List<MemberInfo> out = new ArrayList<MemberInfo>();
        for (MemberInfo m : ci.members) {
            if (m.isStatic && m.isPublic) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 获取 R.<subclass> 的成员（如 R.id.app_name）
     * @param className  例如 "R.id" 表示 R.id 子类
     * @return 候选名列表
     */
    public List<String> getRSubclassMembers(String subclassName) {
        if (config == null) return Collections.emptyList();
        String pkg = config.getJavaPackage();
        String fullName = pkg + "." + subclassName;
        ClassInfo ci = getClassInfo(fullName);
        if (ci == null) return Collections.emptyList();
        List<String> names = new ArrayList<String>();
        for (MemberInfo m : ci.members) {
            if (m.kind == IndexModel.KIND_FIELD) {
                names.add(m.name);
            }
        }
        return names;
    }

    public List<Diagnostic> getDiagnostics() {
        return currentLoader().getDiagnostics();
    }

    /**
     * 合并项目自身诊断和 ProjectConfig 错误
     */
    public List<Diagnostic> getAllDiagnostics() {
        List<Diagnostic> all = new ArrayList<Diagnostic>();
        all.addAll(getDiagnostics());
        if (config != null) {
            for (String w : config.getWarnings()) {
                all.add(new Diagnostic(Diagnostic.SEVERITY_WARNING, w));
            }
            for (String e : config.getErrors()) {
                all.add(new Diagnostic(Diagnostic.SEVERITY_ERROR, e));
            }
        }
        return all;
    }
}
