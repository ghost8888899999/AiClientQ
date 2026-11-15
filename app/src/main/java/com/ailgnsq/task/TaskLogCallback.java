package com.ailgnsq.task;

import com.ailgnsq.contract.ArenaContractApi;

/**
 * 批量任务的回传接口：任务线程向 UI 层汇报进度、结果与失败。
 *
 * <p>注意线程模型：这些回调都在任务线程上触发，实现方若要改 UI 必须自己切回主线程，
 * 不要在实现里直接操作 View。</p>
 */
public interface TaskLogCallback {
    /**
     * 单条过程日志（入场/领取过程中的阶段性信息与 txHash）。
     *
     * @param keyNo 账号展示编号，用于把日志挂到具体行上
     * @param type  日志分类，与 {@code TaskLogItem.logType} 同一套约定
     * @param msg   日志正文
     */
    void onLog(String keyNo, String type, String msg);

    /**
     * 批量任务顺带拉回来的链上战绩（key 为地址，value 为合约 playerSummary 的解析结果）。
     *
     * <p>不是每个任务都会触发：只有内部顺带查询了 summary 的流程才会回调，
     * 界面拿到后用于刷新冠军/场次那一列。</p>
     */
    default void onBatchResult(java.util.Map<String, ArenaContractApi.PlayerSummary> summaries) {}

    /**
     * 单个账号失败。比 {@link #onLog} 更结构化，界面据此落 {@code failure_log} 表并给该行标红。
     *
     * @param keyNo    账号展示编号
     * @param failType 失败环节（ENTER / CLAIM 等），由调用方自行约定
     * @param reason   失败原因原文，直接展示给用户
     */
    default void onKeyFailure(String keyNo, String failType, String reason) {}
}
