package com.lgns.manager;

import android.content.Context;

import com.lgns.model.ArenaConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AppGlobal {
    private static KeyStoreManager sKeyStoreManager;
    private static volatile Context sAppCtx;

    // 空闲自动上锁
    private static final AtomicLong LAST_ACTIVE_TS = new AtomicLong(System.currentTimeMillis());
    private static ScheduledExecutorService idleScheduler;
    private static ScheduledFuture<?> idleTask;
    private static Runnable onIdleLock; // 上层注册,通知 UI 清空密码框/禁用按钮

    public static void initKeyManager(Context context) throws Exception {
        sAppCtx = context.getApplicationContext();
        sKeyStoreManager = new KeyStoreManager(sAppCtx);
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
        startIdleLockLoopIfNeeded();
    }

    public static KeyStoreManager getKeyStoreManager() {
        if (sKeyStoreManager == null) {
            throw new IllegalStateException("请先执行 initKeyManager 初始化密钥管理器");
        }
        touch();
        return sKeyStoreManager;
    }

    public static Context getAppContext() {
        if (sAppCtx == null) {
            throw new IllegalStateException("AppGlobal 未初始化，请先执行 initKeyManager");
        }
        return sAppCtx;
    }

    /** 业务代码在任何用户操作/网络请求前后调一下 */
    public static void touch() {
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
    }

    /** 上层注册空闲回调(如:清空 sessionCryptoKey、禁用批量按钮) */
    public static void setOnIdleLock(Runnable cb) { onIdleLock = cb; }

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
                // 擦内存:SP 里的 cipherText 不动,只把内存中的 manager 断掉,
                // 下次 getKeyStoreManager() 会抛 IllegalStateException 强制重解锁
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
