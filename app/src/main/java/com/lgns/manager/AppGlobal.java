package com.lgns.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.lgns.model.ArenaConfig;
import com.lgns.task.TaskLogCallback;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AppGlobal {
    private static KeyStoreManager sKeyStoreManager;
    private static volatile Context sAppCtx;
    private static TaskLogCallback sLogCallback;
    // 仅上屏的日志回调：定时任务等自动过程用，不写数据库
    private static TaskLogCallback sUiLogCallback;

    // 空闲自动上锁
    private static final AtomicLong LAST_ACTIVE_TS = new AtomicLong(System.currentTimeMillis());
    private static ScheduledExecutorService idleScheduler;
    private static ScheduledFuture<?> idleTask;
    private static Runnable onIdleLock;

    // 定时清除日志
    private static ScheduledExecutorService logClearScheduler;
    private static ScheduledFuture<?> logClearTask;
    // 当前调度器用的间隔：onResume 会反复调 start，间隔没变就不重建
    private static long logClearIntervalMinutes = -1L;

    public static void initKeyManager(Context context) throws Exception {
        sAppCtx = context.getApplicationContext();
        sKeyStoreManager = new KeyStoreManager(sAppCtx);
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
        startIdleLockLoopIfNeeded();
    }

    public static KeyStoreManager getKeyStoreManager() {
        if (sKeyStoreManager == null) {
            throw new IllegalStateException("会话已过期，请重新输入密码解锁");
        }
        touch();
        return sKeyStoreManager;
    }

    public static Context getAppContext() {
        if (sAppCtx == null) {
            throw new IllegalStateException("应用未初始化，请重启 App");
        }
        return sAppCtx;
    }

    public static void touch() {
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
    }

    public static void setOnIdleLock(Runnable cb) { onIdleLock = cb; }

    public static void setLogCallback(TaskLogCallback callback) {
        sLogCallback = callback;
    }

    public static void setUiLogCallback(TaskLogCallback callback) {
        sUiLogCallback = callback;
    }

    // 非用户点击触发的提示：只上屏，不落库
    public static void logUi(String msg) {
        if (sUiLogCallback != null) {
            sUiLogCallback.onLog(null, null, msg);
        }
    }

    public static void logConfigChange(String msg) {
        if (sLogCallback != null) {
            sLogCallback.onLog(null, "CONFIG", msg);
        }
    }

    // 启动定时清理日志任务：只删除早于「保留时长」的旧记录，保留最近数据
    public static synchronized void startLogClearScheduler(long intervalMinutes) {
        if (!ArenaConfig.logClearEnable || intervalMinutes <= 0) {
            stopLogClearScheduler();
            return;
        }

        // 同一间隔已经在跑就直接复用：onResume 每次都会调本方法，
        // 重复 stop+start 会把首次执行时间一直往后推（间隔 1 天就等于永不执行）
        if (logClearTask != null && !logClearTask.isDone()
                && logClearIntervalMinutes == intervalMinutes) {
            return;
        }
        stopLogClearScheduler();

        logClearScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-clear-timer");
            t.setDaemon(true);
            return t;
        });

        // 首轮不等到一个完整间隔：保留期是 1 天时，否则要开着 App 天天不关才会清一次。
        // 清理是按保留期删旧记录，天然幂等，启动后一分钟跑一次没有副作用
        long initialDelayMin = Math.min(intervalMinutes, 1L);
        logClearIntervalMinutes = intervalMinutes;
        logClearTask = logClearScheduler.scheduleWithFixedDelay(() -> {
            try {
                long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(intervalMinutes);
                AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(sAppCtx);
                int deletedCount = dbHelper.deleteLogsOlderThan(cutoff)
                        + dbHelper.deleteDailyStatusOlderThan(cutoff)
                        + dbHelper.deleteFailureLogsOlderThan(cutoff);

                SharedPreferences sp = AppPrefs.get(sAppCtx);
                sp.edit().putLong("last_log_clear_timestamp", System.currentTimeMillis()).apply();

                Log.d("AppGlobal", "定时清理日志完成，共删除 " + deletedCount + " 条历史记录");

                // 自动清理只上屏提示，不再写回日志表，避免“写日志→清日志”互刷数据；
                // 首轮清理会在启动后很快跑一次，没删到东西就不上屏，不然每次开 App 都多一行
                if (deletedCount > 0) {
                    logUi("定时清理日志，共删除 " + deletedCount + " 条历史记录");
                }
            } catch (Exception e) {
                Log.e("AppGlobal", "定时清理日志失败", e);
            }
        }, initialDelayMin, intervalMinutes, TimeUnit.MINUTES);

        Log.d("AppGlobal", "日志定时清理已启动，保留最近 " + intervalMinutes + " 分钟");
    }

    // 停止定时清除日志任务
    public static synchronized void stopLogClearScheduler() {
        logClearIntervalMinutes = -1L;
        if (logClearTask != null) {
            logClearTask.cancel(false);
            logClearTask = null;
        }
        if (logClearScheduler != null) {
            logClearScheduler.shutdownNow();
            logClearScheduler = null;
        }
    }

    // 根据配置计算间隔分钟数
    public static long getIntervalInMinutes(int value, int unitIndex) {
        switch (unitIndex) {
            case 0: return value;
            case 1: return value * 60L;
            case 2: return value * 24L * 60L;
            default: return value;
        }
    }

    private static synchronized void startIdleLockLoopIfNeeded() {
        if (idleScheduler != null) return;
        idleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-idle-lock");
            t.setDaemon(true);
            return t;
        });
        idleTask = idleScheduler.scheduleWithFixedDelay(() -> {
            long idleMs = System.currentTimeMillis() - LAST_ACTIVE_TS.get();
            if (idleMs >= ArenaConfig.IDLE_LOCK_SEC * 1000L && sKeyStoreManager != null) {
                sKeyStoreManager = null;
                Runnable cb = onIdleLock;
                if (cb != null) {
                    try { cb.run(); } catch (Exception ignore) {}
                }
            }
        }, 60L, 60L, TimeUnit.SECONDS);
    }

    public static synchronized void shutdownIdleLockLoop() {
        if (idleTask != null) idleTask.cancel(false);
        if (idleScheduler != null) idleScheduler.shutdownNow();
        idleScheduler = null;
        idleTask = null;
    }
}
