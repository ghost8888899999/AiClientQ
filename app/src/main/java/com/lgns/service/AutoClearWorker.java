package com.lgns.service;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;

public class AutoClearWorker extends Worker {

    public AutoClearWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("app_setting", Context.MODE_PRIVATE);

        long totalMs = sp.getLong("auto_clear_ms", 0);
        long startTs = sp.getLong("auto_clear_start_ts", 0);

        // 没有设置自动清除，直接退出
        if (totalMs <= 0 || startTs <= 0) {
            return Result.success();
        }

        long now = System.currentTimeMillis();
        // 判断是否真正到期（WorkManager会被系统推迟，必须自己再校验时间戳）
        if (now - startTs < totalMs) {
            return Result.success();
        }

        // =====到期执行清除密钥=====
        try {
            if(AppGlobal.getKeyStoreManager() == null){
                AppGlobal.initKeyManager(ctx);
            }
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            mgr.clearAllKeys();

            // 写入标记，通知MainActivity刷新UI列表
            sp.edit()
                    .putBoolean("key_auto_cleared", true)
                    .putLong("auto_clear_ms",0)
                    .putLong("auto_clear_start_ts",0)
                    .apply();

        } catch (Exception e) {
            return Result.retry();
        }
        return Result.success();
    }
}
