package com.andjava.ide;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.core.content.FileProvider;

import com.andjava.ide.Compiler.DexConverter;
import com.andjava.ide.Compiler.JavaCompiler;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.andjava.ide.Compiler.CompileMode;
import android.provider.Settings;

public class ApkBuilder {

    private static final String TAG = "ApkBuilder";
    private static final String AAPT2_LIB_NAME = "libaapt2.so";
    private static final String KEY_PK8 = "testkey.pk8";
    private static final String KEY_X509 = "testkey.x509";

    public interface BuildCallback {
        void onProgress(int percent, String step, String message);
        void onSuccess(File apkFile);
        void onError(String message);
    }

    public static void buildApk(final Context context, final File projectDir, final BuildCallback callback) {
        new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        realBuild(context, projectDir, callback);
                    } catch (Exception e) {
                        Log.e(TAG, "构建异常", e);
                        callback.onError("构建异常: " + e.getMessage());
                    }
                }
            }).start();
    }

    private static void realBuild(Context context, File projectDir, BuildCallback callback) {
        callback.onProgress(0, "初始化", "准备构建环境...");

        // 1. 基础路径校验
        File appDir = new File(projectDir, "app/src/main");
        File manifestFile = new File(appDir, "AndroidManifest.xml");
        File resDir = new File(appDir, "res");
        File javaSrcDir = new File(appDir, "java");

        if (!manifestFile.exists()) {
            callback.onError("未找到 AndroidManifest.xml: " + manifestFile.getAbsolutePath());
            return;
        }
        if (!resDir.exists() || !resDir.isDirectory()) {
            callback.onError("未找到 res 目录: " + resDir.getAbsolutePath());
            return;
        }
        if (!javaSrcDir.exists() || !javaSrcDir.isDirectory()) {
            callback.onError("未找到 Java 源码目录: " + javaSrcDir.getAbsolutePath());
            return;
        }

        // 2. 构建中间目录
        File buildDir = new File(projectDir, "app/build");
        File intermediatesDir = new File(buildDir, "intermediates");
        File compiledResDir = new File(intermediatesDir, "res_cache");
        File rJavaDir = new File(intermediatesDir, "r_java");
        File classesDir = new File(intermediatesDir, "classes");
        File dexDir = new File(intermediatesDir, "dex_cache");
        File apkCacheDir = new File(intermediatesDir, "apk_cache");
        File outputDir = new File(buildDir, "outputs/debug");

        deleteRecursive(buildDir);
        compiledResDir.mkdirs();
        rJavaDir.mkdirs();
        classesDir.mkdirs();
        dexDir.mkdirs();
        apkCacheDir.mkdirs();
        outputDir.mkdirs();

        // 3. 初始化 Java 编译器（内部会管理 android.jar）
        JavaCompiler javaCompiler = new JavaCompiler(context);
        javaCompiler.setCompileMode(CompileMode.JavaCompileMode); // 或 JAVA8 按需

        // 4. 获取 android.jar 路径（供 D8 使用）
        File androidJar = javaCompiler.getAndroidJar();
        if (androidJar == null) {
            callback.onError("无法准备 android.jar");
            return;
        }

        // 5. 获取 aapt2 路径
        String aapt2Path = context.getApplicationInfo().nativeLibraryDir + "/" + AAPT2_LIB_NAME;
        if (!new File(aapt2Path).exists()) {
            callback.onError("找不到 aapt2: " + aapt2Path);
            return;
        }

        // 6. 编译资源
        callback.onProgress(10, "编译资源", "正在用 aapt2 编译资源文件...");
        String compileCmd = aapt2Path + " compile -o " + compiledResDir.getAbsolutePath()
            + " --dir " + resDir.getAbsolutePath();
        String compileOutput = execCommand(compileCmd);
        if (compileOutput == null || compileOutput.contains("error")) {
            callback.onError("资源编译失败:\n" + compileOutput);
            return;
        }
        Log.i(TAG, "资源编译完成:\n" + compileOutput);

        // 7. 链接资源，生成基础 APK + R.java
        callback.onProgress(25, "链接资源", "正在链接资源并生成基础 APK...");
        File baseApk = new File(apkCacheDir, "base.apk");
        String linkCmd = aapt2Path + " link -o " + baseApk.getAbsolutePath()
            + " -I " + androidJar.getAbsolutePath()
            + " -R " + compiledResDir.getAbsolutePath() + "/*.flat"
            + " --manifest " + manifestFile.getAbsolutePath()
            + " --min-sdk-version 21 --target-sdk-version 28 --version-code 1 --version-name 1.0"
            + " --java " + rJavaDir.getAbsolutePath();
        String linkOutput = execCommand(linkCmd);
        if (linkOutput == null || linkOutput.contains("error")) {
            callback.onError("资源链接失败:\n" + linkOutput);
            return;
        }
        if (!baseApk.exists()) {
            callback.onError("链接后未生成基础 APK: " + baseApk.getAbsolutePath());
            return;
        }
        Log.i(TAG, "资源链接完成:\n" + linkOutput);

        // 8. 收集所有 Java 源文件（含 R.java）
        callback.onProgress(40, "编译 Java", "收集 Java 源文件...");
        List<File> javaFiles = new ArrayList<File>();
        collectFiles(javaSrcDir, ".java", javaFiles);
        collectFiles(rJavaDir, ".java", javaFiles);   // 包含生成的 R.java
        if (javaFiles.isEmpty()) {
            callback.onError("未找到任何 Java 源文件");
            return;
        }

        // 9. 使用 JavaCompiler 编译
        callback.onProgress(50, "编译 Java", "正在编译 Java 源码...");
        JavaCompiler.CompileResult compileResult = javaCompiler.compileFiles(javaFiles, classesDir);
        if (!compileResult.success) {
            callback.onError("Java 编译失败:\n" + compileResult.errorMessage);
            return;
        }

        // 10. D8 转换 class → dex
        callback.onProgress(70, "DEX 转换", "正在将 .class 转换为 .dex...");
        File dexFile = new File(dexDir, "classes.dex");
        String dexError = DexConverter.convert(classesDir, dexFile, androidJar);
        if (dexError != null) {
            callback.onError("DEX 转换失败: " + dexError);
            return;
        }

        // 11. 打包未签名 APK（包含 dex）
        callback.onProgress(85, "打包 APK", "正在生成未签名 APK...");
        File unsignedApk = new File(outputDir, projectDir.getName() + "-unsigned.apk");
        try {
            addDexToApk(baseApk, dexFile, unsignedApk);
        } catch (IOException e) {
            callback.onError("APK 打包失败: " + e.getMessage());
            return;
        }

        // 12. 签名
        callback.onProgress(95, "签名", "正在签名 APK...");
        File pk8File = prepareSignKey(context, KEY_PK8);
        File x509File = prepareSignKey(context, KEY_X509);
        if (pk8File == null || x509File == null) {
            callback.onError("签名密钥缺失，请确保 assets 中包含 " + KEY_PK8 + " 和 " + KEY_X509);
            return;
        }

        File signedApk = new File(outputDir, projectDir.getName() + "-signed.apk");
        try {
            ApkSigner.sign(unsignedApk.getAbsolutePath(), signedApk.getAbsolutePath(),
                           pk8File.getAbsolutePath(), x509File.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "签名失败", e);
            callback.onError("签名失败: " + e.getMessage());
            return;
        }

        // 清理未签名包
        unsignedApk.delete();

        // 13. 完成
        callback.onProgress(100, "完成", "构建成功");
        callback.onSuccess(signedApk);
    }

    // ==================== 以下辅助方法保持不变（签名、DEX、命令执行等） ====================

    private static File prepareSignKey(Context context, String assetName) {
        File localFile = new File(context.getFilesDir(), assetName);
        if (localFile.exists() && localFile.length() > 0) {
            return localFile;
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getAssets().open(assetName);
            out = new FileOutputStream(localFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return localFile;
        } catch (IOException e) {
            Log.e(TAG, "复制 " + assetName + " 失败", e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static String execCommand(String command) {
        Log.d(TAG, "执行命令: " + command);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        Process process = null;
        BufferedReader reader = null;
        try {
            process = pb.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8")));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.e(TAG, "命令退出码: " + exitCode + "，输出: " + output.toString());
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "命令执行异常", e);
            return null;
        } finally {
            closeQuietly(reader);
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void collectFiles(File dir, String suffix, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFiles(f, suffix, out);
            } else if (f.getName().toLowerCase().endsWith(suffix)) {
                out.add(f);
            }
        }
    }

    private static void addDexToApk(File baseApk, File dexFile, File outApk) throws IOException {
        ZipInputStream zis = null;
        ZipOutputStream zos = null;
        FileInputStream dexFis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(baseApk));
            zos = new ZipOutputStream(new FileOutputStream(outApk));

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().equals("classes.dex")) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                }
                zis.closeEntry();
            }

            ZipEntry dexEntry = new ZipEntry("classes.dex");
            zos.putNextEntry(dexEntry);
            dexFis = new FileInputStream(dexFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = dexFis.read(buf)) > 0) {
                zos.write(buf, 0, len);
            }
            zos.closeEntry();
        } finally {
            closeQuietly(dexFis);
            closeQuietly(zis);
            closeQuietly(zos);
        }
    }

    private static void deleteRecursive(File fileOrDir) {
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

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {}
        }
    }

    // ---------- 安装 APK ----------
    public static void installApk(Context context, File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                return;
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(context,
                                             context.getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apkFile);
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }
}
