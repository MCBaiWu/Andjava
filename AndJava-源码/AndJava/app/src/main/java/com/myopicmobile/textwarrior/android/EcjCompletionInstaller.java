package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.util.Log;

import com.andjava.ide.project.ProjectIndexService;

import java.lang.reflect.Field;

/**
 * 反射安装器：把 {@link AutoCompletePanel#_completionEngine} 替换为
 * {@link EcjCompletionProxy}，原引擎保留以备降级。
 *
 * 调用方可在任意时刻调用 {@link #uninstall(AutoCompletePanel)} 恢复原引擎。
 */
public final class EcjCompletionInstaller {

    private static final String TAG = "EcjCompletionInstaller";

    /** 反射尝试从 panel 拿 text field 的字段名列表 */
    private static final String[] FIELD_CANDIDATES = {
            "_textField", "mTextField", "textField", "mField", "field"
    };

    private EcjCompletionInstaller() {}

    /**
     * 把原 CompletionEngine 包装成 EcjCompletionProxy 并替换 panel._completionEngine。
     * @return 原始 CompletionEngine，便于 uninstall 时回填；若失败返回 null
     */
    public static CompletionEngine install(Context context, AutoCompletePanel panel,
                                            ProjectIndexService projectIndex) {
        if (context == null || panel == null) return null;
        CompletionEngine original = safeGetEngine(panel);
        if (original == null) {
            Log.w(TAG, "panel 中找不到原引擎，跳过安装");
            return null;
        }
        if (original instanceof EcjCompletionProxy) {
            // 已经安装过，直接同步 projectIndex 即可
            ((EcjCompletionProxy) original).setProjectIndex(projectIndex);
            return original;
        }
        FreeScrollingTextField field = extractField(panel);
        if (field == null) {
            Log.w(TAG, "无法拿到 FreeScrollingTextField，放弃安装");
            return null;
        }
        EcjCompletionProxy proxy = new EcjCompletionProxy(context, original, field);
        proxy.setProjectIndex(projectIndex);
        boolean ok = writeField(panel, original, proxy);
        if (!ok) {
            Log.w(TAG, "替换 _completionEngine 失败");
            return null;
        }
        Log.i(TAG, "ECJ 补全引擎已注入");
        return original;
    }

    /** 把原引擎换回 panel._completionEngine。 */
    public static void uninstall(AutoCompletePanel panel, CompletionEngine original) {
        if (panel == null || original == null) return;
        CompletionEngine current = safeGetEngine(panel);
        if (current == original) return;
        writeField(panel, current, original);
    }

    private static CompletionEngine safeGetEngine(AutoCompletePanel panel) {
        try {
            Field f = AutoCompletePanel.class.getDeclaredField("_completionEngine");
            f.setAccessible(true);
            Object o = f.get(panel);
            if (o instanceof CompletionEngine) {
                return (CompletionEngine) o;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射读 _completionEngine 失败", t);
        }
        return null;
    }

    private static FreeScrollingTextField extractField(AutoCompletePanel panel) {
        for (int i = 0; i < FIELD_CANDIDATES.length; i++) {
            String name = FIELD_CANDIDATES[i];
            try {
                Field f = AutoCompletePanel.class.getDeclaredField(name);
                f.setAccessible(true);
                Object o = f.get(panel);
                if (o instanceof FreeScrollingTextField) {
                    return (FreeScrollingTextField) o;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "反射读 " + name + " 失败", t);
            }
        }
        // 兜底：扫所有声明字段
        try {
            Class<?> c = panel.getClass();
            while (c != null && c != Object.class) {
                Field[] fs = c.getDeclaredFields();
                for (int i = 0; i < fs.length; i++) {
                    Field f = fs[i];
                    if (FreeScrollingTextField.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object o = f.get(panel);
                        if (o != null) return (FreeScrollingTextField) o;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            Log.w(TAG, "扫描字段失败", t);
        }
        return null;
    }

    private static boolean writeField(AutoCompletePanel panel, Object expected, Object replacement) {
        try {
            Field f = AutoCompletePanel.class.getDeclaredField("_completionEngine");
            f.setAccessible(true);
            // 安全：若中间被人改写过则放弃
            Object current = f.get(panel);
            if (expected != null && current != expected) return false;
            f.set(panel, replacement);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "反射写 _completionEngine 失败", t);
            return false;
        }
    }
}
