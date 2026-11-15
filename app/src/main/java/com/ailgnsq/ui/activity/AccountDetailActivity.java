package com.ailgnsq.ui.activity;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ailgnsq.R;
import com.ailgnsq.contract.BlockscoutApi;
import com.ailgnsq.contract.HelixContractApi;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.model.HelixConfig;
import com.ailgnsq.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 账号详情页（只读）：从主界面点某行进入，展示该地址的 HELIX / 烧币诊断读数。
 *
 * <p>这一页只剩只读的链上诊断——今日已烧币笔数（走浏览器计数）、{@code burnForPower} /
 * {@code participate} 的干跑结论（走 {@code eth_call}，不发消息不碰私钥）、功率与涡轮持仓、
 * LGNS 余额及对 PowerLedger 授权。口径与只读面板一致：查不到一律留占位、绝不写成 0；
 * 干跑报的是<b>此刻命中的哪一层</b>判定，单次采样不能当永久结论（区间与闸门可能被项目方改）。</p>
 *
 * <p>原来的擂台（入场 / 领取）明细已整节移除：擂台入场门票是 fLGNS，而本项目尚未开发
 * LGNS ⇄ fLGNS 兑换，账号拿不到 fLGNS，入场/领取链路根本跑不通，这些读数对当前业务无意义。
 * 所以本页不再有任何私钥解密与发交易，纯只读；调试地址池与真实账号在这页看到的数据也一致。</p>
 */
public class AccountDetailActivity extends BaseActivity {

    public static final String EXTRA_ADDRESS = "extra_address";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_GROUP = "extra_group";

    private String address;
    private String displayName;
    private String groupName;

    private TextView tvName, tvGroup, tvAddr;
    private ProgressBar pbLoading;
    private View btnRefresh;
    private TextView tvLog, tvLogToggle;
    private View logHeader;
    private ScrollView scrollLog;

    // HELIX / 烧币诊断行的数值控件（代码里回填，标签固定不变）
    private TextView valHelixBurnToday, valHelixBurnProbe, valHelixPartProbe, valHelixPower,
            valHelixClaimable, valHelixPending, valHelixLgnsBal, valHelixLgnsAllow;

