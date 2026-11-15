package com.ailgnsq.ui.activity;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ailgnsq.R;
import com.ailgnsq.contract.HelixContractApi;
import com.ailgnsq.model.HelixConfig;
import com.ailgnsq.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HELIX 只读面板：把价格源、涡轮参数、账号持仓、代币授权与烧币额度诊断一次摊开给人看。
 *
 * <p>这一页的存在理由只有一条：<b>先让数字对上，再让代码动钱</b>。步骤 1 只搬只读查询，
 * 不发任何交易、不碰私钥，就是为了拿官方 App / 浏览器上的读数来核对这里解出来的 ABI
 * 到底对不对——尤其是 {@code lastTwapPrice} 的口径、{@code quote} 那三个返回值的含义，
 * 以及锁仓/可领这几个数是不是用户心里的那个数。</p>
 *
 * <p>三条硬约定，都是被 {@code E:\py\AIlgns} 的坑逼出来的：</p>
 * <ul>
 *   <li><b>查不到就显示占位 {@code --}，绝不显示 0</b>。原脚本把 revert 当成 0，于是
 *   「这个号没钱可领」和「这个查询压根没通」长得一模一样；</li>
 *   <li><b>每段独立失败</b>：涡轮参数查挂了不影响价格源，页顶只给一条「哪几段没读到」的汇总；</li>
 *   <li><b>功率入口只做诊断</b>：ai.py 那条 {@code burnForPower} 实测额度区间为空集，而官方 App
 *   用的是同一个合约上的 {@code participate}，所以这一节把两条入口拿<b>同一个额度</b>各干跑一次，
 *   只报「现在哪条能用」，不提供任何发送入口，也不去抢窗口。</li>
 * </ul>
 */
public class HelixActivity extends BaseActivity {

    /** 要查持仓的账号地址（主界面选中且启用的那批），与 {@link #EXTRA_LABELS} 按下标对应 */
    public static final String EXTRA_ADDRS = "extra_addrs";
    /** 账号显示名（自定义编号，缺失时由调用方回落到序号） */
    public static final String EXTRA_LABELS = "extra_labels";

    private static final String TAG = "HelixActivity";

    /** {@code burnForPower} 与 {@code participate} 探针共用的零地址：它没有余额、没有授权，只能暴露「纯额度」这一层判定 */
    private static final String ZERO_ADDR = "0x0000000000000000000000000000000000000000";

    private ProgressBar pbLoading;
    private TextView tvError;
    private View btnRefresh;
    /** 页面根容器：每个分区是一张白卡挂在这里 */
    private LinearLayout container;

    // region 各行数值控件（先建骨架、初始一律占位）

    // 链与节点
    private TextView valNode, valChainId, valBlock, valBlockTime, valGasPrice, valAbiCheck;
    // 价格源
    private TextView valTwap, valTwapTime, valTwapAge, valSpot, valDeviation,
            valQuoteProbe, valQuoteOut, valQuoteFee, valQuoteTotal, noteOracleStale;
    // 涡轮参数
    private TextView valFeeBps, valBurnBps, valLockSeconds, valMaxFeeBps, valLockCount, valTotalClaimed;
    // 账号持仓：条数随选中账号变化，行都加在这个内层容器里
    private LinearLayout containerPos;
    // 代币
    private TextView valWatch, valDaiBal, valDaiAllow, valLgnsBal, valLgnsAllow, valAiLgnsBal, noteInfAllow;
    // 金库
    private TextView valVaultReleased;
    // 功率入口对比诊断：两条入口各一对「发起方 + 干跑结果」，各自一个状态胶囊
    private TextView burnChip, valBurnAmount, valBurnFromZero, valBurnRetZero, valBurnFromWatch, valBurnRetWatch;
    private TextView partChip, valPartFromZero, valPartRetZero, valPartFromWatch, valPartRetWatch, notePartCaveat;
    // 合约地址表
    private LinearLayout containerAddr;

    // endregion

    /** 选中且启用的账号地址；为空时持仓与代币两节只给提示，不影响其他数据 */
    private List<String> addrs = new ArrayList<>();
    /** 账号显示名，与 addrs 同下标 */
    private List<String> labels = new ArrayList<>();

