package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.lgns.util.Web3AndroidUtil;

public class AutoClearConfigActivity extends AppCompatActivity {

    private SwitchCompat swAutoEnable;
    private EditText etAutoClearVal;
    private Spinner spTimeUnit;
    private Spinner spPlayMode;
    private SwitchCompat swLogClearEnable;
    private EditText etLogClearVal;
    private Spinner spLogTimeUnit;
    private LinearLayout layoutLogInterval;
    private TextView tvAutoClearStatus;
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
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_init_db, null);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        // 按钮在弹窗布局内自绘：左取消、右危险确认，避免系统按钮栏贴边裁切
        final TextView btnConfirm = view.findViewById(R.id.btn_dialog_confirm);
        final TextView btnCancel = view.findViewById(R.id.btn_dialog_cancel);

        btnConfirm.setOnClickListener(v -> {
            // 初始化期间锁定两个按钮，避免重复点击反复重建表结构
            btnConfirm.setEnabled(false);
            btnCancel.setEnabled(false);
            btnConfirm.setText(R.string.db_init_running);
            new Thread(() -> {
                try {
                    AppLogDbHelper.getInstance(getApplicationContext()).recreateTablesExceptGroups();
                    runOnUiThread(() -> {
                        if (dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(this, R.string.toast_db_initialized, Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    Log.e("AutoClearConfig", "初始化数据库失败", e);
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        btnCancel.setEnabled(true);
                        btnConfirm.setText(R.string.db_init_confirm);
                        Toast.makeText(this,
                                getString(R.string.toast_init_failed_fmt, Web3AndroidUtil.safeMessage(e)),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        // 取消只关闭弹窗，不触发任何数据变更
        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    private void initViews() {
        swAutoEnable = findViewById(R.id.sw_auto_enable);
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        spPlayMode = findViewById(R.id.sp_play_mode);
        swLogClearEnable = findViewById(R.id.sw_log_clear_enable);
        etLogClearVal = findViewById(R.id.et_log_clear_val);
        spLogTimeUnit = findViewById(R.id.sp_log_time_unit);
        layoutLogInterval = findViewById(R.id.layout_log_interval);
        tvAutoClearStatus = findViewById(R.id.tv_auto_clear_status);
        btnSave = findViewById(R.id.btn_save_auto_clear);

        // 两个开关各自管自己的间隔输入，关闭时置灰并禁用，避免改了却不生效
        swAutoEnable.setOnCheckedChangeListener((b, checked) -> {
            applyKeyIntervalEnabled(checked);
            updateStatusText();
        });
        swLogClearEnable.setOnCheckedChangeListener((b, checked) -> {
            applyLogIntervalEnabled(checked);
            updateStatusText();
        });
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

            swLogClearEnable.setChecked(ArenaConfig.logClearEnable);
            applyKeyIntervalEnabled(swAutoEnable.isChecked());
            applyLogIntervalEnabled(swLogClearEnable.isChecked());
            updateStatusText();
        } catch (Exception e) {
            String errMsg = "加载配置异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e("AutoClearConfig", errMsg, e);
            writeErrorToDb("CONFIG_ERROR", errMsg);
            
            // 使用默认值
            swAutoEnable.setChecked(false);
            etAutoClearVal.setText("1");
            spTimeUnit.setSelection(1);
            spPlayMode.setSelection(0);
            swLogClearEnable.setChecked(true);
            etLogClearVal.setText("1");
            spLogTimeUnit.setSelection(2);
            applyKeyIntervalEnabled(false);
            applyLogIntervalEnabled(true);
            updateStatusText();
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
                Toast.makeText(this, R.string.err_clear_interval_positive, Toast.LENGTH_SHORT).show();
                return;
            }

            int logIntervalValue = Integer.parseInt(etLogClearVal.getText().toString());
            if (logIntervalValue <= 0) {
                Toast.makeText(this, R.string.err_log_interval_positive, Toast.LENGTH_SHORT).show();
                return;
            }

            ArenaConfig.autoClearEnable = swAutoEnable.isChecked();
            ArenaConfig.autoClearIntervalValue = intervalValue;
            ArenaConfig.autoClearIntervalUnitIndex = spTimeUnit.getSelectedItemPosition();
            ArenaConfig.playModeIndex = spPlayMode.getSelectedItemPosition();
            ArenaConfig.logClearEnable = swLogClearEnable.isChecked();
            ArenaConfig.logClearIntervalValue = logIntervalValue;
            ArenaConfig.logClearIntervalUnitIndex = spLogTimeUnit.getSelectedItemPosition();

            ArenaConfig.saveConfig(this);

            // 记录日志到数据库
            String configLog = "系统配置已保存: " +
                    "私钥自动清除=" + (ArenaConfig.autoClearEnable ? "开启" : "关闭") + ", " +
                    "私钥清除间隔=" + ArenaConfig.autoClearIntervalValue + " " + getTimeUnitText(ArenaConfig.autoClearIntervalUnitIndex) + ", " +
                    "入场模式=" + getPlayModeText(ArenaConfig.playModeIndex) + ", " +
                    "日志自动清除=" + (ArenaConfig.logClearEnable ? "开启" : "关闭") + ", " +
                    "日志保留=" + ArenaConfig.logClearIntervalValue + " " + getTimeUnitText(ArenaConfig.logClearIntervalUnitIndex);
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            dbHelper.insertLog(null, "CONFIG", configLog, System.currentTimeMillis());
            AppGlobal.logConfigChange(configLog);

            // 日志自动清除开关决定是否重启定时清理任务
            if (ArenaConfig.logClearEnable) {
                long logClearMinutes = AppGlobal.getIntervalInMinutes(
                        ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
                AppGlobal.startLogClearScheduler(logClearMinutes);
            } else {
                AppGlobal.stopLogClearScheduler();
            }

            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show();
            Log.d("AutoClearConfigActivity", configLog);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.err_invalid_number, Toast.LENGTH_SHORT).show();
        }
    }

    private String getTimeUnitText(int unitIndex) {
        String[] timeUnits = getResources().getStringArray(R.array.time_unit_array);
        if (unitIndex >= 0 && unitIndex < timeUnits.length) {
            return timeUnits[unitIndex];
        }
        return getString(R.string.unit_hour);
    }

    private String getPlayModeText(int modeIndex) {
        String[] playModes = getResources().getStringArray(R.array.play_mode_array);
        if (modeIndex >= 0 && modeIndex < playModes.length) {
            return playModes[modeIndex];
        }
        return getString(R.string.play_mode_flexible);
    }

    private void applyKeyIntervalEnabled(boolean enabled) {
        View layout = findViewById(R.id.layout_time_setting);
        if (layout != null) layout.setAlpha(enabled ? 1f : 0.4f);
        etAutoClearVal.setEnabled(enabled);
        spTimeUnit.setEnabled(enabled);
    }

    private void applyLogIntervalEnabled(boolean enabled) {
        if (layoutLogInterval != null) layoutLogInterval.setAlpha(enabled ? 1f : 0.4f);
        etLogClearVal.setEnabled(enabled);
        spLogTimeUnit.setEnabled(enabled);
    }

    // 当前状态卡片：同时展示两个开关，避免只看到私钥状态
    private void updateStatusText() {
        if (tvAutoClearStatus == null) return;
        String keyState = getString(swAutoEnable.isChecked() ? R.string.state_enabled : R.string.state_disabled);
        String logState = getString(swLogClearEnable.isChecked() ? R.string.state_enabled : R.string.state_disabled);
        tvAutoClearStatus.setText(getString(R.string.auto_clear_status_pair_fmt, keyState, logState));
    }
}
