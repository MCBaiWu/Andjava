/*
 * Copyright (c) 2013 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */

package com.myopicmobile.textwarrior.common;

import java.util.HashMap;

public abstract class ColorScheme {
	public enum Colorable {
		FOREGROUND, BACKGROUND, SELECTION_FOREGROUND, SELECTION_BACKGROUND,
		CARET_FOREGROUND, CARET_BACKGROUND, CARET_DISABLED, LINE_HIGHLIGHT,
		NON_PRINTING_GLYPH, COMMENT, KEYWORD, NAME, NUMBER,STRING,
		SECONDARY,DATATYPE,YSF,
		// Semantic highlighting (Java-aware)
		METHOD_CALL, TYPE_REFERENCE, ANNOTATION, R_REFERENCE
		}

	protected HashMap<Colorable, Integer> _colors = generateDefaultColors();

	public void setColor(Colorable colorable, int color) {
		_colors.put(colorable, color);
	}

	public int getColor(Colorable colorable) {
		Integer color = _colors.get(colorable);
		if (color == null) {
			TextWarriorException.fail("Color not specified for " + colorable);
			return 0;
		}
		return color.intValue();
	}

	// Currently, color scheme is tightly coupled with semantics of the token types
	public int getTokenColor(int tokenType) {
		Colorable element;
		switch (tokenType) {
			case Lexer.NORMAL:
				element = Colorable.FOREGROUND;
				break;
			case Lexer.KEYWORD:
				element = Colorable.KEYWORD;
				break;
			case Lexer.NAME:
				element = Colorable.NAME;
				break;
			case Lexer.DOUBLE_SYMBOL_LINE: //fall-through
			case Lexer.DOUBLE_SYMBOL_DELIMITED_MULTILINE:
				//case Lexer.SINGLE_SYMBOL_LINE_B:
				element = Colorable.COMMENT;
				break;
			case Lexer.SINGLE_SYMBOL_DELIMITED_A: //fall-through
			case Lexer.SINGLE_SYMBOL_DELIMITED_B:
				element = Colorable.STRING;
				break;
			case Lexer.NUMBER:
				element = Colorable.NUMBER;
				break;
			case Lexer.SINGLE_SYMBOL_LINE_A: //fall-through
			case Lexer.SINGLE_SYMBOL_WORD:
			case Lexer.OPERATOR:
				element = Colorable.SECONDARY;
				break;
			case Lexer.SINGLE_SYMBOL_LINE_B: //类型
				element = Colorable.NAME;
				break;
            case Lexer.DATATYPE:
                element = Colorable.DATATYPE;
                break;
            case Lexer.YSF:
                element = Colorable.YSF;
                break;
			case Lexer.METHOD_CALL:
				element = Colorable.METHOD_CALL;
				break;
			case Lexer.TYPE_REFERENCE:
				element = Colorable.TYPE_REFERENCE;
				break;
			case Lexer.ANNOTATION:
				element = Colorable.ANNOTATION;
				break;
			case Lexer.R_REFERENCE:
				element = Colorable.R_REFERENCE;
				break;
			default:
				TextWarriorException.fail("Invalid token type");
				element = Colorable.FOREGROUND;
				break;
		}
		return getColor(element);
	}

	/**
	 * Whether this color scheme uses a dark background, like black or dark grey.
	 */
	public abstract boolean isDark();
    protected HashMap<Colorable, Integer> generateDefaultColors() {
        // 深夜模式配色：深色背景，柔和的高亮颜色
        HashMap<Colorable, Integer> colors = new HashMap<Colorable, Integer>(Colorable.values().length);

        // 基础颜色
        colors.put(Colorable.FOREGROUND, 0xFFE0E0E0);        // 主文本颜色：浅灰白，柔和护眼
        colors.put(Colorable.BACKGROUND, 0xFF1E1E1E);        // 编辑器背景：深灰（类似 VS Code 默认）

        // 选中文本
        colors.put(Colorable.SELECTION_FOREGROUND, 0xFFFFFFFF); // 选中文本前景：纯白
        colors.put(Colorable.SELECTION_BACKGROUND, 0xFF3A3A3A); // 选中文本背景：深灰（比背景稍亮）

        // 光标
        colors.put(Colorable.CARET_FOREGROUND, 0xFF40A6FF);     // 光标颜色：亮蓝
        colors.put(Colorable.CARET_BACKGROUND, 0xFF40A6FF);     // 水滴背景色：与光标一致
        colors.put(Colorable.CARET_DISABLED, 0xFF808080);       // 禁用光标：灰色

        // 当前行高亮
        colors.put(Colorable.LINE_HIGHLIGHT, 0x28AAAAAA);       // 当前行半透明灰色覆盖层

        // 行号 / 不可见字符
        colors.put(Colorable.NON_PRINTING_GLYPH, 0xFF6A6A6A);   // 行号颜色：暗灰

        // 语法高亮
        colors.put(Colorable.COMMENT, 0xFF6A9955);              // 注释：柔和的绿色
        colors.put(Colorable.KEYWORD, 0xFF569CD6);              // 关键字：亮蓝色
        colors.put(Colorable.NAME, 0xFFE0E0E0);                 // 标识符/名称：保持前景色
        colors.put(Colorable.NUMBER, 0xFFB5CEA8);               // 数字：浅绿色
        colors.put(Colorable.STRING, 0xFFCE9178);               // 字符串：橙棕色
        colors.put(Colorable.SECONDARY, 0xFFC586C0);            // 宏定义/预处理器：淡紫色
        colors.put(Colorable.DATATYPE, 0xFF4EC9B0);             // 数据类型：青绿色
        colors.put(Colorable.YSF, 0xFFD4D4D4);                  // 运算符：亮灰色

        // 语义高亮（Java 真实语法感知）
        colors.put(Colorable.METHOD_CALL, 0xFFDCDCAA);          // 方法调用：浅黄
        colors.put(Colorable.TYPE_REFERENCE, 0xFF4EC9B0);       // 类型引用：青绿（与基础类型一致）
        colors.put(Colorable.ANNOTATION, 0xFFD7BA7D);           // 注解：橙黄
        colors.put(Colorable.R_REFERENCE, 0xFF9CDCFE);          // R.* / BuildConfig.* 引用：浅蓝

        return colors;
    }
}
