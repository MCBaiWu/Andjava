package com.andjava.ide.Compiler;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

/**
 * 使用 Eclipse ECJ 编译器将 Java 源码编译为 .class 文件
 * <p>
 * 支持通过 Builder 风格 API 动态添加 JAR / AAR 依赖
 */
public class JavaCompiler {

    private static final String TAG = "JavaCompiler";
    public static final String ASSETS_ANDROID_JAR = "android.jar";
    public static final String LOCAL_ANDROID_JAR_NAME = "android.jar";
    public static final String ASSETS_LAMBDA_STUBS_JAR = "core-lambda-stubs.jar";
    public static final String LOCAL_LAMBDA_STUBS_JAR_NAME = "core-lambda-stubs.jar";


    private CompileMode.Mode mode;
    private Context context;
    private List<File> extraLibraries = new ArrayList<File>();

    // 用于缓存已解压的 AAR 中的 classes.jar，避免重复解压
    private Map<String, File> aarCache = new HashMap<String, File>();

    public JavaCompiler(Context context) {
        this.context = context.getApplicationContext();
        this.mode = CompileMode.JavaCompileMode; // 默认
    }

    public void setCompileMode(CompileMode.Mode mode) {
        this.mode = mode;
        Log.i(TAG, "编译模式切换为: " + mode);
    }

    public CompileMode.Mode  getCompileMode() {
        return mode;
    }
    /**
     * 返回本编译器持有的 android.jar 路径（可能为 null）
     */
    public File getAndroidJar() {
        return prepareAndroidJar();
    }

    /**
     * 编译多个 Java 源文件
     *
     * @param sourceFiles 源文件列表
     * @param outputDir   输出目录（存放 .class）
     * @return 编译结果
     */
    public CompileResult compileFiles(List<File> sourceFiles, File outputDir) {
        // 准备 android.jar
        File androidJar = prepareAndroidJar();
        if (androidJar == null) {
            return new CompileResult(false, "无法准备 android.jar 核心库");
        }

        // 准备 lambda stubs（仅 Java 8 模式）
        File lambdaStubsJar = null;
        if (mode == CompileMode.Mode .JAVA8) {
            lambdaStubsJar = prepareLambdaStubsJar();
            if (lambdaStubsJar == null) {
                return new CompileResult(false, "无法准备 core-lambda-stubs.jar (Java 8 必需)");
            }
        }

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(outStream);
        PrintWriter errWriter = new PrintWriter(errStream);

        try {
            List<String> args = new ArrayList<String>();
            args.add("-d");
            args.add(outputDir.getAbsolutePath());

            // classpath
            StringBuilder classpathBuilder = new StringBuilder(androidJar.getAbsolutePath());
            if (mode == CompileMode.Mode .JAVA8 && lambdaStubsJar != null) {
                classpathBuilder.append(File.pathSeparator).append(lambdaStubsJar.getAbsolutePath());
            }
            for (File lib : extraLibraries) {
                if (lib.exists()) {
                    classpathBuilder.append(File.pathSeparator).append(lib.getAbsolutePath());
                } else {
                    Log.w(TAG, "依赖库不存在，已忽略: " + lib);
                }
            }
            args.add("-classpath");
            args.add(classpathBuilder.toString());

            // Java 版本
            if (mode == CompileMode.Mode.JAVA8) {
                args.add("-source");
                args.add("1.8");
                args.add("-target");
                args.add("1.8");
            } else {
                args.add("-source");
                args.add("1.7");
                args.add("-target");
                args.add("1.7");
            }

            args.add("-encoding");
            args.add("UTF-8");
            args.add("-nowarn");
            args.add("-noExit");

            // 所有源文件
            for (File f : sourceFiles) {
                args.add(f.getAbsolutePath());
            }

            Log.i(TAG, "ECJ 参数 (多文件, 模式=" + mode + "): " + args.toString());

            boolean ok = Main.compile(
                args.toArray(new String[0]),
                outWriter,
                errWriter,
                null
            );

            outWriter.flush();
            errWriter.flush();

            if (!ok) {
                String outMsg = outStream.toString("UTF-8");
                String errMsg = errStream.toString("UTF-8");
                String fullMsg = (outMsg.isEmpty() ? "" : outMsg + "\n") + errMsg;
                if (fullMsg.trim().isEmpty()) {
                    fullMsg = "未知编译错误（无输出）";
                }
                Log.e(TAG, "编译失败: " + fullMsg);
                return new CompileResult(false, fullMsg);
            }

            return new CompileResult(true, null);
        } catch (Exception e) {
            Log.e(TAG, "编译过程异常", e);
            try {
                errWriter.flush();
                String errMsg = errStream.toString("UTF-8");
                return new CompileResult(false, e.toString() + "\n" + errMsg);
            } catch (IOException ignored) {
                return new CompileResult(false, e.toString());
            }
        } finally {
            try { outWriter.close(); } catch (Exception ignored) {}
            try { errWriter.close(); } catch (Exception ignored) {}
        }
    }
// 添加到 JavaCompiler.java 中

