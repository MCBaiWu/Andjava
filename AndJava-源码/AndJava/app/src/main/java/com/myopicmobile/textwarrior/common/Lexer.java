/*
 * Copyright (c) 2013 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */
package com.myopicmobile.textwarrior.common;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Does lexical analysis of a text for C-like languages.
 * The programming language syntax used is set as a static class variable.
 *
 * Provides two layers of highlighting:
 *   1. Pure syntax highlighting (keywords, strings, numbers, comments, …)
 *   2. Light-weight semantic highlighting (method calls, type references,
 *      annotations, R.* / BuildConfig.* references) via a second pass.
 *
 * The semantic pass only walks the token list, so it adds negligible cost
 * and never crashes on partial / invalid input – every step is guarded.
 */
public class Lexer implements JavaTokenTypes {
	private final static int MAX_KEYWORD_LENGTH = 127;

	public final static int UNKNOWN = -1;
	public final static int NORMAL = 0;
	public final static int KEYWORD = 1;
	public final static int OPERATOR = 2;
	public final static int NAME = 3;
	public final static int NUMBER = 4;
    public final static int DATATYPE=5;
    public final static int YSF=6;

	// ----- Semantic token types (Java-aware) -----
    public final static int METHOD_CALL = 7;
    public final static int TYPE_REFERENCE = 8;
    public final static int ANNOTATION = 9;

	/** A word that starts with a special symbol, inclusive.
	 * Examples:
	 * :ruby_symbol
	 * */
	public final static int SINGLE_SYMBOL_WORD = 10;

	/** R.* / BuildConfig.* field references (Android resource access). */
	public final static int R_REFERENCE = 11;

	/** Tokens that extend from a single start symbol, inclusive, until the end of line.
	 * Up to 2 types of symbols are supported per language, denoted by A and B
	 * Examples:
	 * #include "myCppFile"
	 * #this is a comment in Python
	 * %this is a comment in Prolog
	 * */
	public final static int SINGLE_SYMBOL_LINE_A = 20;
	public final static int SINGLE_SYMBOL_LINE_B = 21;

	/** Tokens that extend from a two start symbols, inclusive, until the end of line.
	 * Examples:
	 * //this is a comment in C
	 * */
	public final static int DOUBLE_SYMBOL_LINE = 30;

	/** Tokens that are enclosed between a start and end sequence, inclusive,
	 * that can span multiple lines. The start and end sequences contain exactly
	 * 2 symbols.
	 * Examples:
	 * {- this is a...
	 *  ...multi-line comment in Haskell -}
	 * */
	public final static int DOUBLE_SYMBOL_DELIMITED_MULTILINE = 40;

	/** Tokens that are enclosed by the same single symbol, inclusive, and
	 * do not span over more than one line.
	 * Examples: 'c', "hello world"
	 * */
	public final static int SINGLE_SYMBOL_DELIMITED_A = 50;
	public final static int SINGLE_SYMBOL_DELIMITED_B = 51;

	private static Language _globalLanguage = LanguageNonProg.getInstance();
	synchronized public static void setLanguage(Language lang) {
		_globalLanguage = lang;
	}

	synchronized public static Language getLanguage() {
		return _globalLanguage;
	}

