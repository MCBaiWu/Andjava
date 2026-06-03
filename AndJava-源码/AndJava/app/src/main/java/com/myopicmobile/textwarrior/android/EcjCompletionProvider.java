package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.util.Log;

import com.andjava.ide.project.IndexModel;
import com.andjava.ide.project.ProjectIndexService;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Eclipse ECJ 内核的代码补全引擎。
 *
 * 设计目标：
 * 1. 不修改任何原 CompletionEngine / AutoCompletePanel 已有字段和方法签名。
 * 2. 对外暴露同样的 {@link #getCompletions(CharSequence, int, int)} 风格 API。
 * 3. 在内部使用 ECJ 的 Parser 直接对源码 AST 做补全。
 * 4. 失败时（ECJ 在 Android 上偶发 null pointer）自动降级到原始 CompletionEngine。
 *
 * 说明：本类只使用 ECJ 3.x 公共 API（Parser / ProblemReporter / ast.*）。
 * 不使用 org.eclipse.jdt.internal.codeassist.* 包内的 CompletionParser，
 * 原因是该包不在我们的 build classpath 中。
 */
public class EcjCompletionProvider {

    private static final String TAG = "EcjCompletion";

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
            items = safeFallback(source, caret);
        }
        cachedSource = source;
        cachedCaret = caret;
        cachedItems = items;
        return items;
    }

    /** 触发降级 */
    private List<CompletionItem> safeFallback(String source, int caret) {
        if (fallback == null) return Collections.emptyList();
        try {
            return fallback.getCompletions(source, caret);
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

        // 2) 使用标准 ECJ Parser（CompletionParser 所在包不在 classpath）
        Parser parser = new Parser(problemReporter, options.parseLiteralExpressionsAsConstants);

        CompilationUnit cu = new CompilationUnit(contents, "Main.java", "UTF-8");
        CompilationResult result = new CompilationResult(cu, 0, 0, 0);
        CompilationUnitDeclaration unit;
        try {
            unit = parser.parse(cu, result);
        } catch (Throwable t) {
            return null;
        }
        if (unit == null) return null;

        // 仅供 logcat 调试使用
        if (unit.compilationResult != null) {
            IProblem[] problems = unit.compilationResult.problems;
            int errCount = 0;
            if (problems != null) {
                for (int i = 0; i < problems.length; i++) {
                    IProblem p = problems[i];
                    if (p != null && p.isError()) errCount++;
                }
            }
            Log.v(TAG, "ECJ parse 完毕: errors=" + errCount);
        }

        // 3) 从 AST 中直接提取可见符号
        List<CompletionItem> items = new ArrayList<CompletionItem>();
        collectFromAst(unit, items);

        // 4) 合并项目索引
        mergeProjectIndex(items);

        // 5) 关键字兜底
        mergeKeywords(items, source, anchorStart, safeCaret);

        // 去重 + 排序
        dedup(items);
        Collections.sort(items, new Comparator<CompletionItem>() {
            @Override
            public int compare(CompletionItem a, CompletionItem b) {
                if (a == null && b == null) return 0;
                if (a == null) return -1;
                if (b == null) return 1;
                String an = a.displayText == null ? "" : a.displayText;
                String bn = b.displayText == null ? "" : b.displayText;
                return an.compareToIgnoreCase(bn);
            }
        });
        return items;
    }

    /** 从 CompilationUnitDeclaration 提取可见符号 */
    private void collectFromAst(CompilationUnitDeclaration unit, List<CompletionItem> items) {
        try {
            if (unit.types == null) return;
            for (int i = 0; i < unit.types.length; i++) {
                TypeDeclaration type = unit.types[i];
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
                    for (int j = 0; j < type.fields.length; j++) {
                        FieldDeclaration f = type.fields[j];
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
                    for (int j = 0; j < type.methods.length; j++) {
                        MethodDeclaration m = type.methods[j];
                        if (m == null || m.selector == null) continue;
                        String ret = m.returnType != null ? String.valueOf(m.returnType) : "void";
                        String sel = String.valueOf(m.selector);
                        items.add(new CompletionItem(
                                sel,
                                ret + " " + sel + "()",
                                sel + "()",
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
                    if (rMembers != null) {
                        for (int i = 0; i < rMembers.size(); i++) {
                            IndexModel.MemberInfo m = rMembers.get(i);
                            if (m == null) continue;
                            items.add(new CompletionItem(
                                    m.name, "int " + m.name, m.name,
                                    AutoCompletePanel.TYPE_FIELD, ""));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 索引中的用户类
            try {
                List<String> userClasses = projectIndex.suggestBySimpleName("", 500);
                if (userClasses != null) {
                    for (int i = 0; i < userClasses.size(); i++) {
                        String fqcn = userClasses.get(i);
                        if (fqcn == null) continue;
                        int dot = fqcn.lastIndexOf('.');
                        if (dot < 0) continue;
                        String simple = fqcn.substring(dot + 1);
                        if (simple.length() == 0) continue;
                        items.add(new CompletionItem(
                                simple, "类 " + fqcn, simple,
                                AutoCompletePanel.TYPE_CLASS, fqcn));
                    }
                }
            } catch (Throwable ignored) {
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
        if (prefix.length() == 0) return;

        // 移除不匹配前缀的项
        Iterator<CompletionItem> it = items.iterator();
        while (it.hasNext()) {
            CompletionItem ci = it.next();
            if (ci == null || ci.displayText == null
                    || !ci.displayText.toLowerCase().startsWith(prefix.toLowerCase())) {
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
        String prefixLower = prefix.toLowerCase();
        for (int i = 0; i < kws.length; i++) {
            String kw = kws[i];
            if (kw.startsWith(prefixLower)) {
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
        Iterator<CompletionItem> it = list.iterator();
        while (it.hasNext()) {
            CompletionItem ci = it.next();
            if (ci == null) continue;
            String key = (ci.displayText == null ? "" : ci.displayText) + "|" + ci.type;
            if (!seen.add(key)) it.remove();
        }
    }
}
