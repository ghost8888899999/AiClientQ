package com.lgns.ui.activity;

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
import com.lgns.model.ArenaConfig;  // 修复导入路径
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.AppGlobal;

public class AutoClearConfigActivity extends AppCompatActivity {

    private SwitchCompat swAutoEnable;  // 使用 SwitchCompat
    private EditText etAutoClearVal;
    private Spinner spTimeUnit;
    private Spinner spPlayMode;  // 确保这里是 Spinner 类型
    private EditText etLogClearVal;
    private Spinner spLogTimeUnit;
    private TextView btnSave;  // 使用 TextView

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_clear);

        initViews();
        loadConfigs();
    }

    private void initViews() {
        // 绑定返回按钮并设置点击事件
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        swAutoEnable = findViewById(R.id.sw_auto_enable);  // SwitchCompat
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        spPlayMode = findViewById(R.id.sp_play_mode);  // Spinner
        etLogClearVal = findViewById(R.id.et_log_clear_val);
        spLogTimeUnit = findViewById(R.id.sp_log_time_unit);
        btnSave = findViewById(R.id.btn_save_auto_clear);  // TextView
        btnSave.setOnClickListener(v -> saveConfigs());
    }

    private void loadConfigs() {
        ArenaConfig.loadConfig(this); // 确保加载最新配置
        swAutoEnable.setChecked(ArenaConfig.autoClearEnable);
        etAutoClearVal.setText(String.valueOf(ArenaConfig.autoClearIntervalValue));
        spTimeUnit.setSelection(ArenaConfig.autoClearIntervalUnitIndex);
        spPlayMode.setSelection(ArenaConfig.playModeIndex);  // 正确使用 Spinner
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
            ArenaConfig.playModeIndex = spPlayMode.getSelectedItemPosition();  // 正确使用 Spinner
            ArenaConfig.logClearIntervalValue = logIntervalValue;
            ArenaConfig.logClearIntervalUnitIndex = spLogTimeUnit.getSelectedItemPosition();

            ArenaConfig.saveConfig(this);
            
            // 创建配置日志信息
            String configLog = "系统配置已保存: " +
                    "自动清除=" + (ArenaConfig.autoClearEnable ? "开启" : "关闭") + ", " +
                    "清除间隔=" + ArenaConfig.autoClearIntervalValue + " " + getTimeUnitText(ArenaConfig.autoClearIntervalUnitIndex) + ", " +
                    "入场模式=" + getPlayModeText(ArenaConfig.playModeIndex) + ", " +
                    "日志清除=" + ArenaConfig.logClearIntervalValue + " " + getTimeUnitText(ArenaConfig.logClearIntervalUnitIndex);
            
            // 添加日志到数据库
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            long result = dbHelper.insertLog(null, "CONFIG", configLog, System.currentTimeMillis());
            if (result == -1) {
                Log.e("AutoClearConfigActivity", "插入日志失败: " + configLog);
            } else {
                Log.d("AutoClearConfigActivity", "插入日志成功，ID: " + result + ", 内容: " + configLog);
            }
            
            // 设置 AppGlobal 的日志回调（如果当前在 MainActivity 中）
            AppGlobal.setLogCallback((keyNo, type, msg) -> {
                // 在主线程中更新UI
                runOnUiThread(() -> {
                    Toast.makeText(AutoClearConfigActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            });
            
            // 尝试通过 AppGlobal 发送日志到主界面
            AppGlobal.logConfigChange(configLog);
            
            // 显示 Toast 提示
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            Log.d("AutoClearConfigActivity", configLog); // 添加日志记录
            
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            Log.e("AutoClearConfigActivity", "数字格式错误", e);
        }
    }

    // 辅助方法：获取时间单位文本
    private String getTimeUnitText(int unitIndex) {
        String[] timeUnits = getResources().getStringArray(R.array.time_unit_array);
        if (unitIndex >= 0 && unitIndex < timeUnits.length) {
            return timeUnits[unitIndex];
        }
        return "小时"; // 默认值
    }

    // 辅助方法：获取入场模式文本
    private String getPlayModeText(int modeIndex) {
        String[] playModes = getResources().getStringArray(R.array.play_mode_array);
        if (modeIndex >= 0 && modeIndex < playModes.length) {
            return playModes[modeIndex];
        }
        return "能打就打"; // 默认值
    }

    public void showCurrentStatus(View view) {
        String status = ArenaConfig.autoClearEnable ? "已开启" : "已关闭";
        Toast.makeText(this, "自动清除状态: " + status, Toast.LENGTH_SHORT).show();
    }
}
