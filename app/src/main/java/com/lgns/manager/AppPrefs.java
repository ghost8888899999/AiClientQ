package com.lgns.manager;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPrefs {
    public static final String PREFS_NAME = "app_prefs";
    
    private static SharedPreferences sp;
    
    public static SharedPreferences get(Context context) {
        if (sp == null) {
            sp = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return sp;
    }
}
