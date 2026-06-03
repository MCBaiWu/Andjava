package com.andjava.ide.project;

import com.andjava.ide.build.BuildPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 res/ 目录生成 R.java
 * <p>
 * 扫描策略：
 *   res/values/strings.xml  -> R.string.xxx
 *   res/values/colors.xml   -> R.color.xxx
 *   res/values/dimens.xml   -> R.dimen.xxx
 *   res/values/styles.xml   -> R.style.xxx
 *   res/values/arrays.xml   -> R.array.xxx / R.integer / R.bool / R.id
 *   res/values/ids.xml      -> R.id.xxx
 *   res/drawable/xxx.png    -> R.drawable.xxx
 *   res/layout/xxx.xml      -> R.layout.xxx
 *   res/mipmap/xxx.png      -> R.mipmap.xxx
 *   res/menu/xxx.xml        -> R.menu.xxx
 *   res/raw/xxx             -> R.raw.xxx
 *   res/xml/xxx.xml         -> R.xml.xxx
 *   res/anim/xxx.xml        -> R.anim.xxx
 *   其他值类型以文件名 stem 视为 R.<dirname>.xxx
 */
public class RGenerator {

    private static final Pattern ATTR_PATTERN = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");

    /**
     * 生成 R.java 并写入 gen 目录
     * @return 写入的 R.java 文件，失败返回 null
     */
    public File generate(ProjectConfig cfg) {
        File genPkg = BuildPaths.genPackageDir(cfg);
        if (!genPkg.exists() && !genPkg.mkdirs()) {
            cfg.addError("无法创建 gen 目录: " + genPkg);
            return null;
        }
        File out = BuildPaths.generatedR(cfg);

        Map<String, List<String>> groups = new HashMap<String, List<String>>();
        File resDir = BuildPaths.srcMainResDir(cfg);
        if (resDir.exists() && resDir.isDirectory()) {
            collectFromResources(resDir, groups);
        }

        try {
            writeR(cfg, out, groups);
            return out;
        } catch (IOException e) {
            cfg.addError("写入 R.java 失败: " + e.getMessage());
            return null;
        }
    }

    private void collectFromResources(File resDir, Map<String, List<String>> groups) {
        File[] dirs = resDir.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            String dirName = d.getName();
            if (dirName.equals("values")) {
                collectFromValuesDir(d, groups);
            } else {
                List<String> names = groups.get(dirName);
                if (names == null) {
                    names = new ArrayList<String>();
                    groups.put(dirName, names);
                }
                File[] files = d.listFiles();
                if (files == null) continue;
                Set<String> seen = new HashSet<String>();
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String stem = stripExt(f.getName());
                    if (seen.add(stem)) {
                        names.add(stem);
                    }
                }
            }
        }
    }

    private void collectFromValuesDir(File valuesDir, Map<String, List<String>> groups) {
        File[] files = valuesDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".xml")) continue;
            String fileName = f.getName();
            String groupName;
            if (fileName.startsWith("strings")) groupName = "string";
            else if (fileName.startsWith("colors")) groupName = "color";
            else if (fileName.startsWith("dimens")) groupName = "dimen";
            else if (fileName.startsWith("styles")) groupName = "style";
            else if (fileName.startsWith("arrays")) groupName = "array";
            else if (fileName.startsWith("ids")) groupName = "id";
            else if (fileName.startsWith("integers")) groupName = "integer";
            else if (fileName.startsWith("bools")) groupName = "bool";
            else groupName = fileName.substring(0, fileName.length() - 4);

            Map<String, Integer> typeCount = countTypes(f);
            if (typeCount.containsKey("style") && typeCount.get("style").intValue() > 0) {
                groupName = "style";
            } else if (typeCount.containsKey("dimen") && typeCount.get("dimen").intValue() > 0
                    && !typeCount.containsKey("string")
                    && !typeCount.containsKey("color")) {
                groupName = "dimen";
            }

            List<String> names = groups.get(groupName);
            if (names == null) {
                names = new ArrayList<String>();
                groups.put(groupName, names);
            }
            extractNamesFromXml(f, names);
        }
    }

    private Map<String, Integer> countTypes(File xmlFile) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        try {
            InputStream is = new FileInputStream(xmlFile);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = Pattern.compile("<\\s*([a-zA-Z][\\w-]*)").matcher(line);
                    while (m.find()) {
                        String tag = m.group(1);
                        Integer c = map.get(tag);
                        if (c == null) c = Integer.valueOf(0);
                        map.put(tag, Integer.valueOf(c.intValue() + 1));
                    }
                }
            } finally {
                try { is.close(); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    private void extractNamesFromXml(File xmlFile, List<String> out) {
        try {
            InputStream is = new FileInputStream(xmlFile);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = ATTR_PATTERN.matcher(line);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                }
            } finally {
                try { is.close(); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private void writeR(ProjectConfig cfg, File out, Map<String, List<String>> groups) throws IOException {
        PrintWriter pw = new PrintWriter(out, "UTF-8");
        try {
            pw.println("// Auto-generated by AndJava RGenerator. Do not edit.");
            pw.println("package " + cfg.getJavaPackage() + ";");
            pw.println();
            pw.println("public final class R {");
            List<String> keys = new ArrayList<String>(groups.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return a.compareTo(b);
                }
            });
            int baseId = 0x7f000000;
            for (String key : keys) {
                List<String> names = groups.get(key);
                Collections.sort(names);
                pw.println("    public static final class " + key + " {");
                pw.println("        private " + key + "() {}");
                int idx = 0;
                for (String n : names) {
                    pw.println("        public static final int " + safeIdent(n) + " = " + (baseId + idx) + ";");
                    idx++;
                }
                pw.println("    }");
            }
            pw.println("}");
            pw.flush();
        } finally {
            try { pw.close(); } catch (Exception ignored) {}
        }
    }

    private String safeIdent(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isJavaIdentifierPart(c)) sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }
}
