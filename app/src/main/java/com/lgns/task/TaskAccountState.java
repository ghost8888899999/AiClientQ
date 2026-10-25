package com.lgns.task;

import com.lgns.model.ArenaConfig;

/**
 * 单账号的交易状态快照，用于崩溃/重启后恢复在途交易。
 *
 * <p>两条路径分得很开，别混用：</p>
 * <ul>
 *   <li>持久化路径：只能 {@code IDLE} / {@code TX_SENT}，带 {@code rawTx} 与 {@code chainId}，
 *   重启后能反解交易、比对链 ID 并原样重发。</li>
 *   <li>内存路径：{@code SUCCESS} / {@code FAIL} 表示本次运行已经结束，
 *   带参构造会主动抛异常拦住写盘，避免把「已成功」当成待恢复任务重发。</li>
 * </ul>
 */
public class TaskAccountState {
    /** 待执行：没有在途交易，可以随时开新任务 */
    public static final int STATUS_IDLE = 0;
    /** 交易已广播但未确认：重启后需要查回执或重发 */
    public static final int STATUS_TX_SENT = 1;
    /** 成功：仅内存，禁止存磁盘（带参构造会校验） */
    public static final int STATUS_SUCCESS = 2;
    /** 失败：仅内存，禁止存磁盘（带参构造会校验） */
    public static final int STATUS_FAIL = 3;

    /** 未结束：交易还在途，无法判定结果 */
    public static final String OUTCOME_NONE = "";
    /** 回执成功：交易已上链且未回滚 */
    public static final String OUTCOME_CONFIRMED = "confirmed";
    /** 回执回滚：交易上链但执行失败，重发也没用 */
    public static final String OUTCOME_REVERTED = "reverted";
    /** nonce 已被其他交易占用：原交易永远不会上链，要重新组交易 */
    public static final String OUTCOME_NONCE_CONSUMED = "nonce-consumed";

    /** 当前状态，取值见 {@code STATUS_*} */
    public int status;
    /** 交易哈希；未广播时为 null */
    public String txHash;
    /** 状态写入时间（毫秒），用于判断 pending 是否已经老到该放弃 */
    public long timestamp;
    /** 记录所属链 ID：进程重启/换机时防止把 A 链的 pending 恢复到 B 链；0 表示升级前旧数据，跳过校验 */
    public long chainId;
    /** 已签名的原始交易，崩溃恢复时可反解校验并原样重发 */
    public String rawTx;
    /** 最终结局，取值见 {@code OUTCOME_*}；恢复流程靠它区分「可重发」与「不必重发」 */
    public String outcome;

    /** 默认构造：干净的空状态，用于新建任务 */
    public TaskAccountState() {
        status = STATUS_IDLE;
        txHash = null;
        rawTx = null;
        outcome = OUTCOME_NONE;
        timestamp = System.currentTimeMillis();
        chainId = ArenaConfig.CHAIN_ID;
    }

    /**
     * 仅带状态与 txHash 的构造：用于已有交易、但不需要保留原文的场景。
     */
    public TaskAccountState(int status, String txHash) {
        this(status, txHash, null);
    }

    /**
     * 广播前先把 rawTx + 本地算出的 txHash 落 SP，进程崩了也能识别。
     *
     * <p>SUCCESS / FAIL 是内存专用终态，从这里传进来直接抛异常：
     * 一旦写进 SP，下次启动就会把已完成的账号当成待恢复任务重发。</p>
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
     * 生成仅内存的临时状态（SUCCESS / FAIL）：不走带参构造，所以不触发写盘校验，
     * 也永远不会被序列化进 SP。
     */
    public static TaskAccountState makeMemOnly(int memStatus) {
        TaskAccountState s = new TaskAccountState();
        s.status = memStatus;
        return s;
    }

    /** 只读取快照用：这些 getter 全部不做拷贝，返回的字符串不可变、无需防御性复制 */
    public int getStatus() { return status; }

    /** 已广播交易的哈希；未广播时为 null */
    public String getTxHash() { return txHash; }

    /** 已签名的原始交易 hex，恢复时原样重发用 */
    public String getRawTx() { return rawTx; }

    /** 上次回查结果：{@code ""} 未回查 / confirmed / reverted / nonce-consumed */
    public String getOutcome() { return outcome; }

    /** 快照时间戳（毫秒），用来判定 pending 是否已过期 */
    public long getTimestamp() { return timestamp; }

    /** 快照所属链 ID，恢复前先与当前链对比，不一致则丢弃不重发 */
    public long getChainId() { return chainId; }
}