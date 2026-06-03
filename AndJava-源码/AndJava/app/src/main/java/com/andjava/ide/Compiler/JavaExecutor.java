package com.andjava.ide.Compiler;


import android.util.Log;
import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 加载并执行 Dex 文件中的 main 方法，捕获控制台输出
 */
public class JavaExecutor {

    private static final String TAG = "JavaExecutor";
    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    /**
     * 执行 dex 文件中的 Main 类 main 方法
     * @param dexFile   classes.dex 文件
     * @param optimizedDir 优化目录 (通常为 dex 文件所在目录)
     * @return 程序输出及可能的异常信息
     */
    public static String execute(File dexFile, File optimizedDir) {
        return execute(dexFile, optimizedDir, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 执行 dex 文件，可指定超时时间
     */
    public static String execute(File dexFile, File optimizedDir, long timeoutSeconds) {
        if (!dexFile.exists()) {
            return "错误：dex 文件不存在";
        }

        DexClassLoader classLoader = new DexClassLoader(
            dexFile.getAbsolutePath(),
            optimizedDir.getAbsolutePath(),
            null,
            JavaExecutor.class.getClassLoader()
        );

        try {
            Class<?> mainClass = classLoader.loadClass("Main");
            final Method mainMethod = mainClass.getMethod("main", String[].class);

            // 捕获 System.out / err
            final PrintStream originalOut = System.out;
            final PrintStream originalErr = System.err;
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            final PrintStream captureStream = new PrintStream(baos);

            System.setOut(captureStream);
            System.setErr(captureStream);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(new Callable<String>() {
                    @Override
                    public String call() {
                        try {
                            mainMethod.invoke(null, (Object) new String[0]);
                            captureStream.flush();
                            return baos.toString("UTF-8");
                        } catch (Exception e) {
                            captureStream.flush();
                            String output = baos.toString();
                            Throwable cause = e.getCause();
                            String errMsg = (cause != null) ? cause.toString() : e.toString();
                            return output + "\n[异常] " + errMsg;
                        } finally {
                            System.setOut(originalOut);
                            System.setErr(originalErr);
                            try { captureStream.close(); } catch (Exception ignored) {}
                        }
                    }
                });

            try {
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                future.cancel(true);
                return "执行超时或中断: " + e.getMessage();
            } finally {
                executor.shutdownNow();
            }

        } catch (Exception e) {
            Log.e(TAG, "执行异常", e);
            return "执行异常: " + e.getMessage();
        }
    }
}
