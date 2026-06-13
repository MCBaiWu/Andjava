package com.andjava.ide.project;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 项目编译配置数据模型
 * 由 {@link GradleConfigParser} 从 build.gradle 解析得到
 * 也支持默认构造（无 build.gradle 的纯 Java 项目）
 */
public class ProjectConfig {

    private ProjectType projectType = ProjectType.JAVA_CONSOLE;

    // android { } 块相关
    private String applicationId = "com.example.app";
    private int compileSdk = 33;
    private int minSdk = 21;
    private int targetSdk = 33;
    private int versionCode = 1;
    private String versionName = "1.0";

    // dependencies { } 块相关
    private final List<File> jarClasspath = new ArrayList<File>();
    private final List<String> mavenDependencies = new ArrayList<String>();

    // 路径信息
    private File projectRoot;
    private File appDir;
    private File buildGradleFile;

    // .classpath 项目专用路径
    private File sourceDir;    // kind="src" 的 path (如 "src")
    private File outputDir;    // kind="output" 的 path (如 "bin")

    // 错误诊断
    private final List<String> warnings = new ArrayList<String>();
    private final List<String> errors = new ArrayList<String>();

    public ProjectType getProjectType() {
        return projectType;
    }

    public void setProjectType(ProjectType projectType) {
        this.projectType = projectType;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public int getCompileSdk() {
        return compileSdk;
    }

    public void setCompileSdk(int compileSdk) {
        this.compileSdk = compileSdk;
    }

    public int getMinSdk() {
        return minSdk;
    }

    public void setMinSdk(int minSdk) {
        this.minSdk = minSdk;
    }

    public int getTargetSdk() {
        return targetSdk;
    }

    public void setTargetSdk(int targetSdk) {
        this.targetSdk = targetSdk;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    /**
     * 添加解析得到的 jar 文件
     */
    public void addJar(File jar) {
        if (jar != null && jar.exists() && !jarClasspath.contains(jar)) {
            jarClasspath.add(jar);
        }
    }

    public List<File> getJarClasspath() {
        return Collections.unmodifiableList(jarClasspath);
    }

    public void addMavenDependency(String notation) {
        if (notation != null && !mavenDependencies.contains(notation)) {
            mavenDependencies.add(notation);
            warnings.add("Maven 依赖未在本地解析: " + notation);
        }
    }

    public List<String> getMavenDependencies() {
        return Collections.unmodifiableList(mavenDependencies);
    }

    public File getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(File projectRoot) {
        this.projectRoot = projectRoot;
    }

    public File getAppDir() {
        return appDir;
    }

    public void setAppDir(File appDir) {
        this.appDir = appDir;
    }

    public File getBuildGradleFile() {
        return buildGradleFile;
    }

    public void setBuildGradleFile(File buildGradleFile) {
        this.buildGradleFile = buildGradleFile;
    }

    public File getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(File sourceDir) {
        this.sourceDir = sourceDir;
    }

    public File getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(File outputDir) {
        this.outputDir = outputDir;
    }

    public void addWarning(String msg) {
        warnings.add(msg);
    }

    public void addError(String msg) {
        errors.add(msg);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /**
     * 主包名（applicationId 转化为 java 包名）
     */
    public String getJavaPackage() {
        if (applicationId == null) return "com.example.app";
        return applicationId;
    }
}
