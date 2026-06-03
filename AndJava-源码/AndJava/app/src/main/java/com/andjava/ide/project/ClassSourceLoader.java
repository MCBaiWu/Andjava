package com.andjava.ide.project;

import com.andjava.ide.build.BuildPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 合并多源类信息：
 *   1. src/main/java 用户源码（轻量字符串扫描）
 *   2. build/gen/  生成代码（R / BuildConfig）
 *   3. app/libs/*.jar  字节码（ZipEntry 名扫描）
 * <p>
 * 每个源的加载都独立 try-catch，单个失败不会影响其他。
 * 不依赖 javaparser/qdox/asm 等重型库，避免崩溃。
 */
public class ClassSourceLoader {

    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?:public\\s+|private\\s+|protected\\s+)?(?:static\\s+|final\\s+|abstract\\s+)*" +
        "(?:class|interface|enum)\\s+([A-Z]\\w*)");
    private static final Pattern FIELD_PATTERN = Pattern.compile(
        "(public|private|protected)\\s+(static\\s+)?(final\\s+)?([\\w<>\\[\\]]+)\\s+([A-Z]\\w*|[a-z]\\w*)\\s*[=;]");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "(public|private|protected)\\s+(static\\s+)?(?:final\\s+)?([\\w<>\\[\\]]+)\\s+([a-zA-Z]\\w*)\\s*\\(");
    private static final Pattern CONSTRUCTOR_PATTERN = Pattern.compile(
        "(public|private|protected)\\s+([A-Z]\\w*)\\s*\\(");
    private static final Pattern INNER_CLASS_PATTERN = Pattern.compile(
        "public\\s+static\\s+(?:final\\s+)?class\\s+([A-Z]\\w*)");

    private final List<IndexModel.Diagnostic> diagnostics = new ArrayList<IndexModel.Diagnostic>();
    private final Map<String, IndexModel.ClassInfo> classMap = new HashMap<String, IndexModel.ClassInfo>();

    public List<IndexModel.Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public Map<String, IndexModel.ClassInfo> getClassMap() {
        return Collections.unmodifiableMap(classMap);
    }

    /**
     * 加载所有源（用户源码、生成代码、jar）
     */
    public void loadAll(ProjectConfig cfg) {
        loadUserSources(cfg);
        loadGeneratedSources(cfg);
        loadJarClasspath(cfg);
    }

    /**
     * 加载用户源码（src/main/java 下的所有 .java）
     */
    public void loadUserSources(ProjectConfig cfg) {
        File srcDir = BuildPaths.srcMainJavaDir(cfg);
        if (srcDir == null || !srcDir.exists() || !srcDir.isDirectory()) {
            return;
        }
        List<File> javaFiles = new ArrayList<File>();
        collectJavaFiles(srcDir, javaFiles);
        for (File f : javaFiles) {
            try {
                parseJavaFile(f, IndexModel.SOURCE_USER);
            } catch (Throwable t) {
                addDiagnostic(IndexModel.Diagnostic.SEVERITY_WARNING,
                    "解析用户源码失败: " + f.getName() + " - " + t.getMessage(),
                    f.getAbsolutePath());
            }
        }
    }

    /**
     * 加载 build/gen/ 下的生成代码
     */
    public void loadGeneratedSources(ProjectConfig cfg) {
        File genDir = BuildPaths.genDir(cfg);
        if (genDir == null || !genDir.exists() || !genDir.isDirectory()) {
            return;
        }
        List<File> javaFiles = new ArrayList<File>();
        collectJavaFiles(genDir, javaFiles);
        for (File f : javaFiles) {
            try {
                parseJavaFile(f, IndexModel.SOURCE_GENERATED);
            } catch (Throwable t) {
                addDiagnostic(IndexModel.Diagnostic.SEVERITY_WARNING,
                    "解析生成代码失败: " + f.getName() + " - " + t.getMessage(),
                    f.getAbsolutePath());
            }
        }
    }

    /**
     * 扫描 jar 文件，提取所有 .class 入口的全限定类名
     */
    public void loadJarClasspath(ProjectConfig cfg) {
        for (File jar : cfg.getJarClasspath()) {
            try {
                scanJarClassNames(jar);
            } catch (Throwable t) {
                addDiagnostic(IndexModel.Diagnostic.SEVERITY_WARNING,
                    "扫描 jar 失败: " + jar.getName() + " - " + t.getMessage(),
                    jar.getAbsolutePath());
            }
        }
    }

    private void collectJavaFiles(File dir, List<File> out) {
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

    private void parseJavaFile(File f, int source) {
        String content = readFile(f);
        if (content == null) return;

        String pkg = extractPackage(content);
        String fileName = f.getName();
        String simpleFromFile = fileName.substring(0, fileName.lastIndexOf('.'));

        // 提取所有顶级类
        List<String> classNames = new ArrayList<String>();
        Matcher cm = CLASS_PATTERN.matcher(content);
        while (cm.find()) {
            String name = cm.group(1);
            if (!classNames.contains(name)) {
                classNames.add(name);
            }
        }
        if (classNames.isEmpty()) {
            // 至少记录文件对应的类
            classNames.add(simpleFromFile);
        }

        for (String name : classNames) {
            String fullName = (pkg == null || pkg.length() == 0) ? name : (pkg + "." + name);
            IndexModel.ClassInfo ci = new IndexModel.ClassInfo(fullName, source);
            classMap.put(fullName, ci);
        }

        // 提取字段/方法
        if (classNames.size() == 1) {
            IndexModel.ClassInfo ci = classMap.get((pkg == null || pkg.length() == 0)
                ? classNames.get(0) : (pkg + "." + classNames.get(0)));
            if (ci != null) {
                extractMembers(content, ci);
            }
        }
    }

    private String extractPackage(String content) {
        Matcher m = PACKAGE_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private void extractMembers(String content, IndexModel.ClassInfo ci) {
        // 简单扫描（不解析块深度，逐行匹配）
        // 字段
        Matcher fm = FIELD_PATTERN.matcher(content);
        Set<String> seenFields = new HashSet<String>();
        while (fm.find()) {
            String access = fm.group(1);
            String type = fm.group(4);
            String name = fm.group(5);
            String key = access + ":" + type + ":" + name;
            if (seenFields.add(key)) {
                IndexModel.MemberInfo mi = new IndexModel.MemberInfo(name, IndexModel.KIND_FIELD);
                mi.type = type;
                mi.isPublic = "public".equals(access);
                mi.isStatic = fm.group(2) != null;
                mi.isFinal = fm.group(3) != null;
                mi.declaringClass = ci.fullName;
                ci.members.add(mi);
            }
        }
        // 方法
        Matcher mm = METHOD_PATTERN.matcher(content);
        Set<String> seenMethods = new HashSet<String>();
        while (mm.find()) {
            String access = mm.group(1);
            String retType = mm.group(3);
            String name = mm.group(4);
            // 跳过构造器
            if (name.equals(ci.simpleName)) continue;
            String key = access + ":" + retType + ":" + name + "()";
            if (seenMethods.add(key)) {
                IndexModel.MemberInfo mi = new IndexModel.MemberInfo(name, IndexModel.KIND_METHOD);
                mi.type = retType;
                mi.isPublic = "public".equals(access);
                mi.isStatic = mm.group(2) != null;
                mi.declaringClass = ci.fullName;
                ci.members.add(mi);
            }
        }
        // 构造器
        Matcher ccm = CONSTRUCTOR_PATTERN.matcher(content);
        Set<String> seenCtors = new HashSet<String>();
        while (ccm.find()) {
            String name = ccm.group(2);
            if (!name.equals(ci.simpleName)) continue;
            String key = name + "()";
            if (seenCtors.add(key)) {
                IndexModel.MemberInfo mi = new IndexModel.MemberInfo(name, IndexModel.KIND_CONSTRUCTOR);
                mi.isPublic = "public".equals(ccm.group(1));
                mi.declaringClass = ci.fullName;
                ci.members.add(mi);
            }
        }
        // 内部类
        Matcher icm = INNER_CLASS_PATTERN.matcher(content);
        Set<String> seenInner = new HashSet<String>();
        while (icm.find()) {
            String name = icm.group(1);
            String key = "inner:" + name;
            if (seenInner.add(key)) {
                IndexModel.MemberInfo mi = new IndexModel.MemberInfo(name, IndexModel.KIND_INNER_CLASS);
                mi.isPublic = true;
                mi.isStatic = true;
                mi.declaringClass = ci.fullName;
                ci.members.add(mi);
            }
        }
    }

    private void scanJarClassNames(File jar) {
        ZipInputStream zis = null;
        try {
            FileInputStream fis = new FileInputStream(jar);
            zis = new ZipInputStream(fis);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    zis.closeEntry();
                    continue;
                }
                String className = name.replace('/', '.').replace('$', '.');
                if (className.endsWith(".class")) {
                    className = className.substring(0, className.length() - 6);
                }
                if (className.contains(".")) {
                    if (!classMap.containsKey(className)) {
                        classMap.put(className, new IndexModel.ClassInfo(className, IndexModel.SOURCE_JAR));
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            addDiagnostic(IndexModel.Diagnostic.SEVERITY_WARNING,
                "无法读取 jar: " + jar.getName(), jar.getAbsolutePath());
        } finally {
            try { if (zis != null) zis.close(); } catch (IOException ignored) {}
        }
    }

    private String readFile(File f) {
        BufferedReader reader = null;
        try {
            StringBuilder sb = new StringBuilder();
            InputStream is = new FileInputStream(f);
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        }
    }

    private void addDiagnostic(int severity, String msg, String file) {
        diagnostics.add(new IndexModel.Diagnostic(severity, msg, file));
    }
}
