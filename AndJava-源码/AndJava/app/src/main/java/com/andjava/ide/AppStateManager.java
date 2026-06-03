package com.andjava.ide;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局应用状态管理 - 保存当前打开的项目、已打开的文件列表、活动视图模式等
 */
public class AppStateManager {

    private static final String PREFS_NAME = "andjava_prefs";
    private static final String KEY_CURRENT_PROJECT = "current_project_path";
    private static final String KEY_OPEN_FILES = "open_files";
    private static final String KEY_ACTIVE_FILE_INDEX = "active_file_index";
    private static final String KEY_VIEW_MODE = "view_mode"; // "project" 或 "file"

    private static AppStateManager instance;
    private SharedPreferences prefs;

    private AppStateManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppStateManager getInstance(Context context) {
        if (instance == null) {
            instance = new AppStateManager(context);
        }
        return instance;
    }

    /**
     * 保存当前打开的项目根目录路径
     */
    public void saveCurrentProjectPath(String projectPath) {
        prefs.edit().putString(KEY_CURRENT_PROJECT, projectPath).apply();
    }

    /**
     * 获取上次保存的项目根目录路径
     */
    public String getCurrentProjectPath() {
        return prefs.getString(KEY_CURRENT_PROJECT, null);
    }

    /**
     * 清除项目路径记录
     */
    public void clearCurrentProjectPath() {
        prefs.edit().remove(KEY_CURRENT_PROJECT).apply();
    }

    /**
     * 保存已打开的文件列表（路径和修改状态）
     */
    public void saveOpenFiles(List<MainActivity.OpenFile> openFiles) {
        if (openFiles == null || openFiles.isEmpty()) {
            prefs.edit().remove(KEY_OPEN_FILES).apply();
            return;
        }
        JSONArray array = new JSONArray();
        try {
            for (MainActivity.OpenFile file : openFiles) {
                JSONObject obj = new JSONObject();
                obj.put("path", file.file.getAbsolutePath());
                obj.put("modified", file.modified);
                array.put(obj);
            }
            prefs.edit().putString(KEY_OPEN_FILES, array.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * 读取上次保存的打开文件列表
     */
    public List<OpenFileInfo> loadOpenFiles() {
        List<OpenFileInfo> result = new ArrayList<OpenFileInfo>();
        String json = prefs.getString(KEY_OPEN_FILES, null);
        if (TextUtils.isEmpty(json)) return result;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String path = obj.getString("path");
                boolean modified = obj.optBoolean("modified", false);
                File file = new File(path);
                if (file.exists()) {
                    result.add(new OpenFileInfo(file, modified));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 清除保存的打开文件列表
     */
    public void clearOpenFiles() {
        prefs.edit().remove(KEY_OPEN_FILES).apply();
    }

    /**
     * 保存当前激活的文件索引（ViewPager 中的位置）
     */
    public void saveActiveFileIndex(int index) {
        prefs.edit().putInt(KEY_ACTIVE_FILE_INDEX, index).apply();
    }

    /**
     * 获取上次保存的激活文件索引
     */
    public int getActiveFileIndex() {
        return prefs.getInt(KEY_ACTIVE_FILE_INDEX, 0);
    }

    /**
     * 保存当前视图模式（"project" 或 "file"）
     */
    public void saveViewMode(String mode) {
        prefs.edit().putString(KEY_VIEW_MODE, mode).apply();
    }

    /**
     * 获取上次保存的视图模式
     */
    public String getViewMode() {
        return prefs.getString(KEY_VIEW_MODE, "project");
    }

    // 内部类用于存储打开文件信息
    public static class OpenFileInfo {
        public File file;
        public boolean modified;

        public OpenFileInfo(File file, boolean modified) {
            this.file = file;
            this.modified = modified;
        }
    }
}
