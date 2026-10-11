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
    private static final String KEY_ENTRY_LIST = "key_entry_list";
    private static final String KEY_NEXT_ID = "next_key_id";

    // GCM 参数
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;

    // PBKDF2 参数
    private static final int PBKDF2_SALT_LEN = 16;
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    private static final int AES_KEY_BIT = 256;

    private final SharedPreferences sp;

    // 存储一条密钥记录：id + cipherText
    public static class KeyEntry {
        public long id;
        public String cipherText;
        public KeyEntry(long id, String cipherText){
            this.id = id;
            this.cipherText = cipherText;
        }
    }

    public KeyStoreManager(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    /** 添加私钥：分配全局唯一id */
    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey) throws Exception {
        long newId = sp.getLong(KEY_NEXT_ID,1);
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);

        List<KeyEntry> list = loadKeyEntryList();
        list.add(new KeyEntry(newId, cipherText));
        saveKeyEntryList(list);

        //自增，供下一条使用
        sp.edit().putLong(KEY_NEXT_ID, newId+1).apply();
        return new KeyEntry(newId, cipherText);
    }

    /** 根据数组索引删除（内存列表的position） */
    public void removeByIndex(int index) throws Exception {
        List<KeyEntry> list = loadKeyEntryList();
        if(index <0 || index >= list.size()){
            throw new Exception("索引越界");
        }
        list.remove(index);
        saveKeyEntryList(list);
    }

    public void clearAllKeys(){
        sp.edit()
                .remove(KEY_ENTRY_LIST)
                .remove(KEY_NEXT_ID)
                .apply();
    }

    public List<KeyEntry> loadKeyEntryList(){
        String raw = sp.getString(KEY_ENTRY_LIST,"");
        List<KeyEntry> res = new ArrayList<>();
        if(raw.isEmpty()) return res;
        String[] parts = raw.split("\\|");
        for(String p : parts){
            if(p.isEmpty()) continue;
            String[] kv = p.split(":",2);
            if(kv.length!=2) continue;
            long id;
            try{
                id = Long.parseLong(kv[0]);
            }catch (Exception e){
                continue;
            }
            res.add(new KeyEntry(id, kv[1]));
        }
        return res;
    }

    private void saveKeyEntryList(List<KeyEntry> list){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<list.size();i++){
            if(i>0) sb.append("|");
            KeyEntry e = list.get(i);
            sb.append(e.id).append(":").append(e.cipherText);
        }
        sp.edit().putString(KEY_ENTRY_LIST, sb.toString()).apply();
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

        SecretKey derivedAesKey = pbkdf2Derive(sessionCryptoKey, salt, PBKDF2_ITERATION_COUNT, AES_KEY_BIT);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derivedAesKey, spec);
        byte[] plain = cipher.doFinal(cipherBody);
        return new String(plain, StandardCharsets.UTF_8);
    }

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

        byte[] full = new byte[salt.length + iv.length + cipherBody.length];
        int pos = 0;
        System.arraycopy(salt, 0, full, pos, salt.length);
        pos += salt.length;
        System.arraycopy(iv, 0, full, pos, iv.length);
        pos += iv.length;
        System.arraycopy(cipherBody, 0, full, pos, cipherBody.length);

        return Base64.encodeToString(full, Base64.NO_WRAP);
    }

    private SecretKey pbkdf2Derive(char[] passwordChars, byte[] salt, int iteration, int keyBit) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passwordChars, salt, iteration, keyBit);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public void testDecrypt(String cipherText, char[] password) throws Exception {
        decryptCipherText(cipherText, password);
    }
}
