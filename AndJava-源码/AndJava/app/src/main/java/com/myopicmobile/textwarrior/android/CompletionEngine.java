package com.myopicmobile.textwarrior.android;

import android.content.Context;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.myopicmobile.textwarrior.common.DocumentProvider;
import com.myopicmobile.textwarrior.common.Language;
import com.andjava.ide.project.ProjectIndexService;
import com.andjava.ide.project.IndexModel;
import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaParameter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 容错增强版代码补全引擎 (Java 7 语法)
 * 特点：
 * 1. 多层解析降级，最大限度获得 AST 上下文
 * 2. 完全移除正则补全逻辑，依赖 AST + QDox + ASM
 * 3. this. 补全稳定可用
 * 4. 支持简单链式调用的类型推断
 */
public class CompletionEngine {
    private static final String LOG_PATH = "/storage/emulated/0/andjava/andjava.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Context context;
    private FreeScrollingTextField textField;
    private Language language;

    // 全局缓存
    private Map<String, ClassInfo> classInfoCache = new HashMap<String, ClassInfo>();
    private Map<String, String> simpleToFullName = new HashMap<String, String>();
    private JavaProjectBuilder qdoxBuilder = new JavaProjectBuilder();

    // 当前文件状态
    private CompilationUnit currentAst;
    private String currentSource;
    private Map<String, String> localVarTypes = new HashMap<String, String>();
    private String currentPackage;
    private String currentClassName;
    private String currentClassFullName;
    private Set<String> currentImports = new HashSet<String>();
    private Set<String> staticImports = new HashSet<String>();
    private Set<String> wildcardImports = new HashSet<String>();
    private Set<String> staticWildcardImports = new HashSet<String>();

    // 当前类自身的 ClassInfo (用于 this. 补全)
    private ClassInfo currentClassInfo;

    // 是否已加载 jar
    private boolean jarLoaded = false;

    // ====================== ProjectIndexService 集成 ======================
    private ProjectIndexService projectIndex;
    private boolean projectIndexAugmentEnabled = true;

    /**
     * 注入项目索引（由 MainActivity 在打开项目时调用）
     * 启用后补全会额外包含 R.xxx、项目类、jar 类
     */
    public void setProjectIndex(ProjectIndexService index) {
        this.projectIndex = index;
    }

    public ProjectIndexService getProjectIndex() {
        return projectIndex;
    }

    public CompletionEngine(Context ctx, FreeScrollingTextField field) {
        this.context = ctx;
        this.textField = field;
        this.language = AutoCompletePanel.getLanguage();
        initStaticMappings();
    }

