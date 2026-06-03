package com.andjava.ide.project;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码补全/高亮所需的项目索引数据模型
 * 由 {@link ClassSourceLoader} 填充，由 {@link ProjectIndexService} 对外暴露
 */
public final class IndexModel {

    private IndexModel() {
    }

    /** 类的来源 */
    public static final int SOURCE_USER = 0;        // src/main/java 下用户源码
    public static final int SOURCE_GENERATED = 1;   // R / BuildConfig 等自动生成
    public static final int SOURCE_JAR = 2;         // 依赖 jar

    /** 成员种类 */
    public static final int KIND_FIELD = 0;
    public static final int KIND_METHOD = 1;
    public static final int KIND_CONSTRUCTOR = 2;
    public static final int KIND_INNER_CLASS = 3;

    /**
     * 类信息
     */
    public static class ClassInfo {
        public String fullName;          // 完整限定名: com.example.app.R.drawable
        public String simpleName;        // 简单名: drawable
        public String packageName;       // 包名: com.example.app
        public int source;               // SOURCE_*
        public boolean isStatic;
        public boolean isInterface;
        public boolean isEnum;
        public boolean isFinal;
        public String superClass;        // 父类全名（可空）
        public final List<String> interfaces = new ArrayList<String>();
        public final List<MemberInfo> members = new ArrayList<MemberInfo>();

        public ClassInfo() {
        }

        public ClassInfo(String fullName, int source) {
            this.fullName = fullName;
            this.source = source;
            int dot = fullName.lastIndexOf('.');
            this.simpleName = dot < 0 ? fullName : fullName.substring(dot + 1);
            this.packageName = dot < 0 ? "" : fullName.substring(0, dot);
        }
    }

    /**
     * 成员（字段/方法/构造器/内部类）信息
     */
    public static class MemberInfo {
        public String name;
        public int kind;                 // KIND_*
        public String type;              // 返回类型 / 字段类型
        public final List<String> parameterTypes = new ArrayList<String>(); // 方法参数
        public boolean isStatic;
        public boolean isPublic;
        public boolean isFinal;
        public String declaringClass;    // 所属类全名

        public MemberInfo() {
        }

        public MemberInfo(String name, int kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    /**
     * 诊断信息（解析错误、缺失文件等）
     */
    public static class Diagnostic {
        public static final int SEVERITY_ERROR = 0;
        public static final int SEVERITY_WARNING = 1;
        public static final int SEVERITY_INFO = 2;

        public int severity;
        public String message;
        public String file;        // 关联文件（可空）
        public int line;           // 行号（可空，0 表示未知）
        public long timestamp;

        public Diagnostic() {
        }

        public Diagnostic(int severity, String message) {
            this.severity = severity;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }

        public Diagnostic(int severity, String message, String file) {
            this(severity, message);
            this.file = file;
        }

        public Diagnostic(int severity, String message, String file, int line) {
            this(severity, message, file);
            this.line = line;
        }
    }
}
