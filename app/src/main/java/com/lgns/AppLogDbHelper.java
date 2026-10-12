package com.lgns;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class AppLogDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "app_log.db";
    private static final int DB_VERSION = 2; // 版本升级，触发onUpgrade
    private static AppLogDbHelper instance;

    public static final String TABLE_LOG = "task_log";
    public static final String COL_ID = "_id";
    public static final String COL_KEY_NO = "key_no";
    public static final String COL_TIME = "log_time";
    public static final String COL_TYPE = "log_type";
    public static final String COL_CONTENT = "log_content";

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
        // ✅移除 NOT NULL，key_no、log_type允许NULL
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_KEY_NO + " TEXT," +
                COL_TIME + " INTEGER NOT NULL," +
                COL_TYPE + " TEXT," +
                COL_CONTENT + " TEXT);";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            //迁移：旧表改名，创建新表，复制旧数据
            String oldTableName = "task_log_old";
            db.execSQL("ALTER TABLE " + TABLE_LOG + " RENAME TO " + oldTableName);
            onCreate(db);
            //复制旧数据，旧表的null约束字段，null会转为数据库NULL
            String copySql = "INSERT INTO " + TABLE_LOG +
                    "("+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+") " +
                    "SELECT "+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+" FROM "+oldTableName;
            db.execSQL(copySql);
            db.execSQL("DROP TABLE IF EXISTS " + oldTableName);
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
}
