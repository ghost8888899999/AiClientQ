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
    // legacy 私钥独立文件 keystore_sp.xml（与合并后的 app_prefs 隔离）
    private final SharedPreferences legacySp;
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
        this.legacySp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        db = AppLogDbHelper.getInstance(ctx);
        migrateFromSpIfNeeded();
    }

    private synchronized void migrateFromSpIfNeeded() {
        String spRaw = legacySp.getString(KEY_ENTRY_LIST, "");
        if (spRaw.isEmpty()) {
            Log.d(TAG, "keystore_sp.xml 无数据，无需迁移");
            return;
        }
        Log.d(TAG, "开始从 SP 合并迁移到 SQLite（不丢弃 legacy 记录）...");
        try {
            List<KeyEntry> spList = parseSpEntries(spRaw);
            List<Long> sortIds = parseSpSortOrder();

            // SQL 现有数据（可能为空）→ 建立 id/密文集合用于去重
            List<KeyEntry> existing = loadKeyEntryList();
            java.util.Set<Long> existIds = new java.util.HashSet<>();
            java.util.Set<String> existCiphers = new java.util.HashSet<>();
            long maxId = 0;
            for (KeyEntry e : existing) {
                existIds.add(e.id);
                if (e.cipherText != null) existCiphers.add(e.cipherText);
                if (e.id > maxId) maxId = e.id;
            }
            long nextId = Long.parseLong(db.getMetaValue("next_id", "1"));
            if (maxId + 1 > nextId) nextId = maxId + 1;

            // 按 SP 的 custom_sort_order 排序（图例 2,1 → a02 在前），未列出的按原序追加
            List<KeyEntry> ordered = new ArrayList<>();
            for (Long sid : sortIds) {
                for (KeyEntry e : spList) {
                    if (e.id == sid.longValue()) { ordered.add(e); break; }
                }
            }
            for (KeyEntry e : spList) {
                if (!ordered.contains(e)) ordered.add(e);
            }

            // 新增到 sqlite：密文相同 = 同一把钥匙 → 跳过防重复；id 冲突 → 取 next_id 重排
            int baseSort = existing.size();
            int imported = 0;
            int lost = 0;
            for (int i = 0; i < ordered.size(); i++) {
                KeyEntry e = ordered.get(i);
                if (e.cipherText != null && existCiphers.contains(e.cipherText)) continue;
                long id = e.id;
                if (existIds.contains(id)) {
                    // 避让：跳过现有 id 和本轮已分配的 id，杜绝二次碰撞
                    while (existIds.contains(nextId)) nextId++;
                    id = nextId++;
                }
                existIds.add(id);
                if (e.cipherText != null) existCiphers.add(e.cipherText);
                if (id > maxId) maxId = id;
                long row = db.insertKeyEntry((int) id, e.customCode, e.groupNo, e.cipherText, e.address, e.enabled, baseSort + i);
                if (row >= 0) {
                    imported++;
                } else {
                    lost++;
                    Log.e(TAG, "插入失败（主键冲突？）id=" + id + " code=" + e.customCode);
                }
            }

            db.putMetaValue("next_id", String.valueOf(Math.max(nextId, maxId + 1)));
            long masterId = legacySp.getLong(KEY_MASTER_ACCOUNT_ID, -1);
            if (masterId >= 0 && getMasterAccountId() < 0) setMasterAccountId(masterId);
            if (lost == 0) {
                clearSpData();
                Log.d(TAG, "迁移完成！新增 " + imported + " 条 legacy 密钥到 sqlite，跳过重复 " + (spList.size() - imported) + " 条");
            } else {
                // 有丢失则保留 xml，下次启动自动重试（已入库的按密文去重跳过）
                Log.e(TAG, "⚠ " + lost + " 条插入失败，保留 keystore_sp.xml 待下次启动重试");
            }
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

            // 头部格式：id[@自定义编号[@分组]] 或 id#自定义编号[@分组]
            // 图例 "1@a01:xxx" 中 a01 为自定义编号，不是分组
            String idPart = head;
            String codePart = null;
            String groupPart = null;
            if (idPart.contains("#")) {
                String[] idAndCode = idPart.split("#", 2);
                idPart = idAndCode[0];
                String rest = idAndCode[1];
                if (rest.contains("@")) {
                    String[] codeAndGroup = rest.split("@", 2);
                    codePart = codeAndGroup[0];
                    groupPart = codeAndGroup[1];
                } else {
                    codePart = rest;
                }
            } else if (idPart.contains("@")) {
                String[] segs = idPart.split("@", 3);
                idPart = segs[0];
                if (segs.length > 1) codePart = segs[1];
                if (segs.length > 2) groupPart = segs[2];
            }
            try {
                id = Long.parseLong(idPart);
            } catch (Exception e) {
                continue;
            }
            customCode = codePart != null ? codePart : "";
            groupNo = groupPart != null ? groupPart : "";
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
        String str = legacySp.getString(KEY_CUSTOM_SORT_ORDER, "");
        if (str.isEmpty()) return ids;
        String[] arr = str.split(",");
        for (String s : arr) {
            try { ids.add(Long.parseLong(s.trim())); } catch (Exception ignore) {}
        }
        return ids;
    }

    private void clearSpData() {
        legacySp.edit()
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

    public static void updateLastLogClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_log_clear_timestamp", timestamp).apply();
    }

    public static long getLastLogClearTimestamp(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        return sp.getLong("last_log_clear_timestamp", 0);
    }

    public static void updateLastClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_clear_timestamp", timestamp).apply();
    }

    public static long getLastClearTimestamp(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        return sp.getLong("last_clear_timestamp", 0);
    }

    public void testDecrypt(String cipherText, char[] password) throws Exception {
        decryptCipherText(cipherText, password);
    }
}