    /** 页面是否已销毁：后台线程收尾时不再碰控件 */
    private volatile boolean destroyed = false;
    /** 刷新是否在途：连点只认第一次，避免重复建 RPC 连接 */
    private volatile boolean loading = false;
    /** 在途加载作废序号：快速刷新/返回时旧线程不再回填 */
    private final AtomicInteger loadSeq = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_detail);

        address = getIntent().getStringExtra(EXTRA_ADDRESS);
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        groupName = getIntent().getStringExtra(EXTRA_GROUP);

        tvName = findViewById(R.id.tv_d_name);
        tvGroup = findViewById(R.id.tv_d_group);
        tvAddr = findViewById(R.id.tv_d_addr);
        pbLoading = findViewById(R.id.pb_detail_loading);
        btnRefresh = findViewById(R.id.btn_d_refresh);
        tvLog = findViewById(R.id.tv_detail_log);
        scrollLog = findViewById(R.id.scroll_detail_log);
        logHeader = findViewById(R.id.ll_detail_log_header);
        tvLogToggle = findViewById(R.id.tv_detail_log_toggle);

        // 账号头数据回填
        tvName.setText(displayName != null && !displayName.isEmpty()
                ? displayName : getString(R.string.common_placeholder));
        tvGroup.setText(groupName != null && !groupName.isEmpty()
                ? groupName : getString(R.string.common_ungrouped));
        tvAddr.setText(shortAddress(address));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        buildRows();

        btnRefresh.setOnClickListener(v -> handleRefreshClick());

        // 日志折叠：点标题行切换；默认收起由布局 visibility=gone 决定
        logHeader.setOnClickListener(v -> {
            boolean visible = scrollLog.getVisibility() == View.VISIBLE;
            scrollLog.setVisibility(visible ? View.GONE : View.VISIBLE);
            tvLogToggle.setText(visible
                    ? getString(R.string.arrow_collapsed)
                    : getString(R.string.arrow_expanded));
        });

        if (address == null || address.isEmpty()) {
            showError(getString(R.string.detail_key_missing));
            return;
        }
        loadData();
    }

    // region 骨架搭建

    /**
     * 建 HELIX / 烧币诊断一节的骨架，数值一律先摆 {@code --}。
     *
     * <p>行都先摆齐、数据回来再回填：链上取数要几秒到十几秒，先空着的话用户分不清
     * 「这一项待查」和「这一项没有」；回填结束时哪里还是 {@code --} 就是哪里没查到。</p>
     */
    private void buildRows() {
        LinearLayout helix = findViewById(R.id.container_helix);
        valHelixBurnToday = addRow(helix, R.string.detail_helix_burn_today, false);
        valHelixBurnProbe = addRow(helix, R.string.detail_helix_burn_probe, true);
        valHelixPartProbe = addRow(helix, R.string.detail_helix_part_probe, true);
        valHelixPower = addRow(helix, R.string.detail_helix_power, true);
        valHelixClaimable = addRow(helix, R.string.detail_helix_claimable, true);
        valHelixPending = addRow(helix, R.string.detail_helix_pending, true);
        valHelixLgnsBal = addRow(helix, R.string.detail_helix_lgns_bal, true);
        valHelixLgnsAllow = addRow(helix, R.string.detail_helix_lgns_allow, true);

        // 初始全部占位，避免链上回来前把空白当成 0
        TextView[] all = {valHelixBurnToday, valHelixBurnProbe, valHelixPartProbe, valHelixPower,
                valHelixClaimable, valHelixPending, valHelixLgnsBal, valHelixLgnsAllow};
        for (TextView tv : all) tv.setText(R.string.common_placeholder);
    }

    /** 行间细分隔线：取物理像素级的最细，避免 1dp 在低密度屏上显得过重 */
    private void addDivider(LinearLayout container) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
    }

    /**
     * 追加一行「标签—数值」（表格行），返回右侧数值控件。
     *
     * @param dividerAbove true 时在该行上方补一条分隔线；首行不加，避免与卡片顶部悬一条多余的线
     */
    private TextView addRow(LinearLayout container, int labelRes, boolean dividerAbove) {
        if (dividerAbove) addDivider(container);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(labelRes);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(this);
        tvValue.setTextSize(14);
        tvValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setGravity(Gravity.END);
        // 等宽数字：多行金额右对齐时位数不跳，扫读更稳
        tvValue.setFontFeatureSettings("tnum");
        row.addView(tvValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(row);
        return tvValue;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // endregion

    // region 链上取数

    /**
     * 后台拉链上诊断读数并回填；逐项独立失败，只有连节点都 dial 不出来才整页报错。
     *
     * <p>干跑是 {@code eth_call}、今日烧币计数走浏览器，都不发消息、不碰私钥；
     * 复用同一 {@code web3j} 连接，回不到就各自 catch 住留占位，不伪造 0。</p>
     */
    private void loadData() {
        final int seq = loadSeq.incrementAndGet();
        loading = true;
        runOnUiThread(() -> {
            if (destroyed) return;
            pbLoading.setVisibility(View.VISIBLE);
        });
        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();

                final BigInteger burnAmt = HelixConfig.BURN_AMOUNT_RAW;
                BlockscoutApi.BurnDayStat bt = null;
                HelixContractApi.BurnProbe bp = null, pp = null;
                HelixContractApi.Position po = null;
                HelixContractApi.TokenView lv = null;
                // 今日已烧币笔数走浏览器（不占 RPC）：回答 daily maximum reached 到底是本号烧满还是全局满
                try { bt = BlockscoutApi.countBurnForPowerToday(address); } catch (Exception ignore) {}
                // 两条入口传同一个额度各干跑一次，才分得出「通道关了」和「这个号没资格」
                try { bp = HelixContractApi.probeBurnForPower(web3j, address, burnAmt); } catch (Exception ignore) {}
                try { pp = HelixContractApi.probeParticipate(web3j, address, burnAmt); } catch (Exception ignore) {}
                try {
                    List<HelixContractApi.Position> ps = HelixContractApi.callPositions(
                            web3j, java.util.Collections.singletonList(address));
                    if (ps != null && !ps.isEmpty()) po = ps.get(0);
                } catch (Exception ignore) {}
                try {
                    lv = HelixContractApi.callTokenView(web3j, HelixConfig.LGNS_ADDR, address,
                            HelixConfig.POWER_LEDGER_ADDR);
                } catch (Exception ignore) {}

                final BlockscoutApi.BurnDayStat burnToday = bt;
                final HelixContractApi.BurnProbe burnProbe = bp;
                final HelixContractApi.BurnProbe partProbe = pp;
                final HelixContractApi.Position position = po;
                final HelixContractApi.TokenView lgnsView = lv;

                runOnUiThread(() -> {
                    if (seq != loadSeq.get()) return;
                    loading = false;
                    if (destroyed) return;
                    pbLoading.setVisibility(View.GONE);
                    fillHelix(burnToday, burnProbe, partProbe, position, lgnsView);
                });
            } catch (Exception e) {
                // 只有连不上节点 / 链不对才走到这里
                Log.e("AccountDetail", "load chain error", e);
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    if (seq != loadSeq.get()) return;
                    loading = false;
                    if (destroyed) return;
                    pbLoading.setVisibility(View.GONE);
                    showError(getString(R.string.detail_chain_failed_fmt, msg));
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    /**
     * 回填 HELIX / 烧币诊断节。
     *
     * <p>这批读数回答的是「这个号今天到底烧没烧满」：今日已烧币笔数（浏览器计数）+ 此刻
     * {@code burnForPower}/{@code participate} 的干跑结论（eth_call、不发消息）+ 功率与涡轮持仓。
     * 查不到一律留占位、绝不写成 0；干跑报的是此刻命中的哪一层判定，单次采样不能当永久结论。</p>
     */
    private void fillHelix(BlockscoutApi.BurnDayStat burnToday,
                           HelixContractApi.BurnProbe burnProbe,
                           HelixContractApi.BurnProbe partProbe,
                           HelixContractApi.Position pos,
                           HelixContractApi.TokenView lgns) {
        // 今日已烧币：查不到留占位（「查不到」不等于「没烧过」）；>0 标琥珀、=0 标绿
        if (burnToday == null || burnToday.queryFailed) {
            valHelixBurnToday.setText(R.string.common_placeholder);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("burnForPower ").append(burnToday.burnCount).append(" 笔");
            if (burnToday.participateCount > 0)
                sb.append(" · participate ").append(burnToday.participateCount).append(" 笔");
            if (burnToday.otherCount > 0)
                sb.append(" · 未解码 ").append(burnToday.otherCount).append(" 笔");
            valHelixBurnToday.setText(sb.toString());
            valHelixBurnToday.setTextColor(getColor(
                    burnToday.burnCount > 0 ? R.color.warn_text : R.color.success_deep));
        }

        applyProbe(valHelixBurnProbe, burnProbe);
        applyProbe(valHelixPartProbe, partProbe);

        // 功率只摆链上原始累计值、不做除法：定点位数没实测对过，除出来的数会把「口径未知」画成好看的数
        valHelixPower.setText(pos != null && pos.power != null
                ? pos.power.toString() : getString(R.string.common_placeholder));
        valHelixClaimable.setText(pos != null && pos.claimable != null
                ? num(pos.claimable, HelixConfig.DEC_AILGNS, 2) : getString(R.string.common_placeholder));
        valHelixPending.setText(pos != null && pos.claimed != null
                ? num(pos.claimed, HelixConfig.DEC_AILGNS, 2) : getString(R.string.common_placeholder));

        // LGNS 精度取合约自报的 decimals，查不到才回落常量；无限授权（MAX_UINT256）单独标注
        if (lgns != null) {
            int dec = lgns.decimals == null ? HelixConfig.DEC_LGNS : lgns.decimals;
            if (lgns.balance != null) valHelixLgnsBal.setText(num(lgns.balance, dec, 4));
            if (lgns.allowance != null) {
                boolean inf = lgns.allowance.bitLength() >= 255;
                valHelixLgnsAllow.setText(inf ? "无限授权" : num(lgns.allowance, dec, 4));
                valHelixLgnsAllow.setTextColor(getColor(inf ? R.color.warn_text : R.color.text_primary));
            }
        }
    }

    /**
     * 干跑结论行：
     * <ul>
     *   <li>通过：短文案；</li>
     *   <li>被拒且是未开源的自定义错误（整串 {@code 0x…} hex）：只留 selector 前缀、折叠成一行，
     *   不把两百个字符铺出来撑破布局；</li>
     *   <li>被拒且有可读 revert 文案：命中已知闸门词时翻成人话（直接回答“抢功率为何失败”），其余原样、过长截断。</li>
     * </ul>
     */
    private void applyProbe(TextView tv, HelixContractApi.BurnProbe probe) {
        if (probe == null) {
            tv.setText(R.string.common_placeholder);
            return;
        }
        if (probe.wouldSucceed) {
            tv.setText(R.string.detail_helix_probe_ok);
            tv.setTextColor(getColor(R.color.success_deep));
            return;
        }
        tv.setTextColor(getColor(R.color.danger));
        tv.setText(describeRevert(probe.reason == null ? "" : probe.reason.trim()));
    }

    /** 把 revert 原文转成一行可读结论：未开源自定义错误折叠，已知闸门词翻人话，其余截断 */
    private static String describeRevert(String reason) {
        if (reason.isEmpty()) return "未通过";
        String low = reason.toLowerCase(Locale.US);
        if (low.contains("daily maximum"))
            return "未通过：今日额度已满（多为全局池被占满，非本号烧满）";
        if (low.contains("not open today"))
            return "未通过：今日窗口未开（UTC+8 11:59 / 23:59 各 90 秒）";
        if (low.contains("below minimum"))
            return "未通过：额度低于最小值";
        if (isRawHexData(reason)) {
            String sel = reason.length() >= 10 ? reason.substring(0, 10) : reason;
            return "未通过：自定义错误 " + sel + "…（合约未开源，无法解读）";
        }
        return reason.length() > 64 ? reason.substring(0, 64) + "…" : reason;
    }

    /** 是否整串都是 0x 开头的十六进制（即未解析的 revert data blob，而不是 Error(string) 文案） */
    private static boolean isRawHexData(String s) {
        if (s.length() < 10 || !s.startsWith("0x")) return false;
        for (int i = 2; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** 定点读数转人写小数（保留固定位数，四舍五入）；null 兜底占位，与只读面板同源口径 */
    private static String num(BigInteger raw, int decimals, int keep) {
        if (raw == null) return "--";
        return new BigDecimal(raw)
                .divide(new BigDecimal(BigInteger.TEN.pow(decimals)), keep, RoundingMode.HALF_UP)
                .toPlainString();
    }

    // endregion

    // region 刷新与日志

    /**
     * 刷新：重拉链上诊断读数。刷新不是链上交易，日志类型传 null，日志管理页显示为「日志」。
     *
     * <p>在途去抖：每次点击都会新建 RPC 连接，连点 N 次白耗流量与电量（结果本来就会被 loadSeq 丢弃）。</p>
     */
    private void handleRefreshClick() {
        if (loading) return;
        logOp(null, getString(R.string.detail_log_click_refresh));
        loadData();
    }

    /** 把一条操作/失败写进本页日志（带【详情】前缀）：先上屏，再在子线程落库 */
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final long timeMs = System.currentTimeMillis();
        appendLogToUi(safeKeyNo, msg, timeMs);
        final String dbMsg = getString(R.string.detail_log_prefix) + msg;
        // 落库用 Application Context：页面销毁后线程仍可能写日志，不能拿已销毁的 Activity
        new Thread(() -> AppLogDbHelper.getInstance(getApplicationContext())
                .insertLog(safeKeyNo, safeType, dbMsg, timeMs)).start();
    }

    private void appendLogToUi(final String safeKeyNo, final String msg, final long timeMs) {
        runOnUiThread(() -> {
            if (destroyed) return;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) sb.append("[").append(safeKeyNo).append("]");
            sb.append(getString(R.string.detail_log_prefix)).append(msg).append("\n");
            tvLog.append(sb.toString());
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** 按钮操作反馈：本页不弹 Toast，统一写成一条日志——先上屏，再落库（带【详情】前缀） */
    private void logOp(final String type, final String msg) {
        String keyNo = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : null;
        onLog(keyNo, type, msg);
    }

    /** 原错误提示改走底部日志列表：仅上屏（带时间戳），不落库，避免把瞬时失败当作业务日志入库 */
    private void showError(final String msg) {
        appendLogToUi(null, "⚠ " + msg, System.currentTimeMillis());
    }

    /** 地址简短展示：0x + 前 4 位 … 后 4 位，与主界面日志截断口径一致 */
    private static String shortAddress(String a) {
        if (a == null || a.isEmpty()) return "--";
        if (a.length() <= 12) return a;
        return a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }

    // endregion

    /** 详情页不自行处理返回主界面刷新——主界面靠 {@code refreshStatsFromDetailOnResume} 重拉；这里只回收加载线程序号 */
    @Override
    protected void onDestroy() {
        // 先置销毁位：后台线程收尾时不再碰控件
        destroyed = true;
        super.onDestroy();
        loadSeq.incrementAndGet();
    }
}
