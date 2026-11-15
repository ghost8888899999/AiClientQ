package com.ailgnsq.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.ailgnsq.R;

/**
 * 长任务保活前台服务：让「连打 12 圈」「HELIX 一轮几十号」这类几十分钟的任务
 * 在熄屏后不被系统回收，也不会因为 CPU 休眠而让 RPC 请求超时。
 *
 * <p>为什么需要它：本工程里跑得最久的流程是串行等回执的批量任务，一个号两三笔交易、
 * 每笔都要等上链。用户熄屏放兜里是常态用法，而普通后台 Service / Activity 在这个时长里
 * 大概率被 LMK 杀掉，杀在半路就留下一堆 {@code TX_SENT} 在途记录（虽有恢复逻辑兜底，
 * 但那一轮剩下的账号就不会再发了）。</p>
 *
 * <p>为什么还要带 WakeLock：前台服务只保证<b>进程</b>不被回收，不保证 <b>CPU</b> 醒着。
 * 熄屏后设备进 Doze，OkHttp 的连接会挂起、{@code waitReceiptLocal} 的轮询点也走不动，
 * 表现就是「任务卡住不动」。PARTIAL_WAKE_LOCK 只维持 CPU，屏幕照样关，
 * 这是这类等回执任务需要的最小粒度。</p>
 *
 * <p>两条防漏设计：① {@code acquire(timeout)} 自带超时，调用方忘了 {@code end()}
 * 也不会留下永久持锁；② Handler 到点 {@code stopSelf()} 做第二道保险。</p>
 */
public class TaskKeepAliveService extends Service {

    private static final String TAG = "TaskKeepAliveService";

    private static final String CHANNEL_ID = "task_keep_alive";
    private static final int NOTIF_ID = 0x5A01;
    /** 通知栏副标题：调任务时传进来（「批量入场 · 正在跑」之类） */
    private static final String EXTRA_DETAIL = "extra_detail";

    /** 最长存活：4 小时。正常任务远短于此，超出说明调用方漏了 end()，宁可自杀也不长期持锁 */
    private static final long MAX_ALIVE_MS = 4 * 60 * 60 * 1000L;

    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hardStop = this::stopSelf;

    /**
     * 开保活。调用点必须是「任务已经真的要开跑」那一刻（主界面 {@code beginTask}、
     * 详情页抢到闸门之后、HELIX 密码校验通过之后）。
     *
     * <p>失败不抛：Android 12+ 在应用已退到后台时会拒绝启动前台服务
     * （ForegroundServiceStartNotAllowedException），此时任务照旧执行，只是少了熄屏保护，
     * 为了这一点去加重权限申请流程不值得。用户在界面上点按钮触发的正常路径不会走到这个分支。</p>
     */
    public static void begin(Context ctx, String detail) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        Intent it = new Intent(app, TaskKeepAliveService.class);
        it.putExtra(EXTRA_DETAIL, detail);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(it);
            } else {
                app.startService(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "start foreground service not allowed: " + e.getMessage());
        }
    }

    /** 收保活：与 {@link #begin} 成对调用；没在跑时是空操作，可以多调一次 */
    public static void end(Context ctx) {
        if (ctx == null) return;
        try {
            ctx.getApplicationContext().stopService(
                    new Intent(ctx.getApplicationContext(), TaskKeepAliveService.class));
        } catch (Exception e) {
            Log.w(TAG, "stop service failed: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String detail = intent == null ? null : intent.getStringExtra(EXTRA_DETAIL);
        Notification n = buildNotification(detail);
        // API 29 起可以、也建议带上 foregroundServiceType（Q 以下没有这个概念，直接用两参版）；
        // API 34 不带类型会被系统直接拒绝启动，所以这一层分支不能省
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        acquireWakeLock();
        // 到点自停：调用方漏 end() 时，这比让通知挂四天、锁持四天好
        mainHandler.postDelayed(hardStop, MAX_ALIVE_MS);
        // START_NOT_STICKY：被系统杀掉后不自动重建。任务上下文（选中列表、会话口令）
        // 都在 Activity 里，重建出来的服务只会拿着空 detail 挂一条没用的通知
        return START_NOT_STICKY;
    }

    /**
     * PARTIAL_WAKE_LOCK：只保 CPU，不亮屏。
     *
     * <p>带 tag 与超时 acquire：超时是防漏网的关键——即便 end() 一次都没被调到，
     * 四小时后系统自己放锁，不会把用户电量抽干。</p>
     */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ailgns:task-keep-alive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(MAX_ALIVE_MS);
        } catch (Exception e) {
            Log.w(TAG, "acquire wake lock failed: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(hardStop);
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignore) {
            }
            wakeLock = null;
        }
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** O 及以上必须先建渠道；用 LOW，任务通知不该震、不该响 */
    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // 不先查后建：同 id 重复 createNotificationChannel 只会更新渠道名与描述，
        // 不会多出第二条渠道，用户在系统设置里改过的优先级也不会被覆盖
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_task_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_task_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String detail) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app_logo)
                .setContentTitle(getString(R.string.notif_task_title))
                .setContentText(detail == null || detail.isEmpty()
                        ? getString(R.string.notif_task_default_detail) : detail)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
