package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
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

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_save_auto_clear).setOnClickListener(v -> saveConfigs());
        findViewById(R.id.btn_init_db).setOnClickListener(v -> showInitDbDialog());

        initViews();
        loadConfigs();
    }

    private void showInitDbDialog() {
        new AlertDialog.Builder(this)
                .setTitle("初始化数据库")
                .setMessage("确定重新初始化数据库吗？\n\n将删除以下表并重建：\n• 当日状态\n• 失败日志\n• 操作日志\n\n分组信息不会被删除。")
                .setPositiveButton("初始化", (d, w) -> new Thread(() -> {
                    try {
                        AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                        db.recreateTablesExceptGroups();
                        runOnUiThread(() -> Toast.makeText(this, "数据库已初始化", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void initViews() {
        swAutoEnable = findViewById(R.id.sw_auto_enable);
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        spPlayMode = findViewById(R.id.sp_play_mode);
        etLogClearVal = findViewById(R.id.et_log_clear_val);
        spLogTimeUnit = findViewById(R.id.sp_log_time_unit);
        btnSave = findViewById(R.id.btn_save_auto_clear);
    }

    private void loadConfigs() {
        try {
            ArenaConfig.loadConfig(this);
            swAutoEnable.setChecked(ArenaConfig.autoClearEnable);
            etAutoClearVal.setText(String.valueOf(ArenaConfig.autoClearIntervalValue));
            
            // 边界检查：防止索引超出数组范围（覆盖安装兼容）
            int timeUnitIndex = ArenaConfig.autoClearIntervalUnitIndex;
            if (timeUnitIndex < 0 || timeUnitIndex >= spTimeUnit.getCount()) {
                String warnMsg = "配置项 autoClearIntervalUnitIndex 越界: " + timeUnitIndex + "，已重置为默认值 1(小时)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                timeUnitIndex = 1; // 默认：小时
            }
            spTimeUnit.setSelection(timeUnitIndex);
            
            // 入场模式：旧版本有3个选项(0/1/2)，新版本只有2个(0/1)
            int playModeIndex = ArenaConfig.playModeIndex;
            if (playModeIndex < 0 || playModeIndex >= spPlayMode.getCount()) {
                String warnMsg = "配置项 playModeIndex 越界: " + playModeIndex + "，已重置为默认值 0(能打就打)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                playModeIndex = 0; // 默认：能打就打
            }
            spPlayMode.setSelection(playModeIndex);
            
            etLogClearVal.setText(String.valueOf(ArenaConfig.logClearIntervalValue));
            
            int logTimeUnitIndex = ArenaConfig.logClearIntervalUnitIndex;
            if (logTimeUnitIndex < 0 || logTimeUnitIndex >= spLogTimeUnit.getCount()) {
                String warnMsg = "配置项 logClearIntervalUnitIndex 越界: " + logTimeUnitIndex + "，已重置为默认值 2(天)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                logTimeUnitIndex = 2; // 默认：天
            }
            spLogTimeUnit.setSelection(logTimeUnitIndex);
        } catch (Exception e) {
            String errMsg = "加载配置异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e("AutoClearConfig", errMsg, e);
            writeErrorToDb("CONFIG_ERROR", errMsg);
            
            // 使用默认值
            swAutoEnable.setChecked(false);
            etAutoClearVal.setText("1");
            spTimeUnit.setSelection(1);
            spPlayMode.setSelection(0);
            etLogClearVal.setText("1");
            spLogTimeUnit.setSelection(2);
        }
    }

    /**
     * 将配置错误写入日志数据库，方便后续分析
     */
    private void writeErrorToDb(String tag, String message) {
        try {
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            if (dbHelper != null) {
                dbHelper.insertLog(null, tag, message, System.currentTimeMillis());
            }
        } catch (Exception ignore) {
            Log.e("AutoClearConfig", "写入配置错误日志失败", ignore);
        }
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
