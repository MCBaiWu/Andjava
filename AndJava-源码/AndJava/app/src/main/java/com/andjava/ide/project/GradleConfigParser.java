package com.andjava.ide.project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 手写的 Groovy/Gradle DSL 子集解析器
 * <p>
 * 仅解析以下模式，不依赖 Groovy / Android Gradle Plugin：
 *   plugins { id 'com.android.application' }
 *   android {
 *       compileSdk 33
 *       defaultConfig { applicationId "com.xx" minSdk 21 targetSdk 33 versionCode 1 versionName "1.0" }
 *   }
 *   dependencies {
 *       implementation fileTree(dir: 'libs', include: ['*.jar'])
 *       implementation files('a.jar')
 *       implementation 'group:artifact:version'
 *   }
 * <p>
 * 全部采用字符串扫描 + 状态机实现，Java 7 语法兼容。
 */
public class GradleConfigParser {

    private static final Pattern COMMENT_LINE = Pattern.compile("//.*?$", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * 解析 build.gradle
     *
     * @param gradleFile   build.gradle 文件
     * @param projectRoot  项目根目录（用于 fileTree 解析）
     * @param appDir       app 子模块目录
     * @return 解析结果（始终返回非空）
     */
    public ProjectConfig parse(File gradleFile, File projectRoot, File appDir) {
        ProjectConfig cfg = new ProjectConfig();
        cfg.setProjectRoot(projectRoot);
        cfg.setAppDir(appDir);
        cfg.setBuildGradleFile(gradleFile);

        if (gradleFile == null || !gradleFile.exists() || !gradleFile.isFile()) {
            cfg.setProjectType(ProjectType.JAVA_CONSOLE);
            return cfg;
        }

        String raw;
        try {
            raw = readFile(gradleFile);
        } catch (IOException e) {
            cfg.setProjectType(ProjectType.JAVA_CONSOLE);
            cfg.addError("读取 build.gradle 失败: " + e.getMessage());
            return cfg;
        }

        String content = stripComments(raw);

        // 先根据 plugins 块判断项目类型
        if (content.contains("com.android.application") || content.contains("'com.android.library'")) {
            cfg.setProjectType(ProjectType.ANDROID_APP);
        } else {
            cfg.setProjectType(ProjectType.JAVA_CONSOLE);
            return cfg;
        }

        try {
            String androidBlock = extractBlock(content, "android");
            if (androidBlock != null) {
                parseAndroidBlock(androidBlock, cfg);
            } else {
                cfg.addWarning("未找到 android { } 块");
            }

            String depsBlock = extractBlock(content, "dependencies");
            if (depsBlock != null) {
                parseDependenciesBlock(depsBlock, cfg, appDir);
            }
        } catch (Exception e) {
            cfg.addError("解析 build.gradle 出错: " + e.getMessage());
        }

        return cfg;
    }

    private void parseAndroidBlock(String androidBody, ProjectConfig cfg) {
        // compileSdk VERSION
        Integer compileSdk = readInt(androidBody, "compileSdk");
        if (compileSdk != null) cfg.setCompileSdk(compileSdk.intValue());

        // defaultConfig { ... }
        String dcBlock = extractBlock(androidBody, "defaultConfig");
        if (dcBlock != null) {
            String appId = readString(dcBlock, "applicationId");
            if (appId != null) cfg.setApplicationId(appId);

            Integer minSdk = readInt(dcBlock, "minSdk");
            if (minSdk != null) cfg.setMinSdk(minSdk.intValue());

            Integer targetSdk = readInt(dcBlock, "targetSdk");
            if (targetSdk != null) cfg.setTargetSdk(targetSdk.intValue());

            Integer versionCode = readInt(dcBlock, "versionCode");
            if (versionCode != null) cfg.setVersionCode(versionCode.intValue());

            String versionName = readString(dcBlock, "versionName");
            if (versionName != null) cfg.setVersionName(versionName);
        }
    }

    private void parseDependenciesBlock(String depsBody, ProjectConfig cfg, File appDir) {
        // 把所有换行统一，便于一行行扫描
        String normalized = depsBody.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n");
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) continue;
            buffer.append(trimmed).append(' ');
        }
        String flat = buffer.toString().trim();

