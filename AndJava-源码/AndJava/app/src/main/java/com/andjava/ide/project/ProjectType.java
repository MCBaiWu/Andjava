package com.andjava.ide.project;

/**
 * 项目类型枚举
 * ANDROID_APP: 标准 Android 应用项目（生成 APK）
 * JAVA_CONSOLE: 纯 Java 命令行项目（直接 javac + java 运行）
 */
public enum ProjectType {
    ANDROID_APP,
    JAVA_CONSOLE
}
