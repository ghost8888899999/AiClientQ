package com.lgns.manager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.lgns.model.DailyStatusItem;
import com.lgns.model.FailureLogItem;
import com.lgns.task.TaskLogItem;

import java.util.ArrayList;
import java.util.List;

public class AppLogDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "app_log.db";
    private static final int DB_VERSION = 6;
    private static AppLogDbHelper instance;

    public static final String TABLE_LOG = "task_log";
    public static final String COL_ID = "_id";
    public static final String COL_KEY_NO = "key_no";
    public static final String COL_TIME = "log_time";
    public static final String COL_TYPE = "log_type";
    public static final String COL_CONTENT = "log_content";

    public static final String TABLE_GROUP = "key_group";
    public static final String COL_GROUP_NAME = "group_name";
    public static final String COL_GROUP_SORT = "sort_order";
    public static final String COL_GROUP_CREATED = "created_at";

    public static final String TABLE_DAILY_STATUS = "daily_status";
    public static final String COL_DS_KEY_ID = "key_id";
    public static final String COL_DS_KEY_NO = "key_no";
    public static final String COL_DS_DATE = "status_date";
    public static final String COL_DS_TOTAL_QD = "total_qd";
    public static final String COL_DS_TODAY_QD = "today_qd";
    public static final String COL_DS_TODAY_ENTER = "today_enter";
    public static final String COL_DS_TOKENS = "tokens";
    public static final String COL_DS_IS_FAILED = "is_failed";
    public static final String COL_DS_UPDATED = "updated_at";

    public static final String TABLE_FAILURE_LOG = "failure_log";
    public static final String COL_FL_KEY_ID = "key_id";
    public static final String COL_FL_KEY_NO = "key_no";
    public static final String COL_FL_DATE = "fail_date";
    public static final String COL_FL_TYPE = "fail_type";
    public static final String COL_FL_REASON = "fail_reason";
    public static final String COL_FL_TIME = "fail_time";

    public static final String TABLE_KEY_ENTRY = "key_entry";
    public static final String COL_KE_ID = "_id";
    public static final String COL_KE_CUSTOM_CODE = "custom_code";
    public static final String COL_KE_GROUP_NO = "group_no";
    public static final String COL_KE_CIPHER_TEXT = "cipher_text";
    public static final String COL_KE_ADDRESS = "address";
    public static final String COL_KE_ENABLED = "enabled";
    public static final String COL_KE_SORT_ORDER = "sort_order";
    public static final String COL_KE_CREATED = "created_at";

    public static final String TABLE_KEY_META = "key_meta";
    public static final String COL_KM_KEY = "meta_key";
    public static final String COL_KM_VALUE = "meta_value";

    private AppLogDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static synchronized AppLogDbHelper getInstance(Context ctx) {
        if (instance == null) {
            instance = new AppLogDbHelper(ctx);
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_KEY_NO + " TEXT," +
                COL_TIME + " INTEGER NOT NULL," +
                COL_TYPE + " TEXT," +
                COL_CONTENT + " TEXT);";
        db.execSQL(sql);
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_GROUP_NAME + " TEXT UNIQUE NOT NULL," +
                COL_GROUP_SORT + " INTEGER DEFAULT 0," +
                COL_GROUP_CREATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_STATUS + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_DS_KEY_ID + " INTEGER NOT NULL," +
                COL_DS_KEY_NO + " TEXT," +
                COL_DS_DATE + " TEXT NOT NULL," +
                COL_DS_TOTAL_QD + " INTEGER DEFAULT 0," +
                COL_DS_TODAY_QD + " INTEGER DEFAULT 0," +
                COL_DS_TODAY_ENTER + " INTEGER DEFAULT 0," +
                COL_DS_TOKENS + " INTEGER DEFAULT 0," +
                COL_DS_UPDATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAILURE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_FL_KEY_ID + " INTEGER NOT NULL," +
                COL_FL_KEY_NO + " TEXT," +
                COL_FL_DATE + " TEXT NOT NULL," +
                COL_FL_TYPE + " TEXT," +
                COL_FL_REASON + " TEXT," +
                COL_FL_TIME + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_ENTRY + "(" +
                COL_KE_ID + " INTEGER PRIMARY KEY," +
                COL_KE_CUSTOM_CODE + " TEXT DEFAULT ''," +
                COL_KE_GROUP_NO + " TEXT DEFAULT ''," +
                COL_KE_CIPHER_TEXT + " TEXT NOT NULL," +
                COL_KE_ADDRESS + " TEXT," +
                COL_KE_ENABLED + " INTEGER DEFAULT 1," +
                COL_KE_SORT_ORDER + " INTEGER DEFAULT 0," +
                COL_KE_CREATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_META + "(" +
                COL_KM_KEY + " TEXT PRIMARY KEY," +
                COL_KM_VALUE + " TEXT);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            String oldTableName = "task_log_old";
            db.execSQL("ALTER TABLE " + TABLE_LOG + " RENAME TO " + oldTableName);
            onCreate(db);
            String copySql = "INSERT INTO " + TABLE_LOG +
                    "("+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+") " +
                    "SELECT "+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+" FROM "+oldTableName;
            db.execSQL(copySql);
            db.execSQL("DROP TABLE IF EXISTS " + oldTableName);
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_GROUP_NAME + " TEXT UNIQUE NOT NULL," +
                    COL_GROUP_SORT + " INTEGER DEFAULT 0," +
                    COL_GROUP_CREATED + " INTEGER NOT NULL);");
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_STATUS + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_DS_KEY_ID + " INTEGER NOT NULL," +
                    COL_DS_KEY_NO + " TEXT," +
                    COL_DS_DATE + " TEXT NOT NULL," +
                    COL_DS_TOTAL_QD + " INTEGER DEFAULT 0," +
                    COL_DS_TODAY_QD + " INTEGER DEFAULT 0," +
                    COL_DS_TODAY_ENTER + " INTEGER DEFAULT 0," +
                    COL_DS_TOKENS + " INTEGER DEFAULT 0," +
                    COL_DS_UPDATED + " INTEGER NOT NULL);");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAILURE_LOG + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_FL_KEY_ID + " INTEGER NOT NULL," +
                    COL_FL_KEY_NO + " TEXT," +
                    COL_FL_DATE + " TEXT NOT NULL," +
                    COL_FL_TYPE + " TEXT," +
                    COL_FL_REASON + " TEXT," +
                    COL_FL_TIME + " INTEGER NOT NULL);");
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE " + TABLE_DAILY_STATUS + " ADD COLUMN " + COL_DS_IS_FAILED + " INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_ENTRY + "(" +
                    COL_KE_ID + " INTEGER PRIMARY KEY," +
                    COL_KE_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_KE_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_KE_CIPHER_TEXT + " TEXT NOT NULL," +
                    COL_KE_ADDRESS + " TEXT," +
                    COL_KE_ENABLED + " INTEGER DEFAULT 1," +
                    COL_KE_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_KE_CREATED + " INTEGER NOT NULL);");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_META + "(" +
                    COL_KM_KEY + " TEXT PRIMARY KEY," +
                    COL_KM_VALUE + " TEXT);");
        }
    }

    // 【更新】完整入参：keyNo密钥编号, logType ENTER/CLAIM, content日志内容
    public long insertLog(String keyNo, String logType, String content, long timeMs) {
        ContentValues cv = new ContentValues();
        if(keyNo != null){
            cv.put(COL_KEY_NO, keyNo);
        }
        cv.put(COL_TIME, timeMs); //使用外部传入的时间戳
        if(logType != null){
            cv.put(COL_TYPE, logType);
        }
        cv.put(COL_CONTENT, content);

        SQLiteDatabase db = getWritableDatabase();
        long rowId = db.insert(TABLE_LOG, null, cv);
        Log.d("insertLog_DB", "rowId=" + rowId
                + " | keyNo=" + keyNo
                + " | logType=" + logType
                + " | content=" + content);
        if(rowId == -1){
            Log.e("insertLog_DB", "⚠日志数据库插入失败！rowId = -1");
        }
        return rowId;
    }


    public int clearAllLog() {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_LOG, null, null);
    }

    public List<TaskLogItem> queryAllLogDesc() {
        List<TaskLogItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_LOG, null, null, null, null, null, COL_TIME + " DESC");
        while (cursor.moveToNext()) {
            TaskLogItem item = new TaskLogItem();
            item.logId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
            //数据库NULL → Java对象得到null，正好适配上层判空逻辑
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_KEY_NO));
            item.timeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIME));
            item.logType = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE));
            item.content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT));
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // ========== 分组管理 CRUD ==========
    public long insertGroup(String groupName) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_NAME, groupName);
        cv.put(COL_GROUP_SORT, System.currentTimeMillis());
        cv.put(COL_GROUP_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_GROUP, null, cv);
    }

    public int deleteGroup(String groupName) {
        return getWritableDatabase().delete(TABLE_GROUP, COL_GROUP_NAME + "=?", new String[]{groupName});
    }

    public int renameGroup(String oldName, String newName) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_NAME, newName);
        return getWritableDatabase().update(TABLE_GROUP, cv, COL_GROUP_NAME + "=?", new String[]{oldName});
    }

    public List<String> queryAllGroups() {
        List<String> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_GROUP,
                new String[]{COL_GROUP_NAME}, null, null, null, null,
                COL_GROUP_SORT + " ASC");
        while (cursor.moveToNext()) {
            list.add(cursor.getString(cursor.getColumnIndexOrThrow(COL_GROUP_NAME)));
        }
        cursor.close();
        return list;
    }

    public boolean groupExists(String groupName) {
        Cursor cursor = getReadableDatabase().query(TABLE_GROUP,
                new String[]{COL_ID}, COL_GROUP_NAME + "=?", new String[]{groupName},
                null, null, null, "1");
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    public int updateGroupSortOrder(String groupName, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_SORT, sortOrder);
        return getWritableDatabase().update(TABLE_GROUP, cv, COL_GROUP_NAME + "=?", new String[]{groupName});
    }

    // ========== 当日状态 CRUD ==========
    public long upsertDailyStatus(long keyId, String keyNo, String date, int totalQd, int todayQd, int todayEnter, long tokens, boolean isFailed) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DS_KEY_ID, keyId);
        cv.put(COL_DS_KEY_NO, keyNo);
        cv.put(COL_DS_DATE, date);
        cv.put(COL_DS_TOTAL_QD, totalQd);
        cv.put(COL_DS_TODAY_QD, todayQd);
        cv.put(COL_DS_TODAY_ENTER, todayEnter);
        cv.put(COL_DS_TOKENS, tokens);
        cv.put(COL_DS_IS_FAILED, isFailed ? 1 : 0);
        cv.put(COL_DS_UPDATED, System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(TABLE_DAILY_STATUS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<DailyStatusItem> queryDailyStatus(String date) {
        List<DailyStatusItem> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_DAILY_STATUS, null,
                COL_DS_DATE + "=?", new String[]{date}, null, null, COL_DS_KEY_ID + " ASC");
        while (cursor.moveToNext()) {
            DailyStatusItem item = new DailyStatusItem();
            item.keyId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DS_KEY_ID));
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_DS_KEY_NO));
            item.date = cursor.getString(cursor.getColumnIndexOrThrow(COL_DS_DATE));
            item.totalQd = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TOTAL_QD));
            item.todayQd = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TODAY_QD));
            item.todayEnter = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TODAY_ENTER));
            item.tokens = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DS_TOKENS));
            item.isFailed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_IS_FAILED)) == 1;
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // ========== 失败日志 CRUD ==========
    public long insertFailure(long keyId, String keyNo, String date, String failType, String reason) {
        ContentValues cv = new ContentValues();
        cv.put(COL_FL_KEY_ID, keyId);
        cv.put(COL_FL_KEY_NO, keyNo);
        cv.put(COL_FL_DATE, date);
        cv.put(COL_FL_TYPE, failType);
        cv.put(COL_FL_REASON, reason);
        cv.put(COL_FL_TIME, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_FAILURE_LOG, null, cv);
    }

    public List<FailureLogItem> queryFailureLog(String date) {
        List<FailureLogItem> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_FAILURE_LOG, null,
                COL_FL_DATE + "=?", new String[]{date}, null, null, COL_FL_TIME + " DESC");
        while (cursor.moveToNext()) {
            FailureLogItem item = new FailureLogItem();
            item.keyId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FL_KEY_ID));
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_KEY_NO));
            item.date = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_DATE));
            item.failType = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_TYPE));
            item.reason = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_REASON));
            item.timeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FL_TIME));
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // ========== 清空日志 ==========
    public int deleteAllLogs() {
        return getWritableDatabase().delete(TABLE_LOG, null, null);
    }

    // ========== 重建表（保留分组和密钥）==========
    public void recreateTablesExceptGroups() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_LOG, null, null);
            db.delete(TABLE_DAILY_STATUS, null, null);
            db.delete(TABLE_FAILURE_LOG, null, null);
            
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOG);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_DAILY_STATUS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_FAILURE_LOG);
            
            onCreate(db);
            
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ========== 密钥管理 CRUD ==========
    public long insertKeyEntry(int id, String customCode, String groupNo, String cipherText, String address, boolean enabled, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_ID, id);
        cv.put(COL_KE_CUSTOM_CODE, customCode != null ? customCode : "");
        cv.put(COL_KE_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_KE_CIPHER_TEXT, cipherText);
        cv.put(COL_KE_ADDRESS, address);
        cv.put(COL_KE_ENABLED, enabled ? 1 : 0);
        cv.put(COL_KE_SORT_ORDER, sortOrder);
        cv.put(COL_KE_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_KEY_ENTRY, null, cv);
    }

    public int deleteKeyEntry(int id) {
        return getWritableDatabase().delete(TABLE_KEY_ENTRY, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    public int updateKeyEntry(int id, String customCode, String groupNo, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_CUSTOM_CODE, customCode != null ? customCode : "");
        cv.put(COL_KE_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_KE_ENABLED, enabled ? 1 : 0);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    public int updateKeyEntryAddress(int id, String address) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_ADDRESS, address);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    public int updateKeyEntrySortOrder(int id, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_SORT_ORDER, sortOrder);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    public int clearAllKeyEntries() {
        return getWritableDatabase().delete(TABLE_KEY_ENTRY, null, null);
    }

    public boolean keyEntryAddressExists(String address) {
        if (address == null) return false;
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_ENTRY,
                new String[]{COL_KE_ID}, COL_KE_ADDRESS + "=?", new String[]{address},
                null, null, null, "1");
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    public List<int[]> queryKeyEntryIdAndSort() {
        List<int[]> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_ENTRY,
                new String[]{COL_KE_ID, COL_KE_SORT_ORDER},
                null, null, null, null, COL_KE_SORT_ORDER + " ASC");
        while (cursor.moveToNext()) {
            list.add(new int[]{
                    (int) cursor.getLong(cursor.getColumnIndexOrThrow(COL_KE_ID)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(COL_KE_SORT_ORDER))
            });
        }
        cursor.close();
        return list;
    }

    public int getKeyEntryCount() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_KEY_ENTRY, null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    // ========== key_meta CRUD ==========
    public String getMetaValue(String key, String defaultValue) {
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_META,
                new String[]{COL_KM_VALUE}, COL_KM_KEY + "=?", new String[]{key},
                null, null, null, "1");
        String val = defaultValue;
        if (cursor.moveToFirst()) val = cursor.getString(cursor.getColumnIndexOrThrow(COL_KM_VALUE));
        cursor.close();
        return val;
    }

    public void putMetaValue(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KM_KEY, key);
        cv.put(COL_KM_VALUE, value);
        getWritableDatabase().insertWithOnConflict(TABLE_KEY_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