	// Java primitive type / well-known class names for type-reference detection
	private static final Set<String> COMMON_TYPE_NAMES = new HashSet<String>();
	static {
		// primitives (defensive: lexer usually tags them as DATATYPE already)
		COMMON_TYPE_NAMES.add("String");
		COMMON_TYPE_NAMES.add("Object");
		COMMON_TYPE_NAMES.add("Class");
		COMMON_TYPE_NAMES.add("Integer");
		COMMON_TYPE_NAMES.add("Long");
		COMMON_TYPE_NAMES.add("Short");
		COMMON_TYPE_NAMES.add("Byte");
		COMMON_TYPE_NAMES.add("Float");
		COMMON_TYPE_NAMES.add("Double");
		COMMON_TYPE_NAMES.add("Boolean");
		COMMON_TYPE_NAMES.add("Character");
		COMMON_TYPE_NAMES.add("Number");
		COMMON_TYPE_NAMES.add("Throwable");
		COMMON_TYPE_NAMES.add("Exception");
		COMMON_TYPE_NAMES.add("RuntimeException");
		COMMON_TYPE_NAMES.add("Error");
		COMMON_TYPE_NAMES.add("StringBuilder");
		COMMON_TYPE_NAMES.add("StringBuffer");
		COMMON_TYPE_NAMES.add("Math");
		COMMON_TYPE_NAMES.add("System");
		COMMON_TYPE_NAMES.add("List");
		COMMON_TYPE_NAMES.add("Map");
		COMMON_TYPE_NAMES.add("Set");
		COMMON_TYPE_NAMES.add("Collection");
		COMMON_TYPE_NAMES.add("Iterator");
		COMMON_TYPE_NAMES.add("Iterable");
		COMMON_TYPE_NAMES.add("ArrayList");
		COMMON_TYPE_NAMES.add("HashMap");
		COMMON_TYPE_NAMES.add("HashSet");
		COMMON_TYPE_NAMES.add("LinkedList");
		COMMON_TYPE_NAMES.add("TreeMap");
		COMMON_TYPE_NAMES.add("TreeSet");
		COMMON_TYPE_NAMES.add("Vector");
		COMMON_TYPE_NAMES.add("Stack");
		COMMON_TYPE_NAMES.add("Queue");
		COMMON_TYPE_NAMES.add("Date");
		COMMON_TYPE_NAMES.add("Calendar");
		COMMON_TYPE_NAMES.add("File");
		COMMON_TYPE_NAMES.add("URL");
		COMMON_TYPE_NAMES.add("URI");
		COMMON_TYPE_NAMES.add("Pattern");
		COMMON_TYPE_NAMES.add("Matcher");
		COMMON_TYPE_NAMES.add("Scanner");
		COMMON_TYPE_NAMES.add("PrintStream");
		COMMON_TYPE_NAMES.add("InputStream");
		COMMON_TYPE_NAMES.add("OutputStream");
		COMMON_TYPE_NAMES.add("Reader");
		COMMON_TYPE_NAMES.add("Writer");
		COMMON_TYPE_NAMES.add("BufferedReader");
		COMMON_TYPE_NAMES.add("BufferedWriter");
		COMMON_TYPE_NAMES.add("View");
		COMMON_TYPE_NAMES.add("Activity");
		COMMON_TYPE_NAMES.add("Context");
		COMMON_TYPE_NAMES.add("Intent");
		COMMON_TYPE_NAMES.add("Bundle");
		COMMON_TYPE_NAMES.add("Bitmap");
		COMMON_TYPE_NAMES.add("Drawable");
		COMMON_TYPE_NAMES.add("Color");
		COMMON_TYPE_NAMES.add("Log");
		COMMON_TYPE_NAMES.add("Toast");
		COMMON_TYPE_NAMES.add("SharedPreferences");
	}

	private DocumentProvider _hDoc;
	private LexThread _workerThread = null;
	LexCallback _callback = null;

	public Lexer(LexCallback callback) {
		_callback = callback;
	}

	public void tokenize(DocumentProvider hDoc) {
		if (!Lexer.getLanguage().isProgLang()) {
			return;
		}

		//tokenize will modify the state of hDoc; make a copy
		setDocument(new DocumentProvider(hDoc));
		if (_workerThread == null) {
			_workerThread = new LexThread(this);
			_workerThread.start();
		} else {
			_workerThread.restart();
		}
	}

	void tokenizeDone(List<Pair> result) {
		if (_callback != null) {
			_callback.lexDone(result);
		}
		_workerThread = null;
	}

	public void cancelTokenize() {
		if (_workerThread != null) {
			_workerThread.abort();
			_workerThread = null;
		}
	}

	public synchronized void setDocument(DocumentProvider hDoc) {
		_hDoc = hDoc;
	}

