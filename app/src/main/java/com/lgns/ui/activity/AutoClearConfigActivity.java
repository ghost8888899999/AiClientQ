package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.lgns.R;
import com.lgns.service.AutoClearWorker;

import java.util.concurrent.TimeUnit;

public class AutoClearConfigActivity extends AppCompatActivity {

    private static final String[] UNIT_ARRAY = {"分钟", "小时", "天"};
    private static final long MIN_ALLOW_MINUTE = 15;
    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";

    private SharedPreferences spAppSetting;
    private Switch swAutoEnable;
    private EditText etVal;
    private Spinner spUnit;
    private TextView tvStatus;
    private LinearLayout layoutTimeSetting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_clear);

        spAppSetting = getSharedPreferences("app_setting", MODE_PRIVATE);

        swAutoEnable = findViewById(R.id.sw_auto_enable);
        etVal = findViewById(R.id.et_auto_clear_val);
        spUnit = findViewById(R.id.sp_time_unit);
        tvStatus = findViewById(R.id.tv_auto_clear_status);
        layoutTimeSetting = findViewById(R.id.layout_time_setting);
        TextView btnSave = findViewById(R.id.btn_save_auto_clear);
        ImageButton btnBack = findViewById(R.id.btn_back);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, R.layout.layout_spinner_item, UNIT_ARRAY);
        unitAdapter.setDropDownViewResource(R.layout.layout_spinner_dropdown_item);
        spUnit.setAdapter(unitAdapter);

        loadCurrentConfig();

        btnBack.setOnClickListener(v -> finish());

        swAutoEnable.setOnCheckedChangeListener((buttonView, checked) -> {
            etVal.setEnabled(checked);
            spUnit.setEnabled(checked);
            layoutTimeSetting.setAlpha(checked ? 1.0f : 0.5f);
            updateStatusText(checked);
        });

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void loadCurrentConfig() {
        boolean isEnable = spAppSetting.getBoolean("auto_clear_enable", false);
        long oldMs = spAppSetting.getLong("auto_clear_ms", 0);

        swAutoEnable.setChecked(isEnable);
        if (oldMs > 0) {
            if (oldMs >= 1000L * 60 * 60 * 24) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60 * 24)));
                spUnit.setSelection(2);
            } else if (oldMs >= 1000L * 60 * 60) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60)));
                spUnit.setSelection(1);
            } else {
                etVal.setText(String.valueOf(oldMs / (1000L * 60)));
                spUnit.setSelection(0);
            }
        }

        etVal.setEnabled(isEnable);
        spUnit.setEnabled(isEnable);
        layoutTimeSetting.setAlpha(isEnable ? 1.0f : 0.5f);
        updateStatusText(isEnable);
    }

    private void updateStatusText(boolean enabled) {
        if (enabled) {
            tvStatus.setText("已启用，到达时长会自动清除私钥");
            tvStatus.setTextColor(0xFF4CAF50);
        } else {
            tvStatus.setText("已关闭（配置已保存，不会校验清除）");
            tvStatus.setTextColor(0xFF9E9E9E);
        }
    }

    private void saveConfig() {
        boolean newEnable = swAutoEnable.isChecked();
        if (!newEnable) {
            spAppSetting.edit().putBoolean("auto_clear_enable", false).apply();
            WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
            Toast.makeText(this, "已禁用自动清除", Toast.LENGTH_SHORT).show();
            updateStatusText(false);
            return;
        }

        String valStr = etVal.getText().toString().trim();
        if (valStr.isEmpty()) {
            Toast.makeText(this, "请输入时间数值，最小15", Toast.LENGTH_SHORT).show();
            return;
        }

        int val;
        try {
            val = Integer.parseInt(valStr);
        } catch (Exception e) {
            Toast.makeText(this, "请输入合法数字", Toast.LENGTH_SHORT).show();
            return;
        }

        int selPos = spUnit.getSelectedItemPosition();
        long oneUnitMs, inputMinute;
        if (selPos == 0) { oneUnitMs = 60 * 1000L; inputMinute = val; }
        else if (selPos == 1) { oneUnitMs = 60 * 60 * 1000L; inputMinute = val * 60L; }
        else { oneUnitMs = 24 * 60 * 60 * 1000L; inputMinute = val * 24 * 60L; }

        if (inputMinute < MIN_ALLOW_MINUTE) {
            Toast.makeText(this, "最小设置15分钟！", Toast.LENGTH_SHORT).show();
            return;
        }

        spAppSetting.edit().putBoolean("auto_clear_enable", true)
                .putLong("auto_clear_ms", val * oneUnitMs)
                .putLong("auto_clear_start_ts", System.currentTimeMillis()).apply();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                AutoClearWorker.class,
                Math.max(inputMinute, MIN_ALLOW_MINUTE),
                TimeUnit.MINUTES).build();

        WorkManager.getInstance(getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_AUTO_CLEAR,
                        ExistingPeriodicWorkPolicy.UPDATE, req);

        Toast.makeText(this, "自动清除设置完成：" + val + " " + UNIT_ARRAY[selPos], Toast.LENGTH_SHORT).show();
        updateStatusText(true);
    }
}