        // 按顶层语句切分（以 "implementation" / "api" / "compileOnly" / "runtimeOnly" / "annotationProcessor" 开头）
        String[] stmts = flat.split("(?<=\\)|\\])(?=\\s*(?:implementation|api|compileOnly|runtimeOnly|annotationProcessor))");
        for (String stmt : stmts) {
            String s = stmt.trim();
            if (s.length() == 0) continue;
            // implementation fileTree(dir: 'libs', include: ['*.jar'])
            if (s.contains("fileTree")) {
                FileTreeSpec spec = parseFileTree(s);
                if (spec != null) {
                    File dir = resolveFileTreeDir(spec, appDir);
                    if (dir != null && dir.exists() && dir.isDirectory()) {
                        File[] files = dir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar") && spec.matches(f.getName())) {
                                    cfg.addJar(f);
                                }
                            }
                        }
                    } else {
                        cfg.addWarning("fileTree 目录不存在: " + (dir == null ? "null" : dir.getAbsolutePath()));
                    }
                }
                continue;
            }
            // implementation files('a.jar', 'b.jar')
            if (s.contains("files(")) {
                List<String> paths = extractParenStringList(s, "files");
                for (String p : paths) {
                    File f = new File(appDir, p);
                    if (!f.exists()) f = new File(appDir, "libs/" + p);
                    if (f.exists()) cfg.addJar(f);
                    else cfg.addWarning("files 引用不存在: " + p);
                }
                continue;
            }
            // implementation 'group:artifact:version'  或  "group:artifact:version"
            if (s.contains("'") || s.contains("\"")) {
                String notation = firstQuoted(s);
                if (notation != null && notation.contains(":")) {
                    cfg.addMavenDependency(notation);
                }
            }
        }
    }

    // ---------- 通用解析辅助 ----------

    /**
     * 提取 keyword { ... } 块的内容（处理嵌套大括号）
     * 关键字必须是单词边界匹配
     */
    private String extractBlock(String content, String keyword) {
        int idx = findKeyword(content, keyword);
        if (idx < 0) return null;
        int braceStart = content.indexOf('{', idx);
        if (braceStart < 0) return null;
        int depth = 1;
        int pos = braceStart + 1;
        int len = content.length();
        while (pos < len && depth > 0) {
            char c = content.charAt(pos);
            if (c == '\'') {
                pos = skipSingleQuoted(content, pos + 1);
                continue;
            }
            if (c == '"') {
                pos = skipDoubleQuoted(content, pos + 1);
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        if (depth != 0) return null;
        return content.substring(braceStart + 1, pos - 1);
    }

    private int findKeyword(String content, String keyword) {
        int from = 0;
        while (true) {
            int idx = content.indexOf(keyword, from);
            if (idx < 0) return -1;
            // 单词边界
            boolean leftOk = (idx == 0) || !isIdentChar(content.charAt(idx - 1));
            boolean rightOk = (idx + keyword.length() >= content.length()) || !isIdentChar(content.charAt(idx + keyword.length()));
            if (leftOk && rightOk) return idx;
            from = idx + keyword.length();
        }
    }

    private int skipSingleQuoted(String s, int start) {
        int len = s.length();
        int i = start;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') return i + 1;
            i++;
        }
        return i;
    }

    private int skipDoubleQuoted(String s, int start) {
        int len = s.length();
        int i = start;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') return i + 1;
            i++;
        }
        return i;
    }

    private boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 在 body 中读取 "key value" 或 "key = value" 形式的值（字符串或数字）
     */
    private String readValue(String body, String key) {
        int idx = findKeyword(body, key);
        if (idx < 0) return null;
        int cur = idx + key.length();
        int len = body.length();
        // 跳过空白
        while (cur < len && Character.isWhitespace(body.charAt(cur))) cur++;
        // 跳过 =
        if (cur < len && body.charAt(cur) == '=') {
            cur++;
            while (cur < len && Character.isWhitespace(body.charAt(cur))) cur++;
        }
        if (cur >= len) return null;
        char first = body.charAt(cur);
        if (first == '\'') {
            int end = skipSingleQuoted(body, cur + 1);
            return unescape(body.substring(cur + 1, end - 1));
        }
        if (first == '"') {
            int end = skipDoubleQuoted(body, cur + 1);
            return unescape(body.substring(cur + 1, end - 1));
        }
        // 裸值
        int start = cur;
        while (cur < len) {
            char c = body.charAt(cur);
            if (c == ',' || c == '}' || c == ')' || Character.isWhitespace(c)) break;
            cur++;
        }
        return body.substring(start, cur);
    }

    private Integer readInt(String body, String key) {
        String v = readValue(body, key);
        if (v == null) return null;
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String readString(String body, String key) {
        String v = readValue(body, key);
        if (v == null) return null;
        return v.trim();
    }

    private String unescape(String s) {
        return s.replace("\\'", "'").replace("\\\"", "\"");
    }

    // ---------- fileTree ----------

    private static class FileTreeSpec {
        String dir;
        String[] includes;
        String[] excludes;

        boolean matches(String name) {
            if (includes == null || includes.length == 0) return true;
            for (String inc : includes) {
                if (globMatch(inc, name)) {
                    boolean excluded = false;
                    if (excludes != null) {
                        for (String exc : excludes) {
                            if (globMatch(exc, name)) {
                                excluded = true;
                                break;
                            }
                        }
                    }
                    if (!excluded) return true;
                }
            }
            return false;
        }
    }

    private FileTreeSpec parseFileTree(String stmt) {
        int idx = stmt.indexOf("fileTree");
        if (idx < 0) return null;
        int parenStart = stmt.indexOf('(', idx);
        if (parenStart < 0) return null;
        int parenEnd = matchParen(stmt, parenStart);
        if (parenEnd < 0) return null;
        String args = stmt.substring(parenStart + 1, parenEnd);
        FileTreeSpec spec = new FileTreeSpec();
        spec.dir = firstQuoted(extractArg(args, "dir"));
        String includeArg = extractArg(args, "include");
        if (includeArg != null) {
            spec.includes = extractAllQuoted(includeArg);
        }
        String excludeArg = extractArg(args, "exclude");
        if (excludeArg != null) {
            spec.excludes = extractAllQuoted(excludeArg);
        }
        return spec;
    }

    private File resolveFileTreeDir(FileTreeSpec spec, File appDir) {
        if (spec.dir == null) return new File(appDir, "libs");
        File d = new File(appDir, spec.dir);
        if (!d.exists()) d = new File(spec.dir);
        return d;
    }

    /**
     * 提取形如  key: 'value'  的参数值（不包含外层 key:）
     */
    private String extractArg(String args, String key) {
        int idx = findKeyword(args, key);
        if (idx < 0) return null;
        int cur = idx + key.length();
        int len = args.length();
        // 跳过 : 和空白
        while (cur < len) {
            char c = args.charAt(cur);
            if (c == ':') {
                cur++;
                break;
            }
            if (!Character.isWhitespace(c) && c != ',') {
                // 可能是裸值
                break;
            }
            cur++;
        }
        while (cur < len && Character.isWhitespace(args.charAt(cur))) cur++;
        if (cur >= len) return null;
        char first = args.charAt(cur);
        if (first == '\'') {
            int end = skipSingleQuoted(args, cur + 1);
            return args.substring(cur, end);
        }
        if (first == '"') {
            int end = skipDoubleQuoted(args, cur + 1);
            return args.substring(cur, end);
        }
        int start = cur;
        while (cur < len) {
            char c = args.charAt(cur);
            if (c == ',' || c == ')') break;
            cur++;
        }
        return args.substring(start, cur);
    }

    private List<String> extractParenStringList(String stmt, String funcName) {
        List<String> out = new ArrayList<String>();
        int idx = stmt.indexOf(funcName + "(");
        if (idx < 0) return out;
        int parenStart = stmt.indexOf('(', idx);
        int parenEnd = matchParen(stmt, parenStart);
        if (parenEnd < 0) return out;
        String inner = stmt.substring(parenStart + 1, parenEnd);
        int len = inner.length();
        int i = 0;
        while (i < len) {
            char c = inner.charAt(i);
            if (c == '\'' || c == '"') {
                int end = (c == '\'') ? skipSingleQuoted(inner, i + 1) : skipDoubleQuoted(inner, i + 1);
                out.add(inner.substring(i + 1, end - 1));
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    private String[] extractAllQuoted(String s) {
        List<String> list = new ArrayList<String>();
        int len = s.length();
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = skipSingleQuoted(s, i + 1);
                list.add(s.substring(i + 1, end - 1));
                i = end;
            } else if (c == '"') {
                int end = skipDoubleQuoted(s, i + 1);
                list.add(s.substring(i + 1, end - 1));
                i = end;
            } else {
                i++;
            }
        }
        String[] arr = new String[list.size()];
        return list.toArray(arr);
    }

    private String firstQuoted(String s) {
        int len = s.length();
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\'') {
                int end = skipSingleQuoted(s, i + 1);
                return s.substring(i + 1, end - 1);
            }
            if (c == '"') {
                int end = skipDoubleQuoted(s, i + 1);
                return s.substring(i + 1, end - 1);
            }
            i++;
        }
        return null;
    }

    private int matchParen(String s, int openIdx) {
        int depth = 1;
        int len = s.length();
        int i = openIdx + 1;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            } else if (c == '\'') {
                i = skipSingleQuoted(s, i + 1);
                continue;
            } else if (c == '"') {
                i = skipDoubleQuoted(s, i + 1);
                continue;
            }
            i++;
        }
        return -1;
    }

    private static boolean globMatch(String pattern, String name) {
        String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return name.matches(regex);
    }

    // ---------- 注释剥离 ----------

    private String stripComments(String raw) {
        String noBlock = BLOCK_COMMENT.matcher(raw).replaceAll("");
        return COMMENT_LINE.matcher(noBlock).replaceAll("");
    }

    private String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new FileReader(f));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            try { reader.close(); } catch (IOException ignored) {}
        }
        return sb.toString();
    }
}