    // ---------- 初始化静态映射 ----------
    private void initStaticMappings() {
        addStaticMapping("String", "java.lang.String", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Object", "java.lang.Object", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Integer", "java.lang.Integer", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Boolean", "java.lang.Boolean", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("System", "java.lang.System", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Thread", "java.lang.Thread", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("List", "java.util.List", AutoCompletePanel.TYPE_INTERFACE);
        addStaticMapping("ArrayList", "java.util.ArrayList", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Map", "java.util.Map", AutoCompletePanel.TYPE_INTERFACE);
        addStaticMapping("HashMap", "java.util.HashMap", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Set", "java.util.Set", AutoCompletePanel.TYPE_INTERFACE);
        addStaticMapping("Activity", "android.app.Activity", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Context", "android.content.Context", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Intent", "android.content.Intent", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("View", "android.view.View", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("TextView", "android.widget.TextView", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Toast", "android.widget.Toast", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("Bundle", "android.os.Bundle", AutoCompletePanel.TYPE_CLASS);
        addStaticMapping("LinearLayout", "android.widget.LinearLayout", AutoCompletePanel.TYPE_CLASS);
    }

    private void addStaticMapping(String simple, String full, int type) {
        simpleToFullName.put(simple, full);
        if (!classInfoCache.containsKey(full)) {
            ClassInfo info = new ClassInfo();
            info.fullName = full;
            info.simpleName = simple;
            info.type = type;
            classInfoCache.put(full, info);
        }
    }

    // ---------- 初始化：加载 android.jar ----------
    public void initialize() {
        if (jarLoaded) return;
        new Thread(new Runnable() {
                public void run() {
                    try {
                        File jarFile = new File(context.getFilesDir(), "android.jar");
                        if (!jarFile.exists()) {
                            copyAssetToFile("android.jar", jarFile);
                        }
                        if (jarFile.exists()) {
                            loadClassesFromJar(jarFile);
                            jarLoaded = true;
                        }
                    } catch (Throwable t) {
                        // 防止 jar 加载崩溃阻塞编辑器
                        log("初始化异常: " + t.getMessage());
                    }
                }
            }).start();
    }

    private void copyAssetToFile(String assetName, File outFile) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getAssets().open(assetName);
            out = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        } catch (IOException e) {
            log("复制asset失败: " + e.getMessage());
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
            if (out != null) { try { out.close(); } catch (IOException ignored) {} }
        }
    }

    private void loadClassesFromJar(File jarFile) {
        ZipFile zip = null;
        int count = 0;
        int failures = 0;
        try {
            zip = new ZipFile(jarFile);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF")) {
                    try {
                        InputStream is = zip.getInputStream(entry);
                        ClassReader reader = new ClassReader(is);
                        ClassNode node = new ClassNode();
                        reader.accept(node, 0);
                        if ((node.access & Opcodes.ACC_PUBLIC) == 0) {
                            try { is.close(); } catch (IOException ignored) {}
                            continue;
                        }
                        String className = node.name.replace('/', '.');
                        String simpleName = className.substring(className.lastIndexOf('.') + 1);
                        if (simpleName.contains("$")) {
                            simpleName = simpleName.substring(simpleName.lastIndexOf('$') + 1);
                        }
                        ClassInfo info = classInfoCache.get(className);
                        if (info == null) {
                            info = new ClassInfo();
                            info.fullName = className;
                            info.simpleName = simpleName;
                            info.type = ((node.access & Opcodes.ACC_INTERFACE) != 0) ?
                                AutoCompletePanel.TYPE_INTERFACE : AutoCompletePanel.TYPE_CLASS;
                            classInfoCache.put(className, info);
                        }
                        simpleToFullName.put(simpleName, className);
                        if (node.superName != null && !node.superName.equals("java/lang/Object")) {
                            info.superClass = node.superName.replace('/', '.');
                        }
                        for (String intf : node.interfaces) {
                            info.interfaces.add(intf.replace('/', '.'));
                        }
                        // 提取字段
                        for (FieldNode field : node.fields) {
                            if ((field.access & Opcodes.ACC_PUBLIC) != 0) {
                                info.fields.add(field.name);
                                info.fieldTypes.put(field.name, getShortTypeName(Type.getType(field.desc).getClassName()));
                                if ((field.access & Opcodes.ACC_STATIC) != 0) {
                                    info.staticFields.add(field.name);
                                }
                            }
                        }
                        // 提取方法
                        for (MethodNode method : node.methods) {
                            if ((method.access & Opcodes.ACC_PUBLIC) != 0) {
                                if (method.name.startsWith("<")) {
                                    if (method.name.equals("<init>")) {
                                        String constructorSig = buildMethodSignature(simpleName, method.desc);
                                        info.constructors.add(constructorSig);
                                    }
                                    continue;
                                }
                                info.methods.add(method.name);
                                String signature = buildMethodSignature(method.name, method.desc);
                                info.methodSignatures.put(method.name, signature);
                                Type returnType = Type.getReturnType(method.desc);
                                info.methodReturnTypes.put(method.name, getShortTypeName(returnType.getClassName()));
                                if ((method.access & Opcodes.ACC_STATIC) != 0) {
                                    info.staticMethods.add(method.name);
                                }
                            }
                        }
                        count++;
                        is.close();
                        if (count % 1000 == 0) {
                            log("已加载 " + count + " 个类");
                        }
                    } catch (Throwable t) {
                        // 单个类失败不影响其他
                        failures++;
                    }
                }
            }
            log("从android.jar加载了 " + count + " 个类, 失败 " + failures);
        } catch (IOException e) {
            log("加载JAR失败: " + e.getMessage());
        } catch (Throwable t) {
            log("加载JAR异常: " + t.getMessage());
        } finally {
            if (zip != null) { try { zip.close(); } catch (IOException ignored) {} }
        }
    }

    private String buildMethodSignature(String methodName, String desc) {
        StringBuilder sig = new StringBuilder(methodName).append("(");
        Type[] argTypes = Type.getArgumentTypes(desc);
        for (int i = 0; i < argTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(getShortTypeName(argTypes[i].getClassName()));
        }
        sig.append(")");
        return sig.toString();
    }

    private String getShortTypeName(String full) {
        if (full == null || full.isEmpty()) return "";
        int dot = full.lastIndexOf('.');
        if (dot > 0 && dot < full.length() - 1) {
            return full.substring(dot + 1);
        }
        return full;
    }

    // ===================== 主入口 =====================
    public List<CompletionItem> getCompletions(String inputPrefix, int caret) {
        try {
            return getCompletionsInternal(inputPrefix, caret);
        } catch (Throwable t) {
            // 防崩溃：任意异常降级为关键字补全
            log("补全异常降级: " + t.getMessage());
            List<CompletionItem> fallback = new ArrayList<CompletionItem>();
            try {
                fallback.addAll(getKeywordCompletions(inputPrefix == null ? "" : inputPrefix.toLowerCase()));
            } catch (Throwable ignored) {}
            return fallback;
        }
    }

    private List<CompletionItem> getCompletionsInternal(String inputPrefix, int caret) {
        DocumentProvider provider = textField.createDocumentProvider();
        String source = provider.toString();
        // 确保 jar 已加载
        if (!jarLoaded) {
            initialize();
        }
        // 多层解析：总是尝试更新当前文件的元数据（QDox + AST）
        parseSourceWithFallback(source, caret);

        List<CompletionItem> results = new ArrayList<CompletionItem>();

        // 1. 尝试基于 AST 的上下文补全（如果 AST 可用）
        if (currentAst != null) {
            int line = getLineFromCaret(source, caret);
            int column = getColumnFromCaret(source, caret);
            Node cursorNode = findNodeAt(currentAst, line, column);
            ContextInfo ctx = analyzeContext(cursorNode, source, line, column, caret);
            if (ctx != null) {
                if (ctx.isTypeContext) {
                    results.addAll(getClassNameCompletions(inputPrefix.toLowerCase()));
                    return results;
                }
                if (ctx.isImportContext) {
                    results.addAll(getClassNameCompletions(inputPrefix.toLowerCase()));
                    return results;
                }
                if (ctx.target != null) {
                    // R.xxx 风格
                    if ("R".equals(ctx.target) || ctx.target.startsWith("R.")) {
                        List<CompletionItem> rResults = getRClassCompletions(ctx.target, ctx.prefix);
                        if (!rResults.isEmpty()) return rResults;
                    }
                    String targetType = resolveType(ctx.target, source, caret);
                    if (targetType == null) {
                        targetType = resolveTypeFromIndex(ctx.target);
                    }
                    if (targetType != null) {
                        List<CompletionItem> mResults = getMemberCompletions(targetType, ctx.prefix, ctx.onlyStatic);
                        mResults.addAll(getMembersFromIndex(targetType, ctx.prefix, ctx.onlyStatic));
                        if (!mResults.isEmpty()) return mResults;
                    }
                }
            }
        }

        // 2. 降级：基于当前类元数据 + 局部变量类型提供补全
        //    （不使用正则，使用已解析的 localVarTypes 和 QDox 类信息）
        String prefix = inputPrefix.toLowerCase();

        // 判断是否为成员访问上下文（如 "this." 或 "obj."）
        String[] dotContext = extractDotContext(source, caret);
        if (dotContext != null) {
            String targetExpr = dotContext[0];
            String memberPrefix = dotContext[1];

            // R.xxx 风格补全
            if ("R".equals(targetExpr) || targetExpr.startsWith("R.")) {
                List<CompletionItem> rResults = getRClassCompletions(targetExpr, memberPrefix);
                if (!rResults.isEmpty()) return rResults;
            }

            // 优先尝试项目索引解析类型
            String targetType = resolveType(targetExpr, source, caret);
            if (targetType == null) {
                targetType = resolveTypeFromIndex(targetExpr);
            }
            if (targetType != null) {
                // 合并：现有逻辑 + 项目索引
                List<CompletionItem> memberResults = getMemberCompletions(targetType, memberPrefix, false);
                memberResults.addAll(getMembersFromIndex(targetType, memberPrefix, false));
                return memberResults;
            }
        }

        // 3. 默认补全：关键字 + 局部变量 + 类名
        results.addAll(getKeywordCompletions(prefix));
        results.addAll(getLocalVariableCompletions(prefix));
        results.addAll(getClassNameCompletions(prefix));
        return results;
    }

    /**
     * 从光标位置提取点号前的表达式和点号后的前缀
     * 例如："this." -> target="this", prefix=""
     *      "obj.get" -> target="obj", prefix="get"
     * 不使用正则，纯字符扫描。
     */
    private String[] extractDotContext(String source, int caret) {
        if (caret <= 0) return null;
        // 向前找点号
        int dotPos = -1;
        for (int i = caret - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '.') {
                dotPos = i;
                break;
            }
            if (c == '\n' || c == ';' || c == '{' || c == '}' || c == '(' || c == ')') {
                break;
            }
        }
        if (dotPos == -1) return null;
        // 提取点号前的标识符
        int idStart = dotPos - 1;
        while (idStart >= 0 && Character.isJavaIdentifierPart(source.charAt(idStart))) {
            idStart--;
        }
        idStart++;
        String identifier = source.substring(idStart, dotPos);
        if (identifier.isEmpty() || !Character.isJavaIdentifierStart(identifier.charAt(0))) {
            return null;
        }
        // 提取点号后的前缀
        String afterDot = source.substring(dotPos + 1, caret);
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < afterDot.length(); i++) {
            char c = afterDot.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                prefix.append(c);
            } else {
                break;
            }
        }
        return new String[]{identifier, prefix.toString()};
    }

    // ---------- 多层解析：容错获取 AST 和元数据 ----------
    private void parseSourceWithFallback(String source, int caret) {
        // 始终用 QDox 解析当前文件，获取类的基本信息（即使语法错误，QDox 也能提取部分）
        parseWithQDox(source);

        // 尝试完整解析
        boolean parsed = tryParseFullSource(source);
        if (parsed) {
            return;
        }

        // 完整解析失败，尝试截取光标所在方法或语句块进行部分解析
        String partialSource = extractPartialSource(source, caret);
        if (partialSource != null && !partialSource.isEmpty()) {
            tryParseFullSource(partialSource); // 用部分源码覆盖 currentAst
        }

        // 如果仍然没有 AST，则仅依赖 QDox 信息和手动扫描的局部变量
        if (currentAst == null) {
            // 手动扫描局部变量声明（简单的字符串扫描，不使用正则）
            scanLocalVariables(source, caret);
        }
    }

    private boolean tryParseFullSource(String source) {
        try {
            currentAst = JavaParser.parse(new StringReader(source), false);
            // 解析成功，提取信息
            extractSymbolsFromAST();
            return true;
        } catch (ParseException e) {
            log("完整AST解析失败: " + e.getMessage());
        } catch (Exception e) {
            log("完整AST解析异常: " + e.getMessage());
        }
        return false;
    }

    /**
     * 使用 QDox 解析源文件，提取包名、类名、字段、方法等元数据。
     * QDox 对语法错误的容忍度较高。
     */
    private void parseWithQDox(String source) {
        try {
            // 清空旧的 QDox 构建器
            qdoxBuilder = new JavaProjectBuilder();
            qdoxBuilder.addSource(new StringReader(source));
            // 获取主类
            Collection<JavaClass> classes = qdoxBuilder.getClasses();
            if (!classes.isEmpty()) {
                JavaClass mainClass = classes.iterator().next();
                currentPackage = mainClass.getPackageName();
                currentClassName = mainClass.getName();
                currentClassFullName = mainClass.getFullyQualifiedName();
                // 构建当前类的 ClassInfo
                currentClassInfo = convertQdoxToClassInfo(mainClass);
                classInfoCache.put(currentClassFullName, currentClassInfo);
                simpleToFullName.put(currentClassName, currentClassFullName);
                localVarTypes.put("this", currentClassFullName);
                if (mainClass.getSuperJavaClass() != null) {
                    localVarTypes.put("super", mainClass.getSuperJavaClass().getFullyQualifiedName());
                } else {
                    localVarTypes.put("super", "java.lang.Object");
                }
                // 提取导入
                // QDox 2.0.0 的导入提取较麻烦，我们仍从源码中简单扫描
                scanImportsFromSource(source);
            }
        } catch (Exception e) {
            log("QDox解析失败: " + e.getMessage());
        }
    }

    private ClassInfo convertQdoxToClassInfo(JavaClass jc) {
        ClassInfo info = new ClassInfo();
        info.fullName = jc.getFullyQualifiedName();
        info.simpleName = jc.getName();
        info.type = jc.isInterface() ? AutoCompletePanel.TYPE_INTERFACE : AutoCompletePanel.TYPE_CLASS;
        if (jc.getSuperJavaClass() != null) {
            info.superClass = jc.getSuperJavaClass().getFullyQualifiedName();
        }
        for (JavaClass intf : jc.getInterfaces()) {
            info.interfaces.add(intf.getFullyQualifiedName());
        }
        for (JavaMethod m : jc.getMethods()) {
            if (m.isPublic()) {
                info.methods.add(m.getName());
                StringBuilder sig = new StringBuilder(m.getName()).append("(");
                List<JavaParameter> params = m.getParameters();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params.get(i).getType().getValue());
                }
                sig.append(")");
                info.methodSignatures.put(m.getName(), sig.toString());
                info.methodReturnTypes.put(m.getName(), m.getReturnType().getValue());
                if (m.isStatic()) info.staticMethods.add(m.getName());
            }
        }
        for (JavaField f : jc.getFields()) {
            if (f.isPublic()) {
                info.fields.add(f.getName());
                info.fieldTypes.put(f.getName(), f.getType().getFullyQualifiedName());
                if (f.isStatic()) info.staticFields.add(f.getName());
            }
        }
        return info;
    }

    /**
     * 从源码扫描导入语句（简单字符串扫描，不使用正则）
     */
    private void scanImportsFromSource(String source) {
        currentImports.clear();
        wildcardImports.clear();
        wildcardImports.add("java.lang.");
        if (source == null) return;
        String[] lines = source.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("import ")) {
                String importStr = line.substring(7).trim();
                if (importStr.endsWith(";")) {
                    importStr = importStr.substring(0, importStr.length() - 1);
                }
                if (importStr.endsWith(".*")) {
                    String pkg = importStr.substring(0, importStr.length() - 2);
                    wildcardImports.add(pkg + ".");
                } else {
                    String simple = importStr.substring(importStr.lastIndexOf('.') + 1);
                    currentImports.add(simple);
                    simpleToFullName.put(simple, importStr);
                }
            }
        }
    }

    /**
     * 截取光标所在位置之前的、相对完整的代码块，用于部分解析。
     * 简单实现：找到光标所在的方法开始位置，截取到光标处，并尝试补全括号。
     */
    private String extractPartialSource(String source, int caret) {
        if (caret <= 0 || caret > source.length()) return null;
        // 找到光标所在行的开始
        int lineStart = caret - 1;
        while (lineStart >= 0 && source.charAt(lineStart) != '\n') lineStart--;
        lineStart++;
        // 向上查找方法声明或类声明
        int methodStart = findMethodStart(source, lineStart);
        if (methodStart == -1) {
            methodStart = 0;
        }
        String partial = source.substring(methodStart, caret);
        // 尝试补全缺失的括号和分号（非常简单的启发式）
        partial = healPartialCode(partial);
        // 加上类声明包装，以便解析
        String wrapped = wrapAsClass(partial);
        return wrapped;
    }

    private int findMethodStart(String source, int pos) {
        // 简单向上查找包含 "void " 或 "public " 或 "private " 或 "protected " 的行
        int lineStart = pos;
        while (lineStart > 0) {
            int prevLineEnd = lineStart - 1;
            while (prevLineEnd > 0 && source.charAt(prevLineEnd) != '\n') prevLineEnd--;
            if (prevLineEnd == 0) break;
            int lineBegin = prevLineEnd + 1;
            String line = source.substring(lineBegin, lineStart).trim();
            if (line.contains(" void ") || line.contains(" public ") || line.contains(" private ") ||
                line.contains(" protected ") || line.startsWith("void ") || line.startsWith("public ") ||
                line.startsWith("private ") || line.startsWith("protected ")) {
                return lineBegin;
            }
            lineStart = prevLineEnd;
        }
        return 0;
    }

    private String healPartialCode(String code) {
        // 简单的括号平衡
        int paren = 0, brace = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '{') brace++;
            else if (c == '}') brace--;
        }
        StringBuilder sb = new StringBuilder(code);
        for (int i = 0; i < paren; i++) sb.append(')');
        for (int i = 0; i < brace; i++) sb.append('}');
        // 如果最后不是分号或括号，加分号
        char last = sb.charAt(sb.length() - 1);
        if (last != ';' && last != '}' && last != '{') {
            sb.append(';');
        }
        return sb.toString();
    }

    private String wrapAsClass(String body) {
        String pkg = currentPackage != null ? "package " + currentPackage + ";\n" : "";
        String imports = buildImportsString();
        return pkg + imports + "public class " + (currentClassName != null ? currentClassName : "Temp") + " { " + body + " }";
    }

    private String buildImportsString() {
        StringBuilder sb = new StringBuilder();
        for (String imp : currentImports) {
            String full = simpleToFullName.get(imp);
            if (full != null) {
                sb.append("import ").append(full).append(";\n");
            }
        }
        for (String wild : wildcardImports) {
            sb.append("import ").append(wild).append("*;\n");
        }
        return sb.toString();
    }

    /**
     * 当 AST 完全不可用时，手动扫描局部变量（仅用于降级显示）
     */
    private void scanLocalVariables(String source, int caret) {
        // 简单实现：扫描 "类型 变量名 =" 的模式
        localVarTypes.clear();
        if (source == null) return;
        int limit = Math.min(caret, source.length());
        int i = 0;
        while (i < limit) {
            // 跳过空白
            while (i < limit && Character.isWhitespace(source.charAt(i))) i++;
            // 找标识符开始
            int start = i;
            while (i < limit && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            if (start == i) {
                i++;
                continue;
            }
            String type = source.substring(start, i);
            // 跳过空白
            while (i < limit && Character.isWhitespace(source.charAt(i))) i++;
            // 找变量名
            int varStart = i;
            while (i < limit && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            if (varStart == i) continue;
            String varName = source.substring(varStart, i);
            // 看后面是不是 =
            while (i < limit && Character.isWhitespace(source.charAt(i))) i++;
            if (i < limit && source.charAt(i) == '=') {
                localVarTypes.put(varName, type);
            }
        }
        if (currentClassFullName != null) {
            localVarTypes.put("this", currentClassFullName);
        }
    }

    // ---------- AST 符号提取 ----------
    private void extractSymbolsFromAST() {
        if (currentAst == null) return;
        // 包名
        if (currentAst.getPackage() != null) {
            currentPackage = currentAst.getPackage().getName().toString();
        }
        // 导入
        parseImportsFromAST();
        // 类信息
        if (!currentAst.getTypes().isEmpty()) {
            TypeDeclaration typeDecl = currentAst.getTypes().get(0);
            if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) typeDecl;
                currentClassName = cls.getName();
                currentClassFullName = (currentPackage != null && !currentPackage.isEmpty()) ?
                    currentPackage + "." + currentClassName : currentClassName;
                // 构建当前类的 ClassInfo
                currentClassInfo = buildClassInfoFromAST(cls);
                classInfoCache.put(currentClassFullName, currentClassInfo);
                simpleToFullName.put(currentClassName, currentClassFullName);
                localVarTypes.put("this", currentClassFullName);
                if (cls.getExtends() != null && !cls.getExtends().isEmpty()) {
                    localVarTypes.put("super", resolveType(cls.getExtends().get(0).getName(), currentSource, 0));
                } else {
                    localVarTypes.put("super", "java.lang.Object");
                }
            }
        }
        // 收集局部变量
        currentAst.accept(new VoidVisitorAdapter<Object>() {
                @Override
                public void visit(MethodDeclaration n, Object arg) {
                    if (n.getParameters() != null) {
                        for (Parameter p : n.getParameters()) {
                            localVarTypes.put(p.getId().getName(), p.getType().toString());
                        }
                    }
                    super.visit(n, arg);
                }
                @Override
                public void visit(VariableDeclarationExpr n, Object arg) {
                    String type = n.getType().toString();
                    for (VariableDeclarator var : n.getVars()) {
                        localVarTypes.put(var.getId().getName(), type);
                    }
                    super.visit(n, arg);
                }
                @Override
                public void visit(FieldDeclaration n, Object arg) {
                    String type = n.getType().toString();
                    for (VariableDeclarator var : n.getVariables()) {
                        localVarTypes.put(var.getId().getName(), type);
                    }
                    super.visit(n, arg);
                }
            }, null);
    }

    private void parseImportsFromAST() {
        currentImports.clear();
        staticImports.clear();
        wildcardImports.clear();
        staticWildcardImports.clear();
        wildcardImports.add("java.lang.");
        if (currentAst == null || currentAst.getImports() == null) return;
        for (ImportDeclaration imp : currentAst.getImports()) {
            String name = imp.getName().toString();
            if (imp.isStatic()) {
                if (imp.isAsterisk()) {
                    staticWildcardImports.add(name + ".");
                } else {
                    int lastDot = name.lastIndexOf('.');
                    if (lastDot > 0) {
                        String className = name.substring(0, lastDot);
                        String memberName = name.substring(lastDot + 1);
                        staticImports.add(className + "#" + memberName);
                        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
                        simpleToFullName.put(simpleClassName, className);
                    }
                }
            } else {
                if (imp.isAsterisk()) {
                    wildcardImports.add(name + ".");
                } else {
                    String simpleName = name.substring(name.lastIndexOf('.') + 1);
                    currentImports.add(simpleName);
                    simpleToFullName.put(simpleName, name);
                }
            }
        }
    }

    private ClassInfo buildClassInfoFromAST(ClassOrInterfaceDeclaration cls) {
        ClassInfo info = new ClassInfo();
        info.fullName = currentClassFullName;
        info.simpleName = currentClassName;
        info.type = cls.isInterface() ? AutoCompletePanel.TYPE_INTERFACE : AutoCompletePanel.TYPE_CLASS;
        if (cls.getExtends() != null && !cls.getExtends().isEmpty()) {
            info.superClass = resolveType(cls.getExtends().get(0).getName(), currentSource, 0);
        }
        if (cls.getImplements() != null) {
            for (ClassOrInterfaceType intf : cls.getImplements()) {
                info.interfaces.add(resolveType(intf.getName(), currentSource, 0));
            }
        }
        for (BodyDeclaration member : cls.getMembers()) {
            if (member instanceof FieldDeclaration) {
                FieldDeclaration fd = (FieldDeclaration) member;
                boolean isStatic = (fd.getModifiers() & ModifierSet.STATIC) != 0;
                String type = fd.getType().toString();
                for (VariableDeclarator var : fd.getVariables()) {
                    String name = var.getId().getName();
                    info.fields.add(name);
                    info.fieldTypes.put(name, type);
                    if (isStatic) info.staticFields.add(name);
                }
            } else if (member instanceof MethodDeclaration) {
                MethodDeclaration md = (MethodDeclaration) member;
                boolean isStatic = (md.getModifiers() & ModifierSet.STATIC) != 0;
                String name = md.getName();
                info.methods.add(name);
                String retType = md.getType() != null ? md.getType().toString() : "void";
                info.methodReturnTypes.put(name, retType);
                StringBuilder sig = new StringBuilder(name).append("(");
                if (md.getParameters() != null) {
                    for (int i = 0; i < md.getParameters().size(); i++) {
                        if (i > 0) sig.append(", ");
                        sig.append(md.getParameters().get(i).getType().toString());
                    }
                }
                sig.append(")");
                info.methodSignatures.put(name, sig.toString());
                if (isStatic) info.staticMethods.add(name);
            }
        }
        return info;
    }

    // ---------- 类型解析（支持简单链式调用）----------
    private String resolveType(String expression, String source, int caret) {
        if (expression == null || expression.isEmpty()) return null;
        if ("this".equals(expression)) {
            return currentClassFullName != null ? currentClassFullName : "java.lang.Object";
        }
        if ("super".equals(expression)) {
            return currentClassInfo != null && currentClassInfo.superClass != null ?
                currentClassInfo.superClass : "java.lang.Object";
        }
        // 处理链式调用 a.b.c
        int lastDot = expression.lastIndexOf('.');
        if (lastDot > 0) {
            String left = expression.substring(0, lastDot);
            String right = expression.substring(lastDot + 1);
            String leftType = resolveType(left, source, caret);
            if (leftType != null) {
                ClassInfo info = getClassInfo(leftType);
                if (info != null) {
                    if (info.fieldTypes.containsKey(right)) {
                        return expandTypeName(info.fieldTypes.get(right));
                    }
                    if (info.methodReturnTypes.containsKey(right)) {
                        return expandTypeName(info.methodReturnTypes.get(right));
                    }
                }
            }
        }
        // 单标识符
        if (localVarTypes.containsKey(expression)) {
            return expandTypeName(localVarTypes.get(expression));
        }
        // 静态导入成员
        for (String staticImp : staticImports) {
            if (staticImp.endsWith("#" + expression)) {
                return staticImp.substring(0, staticImp.indexOf('#'));
            }
        }
        // 当前类成员
        if (currentClassInfo != null) {
            if (currentClassInfo.fieldTypes.containsKey(expression)) {
                return expandTypeName(currentClassInfo.fieldTypes.get(expression));
            }
            if (currentClassInfo.methodReturnTypes.containsKey(expression)) {
                return expandTypeName(currentClassInfo.methodReturnTypes.get(expression));
            }
        }
        // 导入类
        if (currentImports.contains(expression)) {
            return simpleToFullName.get(expression);
        }
        for (String pkg : wildcardImports) {
            String full = pkg + expression;
            if (classInfoCache.containsKey(full)) return full;
        }
        if (simpleToFullName.containsKey(expression)) {
            return simpleToFullName.get(expression);
        }
        String javaLang = "java.lang." + expression;
        if (classInfoCache.containsKey(javaLang)) return javaLang;
        if (currentPackage != null) {
            String cur = currentPackage + "." + expression;
            if (classInfoCache.containsKey(cur)) return cur;
        }
        return null;
    }

    private String expandTypeName(String typeName) {
        if (typeName == null) return null;
        Map<String, String> prim = new HashMap<String, String>();
        prim.put("int", "java.lang.Integer"); prim.put("long", "java.lang.Long");
        prim.put("float", "java.lang.Float"); prim.put("double", "java.lang.Double");
        prim.put("boolean", "java.lang.Boolean"); prim.put("char", "java.lang.Character");
        prim.put("byte", "java.lang.Byte"); prim.put("short", "java.lang.Short");
        prim.put("void", "java.lang.Void");
        if (prim.containsKey(typeName)) return prim.get(typeName);
        if (typeName.endsWith("[]")) {
            String elem = expandTypeName(typeName.substring(0, typeName.length() - 2));
            return elem != null ? elem + "[]" : typeName;
        }
        int genericStart = typeName.indexOf('<');
        if (genericStart > 0) {
            typeName = typeName.substring(0, genericStart);
        }
        if (typeName.contains(".") && classInfoCache.containsKey(typeName)) return typeName;
        for (String imp : currentImports) if (imp.equals(typeName)) return simpleToFullName.get(imp);
        for (String pkg : wildcardImports) {
            String full = pkg + typeName;
            if (classInfoCache.containsKey(full)) return full;
        }
        if (simpleToFullName.containsKey(typeName)) return simpleToFullName.get(typeName);
        String javaLang = "java.lang." + typeName;
        if (classInfoCache.containsKey(javaLang)) return javaLang;
        if (currentPackage != null) {
            String cur = currentPackage + "." + typeName;
            if (classInfoCache.containsKey(cur)) return cur;
        }
        return typeName;
    }

    // ---------- AST 上下文分析 ----------
    private ContextInfo analyzeContext(Node node, String source, int line, int column, int caret) {
        if (node == null) return null;
        if (isTypeNameContext(node)) {
            ContextInfo info = new ContextInfo(null, "");
            info.isTypeContext = true;
            return info;
        }
        if (isImportContext(node)) {
            ContextInfo info = new ContextInfo(null, "");
            info.isImportContext = true;
            return info;
        }
        if (node instanceof FieldAccessExpr) {
            FieldAccessExpr fae = (FieldAccessExpr) node;
            String target = fae.getScope().toString();
            String prefix = extractPrefixFromCaret(source, caret);
            return new ContextInfo(target, prefix);
        }
        if (node instanceof MethodCallExpr) {
            MethodCallExpr mce = (MethodCallExpr) node;
            if (mce.getScope() != null && isCursorInside(mce.getScope(), line, column)) {
                return new ContextInfo(mce.getScope().toString(), "");
            }
        }
        if (node instanceof NameExpr) {
            NameExpr ne = (NameExpr) node;
            if (line == ne.getEndLine() && column > ne.getEndColumn()) {
                int dot = findDotBeforeCaret(source, caret);
                if (dot != -1) {
                    String target = source.substring(ne.getBeginColumn() - 1, dot).trim();
                    String prefix = source.substring(dot + 1, caret).trim();
                    return new ContextInfo(target, prefix);
                }
            }
        }
        if (node instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType cit = (ClassOrInterfaceType) node;
            if (line == cit.getEndLine() && column > cit.getEndColumn()) {
                int dot = findDotBeforeCaret(source, caret);
                if (dot != -1) {
                    String target = cit.getName();
                    String prefix = source.substring(dot + 1, caret).trim();
                    ContextInfo info = new ContextInfo(target, prefix);
                    info.onlyStatic = true;
                    return info;
                }
            }
        }
        return null;
    }

    private Node findNodeAt(CompilationUnit cu, final int targetLine, final int targetColumn) {
        if (cu == null) return null;
        final Node[] result = {null};
        final int[] maxDepth = {-1};
        traverseNode(cu, targetLine, targetColumn, 0, result, maxDepth);
        return result[0];
    }

    private void traverseNode(Node node, int targetLine, int targetColumn, int depth, Node[] result, int[] maxDepth) {
        if (node == null) return;
        if (node.getBeginLine() != -1) {
            boolean contains = true;
            if (targetLine < node.getBeginLine() || targetLine > node.getEndLine()) contains = false;
            else if (targetLine == node.getBeginLine() && targetColumn < node.getBeginColumn()) contains = false;
            else if (targetLine == node.getEndLine() && targetColumn > node.getEndColumn() + 1) contains = false;
            if (contains && depth > maxDepth[0]) {
                maxDepth[0] = depth;
                result[0] = node;
            }
        }
        if (node.getChildrenNodes() != null) {
            for (Node child : node.getChildrenNodes()) {
                traverseNode(child, targetLine, targetColumn, depth + 1, result, maxDepth);
            }
        }
    }

    private boolean isCursorInside(Node n, int line, int column) {
        if (line < n.getBeginLine() || line > n.getEndLine()) return false;
        if (line == n.getBeginLine() && column < n.getBeginColumn()) return false;
        if (line == n.getEndLine() && column > n.getEndColumn()) return false;
        return true;
    }

    private int findDotBeforeCaret(String source, int caret) {
        for (int i = caret - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '.') return i;
            if (c == '\n' || c == ';' || c == '{' || c == '}' || c == '(' || c == ')') break;
        }
        return -1;
    }

    private String extractPrefixFromCaret(String source, int caret) {
        int dot = source.lastIndexOf('.', caret - 1);
        if (dot == -1) return "";
        String after = source.substring(dot + 1, caret);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < after.length(); i++) {
            if (Character.isJavaIdentifierPart(after.charAt(i))) sb.append(after.charAt(i));
            else break;
        }
        return sb.toString();
    }

    private boolean isTypeNameContext(Node node) {
        while (node != null) {
            if (node instanceof ClassOrInterfaceType || node instanceof ObjectCreationExpr ||
                node instanceof ReferenceType) return true;
            node = node.getParentNode();
        }
        return false;
    }

    private boolean isImportContext(Node node) {
        while (node != null) {
            if (node instanceof ImportDeclaration) return true;
            node = node.getParentNode();
        }
        return false;
    }

    // ---------- 补全列表生成 ----------
    private List<CompletionItem> getKeywordCompletions(String prefix) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        if (language == null) return res;
        String[] kws = language.getKeywords();
        if (kws == null) return res;
        for (String kw : kws) {
            if (kw != null && kw.toLowerCase().startsWith(prefix)) {
                String clean = kw.replaceAll("\\[.*\\]", "").trim();
                if (!clean.isEmpty())
                    res.add(new CompletionItem(clean, "关键字", clean, AutoCompletePanel.TYPE_KEYWORD, null));
            }
        }
        return res;
    }

    private List<CompletionItem> getClassNameCompletions(String prefix) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        for (Map.Entry<String, String> e : simpleToFullName.entrySet()) {
            if (e.getKey().toLowerCase().startsWith(prefix)) {
                ClassInfo info = classInfoCache.get(e.getValue());
                int t = (info != null && info.type == AutoCompletePanel.TYPE_INTERFACE) ?
                    AutoCompletePanel.TYPE_INTERFACE : AutoCompletePanel.TYPE_CLASS;
                res.add(new CompletionItem(e.getKey(), "类 - " + e.getValue(), e.getKey(), t, e.getValue()));
            }
        }
        // 增强：从项目索引追加项目类、jar 类
        augmentWithProjectIndex(prefix, res);
        return res;
    }

    private List<CompletionItem> getLocalVariableCompletions(String prefix) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        for (Map.Entry<String, String> e : localVarTypes.entrySet()) {
            if (e.getKey().toLowerCase().startsWith(prefix)) {
                res.add(new CompletionItem(e.getKey(), e.getValue() + " - 变量", e.getKey(),
                                           AutoCompletePanel.TYPE_LOCAL_VAR, null));
            }
        }
        return res;
    }

    private List<CompletionItem> getMemberCompletions(String targetType, String prefix, boolean onlyStatic) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        if (targetType == null) return res;
        ClassInfo info = getClassInfo(targetType);
        if (info == null) return res;
        Set<String> visited = new HashSet<String>();
        collectMembers(info, prefix.toLowerCase(), res, visited, onlyStatic);
        return res;
    }

    private ClassInfo getClassInfo(String fullName) {
        if (fullName == null) return null;
        ClassInfo info = classInfoCache.get(fullName);
        if (info != null) return info;
        // 尝试 QDox 获取
        try {
            JavaClass jc = qdoxBuilder.getClassByName(fullName);
            if (jc != null) {
                info = convertQdoxToClassInfo(jc);
                classInfoCache.put(fullName, info);
                simpleToFullName.put(info.simpleName, fullName);
            }
        } catch (Exception ignored) {}
        return info;
    }

    private void collectMembers(ClassInfo info, String prefix, List<CompletionItem> res,
                                Set<String> visited, boolean onlyStatic) {
        if (info == null || visited.contains(info.fullName)) return;
        visited.add(info.fullName);
        for (String f : info.fields) {
            if (onlyStatic && !info.staticFields.contains(f)) continue;
            if (prefix.isEmpty() || f.toLowerCase().startsWith(prefix)) {
                String type = info.fieldTypes.get(f);
                res.add(new CompletionItem(f, f + " : " + getShortTypeName(type), f,
                                           AutoCompletePanel.TYPE_FIELD, null));
            }
        }
        for (String m : info.methods) {
            if (onlyStatic && !info.staticMethods.contains(m)) continue;
            if (prefix.isEmpty() || m.toLowerCase().startsWith(prefix)) {
                String sig = info.methodSignatures.get(m);
                String ret = info.methodReturnTypes.get(m);
                if (sig == null) sig = m + "()";
                if (ret == null) ret = "void";
                res.add(new CompletionItem(m, sig + " : " + getShortTypeName(ret), m,
                                           AutoCompletePanel.TYPE_METHOD, null));
            }
        }
        if (!onlyStatic) {
            for (String c : info.constructors) {
                if (prefix.isEmpty() || c.toLowerCase().startsWith(prefix))
                    res.add(new CompletionItem(c, c, c, AutoCompletePanel.TYPE_METHOD, null));
            }
        }
        if (info.superClass != null) {
            collectMembers(getClassInfo(info.superClass), prefix, res, visited, onlyStatic);
        }
        for (String intf : info.interfaces) {
            collectMembers(getClassInfo(intf), prefix, res, visited, onlyStatic);
        }
    }

    // ---------- 辅助定位 ----------
    private int getLineFromCaret(String source, int caret) {
        int line = 1;
        for (int i = 0; i < caret && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private int getColumnFromCaret(String source, int caret) {
        int col = 1;
        for (int i = caret - 1; i >= 0 && source.charAt(i) != '\n'; i--) col++;
        return col;
    }

    private void log(String msg) {
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(LOG_PATH, true));
            pw.println(SDF.format(new Date()) + " [CompletionEngine] " + msg);
            pw.close();
        } catch (IOException ignored) {}
    }

    // ===================== 内部数据类 =====================
    static class ClassInfo {
        String fullName, simpleName;
        int type;
        String superClass;
        List<String> interfaces = new ArrayList<String>();
        List<String> methods = new ArrayList<String>();
        List<String> fields = new ArrayList<String>();
        List<String> constructors = new ArrayList<String>();
        Set<String> staticMethods = new HashSet<String>();
        Set<String> staticFields = new HashSet<String>();
        Map<String, String> methodSignatures = new HashMap<String, String>();
        Map<String, String> methodReturnTypes = new HashMap<String, String>();
        Map<String, String> fieldTypes = new HashMap<String, String>();
    }

    private static class ContextInfo {
        String target;
        String prefix;
        boolean isTypeContext;
        boolean isImportContext;
        boolean onlyStatic;
        ContextInfo(String t, String p) {
            target = t;
            prefix = p;
        }
    }

    // ===================== ProjectIndexService 辅助 =====================

    /**
     * 处理 R.xxx 风格的补全
     * 支持以下情况：
     *   R.              -> 列出 R 子类
     *   R.id            -> 高亮 id 子类
     *   R.id.app        -> 列出 id 子类中以 app 开头的字段
     * @param packageAndClass 如 "R" 或 "R.id"
     * @param memberPrefix 成员前缀（可能含点号，如 "id.app"）
     * @return 候选补全项
     */
    private List<CompletionItem> getRClassCompletions(String packageAndClass, String memberPrefix) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        if (projectIndex == null) return res;
        try {
            String pkg = projectIndex.getConfig() == null ? null : projectIndex.getConfig().getJavaPackage();
            if (pkg == null) return res;

            // 默认子类型列表（基于常见 res 目录）
            String[] commonSubs = new String[]{
                "drawable", "string", "id", "layout", "mipmap", "color",
                "dimen", "style", "menu", "raw", "xml", "anim", "array", "integer", "bool"
            };

            // case 1: R. (无 prefix)  -> 列出子类
            if ("R".equals(packageAndClass) || packageAndClass == null) {
                String mp = memberPrefix == null ? "" : memberPrefix;
                // 解析 mp 是否含子类型前缀
                int dotIdx = mp.indexOf('.');
                if (dotIdx < 0) {
                    String subPrefix = mp.toLowerCase();
                    for (String sub : commonSubs) {
                        if (subPrefix.isEmpty() || sub.toLowerCase().startsWith(subPrefix)) {
                            res.add(new CompletionItem(sub, pkg + ".R." + sub, sub,
                                                       AutoCompletePanel.TYPE_CLASS, pkg + ".R." + sub));
                        }
                    }
                    return res;
                }
                // mp = "id.app" -> 查找 id 子类的成员
                String sub = mp.substring(0, dotIdx);
                String fieldPrefix = mp.substring(dotIdx + 1).toLowerCase();
                IndexModel.ClassInfo ci = projectIndex.getClassInfo(pkg + ".R." + sub);
                if (ci != null) {
                    for (IndexModel.MemberInfo m : ci.members) {
                        if (m.kind != IndexModel.KIND_FIELD) continue;
                        if (fieldPrefix.isEmpty() || m.name.toLowerCase().startsWith(fieldPrefix)) {
                            res.add(new CompletionItem(m.name, m.name + " : int", m.name,
                                                       AutoCompletePanel.TYPE_FIELD, null));
                        }
                    }
                }
                return res;
            }

            // case 2: R.id (R.<sub>) -> 列出该子类的成员
            if (packageAndClass.startsWith("R.")) {
                String sub = packageAndClass.substring(2);
                String fieldPrefix = memberPrefix == null ? "" : memberPrefix.toLowerCase();
                IndexModel.ClassInfo ci = projectIndex.getClassInfo(pkg + ".R." + sub);
                if (ci != null) {
                    for (IndexModel.MemberInfo m : ci.members) {
                        if (m.kind != IndexModel.KIND_FIELD) continue;
                        if (fieldPrefix.isEmpty() || m.name.toLowerCase().startsWith(fieldPrefix)) {
                            res.add(new CompletionItem(m.name, m.name + " : int", m.name,
                                                       AutoCompletePanel.TYPE_FIELD, null));
                        }
                    }
                }
                return res;
            }
        } catch (Throwable t) {
            log("R.补全异常: " + t.getMessage());
        }
        return res;
    }

    /**
     * 从 ProjectIndexService 增强类名补全（项目类、jar 类）
     */
    private List<CompletionItem> augmentWithProjectIndex(String prefix, List<CompletionItem> base) {
        if (!projectIndexAugmentEnabled || projectIndex == null) return base;
        try {
            List<String> simpleNameHits = projectIndex.suggestBySimpleName(prefix, 200);
            for (String full : simpleNameHits) {
                IndexModel.ClassInfo ci = projectIndex.getClassInfo(full);
                if (ci == null) continue;
                // 跳过已存在的
                boolean exists = false;
                for (CompletionItem it : base) {
                    if (it.commitText != null && it.commitText.equals(ci.simpleName)) {
                        exists = true;
                        break;
                    }
                }
                if (exists) continue;
                int type = ci.isInterface ? AutoCompletePanel.TYPE_INTERFACE : AutoCompletePanel.TYPE_CLASS;
                base.add(new CompletionItem(ci.simpleName, "类 - " + ci.fullName, ci.simpleName, type, ci.fullName));
            }
        } catch (Throwable t) {
            log("项目索引增强补全异常: " + t.getMessage());
        }
        return base;
    }

    /**
     * 解析类型字符串（可能是简单名或全限定名）
     * 优先使用项目索引查找
     */
    private String resolveTypeFromIndex(String expression) {
        if (projectIndex == null) return null;
        try {
            if (expression == null || expression.isEmpty()) return null;
            // 1) 直接是 FQCN
            if (expression.contains(".") && projectIndex.getClassInfo(expression) != null) {
                return expression;
            }
            // 2) 简单名 - 通过项目索引
            List<String> cands = projectIndex.suggestBySimpleName(expression, 5);
            if (cands.size() == 1) return cands.get(0);
            // 3) 多个候选则返回第一个（用户可继续输入限定）
            if (cands.size() > 1) return cands.get(0);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 从项目索引获取类的成员补全
     */
    private List<CompletionItem> getMembersFromIndex(String fullName, String prefix, boolean onlyStatic) {
        List<CompletionItem> res = new ArrayList<CompletionItem>();
        if (projectIndex == null) return res;
        try {
            List<IndexModel.MemberInfo> members = onlyStatic
                ? projectIndex.getStaticMembers(fullName)
                : projectIndex.getMembers(fullName);
            for (IndexModel.MemberInfo m : members) {
                if (prefix == null || prefix.isEmpty() || m.name.toLowerCase().startsWith(prefix.toLowerCase())) {
                    if (m.kind == IndexModel.KIND_FIELD) {
                        res.add(new CompletionItem(m.name, m.name + " : " + m.type, m.name,
                                                   AutoCompletePanel.TYPE_FIELD, null));
                    } else if (m.kind == IndexModel.KIND_METHOD) {
                        String sig = m.name + "()";
                        res.add(new CompletionItem(m.name, sig + " : " + m.type, m.name,
                                                   AutoCompletePanel.TYPE_METHOD, null));
                    } else if (m.kind == IndexModel.KIND_CONSTRUCTOR) {
                        res.add(new CompletionItem(m.name, m.name + "()", m.name,
                                                   AutoCompletePanel.TYPE_METHOD, null));
                    } else if (m.kind == IndexModel.KIND_INNER_CLASS) {
                        res.add(new CompletionItem(m.name, m.name, m.name,
                                                   AutoCompletePanel.TYPE_CLASS, null));
                    }
                }
            }
        } catch (Throwable t) {
            log("项目索引成员补全异常: " + t.getMessage());
        }
        return res;
    }
}
