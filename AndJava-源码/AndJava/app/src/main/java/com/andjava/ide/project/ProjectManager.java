package com.andjava.ide.project;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProjectManager {

    public static final String PROJECT_DOT_FILE = ".project";

    private Context context;
    private File workspaceRoot;

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

    public File getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(File root) {
        this.workspaceRoot = root;
    }

    /**
     * 从 assets 中的 zip 模板创建项目
     */
    public void createProjectFromTemplate(File parentDir, String projectName,
                                          String packageName,
                                          TemplateManager.Template template,
                                          final ProjectCreateCallback callback) {
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            callback.onError("无法创建父目录");
            return;
        }

        final File projectDir = new File(parentDir, projectName);
        if (projectDir.exists()) {
            callback.onError("项目已存在");
            return;
        }
        if (!projectDir.mkdir()) {
            callback.onError("无法创建项目目录");
            return;
        }

        InputStream is = null;
        try {
            is = context.getAssets().open(template.zipPath);
            unzipAndReplace(is, projectDir, projectName, packageName);
            createDotProjectFile(projectDir);
            callback.onSuccess(projectDir);
        } catch (IOException e) {
            e.printStackTrace();
            callback.onError("解压模板失败: " + e.getMessage());
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void unzipAndReplace(InputStream zipStream, File destDir,
                                 String projectName, String packageName) throws IOException {
        ZipInputStream zis = new ZipInputStream(zipStream);
        ZipEntry entry;

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
            zis.closeEntry();
        }
        zis.close();
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
}
