/*
 * Copyright (c) 2013 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */
package com.myopicmobile.textwarrior.common;

import java.util.List;
import java.util.Vector;
import java.util.stream.*;


//TODO Have all methods work with charOffsets and move all gap handling to logicalToRealIndex()
public class TextBuffer implements CharSequence
{

	@Override
	public IntStream chars() {
		// TODO: Implement this method
		return null;
	}

	@Override
	public IntStream codePoints() {
		// TODO: Implement this method
		return null;
	}


	@Override
	public int length()
	{
		// TODO: Implement this method
		return getTextLength();
	}
	
	// gap size must be > 0 to insert into full buffers successfully
	protected final static int MIN_GAP_SIZE = 50;
	protected char[] _contents;
	protected int _gapStartIndex;
	/** One past end of gap */
	protected int _gapEndIndex;
	protected int _lineCount;
	/** The number of times memory is allocated for the buffer */
	private int _allocMultiplier;
	private TextBufferCache _cache;
	private UndoStack _undoStack;

	/**
	 * 文档版本号：每次 insert/delete/setBuffer/shiftGapStart 自增。
	 * 异步 Lexer 在拿到结果时若发现版本已变，说明扫描期间文档被修改过，
	 * 结果应直接丢弃，避免把过期高亮写进 UI 导致"高亮走一下又回来"的闪烁。
	 */
	private volatile int _docVersion = 0;

	/** Continuous seq of chars that have the same format (color, font, etc.) */
	protected volatile List<Pair> _spans;


	public TextBuffer(){
		_contents = new char[MIN_GAP_SIZE + 1]; // extra char for EOF
		_contents[MIN_GAP_SIZE] = Language.EOF;
		_allocMultiplier = 1;
		_gapStartIndex = 0;
		_gapEndIndex = MIN_GAP_SIZE;
		_lineCount = 1;
		_cache = new TextBufferCache();
		_undoStack = new UndoStack(this);
	}

	/**
	 * Calculate the implementation size of the char array needed to store
	 * textSize number of characters.
	 * The implementation size may be greater than textSize because of buffer 
	 * space, cached characters and so on.
	 * 
	 * @param textSize
	 * @return The size, measured in number of chars, required by the 
	 * 		implementation to store textSize characters, or -1 if the request 
	 * 		cannot be satisfied
	 */
	public static int memoryNeeded(int textSize){
		long bufferSize = textSize + MIN_GAP_SIZE + 1; // extra char for EOF
		if(bufferSize < Integer.MAX_VALUE){
			return (int) bufferSize;
		}
		return -1;
	}

	synchronized public void setBuffer(char[] newBuffer, int textSize, int lineCount){
		_contents = newBuffer;
		initGap(textSize);
		_lineCount = lineCount;
		_allocMultiplier = 1;
		_docVersion++;
	}

	synchronized public void setBuffer(char[] newBuffer){
		int lineCount=1;
		int len=newBuffer.length;
		for(int i=0;i<len;i++){
			if(newBuffer[i]=='\n')
				lineCount++;
		}
		setBuffer(newBuffer,len,lineCount);
	}
	

	/**
	 * Returns a string of text corresponding to the line with index lineNumber.
	 * 
	 * @param lineNumber The index of the line of interest
	 * @return The text on lineNumber, or an empty string if the line does not exist
	 */
	synchronized public String getLine(int lineNumber){
		int startIndex = getLineOffset(lineNumber);
		
		if(startIndex < 0){
            return "";
		}
		int lineSize = getLineSize(lineNumber);
		
		return subSequence(startIndex, lineSize).toString();
	}
	
	/**
	 * Get the offset of the first character of the line with index lineNumber.
	 * The offset is counted from the beginning of the text.
	 * 
	 * @param lineNumber The index of the line of interest
	 * @return The character offset of lineNumber, or -1 if the line does not exist
	 */
	synchronized public int getLineOffset(int lineNumber){
		if(lineNumber < 0){
			return -1;
		}
		
		// start search from nearest known lineIndex~charOffset pair
		Pair cachedEntry = _cache.getNearestLine(lineNumber);
		int cachedLine = cachedEntry.getFirst();
		int cachedOffset = cachedEntry.getSecond();

		int offset;
		if (lineNumber > cachedLine){
			offset = findCharOffset(lineNumber, cachedLine, cachedOffset);
		}
		else if (lineNumber < cachedLine){
			offset = findCharOffsetBackward(lineNumber, cachedLine, cachedOffset);
		}
		else{
			offset = cachedOffset;
		}
		
		if (offset >= 0){
			// seek successful
			_cache.updateEntry(lineNumber, offset);
		}

		return offset;
	}

