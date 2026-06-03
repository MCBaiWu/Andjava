package com.myopicmobile.textwarrior.android;

/**
 * 补全项数据模型
 */
public class CompletionItem {
    public String displayText;   // 显示文本
    public String subText;       // 副文本（如类型信息）
    public String commitText;    // 实际插入文本
    public String fullClassName; // 全限定类名（用于自动导入）
    public int type;             // 类型常量，参考 AutoCompletePanel.TYPE_*

    public CompletionItem(String displayText, String subText, String commitText, int type, String fullClassName) {
        this.displayText = displayText;
        this.subText = subText;
        this.commitText = commitText;
        this.type = type;
        this.fullClassName = fullClassName;
    }
}
