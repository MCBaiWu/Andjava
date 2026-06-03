package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import com.andjava.ide.R;
import com.myopicmobile.textwarrior.common.Document;
import com.myopicmobile.textwarrior.common.DocumentProvider;
import com.myopicmobile.textwarrior.common.Language;
import com.myopicmobile.textwarrior.common.LanguageNonProg;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.myopicmobile.textwarrior.common.LanguageJava;

/**
 * 自动补全面板（UI 层）
 * 负责显示补全列表，处理用户选择，自动导入等。
 */
public class AutoCompletePanel {

    private FreeScrollingTextField _textField;
    private Context _context;
    private static Language _globalLanguage = LanguageJava.getInstance();
    private ListPopupWindow _autoCompletePanel;
    private AutoPanelAdapter _adapter;
    private Filter _filter;
    private int _verticalOffset;
    private int _height;
    private int _horizontal;
    private CharSequence _constraint;
    private GradientDrawable gd;
    private int _textColor;
    private boolean isShow = false;
    private final int PADDING = 20;
    private static final String LOG_PATH = "/storage/emulated/0/andjava/andjava.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    // 类型常量
    public static final int TYPE_KEYWORD = 0;
    public static final int TYPE_CLASS = 1;
    public static final int TYPE_INTERFACE = 2;
    public static final int TYPE_METHOD = 3;
    public static final int TYPE_FIELD = 4;
    public static final int TYPE_PACKAGE = 5;
    public static final int TYPE_VARIABLE = 6;
    public static final int TYPE_LOCAL_VAR = 7;

    // 核心引擎
    private CompletionEngine _completionEngine;

    // 图标缓存
    private Map<Integer, Integer> iconResMap;
    private Map<Integer, Bitmap> bitmapCache;

    public AutoCompletePanel(FreeScrollingTextField textField) {
        _textField = textField;
        _context = textField.getContext();
        initIconResources();
        initAutoCompletePanel();
        _completionEngine = new CompletionEngine(_context, _textField);
        _completionEngine.initialize();
        ensureLogDirectory();
    }

    private void ensureLogDirectory() {
        try {
            File dir = new File("/storage/emulated/0/andjava");
            if (!dir.exists()) dir.mkdirs();
        } catch (Exception ignored) {}
    }

