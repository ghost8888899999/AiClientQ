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
    private TextView tvLogCount;
    private TextView tvLogEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_view);

        tvLogCount = findViewById(R.id.tv_log_count);
        tvLogEmpty = findViewById(R.id.tv_log_empty);
        RecyclerView rvLog = findViewById(R.id.rv_log);

        logAdapter = new LogAdapter();
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvLog.setAdapter(logAdapter);

        initViews();

        loadLogs(tvLogCount, tvLogEmpty);
    }

    private void initViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_export_log).setOnClickListener(v -> copyLogsToClipboard());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_log_title)
                .setMessage(R.string.dialog_clear_log_msg)
                .setPositiveButton(R.string.btn_clear, (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper.getInstance(this).clearAllLogData();
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.toast_log_cleared, Toast.LENGTH_SHORT).show();
                            loadLogs(tvLogCount, tvLogEmpty);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this,
                                getString(R.string.toast_clear_failed_fmt, e.getMessage()),
                                Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton(R.string.btn_cancel, null)
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
                    tvLogCount.setText(getString(R.string.common_items_fmt, count));
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvLogCount.setText(R.string.common_error));
            }
        }).start();
    }

    private void copyLogsToClipboard() {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();
                
                if (logList == null || logList.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.toast_no_log_to_copy, Toast.LENGTH_SHORT).show());
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
                ClipData clip = ClipData.newPlainText(getString(R.string.clip_label_operation_log), logText);
                clipboard.setPrimaryClip(clip);

                final int copiedCount = logList.size();
                runOnUiThread(() ->
                    Toast.makeText(this, getString(R.string.toast_log_copied_fmt, copiedCount), Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.toast_copy_failed_fmt, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
