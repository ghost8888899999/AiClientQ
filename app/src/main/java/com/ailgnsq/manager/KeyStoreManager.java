package com.ailgnsq.manager;
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

/**
 * 私钥仓库：负责密钥的加密存储、读写与迁移。
 *
 * <p>存储口径：私钥本体永远只以密文形式存在（Base64 字符串：salt | iv | 密文），
 * 明文只在调用方传入会话密码时临时解出。加密算法固定为
 * PBKDF2WithHmacSHA256 派生密钥 + AES-256-GCM，参数见下方常量。</p>
 *
 * <p>存储位置：当前主库是 SQLite 的 {@code key_entry} 表（经 {@link AppLogDbHelper}），
 * 旧的 {@code keystore_sp.xml} 只在构造时做一次一次性迁移，迁完清空。</p>
 *
 * <p>线程约定：本类不加锁（除了迁移），调用方需保证「不跨线程同时改同一张表」；
 * UI 层实际都是在后台线程读写、主线程只拿结果。</p>
 */
public class KeyStoreManager {

    // region 常量与存储键

    /** legacy 私钥的 SP 文件名（已废弃，仅迁移时读） */
    private static final String SP_NAME = "keystore_sp";
    /** legacy 密钥列表键：格式为 {@code id@编号@分组:密文!enabled|...} */
    private static final String KEY_ENTRY_LIST = "key_entry_list";
    /** legacy 自增 id 计数键 */
    private static final String KEY_NEXT_ID = "next_key_id";
    /** legacy 自定义排序（逗号分隔的 id 串） */
    private static final String KEY_CUSTOM_SORT_ORDER = "custom_sort_order";
    /** legacy 主号 id */
    private static final String KEY_MASTER_ACCOUNT_ID = "master_account_id";
    /** 日志 TAG */
    private static final String TAG = "KeyStoreManager";

    /** GCM 的 IV 长度（字节）：12 字节是 GCM 推荐值，短了不安全、长了不省空间 */
    private static final int GCM_IV_LEN = 12;
    /** GCM 认证标签长度（位）：128 位标签拒绝任何密文篡改 */
    private static final int GCM_TAG_LEN = 128;
    /** PBKDF2 盐长度（字节）：每条密文独立随机盐，防止同密码同明文得到相同密文 */
    private static final int PBKDF2_SALT_LEN = 16;
    /** PBKDF2 迭代次数：手机上约百毫秒量级，兼顾抗暴破和解锁体验 */
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    /** AES 密钥位数（bit） */
    private static final int AES_KEY_BIT = 256;

    /** legacy 私钥独立文件 keystore_sp.xml（与合并后的 app_prefs 隔离） */
    private final SharedPreferences legacySp;
    /** 当前唯一的数据访问层，所有表操作都经它 */
    private final AppLogDbHelper db;

    // endregion

    // region 数据模型

    /**
     * 一条密钥记录（表里的一行），也是对外传参用的载体。
     *
     * <p>注意 {@code cipherText} 为空表示这条记录没有密文（历史上导入失败的脏数据），
     * 改密码、解密、入场前都会先校验它，不能假设非空。</p>
     */
    public static class KeyEntry {
        /** 主键：解密、改开关、转账目标都靠它定位 */
        public long id;
        /** 用户自定义编号；可为空 */
        public String customCode;
        /** 所属分组名；可为空 */
        public String groupNo;
        /** 私钥密文（Base64） */
        public String cipherText;
        /** 由私钥推导出的地址（可空：历史数据可能没有） */
        public String address;
        /** 是否启用：批量任务只取启用行 */
        public boolean enabled = true;

        /** 空对象：供游标逐字段回填使用 */
        public KeyEntry() {
        }

        /** 旧版签名（无自定义编号/地址），保留以兼容旧调用方 */
        public KeyEntry(long id, String groupNo, String cipherText){
            this(id, "", groupNo, cipherText, null, true);
        }

        /** 带地址但不带编号：供迁移回填时保持原有字段 */
        public KeyEntry(long id, String groupNo, String cipherText, String address){
            this(id, "", groupNo, cipherText, address, true);
        }

        /** 带编号与地址，默认启用 */
        public KeyEntry(long id, String customCode, String groupNo, String cipherText, String address){
            this(id, customCode, groupNo, cipherText, address, true);
        }

        /**
         * 全参构造：编号/分组传 null 统一存成空串，
         * 避免后续展示与分组比较出现 null 判断遗漏。
         */
        public KeyEntry(long id, String customCode, String groupNo, String cipherText, String address, boolean enabled){
            this.id = id;
            this.customCode = customCode != null ? customCode : "";
            this.groupNo = groupNo != null ? groupNo : "";
            this.cipherText = cipherText;
            this.address = address;
            this.enabled = enabled;
        }
    }

