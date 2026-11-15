package com.ailgnsq.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.task.TaskLogCallback;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局单例容器：解密会话（KeyStoreManager）、日志回调、两个后台定时器。
 *
 * <p>三个容易踩的点：</p>
 * <ul>
 *   <li>解密会话就是 {@code sKeyStoreManager} 是否为 null：空闲超时后置空，
 *   之后 {@link #getKeyStoreManager()} 会抛 IllegalStateException，调用方靠它判断“该重新输密码了”。</li>
 *   <li>两类日志回调分开：{@code sLogCallback} 会落库，{@code sUiLogCallback} 只上屏；
 *   自动过程用后者，否则会出现「写日志→清日志」互刷。</li>
 *   <li>定时器都是守护线程，进程退出不影响；重建前必须根据间隔判断是否需要，
 *   否则反复 stop+start 会把首次执行时间一直往后推。</li>
 * </ul>
 */
public class AppGlobal {

    // region 会话与全局状态

    /** 解密会话：非 null 即已解锁；空闲超时或切后台后被置空 */
    private static KeyStoreManager sKeyStoreManager;
    /**
     * 元数据实例：只管密钥表的读写（编号/地址/分组/启用/主号），不参与「是否已解锁」判定。
     * 空闲超时不会把它置空，否则页面会因为“读不到元数据”而整页变空。
     */
    private static volatile KeyStoreManager sMetaManager;
    /** Application Context，避免持有 Activity 导致泄漏 */
    private static volatile Context sAppCtx;
    /** 全量日志回调：带落库（任务过程日志走它） */
    private static TaskLogCallback sLogCallback;
    /** 仅上屏的日志回调：定时任务等自动过程用，不写数据库 */
    private static TaskLogCallback sUiLogCallback;

    /** 最后一次用户活动时间，空闲锁定靠它判定 */
    private static final AtomicLong LAST_ACTIVE_TS = new AtomicLong(System.currentTimeMillis());
    /** 空闲检查线程（单线程、守护，60 秒一轮） */
    private static ScheduledExecutorService idleScheduler;
    /** 空闲检查任务句柄，用于取消 */
    private static ScheduledFuture<?> idleTask;
    /** 空闲到期后的回调（由 UI 层注入，用于回锁屏/跳转解锁页） */
    private static Runnable onIdleLock;

    // endregion

    // region 日志定时清理

    /** 日志清理调度线程 */
    private static ScheduledExecutorService logClearScheduler;
    /** 日志清理任务句柄，用于取消 */
    private static ScheduledFuture<?> logClearTask;
    /** 当前调度器用的间隔：onResume 会反复调 start，间隔没变就不重建 */
    private static long logClearIntervalMinutes = -1L;

    // endregion

    /**
     * 记下 Application Context，供 {@link #getMetaManager()} 这类不依赖会话的入口使用。
     *
     * <p>必须在任何元数据操作之前调：{@code getMetaManager()} 在会话为空时要新建实例，
     * 而新建需要 Context。以前 sAppCtx 只在 {@link #initKeyManager(Context)} 里赋值，
     * 于是冷启动时「自动清除私钥」这类在界面创建阶段就开跑的元数据操作会撞上
     * {@code initKeyManager} 还没执行到那一行，抛出「应用未初始化」并被上层 catch 吞掉，
     * 看上去就是「开关开着但从来没清过」。</p>
     */
    public static void setAppContext(Context context) {
        sAppCtx = context.getApplicationContext();
    }

    /**
     * 初始化全局状态：建 KeyStoreManager、记录活动时间、拉起空闲锁定线程。
     *
     * <p>由解锁流程在密码验证通过后调用。虽然构造了 KeyStoreManager，
     * 但此时还不能解密密文——真正的密码在它内部保存。</p>
     */
    public static void initKeyManager(Context context) throws Exception {
        setAppContext(context);
        sKeyStoreManager = new KeyStoreManager(getAppContext());
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
        startIdleLockLoopIfNeeded();
    }

    /**
     * 取当前解密会话，并顺带刷新活动时间。
     *
     * <p>会话为空（空闲超时、进程重建）时直接抛 IllegalStateException，
     * 而不是返回 null：让每个调用点都不能忘记处理「待重新解锁」。
     * 因为带刷新副作用，不要在后台循环里高频调，循环开始调一次即可。</p>
     */
    public static KeyStoreManager getKeyStoreManager() {
        if (sKeyStoreManager == null) {
            throw new IllegalStateException("会话已过期，请重新输入密码解锁");
        }
        touch();
        return sKeyStoreManager;
    }

    /**
     * 取「元数据实例」：只用来读写密钥表与元信息，与会话状态无关，会话过期照常返回、不抛异常。
     *
     * <p>为什么必须跟 {@link #getKeyStoreManager()} 分开：后者把「会话未过期」当作硬门控，
     * 而 {@code loadKeyEntryList()} / {@code getMasterAccountId()} / {@code setKeyEnabled()}
     * 这类方法只查改 SQLite，根本用不到密码（密码是调用方以 char[] 传进解密方法的）。
     * 私钥管理页整页只用元数据，走会话门的结果就是空闲超时后进去一片空白，
     * 而日志/分组/地址簿这些直连数据库的页面数据照旧——用户看到的就是「只有私钥管理空了」。</p>
     *
     * <p>两个实例共用同一个 {@link AppLogDbHelper} 单例，也都不持有密钥材料，
     * 所以同时存在是安全的；会话实例只多承担「还能不能解密」这一层语义。</p>
     */
    public static KeyStoreManager getMetaManager() {
        // 已解锁就直接复用会话实例：常态下整进程仍然只有一个 KeyStoreManager，
        // 只有「已上锁」时才需要额外补一个只做元数据读写的实例
        KeyStoreManager session = sKeyStoreManager;
        if (session != null) return session;
        KeyStoreManager m = sMetaManager;
        if (m != null) return m;
        synchronized (AppGlobal.class) {
            if (sMetaManager == null) {
                sMetaManager = new KeyStoreManager(getAppContext());
            }
            return sMetaManager;
        }
    }

    /** 会话是否已过期（空闲超时/进程重建后为 true）；纯元数据读取不要拿它做门控 */
    public static boolean isSessionLocked() {
        return sKeyStoreManager == null;
    }

    /**
     * 密码验证通过后恢复会话：会话还在就只刷活动时间，已过期才重建实例。
     *
     * <p>给 {@code CryptoKeyPrompt} 这类「就地解锁」的入口用：它先拿元数据实例试解，
     * 解开了才调本方法把会话补回来，后续 {@link #getKeyStoreManager()} 的解密链路才重新可用。</p>
     */
    public static void ensureSession(Context context) throws Exception {
        if (sKeyStoreManager == null) {
            initKeyManager(context);
        } else {
            touch();
        }
    }

    /** 取 Application Context；未初始化就抛异常，避免调用方拿到 null 后空指针难查 */
    public static Context getAppContext() {
        if (sAppCtx == null) {
            throw new IllegalStateException("应用未初始化，请重启 App");
        }
        return sAppCtx;
    }

    /** 刷新用户活动时间：任何用户交互后都要调，否则会被空闲锁定误判 */
    public static void touch() {
        LAST_ACTIVE_TS.set(System.currentTimeMillis());
    }

    /** 注册空闲到期回调（只在主线程的定时线程里触发，内部自行切 UI 线程） */
    public static void setOnIdleLock(Runnable cb) { onIdleLock = cb; }

    /** 注册带落库的日志回调（任务过程日志） */
    public static void setLogCallback(TaskLogCallback callback) {
        sLogCallback = callback;
    }

    /** 注册仅上屏的日志回调（自动过程用，原因见 {@link #logUi}） */
    public static void setUiLogCallback(TaskLogCallback callback) {
        sUiLogCallback = callback;
    }

    /**
     * 非用户点击触发的提示：只上屏，不落库。
     *
     * <p>keyNo 与 type 都传 null，表示这行不属于任何账号、也不是某种业务动作。</p>
     */
    public static void logUi(String msg) {
        if (sUiLogCallback != null) {
            sUiLogCallback.onLog(null, null, msg);
        }
    }

    /** 记录配置变更（落库，type 固定为 CONFIG） */
    public static void logConfigChange(String msg) {
        if (sLogCallback != null) {
            sLogCallback.onLog(null, "CONFIG", msg);
        }
    }

    /**
     * 启动定时清理日志任务：只删除早于「保留时长」的旧记录，保留最近数据。
     *
     * <p>两个实现细节都有原因：</p>
     * <ul>
     *   <li>同一间隔已在跑就不重建：onResume 每次都会调本方法，
     *   重复 stop+start 会把首次执行时间一直往后推（间隔 1 天就等于永不执行）；</li>
     *   <li>首轮不等一个完整间隔：否则保留期是 1 天时，得开着 App 天天不关才会清一次。
     *   清理按保留期删旧记录，天然幂等，启动后一分钟跑一次没有副作用。</li>
     * </ul>
     *
     * <p>开关关闭或间隔非正数时直接停掉调度，不保留空任务。</p>
     */
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

    /**
     * 停止定时清理。先置空间隔记录再取消，否则下次 start 会因间隔相等而误以为在跑。
     */
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

    /**
     * 把「值 + 单位下标」换算成分钟。下标：0 分钟 / 1 小时 / 2 天，越界按分钟处理。
     */
    public static long getIntervalInMinutes(int value, int unitIndex) {
        switch (unitIndex) {
            case 0: return value;
            case 1: return value * 60L;
            case 2: return value * 24L * 60L;
            default: return value;
        }
    }

    // region 全局任务闸门（跨页面互斥）

    /**
     * 全局任务闸门：主界面批量任务与详情页单号任务共用同一把锁。
     *
     * <p>为什么不能只靠各页面自己的 {@code isTaskRunning}：主界面与详情页是两个 Activity，
     * 批量任务跑到某个号时，用户完全可以在详情页对同一个号点「入场」——两条链路用同一把私钥
     * 并发签名，结果是 nonce 冲突、重复入场、重复扣费。闸门放在进程级静态变量上，
     * 任何入口都必须先 {@link #acquireTask()} 才能发交易。</p>
     */
    private static final AtomicBoolean TASK_BUSY = new AtomicBoolean(false);

    /** 占用闸门；已有任务在跑返回 false（调用方据此直接拒绝，不排队） */
    public static boolean acquireTask() {
        return TASK_BUSY.compareAndSet(false, true);
    }

    /** 释放闸门；必须在任务收尾的 finally 里调，漏一次后面所有任务都会被拦 */
    public static void releaseTask() {
        TASK_BUSY.set(false);
    }

    /** 是否有任一入口的任务在跑（主界面批量 / 详情页单号） */
    public static boolean isTaskBusy() {
        return TASK_BUSY.get();
    }

    // endregion

    // region 空闲自动上锁

    /**
     * 拉起空闲检查线程（幂等：已在跑就直接返回）。
     *
     * <p>每 60 秒检查一次「距上次活动多久」，超过配置的 {@code IDLE_LOCK_SEC} 就：
     * 把会话置空（等于上锁）→ 异步回调 UI。置空在前、回调在后，
     * 即使回调漏了也不会出现「已超时但仍能解密」的窗口。</p>
     */
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

    /** 关闭空闲检查线程（进程退出或彻底登出时调用） */
    public static synchronized void shutdownIdleLockLoop() {
        if (idleTask != null) idleTask.cancel(false);
        if (idleScheduler != null) idleScheduler.shutdownNow();
        idleScheduler = null;
        idleTask = null;
    }

    // endregion
}
