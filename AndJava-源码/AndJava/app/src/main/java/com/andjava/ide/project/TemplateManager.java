package com.andjava.ide.project;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TemplateManager {

    private static final String TEMPLATES_JSON = "templates/templates.json";
    private Context context;
    private List<TemplateCategory> categories;

    public static class Template {
        public String id;
        public String name;
        public String description;
        public String category;
        public String mainFile;          // 项目主文件相对路径
        public String iconPath;           // assets 中的图标路径
        public String zipPath;            // 模板压缩包路径
        public Bitmap iconBitmap;

        public Template(JSONObject json) throws JSONException {
            this.id = json.getString("id");
            this.name = json.getString("name");
            this.description = json.optString("description", "");
            this.category = json.getString("category");
            this.mainFile = json.optString("mainFile", "");
            this.iconPath = json.optString("iconPath", "");
            this.zipPath = json.getString("zipPath");
        }
    }

    public static class TemplateCategory {
        public String name;
        public List<Template> templates = new ArrayList<Template>();

        public TemplateCategory(String name) {
            this.name = name;
        }
    }

    public TemplateManager(Context context) {
        this.context = context.getApplicationContext();
        loadTemplates();
    }

    private void loadTemplates() {
        categories = new ArrayList<TemplateCategory>();
        Map<String, TemplateCategory> categoryMap = new HashMap<String, TemplateCategory>();

        String jsonStr = readAssetFile(TEMPLATES_JSON);
        if (jsonStr == null) return;

        try {
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Template template = new Template(obj);

                // 加载图标（可选）
                if (!template.iconPath.isEmpty()) {
                    try {
                        InputStream is = context.getAssets().open(template.iconPath);
                        template.iconBitmap = BitmapFactory.decodeStream(is);
                        is.close();
                    } catch (IOException e) {
                        template.iconBitmap = null;
                    }
                }

                String catName = template.category;
                TemplateCategory cat = categoryMap.get(catName);
                if (cat == null) {
                    cat = new TemplateCategory(catName);
                    categoryMap.put(catName, cat);
                    categories.add(cat);
                }
                cat.templates.add(template);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private String readAssetFile(String path) {
        StringBuilder sb = new StringBuilder();
        try {
            InputStream is = context.getAssets().open(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public List<TemplateCategory> getCategories() {
        return categories;
    }

    public Template findTemplateById(String id) {
        for (TemplateCategory cat : categories) {
            for (Template t : cat.templates) {
                if (t.id.equals(id)) return t;
            }
        }
        return null;
    }
}