	/*
	 * Precondition: startOffset is the offset of startLine
	 */
	private int findCharOffset(int targetLine, int startLine, int startOffset){
		int workingLine = startLine;
		int offset = logicalToRealIndex(startOffset);

		TextWarriorException.assertVerbose(isValid(startOffset),
			"findCharOffsetBackward: Invalid startingOffset given");
		
		while((workingLine < targetLine) && (offset < _contents.length)){
			if (_contents[offset] == Language.NEWLINE){
				++workingLine;
			}
			++offset;
			
			// skip the gap
			if(offset == _gapStartIndex){
				offset = _gapEndIndex;
			}
		}

		if (workingLine != targetLine){
			return -1;
		}
		return realToLogicalIndex(offset);
	}

	/*
	 * Precondition: startOffset is the offset of startLine
	 */
	private int findCharOffsetBackward(int targetLine, int startLine, int startOffset){
		if (targetLine == 0){
			return 0;
		}

		TextWarriorException.assertVerbose(isValid(startOffset),
			"findCharOffsetBackward: Invalid startOffset given");
		
		int workingLine = startLine;
		int offset = logicalToRealIndex(startOffset);
		while(workingLine > (targetLine-1) && offset >= 0){ 
			// skip behind the gap
			if(offset == _gapEndIndex){
				offset = _gapStartIndex;
			}
			--offset;

			if (_contents[offset] == Language.NEWLINE){
				--workingLine;
			}

		}
		
		int charOffset;
		if (offset >= 0){
			// now at the '\n' of the line before targetLine
			charOffset = realToLogicalIndex(offset);
			++charOffset;
		}
		else{
			TextWarriorException.assertVerbose(false,
				"findCharOffsetBackward: Invalid cache entry or line arguments");
			charOffset = -1;
		}

		return charOffset;
	}

	/**
	 * Get the line number that charOffset is on
	 * 
	 * @return The line number that charOffset is on, or -1 if charOffset is invalid
	 */
	synchronized public int findLineNumber(int charOffset){
		if(!isValid(charOffset)){
			return -1;
		}
		
		Pair cachedEntry = _cache.getNearestCharOffset(charOffset);
		int line = cachedEntry.getFirst();
		int offset = logicalToRealIndex(cachedEntry.getSecond());
		int targetOffset = logicalToRealIndex(charOffset);
		int lastKnownLine = -1;
		int lastKnownCharOffset = -1;
		
		if (targetOffset > offset){
			// search forward
			while((offset < targetOffset) && (offset < _contents.length)){			
				if (_contents[offset] == Language.NEWLINE){
					++line;
					lastKnownLine = line;
					lastKnownCharOffset = realToLogicalIndex(offset) + 1;
				}
				
				++offset;
				// skip the gap
				if(offset == _gapStartIndex){
					offset = _gapEndIndex;
				}
			}
		}
		else if (targetOffset < offset){
			// search backward
			while((offset > targetOffset) && (offset > 0)){
				// skip behind the gap
				if(offset == _gapEndIndex){
					offset = _gapStartIndex;
				}
				--offset;
				
				if (_contents[offset] == Language.NEWLINE){
					lastKnownLine = line;
					lastKnownCharOffset = realToLogicalIndex(offset) + 1;
					--line;
				}
			}
		}


		if (offset == targetOffset){
			if(lastKnownLine != -1){
				// cache the lookup entry
				_cache.updateEntry(lastKnownLine, lastKnownCharOffset);
			}
			return line;
		}
		else{
			return -1;
		}
	}