    public static CompilationUnitDeclaration parseSource(String source) {
        Map<String, String> settings = new HashMap<>();
        settings.put(CompilerOptions.OPTION_Source, "1.7");
        CompilerOptions options = new CompilerOptions(settings);
        ProblemReporter problemReporter = new ProblemReporter(
            DefaultErrorHandlingPolicies.proceedWithAllProblems(),
            options,
            new DefaultProblemFactory()
        );
        Parser parser = new Parser(problemReporter, options.parseLiteralExpressionsAsConstants);
        CompilationUnit unit = new CompilationUnit(
            source.toCharArray(),
            "Main.java",
            "UTF-8"
        );
        CompilationResult result = new CompilationResult(unit, 0, 0, 0);
        return parser.parse(unit, result);
    }
    // ---------- 依赖管理 API (Builder 风格) ----------

    /**
     * 开始配置额外依赖，返回一个 DependencyBuilder 实例
     */
    public DependencyBuilder dependencies() {
        return new DependencyBuilder();
    }

    /**
     * 清空已添加的所有额外依赖
     */
    public void clearDependencies() {
        extraLibraries.clear();
        // 注意：不清除 aarCache，下次使用相同 AAR 仍可复用
    }

    /**
     * 清理由本编译器解压产生的临时 classes.jar 文件
     * 通常在编译结束后调用
     */
    public void cleanupExtractedLibs() {
        for (File lib : aarCache.values()) {
            if (lib.exists()) {
                boolean deleted = lib.delete();
                if (deleted) {
                    Log.d(TAG, "已删除临时 classes.jar: " + lib);
                }
                // 尝试删除父目录（若为空）
                File parent = lib.getParentFile();
                if (parent != null && parent.exists() && parent.isDirectory()) {
                    parent.delete();
                }
            }
        }
        aarCache.clear();
    }

    /**
     * 依赖构建器，提供链式添加方法
     */
    public class DependencyBuilder {

        /**
         * 添加一个 JAR 文件
         */
        public DependencyBuilder addJar(File jarFile) {
            if (jarFile != null && jarFile.exists() && jarFile.getName().endsWith(".jar")) {
                extraLibraries.add(jarFile);
                Log.d(TAG, "添加 JAR: " + jarFile);
            } else {
                Log.w(TAG, "无效的 JAR 文件: " + jarFile);
            }
            return this;
        }

        /**
         * 添加一个 AAR 文件（自动提取内部的 classes.jar）
         */
        public DependencyBuilder addAar(File aarFile) throws IOException {
            if (aarFile != null && aarFile.exists() && aarFile.getName().endsWith(".aar")) {
                File extractedJar = extractClassesJarFromAar(aarFile);
                if (extractedJar != null) {
                    extraLibraries.add(extractedJar);
                    Log.d(TAG, "添加 AAR (提取 classes.jar): " + aarFile);
                }
            } else {
                Log.w(TAG, "无效的 AAR 文件: " + aarFile);
            }
            return this;
        }

