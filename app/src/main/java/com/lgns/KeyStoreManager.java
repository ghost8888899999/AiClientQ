package com.lgns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class KeyStoreManager {
    private static final String SP_NAME = "keystore_sp";
    private static final String KEY_CIPHER_LIST = "cipher_list";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;

    private final SharedPreferences sp;
    private final SecretKey mAesSecretKey;

    //和AppGlobal匹配的构造：传入系统生成的AES SecretKey
    public KeyStoreManager(Context ctx, SecretKey aesKey) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        mAesSecretKey = aesKey;
    }

    /** 添加私钥：使用本次会话传入的char[]种子做加密，保存密文字符串 */
    public void addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey) throws Exception {
        List<String> cipherList = loadCipherTextList();
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);
        cipherList.add(cipherText);
        saveCipherList(cipherList);
    }

    /** 根据索引删除密文 */
    public void removeByIndex(int index) throws Exception {
        List<String> cipherList = loadCipherTextList();
        if(index <0 || index >= cipherList.size()){
            throw new Exception("索引越界");
        }
        cipherList.remove(index);
        saveCipherList(cipherList);
    }

    /** 清空全部密文 */
    public void clearAllKeys(){
        sp.edit().remove(KEY_CIPHER_LIST).apply();
    }

    /** 获取全部密文列表 */
    public List<String> loadCipherTextList(){
        String json = sp.getString(KEY_CIPHER_LIST,"");
        List<String> res = new ArrayList<>();
        if(json == null || json.isEmpty()){
            return res;
        }
        String[] arr = json.split("\\|");
        for(String s : arr){
            if(!s.isEmpty()) res.add(s);
        }
        return res;
    }

    private void saveCipherList(List<String> list){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<list.size();i++){
            if(i>0) sb.append("|");
            sb.append(list.get(i));
        }
        sp.edit().putString(KEY_CIPHER_LIST, sb.toString()).apply();
    }

    /** 解密密文，会话key不匹配抛出异常 */
    public String decryptCipherText(String cipherBase64, char[] sessionCryptoKey) throws Exception {
        byte[] fullData = Base64.decode(cipherBase64, Base64.NO_WRAP);
        byte[] iv = new byte[GCM_IV_LEN];
        System.arraycopy(fullData,0,iv,0,GCM_IV_LEN);
        byte[] cipherBody = new byte[fullData.length - GCM_IV_LEN];
        System.arraycopy(fullData,GCM_IV_LEN,cipherBody,0,cipherBody.length);

        SecretKey derivedKey = deriveKeyFromChars(sessionCryptoKey);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derivedKey, spec);
        byte[] plain = cipher.doFinal(cipherBody);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 加密明文私钥，返回base64密文 */
    public String encrypt(String plainText, char[] sessionCryptoKey) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[GCM_IV_LEN];
        random.nextBytes(iv);
        SecretKey derivedKey = deriveKeyFromChars(sessionCryptoKey);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, spec);
        byte[] cipherBody = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        byte[] full = new byte[iv.length + cipherBody.length];
        System.arraycopy(iv,0,full,0,iv.length);
        System.arraycopy(cipherBody,0,full,iv.length,cipherBody.length);
        return Base64.encodeToString(full, Base64.NO_WRAP);
    }

    //由用户输入char[]会话密码派生AES密钥
    private SecretKey deriveKeyFromChars(char[] passChars) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(passChars);
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] key32 = new byte[32];
        for(int i=0;i<raw.length;i++){
            key32[i % 32] ^= raw[i];
        }
        return new SecretKeySpec(key32,"AES");
    }

    /**
     * 仅用于校验密码是否正确；解密成功无返回，失败抛异常
     * @param cipherText 密文
     * @param password 密码字符数组
     * @throws Exception 密码错误/解密失败抛出异常
     */
    public void testDecrypt(String cipherText, char[] password) throws Exception{
        //调用真实解密方法 decryptCipherText，丢弃返回明文，仅用来校验密码，异常自动上抛
        decryptCipherText(cipherText, password);
    }

}
