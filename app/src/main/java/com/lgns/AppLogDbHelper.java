package com.lgns;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class AppLogDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "app_log.db";
    private static final int DB_VERSION = 1;
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
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_KEY_NO + " TEXT NOT NULL," +
                COL_TIME + " INTEGER NOT NULL," +
                COL_TYPE + " TEXT NOT NULL," +
                COL_CONTENT + " TEXT);";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // 【更新】完整入参：keyNo密钥编号, logType ENTER/CLAIM, content日志内容
    public long insertLog(String keyNo, String logType, String content) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KEY_NO, keyNo);
        cv.put(COL_TIME, System.currentTimeMillis());
        cv.put(COL_TYPE, logType);
        cv.put(COL_CONTENT, content);
        SQLiteDatabase db = getWritableDatabase();
        return db.insert(TABLE_LOG, null, cv);
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