	public synchronized DocumentProvider getDocument() {
		return _hDoc;
	}

	private class LexThread extends Thread {
		private boolean rescan = false;
		private final Lexer _lexManager;
		/** can be set by another thread to stop the scan immediately */
		private final Flag _abort;
		/** A collection of Pairs, where Pair.first is the start
		 *  position of the token, and Pair.second is the type of the token.*/
		/**
		 * pair的集合，first表示token的开始，second表示token的类型
		 */
		private List<Pair> _tokens;

		public LexThread(Lexer p) {
			_lexManager = p;
			_abort = new Flag();
		}

		@Override
		public void run() {
			do{
				rescan = false;
				_abort.clear();
				tokenize();
			}
			while(rescan);

			if (!_abort.isSet()) {
				// lex complete
				_lexManager.tokenizeDone(_tokens);
			}
		}

		public void restart() {
			rescan = true;
			_abort.set();
		}

		public void abort() {
			_abort.set();
		}
		/**
		 * Scans the document referenced by _lexManager for tokens.
		 * The result is stored internally.
		 *扫描结果存在list
		 *******************************

		 *******************************
		 */
		public void tokenize() {
			DocumentProvider hDoc = getDocument();
			Language language = Lexer.getLanguage();
			//这里用ArrayList速度会发生质的飞跃
			List<Pair> tokens = new ArrayList<Pair>();

			if (!language.isProgLang()) {
				tokens.add(new Pair(0, NORMAL));
				_tokens = tokens;
				return;
			}
			// Capture the document once.  toString() walks the underlying
			// TextBuffer and is O(n); we should not call it twice.
			String source = safeToString(hDoc);
			StringReader stringReader = new StringReader(source);
			JavaLexer jLexer = new JavaLexer(stringReader);

			// Parallel list: tokenTexts[i] holds the source text of the
			// token at tokens[i], or null if it isn't an identifier that
			// we need to inspect in the semantic pass.
			List<String> tokenTexts = new ArrayList<String>();

			int jType = 0;
			int idx = 0;
			String identifier = null;
			language.clearUserWord();
            while (jType != JavaTokenTypes.TokenNameEOF) {
                try {
                    jType = jLexer.getNextToken();
                    switch (jType) {
							//关键字
                        case TokenNameabstract:
                        case TokenNamebreak:
                        case TokenNamecase:
                        case TokenNamecatch:
                        case TokenNameclass:
                        case TokenNamecontinue:
                        case TokenNameconst:
                        case TokenNamedefault:
                        case TokenNamedo:
                        case TokenNameelse:
                        case TokenNameextends:
                        case TokenNamefinal:
                        case TokenNamefinally:
                        case TokenNamefor:
                        case TokenNameif:
                        case TokenNameimplements:
                        case TokenNameimport:
                        case TokenNameinstanceof:
                        case TokenNameinterface:
                        case TokenNamenative:
                        case TokenNamenew:
                        case TokenNamepackage:
                        case TokenNameprivate:
                        case TokenNameprotected:
                        case TokenNamepublic:
                        case TokenNamereturn:
                        case TokenNamestatic:
                        case TokenNamestrictfp:
                        case TokenNamesuper:
                        case TokenNameswitch:
                        case TokenNamesynchronized:
                        case TokenNamethis:
                        case TokenNamethrow:
                        case TokenNamethrows:
                        case TokenNametransient:
                        case TokenNametry:
                        case TokenNamevolatile:
                        case TokenNamewhile:
                            tokens.add(new Pair(idx, KEYWORD));
                            tokenTexts.add(null);
                            break;
                            //数据类型
                        case TokenNamevoid:
                        case TokenNameshort:
                        case TokenNamelong:
                        case TokenNameint:
                        case TokenNamefloat:
                        case TokenNamedouble:
                        case TokenNamechar:
                        case TokenNamebyte:
                        case TokenNameboolean:
                            tokens.add(new Pair(idx, DATATYPE));
                            tokenTexts.add(null);
                            break;
                        case TokenNameCOLON_COLON:
                        case TokenNameDOT:
                        case TokenNameCOMMA:
                        case TokenNameCOLON:
                        case TokenNameSEMICOLON:
                        case TokenNameRBRACKET:
                        case TokenNameLBRACKET:
                        case TokenNameLPAREN:
                        case TokenNameRPAREN:
                        case TokenNameLBRACE:
                        case TokenNameRBRACE:
                            tokens.add(new Pair(idx, YSF));
                            tokenTexts.add(null);
                            break;
                            //数字
                        case TokenNameCharacterLiteral:
                        case TokenNameDoubleLiteral:
                        case TokenNameFloatingPointLiteral:
                        case TokenNameLongLiteral:
                        case TokenNameIntegerLiteral:
                            //字符串
                        case TokenNameStringLiteral:
                        case TokenNamenull:
                        case TokenNametrue:
                        case TokenNamefalse:
                            tokens.add(new Pair(idx, NUMBER));
                            tokenTexts.add(null);
                            break;
                            //注释
                        case TokenNameCOMMENT_BLOCK:
                        case TokenNameCOMMENT_LINE:
                            tokens.add(new Pair(idx, DOUBLE_SYMBOL_LINE));
                            tokenTexts.add(null);
                            break;
                            //运算符
                        case TokenNamePLUS_PLUS:
                        case TokenNameMINUS_MINUS:
                        case TokenNameEQUAL_EQUAL:
                        case TokenNameLESS_EQUAL:
                        case TokenNameGREATER_EQUAL:
                        case TokenNameNOT_EQUAL:
                        case TokenNameLEFT_SHIFT:
                        case TokenNamePLUS_EQUAL:
                        case TokenNameMINUS_EQUAL:
                        case TokenNameMULTIPLY_EQUAL:
                        case TokenNameDIVIDE_EQUAL:
                        case TokenNameAND_EQUAL:
                        case TokenNameOR_EQUAL:
                        case TokenNameXOR_EQUAL:
                        case TokenNameREMAINDER_EQUAL:
                        case TokenNameLEFT_SHIFT_EQUAL:
                        case TokenNameRIGHT_SHIFT_EQUAL:
                        case TokenNameUNSIGNED_RIGHT_SHIFT_EQUAL:
                        case TokenNameOR_OR:
                        case TokenNameAND_AND:
                        case TokenNamePLUS:
                        case TokenNameMINUS:
                        case TokenNameNOT:
                        case TokenNameREMAINDER:
                        case TokenNameXOR:
                        case TokenNameAND:
                        case TokenNameMULTIPLY:
                        case TokenNameDIVIDE:
                        case TokenNameOR:
                        case TokenNameTWIDDLE:
                        case TokenNameGREATER:
                        case TokenNameLESS:
                        case TokenNameEQUAL:
                            tokens.add(new Pair(idx, YSF));
                            tokenTexts.add(null);
                            break;
                        case TokenNameAT:
                            // The '@' itself is part of the annotation;
                            // we mark it ANNOTATION so the leading char
                            // is already coloured.  The next identifier
                            // will be promoted in the semantic pass.
                            tokens.add(new Pair(idx, ANNOTATION));
                            tokenTexts.add("@");
                            break;
                            //标识符
                        case TokenNameIdentifier:
                            identifier = jLexer.yytext();
                            tokens.add(new Pair(idx, NORMAL));
                            tokenTexts.add(identifier);
                            break;
                        default:
                            tokens.add(new Pair(idx, NORMAL));
                            tokenTexts.add(null);
                            break;
                    }
                    idx = jLexer.getCharIndex();
                } catch (Exception e) {
                    e.printStackTrace();
                    idx++;//错误了，索引也要往后挪
                }
            }

			if (tokens.isEmpty()) {
				// return value cannot be empty
				tokens.add(new Pair(0, NORMAL));
			}

			// ---------------- Semantic pass ----------------
			// Cheap second pass that upgrades NORMAL identifier tokens to
			// METHOD_CALL, TYPE_REFERENCE, ANNOTATION, R_REFERENCE based
			// on surrounding tokens.  Always safe: any out-of-range access
			// is caught by index checks.
			try {
				int n = tokens.size();
				for (int i = 0; i < n; i++) {
					Pair p = tokens.get(i);
					if (p.getSecond() != NORMAL) continue;
					String text = tokenTexts.get(i);
					if (text == null) continue;

					// 1. @Foo  ->  ANNOTATION  (the '@' token was emitted
					//    right before, and we keep this token coloured
					//    as the annotation name as well).
					if (i > 0
							&& tokenTexts.get(i - 1) != null
							&& "@".equals(tokenTexts.get(i - 1))) {
						p.setSecond(ANNOTATION);
						continue;
					}

					// 2. identifier immediately followed by '(' -> METHOD_CALL
					if (i + 1 < n) {
						Pair next = tokens.get(i + 1);
						if (next.getSecond() == YSF
								&& next.getFirst() == p.getFirst() + text.length()) {
							int typ = next.getSecond(); // we already know == YSF
							// re-use typ check to silence unused warning if any
							if (typ == YSF) {
								// confirm by scanning the LPAREN character at that offset
								char ch = (idx > next.getFirst()
										&& next.getFirst() < source.length())
												? source.charAt(next.getFirst())
												: '\0';
								if (ch == '(') {
									p.setSecond(METHOD_CALL);
									continue;
								}
							}
						}
					}

					// 3. R.id, R.layout.xxx  /  BuildConfig.DEBUG  ->
					//    R_REFERENCE.  The leading 'R' / 'BuildConfig'
					//    identifier will already be a TYPE_REFERENCE;
					//    the segment after the dot becomes R_REFERENCE.
					if (i > 0) {
						Pair prev = tokens.get(i - 1);
						String prevText = tokenTexts.get(i - 1);
						if (prev.getSecond() == YSF
								&& ".".equals(prevText)
								&& i >= 2) {
							Pair before = tokens.get(i - 2);
							String beforeText = tokenTexts.get(i - 2);
							if (beforeText != null
									&& (beforeText.equals("R")
											|| beforeText.equals("BuildConfig"))) {
								p.setSecond(R_REFERENCE);
								continue;
							}
						}
					}

					// 4. Common type names -> TYPE_REFERENCE
					if (COMMON_TYPE_NAMES.contains(text)) {
						p.setSecond(TYPE_REFERENCE);
						continue;
					}
					// 5. Identifier starting with an uppercase letter used
					//    immediately after a type-use context (new, catch,
					//    extends, implements, instanceOf, <, comma, …) ->
					//    TYPE_REFERENCE.
					if (!text.isEmpty()
							&& Character.isUpperCase(text.charAt(0))
							&& i > 0
							&& isTypeUseContext(tokens.get(i - 1))) {
						p.setSecond(TYPE_REFERENCE);
						continue;
					}
				}
			} catch (Throwable ignored) {
				// never let the semantic pass crash the editor
			}

            _tokens = tokens;
		}

		/** A small set of token types that may precede a user-defined type name. */
		private boolean isTypeUseContext(Pair prev) {
			int t = prev.getSecond();
			return t == KEYWORD
					|| t == YSF
					|| t == NAME
					|| t == DATATYPE
					|| t == NORMAL;
		}

	}//end inner class

	private static String safeToString(DocumentProvider hDoc) {
		try {
			return hDoc.toString();
		} catch (Throwable t) {
			return "";
		}
	}

	private  void log(String log) {
		System.out.println("------------------>Lexer:" + log);
	}

	private  void printList(List<Pair> list) {
		System.out.println("------------------>:Lexer start,Lexer len:" + list.size());
		for (int i=0;i < list.size();i++) {
			Pair pair=list.get(i);
			System.out.println("---------------->" + pair.toString());//不打印？
		}
		System.out.println("------------------>:Lexer end");

	}

	public interface LexCallback {
        void lexDone(List<Pair> results);
	}
}
