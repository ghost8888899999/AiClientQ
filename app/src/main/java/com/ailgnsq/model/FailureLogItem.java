package com.ailgnsq.model;

/**
 * 失败日志一行：对应 {@code failure_log} 表，记录哪个账号在什么时候因为什么原因失败。
 *
 * <p>写入在 {@code AppLogDbHelper.insertFailure}（主界面收单账号失败回调时调用），
 * 查询方法 {@code queryFailureLog} 按时间倒序返回，供失败日志页展示。</p>
 */
public class FailureLogItem {
    /** 对应 key_entry.id；失败记录只记当时 id，账号被删后仍可留档 */
    public long keyId;
    /** 账号展示编号（写入时快照）：便于不带私钥的情况下认账号 */
    public String keyNo;
    /** 失败日期 yyyy-MM-dd，按本地时区 */
    public String date;
    /** 所属分组：查询时取 key_entry 的最新分组（LEFT JOIN），不是写入时的旧值 */
    public String groupNo;
    /** 失败类型（如 ENTER/CLAIM，由调用方自行约定），用于分类统计 */
    public String failType;
    /** 失败原因原文（已根据异常取可读文案），直接展示给用户 */
    public String failReason;
    /** 失败时间毫秒时间戳，也是列表倒序的依据 */
    public long timeMs;
}
