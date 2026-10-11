package com.lgns;

public interface TaskLogCallback {
    /**
     * @param keyNo 密钥编号 "001"；无密钥传空字符串""
     * @param logType 日志分类 "ENTER" / "CLAIM" / ""
     * @param msg 日志文本
     */
    void onLog(String keyNo, String logType, String msg);
}
