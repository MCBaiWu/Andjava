package com.myopicmobile.textwarrior.android;

import android.content.Context;
import android.util.Log;

import com.andjava.ide.project.ProjectIndexService;

import java.util.Collections;
import java.util.List;

/**
 * 代理包装：把 {@link AutoCompletePanel} 对 {@link CompletionEngine} 的调用转交给
 * ECJ 引擎 {@link EcjCompletionProvider}。当 ECJ 失败时回退到原始引擎。
 *
 * 该类继承自 {@link CompletionEngine}，所以可以直接通过反射注入到
 * {@link AutoCompletePanel#_completionEngine} 字段，无需改动原代码。
 */
public class EcjCompletionProxy extends CompletionEngine {

    private static final String TAG = "EcjCompletionProxy";

    private final EcjCompletionProvider provider;

    public EcjCompletionProxy(Context context, CompletionEngine delegate, FreeScrollingTextField field) {
        super(context, field);
        this.delegate = delegate;
        this.provider = new EcjCompletionProvider(context, delegate, field);
    }

    private CompletionEngine delegate;

    public CompletionEngine getDelegate() {
        return delegate;
    }

    public EcjCompletionProvider getProvider() {
        return provider;
    }

    @Override
    public void setProjectIndex(ProjectIndexService index) {
        super.setProjectIndex(index);
        if (provider != null) provider.setProjectIndex(index);
    }

    /**
     * 实际接管补全入口
     */
    @Override
    public List<CompletionItem> getCompletions(String inputPrefix, int caret) {
        try {
            List<CompletionItem> items = provider.getCompletions(inputPrefix, caret, caret);
            if (items != null && !items.isEmpty()) {
                return items;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ECJ provider failed, fallback", t);
        }
        if (delegate != null) {
            try {
                return delegate.getCompletions(inputPrefix, caret);
            } catch (Throwable t) {
                Log.w(TAG, "delegate failed", t);
            }
        }
        return Collections.emptyList();
    }
}
