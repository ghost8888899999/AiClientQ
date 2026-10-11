package com.lgns;

public class TaskAccountState {
    // 0 IDLE待执行，1 TX_SENT已发送交易
    // 2 SUCCESS成功(仅内存，禁止存磁盘)，3 FAIL失败(仅内存，禁止存磁盘)
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;

    public int status;
    public String txHash;
    public long timestamp;

    public TaskAccountState() {
        status = STATUS_IDLE;
        txHash = null;
        timestamp = System.currentTimeMillis();
    }

    /**
     * 构造函数：拦截禁止把 SUCCESS / FAIL 存入持久化对象
     */
    public TaskAccountState(int status, String txHash) {
        // 防御：SUCCESS、FAIL不允许构造用于磁盘保存的实例
        if (status == STATUS_SUCCESS || status == STATUS_FAIL) {
            throw new IllegalArgumentException("SUCCESS/FAIL仅允许内存使用，不能持久化到SP");
        }
        this.status = status;
        this.txHash = txHash;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 仅生成内存临时对象（SUCCESS/FAIL，不会存入SP）
     */
    public static TaskAccountState makeMemOnly(int memStatus) {
        TaskAccountState s = new TaskAccountState();
        s.status = memStatus;
        return s;
    }

    // =========新增getter，适配ArenaTask调用，原有public字段不删除，保证gson反序列化兼容=========
    public int getStatus() {
        return status;
    }

    public String getTxHash() {
        return txHash;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
