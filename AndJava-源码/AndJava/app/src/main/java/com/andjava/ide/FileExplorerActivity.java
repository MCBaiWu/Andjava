package com.andjava.ide;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文件浏览器活动
 */
public class FileExplorerActivity extends AppCompatActivity {

    private LinearLayout rootLayout;
    private TextView pathText;
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private List<FileItem> items = new ArrayList<>();
    private File currentDir;

    static class FileItem {
        File file;
        String name;
        boolean isDirectory;
        long size;

        FileItem(File f) {
            this.file = f;
            this.name = f.getName();
            this.isDirectory = f.isDirectory();
            this.size = f.length();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 创建根布局
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#263238"));
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(Color.parseColor("#1976D2"));
        header.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));

        // 返回按钮
        TextView backBtn = new TextView(this);
        backBtn.setText("←");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(24);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigateUp();
            }
        });
        header.addView(backBtn);

        // 路径显示
        pathText = new TextView(this);
        pathText.setTextColor(Color.WHITE);
        pathText.setTextSize(14);
        pathText.setPadding(dp2px(16), 0, 0, 0);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pathText.setLayoutParams(pathParams);
        header.addView(pathText);

        rootLayout.addView(header);

        // RecyclerView
        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1.0f));
        recyclerView.setBackgroundColor(Color.parseColor("#37474F"));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new FileAdapter();
        recyclerView.setAdapter(adapter);
        rootLayout.addView(recyclerView);

        setContentView(rootLayout);

        // 初始化
        currentDir = Environment.getExternalStorageDirectory();
        loadFiles();
    }

    private void loadFiles() {
        pathText.setText(currentDir.getAbsolutePath());
        items.clear();

        File[] files = currentDir.listFiles();
        if (files != null) {
            List<FileItem> dirs = new ArrayList<>();
            List<FileItem> fls = new ArrayList<>();

            for (File f : files) {
                if (!f.isHidden()) {
                    FileItem item = new FileItem(f);
                    if (f.isDirectory()) dirs.add(item);
                    else fls.add(item);
                }
            }

            Collections.sort(dirs, new Comparator<FileItem>() {
                @Override
                public int compare(FileItem a, FileItem b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            Collections.sort(fls, new Comparator<FileItem>() {
                @Override
                public int compare(FileItem a, FileItem b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            items.addAll(dirs);
            items.addAll(fls);
        }

        adapter.notifyDataSetChanged();
    }

    private void navigateUp() {
        File parent = currentDir.getParentFile();
        if (parent != null) {
            currentDir = parent;
            loadFiles();
        } else {
            finish();
        }
    }

    private void showFileOptions(final FileItem item) {
        String[] options = {"重命名", "删除"};
        new AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) {
                        showRenameDialog(item);
                    } else {
                        deleteFile(item);
                    }
                }
            })
            .show();
    }

    private void showRenameDialog(final FileItem item) {
        final EditText input = new EditText(this);
        input.setText(item.name);
        new AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String newName = input.getText().toString();
                    File newFile = new File(item.file.getParent(), newName);
                    if (item.file.renameTo(newFile)) {
                        loadFiles();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void deleteFile(FileItem item) {
        if (item.file.delete()) {
            loadFiles();
        }
    }

    @Override
    public void onBackPressed() {
        if (!currentDir.equals(Environment.getExternalStorageDirectory())) {
            navigateUp();
        } else {
            super.onBackPressed();
        }
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout layout = new LinearLayout(FileExplorerActivity.this);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
            layout.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView icon = new TextView(FileExplorerActivity.this);
            icon.setTextSize(20);
            icon.setLayoutParams(new LinearLayout.LayoutParams(
                dp2px(32), dp2px(32)));
            icon.setTag("icon");
            layout.addView(icon);

            LinearLayout textLayout = new LinearLayout(FileExplorerActivity.this);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            textLayout.setPadding(dp2px(12), 0, 0, 0);

            TextView name = new TextView(FileExplorerActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setTag("name");
            textLayout.addView(name);

            TextView info = new TextView(FileExplorerActivity.this);
            info.setTextColor(Color.GRAY);
            info.setTextSize(12);
            info.setTag("info");
            textLayout.addView(info);

            layout.addView(textLayout);

            return new ViewHolder(layout);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, final int position) {
            final FileItem item = items.get(position);

            TextView icon = (TextView) holder.itemView.findViewWithTag("icon");
            TextView name = (TextView) holder.itemView.findViewWithTag("name");
            TextView info = (TextView) holder.itemView.findViewWithTag("info");

            icon.setText(item.isDirectory ? "📁" : "📄");
            name.setText(item.name);

            if (item.isDirectory) {
                info.setText("文件夹");
            } else {
                String sizeStr = item.size < 1024 ? item.size + " B" : 
                    (item.size < 1024*1024 ? (item.size/1024) + " KB" : (item.size/(1024*1024)) + " MB");
                info.setText(sizeStr);
            }

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (item.isDirectory) {
                        currentDir = item.file;
                        loadFiles();
                    } else {
                        Intent result = new Intent();
                        result.putExtra("path", item.file.getAbsolutePath());
                        setResult(RESULT_OK, result);
                        finish();
                    }
                }
            });

            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showFileOptions(item);
                    return true;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ViewHolder(View itemView) {
                super(itemView);
            }
        }
    }

    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
