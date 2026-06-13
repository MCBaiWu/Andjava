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

/**
 * Off-black on off-white background color scheme
 */
public class ColorSchemeLight extends ColorScheme {
	@Override
	public boolean isDark() {
		return false;
	}

	@Override
	protected HashMap<Colorable, Integer> generateDefaultColors() {
		HashMap<Colorable, Integer> colors = new HashMap<Colorable, Integer>(Colorable.values().length);

		colors.put(Colorable.FOREGROUND, 0xFF1E1E1E);
		colors.put(Colorable.BACKGROUND, 0xFFF5F5F5);
		colors.put(Colorable.SELECTION_FOREGROUND, 0xFF1E1E1E);
		colors.put(Colorable.SELECTION_BACKGROUND, 0xFFB0D4F1);
		colors.put(Colorable.CARET_FOREGROUND, 0xFF000000);
		colors.put(Colorable.CARET_BACKGROUND, 0xFF000000);
		colors.put(Colorable.CARET_DISABLED, 0xFF808080);
		colors.put(Colorable.LINE_HIGHLIGHT, 0x1A000000);
		colors.put(Colorable.NON_PRINTING_GLYPH, 0xFF999999);
		colors.put(Colorable.COMMENT, 0xFF008000);
		colors.put(Colorable.KEYWORD, 0xFF0000FF);
		colors.put(Colorable.NAME, 0xFF1E1E1E);
		colors.put(Colorable.NUMBER, 0xFF098658);
		colors.put(Colorable.STRING, 0xFFA31515);
		colors.put(Colorable.SECONDARY, 0xFF795E26);
		colors.put(Colorable.DATATYPE, 0xFF267F99);
		colors.put(Colorable.YSF, 0xFF1E1E1E);
		colors.put(Colorable.METHOD_CALL, 0xFF795E26);
		colors.put(Colorable.TYPE_REFERENCE, 0xFF267F99);
		colors.put(Colorable.ANNOTATION, 0xFF795E26);
		colors.put(Colorable.R_REFERENCE, 0xFF267F99);

		return colors;
	}
}
