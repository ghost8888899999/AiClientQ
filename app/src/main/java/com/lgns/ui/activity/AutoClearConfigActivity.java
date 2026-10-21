package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.lgns.R;
import com.lgns.model.ArenaConfig;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.AppGlobal;

public class AutoClearConfigActivity extends AppCompatActivity {

    private SwitchCompat swAutoEnable;
    private EditText etAutoClearVal;
    private Spinner spTimeUnit;
    private Spinner spPlayMode;
    private EditText etLogClearVal;
    private Spinner spLogTimeUnit;
    private TextView btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_clear);

        initViews();
        loadConfigs();
    }

    private void initViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            // 调试用：返回前强制 checkpoint，方便复制 db 文件
            AppLogDbHelper.getInstance(this).forceCheckpointForDebug();
            onBackPressed();
        });
        swAutoEnable = findViewById(R.id.sw_auto_enable);
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        spPlayMode = findViewById(R.id.sp_play_mode);
        etLogClearVal = findViewById(R.id.et_log_clear_val);
        spLogTimeUnit = findViewById(R.id.sp_log_time_unit);
        btnSave = findViewById(R.id.btn_save_auto_clear);
        btnSave.setOnClickListener(v -> saveConfigs());
    }

    private void loadConfigs() {
        ArenaConfig.loadConfig(this);
        swAutoEnable.setChecked(ArenaConfig.autoClearEnable);
        etAutoClearVal.setText(String.valueOf(ArenaConfig.autoClearIntervalValue));
        spTimeUnit.setSelection(ArenaConfig.autoClearIntervalUnitIndex);
        spPlayMode.setSelection(ArenaConfig.playModeIndex);
        etLogClearVal.setText(String.valueOf(ArenaConfig.logClearIntervalValue));
        spLogTimeUnit.setSelection(ArenaConfig.logClearIntervalUnitIndex);
    }

    private void saveConfigs() {
        try {
            int intervalValue = Integer.parseInt(etAutoClearVal.getText().toString());
            if (intervalValue <= 0) {
                Toast.makeText(this, "清除间隔必须大于0", Toast.LENGTH_SHORT).show();
                return;
            }

            int logIntervalValue = Integer.parseInt(etLogClearVal.getText().toString());
            if (logIntervalValue <= 0) {
                Toast.makeText(this, "日志清除间隔必须大于0", Toast.LENGTH_SHORT).show();
                return;
            }

            ArenaConfig.autoClearEnable = swAutoEnable.isChecked();
            ArenaConfig.autoClearIntervalValue = intervalValue;
            ArenaConfig.autoClearIntervalUnitIndex = spTimeUnit.getSelectedItemPosition();
            ArenaConfig.playModeIndex = spPlayMode.getSelectedItemPosition();
            ArenaConfig.logClearIntervalValue = logIntervalValue;
            ArenaConfig.logClearIntervalUnitIndex = spLogTimeUnit.getSelectedItemPosition();

            ArenaConfig.saveConfig(this);

            // 记录日志到数据库
            String configLog = "系统配置已保存: " +
                    "自动清除=" + (ArenaConfig.autoClearEnable ? "开启" : "关闭") + ", " +
                    "清除间隔=" + ArenaConfig.autoClearIntervalValue + " " + getTimeUnitText(ArenaConfig.autoClearIntervalUnitIndex) + ", " +
                    "入场模式=" + getPlayModeText(ArenaConfig.playModeIndex) + ", " +
                    "日志清除=" + ArenaConfig.logClearIntervalValue + " " + getTimeUnitText(ArenaConfig.logClearIntervalUnitIndex);
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            dbHelper.insertLog(null, "CONFIG", configLog, System.currentTimeMillis());
            AppGlobal.logConfigChange(configLog);

            // 重启定时清除调度器（使用新配置的间隔）
            long logClearMinutes = AppGlobal.getIntervalInMinutes(
                    ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
            AppGlobal.startLogClearScheduler(logClearMinutes);

            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            Log.d("AutoClearConfigActivity", configLog);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
        }
    }

    private String getTimeUnitText(int unitIndex) {
        String[] timeUnits = getResources().getStringArray(R.array.time_unit_array);
        if (unitIndex >= 0 && unitIndex < timeUnits.length) {
            return timeUnits[unitIndex];
        }
        return "小时";
    }

    private String getPlayModeText(int modeIndex) {
        String[] playModes = getResources().getStringArray(R.array.play_mode_array);
        if (modeIndex >= 0 && modeIndex < playModes.length) {
            return playModes[modeIndex];
        }
        return "能打就打";
    }

    public void showCurrentStatus(View view) {
        String status = ArenaConfig.autoClearEnable ? "已开启" : "已关闭";
        Toast.makeText(this, "自动清除状态: " + status, Toast.LENGTH_SHORT).show();
    }
}