	/**
	 * Finds the number of char on the specified line.
	 * All valid lines contain at least one char, which may be a non-printable
	 * one like \n, \t or EOF.
	 * 
	 * @return The number of chars in lineNumber, or 0 if the line does not exist.
	 */
	synchronized public int getLineSize(int lineNumber){
		int lineLength = 0;
		int pos = getLineOffset(lineNumber);
		
		if (pos != -1){
			pos = logicalToRealIndex(pos);
			//TODO consider adding check for (pos < _contents.length) in case EOF is not properly set
			while(_contents[pos] != Language.NEWLINE &&
			 _contents[pos] != Language.EOF){
				++lineLength;
				++pos;
				
				// skip the gap
				if(pos == _gapStartIndex){
					pos = _gapEndIndex;
				}
			}
			++lineLength; // account for the line terminator char
		}
		
		return lineLength;
	}
	
	/**
	 * Gets the char at charOffset
	 * Does not do bounds-checking.
	 * 
	 * @return The char at charOffset. If charOffset is invalid, the result 
	 * 		is undefined.
	 */
	synchronized public char charAt(int charOffset){
		return _contents[logicalToRealIndex(charOffset)];
	}

	/**
	 * Gets up to maxChars number of chars starting at charOffset
	 * 
	 * @return The chars starting from charOffset, up to a maximum of maxChars. 
	 * 		An empty array is returned if charOffset is invalid or maxChars is
	 *		non-positive.
	 */
	synchronized public CharSequence subSequence(int charOffset, int maxChars){
		if(!isValid(charOffset) || maxChars <= 0){
            return "";
		}
		int totalChars = maxChars;
		if((charOffset + totalChars) > getTextLength()){
			totalChars = getTextLength() - charOffset;
		}
		int realIndex = logicalToRealIndex(charOffset);
		char[] chars = new char[totalChars];
		
		for (int i = 0; i < totalChars; ++i){
			chars[i] = _contents[realIndex];
			++realIndex;
			// skip the gap
			if(realIndex == _gapStartIndex){
				realIndex = _gapEndIndex;
			}
		}
		
		return new String(chars);
	}
	
	/**
	 * Gets charCount number of consecutive characters starting from _gapStartIndex.
	 * 
	 * Only UndoStack should use this method. No error checking is done.
	 */
	char[] gapSubSequence(int charCount){
		char[] chars = new char[charCount];
		
		for (int i = 0; i < charCount; ++i){
			chars[i] = _contents[_gapStartIndex + i];
		}
		
		return chars;
	}

	/**
	 * Insert all characters in c into position charOffset.
	 *
	 * No error checking is done
	 */
	public synchronized void insert(char[] c, int charOffset, long timestamp,
			boolean undoable){
		if(undoable){
			_undoStack.captureInsert(charOffset, c.length, timestamp);
		}

		int insertIndex = logicalToRealIndex(charOffset);

		// shift gap to insertion point
		if (insertIndex != _gapEndIndex){
			if (isBeforeGap(insertIndex)){
				shiftGapLeft(insertIndex);
			}
			else{
				shiftGapRight(insertIndex);
			}
		}

		if(c.length >= gapSize()){
			growBufferBy(c.length - gapSize());
		}

		for (int i = 0; i < c.length; ++i){
			if(c[i] == Language.NEWLINE){
				++_lineCount;
			}
			_contents[_gapStartIndex] = c[i];
			++_gapStartIndex;
		}

		_cache.invalidateCache(charOffset);
		// 关键：立即平移编辑点下方的 span 偏移量
		shiftSpansAfterEdit(charOffset, c.length);
		_docVersion++;
	}

	/**
	 * Deletes up to totalChars number of char starting from position
	 * charOffset, inclusive.
	 *
	 * No error checking is done
	 */
	public synchronized void delete(int charOffset, int totalChars, long timestamp,
			boolean undoable){
		if(undoable){
			_undoStack.captureDelete(charOffset, totalChars, timestamp);
		}

		int newGapStart = charOffset + totalChars;

		// shift gap to deletion point
		if (newGapStart != _gapStartIndex){
			if (isBeforeGap(newGapStart)){
				shiftGapLeft(newGapStart);
			}
			else{
				shiftGapRight(newGapStart + gapSize());
			}
		}

		// increase gap size
		for(int i = 0; i < totalChars; ++i){
			--_gapStartIndex;
			if(_contents[_gapStartIndex] == Language.NEWLINE){
				--_lineCount;
			}
		}

		_cache.invalidateCache(charOffset);
		// 关键：立即平移编辑点下方的 span 偏移量（delta 为负数）
		shiftSpansAfterEdit(charOffset, -totalChars);
		_docVersion++;
	}