    private void writeLog(String msg) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(LOG_PATH, true));
            pw.println(SDF.format(new Date()) + " [AutoComplete] " + msg);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (pw != null) pw.close();
        }
    }

    private void log(String msg) {
        writeLog(msg);
    }

    private void initIconResources() {
        iconResMap = new HashMap<Integer, Integer>();
        iconResMap.put(TYPE_PACKAGE, R.drawable.pakage);
        iconResMap.put(TYPE_INTERFACE, R.drawable.objects_light);
        iconResMap.put(TYPE_CLASS, R.drawable.objects);
        iconResMap.put(TYPE_KEYWORD, R.drawable.box_red);
        iconResMap.put(TYPE_METHOD, R.drawable.box_blue);
        iconResMap.put(TYPE_FIELD, R.drawable.box_light_blue);
        iconResMap.put(TYPE_VARIABLE, R.drawable.box_light_pink);
        iconResMap.put(TYPE_LOCAL_VAR, R.drawable.box_light_pink);
        bitmapCache = new HashMap<Integer, Bitmap>();
    }

    @SuppressWarnings("ResourceType")
    private void initAutoCompletePanel() {
        _autoCompletePanel = new ListPopupWindow(_context);
        _autoCompletePanel.setAnchorView(_textField);
        _adapter = new AutoPanelAdapter(_context);
        _autoCompletePanel.setAdapter(_adapter);
        _filter = _adapter.getFilter();
        _autoCompletePanel.setContentWidth(ListPopupWindow.WRAP_CONTENT);

        TypedArray array = _context.getTheme().obtainStyledAttributes(new int[]{
                                                                          android.R.attr.colorBackground,
                                                                          android.R.attr.textColorPrimary,
                                                                      });
        int backgroundColor = array.getColor(0, 0xFF00FF);
        int textColor = array.getColor(1, 0xFF00FF);
        array.recycle();

        gd = new GradientDrawable();
        gd.setColor(backgroundColor);
        gd.setCornerRadius(4);
        gd.setStroke(1, textColor);
        setTextColor(textColor);
        _autoCompletePanel.setBackgroundDrawable(gd);

        _autoCompletePanel.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    select(position);
                }
            });
    }

    public void setTextColor(int color) {
        _textColor = color;
        gd.setStroke(1, color);
        _autoCompletePanel.setBackgroundDrawable(gd);
    }

    public void setBackgroundColor(int color) {
        gd.setColor(color);
        _autoCompletePanel.setBackgroundDrawable(gd);
    }

    public void setBackground(Drawable drawable) {
        _autoCompletePanel.setBackgroundDrawable(drawable);
    }

    public void setWidth(int width) { _autoCompletePanel.setWidth(width); }
    private void setHeight(int height) {
        if (_height != height) {
            _height = height;
            _autoCompletePanel.setHeight(height);
        }
    }
    private void setHorizontalOffset(int horizontal) {
        horizontal = Math.min(horizontal, _textField.getWidth() / 2);
        if (_horizontal != horizontal) {
            _horizontal = horizontal;
            _autoCompletePanel.setHorizontalOffset(horizontal);
        }
    }
    private void setVerticalOffset(int verticalOffset) {
        int max = 0 - _autoCompletePanel.getHeight();
        if (verticalOffset > max) {
            _textField.scrollBy(0, verticalOffset - max);
            verticalOffset = max;
        }
        if (_verticalOffset != verticalOffset) {
            _verticalOffset = verticalOffset;
            _autoCompletePanel.setVerticalOffset(verticalOffset);
        }
    }

    public void update(CharSequence constraint) {
        _adapter.restart();
        _filter.filter(constraint);
    }

    public void onDotTyped() {
        _adapter.restart();
        _filter.filter("");
    }

    public void show() {
        if (!_autoCompletePanel.isShowing()) {
            _autoCompletePanel.show();
            _autoCompletePanel.getListView().setFadingEdgeLength(0);
            isShow = true;
        }
    }

    public void dismiss() {
        if (_autoCompletePanel.isShowing()) {
            isShow = false;
            _autoCompletePanel.dismiss();
        }
    }

    public boolean isShow() { return _autoCompletePanel.isShowing(); }

    public static synchronized void setLanguage(Language lang) { _globalLanguage = lang; }
    public static synchronized Language getLanguage() { return _globalLanguage; }

    public void selectFirst() { select(0); }

    public void select(int pos) {
        CompletionItem item = _adapter.getItem(pos);
        if (item == null) return;

        String insertText = item.commitText;
        if (item.type == TYPE_METHOD) {
            insertText = insertText + "()";
        }

        int start = _textField.getCaretPosition() - _constraint.length();
        _textField.replaceText(start, _constraint.length(), insertText);
        _adapter.abort();
        dismiss();

        if ((item.type == TYPE_CLASS || item.type == TYPE_INTERFACE) && item.fullClassName != null) {
            addImportIfNeeded(item.fullClassName);
        }
        if (item.type == TYPE_METHOD) {
            _textField.moveCaretLeft();
        }
    }

    private void addImportIfNeeded(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty() || fullClassName.startsWith("java.lang.")) return;

        DocumentProvider provider = _textField.createDocumentProvider();
        String text = provider.toString();
        if (text.contains("import " + fullClassName + ";")) return;

        String importLine = "import " + fullClassName + ";\n";
        int insertPos = 0;

        int pkgIdx = text.indexOf("package ");
        if (pkgIdx != -1) {
            int semi = text.indexOf(';', pkgIdx);
            if (semi != -1) {
                insertPos = semi + 1;
                while (insertPos < text.length() && Character.isWhitespace(text.charAt(insertPos))) {
                    insertPos++;
                }
            }
        }

        Pattern importPattern = Pattern.compile("^import\\s+[\\w\\.]+\\s*;", Pattern.MULTILINE);
        Matcher m = importPattern.matcher(text);
        int lastImportEnd = -1;
        while (m.find()) {
            lastImportEnd = m.end();
        }
        if (lastImportEnd != -1) {
            insertPos = lastImportEnd;
            while (insertPos < text.length() && (text.charAt(insertPos) == '\n' || text.charAt(insertPos) == '\r')) {
                insertPos++;
            }
            if (insertPos > 0 && text.charAt(insertPos - 1) != '\n') {
                importLine = "\n" + importLine;
            }
        } else {
            if (pkgIdx != -1) {
                importLine = "\n" + importLine;
            }
        }

        int caret = _textField.getCaretPosition();
        String newText = text.substring(0, insertPos) + importLine + text.substring(insertPos);
        Document doc = new Document(_textField);
        doc.setWordWrap(_textField.isWordWrap());
        doc.setText(newText);
        _textField.setDocumentProvider(new DocumentProvider(doc));

        int newCaret = caret + importLine.length();
        int delta = newCaret - _textField.getCaretPosition();
        if (delta != 0) {
            _textField.moveCaret(delta);
        }
    }

    // ---------- Adapter ----------
    class AutoPanelAdapter extends BaseAdapter implements Filterable {
        private int _h;
        private Flag _abort = new Flag();
        private List<CompletionItem> completionItems = new ArrayList<CompletionItem>();
        private LayoutInflater inflater;
        private DisplayMetrics dm;
        private int primaryTextColor;

        AutoPanelAdapter(Context c) {
            inflater = LayoutInflater.from(c);
            dm = c.getResources().getDisplayMetrics();
            TypedArray array = c.getTheme().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            primaryTextColor = array.getColor(0, Color.WHITE);
            array.recycle();
        }

        void abort() { _abort.set(); }
        void restart() { _abort.clear(); }
        @Override public int getCount() { return completionItems.size(); }
        @Override public CompletionItem getItem(int p) { return completionItems.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            ViewHolder h;
            if (cv == null) {
                LinearLayout ll = new LinearLayout(_context);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setPadding(dp(8), dp(4), dp(8), dp(4));
                ll.setGravity(Gravity.CENTER_VERTICAL);

                ImageView icon = new ImageView(_context);
                int s = dp(24);
                icon.setLayoutParams(new LinearLayout.LayoutParams(s, s));
                ((LinearLayout.LayoutParams) icon.getLayoutParams()).setMargins(0, 0, dp(8), 0);
                ll.addView(icon);

                LinearLayout tc = new LinearLayout(_context);
                tc.setOrientation(LinearLayout.VERTICAL);
                tc.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                ll.addView(tc);

                TextView main = new TextView(_context);
                main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                main.setTextColor(primaryTextColor);
                main.setSingleLine(true);
                tc.addView(main);

                TextView sub = new TextView(_context);
                sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                sub.setTextColor(Color.GRAY);
                sub.setSingleLine(true);
                tc.addView(sub);

                cv = ll;
                cv.setTag(new ViewHolder(icon, main, sub));
            }
            h = (ViewHolder) cv.getTag();

            CompletionItem item = getItem(pos);
            Integer iconRes = iconResMap.get(item.type);
            if (iconRes != null) {
                Bitmap bmp = bitmapCache.get(iconRes);
                if (bmp == null) {
                    bmp = BitmapFactory.decodeResource(_context.getResources(), iconRes);
                    bitmapCache.put(iconRes, bmp);
                }
                h.icon.setImageBitmap(bmp);
            }

            String display = item.displayText;
            SpannableString ss;
            if (item.type == TYPE_METHOD) {
                display = display + "()";
                ss = new SpannableString(display);
                ss.setSpan(new ForegroundColorSpan(primaryTextColor), 0, item.displayText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ForegroundColorSpan(Color.GRAY), item.displayText.length(), display.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ss = new SpannableString(display);
                int col = primaryTextColor;
                if (item.type == TYPE_KEYWORD) col = 0xff2c82c8;
                else if (item.type == TYPE_CLASS || item.type == TYPE_INTERFACE) col = 0xff6a8759;
                else if (item.type == TYPE_FIELD || item.type == TYPE_LOCAL_VAR) col = 0xff9876aa;
                ss.setSpan(new ForegroundColorSpan(col), 0, display.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            h.mainText.setText(ss);
            if (item.subText != null && !item.subText.isEmpty()) {
                h.subText.setText(item.subText);
                h.subText.setVisibility(View.VISIBLE);
            } else {
                h.subText.setVisibility(View.GONE);
            }
            return cv;
        }

        class ViewHolder {
            ImageView icon;
            TextView mainText, subText;
            ViewHolder(ImageView i, TextView m, TextView s) { icon = i; mainText = m; subText = s; }
        }

        private int dp(float n) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n, dm); }
        int getItemHeight() { if (_h == 0) _h = dp(48); return _h; }

        @Override
        public Filter getFilter() {
            return new Filter() {
                protected FilterResults performFiltering(CharSequence cs) {
                    FilterResults r = new FilterResults();
                    if (cs == null) {
                        r.values = new ArrayList<CompletionItem>();
                        r.count = 0;
                        return r;
                    }

                    String input = cs.toString();
                    _constraint = input;
                    int caret = _textField.getCaretPosition();

                    log("=== performFiltering ===");
                    log("input: '" + input + "', caret: " + caret);

                    List<CompletionItem> filtered = _completionEngine.getCompletions(input, caret);

                    Set<String> seen = new HashSet<String>();
                    List<CompletionItem> unique = new ArrayList<CompletionItem>();
                    for (CompletionItem item : filtered) {
                        String key = item.displayText + "|" + item.type;
                        if (!seen.contains(key)) {
                            seen.add(key);
                            unique.add(item);
                        }
                    }

                    log("result count: " + unique.size());
                    r.values = unique;
                    r.count = unique.size();
                    return r;
                }

                protected void publishResults(CharSequence cs, FilterResults r) {
                    if (r != null && r.count > 0 && !_abort.isSet()) {
                        completionItems.clear();
                        completionItems.addAll((List<CompletionItem>) r.values);
                        setHeight(getItemHeight() * Math.min(5, completionItems.size()));
                        setHorizontalOffset(PADDING);
                        setWidth(_textField.getWidth() - PADDING * 2);
                        int y = _textField.getCaretY() + _textField.rowHeight() / 2 - _textField.getScrollY();
                        setVerticalOffset(y - _textField.getHeight());
                        notifyDataSetChanged();
                        show();
                    } else {
                        notifyDataSetInvalidated();
                        dismiss();
                    }
                }
            };
        }
    }

    private static class Flag {
        private boolean set;
        synchronized void set() { set = true; }
        synchronized void clear() { set = false; }
        synchronized boolean isSet() { return set; }
    }
}
