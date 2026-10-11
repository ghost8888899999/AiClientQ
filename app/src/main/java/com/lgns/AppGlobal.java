package com.lgns;

import android.content.Context;

public class AppGlobal {
    private static KeyStoreManager sKeyStoreManager;

    public static void initKeyManager(Context context) throws Exception {
        // PBKDF2版本不再需要系统生成AES密钥，全部由用户密码派生
        sKeyStoreManager = new KeyStoreManager(context);
    }

    public static KeyStoreManager getKeyStoreManager() {
        if (sKeyStoreManager == null) {
            throw new IllegalStateException("请先执行 initKeyManager 初始化密钥管理器");
        }
        return sKeyStoreManager;
    }
}
