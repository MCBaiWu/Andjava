package com.andjava.ide;

import android.app.ActionBar;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.view.MenuItem;

    public class SettingsActivity extends PreferenceActivity {

    @Override
        protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置标题
        setTitle("设置");

        // 启用 ActionBar 返回按钮
        ActionBar actionBar = getActionBar();
            if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
                    }

                        // 加载设置布局
                        addPreferencesFromResource(R.xml.preferences);

                        // 设置摘要显示当前选择的值
                    ListPreference javaVersionPref = (ListPreference) findPreference("java_version");
                if (javaVersionPref != null) {
        javaVersionPref.setSummary(javaVersionPref.getEntry());
    javaVersionPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
    ListPreference listPreference = (ListPreference) preference;
        int index = listPreference.findIndexOfValue((String) newValue);
            preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : "");
    return true;
}
            });
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // 处理返回按钮点击
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 静态工具方法：获取当前设置的 Java 版本
    public static String getJavaVersion(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString("java_version", "7");
    }
}
