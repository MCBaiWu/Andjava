package com.andjava.ide.project;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProjectManager {

    public static final String PROJECT_DOT_FILE = ".project";

    private static final String TAG = "ProjectManager";

    private static final Pattern PKG_PATTERN = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"");

    private Context context;
    private File workspaceRoot;

    // 项目索引缓存（按项目根路径缓存）
    private final Map<String, ProjectIndexService> indexCache = new HashMap<String, ProjectIndexService>();

    public interface ProjectCreateCallback {
        void onSuccess(File projectDir);
        void onError(String message);
    }

    public ProjectManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 判断目录是否为项目根目录（基于 build.gradle 内容或 .project 文件）
     */
    public boolean isProjectDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File buildGradle = new File(dir, "build.gradle");
        if (buildGradle.exists() && buildGradle.isFile()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(buildGradle));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("apply plugin: 'com.android.application'") ||
                        line.contains("apply plugin: \"com.android.application\"")) {
                        return true;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
        }
        File dotProject = new File(dir, PROJECT_DOT_FILE);
        return dotProject.exists() && dotProject.isFile();
    }

    /**
     * 检测项目类型，返回中文字符串描述
     * @param projectDir 项目根目录
     * @return "Android项目"、"Java项目" 或 "未知项目"
     */
    public static String getProjectType(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            return "未知项目";
        }

        // 1. 检查 build.gradle 中是否包含 Android 插件
        File buildGradle = new File(projectDir, "build.gradle");
        if (buildGradle.exists() && buildGradle.isFile()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(buildGradle));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("apply plugin: 'com.android.application'") ||
                        line.contains("apply plugin: \"com.android.application\"")) {
                        return "Android项目";
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
        }

        // 2. 检查是否为 Maven Java 项目 (存在 pom.xml)
        File pomXml = new File(projectDir, "pom.xml");
        if (pomXml.exists() && pomXml.isFile()) {
            return "Java项目";
        }

        // 3. 检查是否存在典型的 Java 源代码目录结构 (src/main/java)
        File javaSrc = new File(projectDir, "src/main/java");
        if (javaSrc.exists() && javaSrc.isDirectory()) {
            return "Java项目";
        }

        // 4. 简单检查是否存在 .java 文件
        if (containsJavaFile(projectDir)) {
            return "Java项目";
        }

        // 5. 如果有 .project 文件，但不属于以上类型，仍视为某种项目
        File dotProject = new File(projectDir, PROJECT_DOT_FILE);
        if (dotProject.exists() && dotProject.isFile()) {
            return "未知项目";
        }

        return "未知项目";
    }

    private static boolean containsJavaFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsJavaFile(f)) return true;
            } else if (f.getName().endsWith(".java")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取项目的显示名称（优先从 build.gradle 的 rootProject.name 或 .project 文件读取，否则返回目录名）
     */
    public static String getProjectDisplayName(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            return "未命名";
        }

        // 1. 尝试从 settings.gradle 或 build.gradle 中读取 rootProject.name
        File settingsGradle = new File(projectDir, "settings.gradle");
        if (settingsGradle.exists() && settingsGradle.isFile()) {
            String name = extractRootProjectName(settingsGradle);
            if (name != null) return name;
        }

        // 2. 尝试从 build.gradle 中读取（某些项目只在 build.gradle 中定义）
        File buildGradle = new File(projectDir, "build.gradle");
        if (buildGradle.exists() && buildGradle.isFile()) {
            String name = extractRootProjectName(buildGradle);
            if (name != null) return name;
        }

        // 3. 尝试从 .project 文件读取
        File dotProject = new File(projectDir, PROJECT_DOT_FILE);
        if (dotProject.exists() && dotProject.isFile()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(dotProject));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("project=")) {
                        return line.substring("project=".length()).trim();
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
        }

        // 4. 回退到目录名
        return projectDir.getName();
    }

    private static String extractRootProjectName(File gradleFile) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(gradleFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("rootProject.name")) {
                    int start = line.indexOf('=') + 1;
                    if (start > 0) {
                        String value = line.substring(start).trim();
                        // 去除可能的引号
                        if (value.startsWith("'") || value.startsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * 读取文件全部内容为字符串（UTF-8）。
     * 若文件不存在或读取失败返回 null。
     */
    public static String readFileAsString(File file) {
        if (file == null || !file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        InputStream is = null;
        try {
            is = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) > 0) {
                sb.append(buf, 0, len);
            }
            return sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 从 AndroidManifest.xml 中提取 package 属性值。
     * 先尝试常规的 <manifest package="..."/>，若没有再回退到 build.gradle。
     * 失败返回 null。
     */
    public static String extractManifestPackage(File projectDir) {
        if (projectDir == null) return null;
        File manifest = new File(projectDir, "app/src/main/AndroidManifest.xml");
        if (!manifest.exists()) {
            manifest = new File(projectDir, "src/main/AndroidManifest.xml");
        }
        if (!manifest.exists()) {
            manifest = new File(projectDir, "AndroidManifest.xml");
        }
        if (manifest.exists() && manifest.isFile()) {
            String content = readFileAsString(manifest);
            if (content != null) {
                Matcher m = PKG_PATTERN.matcher(content);
                if (m.find()) {
                    String pkg = m.group(1).trim();
                    if (pkg.length() > 0) return pkg;
                }
            }
        }
        // 回退到 build.gradle 里的 applicationId / namespace
        File buildGradle = new File(projectDir, "app/build.gradle");
        if (!buildGradle.exists()) {
            buildGradle = new File(projectDir, "build.gradle");
        }
        if (buildGradle.exists() && buildGradle.isFile()) {
            String content = readFileAsString(buildGradle);
            if (content != null) {
                Pattern appId = Pattern.compile("applicationId\\s*['\\\"]\\s*([^'\\\"]+)\\s*['\\\"]");
                Matcher m = appId.matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
                Pattern ns = Pattern.compile("namespace\\s*['\\\"]\\s*([^'\\\"]+)\\s*['\\\"]");
                m = ns.matcher(content);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        }
        return null;
    }

    public File getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(File root) {
        this.workspaceRoot = root;
    }

    /**
     * 从 assets 中的 zip 模板创建项目（异步，避免阻塞 UI 线程）
     * <p>
     * 创建过程：<br>
     *  1. 校验项目名（不允许特殊字符）<br>
     *  2. 在子线程中解压模板到目标目录<br>
     *  3. 解压成功：写 .project 标记 → onSuccess<br>
     *  4. 解压失败：自动回滚（删除不完整目录）→ onError
     */
    public void createProjectFromTemplate(final File parentDir, final String projectName,
                                          final String packageName,
                                          final TemplateManager.Template template,
                                          final ProjectCreateCallback callback) {
        // 1. 校验项目名
        if (projectName == null || projectName.isEmpty()) {
            callback.onError("项目名称不能为空");
            return;
        }
        if (projectName.contains("/") || projectName.contains("\\")
                || projectName.contains("..") || projectName.contains("\0")) {
            callback.onError("项目名称包含非法字符");
            return;
        }
        if (projectName.startsWith(".")) {
            callback.onError("项目名称不能以 . 开头");
            return;
        }

        // 2. 校验父目录
        if (parentDir == null) {
            callback.onError("父目录为空");
            return;
        }
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            callback.onError("无法创建父目录: " + parentDir.getAbsolutePath());
            return;
        }
        if (!parentDir.isDirectory()) {
            callback.onError("父路径不是目录: " + parentDir.getAbsolutePath());
            return;
        }
        if (!parentDir.canWrite()) {
            callback.onError("父目录不可写: " + parentDir.getAbsolutePath());
            return;
        }

        // 3. 校验模板
        if (template == null || template.zipPath == null) {
            callback.onError("模板无效");
            return;
        }

        final File projectDir = new File(parentDir, projectName);
        if (projectDir.exists()) {
            callback.onError("项目已存在: " + projectDir.getAbsolutePath());
            return;
        }
        if (!projectDir.mkdir()) {
            callback.onError("无法创建项目目录");
            return;
        }

        // 4. 异步解压
        new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream is = null;
                    boolean success = false;
                    try {
                        is = context.getAssets().open(template.zipPath);
                        unzipAndReplace(is, projectDir, projectName, packageName);
                        createDotProjectFile(projectDir);
                        success = true;
                        callback.onSuccess(projectDir);
                    } catch (IOException e) {
                        e.printStackTrace();
                        callback.onError("解压模板失败: " + e.getMessage());
                    } catch (Throwable t) {
                        t.printStackTrace();
                        callback.onError("未知错误: " + t.getMessage());
                    } finally {
                        if (is != null) {
                            try { is.close(); } catch (IOException ignored) {}
                        }
                        // 5. 失败回滚
                        if (!success) {
                            deleteRecursive(projectDir);
                        }
                    }
                }
            }, "andjava-project-create").start();
    }

    /**
     * 递归删除文件/目录（用于失败时回滚）
     */
    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        // 二次确认，删不掉就忽略（可能只读、权限不足）
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    private void unzipAndReplace(InputStream zipStream, File destDir,
                                 String projectName, String packageName) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipStream);
        ZipEntry entry;
        byte[] copyBuffer = new byte[8192];

        while ((entry = zis.getNextEntry()) != null) {
            String entryName = entry.getName();

            String relativePath = entryName;
            // 如果包名不为空，替换路径中的 $package_name$
            if (!TextUtils.isEmpty(packageName) && relativePath.contains("$package_name$")) {
                String packagePath = packageName.replace('.', '/');
                relativePath = relativePath.replace("$package_name$", packagePath);
            }

            File targetFile = new File(destDir, relativePath);

            if (entry.isDirectory()) {
                if (!targetFile.exists()) {
                    targetFile.mkdirs();
                }
            } else {
                File parent = targetFile.getParentFile();
                if (!parent.exists()) {
                    parent.mkdirs();
                }

                if (isBinaryFile(entryName)) {
                    // 二进制文件：直接复制字节，不做字符串替换
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(targetFile);
                        int len;
                        while ((len = zis.read(copyBuffer)) > 0) {
                            fos.write(copyBuffer, 0, len);
                        }
                    } finally {
                        if (fos != null) {
                            try { fos.close(); } catch (IOException ignored) {}
                        }
                    }
                } else {
                    // 文本文件：先读出，做占位符替换后写回
                    StringBuilder content = new StringBuilder();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        content.append(new String(buffer, 0, len, "UTF-8"));
                    }

                    String fileContent = content.toString();
                    // 替换项目名称占位符
                    fileContent = fileContent.replace("$project_name$", projectName);
                    // 如果包名不为空，替换包名占位符
                    if (!TextUtils.isEmpty(packageName)) {
                        fileContent = fileContent.replace("$package_name$", packageName);
                    }

                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(targetFile);
                        fos.write(fileContent.getBytes("UTF-8"));
                    } finally {
                        if (fos != null) {
                            try { fos.close(); } catch (IOException ignored) {}
                        }
                    }
                }
            }
            zis.closeEntry();
        }
        zis.close();
    }

    /**
     * 根据文件扩展名判断是否为二进制文件。
     * 二进制文件必须按字节流复制，不能以 UTF-8 字符串读写，否则会损坏。
     */
    private static boolean isBinaryFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;

        // gradlew 脚本虽是文本，但若被平台当二进制则无害；这里以扩展名为准
        if (base.endsWith(".png") || base.endsWith(".jpg") || base.endsWith(".jpeg")
                || base.endsWith(".gif") || base.endsWith(".webp") || base.endsWith(".bmp")
                || base.endsWith(".ico") || base.endsWith(".9.png")) {
            return true;
        }
        if (base.endsWith(".jar") || base.endsWith(".aar") || base.endsWith(".zip")
                || base.endsWith(".rar") || base.endsWith(".7z") || base.endsWith(".tar")
                || base.endsWith(".gz") || base.endsWith(".so") || base.endsWith(".dll")
                || base.endsWith(".dylib")) {
            return true;
        }
        if (base.endsWith(".ttf") || base.endsWith(".otf") || base.endsWith(".woff")
                || base.endsWith(".woff2")) {
            return true;
        }
        if (base.endsWith(".mp3") || base.endsWith(".mp4") || base.endsWith(".wav")
                || base.endsWith(".ogg") || base.endsWith(".flac") || base.endsWith(".aac")) {
            return true;
        }
        if (base.endsWith(".pdf") || base.endsWith(".doc") || base.endsWith(".docx")
                || base.endsWith(".xls") || base.endsWith(".xlsx") || base.endsWith(".ppt")
                || base.endsWith(".pptx")) {
            return true;
        }
        if (base.endsWith(".keystore") || base.endsWith(".jks") || base.endsWith(".p12")
                || base.endsWith(".pk8") || base.endsWith(".x509") || base.endsWith(".pem")) {
            return true;
        }
        if (base.endsWith(".class")) {
            return true;
        }
        return false;
    }

    private void createDotProjectFile(File projectDir) {
        File dotProject = new File(projectDir, PROJECT_DOT_FILE);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(dotProject);
            fos.write(("project=" + projectDir.getName()).getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ===================== 新版：项目配置 + 索引 =====================

    /**
     * 加载（若不存在则构造）项目配置
     * <p>
     * 识别策略：
     *   1. 项目根/build.gradle 存在 -> 用 GradleConfigParser 解析
     *   2. 项目根/app/build.gradle 存在 -> 同上（app 子模块形式）
     *   3. 否则视为 JAVA_CONSOLE 项目
     */
    public ProjectConfig loadProjectConfig(File projectDir) {
        if (projectDir == null || !projectDir.isDirectory()) {
            ProjectConfig cfg = new ProjectConfig();
            cfg.setProjectType(ProjectType.JAVA_CONSOLE);
            return cfg;
        }

        File rootGradle = new File(projectDir, "build.gradle");
        File appDir = new File(projectDir, "app");
        File appGradle = new File(appDir, "build.gradle");

        if (rootGradle.exists() && rootGradle.isFile()) {
            // 单 module 形式
            return new GradleConfigParser().parse(rootGradle, projectDir, projectDir);
        }
        if (appGradle.exists() && appGradle.isFile()) {
            return new GradleConfigParser().parse(appGradle, projectDir, appDir);
        }

        // 没有 build.gradle - 推断为控制台项目
        ProjectConfig cfg = new ProjectConfig();
        cfg.setProjectRoot(projectDir);
        cfg.setAppDir(new File(projectDir, "src/main"));
        cfg.setProjectType(ProjectType.JAVA_CONSOLE);
        // 扫描 libs/ 下的 jar
        File libsDir = new File(cfg.getAppDir(), "libs");
        if (libsDir.isDirectory()) {
            File[] jars = libsDir.listFiles();
            if (jars != null) {
                for (File j : jars) {
                    if (j.getName().toLowerCase().endsWith(".jar")) {
                        cfg.addJar(j);
                    }
                }
            }
        }
        return cfg;
    }

    /**
     * 获取或创建项目索引（带缓存）
     */
    public ProjectIndexService getOrCreateIndex(File projectDir) {
        if (projectDir == null) return null;
        String key = projectDir.getAbsolutePath();
        ProjectIndexService index = indexCache.get(key);
        if (index == null) {
            ProjectConfig cfg = loadProjectConfig(projectDir);
            index = new ProjectIndexService(cfg);
            index.refresh();
            indexCache.put(key, index);
        }
        return index;
    }

    /**
     * 强制刷新项目索引
     */
    public ProjectIndexService refreshIndex(File projectDir) {
        if (projectDir == null) return null;
        String key = projectDir.getAbsolutePath();
        ProjectIndexService index = indexCache.get(key);
        if (index == null) {
            return getOrCreateIndex(projectDir);
        }
        index.refresh();
        return index;
    }

    /**
     * 清除项目索引缓存
     */
    public void clearIndex(File projectDir) {
        if (projectDir == null) return;
        indexCache.remove(projectDir.getAbsolutePath());
    }

    /**
     * 构建项目入口：返回解析后的配置 + 索引
     */
    public static class ProjectBundle {
        public final ProjectConfig config;
        public final ProjectIndexService index;
        public ProjectBundle(ProjectConfig cfg, ProjectIndexService idx) {
            this.config = cfg;
            this.index = idx;
        }
    }

    public ProjectBundle createBundle(File projectDir) {
        ProjectConfig cfg = loadProjectConfig(projectDir);
        ProjectIndexService index = new ProjectIndexService(cfg);
        index.refresh();
        if (projectDir != null) {
            indexCache.put(projectDir.getAbsolutePath(), index);
        }
        return new ProjectBundle(cfg, index);
    }
}
