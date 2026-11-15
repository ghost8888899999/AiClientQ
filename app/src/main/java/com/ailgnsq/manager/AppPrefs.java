package com.ailgnsq.manager;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences 的唯一入口，集中管理文件名与缓存。
 *
 * <p>为何要包一层：直接每处调 {@code getSharedPreferences} 容易写出不同的文件名，
 * 导致同一份配置在两个文件里各存一份。清理时间戳、pending 恢复、用户配置都共用这一份。</p>
 */
public class AppPrefs {
    /** 唯一的 SP 文件名，改名等于丢弃所有旧配置，不要随意改 */
    public static final String PREFS_NAME = "app_prefs";

    /** 进程内缓存，避免反复打开 SP（多线程下重复初始化不影响正确性，SP 本身线程安全） */
    private static SharedPreferences sp;

    /** 取应用级 SharedPreferences（用 ApplicationContext，不会泄漏 Activity） */
    public static SharedPreferences get(Context context) {
        if (sp == null) {
            sp = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return sp;
    }
}
