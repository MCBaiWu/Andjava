package com.andjava.ide.Compiler;


import android.content.Context;
import android.os.AsyncTask;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

/**
 * Java 代码编译、转换、执行一体化运行器
 * 提供与原 JavaRun 相同的公开静态方法
 */
public class JavaRunner {

    private static final String TAG = "JavaRunner";

    // 全局编译模式（影响所有编译操作）
    private static CompileMode.Mode globalMode = CompileMode.Mode.JAVA8;

    /**
     * 设置全局编译模式
     */
    public static void setCompileMode(CompileMode.Mode mode) {
        globalMode = mode;
    }

    /**
     * 获取全局编译模式
     */
    public static CompileMode.Mode getCompileMode() {
        return globalMode;
    }

    /**
     * 从文件读取 Java 代码并运行（同步）
     */
    public static String runJavaFromFile(Context context, File javaFile) {
        String code = JavaCompiler.readFileToString(javaFile);
        if (code == null) {
            return "错误：无法读取文件内容";
        }
        return runJava(context, code);
    }

    /**
     * 从文件读取 Java 代码并运行（异步，结果输出到 TextView）
     */
    public static void runJavaFromFile(final Context context, final File javaFile, final TextView outputView) {
        final String code = JavaCompiler.readFileToString(javaFile);
        if (code == null) {
            outputView.setText("错误：无法读取文件内容");
            return;
        }
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                return runJava(context, code);
            }
            @Override
            protected void onPostExecute(String result) {
                outputView.setText(result);
            }
        }.execute();
    }

    /**
     * 运行 Java 源代码字符串
     */
    public static String runJava(Context context, String javaCode) {
        // 创建工作目录
        File workDir = new File(context.getFilesDir(), "javarun_" + System.currentTimeMillis());
        if (!workDir.exists() && !workDir.mkdirs()) {
            return "错误：无法创建工作目录";
        }

        try {
            // 1. 准备源文件
            File sourceFile = new File(workDir, "Main.java");
            String processedCode = JavaCompiler.ensureMainClass(javaCode);
            JavaCompiler.writeTextToFile(sourceFile, processedCode);

            // 2. 编译
            JavaCompiler compiler = new JavaCompiler(context);
            compiler.setCompileMode(globalMode);
            JavaCompiler.CompileResult compileResult = compiler.compile(sourceFile, workDir);
            if (!compileResult.success) {
                return "编译失败：\n" + compileResult.errorMessage;
            }

            // 3. 准备 android.jar（DexConverter 需要）
            File androidJar = new File(context.getFilesDir(), JavaCompiler.LOCAL_ANDROID_JAR_NAME);
            if (!androidJar.exists()) {
                // 若不存在则通过 compiler 内部机制生成（因为 compiler 的 prepare 方法是 private，这里重新复制一次）
                // 简单处理：调用一次编译实际上已经生成了，但为了健壮，我们再确保一下。
                compiler = new JavaCompiler(context);
                compiler.compile(sourceFile, workDir); // 这会触发 prepare
                androidJar = new File(context.getFilesDir(), JavaCompiler.LOCAL_ANDROID_JAR_NAME);
            }

            // 4. 转换为 dex
            File dexFile = new File(workDir, "classes.dex");
            String dexError = DexConverter.convert(workDir, dexFile, androidJar);
            if (dexError != null) {
                return "DEX 转换失败：" + dexError;
            }

            // 5. 执行
            return JavaExecutor.execute(dexFile, workDir);

        } catch (IOException e) {
            return "文件操作异常: " + e.getMessage();
        } catch (Exception e) {
            return "运行异常: " + e.getMessage();
        } finally {
            JavaCompiler.deleteRecursive(workDir);
        }
    }
}
