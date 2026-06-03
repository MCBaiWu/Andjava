package com.myopicmobile.textwarrior.android;

import android.content.Context;

import com.andjava.ide.project.ProjectIndexService;

import java.lang.reflect.Field;

/**
 * 一行代码切换 AutoCompletePanel 的代码补全引擎。
 *
 * 使用示例（不动原 CompletionEngine / AutoCompletePanel 任何代码）：
 * <pre>
 *   // 安装 ECJ 补全
 *   EcjCompletionInstaller.install(this, autoCompletePanel, projectIndex);
 *   // 回退到原引擎
 *   EcjCompletionInstaller.uninstall(autoCompletePanel);
 * </pre>
 *
 * 通过反射写入 AutoCompletePanel 的 _completionEngine 字段。
 */
public final class EcjCompletionInstaller {

    private EcjCompletionInstaller() {}

    /**
     * 将 ECJ 补全安装到指定 panel 上。
     *
     * @return 原始 CompletionEngine（用来回退），若安装失败返回 null
     */
    public static CompletionEngine install(Context context, AutoCompletePanel panel,
                                           ProjectIndexService projectIndex) {
        if (panel == null) return null;
        CompletionEngine original = safeGetEngine(panel);
        if (original == null) return null;
        if (original instanceof EcjCompletionProxy) {
            // 已经装过
            return original;
        }
        try {
            EcjCompletionProxy proxy = new EcjCompletionProxy(context, original, panel);
            proxy.setProjectIndex(projectIndex);
            writeField(panel, "_completionEngine", proxy);
            return original;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 卸载并恢复到原 CompletionEngine。
     */
    public static void uninstall(AutoCompletePanel panel) {
        if (panel == null) return;
        CompletionEngine cur = safeGetEngine(panel);
        if (cur instanceof EcjCompletionProxy) {
            CompletionEngine original = ((EcjCompletionProxy) cur).getDelegate();
            try {
                writeField(panel, "_completionEngine", original);
            } catch (Throwable ignored) {}
        }
    }

    private static CompletionEngine safeGetEngine(AutoCompletePanel panel) {
        try {
            Field f = AutoCompletePanel.class.getDeclaredField("_completionEngine");
            f.setAccessible(true);
            return (CompletionEngine) f.get(panel);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeField(AutoCompletePanel panel, String name, Object value)
            throws Throwable {
        Field f = AutoCompletePanel.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(panel, value);
    }
}
