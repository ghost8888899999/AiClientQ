package com.lgns;

public class TaskAccountState {
    // 0 IDLE待执行，1 TX_SENT已发送交易，2 SUCCESS成功，3 FAIL失败
    public int status;
    public String txHash;
    public long timestamp;

    public TaskAccountState(){
        status = 0;
        txHash = null;
        timestamp = System.currentTimeMillis();
    }

    public TaskAccountState(int status, String txHash){
        this.status = status;
        this.txHash = txHash;
        this.timestamp = System.currentTimeMillis();
    }
}
