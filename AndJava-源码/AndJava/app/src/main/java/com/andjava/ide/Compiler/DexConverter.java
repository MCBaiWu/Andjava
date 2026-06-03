package com.andjava.ide.Compiler;


import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用 D8 将 .class 文件转换为 .dex
 */
public class DexConverter {

    private static final String TAG = "DexConverter";

    /**
     * 将指定目录下的所有 .class 文件转换为 classes.dex
     * @param classDir   包含 .class 文件的根目录
     * @param outputDex  期望输出的 dex 文件路径 (例如 .../classes.dex)
     * @param androidJar android.jar 路径 (作为 --lib)
     * @return null 表示成功，否则返回错误信息
     */
    public static String convert(File classDir, File outputDex, File androidJar) {
        if (!androidJar.exists()) {
            return "android.jar 不存在: " + androidJar.getAbsolutePath();
        }

        // 收集所有 .class 文件
        List<File> classFiles = new ArrayList<File>();
        collectClassFiles(classDir, classFiles);
        if (classFiles.isEmpty()) {
            return "未找到任何 .class 文件";
        }

        // 构建 D8 参数
        List<String> args = new ArrayList<String>();
        for (File f : classFiles) {
            args.add(f.getAbsolutePath());
        }
        args.add("--lib");
        args.add(androidJar.getAbsolutePath());
        args.add("--output");
        args.add(outputDex.getParent());  // D8 输出目录为 dex 文件所在目录
        args.add("--min-api");
        args.add("26");

        Log.i(TAG, "D8 参数: " + args.toString());

        // 重定向 System.out/err 以捕获输出
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos);
        System.setOut(captureStream);
        System.setErr(captureStream);

        try {
            com.android.tools.r8.D8.main(args.toArray(new String[0]));
            captureStream.flush();

            // D8 默认输出为 classes.dex，检查并重命名为目标文件
            File generatedDex = new File(outputDex.getParent(), "classes.dex");
            if (!generatedDex.exists()) {
                String errOutput = baos.toString("UTF-8");
                return "D8 执行后未生成 classes.dex。输出：" + errOutput;
            }
            if (!generatedDex.equals(outputDex)) {
                boolean renamed = generatedDex.renameTo(outputDex);
                if (!renamed) {
                    return "无法重命名生成的 dex 文件";
                }
            }
            Log.i(TAG, "DEX 转换成功: " + outputDex.getAbsolutePath());
            return null;
        } catch (Exception e) {
            Log.e(TAG, "D8 执行异常", e);
            try {
                captureStream.flush();
                String errOutput = baos.toString("UTF-8");
                return e.toString() + "\n" + errOutput;
            } catch (IOException ignored) {
                return e.toString();
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            try { captureStream.close(); } catch (Exception ignored) {}
        }
    }

    private static void collectClassFiles(File dir, List<File> collector) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                collectClassFiles(file, collector);
            } else if (file.getName().endsWith(".class")) {
                collector.add(file);
            }
        }
    }
}