        /**
         * 添加任意文件，根据扩展名自动判断类型
         */
        public DependencyBuilder add(File file) throws IOException {
            if (file == null || !file.exists()) {
                return this;
            }
            String name = file.getName().toLowerCase();
            if (name.endsWith(".jar")) {
                return addJar(file);
            } else if (name.endsWith(".aar")) {
                return addAar(file);
            } else {
                Log.w(TAG, "不支持的文件类型: " + file);
            }
            return this;
        }

        /**
         * 返回所属的 JavaCompiler 实例，便于链式调用后执行编译
         */
        public JavaCompiler build() {
            return JavaCompiler.this;
        }
    }

    /**
     * 从 AAR 文件中提取 classes.jar
     * 使用缓存避免重复解压同一个 AAR 文件
     */
    private File extractClassesJarFromAar(File aarFile) throws IOException {
        String aarPath = aarFile.getAbsolutePath();

        // 检查缓存
        if (aarCache.containsKey(aarPath)) {
            File cached = aarCache.get(aarPath);
            if (cached.exists()) {
                Log.d(TAG, "使用缓存的 classes.jar: " + cached);
                return cached;
            } else {
                aarCache.remove(aarPath);
            }
        }

        // 在应用私有缓存目录下创建临时文件夹
        File tempDir = new File(context.getCacheDir(), "aar_extracted_" + System.currentTimeMillis());
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("无法创建临时解压目录: " + tempDir);
        }

        ZipFile zip = null;
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            zip = new ZipFile(aarFile);
            ZipEntry entry = zip.getEntry("classes.jar");
            if (entry == null) {
                Log.w(TAG, "AAR 中未找到 classes.jar: " + aarFile);
                return null;
            }

