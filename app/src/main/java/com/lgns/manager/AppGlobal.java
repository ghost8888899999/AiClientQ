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

    // 空闲自动上锁
    private static final AtomicLong LAST_ACTIVE_TS = new AtomicLong(System.currentTimeMillis());
    private static ScheduledExecutorService idleScheduler;
    private static ScheduledFuture<?> idleTask;
    private static Runnable onIdleLock;

    // 定时清除日志
    private static ScheduledExecutorService logClearScheduler;
    private static ScheduledFuture<?> logClearTask;

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

    public static void logConfigChange(String msg) {
        if (sLogCallback != null) {
            sLogCallback.onLog(null, "CONFIG", msg);
        }
    }

    // 启动定时清除日志任务（根据配置的间隔）
    public static synchronized void startLogClearScheduler(long intervalMinutes) {
        stopLogClearScheduler();
        if (intervalMinutes <= 0) return;

        logClearScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-clear-timer");
            t.setDaemon(true);
            return t;
        });

        logClearTask = logClearScheduler.scheduleWithFixedDelay(() -> {
            try {
                AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(sAppCtx);
                int deletedCount = dbHelper.clearAllLogData();

                SharedPreferences sp = AppPrefs.get(sAppCtx);
                sp.edit().putLong("last_log_clear_timestamp", System.currentTimeMillis()).apply();

                Log.d("AppGlobal", "定时清除日志完成，共删除 " + deletedCount + " 条");

                if (sLogCallback != null) {
                    sLogCallback.onLog(null, "CONFIG", "定时清除日志，共删除 " + deletedCount + " 条");
                }
            } catch (Exception e) {
                Log.e("AppGlobal", "定时清除日志失败", e);
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

        Log.d("AppGlobal", "日志定时清除已启动，间隔=" + intervalMinutes + "分钟");
    }

    // 停止定时清除日志任务
    public static synchronized void stopLogClearScheduler() {
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
