package com.lgns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;

public class KeyStoreManager {
    private static final String SP_NAME = "keystore_sp";
    private static final String KEY_CIPHER_LIST = "cipher_list";

    // GCM 参数
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;

    // PBKDF2 参数
    private static final int PBKDF2_SALT_LEN = 16;
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    private static final int AES_KEY_BIT = 256;

    private final SharedPreferences sp;
    //注意：现在不再使用外部传入的AES密钥，全部由用户密码+盐PBKDF2派生
    public KeyStoreManager(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 添加私钥：使用用户会话char[]密码加密，保存密文字符串 */
    public void addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey) throws Exception {
        List<String> cipherList = loadCipherTextList();
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);
        cipherList.add(cipherText);
        saveCipherList(cipherList);
    }

    /** 根据索引删除密文 */
    public void removeByIndex(int index) throws Exception {
        List<String> cipherList = loadCipherTextList();
        if (index < 0 || index >= cipherList.size()) {
            throw new Exception("索引越界");
        }
        cipherList.remove(index);
        saveCipherList(cipherList);
    }

    /** 清空全部密文 */
    public void clearAllKeys() {
        sp.edit().remove(KEY_CIPHER_LIST).apply();
    }

    /** 获取全部密文列表 */
    public List<String> loadCipherTextList() {
        String json = sp.getString(KEY_CIPHER_LIST, "");
        List<String> res = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return res;
        }
        String[] arr = json.split("\\|");
        for (String s : arr) {
            if (!s.isEmpty()) res.add(s);
        }
        return res;
    }

    private void saveCipherList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(list.get(i));
        }
        sp.edit().putString(KEY_CIPHER_LIST, sb.toString()).apply();
    }

    /**
     * 解密：密文结构 Base64(盐[16] + IV[12] + GCM密文)
     */
    public String decryptCipherText(String cipherBase64, char[] sessionCryptoKey) throws Exception {
        byte[] fullData = Base64.decode(cipherBase64, Base64.NO_WRAP);

        int offset = 0;
        byte[] salt = new byte[PBKDF2_SALT_LEN];
        System.arraycopy(fullData, offset, salt, 0, PBKDF2_SALT_LEN);
        offset += PBKDF2_SALT_LEN;

        byte[] iv = new byte[GCM_IV_LEN];
        System.arraycopy(fullData, offset, iv, 0, GCM_IV_LEN);
        offset += GCM_IV_LEN;

        byte[] cipherBody = new byte[fullData.length - offset];
        System.arraycopy(fullData, offset, cipherBody, 0, cipherBody.length);

        // PBKDF2派生AES密钥
        SecretKey derivedAesKey = pbkdf2Derive(sessionCryptoKey, salt, PBKDF2_ITERATION_COUNT, AES_KEY_BIT);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derivedAesKey, spec);
        byte[] plain = cipher.doFinal(cipherBody);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 加密，生成随机盐、随机IV，输出Base64完整包 */
    public String encrypt(String plainText, char[] sessionCryptoKey) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[PBKDF2_SALT_LEN];
        random.nextBytes(salt);

        byte[] iv = new byte[GCM_IV_LEN];
        random.nextBytes(iv);

        SecretKey derivedAesKey = pbkdf2Derive(sessionCryptoKey, salt, PBKDF2_ITERATION_COUNT, AES_KEY_BIT);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derivedAesKey, spec);
        byte[] cipherBody = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        //拼接 salt + iv + cipherBody
        byte[] full = new byte[salt.length + iv.length + cipherBody.length];
        int pos = 0;
        System.arraycopy(salt, 0, full, pos, salt.length);
        pos += salt.length;
        System.arraycopy(iv, 0, full, pos, iv.length);
        pos += iv.length;
        System.arraycopy(cipherBody, 0, full, pos, cipherBody.length);

        return Base64.encodeToString(full, Base64.NO_WRAP);
    }

    /** PBKDF2‑HMAC‑SHA256 派生AES密钥 */
    private SecretKey pbkdf2Derive(char[] passwordChars, byte[] salt, int iteration, int keyBit) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passwordChars, salt, iteration, keyBit);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 密码校验测试解密 */
    public void testDecrypt(String cipherText, char[] password) throws Exception {
        decryptCipherText(cipherText, password);
    }
}