            File classesJar = new File(tempDir, "classes.jar");
            is = zip.getInputStream(entry);
            fos = new FileOutputStream(classesJar);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }

            Log.i(TAG, "成功提取 classes.jar 到: " + classesJar);
            aarCache.put(aarPath, classesJar);
            return classesJar;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------- 编译主流程 ----------

    /**
     * 编译 Java 源文件到指定输出目录
     *
     * @param sourceFile 源文件 (Main.java)
     * @param outputDir  输出目录 (存放 .class)
     * @return CompileResult 包含成功标志和错误信息
     */
    public CompileResult compile(File sourceFile, File outputDir) {
        // 准备 android.jar
        File androidJar = prepareAndroidJar();
        if (androidJar == null) {
            return new CompileResult(false, "无法准备 android.jar 核心库");
        }

        // 准备 lambda stubs (仅 Java 8 模式)
        File lambdaStubsJar = null;
        if (mode == CompileMode.Mode.JAVA8) {
            lambdaStubsJar = prepareLambdaStubsJar();
            if (lambdaStubsJar == null) {
                return new CompileResult(false, "无法准备 core-lambda-stubs.jar (Java 8 必需)");
            }
        }

        return compileWithECJ(sourceFile, outputDir, androidJar, lambdaStubsJar);
    }

    private CompileResult compileWithECJ(File sourceFile, File outputDir,
                                         File androidJar, File lambdaStubsJar) {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(outStream);
        PrintWriter errWriter = new PrintWriter(errStream);

        try {
            List<String> args = new ArrayList<String>();
            args.add("-d");
            args.add(outputDir.getAbsolutePath());

            // 构建 classpath：基础 android.jar + lambda stubs + 额外添加的库
            StringBuilder classpathBuilder = new StringBuilder(androidJar.getAbsolutePath());
            if (mode == CompileMode.Mode.JAVA8 && lambdaStubsJar != null) {
                classpathBuilder.append(File.pathSeparator).append(lambdaStubsJar.getAbsolutePath());
            }
            for (File lib : extraLibraries) {
                if (lib.exists()) {
                    classpathBuilder.append(File.pathSeparator).append(lib.getAbsolutePath());
                } else {
                    Log.w(TAG, "依赖库不存在，已忽略: " + lib);
                }
            }
            args.add("-classpath");
            args.add(classpathBuilder.toString());

            // 设置 Java 版本
            if (mode == CompileMode.Mode.JAVA8) {
                args.add("-source");
                args.add("1.8");
                args.add("-target");
                args.add("1.8");
            } else {
                args.add("-source");
                args.add("1.7");
                args.add("-target");
                args.add("1.7");
            }

            args.add("-encoding");
            args.add("UTF-8");
            args.add("-nowarn");
            args.add("-noExit");
            args.add(sourceFile.getAbsolutePath());

            Log.i(TAG, "ECJ 参数 (模式=" + mode + "): " + args.toString());

            boolean ok = Main.compile(
                args.toArray(new String[0]),
                outWriter,
                errWriter,
                null
            );

            outWriter.flush();
            errWriter.flush();

            if (!ok) {
                String outMsg = outStream.toString("UTF-8");
                String errMsg = errStream.toString("UTF-8");
                String fullMsg = (outMsg.isEmpty() ? "" : outMsg + "\n") + errMsg;
                if (fullMsg.trim().isEmpty()) {
                    fullMsg = "未知编译错误（无输出）";
                }
                Log.e(TAG, "编译失败: " + fullMsg);
                return new CompileResult(false, fullMsg);
            }

            File classFile = new File(outputDir, "Main.class");
            if (!classFile.exists()) {
                return new CompileResult(false, "编译完成但未生成 Main.class");
            }

            Log.i(TAG, "编译成功: " + classFile.getAbsolutePath());
            return new CompileResult(true, null);

        } catch (Exception e) {
            Log.e(TAG, "编译过程异常", e);
            try {
                errWriter.flush();
                String errMsg = errStream.toString("UTF-8");
                return new CompileResult(false, e.toString() + "\n" + errMsg);
            } catch (IOException ignored) {
                return new CompileResult(false, e.toString());
            }
        } finally {
            try {
                outWriter.close();
            } catch (Exception ignored) {
            }
            try {
                errWriter.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 准备库文件 ----------
    private File prepareAndroidJar() {
        File localJar = new File(context.getFilesDir(), LOCAL_ANDROID_JAR_NAME);
        if (localJar.exists() && localJar.length() > 0) {
            Log.i(TAG, "android.jar 已存在: " + localJar.getAbsolutePath());
            return localJar;
        }
        return copyAssetToFile(ASSETS_ANDROID_JAR, localJar);
    }

    private File prepareLambdaStubsJar() {
        File localJar = new File(context.getFilesDir(), LOCAL_LAMBDA_STUBS_JAR_NAME);
        if (localJar.exists() && localJar.length() > 0) {
            Log.i(TAG, "core-lambda-stubs.jar 已存在: " + localJar.getAbsolutePath());
            return localJar;
        }
        return copyAssetToFile(ASSETS_LAMBDA_STUBS_JAR, localJar);
    }

    private File copyAssetToFile(String assetName, File destFile) {
        Log.i(TAG, "从 assets 复制 " + assetName + " ...");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getAssets().open(assetName);
            out = new FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();
            Log.i(TAG, assetName + " 复制成功，大小: " + destFile.length() + " 字节");
            return destFile;
        } catch (IOException e) {
            Log.e(TAG, "复制 " + assetName + " 失败", e);
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 编译结果包装类
     */
    public static class CompileResult {
        public final boolean success;
        public final String errorMessage;

        public CompileResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    // ---------- 静态工具方法 (供外部使用) ----------
    public static String readFileToString(File file) {
        FileInputStream fis = null;
        java.io.BufferedReader reader = null;
        StringBuilder sb = new StringBuilder();
        try {
            fis = new FileInputStream(file);
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败", e);
            return null;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (IOException ignored) {
            }
            try {
                if (fis != null) fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void writeTextToFile(File file, String content) throws IOException {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String ensureMainClass(String code) {
        if (code.contains("public class Main")) {
            return code;
        }
        return code.replaceFirst("public\\s+class\\s+\\w+", "public class Main");
    }

    public static void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}
