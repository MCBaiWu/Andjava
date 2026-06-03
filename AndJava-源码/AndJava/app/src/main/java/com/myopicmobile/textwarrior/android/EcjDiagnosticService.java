package com.myopicmobile.textwarrior.android;

import android.util.Log;

import com.andjava.ide.project.ProjectConfig;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 ECJ 的源码错误/警告诊断服务。
 *
 * 仅使用 ECJ 3.x 公共 API（Parser / ProblemReporter / IProblem）。
 * 因为 IProblem 3.x 没有 getSourceColumnNumber()，所以列号由源文本手工计算。
 */
public class EcjDiagnosticService {

    private static final String TAG = "EcjDiag";

    public enum Severity { ERROR, WARNING, INFO }

    public static class Diagnostic {
        public int startOffset;
        public int endOffset;
        public int startLine;
        public int endLine;
        public int startColumn;
        public int endColumn;
        public Severity severity;
        public String message;
        /** 原始问题 id（如 IProblem.UndefinedName） */
        public int problemId;
        public Diagnostic() {}
        public Diagnostic(int start, int end, Severity s, String msg, int pid) {
            this.startOffset = start;
            this.endOffset = end;
            this.severity = s;
            this.message = msg;
            this.problemId = pid;
        }
    }

    /**
     * 解析源码并返回诊断列表。
     */
    public static List<Diagnostic> analyze(String source, String unitName, ProjectConfig cfg) {
        if (source == null) return Collections.emptyList();
        char[] contents;
        try {
            contents = source.toCharArray();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        return analyzeFromChars(contents, unitName, cfg, source);
    }

    public static List<Diagnostic> analyzeFromChars(char[] contents, String unitName,
                                                    ProjectConfig cfg, String originalSource) {
        List<Diagnostic> out = new ArrayList<Diagnostic>();
        if (contents == null || contents.length == 0) return out;

        Map<String, String> settings = new HashMap<String, String>();
        settings.put(CompilerOptions.OPTION_Source, "1.7");
        settings.put(CompilerOptions.OPTION_ReportDeprecation, "warning");
        settings.put(CompilerOptions.OPTION_ReportUnusedLocal, "warning");
        settings.put(CompilerOptions.OPTION_ReportUnusedImport, "warning");
        settings.put(CompilerOptions.OPTION_ReportFallthroughCase, "warning");
        settings.put(CompilerOptions.OPTION_ReportEmptyStatement, "warning");
        settings.put(CompilerOptions.OPTION_Compliance, "1.7");
        CompilerOptions options;
        try {
            options = new CompilerOptions(settings);
        } catch (Throwable t) {
            return out;
        }

        ProblemReporter problemReporter;
        try {
            problemReporter = new ProblemReporter(
                    DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                    options,
                    new DefaultProblemFactory());
        } catch (Throwable t) {
            return out;
        }

        Parser parser = new Parser(problemReporter, options.parseLiteralExpressionsAsConstants);
        CompilationUnit cu = new CompilationUnit(contents,
                unitName == null ? "Main.java" : unitName, "UTF-8");
        CompilationResult result = new CompilationResult(cu, 0, 0, 0);
        CompilationUnitDeclaration unit;
        try {
            unit = parser.parse(cu, result);
        } catch (Throwable t) {
            Log.w(TAG, "parse failed", t);
            return out;
        }
        if (unit == null) return out;

        IProblem[] problems = unit.compilationResult == null ? null : unit.compilationResult.problems;
        if (problems == null) return out;

        for (int i = 0; i < problems.length; i++) {
            IProblem p = problems[i];
            if (p == null) continue;
            Diagnostic d = new Diagnostic();
            int srcStart = Math.max(0, p.getSourceStart());
            int srcEnd = Math.max(srcStart, p.getSourceEnd());
            d.startOffset = srcStart;
            d.endOffset = srcEnd;
            d.startLine = p.getSourceLineNumber();
            if (d.startLine <= 0) d.startLine = 1;
            d.endLine = d.startLine;
            // IProblem 3.x 不提供 getSourceColumnNumber()，手工从源文本计算
            d.startColumn = lineColumnToOffset(originalSource, d.startLine, 1);
            d.startColumn = columnFromOffset(originalSource, srcStart, d.startLine);
            d.endColumn = columnFromOffset(originalSource, srcEnd, d.endLine);
            d.message = p.getMessage();
            d.problemId = p.getID();
            if (p.isError()) {
                d.severity = Severity.ERROR;
            } else if (p.isWarning()) {
                d.severity = Severity.WARNING;
            } else {
                d.severity = Severity.INFO;
            }
            out.add(d);
        }
        return out;
    }

    /**
     * 给定 0-based offset 和所在行号，返回 0-based column。
     * 当 lineNumber 不可信时退化为 0。
     */
    private static int columnFromOffset(String source, int offset, int lineNumber) {
        if (source == null) return 0;
        if (offset < 0) return 0;
        if (offset > source.length()) offset = source.length();
        int curLine = 1;
        int lineStart = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                curLine++;
                if (curLine == lineNumber) {
                    lineStart = i + 1;
                }
            }
        }
        if (lineNumber == 1) lineStart = 0;
        return offset - lineStart;
    }

    /**
     * 把一个编译错误文本中的行列转换为源码偏移（用于非 ECJ 来源的错误，如 aapt 错误）。
     * line/column 从 1 开始。
     */
    public static int lineColumnToOffset(String source, int line, int column) {
        if (source == null) return 0;
        if (line < 1) line = 1;
        if (column < 1) column = 1;
        int curLine = 1;
        int idx = 0;
        int n = source.length();
        while (idx < n && curLine < line) {
            if (source.charAt(idx) == '\n') curLine++;
            idx++;
        }
        int col = 1;
        while (idx < n && col < column) {
            char c = source.charAt(idx);
            if (c == '\n') break;
            idx++;
            col++;
        }
        return idx;
    }

    public static Diagnostic makeInfo(int start, int end, String msg) {
        return new Diagnostic(start, end, Severity.INFO, msg, -1);
    }

    public static Diagnostic makeError(int start, int end, String msg) {
        return new Diagnostic(start, end, Severity.ERROR, msg, -1);
    }

    public static Diagnostic makeWarning(int start, int end, String msg) {
        return new Diagnostic(start, end, Severity.WARNING, msg, -1);
    }
}
