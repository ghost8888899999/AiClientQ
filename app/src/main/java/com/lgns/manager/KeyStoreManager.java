package com.lgns.manager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
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
    private static final String TAG = "KeyStoreManager";

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_LEN = 128;
    private static final int PBKDF2_SALT_LEN = 16;
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    private static final int AES_KEY_BIT = 256;
    private final SharedPreferences sp;
    private final AppLogDbHelper db;

    public static class KeyEntry {
        public long id;
        public String customCode;
        public String groupNo;
        public String cipherText;
        public String address;
        public boolean enabled = true;

        public KeyEntry() {
        }

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
        db = AppLogDbHelper.getInstance(ctx);
        migrateFromSpIfNeeded();
    }

    private synchronized void migrateFromSpIfNeeded() {
        String spRaw = sp.getString(KEY_ENTRY_LIST, "");
        if (spRaw.isEmpty()) {
            Log.d(TAG, "SP 无数据，无需迁移");
            return;
        }
        int dbCount = db.getKeyEntryCount();
        if (dbCount > 0) {
            Log.d(TAG, "SQLite 已有 " + dbCount + " 条数据，跳过迁移，清除 SP");
            clearSpData();
            return;
        }
        Log.d(TAG, "开始从 SP 迁移到 SQLite...");
        try {
            List<KeyEntry> spList = parseSpEntries(spRaw);
            List<Long> sortIds = parseSpSortOrder();
            for (int i = 0; i < spList.size(); i++) {
                KeyEntry e = spList.get(i);
                int sortOrder = sortIds.contains(e.id) ? sortIds.indexOf(e.id) : i;
                db.insertKeyEntry((int) e.id, e.customCode, e.groupNo, e.cipherText, e.address, e.enabled, sortOrder);
            }
            long nextId = sp.getLong(KEY_NEXT_ID, 1);
            db.putMetaValue("next_id", String.valueOf(nextId));
            long masterId = sp.getLong(KEY_MASTER_ACCOUNT_ID, -1);
            db.putMetaValue("master_account_id", String.valueOf(masterId));
            clearSpData();
            Log.d(TAG, "迁移完成！共 " + spList.size() + " 条密钥已转入 SQLite");
        } catch (Exception e) {
            Log.e(TAG, "迁移失败: " + e.getMessage(), e);
        }
    }

    private List<KeyEntry> parseSpEntries(String raw) {
        List<KeyEntry> res = new ArrayList<>();
        if (raw.isEmpty()) return res;
        String[] parts = raw.split("\\|");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String[] kv = p.split(":", 2);
            if (kv.length != 2) continue;
            String head = kv[0];
            String cipherText = kv[1];
            long id;
            String customCode = "";
            String groupNo = "";
            String addr = null;
            boolean enabled = true;

            String idPart = head;
            if (head.contains("@")) {
                String[] idAndGroup = head.split("@", 2);
                idPart = idAndGroup[0];
                try { groupNo = idAndGroup[1]; } catch (Exception ignore) {}
            }
            if (idPart.contains("#")) {
                String[] idAndCode = idPart.split("#", 2);
                try { id = Long.parseLong(idAndCode[0]); } catch (Exception e) { continue; }
                customCode = idAndCode[1];
            } else {
                try { id = Long.parseLong(idPart); } catch (Exception e) { continue; }
            }
            if (cipherText.contains("@")) {
                String[] ctAndAddr = cipherText.split("@", 2);
                cipherText = ctAndAddr[0];
                addr = ctAndAddr[1];
            }
            if (cipherText.contains("!")) {
                String[] ctAndEnabled = cipherText.split("!", 2);
                cipherText = ctAndEnabled[0];
                try { enabled = Boolean.parseBoolean(ctAndEnabled[1]); } catch (Exception ignore) {}
            }
            res.add(new KeyEntry(id, customCode, groupNo, cipherText, addr, enabled));
        }
        return res;
    }

    private List<Long> parseSpSortOrder() {
        List<Long> ids = new ArrayList<>();
        String str = sp.getString(KEY_CUSTOM_SORT_ORDER, "");
        if (str.isEmpty()) return ids;
        String[] arr = str.split(",");
        for (String s : arr) {
            try { ids.add(Long.parseLong(s.trim())); } catch (Exception ignore) {}
        }
        return ids;
    }

    private void clearSpData() {
        sp.edit()
                .remove(KEY_ENTRY_LIST)
                .remove(KEY_NEXT_ID)
                .remove(KEY_CUSTOM_SORT_ORDER)
                .remove(KEY_MASTER_ACCOUNT_ID)
                .apply();
    }

    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String groupNo) throws Exception {
        return addPrivateKey(rawPrivateKey, sessionCryptoKey, "", groupNo);
    }

    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String customCode, String groupNo) throws Exception {
        long nextId = Long.parseLong(db.getMetaValue("next_id", "1"));
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);
        
        String addr = "0x" + org.web3j.crypto.Keys.getAddress(
                org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(rawPrivateKey)));
        
        int sortOrder = db.getKeyEntryCount();
        db.insertKeyEntry((int) nextId, customCode, groupNo, cipherText, addr, true, sortOrder);
        db.putMetaValue("next_id", String.valueOf(nextId + 1));
        
        return new KeyEntry(nextId, customCode, groupNo, cipherText, addr);
    }

    public void removeByIndex(int index) throws Exception {
        List<KeyEntry> list = loadKeyEntryList();
        if (index < 0 || index >= list.size()) {
            throw new Exception("索引越界");
        }
        long removeId = list.get(index).id;
        db.deleteKeyEntry((int) removeId);
    }

    public void updateKeyEntry(int id, String customCode, String groupNo, boolean enabled) {
        db.updateKeyEntry(id, customCode, groupNo, enabled);
    }

    public void clearAllKeys() {
        db.clearAllKeyEntries();
        db.putMetaValue("next_id", "1");
        db.putMetaValue("master_account_id", "-1");
    }

    public List<KeyEntry> loadKeyEntryList() {
        List<KeyEntry> res = new ArrayList<>();
        android.database.sqlite.SQLiteDatabase sqliteDb = db.getReadableDatabase();
        android.database.Cursor cursor = sqliteDb.query(AppLogDbHelper.TABLE_KEY_ENTRY,
                null, null, null, null, null, AppLogDbHelper.COL_KE_SORT_ORDER + " ASC");
        while (cursor.moveToNext()) {
            KeyEntry e = new KeyEntry();
            e.id = cursor.getLong(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ID));
            e.customCode = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_CUSTOM_CODE));
            e.groupNo = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_GROUP_NO));
            e.cipherText = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_CIPHER_TEXT));
            e.address = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ADDRESS));
            e.enabled = cursor.getInt(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ENABLED)) == 1;
            res.add(e);
        }
        cursor.close();
        return res;
    }

    public void saveKeyEntryList(List<KeyEntry> list) {
        android.database.sqlite.SQLiteDatabase sqliteDb = db.getWritableDatabase();
        sqliteDb.beginTransaction();
        try {
            sqliteDb.delete(AppLogDbHelper.TABLE_KEY_ENTRY, null, null);
            for (int i = 0; i < list.size(); i++) {
                KeyEntry e = list.get(i);
                db.insertKeyEntry((int) e.id, e.customCode, e.groupNo, e.cipherText, e.address, e.enabled, i);
            }
            sqliteDb.setTransactionSuccessful();
        } finally {
            sqliteDb.endTransaction();
        }
    }

    public List<Long> getCustomSortIdList() {
        List<Long> ids = new ArrayList<>();
        android.database.Cursor cursor = db.getReadableDatabase().query(AppLogDbHelper.TABLE_KEY_ENTRY,
                new String[]{AppLogDbHelper.COL_KE_ID}, null, null, null, null,
                AppLogDbHelper.COL_KE_SORT_ORDER + " ASC");
        while (cursor.moveToNext()) {
            ids.add(cursor.getLong(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ID)));
        }
        cursor.close();
        return ids;
    }

    public void saveCustomSortIdList(List<Long> idList) {
        android.database.sqlite.SQLiteDatabase sqliteDb = db.getWritableDatabase();
        sqliteDb.beginTransaction();
        try {
            for (int i = 0; i < idList.size(); i++) {
                db.updateKeyEntrySortOrder(idList.get(i).intValue(), i);
            }
            sqliteDb.setTransactionSuccessful();
        } finally {
            sqliteDb.endTransaction();
        }
    }

    private void appendIdToCustomSort(long newId) {
        List<Long> list = getCustomSortIdList();
        if (!list.contains(newId)) {
            db.updateKeyEntrySortOrder((int) newId, list.size());
        }
    }

    private void removeIdFromCustomSort(long removeId) {
        List<Long> list = getCustomSortIdList();
        list.remove(removeId);
        for (int i = 0; i < list.size(); i++) {
            db.updateKeyEntrySortOrder(list.get(i).intValue(), i);
        }
    }

    public long getMasterAccountId() {
        return Long.parseLong(db.getMetaValue("master_account_id", "-1"));
    }

    public void setMasterAccountId(long id) {
        db.putMetaValue("master_account_id", String.valueOf(id));
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
