package com.ailgnsq.model;

/**
 * 主界面的账号清单行（运行时模型，不落库）。
 *
 * <p>每次加载时由 {@code KeyEntry} 重新拼出，每次读库都是全新建对象，
 * 所以这里改了字段不会自动写回数据库——持久化一律走 KeyStoreManager。</p>
 *
 * <p>调试模式下会额外插入地址池展开出的行：{@code keyId} 为负、{@code cipherText} 为空，
 * 只用于展示与统计，不参与解密和转账。</p>
 */
public class KeyListItem {
    /** key_entry.id：解密、开关、转账日志都靠它定位账号 */
    public long keyId;
    /** 在清单中的序号（从 1 开始），仅用于展示排序 */
    public int seqNo;
    /** 默认编号：id 补零到三位，用户没填自定义编号时作为展示名 */
    public String displayNo;
    /** 用户自定义编号；可为空 */
    public String customCode;
    /** 所属分组名；为空在界面上归入「未分组」 */
    public String groupNo;
    /** 私钥密文：只落加密结果，解密由 KeyStoreManager 用会话密码完成 */
    public String cipherText;
    /** 账号地址（0x 开头）；早期数据可能为空，补全后回写数据库 */
    public String address;
    /** 是否启用：批量任务只取启用的行，主号固定不参与 */
    public boolean enabled = true;
    /** 可用标记：加载时默认 true，界面可据此过滤不可用条目 */
    public boolean isUsable = true;
    /** 是否被勾选（部分场景只看勾选账号：如锦标赛统计） */
    public boolean isChecked;
}
