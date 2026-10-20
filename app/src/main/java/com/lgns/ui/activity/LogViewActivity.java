package com.lgns.ui.activity;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.manager.AppLogDbHelper;
import com.lgns.R;
import com.lgns.task.TaskLogItem;
import com.lgns.ui.adapter.LogAdapter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewActivity extends AppCompatActivity {

    private LogAdapter logAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_view);

        ImageButton btnBack = findViewById(R.id.btn_back);
        TextView tvLogCount = findViewById(R.id.tv_log_count);
        TextView tvLogEmpty = findViewById(R.id.tv_log_empty);
        RecyclerView rvLog = findViewById(R.id.rv_log);
        ImageButton btnExportLog = findViewById(R.id.btn_export_log);
        ImageButton btnClearLog = findViewById(R.id.btn_clear_log);
        ImageButton btnInitDb = findViewById(R.id.btn_init_db);

        logAdapter = new LogAdapter();
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvLog.setAdapter(logAdapter);

        btnBack.setOnClickListener(v -> finish());

        loadLogs(tvLogCount, tvLogEmpty);

        btnExportLog.setOnClickListener(v -> copyLogsToClipboard());

        btnClearLog.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确定清空所有操作日志吗？\n\n此操作不可恢复！")
                .setPositiveButton("清空", (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        db.deleteAllLogs();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "已清空所有日志", Toast.LENGTH_SHORT).show();
                            loadLogs(tvLogCount, tvLogEmpty);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show());

        btnInitDb.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("初始化数据库")
                .setMessage("确定重新初始化数据库吗？\n\n将删除以下表并重建：\n• 当日状态\n• 失败日志\n• 操作日志\n\n分组信息不会被删除。")
                .setPositiveButton("初始化", (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        db.recreateTablesExceptGroups();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "数据库已初始化", Toast.LENGTH_SHORT).show();
                            loadLogs(tvLogCount, tvLogEmpty);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show());
    }

    private void loadLogs(TextView tvLogCount, TextView tvLogEmpty) {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();
                int count = logList.size();

                runOnUiThread(() -> {
                    if (count == 0) {
                        tvLogEmpty.setVisibility(View.VISIBLE);
                        findViewById(R.id.rv_log).setVisibility(View.GONE);
                    } else {
                        tvLogEmpty.setVisibility(View.GONE);
                        findViewById(R.id.rv_log).setVisibility(View.VISIBLE);
                        logAdapter.setItems(logList);
                    }
                    tvLogCount.setText("共 " + count + " 条");
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvLogCount.setText("错误"));
            }
        }).start();
    }

    private void copyLogsToClipboard() {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();
                
                if (logList == null || logList.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "没有日志可复制", Toast.LENGTH_SHORT).show());
                    return;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                StringBuilder sb = new StringBuilder();
                
                for (TaskLogItem item : logList) {
                    String time = sdf.format(new Date(item.timeMs));
                    String keyNo = item.keyNo != null ? item.keyNo : "-";
                    String type = item.logType != null ? item.logType : "";
                    String content = item.content != null ? item.content : "";
                    
                    // 纯文本格式：时间 编号 类型 内容
                    sb.append(String.format("%s %s %s %s\n", time, keyNo, type, content));
                }

                String logText = sb.toString();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("操作日志", logText);
                clipboard.setPrimaryClip(clip);

                runOnUiThread(() -> 
                    Toast.makeText(this, "已复制 " + logList.size() + " 条日志\n可粘贴到记事本或Excel", Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
