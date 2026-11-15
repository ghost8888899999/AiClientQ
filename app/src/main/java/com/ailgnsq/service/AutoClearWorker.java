package com.ailgnsq.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.manager.AppPrefs;
import com.ailgnsq.manager.KeyStoreManager;

import java.util.concurrent.TimeUnit;

/**
 * 自动清理的 WorkManager Worker（历史遗留实现）。
 *
 * <p>注意：当前项目不会通过 WorkManager 入队本类，它不会被执行。实际逻辑在
 * {@code MainActivity.checkAutoClearBoot()}（打开 APP 时校验）与
 * {@code AppGlobal.startLogClearScheduler()}（运行期定时清理）里。
 * 保留此类是为了旧版本已入队的任务在升级后仍能反序列化，不报 ClassNotFound。</p>
 *
 * <p>因此本类内的清理逻辑与主路径是两套并行实现，改清理规则时两边都要看，
 * 不能只改一边就当完成。</p>
 */
public class AutoClearWorker extends Worker {

    /** WorkManager 要求保留的构造函数，不能删，否则旧任务反序列化会失败 */
    public AutoClearWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    /**
     * 执行清理：先看私钥清理开关是否到期，再处理日志与历史数据。
     *
     * <p>两件事互不影响：私钥清理失败会直接走进异常分支返回 failure（安全优先，
     * 下次重试），日志清理失败只在内部 catch 里记一笔，不阻断整体成功。</p>
     */
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

    /**
     * 把「私钥自动清理间隔」换算成毫秒。单位下标：0 分钟 / 1 小时 / 2 天。
     *
     * <p>越界时回退到 1 小时：宁可清得晚一点，也不能因为脏配置把间隔算成 0 而立刻清空私钥。</p>
     */
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

    /**
     * 按保留期删除旧日志、旧当日状态与旧失败日志。
     *
     * <p>语义必须与系统配置页一致：开关关闭时一条旧记录也不删。
     * 删除阈值的口径是「保留多久」，天然幂等，所以不需要再用上次执行时间做闸门；
     * 这也是它与私钥清理（必须记上次时间）的区别。</p>
     */
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

    /**
     * 把「日志保留期」换算成毫秒。单位下标：0 分钟 / 1 小时 / 2 天，越界回退 1 天。
     */
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

    /** 记下本次私钥清理时间，供下次计算是否到期 */
    private void updateLastClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_clear_timestamp", timestamp).apply();
    }

    /** 记下本次日志清理时间（仅作展示/排查用，删除已按保留期幂等） */
    private void updateLastLogClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_log_clear_timestamp", timestamp).apply();
    }
}
