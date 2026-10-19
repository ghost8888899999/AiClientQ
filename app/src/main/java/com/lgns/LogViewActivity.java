package com.lgns;

import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_view);

        TextView tvLogContent = findViewById(R.id.tv_log_content);
        TextView tvLogCount = findViewById(R.id.tv_log_count);
        TextView tvLogEmpty = findViewById(R.id.tv_log_empty);
        ScrollView svLog = findViewById(R.id.sv_log);

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