    /** 页面是否已销毁：后台线程收尾时不再碰控件 */
    private volatile boolean destroyed = false;
    /** 在途加载序号：连点刷新时，旧线程的结果不再回填 */
    private final AtomicInteger loadSeq = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_helix_overview);

        pbLoading = findViewById(R.id.pb_helix_loading);
        tvError = findViewById(R.id.tv_helix_error);
        btnRefresh = findViewById(R.id.btn_helix_refresh);
        container = findViewById(R.id.container_helix);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> loadData());

        buildSections();
        loadData();
    }

    // region 骨架搭建

    /**
     * 建出全部分区与行，数值一律先摆 {@code --}。
     *
     * <p>为什么不在数据回来后才建行：链上取数要几秒到十几秒，先空着的话用户分不清
     * 「这一项待查」和「这一项没有」；把行摆齐之后，回填结束时哪里还是 {@code --} 就是哪里没查到。</p>
     */
    private void buildSections() {
        // ── 链与节点
        LinearLayout chain = addSection(R.string.helix_sec_chain, null);
        valNode = addRow(chain, R.string.helix_lbl_node, false);
        valChainId = addRow(chain, R.string.helix_lbl_chain_id, true);
        valBlock = addRow(chain, R.string.helix_lbl_block, true);
        valBlockTime = addRow(chain, R.string.helix_lbl_block_time, true);
        valGasPrice = addRow(chain, R.string.helix_lbl_gas_price, true);
        valAbiCheck = addRow(chain, R.string.helix_lbl_abi_check, true);
        valAbiCheck.setTextSize(11f);

        // ── 价格源
        LinearLayout price = addSection(R.string.helix_sec_price, getString(R.string.helix_note_price_unit));
        valTwap = addRow(price, R.string.helix_lbl_twap, false);
        valTwapTime = addRow(price, R.string.helix_lbl_twap_time, true);
        valTwapAge = addRow(price, R.string.helix_lbl_twap_age, true);
        valSpot = addRow(price, R.string.helix_lbl_spot, true);
        valDeviation = addRow(price, R.string.helix_lbl_deviation, true);
        valQuoteProbe = addRow(price, R.string.helix_lbl_quote_probe, true);
        valQuoteOut = addRow(price, R.string.helix_lbl_quote_out, true);
        valQuoteFee = addRow(price, R.string.helix_lbl_quote_fee, true);
        valQuoteTotal = addRow(price, R.string.helix_lbl_quote_total, true);
        noteOracleStale = addNote(price, R.string.helix_note_oracle_stale);

        // ── 涡轮参数
        LinearLayout param = addSection(R.string.helix_sec_turbine_params, null);
        valFeeBps = addRow(param, R.string.helix_lbl_fee_bps, false);
        valBurnBps = addRow(param, R.string.helix_lbl_burn_bps, true);
        valLockSeconds = addRow(param, R.string.helix_lbl_lock_seconds, true);
        valMaxFeeBps = addRow(param, R.string.helix_lbl_max_fee_bps, true);
        valLockCount = addRow(param, R.string.helix_lbl_lock_count, true);
        valTotalClaimed = addRow(param, R.string.helix_lbl_total_claimed, true);

        // ── 账号持仓（动态行）
        LinearLayout posCard = addSection(R.string.helix_sec_position, null);
        containerPos = addSubContainer(posCard);

        // ── 代币余额与授权
        LinearLayout token = addSection(R.string.helix_sec_tokens, null);
        valWatch = addRow(token, R.string.helix_lbl_watch_account, false);
        valDaiBal = addRow(token, R.string.helix_lbl_dai_balance, true);
        valDaiAllow = addRow(token, R.string.helix_lbl_dai_allow, true);
        valLgnsBal = addRow(token, R.string.helix_lbl_lgns_balance, true);
        valLgnsAllow = addRow(token, R.string.helix_lbl_lgns_allow, true);
        valAiLgnsBal = addRow(token, R.string.helix_lbl_ailgns_balance, true);
        noteInfAllow = addNote(token, R.string.helix_note_inf_allow);

        // ── 金库
        LinearLayout vault = addSection(R.string.helix_sec_vault, null);
        valVaultReleased = addRow(vault, R.string.helix_lbl_total_released, false);

        // ── 功率入口对比诊断：先把 burnForPower 摆完，再接 participate，两段同一张卡
        LinearLayout burn = addSection(R.string.helix_sec_burn, getString(R.string.helix_burn_note));
        valBurnAmount = addRow(burn, R.string.helix_burn_probe_amount, false);
        valBurnFromZero = addRow(burn, R.string.helix_burn_probe_from, true);
        valBurnRetZero = addRow(burn, R.string.helix_burn_probe_result, true);
        valBurnFromWatch = addRow(burn, R.string.helix_burn_probe_from, true);
        valBurnRetWatch = addRow(burn, R.string.helix_burn_probe_result, true);
        burnChip = addChip(burn);

        valPartFromZero = addRow(burn, R.string.helix_part_probe_from, true);
        valPartRetZero = addRow(burn, R.string.helix_part_probe_result, true);
        valPartFromWatch = addRow(burn, R.string.helix_part_probe_from, true);
        valPartRetWatch = addRow(burn, R.string.helix_part_probe_result, true);
        partChip = addChip(burn);
        // participate 的入参口径没逐档实测过，这句提醒必须跟它的读数同时出现，
        // 不然两个「通 / 不通」摆在一起很容易被读成「participate 收 500 LGNS」
        notePartCaveat = addNote(burn, R.string.helix_part_note);

        // ── 合约地址表
        LinearLayout addrCard = addSection(R.string.helix_sec_addrs, getString(R.string.helix_readonly_hint));
        containerAddr = addSubContainer(addrCard);
        for (String[] row : CONTRACT_TABLE) {
            addAddrRow(containerAddr, row[0], row[1]);
        }
    }

    /**
     * 页面上要摊开的合约地址。
     *
     * <p>全地址原样显示、不缩写：这一节是拿来和浏览器逐个字符对的，
     * 缩成 {@code 0xe884…9BaD} 就没法核对首尾了。</p>
     */
    private static final String[][] CONTRACT_TABLE = {
            {"Turbine", HelixConfig.TURBINE_ADDR},
            {"PowerLedger", HelixConfig.POWER_LEDGER_ADDR},
            {"CommunityVault", HelixConfig.COMMUNITY_VAULT_ADDR},
            {"TwapOracle", HelixConfig.ORACLE_ADDR},
            {"Pair 主池", HelixConfig.PAIR_MAIN_ADDR},
            {"AiLGNS", HelixConfig.AILGNS_ADDR},
            {"LGNS", HelixConfig.LGNS_ADDR},
            {"DAI(Gas)", HelixConfig.DAI_ADDR},
    };

    /** 新建一张白卡分区，返回往里加行的容器 */
    private LinearLayout addSection(int titleRes, String note) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_white);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        container.addView(card, lp);

        TextView tv = new TextView(this);
        tv.setText(titleRes);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.primary));
        tv.setLetterSpacing(0.04f);
        card.addView(tv);

        if (note != null && !note.isEmpty()) {
            TextView tvNote = new TextView(this);
            tvNote.setText(note);
            tvNote.setTextSize(11);
            tvNote.setTextColor(getColor(R.color.text_hint));
            tvNote.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams lpNote = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpNote.topMargin = dp(4);
            card.addView(tvNote, lpNote);
        }
        return card;
    }

    /**
     * 在分区里再套一层容器，专门放「条数不固定」的行。
     *
     * <p>必须有这层：回填时要 {@code removeAllViews()} 清掉上一轮的动态行，
     * 直接清分区容器会把分区标题一起删掉，第二轮刷新后标题就没了。</p>
     */
    private LinearLayout addSubContainer(LinearLayout card) {
        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.VERTICAL);
        card.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return sub;
    }

    /** 分区内的琥珀色提醒条（默认隐藏），用于「口径可疑」「已是无限授权」这类要就地说明的话 */
    private TextView addNote(LinearLayout parent, int textRes) {
        TextView tv = new TextView(this);
        tv.setText(textRes);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.warn_text));
        tv.setBackgroundResource(R.drawable.bg_card_soft);
        tv.setPadding(dp(8), dp(6), dp(8), dp(6));
        tv.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(tv, lp);
        tv.setVisibility(View.GONE);
        return tv;
    }

    /**
     * 一行「标签—数值」，返回右侧数值控件。
     *
     * @param dividerAbove 上方是否补分隔线；分区首行不加，否则标题与首行之间会悬着一条多余的线
     */
    private TextView addRow(LinearLayout parent, int labelRes, boolean dividerAbove) {
        if (dividerAbove) {
            View d = new View(this);
            d.setBackgroundColor(getColor(R.color.divider));
            parent.addView(d, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(labelRes);
        tvLabel.setTextSize(12);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f));

        TextView tvValue = new TextView(this);
        tvValue.setTextSize(13);
        tvValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setGravity(Gravity.END);
        // 等宽数字：十几行金额并排刷新时位数不跳
        tvValue.setFontFeatureSettings("tnum");
        row.addView(tvValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row);
        tvValue.setText(R.string.common_placeholder);
        return tvValue;
    }

    /** 一行「合约名—全地址」，值可选中方便长按复制 */
    private void addAddrRow(LinearLayout parent, String name, String addr) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(11);
        tvName.setTextColor(getColor(R.color.text_secondary));
        row.addView(tvName, new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvAddr = new TextView(this);
        tvAddr.setText(addr);
        tvAddr.setTextSize(11);
        tvAddr.setTextColor(getColor(R.color.text_primary));
        tvAddr.setTextIsSelectable(true);
        row.addView(tvAddr, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    /** 状态胶囊：烧币可用性这种二值结论，文字与底色一起换，不只靠颜色（色弱用户也能读） */
    private TextView addChip(LinearLayout parent) {
        TextView tv = new TextView(this);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.white));
        tv.setBackgroundResource(R.drawable.bg_tag_gray);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(tv, lp);
        return tv;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // endregion

    // region 取数

    /**
     * 一轮取数的容器：每一项都可能单独失败，所以字段全部可以为 null。
     *
     * <p>{@code null} 与「值为 0」在回填时走两条路——{@code null} 显示占位，0 显示 0。
     * 这条区分是这一整页的核心，不能省。</p>
     */
    private static final class Snapshot {
        HelixContractApi.Baseline baseline;
        HelixContractApi.OracleView oracle;
        BigInteger spot;
        HelixContractApi.Quote quote;
        HelixContractApi.TurbineParams params;
        List<HelixContractApi.Position> positions;
        HelixContractApi.TokenView dai;
        HelixContractApi.TokenView lgns;
        HelixContractApi.TokenView aiLgns;
        BigInteger vaultReleased;
        HelixContractApi.BurnProbe burnZero;
        HelixContractApi.BurnProbe burnWatch;
        HelixContractApi.BurnProbe participateZero;
        HelixContractApi.BurnProbe participateWatch;
        String abiCheck;
        final List<String> failedParts = new ArrayList<>();
    }

    /**
     * 后台拉链上数据。
     *
     * <p>每段各自 try/catch：只读面板里一段查挂，就该只有那一节空着，而不是整页变成错误屏。
     * 只有连节点都 dial 不出来时才整页报错。</p>
     *
     * <p>{@code Web3AndroidUtil.dialAny()} 内部带一道「擂台合约字节码就绪」的校验：HELIX 与擂台
     * 在同一条链上，不存在只部署了其中一边的节点，所以直接复用它换到的连接，不再另写一套探测逻辑。</p>
     */
    private void loadData() {
        final int seq = loadSeq.incrementAndGet();
        readIntentArgs();

        pbLoading.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        btnRefresh.setEnabled(false);

        new Thread(() -> {
            final Snapshot snap = new Snapshot();
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();
                // 自检是纯本地计算，放在最前面：签名要是和实测表不符，后面查得再准也没意义
                snap.abiCheck = HelixContractApi.abiSelfCheck();

                try { snap.baseline = HelixContractApi.callBaseline(web3j); }
                catch (Exception e) { fail(snap, "链基线", e); }

                try { snap.oracle = HelixContractApi.callOracle(web3j); }
                catch (Exception e) { fail(snap, "TWAP 价格", e); }

                try { snap.spot = HelixContractApi.callMainPairSpotPrice(web3j); }
                catch (Exception e) { fail(snap, "主池瞬时价", e); }

                try { snap.quote = HelixContractApi.callQuote(web3j, HelixConfig.QUOTE_PROBE_AMOUNT_RAW); }
                catch (Exception e) { fail(snap, "涡轮报价", e); }

                try { snap.params = HelixContractApi.callTurbineParams(web3j); }
                catch (Exception e) { fail(snap, "涡轮参数", e); }

                String watch = addrs.isEmpty() ? null : addrs.get(0);
                if (!addrs.isEmpty()) {
                    try { snap.positions = HelixContractApi.callPositions(web3j, addrs); }
                    catch (Exception e) { fail(snap, "账号持仓", e); }

                    // 代币只查第一个号（观察地址）：这两个数是用来核对「这个号有没有钱」的，
                    // 每个号都查一遍会在只读页上多打出十几笔请求，价值不大
                    try { snap.dai = HelixContractApi.callTokenView(web3j, HelixConfig.DAI_ADDR, watch, HelixConfig.TURBINE_ADDR); }
                    catch (Exception e) { fail(snap, "DAI", e); }
                    try { snap.lgns = HelixContractApi.callTokenView(web3j, HelixConfig.LGNS_ADDR, watch, HelixConfig.POWER_LEDGER_ADDR); }
                    catch (Exception e) { fail(snap, "LGNS", e); }
                    try { snap.aiLgns = HelixContractApi.callTokenView(web3j, HelixConfig.AILGNS_ADDR, watch, HelixConfig.TURBINE_ADDR); }
                    catch (Exception e) { fail(snap, "AiLGNS", e); }
                }

                try { snap.vaultReleased = HelixContractApi.callVaultTotalReleased(web3j); }
                catch (Exception e) { fail(snap, "金库", e); }

                // 两个发起方各干跑一次：零地址与真实地址给出同一条文案，才能说明卡住的是额度而不是余额
                try { snap.burnZero = HelixContractApi.probeBurnForPower(web3j, null, HelixConfig.BURN_AMOUNT_RAW); }
                catch (Exception e) { fail(snap, "烧币额度探针", e); }
                try { snap.burnWatch = HelixContractApi.probeBurnForPower(web3j, watch, HelixConfig.BURN_AMOUNT_RAW); }
                catch (Exception e) { fail(snap, "烧币额度探针", e); }
                // 与上面两条传完全相同的额度：并排对跑才分得出「这条通道关了」和「这个号没资格」
                try { snap.participateZero = HelixContractApi.probeParticipate(web3j, null, HelixConfig.BURN_AMOUNT_RAW); }
                catch (Exception e) { fail(snap, "participate 探针", e); }
                try { snap.participateWatch = HelixContractApi.probeParticipate(web3j, watch, HelixConfig.BURN_AMOUNT_RAW); }
                catch (Exception e) { fail(snap, "participate 探针", e); }

                final String dialUrl = Web3AndroidUtil.currentRpcUrl();
                runOnUiThread(() -> {
                    if (destroyed || seq != loadSeq.get()) return;
                    pbLoading.setVisibility(View.GONE);
                    btnRefresh.setEnabled(true);
                    fill(snap, dialUrl);
                });
            } catch (Exception e) {
                // 只有连不上节点 / 链不对才走到这里
                Log.e(TAG, "dial chain failed", e);
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    if (destroyed || seq != loadSeq.get()) return;
                    pbLoading.setVisibility(View.GONE);
                    btnRefresh.setEnabled(true);
                    tvError.setText(getString(R.string.helix_chain_failed_fmt, msg));
                    tvError.setVisibility(View.VISIBLE);
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    /** 某一段没读到：记名字 + 打 logcat，不落库（只读页面刷新一次就能写满日志表） */
    private void fail(Snapshot snap, String part, Exception e) {
        snap.failedParts.add(part);
        Log.w(TAG, "helix part [" + part + "] failed: " + Web3AndroidUtil.safeMessage(e));
    }

    /** 从 Intent 取账号清单：与锦标赛统计页同一套传参口径 */
    private void readIntentArgs() {
        List<String> in = getIntent().getStringArrayListExtra(EXTRA_ADDRS);
        addrs = in == null ? new ArrayList<String>() : new ArrayList<>(in);
        List<String> lb = getIntent().getStringArrayListExtra(EXTRA_LABELS);
        labels = lb == null ? new ArrayList<String>() : new ArrayList<>(lb);
    }

    // endregion

    // region 回填

    private void fill(Snapshot s, String dialUrl) {
        set(valAbiCheck, s.abiCheck);
        valAbiCheck.setTextColor(getColor(s.abiCheck != null && s.abiCheck.startsWith("⚠")
                ? R.color.danger : R.color.text_primary));

        HelixContractApi.Baseline b = s.baseline;
        set(valNode, b == null ? dialUrl : b.rpcUrl);
        set(valChainId, b == null ? null : String.valueOf(b.chainId));
        set(valBlock, b == null ? null : str(b.blockNumber));
        set(valBlockTime, b == null ? null : formatTime(b.blockTimestamp));
        set(valGasPrice, b == null ? null : num(b.gasPrice, HelixConfig.DEC_DAI, 9) + " " + getString(R.string.helix_unit_dai));

        fillPrice(s, b);
        fillParams(s);
        fillPositions(s);
        fillTokens(s);
        set(valVaultReleased, s.vaultReleased == null ? null
                : num(s.vaultReleased, HelixConfig.DEC_AILGNS, 2) + " " + getString(R.string.helix_unit_ailgns));
        fillPowerProbe(s);

        if (s.failedParts.isEmpty()) {
            tvError.setVisibility(View.GONE);
        } else {
            tvError.setText(getString(R.string.helix_part_failed_fmt, join(s.failedParts)));
            tvError.setVisibility(View.VISIBLE);
        }
    }

    private void fillPrice(Snapshot s, HelixContractApi.Baseline b) {
        final String dai = getString(R.string.helix_unit_dai);
        if (s.oracle != null) {
            valTwap.setText(num(s.oracle.lastTwapPrice, HelixConfig.DEC_DAI, 6) + " " + dai);
            set(valTwapTime, formatTime(s.oracle.lastTimestamp));
            if (b != null && s.oracle.lastTimestamp != null && b.blockTimestamp != null) {
                long age = b.blockTimestamp.longValue() - s.oracle.lastTimestamp.longValue();
                valTwapAge.setText(age + getString(R.string.helix_unit_second));
                // 偏旧只标色不报错：TWAP 本来就靠外部 update() 喂，旧不等于错
                boolean stale = age > HelixConfig.ORACLE_STALE_WARN_SEC;
                valTwapAge.setTextColor(getColor(stale ? R.color.warn_text : R.color.text_primary));
                noteOracleStale.setVisibility(stale ? View.VISIBLE : View.GONE);
            }
        }
        set(valSpot, s.spot == null ? null : num(s.spot, HelixConfig.DEC_DAI, 6) + " " + dai);

        // 偏差按万分之一（bps）表达：实测 TWAP 与主池瞬时价差约 1/10000，同口径才谈得上比
        if (s.oracle != null && s.spot != null && s.oracle.lastTwapPrice != null
                && s.spot.signum() > 0 && s.oracle.lastTwapPrice.signum() > 0) {
            BigInteger diff = s.oracle.lastTwapPrice.subtract(s.spot).abs()
                    .multiply(BigInteger.valueOf(10000)).divide(s.spot);
            boolean same = diff.intValue() <= 50;
            valDeviation.setText(diff + " / 10000 " + (same ? "✅ 同口径" : "⚠ 口径可疑"));
            valDeviation.setTextColor(getColor(same ? R.color.success_deep : R.color.warn_text));
        }

        if (s.quote != null) {
            valQuoteProbe.setText(num(s.quote.amountIn, HelixConfig.DEC_AILGNS, 6)
                    + " " + getString(R.string.helix_unit_ailgns));
            valQuoteOut.setText(num(s.quote.out, HelixConfig.DEC_DAI, 6) + " " + dai);
            valQuoteFee.setText(num(s.quote.fee, HelixConfig.DEC_DAI, 6) + " " + dai);
            // 合计这一项就是将来 activate 要填的 maxDaiIn，单独标主色，别当成普通读数扫过去
            valQuoteTotal.setText(num(s.quote.total, HelixConfig.DEC_DAI, 6) + " " + dai);
            valQuoteTotal.setTextColor(getColor(R.color.primary));
        }
    }

    private void fillParams(Snapshot s) {
        HelixContractApi.TurbineParams p = s.params;
        if (p == null) return;
        valFeeBps.setText(str(p.feeBps) + " " + getString(R.string.helix_unit_bps) + "（" + bpsPct(p.feeBps) + "）");
        valBurnBps.setText(str(p.burnBps) + " " + getString(R.string.helix_unit_bps) + "（" + bpsPct(p.burnBps) + "）");
        if (p.lockSeconds != null) {
            valLockSeconds.setText(p.lockSeconds + getString(R.string.helix_unit_second)
                    + " ≈ " + (p.lockSeconds.longValue() / 3600) + " 小时");
        }
        set(valMaxFeeBps, str(p.maxFeeBps));
        set(valLockCount, str(p.lockCount));
        set(valTotalClaimed, p.totalClaimed == null ? null
                : num(p.totalClaimed, HelixConfig.DEC_AILGNS, 2) + " " + getString(R.string.helix_unit_ailgns));
    }

    /**
     * 持仓一节：每个账号压成两列三行（左列涡轮三项，右列笔数/金库/功率）。
     *
     * <p>不做成六列表格：窄屏放不下，而且各列数宽差极大（可领是 0.00，功率是 5e17 这种量），
     * 硬排会挤到换行。压成一个账号一块之后，同一账号的数据天然绑在一起，
     * 跨账号扫读靠左边的编号定位就够了。</p>
     */
    private void fillPositions(Snapshot s) {
        containerPos.removeAllViews();
        if (addrs.isEmpty()) {
            addNote(containerPos, R.string.helix_position_none).setVisibility(View.VISIBLE);
            return;
        }
        int total = addrs.size();
        int shown = s.positions == null ? 0 : s.positions.size();
        if (shown < total) {
            TextView tip = addNote(containerPos, R.string.helix_position_truncated_fmt);
            tip.setText(getString(R.string.helix_position_truncated_fmt, total, shown));
            tip.setVisibility(View.VISIBLE);
        }
        if (shown == 0) return;

        final String unknown = getString(R.string.helix_pos_unknown);
        int errCnt = 0;
        for (int i = 0; i < s.positions.size(); i++) {
            HelixContractApi.Position p = s.positions.get(i);
            String label = (i < labels.size() && labels.get(i) != null && !labels.get(i).isEmpty())
                    ? labels.get(i) : String.valueOf(i + 1);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(7), 0, dp(3));

            TextView tvName = new TextView(this);
            tvName.setText(label + "\n" + HelixContractApi.shortAddr(p.addr));
            tvName.setTextSize(11);
            tvName.setTextColor(getColor(R.color.text_secondary));
            row.addView(tvName, new LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvLeft = new TextView(this);
            tvLeft.setTextSize(11);
            tvLeft.setTextColor(getColor(R.color.text_primary));
            tvLeft.setFontFeatureSettings("tnum");
            tvLeft.setText(trio(getString(R.string.helix_pos_claimable), p.claimable, unknown)
                    + "\n" + trio(getString(R.string.helix_pos_locked), p.locked, unknown)
                    + "\n" + trio(getString(R.string.helix_pos_claimed), p.claimed, unknown));
            row.addView(tvLeft, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvRight = new TextView(this);
            tvRight.setTextSize(11);
            tvRight.setTextColor(getColor(R.color.text_secondary));
            tvRight.setGravity(Gravity.END);
            tvRight.setFontFeatureSettings("tnum");
            String locks = p.lockCount == null ? null : p.lockCount + " " + getString(R.string.helix_pos_locks);
            // 功率只摆原始值、不做除法：它的定点位数没像价格那样实测对过，
            // 按 1e18 去除只会把「口径未知」画成一个好看的数；对官方 App 时拿这串原值对最直接
            String power = orUnknown(str(p.power), unknown);
            tvRight.setText(orUnknown(locks, unknown)
                    + "\n" + trio(getString(R.string.helix_pos_pending), p.vaultPending, unknown)
                    + "\n" + getString(R.string.helix_pos_power) + " " + power);
            row.addView(tvRight, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            containerPos.addView(row);
            // errors 里只登记真正失败的项，revert 的文案已经打进 logcat
            errCnt += p.errors.size();
        }
        if (errCnt > 0) {
            TextView tip = addNote(containerPos, R.string.helix_position_failed_fmt);
            tip.setText(getString(R.string.helix_position_failed_fmt, errCnt));
            tip.setVisibility(View.VISIBLE);
        }
    }

    private void fillTokens(Snapshot s) {
        if (!addrs.isEmpty()) set(valWatch, HelixContractApi.shortAddr(addrs.get(0)));
        applyToken(valDaiBal, valDaiAllow, s.dai, HelixConfig.DEC_DAI, R.string.helix_unit_dai);
        applyToken(valLgnsBal, valLgnsAllow, s.lgns, HelixConfig.DEC_LGNS, R.string.helix_unit_lgns);
        if (s.aiLgns != null && s.aiLgns.balance != null) {
            valAiLgnsBal.setText(num(s.aiLgns.balance, HelixConfig.DEC_AILGNS, 4)
                    + " " + getString(R.string.helix_unit_ailgns));
        }
        // 无限授权不是错误，但要在页面上说一句：步骤「撤销授权」工具要动的就是这些值
        noteInfAllow.setVisibility(isInfinite(s.dai) || isInfinite(s.lgns) ? View.VISIBLE : View.GONE);
    }

    /**
     * 回填一枚 ERC20 的余额与授权。
     *
     * <p>精度取合约自己报的 {@code decimals}，取不到才回落到 {@code HelixConfig} 的常量；
     * 两者不一致时把余额标黄——LGNS 是 9 位、其余是 18 位，一旦哪天精度改了而不标出来，
     * 展示值会安静地错 10^9 倍。</p>
     */
    private void applyToken(TextView tvBal, TextView tvAllow, HelixContractApi.TokenView v,
                            int decFallback, int unitRes) {
        if (v == null) return;
        int dec = v.decimals == null ? decFallback : v.decimals;
        String unit = getString(unitRes);
        if (v.balance != null) {
            tvBal.setText(num(v.balance, dec, 4) + " " + unit);
            if (v.decimals != null && v.decimals != decFallback) {
                tvBal.setTextColor(getColor(R.color.warn_text));
            }
        }
        if (v.allowance != null) {
            boolean inf = isInfinite(v);
            tvAllow.setText(inf ? "无限授权" : num(v.allowance, dec, 4) + " " + unit);
            tvAllow.setTextColor(getColor(inf ? R.color.warn_text : R.color.text_primary));
        }
    }

    /** 授权额是否等于无限（{@code MAX_UINT256}）：实测全网对 Turbine 的 DAI 授权都是这个值 */
    private boolean isInfinite(HelixContractApi.TokenView v) {
        return v != null && v.allowance != null && v.allowance.bitLength() >= 255;
    }

    /**
     * 回填功率入口的对比诊断。
     *
     * <p>两个胶囊分开判：只要任意一个发起方干跑通过，就认为那条入口可用——两个地址同一额度
     * 只给出一个成功时，具体差在资格还是差在余额，上面那两行原文文案自己会讲。</p>
     */
    private void fillPowerProbe(Snapshot s) {
        valBurnAmount.setText(num(HelixConfig.BURN_AMOUNT_RAW, HelixConfig.DEC_LGNS, 0)
                + " " + getString(R.string.helix_unit_lgns));

        boolean anyOk = (s.burnZero != null && s.burnZero.wouldSucceed)
                || (s.burnWatch != null && s.burnWatch.wouldSucceed);
        if (s.burnZero != null) {
            valBurnFromZero.setText(probeFromLabel(true));
            applyProbeResult(valBurnRetZero, s.burnZero);
        }
        if (s.burnWatch != null) {
            valBurnFromWatch.setText(probeFromLabel(false));
            applyProbeResult(valBurnRetWatch, s.burnWatch);
        }
        burnChip.setText(anyOk ? R.string.helix_burn_ok : R.string.helix_burn_blocked);
        burnChip.setBackgroundResource(anyOk ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);

        boolean partOk = (s.participateZero != null && s.participateZero.wouldSucceed)
                || (s.participateWatch != null && s.participateWatch.wouldSucceed);
        if (s.participateZero != null) {
            valPartFromZero.setText(probeFromLabel(true));
            applyProbeResult(valPartRetZero, s.participateZero);
        }
        if (s.participateWatch != null) {
            valPartFromWatch.setText(probeFromLabel(false));
            applyProbeResult(valPartRetWatch, s.participateWatch);
            // 有读数才把「口径未实测」这句摆出来，免得空着四行时多一块无关的黄底提醒
            notePartCaveat.setVisibility(View.VISIBLE);
        }
        partChip.setText(partOk ? R.string.helix_part_ok : R.string.helix_part_blocked);
        partChip.setBackgroundResource(partOk ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);
    }

    /** 探针发起方那一行的文案：零地址只看得到额度判定，真实地址那行直接带上短地址 */
    private String probeFromLabel(boolean zero) {
        if (zero) return "零地址（只暴露额度判定）";
        return addrs.isEmpty() ? "未选中账号" : HelixContractApi.shortAddr(addrs.get(0));
    }

    private void applyProbeResult(TextView tv, HelixContractApi.BurnProbe probe) {
        tv.setText(probe.wouldSucceed ? getString(R.string.helix_burn_ok) : probe.reason);
        tv.setTextColor(getColor(probe.wouldSucceed ? R.color.success_deep : R.color.danger));
    }

    // endregion

    // region 小工具

    /** 回填一行：没查到就写占位符，绝不写 0——这是本页与那两个 py 脚本最大的分别 */
    private void set(TextView tv, String v) {
        tv.setText(v == null ? getString(R.string.common_placeholder) : v);
    }

    private static String str(BigInteger v) {
        return v == null ? null : v.toString();
    }

    private static String orUnknown(String v, String unknown) {
        return v == null || v.isEmpty() ? unknown : v;
    }

    /** 持仓里「标签 + 金额」的一小段：金额为 null 时用 {@code —}，不用 0 */
    private String trio(String label, BigInteger wei, String unknown) {
        return label + " " + orUnknown(wei == null ? null : num(wei, HelixConfig.DEC_AILGNS, 2), unknown);
    }

    /** 定点读数转人写小数（保留固定位数，四舍五入） */
    private static String num(BigInteger raw, int decimals, int keep) {
        if (raw == null) return getString0();
        return new BigDecimal(raw)
                .divide(new BigDecimal(BigInteger.TEN.pow(decimals)), keep, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** {@link #num} 里 null 的兜底字面量：与 {@code common_placeholder} 一致 */
    private static String getString0() {
        return "--";
    }

    /** bps 转百分数文本：250 → 2.5%，尾零不保留 */
    private static String bpsPct(BigInteger bps) {
        if (bps == null) return "--";
        return new BigDecimal(bps)
                .divide(new BigDecimal(100), 2, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("、");
            sb.append(p);
        }
        return sb.toString();
    }

    /** 链上秒级时间戳转本地时间：核对「TWAP 是不是刚喂过」看秒就够，不用只给一个数字 */
    private String formatTime(BigInteger tsSec) {
        if (tsSec == null) return null;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(tsSec.longValue() * 1000L));
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadSeq.incrementAndGet();
        super.onDestroy();
    }
}