	/**
	 * Moves _gapStartIndex by displacement units. Note that displacement can be
	 * negative and will move _gapStartIndex to the left.
	 *
	 * Only UndoStack should use this method to carry out a simple undo/redo
	 * of insertions/deletions. No error checking is done.
	 */
	synchronized void shiftGapStart(int displacement){
		if(displacement >= 0){
			_lineCount += countNewlines(_gapStartIndex, displacement);
		}
		else{
			_lineCount -= countNewlines(_gapStartIndex + displacement, -displacement);
		}

		_gapStartIndex += displacement;
		_cache.invalidateCache(realToLogicalIndex(_gapStartIndex - 1) + 1);
		_docVersion++;
	}

	//does NOT skip the gap when examining consecutive positions
	private int countNewlines(int start, int totalChars){
		int newlines = 0;
		for(int i = start; i < (start + totalChars); ++i){
			if(_contents[i] == Language.NEWLINE){
				++newlines;
			}
		}
		
		return newlines;
	}
	
	/**
	 * Adjusts gap so that _gapStartIndex is at newGapStart
	 */
	final protected void shiftGapLeft(int newGapStart){
		while(_gapStartIndex > newGapStart){
			--_gapEndIndex;
			--_gapStartIndex;
			_contents[_gapEndIndex] = _contents[_gapStartIndex];
		}
	}

	/**
	 * Adjusts gap so that _gapEndIndex is at newGapEnd
	 */
	final protected void shiftGapRight(int newGapEnd){
		while(_gapEndIndex < newGapEnd){
			_contents[_gapStartIndex] = _contents[_gapEndIndex];
			++_gapStartIndex;
			++_gapEndIndex;
		}
	}
	
	/**
	 * Create a gap at the start of _contents[] and tack a EOF at the end.
	 * Precondition: real contents are from _contents[0] to _contents[contentsLength-1]
	 */
	protected void initGap(int contentsLength){
		int toPosition = _contents.length - 1;
		_contents[toPosition--] = Language.EOF; // mark end of file
		int fromPosition = contentsLength - 1;
		while(fromPosition >= 0){
			_contents[toPosition--] = _contents[fromPosition--];
		}
		_gapStartIndex = 0;
		_gapEndIndex = toPosition + 1; // went one-past in the while loop
	}
	
	/**
	 * Copies _contents into a buffer that is larger by
	 * 		minIncrement + INITIAL_GAP_SIZE * _allocCount bytes.
	 * 
	 * _allocMultiplier doubles on every call to this method, to avoid the 
	 * overhead of repeated allocations.
	 */
	protected void growBufferBy(int minIncrement){
		//TODO handle new size > MAX_INT or allocation failure
		int increasedSize = minIncrement + MIN_GAP_SIZE * _allocMultiplier;
		char[] temp = new char[_contents.length + increasedSize];
		int i = 0;
		while(i < _gapStartIndex){
			temp[i] = _contents[i];
			++i;
		}
		
		i = _gapEndIndex;
		while(i < _contents.length){
			temp[i + increasedSize] = _contents[i];
			++i;
		}

		_gapEndIndex += increasedSize;
		_contents = temp;
		_allocMultiplier <<= 1;
	}
	
	/**
	 * Returns the total number of characters in the text, including the 
	 * EOF sentinel char
	 */
	final synchronized public int getTextLength(){
		return _contents.length - gapSize();
	}

	synchronized public int getLineCount(){
		return _lineCount;
	}

	/**
	 * 返回当前文档版本号。每次 insert/delete/setBuffer/shiftGapStart 后该值
	 * 都会自增。异步 Lexer 在拿到结果时可通过比对版本号判断扫描期间文档
	 * 是否被修改过。
	 */
	public int getDocVersion(){
		return _docVersion;
	}
	
	final synchronized public boolean isValid(int charOffset){
		return (charOffset >= 0 && charOffset < getTextLength());
	}
	
	final protected int gapSize(){
		return _gapEndIndex - _gapStartIndex;
	}
	
	final protected int logicalToRealIndex(int i){
		if (isBeforeGap(i)){
			return i;
		}
		else{
			return i + gapSize(); 
		}
	}

