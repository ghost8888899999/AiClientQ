package com.lgns;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;

public class PrivateKeyEncryptUtil {
    private static final String KEYSTORE_ALIAS = "arena_aes_256";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
            + KeyProperties.BLOCK_MODE_GCM + "/"
            + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN_BITS = 128;

    /**
     * 获取或者生成持久化AES‑256密钥，存储在系统AndroidKeyStore，APP重启不会丢失
     */
    public static SecretKey getOrCreateAesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if(ks.containsAlias(KEYSTORE_ALIAS)){
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEYSTORE_ALIAS,null);
            return entry.getSecretKey();
        }
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,ANDROID_KEYSTORE);
        kg.init(spec);
        return kg.generateKey();
    }

    /**
     * GCM加密：返回 iv(12字节) + tag(16字节) + cipher 拼接后的base64字符串
     */
    public static String encrypt(String plainText, SecretKey secretKey) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] cipherData = cipher.doFinal(plainText.getBytes());
        byte[] iv = cipher.getIV();
        byte[] out = new byte[iv.length + cipherData.length];
        System.arraycopy(iv,0,out,0,iv.length);
        System.arraycopy(cipherData,0,out,iv.length,cipherData.length);
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
    }

    public static String decrypt(String cipherBase64, SecretKey secretKey) throws Exception {
        byte[] raw = android.util.Base64.decode(cipherBase64, android.util.Base64.NO_WRAP);
        byte[] iv = new byte[GCM_IV_LEN];
        System.arraycopy(raw,0,iv,0, GCM_IV_LEN);
        byte[] cipherAndTag = new byte[raw.length - GCM_IV_LEN];
        System.arraycopy(raw,GCM_IV_LEN,cipherAndTag,0,cipherAndTag.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE,secretKey,gcmSpec);
        byte[] plainBytes = cipher.doFinal(cipherAndTag);
        return new String(plainBytes);
    }
}
