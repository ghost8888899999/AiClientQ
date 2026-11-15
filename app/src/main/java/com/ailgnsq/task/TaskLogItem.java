package com.ailgnsq.task;

/**
 * 任务执行日志的一行（运行时模型）。
 *
 * <p>由 {@link TaskLogCallback#onLog} 逐条回调，主界面负责上屏并异步落库；
 * 日志只做展示与排查用，不参与任何业务判断。</p>
 */
public class TaskLogItem {
    /** 自增主键；内存对象尚未落库时为 0 */
    public long logId;
    /** 账号展示编号（任务开始时的快照）：账号后来改名，历史日志也不会跟着变 */
    public String keyNo;
    /** 日志产生时间（毫秒时间戳），列表按它倒序 */
    public long timeMs;
    /** 日志分类：ENTER 入场 / CLAIM 领取 */
    public String logType;
    /** 日志正文：一般包含 txHash 与失败原因，过长时界面自行截断 */
    public String content;
}
