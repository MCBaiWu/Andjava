package com.andjava.ide.build;

import com.andjava.ide.project.ProjectConfig;

import java.io.File;

/**
 * 构建产物路径统一管理
 * 标准布局:
 *   projectRoot/
 *     build/
 *       bin/
 *         classesDebug/
 *         classesRelease/
 *         res/
 *         injected/AndroidManifest.xml
 *         jardex/
 *         classes.dex
 *         resources.ap_
 *         aapt_rules.txt
 *         app.apk
 *       gen/
 *         com/example/so/
 *           R.java
 *           BuildConfig.java
 *     app/
 *       build.gradle
 *       libs/*.jar
 *       src/main/
 *         java/...
 *         res/...
 *         assets/...
 *         AndroidManifest.xml
 */
public final class BuildPaths {

    private BuildPaths() {
    }

    public static File binDir(ProjectConfig cfg) {
        return new File(cfg.getProjectRoot(), "build/bin");
    }

    public static File genDir(ProjectConfig cfg) {
        return new File(cfg.getProjectRoot(), "build/gen");
    }

    public static File classesDebugDir(ProjectConfig cfg) {
        return new File(binDir(cfg), "classesDebug");
    }

    public static File classesReleaseDir(ProjectConfig cfg) {
        return new File(binDir(cfg), "classesRelease");
    }

    public static File resBinDir(ProjectConfig cfg) {
        return new File(binDir(cfg), "res");
    }

    public static File injectedDir(ProjectConfig cfg) {
        return new File(binDir(cfg), "injected");
    }

    public static File injectedManifest(ProjectConfig cfg) {
        return new File(injectedDir(cfg), "AndroidManifest.xml");
    }

    public static File jardexDir(ProjectConfig cfg) {
        return new File(binDir(cfg), "jardex");
    }

    public static File classesDex(ProjectConfig cfg) {
        return new File(binDir(cfg), "classes.dex");
    }

    public static File resourcesApk(ProjectConfig cfg) {
        return new File(binDir(cfg), "resources.ap_");
    }

    public static File aaptRulesFile(ProjectConfig cfg) {
        return new File(binDir(cfg), "aapt_rules.txt");
    }

    public static File finalApk(ProjectConfig cfg) {
        return new File(binDir(cfg), "app.apk");
    }

    /**
     * gen 目录下 applicationId 对应的包目录
     */
    public static File genPackageDir(ProjectConfig cfg) {
        return new File(genDir(cfg), cfg.getJavaPackage().replace('.', '/'));
    }

    public static File generatedR(ProjectConfig cfg) {
        return new File(genPackageDir(cfg), "R.java");
    }

    public static File generatedBuildConfig(ProjectConfig cfg) {
        return new File(genPackageDir(cfg), "BuildConfig.java");
    }

    public static File srcMainJavaDir(ProjectConfig cfg) {
        return new File(cfg.getAppDir(), "src/main/java");
    }

    public static File srcMainResDir(ProjectConfig cfg) {
        return new File(cfg.getAppDir(), "src/main/res");
    }

    public static File srcMainAssetsDir(ProjectConfig cfg) {
        return new File(cfg.getAppDir(), "src/main/assets");
    }

    public static File sourceManifest(ProjectConfig cfg) {
        return new File(cfg.getAppDir(), "src/main/AndroidManifest.xml");
    }

    public static File libsDir(ProjectConfig cfg) {
        return new File(cfg.getAppDir(), "libs");
    }
}
