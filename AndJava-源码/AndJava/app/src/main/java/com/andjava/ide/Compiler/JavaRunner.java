package com.andjava.ide.Compiler;


import android.content.Context;
import android.os.AsyncTask;
import android.widget.TextView;

import com.andjava.ide.project.ProjectConfig;
import com.andjava.ide.project.ProjectType;

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
        return runJavaWithConfig(context, javaCode, null);
    }

    /**
     * 基于 ProjectConfig 运行 Java 代码
     * <p>
     * ANDROID_APP  : javac -> dx -> DexClassLoader 执行
     * JAVA_CONSOLE : javac -> dx -> DexClassLoader 执行
     * <p>
     * 如果 ProjectConfig 有 outputDir (.classpath 项目的 bin 目录)，
     * 则直接使用该目录的 classes.dex 执行（不重新编译）
     */
    public static String runJavaWithConfig(Context context, String javaCode, ProjectConfig cfg) {
        // .classpath 项目: 如果 bin 目录已有 classes.dex，直接执行
        if (cfg != null && cfg.getOutputDir() != null) {
            File binDir = cfg.getOutputDir();
            File existingDex = new File(binDir, "classes.dex");
            if (existingDex.exists() && existingDex.length() > 0) {
                return JavaExecutor.execute(existingDex, binDir);
            }
        }

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

            // 2. 编译（基于 ProjectConfig）
            JavaCompiler compiler = new JavaCompiler(context);
            compiler.setCompileMode(globalMode);
            JavaCompiler.CompileResult compileResult;
            if (cfg == null) {
                compileResult = compiler.compile(sourceFile, workDir);
            } else {
                compileResult = compiler.compileWithConfig(sourceFile, workDir, cfg);
            }
            if (!compileResult.success) {
                return "编译失败：\n" + compileResult.errorMessage;
            }

            // 3. 准备 android.jar（DexConverter 需要）
            File androidJar = new File(context.getFilesDir(), JavaCompiler.LOCAL_ANDROID_JAR_NAME);
            if (!androidJar.exists()) {
                // 若不存在则通过 compiler 内部机制生成
                compiler = new JavaCompiler(context);
                compiler.compile(sourceFile, workDir);
                androidJar = new File(context.getFilesDir(), JavaCompiler.LOCAL_ANDROID_JAR_NAME);
            }

            // 4. 转换为 dex
            File dexFile = new File(workDir, "classes.dex");
            String dexError = DexConverter.convert(workDir, dexFile, androidJar);
            if (dexError != null) {
                return "DEX 转换失败：" + dexError;
            }

            // 5. 如果是 .classpath 项目，把 dex 也复制到 bin 目录
            if (cfg != null && cfg.getOutputDir() != null) {
                File binDex = new File(cfg.getOutputDir(), "classes.dex");
                if (binDex.isDirectory()) binDex.delete();
                try {
                    copyFile(dexFile, binDex);
                } catch (IOException ignored) {
                }
            }

            // 6. 执行
            return JavaExecutor.execute(dexFile, workDir);

        } catch (IOException e) {
            return "文件操作异常: " + e.getMessage();
        } catch (Exception e) {
            return "运行异常: " + e.getMessage();
        } finally {
            JavaCompiler.deleteRecursive(workDir);
        }
    }

    /**
     * 运行 .classpath 纯 Java 项目（从 bin 目录执行 dex）
     * <p>
     * 如果 bin/classes.dex 存在则直接执行，
     * 否则先编译 src 下的所有 .java 文件到 bin，再转 dex 执行
     */
    public static String runClasspathProject(final Context context, final ProjectConfig cfg) {
        if (cfg == null || cfg.getProjectRoot() == null) {
            return "错误：项目配置无效";
        }

        File srcDir = cfg.getSourceDir();
        File binDir = cfg.getOutputDir();
        if (srcDir == null) srcDir = new File(cfg.getProjectRoot(), "src");
        if (binDir == null) binDir = new File(cfg.getProjectRoot(), "bin");

        // 如果 bin/classes.dex 已存在，直接执行
        File existingDex = new File(binDir, "classes.dex");
        if (existingDex.exists() && existingDex.length() > 0) {
            return JavaExecutor.execute(existingDex, binDir);
        }

        // 否则编译 src 下所有 .java -> bin -> dex -> 执行
        try {
            // 1. 收集 src 下所有 .java 文件
            java.util.List<File> javaFiles = new java.util.ArrayList<File>();
            collectJavaFiles(srcDir, javaFiles);
            if (javaFiles.isEmpty()) {
                return "错误：src 目录下没有 Java 源文件";
            }

            // 2. 编译
            if (!binDir.exists() && !binDir.mkdirs()) {
                return "错误：无法创建 bin 目录";
            }
            JavaCompiler compiler = new JavaCompiler(context);
            compiler.setCompileMode(globalMode);
            compiler.applyProjectConfig(cfg);
            JavaCompiler.CompileResult result = compiler.compileFilesWithConfig(
                javaFiles, binDir, cfg);
            if (!result.success) {
                return "编译失败：\n" + result.errorMessage;
            }

            // 3. 转 dex
            File androidJar = compiler.getAndroidJar();
            File dexFile = new File(binDir, "classes.dex");
            String dexError = DexConverter.convert(binDir, dexFile, androidJar);
            if (dexError != null) {
                return "DEX 转换失败：" + dexError;
            }

            // 4. 执行
            return JavaExecutor.execute(dexFile, binDir);

        } catch (Exception e) {
            return "运行异常: " + e.getMessage();
        }
    }

    private static void collectJavaFiles(File dir, java.util.List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectJavaFiles(f, out);
            } else if (f.getName().toLowerCase().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            } finally {
                try { fos.close(); } catch (IOException ignored) {}
            }
        } finally {
            try { fis.close(); } catch (IOException ignored) {}
        }
    }
}