    // endregion

    /**
     * 构造时顺手做 legacy 迁移：每次解锁都会走一次，没数据时开销极小。
     */
    public KeyStoreManager(Context ctx) {
        this.legacySp = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        db = AppLogDbHelper.getInstance(ctx);
        migrateFromSpIfNeeded();
    }

    // region legacy 迁移（keystore_sp.xml → SQLite）

    /**
     * 把旧 SP 里的密钥合并进 SQLite，失败也不抛错（不影响本次启动）。
     *
     * <p>四个关键约定：</p>
     * <ul>
     *   <li>不丢 legacy 记录：密文相同视为同一把钥匙，跳过防重复；id 冲突则用 next_id
     *   避让重排（要同时避开现有 id 与本轮已分配的 id，否则会二次碰撞）；</li>
     *   <li>排序按旧的自定义顺序还原，未列出的按原序追加；</li>
     *   <li>有任何一条插入失败就保留 xml，下次启动自动重试（已入库的会被去重跳过）；</li>
     *   <li>全部成功才清空 SP，避免“清掉了但没搬完”。</li>
     * </ul>
     */
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

    /**
     * 解旧 SP 的密钥串：{@code id[@编号[@分组]]:密文[!enabled][@地址]} 用 {@code |} 拼接。
     *
     * <p>头部兼容两种分隔：{@code #} 是后来的写法，{@code @} 是更早的写法。
     * 注意老格式 {@code 1@a01:xxx} 里的 a01 是自定义编号而不是分组（旧版遗留的歧义）；
     * 解析失败的条目直接跳过，不阻断其余条目导入。</p>
     */
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

    /** 解旧 SP 的自定义排序（逗号分隔 id），非法项跳过 */
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

    /** 清空 legacy 四个键（仅在全量迁移成功时调用） */
    private void clearSpData() {
        legacySp.edit()
                .remove(KEY_ENTRY_LIST)
                .remove(KEY_NEXT_ID)
                .remove(KEY_CUSTOM_SORT_ORDER)
                .remove(KEY_MASTER_ACCOUNT_ID)
                .apply();
    }

    // endregion

    // region 密钥增删改

