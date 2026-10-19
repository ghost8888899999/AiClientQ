package com.lgns;

public class TaskAccountState {
    // 0 IDLE待执行，1 TX_SENT已发送交易
    // 2 SUCCESS成功(仅内存，禁止存磁盘)，3 FAIL失败(仅内存，禁止存磁盘)
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;

    // outcome 状态机
    public static final String OUTCOME_NONE = "";
    public static final String OUTCOME_CONFIRMED = "confirmed";
    public static final String OUTCOME_REVERTED = "reverted";
    public static final String OUTCOME_NONCE_CONSUMED = "nonce-consumed";

    public int status;
    public String txHash;
    public long timestamp;
    // 进程重启/换机时防止把 A 链的 pending 恢复到 B 链;0 表示升级前旧数据,跳过校验
    public long chainId;
    // 落 signed raw tx,崩溃恢复时可反解校验并原样重发
    public String rawTx;
    // 记录最终结局
    public String outcome;

    public TaskAccountState() {
        status = STATUS_IDLE;
        txHash = null;
        rawTx = null;
        outcome = OUTCOME_NONE;
        timestamp = System.currentTimeMillis();
        chainId = ArenaConfig.CHAIN_ID;
    }

    /**
     * 构造函数：拦截禁止把 SUCCESS / FAIL 存入持久化对象
     */
    public TaskAccountState(int status, String txHash) {
        this(status, txHash, null);
    }

    /**
     * 广播前先把 rawTx + 本地算出的 txHash 落 SP,进程崩溃也能识别
     */
    public TaskAccountState(int status, String txHash, String rawTx) {
        if (status == STATUS_SUCCESS || status == STATUS_FAIL) {
            throw new IllegalArgumentException("SUCCESS/FAIL仅允许内存使用，不能持久化到SP");
        }
        this.status = status;
        this.txHash = txHash;
        this.rawTx = rawTx;
        this.outcome = OUTCOME_NONE;
        this.timestamp = System.currentTimeMillis();
        this.chainId = ArenaConfig.CHAIN_ID;
    }

    /**
     * 仅生成内存临时对象（SUCCESS/FAIL，不会存入SP）
     */
    public static TaskAccountState makeMemOnly(int memStatus) {
        TaskAccountState s = new TaskAccountState();
        s.status = memStatus;
        return s;
    }

    public int getStatus() { return status; }
    public String getTxHash() { return txHash; }
    public String getRawTx() { return rawTx; }
    public String getOutcome() { return outcome; }
    public long getTimestamp() { return timestamp; }
    public long getChainId() { return chainId; }
}