	final protected int realToLogicalIndex(int i){
		if (isBeforeGap(i)){
			return i;
		}
		else{
			return i - gapSize(); 
		}
	}

	final protected boolean isBeforeGap(int i){
		return i < _gapStartIndex;
	}
	
	public void clearSpans(){
		_spans = new Vector<Pair>();
	    _spans.add(new Pair(0, Lexer.NORMAL));
	}
	
	public List<Pair> getSpans(){
		return _spans;
	}
	
	/**
	 * Sets the spans to use in the document.
	 * Spans are continuous sequences of characters that have the same format 
	 * like color, font, etc.
	 * 
	 * @param spans A collection of Pairs, where Pair.first is the start 
	 * 		position of the token, and Pair.second is the type of the token.
	 */
	public void setSpans(List<Pair> spans){
		_spans = spans;
	}
	
	/**
	 * 带版本号检查的 setSpans，防止竞态条件。
	 * 在 setSpans 到 invalidate 之间如果文档被修改，版本号会变化，
	 * 此时应该丢弃这次设置，避免使用过期的 spans。
	 * 
	 * @param spans 新的 span 列表
	 * @param expectedVersion 期望的文档版本号
	 * @return true 如果成功设置了 spans，false 如果版本号不匹配（已过期）
	 */
	public boolean setSpans(List<Pair> spans, int expectedVersion){
		// Double-check：在设置 spans 之前再次确认版本号
		// 防止在 lexDone 的版本号检查到 setSpans 之间文档被修改
		if(_docVersion != expectedVersion){
			return false;
		}
		_spans = spans;
		return true;
	}

	/**
	 * 在编辑点后平移 span 偏移量，防止高亮闪烁。
	 * 
	 * @param charOffset 编辑点位置
	 * @param delta 偏移量（正数表示插入，负数表示删除）
	 */
	private void shiftSpansAfterEdit(int charOffset, int delta){
		if(_spans == null || _spans.isEmpty()){
			return;
		}
		
		// 创建新的 span 列表
		List<Pair> newSpans = new Vector<Pair>();
		
		for(int i = 0; i < _spans.size(); i++){
			Pair span = _spans.get(i);
			int spanStart = span.getFirst();
			int spanType = span.getSecond();
			
			if(delta > 0){
				// 插入操作：所有 >= charOffset 的 span 都向后移动
				if(spanStart >= charOffset){
					newSpans.add(new Pair(spanStart + delta, spanType));
				}
				else{
					newSpans.add(span);
				}
			}
			else if(delta < 0){
				// 删除操作
				int deleteEnd = charOffset - delta; // delta 是负数
				if(spanStart >= deleteEnd){
					// span 在删除区域之后，向前移动
					newSpans.add(new Pair(spanStart + delta, spanType));
				}
				else if(spanStart >= charOffset){
					// span 在删除区域内，跳过
					continue;
				}
				else{
					// span 在删除区域之前，保持不变
					newSpans.add(span);
				}
			}
		}
		
		// 确保至少有一个 span
		if(newSpans.isEmpty()){
			newSpans.add(new Pair(0, Lexer.NORMAL));
		}
		
		_spans = newSpans;
	}

	/**
	 * Returns true if in batch edit mode
	 */
	public boolean isBatchEdit(){
		return _undoStack.isBatchEdit();
	}
	
	/**
	 * Signals the beginning of a series of insert/delete operations that can be 
	 * undone/redone as a single unit
	 */
	public void beginBatchEdit() {
		_undoStack.beginBatchEdit();
	}

	/**
	 * Signals the end of a series of insert/delete operations that can be 
	 * undone/redone as a single unit
	 */
	public void endBatchEdit() {
		_undoStack.endBatchEdit();
	}
	
	public boolean canUndo() {
		return _undoStack.canUndo();
	}
	
	public boolean canRedo() {
		return _undoStack.canRedo();
	}
	
	public int undo(){
		return _undoStack.undo();
	}
	
	public int redo(){
		return _undoStack.redo();
	}

	@Override
	public String toString()
	{
		// TODO: Implement this method
		int len=getTextLength();
		StringBuffer buf=new StringBuffer();
		for (int i=0;i < len;i++){
			char c=charAt(i);
			if (c==Language.EOF)
				break;
			buf.append(c);
		}
		return new String(buf);
	}
	
	
}
