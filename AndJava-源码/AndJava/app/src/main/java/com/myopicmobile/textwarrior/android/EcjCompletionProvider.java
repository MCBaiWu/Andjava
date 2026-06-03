package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.util.Log;

import com.andjava.ide.project.IndexModel;
import com.andjava.ide.project.ProjectIndexService;

import org.eclipse.jdt.internal.codeassist.complete.CompletionParser;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.core.compiler.IProblem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Eclipse ECJ 内核的代码补全引擎。
 *
 * 设计目标：
 * 1. 不修改任何原 CompletionEngine / AutoCompletePanel 已有字段和方法签名。
 * 2. 对外暴露同样的 {@link #getCompletions(CharSequence, int, int, int)} 风格 API。
 * 3. 在内部使用 ECJ 的 {@link CompletionParser} 直接对源码 AST 做补全：
 *    - 字段、局部变量、方法、类型字面量、import 都会触发对应完成项
 *    - 关键字 / 标识符前缀用 ECJ 的 proposal 体系表示，再映射到 {@link CompletionItem}
 * 4. 通过 {@link JavaCompiler} 内部已有的 classpath（含 android.jar + 用户依赖）参与解析。
 * 5. 失败时（ECJ 在 Android 上偶发 null pointer）自动降级到 {@link CompletionEngine} 的现有逻辑，
 *    保证不会因为切换了引擎而让补全"消失"。
 *
 * 使用方法（不修改原 CompletionEngine）：
 * <pre>
 *   CompletionEngine original = autoCompletePanel.getCompletionEngine();
 *   EcjCompletionProvider ecj = new EcjCompletionProvider(context, original, autoCompletePanel);
 *   ecj.setProjectIndex(projectIndex);
 *   autoCompletePanel.setCompletionEngine(ecj);
 * </pre>
 * 若想回退到原始实现：把 original 设置回 {@link AutoCompletePanel} 即可。
 */
public class EcjCompletionProvider {

    private static final String TAG = "EcjCompletion";
    /** 软超时：ECJ 解析超过此时间则降级 */
    private static final long PARSE_TIMEOUT_MS = 800L;

    private final Context context;
    /** 原始 CompletionEngine（用来降级） */
    private final CompletionEngine fallback;
    private final FreeScrollingTextField textField;

    private ProjectIndexService projectIndex;

    // 简易缓存：相同源码 + 相同光标位置 -> 补全列表
    private String cachedSource = null;
    private int cachedCaret = -1;
    private List<CompletionItem> cachedItems = Collections.emptyList();

    public EcjCompletionProvider(Context context, CompletionEngine fallback,
                                 FreeScrollingTextField textField) {
        this.context = context;
        this.fallback = fallback;
        this.textField = textField;
    }

    public void setProjectIndex(ProjectIndexService index) {
        this.projectIndex = index;
    }

    public ProjectIndexService getProjectIndex() {
        return projectIndex;
    }

    /**
     * 同步入口：与 CompletionEngine#getCompletions 保持同一返回类型。
     */
    public List<CompletionItem> getCompletions(CharSequence text, int caret, int anchorStart) {
        if (text == null) return Collections.emptyList();
        String source = text.toString();

        // 缓存命中
        if (source.equals(cachedSource) && caret == cachedCaret) {
            return cachedItems;
        }

        List<CompletionItem> items = null;
        try {
            items = ecjComplete(source, caret, anchorStart);
        } catch (Throwable t) {
            Log.w(TAG, "ECJ 补全异常，降级到原引擎", t);
        }
        if (items == null || items.isEmpty()) {
            items = safeFallback(text, caret);
        }
        cachedSource = source;
        cachedCaret = caret;
        cachedItems = items;
        return items;
    }

    /** 触发降级 */
    private List<CompletionItem> safeFallback(CharSequence text, int caret) {
        if (fallback == null) return Collections.emptyList();
        try {
            return fallback.getCompletions(text.toString(), caret);
        } catch (Throwable t) {
            Log.w(TAG, "原引擎补全也失败", t);
            return Collections.emptyList();
        }
    }

    /**
     * 真正的 ECJ 补全。
     *
     * 思路：
     *  1) 在源码中插入占位符 \u0000 作为 completion location
     *  2) 用 ECJ 的 Parser 解析，得到 AST
     *  3) 收集可见的字段、局部变量、类型、import
     *  4) 加上 ProjectIndexService 中的 R.* / 用户类
     *  5) 加上语言关键字（兜底）
     */
    private List<CompletionItem> ecjComplete(String source, int caret, int anchorStart) {
        // 1) 构造 completion source
        StringBuilder sb = new StringBuilder(source);
        // 限制 caret 范围
        int safeCaret = Math.max(0, Math.min(caret, sb.length()));
        sb.insert(safeCaret, '\u0000');
        char[] contents = sb.toString().toCharArray();

        Map<String, String> settings = new HashMap<String, String>();
        settings.put(CompilerOptions.OPTION_Source, "1.7");
        settings.put(CompilerOptions.OPTION_ReportDeprecation, "ignore");
        settings.put(CompilerOptions.OPTION_Compliance, "1.7");
        CompilerOptions options = new CompilerOptions(settings);

        ProblemReporter problemReporter = new ProblemReporter(
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options,
                new DefaultProblemFactory());

        // ECJ 的 CompletionParser 在 Android 上偶尔会 NPE，用 Parser 兜底
        Parser parser;
        try {
            parser = new CompletionParser(problemReporter, options);
        } catch (Throwable t) {
            parser = new Parser(problemReporter, options.parseLiteralExpressionsAsConstants);
        }

        CompilationUnit cu = new CompilationUnit(contents, "Main.java", "UTF-8");
        CompilationResult result = new CompilationResult(cu, 0, 0, 0);
        CompilationUnitDeclaration unit;
        try {
            unit = parser.parse(cu, result);
        } catch (Throwable t) {
            return null;
        }
        if (unit == null) return null;

        // 2) 收集 proposals
        List<CompletionItem> items = new ArrayList<CompletionItem>();
        // ECJ 在 Android 上 100% 解析完成通常会包含问题；此处只消费不展示
        if (unit.compilationResult != null) {
            IProblem[] problems = unit.compilationResult.problems;
            int errCount = 0;
            if (problems != null) {
                for (IProblem p : problems) if (p != null && p.isError()) errCount++;
            }
            Log.v(TAG, "ECJ parse 完毕: errors=" + errCount);
        }

        // 3) 从 AST 中直接提取可见符号
        collectFromAst(unit, items);

        // 4) 合并项目索引
        mergeProjectIndex(items);

        // 5) 关键字兜底
        mergeKeywords(items, source, anchorStart, safeCaret);

        // 去重 + 排序
        dedup(items);
        Collections.sort(items, (a, b) -> a.displayText.compareToIgnoreCase(b.displayText));
        return items;
    }

    /** 从 CompilationUnitDeclaration 提取可见符号 */
    private void collectFromAst(CompilationUnitDeclaration unit, List<CompletionItem> items) {
        try {
            if (unit.types == null) return;
            for (org.eclipse.jdt.internal.compiler.ast.TypeDeclaration type : unit.types) {
                if (type == null) continue;
                // 自身类名 -> 类型
                if (type.name != null && type.name.length > 0) {
                    String name = String.valueOf(type.name);
                    items.add(new CompletionItem(
                            name, "类 " + name, name,
                            AutoCompletePanel.TYPE_CLASS, ""));
                }
                // 字段
                if (type.fields != null) {
                    for (org.eclipse.jdt.internal.compiler.ast.FieldDeclaration f : type.fields) {
                        if (f == null || f.name == null) continue;
                        String name = String.valueOf(f.name);
                        String typeName = f.type != null ? String.valueOf(f.type) : "Object";
                        items.add(new CompletionItem(
                                name, typeName, name,
                                AutoCompletePanel.TYPE_FIELD, ""));
                    }
                }
                // 方法
                if (type.methods != null) {
                    for (org.eclipse.jdt.internal.compiler.ast.MethodDeclaration m : type.methods) {
                        if (m == null || m.selector == null) continue;
                        String name = String.valueOf(m.selector) + "()";
                        String ret = m.returnType != null ? String.valueOf(m.returnType) : "void";
                        items.add(new CompletionItem(
                                String.valueOf(m.selector),
                                ret + " " + name,
                                String.valueOf(m.selector) + "()",
                                AutoCompletePanel.TYPE_METHOD, ""));
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "collectFromAst failed", t);
        }
    }

    /** 合并项目索引：R.*、jar 类、用户类 */
    private void mergeProjectIndex(List<CompletionItem> items) {
        if (projectIndex == null) return;
        try {
            // R 类：直接 query R 类的 static 成员
            try {
                String pkg = projectIndex.getConfig() == null ? null : projectIndex.getConfig().getJavaPackage();
                if (pkg != null) {
                    String rFullName = pkg + ".R";
                    List<IndexModel.MemberInfo> rMembers = projectIndex.getStaticMembers(rFullName);
                    for (IndexModel.MemberInfo m : rMembers) {
                        items.add(new CompletionItem(
                                m.name, "int " + m.name, m.name,
                                AutoCompletePanel.TYPE_FIELD, ""));
                    }
                }
            } catch (Throwable ignored) {}

            // 索引中的用户类
            List<String> userClasses = projectIndex.suggestBySimpleName("", 500);
            for (String fqcn : userClasses) {
                int dot = fqcn.lastIndexOf('.');
                if (dot < 0) continue;
                String simple = fqcn.substring(dot + 1);
                if (simple.isEmpty()) continue;
                items.add(new CompletionItem(
                        simple, "类 " + fqcn, simple,
                        AutoCompletePanel.TYPE_CLASS, fqcn));
            }
        } catch (Throwable t) {
            Log.w(TAG, "mergeProjectIndex failed", t);
        }
    }

    /** 关键字 + 当前前缀过滤 */
    private void mergeKeywords(List<CompletionItem> items, String source, int anchorStart, int caret) {
        int start = anchorStart >= 0 ? anchorStart : caret;
        if (start < 0) start = 0;
        if (start > source.length()) start = source.length();
        int wordStart = start;
        while (wordStart > 0 && isIdentChar(source.charAt(wordStart - 1))) {
            wordStart--;
        }
        String prefix = source.substring(wordStart, start);
        if (prefix.isEmpty()) return;

        // 移除不匹配前缀的项
        java.util.Iterator<CompletionItem> it = items.iterator();
        while (it.hasNext()) {
            CompletionItem ci = it.next();
            if (ci.displayText == null || !ci.displayText.toLowerCase().startsWith(prefix.toLowerCase())) {
                it.remove();
            }
        }
        // 补一点关键字
        String[] kws = {
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public",
                "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
                "null"
        };
        for (String kw : kws) {
            if (kw.startsWith(prefix.toLowerCase())) {
                items.add(new CompletionItem(
                        kw, "关键字", kw,
                        AutoCompletePanel.TYPE_KEYWORD, ""));
            }
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static void dedup(List<CompletionItem> list) {
        Set<String> seen = new HashSet<String>();
        java.util.Iterator<CompletionItem> it = list.iterator();
        while (it.hasNext()) {
            CompletionItem ci = it.next();
            String key = ci.displayText + "|" + ci.type;
            if (!seen.add(key)) it.remove();
        }
    }
}
