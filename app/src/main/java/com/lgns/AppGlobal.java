package com.lgns;

import android.content.Context;
import javax.crypto.SecretKey;

public class AppGlobal {
    private static KeyStoreManager sKeyStoreManager;

    public static void initKeyManager(Context context) throws Exception {
        SecretKey aesKey = PrivateKeyEncryptUtil.getOrCreateAesKey();
        sKeyStoreManager = new KeyStoreManager(context, aesKey);
    }

    public static KeyStoreManager getKeyStoreManager() {
        if (sKeyStoreManager == null) {
            throw new IllegalStateException("请先执行 initKeyManager 初始化密钥管理器");
        }
        return sKeyStoreManager;
    }
}
