package com.ailgnsq.model;

/**
 * 每日状态一行：对应 {@code daily_status} 表，记录某账号某一天的完成计数与失败标记。
 *
 * <p>读写接口在 {@code AppLogDbHelper.upsertDailyStatus / queryDailyStatus}；
 * 擂台版本的主界面统计以链上 playerSummary 为准，这条路径目前不走，
 * 字段名（QD）沿用早期签到产品的口径，勿按擂台规则重新理解。</p>
 */
public class DailyStatusItem {
    /** 对应 key_entry.id；表关联靠它，而不是靠编号字符串 */
    public long keyId;
    /** 账号展示编号（快照值）：编号后来被改也不会回溯修正历史记录 */
    public String keyNo;
    /** 统计日期 yyyy-MM-dd，按本地时区 */
    public String date;
    /** 所属分组：查询时取 key_entry 的最新分组（LEFT JOIN 得到），不是写入时的旧值 */
    public String groupNo;  // 新增
    /** 累计完成次数（沿用早期签到口径的字段名） */
    public int totalQd;
    /** 当日完成次数 */
    public int todayQd;
    /** 当日入场次数 */
    public int todayEnter;
    /** 当日代币数：以 long 存储，精度由写入方决定，模型不做换算 */
    public long tokens;
    /** 当日是否出现过失败（界面据此标红并打失败徽标） */
    public boolean isFailed;
}
