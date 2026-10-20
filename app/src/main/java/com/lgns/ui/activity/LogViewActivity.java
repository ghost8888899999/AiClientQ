package com.lgns.ui.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.lgns.manager.AppLogDbHelper;
import com.lgns.R;
import com.lgns.task.TaskLogItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_view);

        ImageButton btnBack = findViewById(R.id.btn_back);
        TextView tvLogContent = findViewById(R.id.tv_log_content);
        TextView tvLogCount = findViewById(R.id.tv_log_count);
        TextView tvLogEmpty = findViewById(R.id.tv_log_empty);
        ScrollView svLog = findViewById(R.id.sv_log);
        ImageButton btnClearLog = findViewById(R.id.btn_clear_log);
        ImageButton btnInitDb = findViewById(R.id.btn_init_db);

        btnBack.setOnClickListener(v -> finish());

        loadLogs(tvLogContent, tvLogCount, tvLogEmpty, svLog);

        btnClearLog.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确定清空所有运行日志吗？\n\n此操作不可恢复！")
                .setPositiveButton("清空", (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        db.deleteAllLogs();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "✅ 已清空所有日志", Toast.LENGTH_SHORT).show();
                            loadLogs(tvLogContent, tvLogCount, tvLogEmpty, svLog);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "❌ 清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show());

        btnInitDb.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("初始化数据库")
                .setMessage("确定重新初始化数据库吗？\n\n将删除以下表并重建：\n• daily_status（当日状态）\n• failure_log（失败日志）\n• task_log（运行日志）\n\n分组信息不会被删除。")
                .setPositiveButton("初始化", (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        db.recreateTablesExceptGroups();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "✅ 数据库已初始化", Toast.LENGTH_SHORT).show();
                            loadLogs(tvLogContent, tvLogCount, tvLogEmpty, svLog);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "❌ 初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show());
    }

    private void loadLogs(TextView tvLogContent, TextView tvLogCount, TextView tvLogEmpty, ScrollView svLog) {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                StringBuilder sb = new StringBuilder();
                for (TaskLogItem item : logList) {
                    String timeStr = sdf.format(new Date(item.timeMs));
                    sb.append("[").append(timeStr).append("]");
                    if (item.keyNo != null) {
                        sb.append("[").append(item.keyNo).append("]");
                    }
                    sb.append(item.content);
                    sb.append("\n");
                }
                String showText = sb.toString();
                int count = logList.size();

                runOnUiThread(() -> {
                    if (count == 0) {
                        tvLogEmpty.setVisibility(View.VISIBLE);
                        svLog.setVisibility(View.GONE);
                    } else {
                        tvLogEmpty.setVisibility(View.GONE);
                        svLog.setVisibility(View.VISIBLE);
                        tvLogContent.setText(showText);
                    }
                    tvLogCount.setText("共 " + count + " 条");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvLogContent.setText("读取日志异常：" + e.getMessage());
                    tvLogCount.setText("错误");
                });
            }
        }).start();
    }
}
