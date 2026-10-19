package com.lgns.manager;
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
    private static final String KEY_CUSTOM_SORT_ORDER = "custom_sort_order";
    private static final String KEY_MASTER_ACCOUNT_ID = "master_account_id";

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final int PBKDF2_SALT_LEN = 16;
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    private static final int AES_KEY_BIT = 256;
    private final SharedPreferences sp;

    public static class KeyEntry {
        public long id;
        public String customCode;
        public String groupNo;
        public String cipherText;
        public String address;
        public boolean enabled = true;

        public KeyEntry(long id, String groupNo, String cipherText){
            this(id, "", groupNo, cipherText, null, true);
        }

        public KeyEntry(long id, String groupNo, String cipherText, String address){
            this(id, "", groupNo, cipherText, address, true);
        }

        public KeyEntry(long id, String customCode, String groupNo, String cipherText, String address){
            this(id, customCode, groupNo, cipherText, address, true);
        }

        public KeyEntry(long id, String customCode, String groupNo, String cipherText, String address, boolean enabled){
            this.id = id;
            this.customCode = customCode != null ? customCode : "";
            this.groupNo = groupNo != null ? groupNo : "";
            this.cipherText = cipherText;
            this.address = address;
            this.enabled = enabled;
        }
    }

    public KeyStoreManager(Context ctx) {
        sp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String groupNo) throws Exception {
        return addPrivateKey(rawPrivateKey, sessionCryptoKey, "", groupNo);
    }

    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String customCode, String groupNo) throws Exception {
        long newId = sp.getLong(KEY_NEXT_ID,1);
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);
        
        // 地址带 0x 前缀
        String addr = "0x" + org.web3j.crypto.Keys.getAddress(
                org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(rawPrivateKey)));
        
        List<KeyEntry> list = loadKeyEntryList();
        list.add(new KeyEntry(newId, customCode, groupNo, cipherText, addr));
        saveKeyEntryList(list);
        sp.edit().putLong(KEY_NEXT_ID, newId+1).apply();

        appendIdToCustomSort(newId);
        return new KeyEntry(newId, customCode, groupNo, cipherText, addr);
    }

    public void removeByIndex(int index) throws Exception {
        List<KeyEntry> list = loadKeyEntryList();
        if(index <0 || index >= list.size()){
            throw new Exception("索引越界");
        }
        long removeId = list.get(index).id;
        list.remove(index);
        saveKeyEntryList(list);
        removeIdFromCustomSort(removeId);
    }

    public void clearAllKeys(){
        sp.edit()
                .remove(KEY_ENTRY_LIST)
                .remove(KEY_NEXT_ID)
                .remove(KEY_CUSTOM_SORT_ORDER)
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
            String head = kv[0];
            String cipherText = kv[1];
            long id;
            String customCode = "";
            String groupNo = "";
            String addr = null;
            boolean enabled = true;

            String idPart = head;
            if(head.contains("@")){
                String[] idAndGroup = head.split("@",2);
                idPart = idAndGroup[0];
                try{ groupNo = idAndGroup[1]; }catch (Exception ignore){}
            }
            if(idPart.contains("#")){
                String[] idAndCode = idPart.split("#",2);
                try{ id = Long.parseLong(idAndCode[0]); }catch (Exception e){ continue; }
                customCode = idAndCode[1];
            }else{
                try{ id = Long.parseLong(idPart); }catch (Exception e){ continue; }
            }

            if(cipherText.contains("@")){
                String[] ctAndAddr = cipherText.split("@",2);
                cipherText = ctAndAddr[0];
                addr = ctAndAddr[1];
            }
            if(cipherText.contains("!")){
                String[] ctAndEnabled = cipherText.split("!",2);
                cipherText = ctAndEnabled[0];
                try{ enabled = Boolean.parseBoolean(ctAndEnabled[1]); }catch (Exception ignore){}
            }
            res.add(new KeyEntry(id, customCode, groupNo, cipherText, addr, enabled));
        }
        return res;
    }

    public void saveKeyEntryList(List<KeyEntry> list){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<list.size();i++){
            if(i>0) sb.append("|");
            KeyEntry e = list.get(i);
            sb.append(e.id);
            if(e.customCode != null && !e.customCode.isEmpty()){
                sb.append("#").append(e.customCode);
            }
            if(e.groupNo != null && !e.groupNo.isEmpty()){
                sb.append("@").append(e.groupNo);
            }
            sb.append(":").append(e.cipherText);
            if(!e.enabled){
                sb.append("!false");
            }
            if(e.address != null){
                sb.append("@").append(e.address);
            }
        }
        sp.edit().putString(KEY_ENTRY_LIST, sb.toString()).apply();
    }

    public List<Long> getCustomSortIdList(){
        String str = sp.getString(KEY_CUSTOM_SORT_ORDER,"");
        List<Long> ids = new ArrayList<>();
        if(str.isEmpty()) return ids;
        String[] arr = str.split(",");
        for(String s:arr){
            try{
                ids.add(Long.parseLong(s.trim()));
            }catch (Exception ignore){}
        }
        return ids;
    }

    public void saveCustomSortIdList(List<Long> idList){
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<idList.size();i++){
            if(i>0) sb.append(",");
            sb.append(idList.get(i));
        }
        sp.edit().putString(KEY_CUSTOM_SORT_ORDER, sb.toString()).apply();
    }

    private void appendIdToCustomSort(long newId){
        List<Long> list = getCustomSortIdList();
        if(!list.contains(newId)){
            list.add(newId);
            saveCustomSortIdList(list);
        }
    }

    private void removeIdFromCustomSort(long removeId){
        List<Long> list = getCustomSortIdList();
        list.remove(removeId);
        saveCustomSortIdList(list);
    }

    public long getMasterAccountId() {
        return sp.getLong(KEY_MASTER_ACCOUNT_ID, -1);
    }

    public void setMasterAccountId(long id) {
        sp.edit().putLong(KEY_MASTER_ACCOUNT_ID, id).apply();
    }

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