    /**
     * 新增私钥（不带自定义编号）。
     *
     * @param rawPrivateKey    明文私钥
     * @param sessionCryptoKey 会话密码，用于加密
     * @param groupNo          分组名，可空
     */
    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String groupNo) throws Exception {
        return addPrivateKey(rawPrivateKey, sessionCryptoKey, "", groupNo);
    }

    /**
     * 新增私钥：加密 → 推导地址 → 取排序号 → 插入 → 推进 id 计数。
     *
     * <p>两个细节：</p>
     * <ul>
     *   <li>排序号取现有最大值 + 1，而不是取行数：删过密钥后用行数会与老记录撞号，
     *   新密钥的位置就变成随机的；</li>
     *   <li>插入失败必须抛异常：旧实现忽略返回值，会出现「提示添加成功但库里没有」。</li>
     * </ul>
     */
    public KeyEntry addPrivateKey(String rawPrivateKey, char[] sessionCryptoKey, String customCode, String groupNo) throws Exception {
        long nextId = Long.parseLong(db.getMetaValue("next_id", "1"));
        String cipherText = encrypt(rawPrivateKey, sessionCryptoKey);
        
        String addr = "0x" + org.web3j.crypto.Keys.getAddress(
                org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(rawPrivateKey)));
        
        // sort_order 取现有最大值 +1：用表行数会在删过密钥后与老记录撞号，导致新密钥排序随机
        int sortOrder = 0;
        for (int[] idAndSort : db.queryKeyEntryIdAndSort()) {
            if (idAndSort[1] >= sortOrder) sortOrder = idAndSort[1] + 1;
        }
        long row = db.insertKeyEntry((int) nextId, customCode, groupNo, cipherText, addr, true, sortOrder);
        // 插入失败必须抛错：旧实现忽略返回值，会提示“添加成功”但库里根本没有
        if (row < 0) throw new Exception("私钥写入失败，请重试");
        db.putMetaValue("next_id", String.valueOf(nextId + 1));
        
        return new KeyEntry(nextId, customCode, groupNo, cipherText, addr);
    }

    /**
     * 按列表下标删除（列表需与 {@link #loadKeyEntryList()} 顺序一致）。
     *
     * <p>用下标而不是 id 是历史接口，调用方必须先拉到同一份排序的列表；
     * 越界直接抛异常，不做静默忽略。</p>
     */
    public void removeByIndex(int index) throws Exception {
        List<KeyEntry> list = loadKeyEntryList();
        if (index < 0 || index >= list.size()) {
            throw new Exception("索引越界");
        }
        long removeId = list.get(index).id;
        db.deleteKeyEntry((int) removeId);
    }

    /** 整体更新编号/分组/启用状态（编辑弹窗保存时的旧接口，会连 enabled 一起覆盖） */
    public void updateKeyEntry(int id, String customCode, String groupNo, boolean enabled) {
        db.updateKeyEntry(id, customCode, groupNo, enabled);
    }

    /** 只改启用状态，返回受影响行数（0 表示这条已不存在或写失败） */
    public int setKeyEnabled(long id, boolean enabled) {
        return db.updateKeyEnabled((int) id, enabled);
    }

    /** 只改编号/分组，不动 enabled，避免编辑保存时把开关状态覆盖成内存里的旧值 */
    public void updateKeyMeta(int id, String customCode, String groupNo) {
        db.updateKeyEntryMeta(id, customCode, groupNo);
    }

    /** 分组重命名：一条 UPDATE 改完同组记录 */
    public int renameKeyGroup(String oldGroup, String newGroup) {
        return db.updateKeyGroupNo(oldGroup, newGroup);
    }

    /**
     * 改密码：先用旧密码把全部私钥解出来（任一失败即整体中止、一条也不改），
     * 再用新密码重加密并事务性回写。
     *
     * <p>为什么要先全解再全加密：半途失败会变成「一半旧密码一半新密码」，
     * 用户再也解不开。解密得到的明文在 finally 里置 null，尽量缩短内存驻留时间。</p>
     *
     * @return 重加密的条数
     */
    public int changeCryptoKey(char[] oldKey, char[] newKey) throws Exception {
        List<KeyEntry> list = loadKeyEntryList();
        if (list.isEmpty()) throw new Exception("暂无可修改的私钥");
        List<String> plains = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            KeyEntry e = list.get(i);
            if (e.cipherText == null || e.cipherText.isEmpty()) {
                throw new Exception("第 " + (i + 1) + " 条密钥密文缺失，无法改密");
            }
            try {
                plains.add(decryptCipherText(e.cipherText, oldKey));
            } catch (Exception ex) {
                throw new Exception("旧密码不正确（第 " + (i + 1) + " 条解密失败），未做任何修改");
            }
        }
        List<KeyEntry> updated = new ArrayList<>(list.size());
        try {
            for (int i = 0; i < list.size(); i++) {
                KeyEntry e = list.get(i);
                updated.add(new KeyEntry(e.id, e.customCode, e.groupNo,
                        encrypt(plains.get(i), newKey), e.address, e.enabled));
            }
        } finally {
            for (int i = 0; i < plains.size(); i++) plains.set(i, null);
        }
        saveKeyEntryList(updated);
        return updated.size();
    }

    /**
     * 清空全部密钥（自动清除/重置时调用）：同时把 id 计数与主号重置，
     * 避免新加的密钥接着已删数据的编号。
     */
    public void clearAllKeys() {
        db.clearAllKeyEntries();
        db.putMetaValue("next_id", "1");
        db.putMetaValue("master_account_id", "-1");
    }

    /** 读全部密钥，按 sort_order 升序（列表展示顺序与拖拽结果的唯一依据） */
    public List<KeyEntry> loadKeyEntryList() {
        List<KeyEntry> res = new ArrayList<>();
        android.database.sqlite.SQLiteDatabase sqliteDb = db.getReadableDatabase();
        android.database.Cursor cursor = sqliteDb.query(AppLogDbHelper.TABLE_KEY_ENTRY,
                null, null, null, null, null, AppLogDbHelper.COL_KE_SORT_ORDER + " ASC");
        while (cursor.moveToNext()) {
            KeyEntry e = new KeyEntry();
            e.id = cursor.getLong(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ID));
            e.customCode = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_CUSTOM_CODE));
            if (e.customCode == null) e.customCode = "";
            e.groupNo = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_GROUP_NO));
            e.cipherText = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_CIPHER_TEXT));
            e.address = cursor.getString(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ADDRESS));
            e.enabled = cursor.getInt(cursor.getColumnIndexOrThrow(AppLogDbHelper.COL_KE_ENABLED)) == 1;
            res.add(e);
        }
        cursor.close();
        return res;
    }

    /**
     * 整表覆盖保存（先删后插，同一事务）。
     *
     * <p>“先删后插”一旦某条插入失败就会整表丢数据，所以失败必须抛错让事务回滚；
     * 插入时的 sort_order 就取列表下标。
     * 因为会丢掉未包含在列表里的记录，除改密/迁移外不要随便调。</p>
     */
    public void saveKeyEntryList(List<KeyEntry> list) throws Exception {
        android.database.sqlite.SQLiteDatabase sqliteDb = db.getWritableDatabase();
        sqliteDb.beginTransaction();
        try {
            sqliteDb.delete(AppLogDbHelper.TABLE_KEY_ENTRY, null, null);
            for (int i = 0; i < list.size(); i++) {
                KeyEntry e = list.get(i);
                long row = db.insertKeyEntry((int) e.id, e.customCode, e.groupNo, e.cipherText, e.address, e.enabled, i);
                // “先删后插”一旦某条插失败会整表丢数据，必须抛错让事务回滚
                if (row < 0) throw new Exception("写入第 " + (i + 1) + " 条密钥失败，已回滚");
            }
            sqliteDb.setTransactionSuccessful();
        } finally {
            sqliteDb.endTransaction();
        }
    }

    // endregion

    // region 自定义排序

    /** 按当前排序返回 id 列表，供拖拽页回显与提交 */
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

    /** 把新的排序写回（事务内逐条 update sort_order，下标即顺序） */
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

    /** 把新密钥追加到排序末尾（仅当它还没进排序表时） */
    private void appendIdToCustomSort(long newId) {
        List<Long> list = getCustomSortIdList();
        if (!list.contains(newId)) {
            db.updateKeyEntrySortOrder((int) newId, list.size());
        }
    }

    /** 删除后重排剩下的 sort_order，保持 0..n-1 连续，避免出现空洞影响后续追加 */
    private void removeIdFromCustomSort(long removeId) {
        List<Long> list = getCustomSortIdList();
        list.remove(removeId);
        for (int i = 0; i < list.size(); i++) {
            db.updateKeyEntrySortOrder(list.get(i).intValue(), i);
        }
    }

    // endregion

    // region 主号

    /** 取主号 id；返回 -1 表示未设置 */
    public long getMasterAccountId() {
        return Long.parseLong(db.getMetaValue("master_account_id", "-1"));
    }

    /** 设置主号 id（传 -1 取消） */
    public void setMasterAccountId(long id) {
        db.putMetaValue("master_account_id", String.valueOf(id));
    }

    // endregion

    // region 加解密（PBKDF2 + AES-256-GCM）

    /**
     * 解密一条密文。
     *
     * <p>密文布局：{@code Base64(salt[16] | iv[12] | cipherBody)}，与 {@link #encrypt} 对称。
     * 密码错误或密文被改会直接在 {@code doFinal} 抛 AEADBadTagException——
     * GCM 的认证标签同时承担了「密码对不对」的校验作用，不需要额外存校验位。</p>
     *
     * <p>注意本方法不负责清零 {@code sessionCryptoKey}，也拿不到明文副本，
     * 调用方解出的私钥字符串用完应自己尽快丢弃。</p>
     *
     * @throws Exception 密码错误、密文损坏或格式不对
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

    /**
     * 加密一字符串（实际用法就是加密私钥）。
     *
     * <p>每次都用 {@code SecureRandom} 新建 salt 与 iv：即使同一把私钥、同一个密码，
     * 两次加密结果也完全不同（否则密文可被直接比对猜出“这两行是同一个号”）。</p>
     */
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

    /**
     * PBKDF2 派生：把用户密码 + 盐 转成 AES 密钥。
     *
     * <p>用 char[] 而非 String 存密码是为了能主动清零，减少明文密码在内存里停留的时间。</p>
     */
    private SecretKey pbkdf2Derive(char[] passwordChars, byte[] salt, int iteration, int keyBit) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passwordChars, salt, iteration, keyBit);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // endregion

    // region 清理时间戳（存 AppPrefs，与 Worker/调度器共用同一份）

    /** 记录日志清理时间 */
    public static void updateLastLogClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_log_clear_timestamp", timestamp).apply();
    }

    /** 读日志清理时间；0 表示从未清理过 */
    public static long getLastLogClearTimestamp(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        return sp.getLong("last_log_clear_timestamp", 0);
    }

    /** 记录私钥清理时间（自动清除靠它判断是否到期） */
    public static void updateLastClearTimestamp(long timestamp, Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        sp.edit().putLong("last_clear_timestamp", timestamp).apply();
    }

    /** 读私钥清理时间；0 表示从未清理过（首次启用时会被当成已到期） */
    public static long getLastClearTimestamp(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        return sp.getLong("last_clear_timestamp", 0);
    }

    // endregion

    /**
     * 试解密：只用于验证密码是否正确，不返回明文。
     *
     * <p>失败即抛异常，调用方（密码校验/改密前的检查）只需 catch 就能区分对错。</p>
     */
    public void testDecrypt(String cipherText, char[] password) throws Exception {
        decryptCipherText(cipherText, password);
    }
}