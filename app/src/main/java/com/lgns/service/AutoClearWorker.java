package com.lgns.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.lgns.model.ArenaConfig;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.AppPrefs;
import com.lgns.manager.KeyStoreManager;

import java.util.concurrent.TimeUnit;

public class AutoClearWorker extends Worker {

    // 注意：当前项目未通过 WorkManager 入队本类，它不会被执行。
    // 实际逻辑在 MainActivity.checkAutoClearBoot()（打开 APP 校验）
    // 与 AppGlobal.startLogClearScheduler()（运行期定时清理）中。
    // 保留此类是为了旧版本曾入队的历史任务在升级后仍能反序列化，不报 ClassNotFound。

    public AutoClearWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        try {
            Log.d("AutoClearWorker", "=======开始执行自动清除任务=======");
            
            // 检查是否启用了自动清除
            if (ArenaConfig.autoClearEnable) {
                long currentTime = System.currentTimeMillis();
                long clearThreshold = currentTime - getClearThresholdMs();

                // 检查是否到达清除时间
                if (currentTime >= clearThreshold) {
                    // 执行清除私钥
                    Log.d("AutoClearWorker", "=======到期执行清除私钥=======");
                    KeyStoreManager mgr = new KeyStoreManager(getApplicationContext());
                    mgr.clearAllKeys();
                    
                    // 重置清除时间
                    ArenaConfig.loadConfig(getApplicationContext()); // 确保配置最新
                    updateLastClearTimestamp(currentTime, getApplicationContext());
                    
                    Log.d("AutoClearWorker", "=======私钥清除完成=======");
                }
            }

            // 检查是否需要清除日志和相关数据
            checkAndClearLogsAndData();

            return Result.success();
        } catch (Exception e) {
            Log.e("AutoClearWorker", "自动清除任务执行失败", e);
            return Result.failure();
        }
    }

    private long getClearThresholdMs() {
        switch (ArenaConfig.autoClearIntervalUnitIndex) {
            case 0: // 分钟
                return TimeUnit.MINUTES.toMillis(ArenaConfig.autoClearIntervalValue);
            case 1: // 小时
                return TimeUnit.HOURS.toMillis(ArenaConfig.autoClearIntervalValue);
            case 2: // 天
                return TimeUnit.DAYS.toMillis(ArenaConfig.autoClearIntervalValue);
            default:
                return TimeUnit.HOURS.toMillis(1); // 默认1小时
        }
    }

    private void checkAndClearLogsAndData() {
        try {
            // 语义必须与系统配置页一致：开关关闭时一条旧记录也不删
            ArenaConfig.loadConfig(getApplicationContext());
            if (!ArenaConfig.logClearEnable) return;

            long currentTime = System.currentTimeMillis();
            // 按保留期删除，天然幂等，不需要再拿上次执行时间做闸门
            long logClearThreshold = currentTime - getLogClearThresholdMs();
            Log.d("AutoClearWorker", "=======开始清除日志和数据=======");

            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(getApplicationContext());

            // 清除旧日志
            int deletedLogCount = dbHelper.deleteLogsOlderThan(logClearThreshold);

            // 清除旧的当日状态数据
            int deletedStatusCount = dbHelper.deleteDailyStatusOlderThan(logClearThreshold);

            // 清除旧的失败日志
            int deletedFailureCount = dbHelper.deleteFailureLogsOlderThan(logClearThreshold);

            Log.d("AutoClearWorker", "=======数据清除完成=======");
            Log.d("AutoClearWorker", "删除日志: " + deletedLogCount + " 条");
            Log.d("AutoClearWorker", "删除状态: " + deletedStatusCount + " 条");
            Log.d("AutoClearWorker", "删除失败日志: " + deletedFailureCount + " 条");

            // 更新下次清除时间
            updateLastLogClearTimestamp(currentTime, getApplicationContext());
        } catch (Exception e) {
            Log.e("AutoClearWorker", "清除日志和数据失败", e);
        }
    }

    private long getLogClearThresholdMs() {
        switch (ArenaConfig.logClearIntervalUnitIndex) {
            case 0: // 分钟
                return TimeUnit.MINUTES.toMillis(ArenaConfig.logClearIntervalValue);
            case 1: // 小时
                return TimeUnit.HOURS.toMillis(ArenaConfig.logClearIntervalValue);
            case 2: // 天
                return TimeUnit.DAYS.toMillis(ArenaConfig.logClearIntervalValue);
            default:
                return TimeUnit.DAYS.toMillis(1); // 默认1天
        }
    }

    private void updateLastClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_clear_timestamp", timestamp).apply();
    }

    private void updateLastLogClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_log_clear_timestamp", timestamp).apply();
    }
}
