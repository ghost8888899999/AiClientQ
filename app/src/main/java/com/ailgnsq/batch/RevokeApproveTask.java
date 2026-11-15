package com.ailgnsq.batch;

import android.content.Context;
import android.content.SharedPreferences;

import com.ailgnsq.contract.ArenaContractApi;
import com.ailgnsq.contract.BlockscoutApi;
import com.ailgnsq.contract.HelixContractApi;
import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.manager.KeyStoreManager;
import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.model.HelixConfig;
import com.ailgnsq.model.KeyListItem;
import com.ailgnsq.task.TaskAccountState;
import com.ailgnsq.task.TaskLogCallback;
import com.ailgnsq.util.Web3AndroidUtil;
import com.google.gson.Gson;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 授权巡检与撤销：逐号读 ERC20 对合约的 allowance，只把不等于目标值的那笔 approve 发出去。
 *
 * <p>为什么必须有这一条：{@link HelixTurbineTask} 与 {@link BatchEnterTask} 都按链上现实用了
 * <b>无限授权</b>（实测对 Turbine 的 DAI 授权就是一串 F，官方 App 也这么签）。无限授权省掉了
 * 每轮一次 approve 的 gas，代价是一旦合约出问题，被划走的额度没有上限。这批号唯一能把额度收回来的
 * 入口就是这里，不做等于签出去就再也不过问。</p>
 *
 * <p>两个预设项都不是我推测出来的组合，各自有出处：</p>
 * <ul>
 *   <li><b>DAI → Turbine</b>：{@code ling1.py} 的 {@code approve(TURBINE_ADDRESS, 2**256-1)}，
 *       也是本工程激活前那一步；撤了之后激活会被合约拒，必须重新授权才能恢复；</li>
 *   <li><b>LGNS → PowerLedger</b>：{@code ai.py} 的 {@code pre_approve_all}，额度是
 *       {@code BURN_AMOUNT}（500 LGNS）而不是无限，所以「恢复」档写回的是这个原值。</li>
 * </ul>
 *
 * <p>擂台那条 {@code fLGNS → Arena} 曾是第三个预设，因本页只巡检 HELIX 在用授权、
 * 不整 App 玩 fLGNS（最多测试 LGNS ⇄ fLGNS 兑换）而移除。</p>
 *
 * <p>三条口径沿用 {@link HelixTurbineTask}：私钥只从 App 自己的 KeyStore 取；
 * 读数查不到就跳过、不当 0 处理（把「查失败」当成「没授权」会白签一笔交易）；
 * 广播前先把 txHash + rawTx 落 SP，进程被回收后能原样重发。</p>
 */
public class RevokeApproveTask {

    /** 日志分类，与 {@code TaskLogItem.logType} 同一套约定 */
    private static final String TAG = "REVOKE";

    /**
     * 账号级进度所在的 SP 文件。
     *
     * <p>不复用 {@code helix_progress}：两边都用 {@code task_<地址>} 当 key，同一个地址
     * 既可能在 HELIX 里有在途 activate、又可能在这里有在途 approve，混一个文件会互相抹掉对方的在途记录。</p>
     */
    private static final String SP_PROGRESS = "revoke_progress";

    private static final Gson gson = new Gson();

    /** {@code Options.targetIndex}：-1 = 巡检全部预设项 */
    public static final int TARGET_ALL = -1;

    /** 动作：把额度改成 0 */
    public static final int MODE_REVOKE = 0;
    /** 动作：把额度改回这条授权关系原本的值（撤销后发现功能不能用了，用它恢复） */
    public static final int MODE_RESTORE = 1;

    // region 预设授权关系

    /** 一条「币 → spender」的授权关系；UI 的下拉项和任务的判据共用同一份定义 */
    public static class Target {
        /** 展示名，同时进日志，保证页面说的和日志里记的是同一句话 */
        public final String name;
        /** 币合约地址 */
        public final String tokenAddr;
        public final String tokenName;
        /**
         * 展示用精度。
         *
         * <p>只有「恢复 LGNS 授权」那一档的数值真的依赖精度（500 × 10^9），而它取的是
         * {@link HelixConfig#BURN_AMOUNT_RAW} 常量，不经过这个字段。撤销档写的是 0、
         * 无限档写的是 2^256-1，两个都与精度无关，所以这里填错最多让日志里的数字难看，
         * 不会发错金额。</p>
         */
        public final int dispDecimals;
        public final String spenderAddr;
        public final String spenderName;
        /** 「恢复」档写回的原值：无限或这条关系当初签的实际额度 */
        public final BigInteger restoreAmount;
        /** 撤回 0 之后哪条功能会停：确认框要原样摆给用户 */
        public final String revokeImpact;

        Target(String name, String tokenAddr, String tokenName, int dispDecimals,
               String spenderAddr, String spenderName, BigInteger restoreAmount, String revokeImpact) {
            this.name = name;
            this.tokenAddr = tokenAddr;
            this.tokenName = tokenName;
            this.dispDecimals = dispDecimals;
            this.spenderAddr = spenderAddr;
            this.spenderName = spenderName;
            this.restoreAmount = restoreAmount;
            this.revokeImpact = revokeImpact;
        }

        /** 币地址那一栏的展示值 */
        public String tokenDesc() {
            return tokenName + " " + HelixContractApi.shortAddr(tokenAddr);
        }
    }

    /** 涡轮激活要划 DAI，授权对象是 Turbine 合约本身 */
    public static final Target DAI_TURBINE = new Target(
            "DAI → 涡轮 Turbine", HelixConfig.DAI_ADDR, "DAI", HelixConfig.DEC_DAI,
            HelixConfig.TURBINE_ADDR, "Turbine", HelixConfig.MAX_UINT256,
            "撤销后每日链路的「激活」会被合约拒（Turbine 划不走 DAI），要重新授权才能激活");

    /** 烧 LGNS 换功率：ai.py 里签的是 500 LGNS 这个具体额度，不是无限 */
    public static final Target LGNS_POWER = new Target(
            "LGNS → 功率账本", HelixConfig.LGNS_ADDR, "LGNS", HelixConfig.DEC_LGNS,
            HelixConfig.POWER_LEDGER_ADDR, "PowerLedger", HelixConfig.BURN_AMOUNT_RAW,
            "撤销后 burnForPower 划不走 LGNS（本工程未接这条写链路，实际影响的是官方 App 的烧币）");

    /** 下拉项顺序：默认第一项就是全量巡检——多数用户想的是「这些号到底还签了哪些额度出去」 */
    public static final Target[] TARGETS = {DAI_TURBINE, LGNS_POWER};

    /** 按 {@code Options.targetIndex} 取本轮要处理的授权关系 */
    public static List<Target> scopeOf(int targetIndex) {
        if (targetIndex == TARGET_ALL) return Arrays.asList(TARGETS);
        if (targetIndex < 0 || targetIndex >= TARGETS.length) return new ArrayList<>();
        return java.util.Collections.singletonList(TARGETS[targetIndex]);
    }

    /** 给确认框与开头日志用的一句话范围描述 */
    public static String describeScope(int targetIndex) {
        List<Target> scope = scopeOf(targetIndex);
        if (scope.isEmpty()) return "（无）";
        if (scope.size() == 1) return scope.get(0).name;
        StringBuilder sb = new StringBuilder();
        for (Target t : scope) {
            if (sb.length() > 0) sb.append("、");
            sb.append(t.name);
        }
        return sb.append("（全部巡检）").toString();
    }

    // endregion

    // region 轻量工具

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    private static String shortMsg(Exception e) {
        String m = msgOf(e);
        return m.length() <= 60 ? m : m.substring(0, 60);
    }

    private static String keyNoOf(KeyListItem item) {
        return (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
    }

    /** 本轮要写回的目标额度：恢复档取这条关系原本的值，撤销档固定 0 */
    public static BigInteger wantOf(Target t, int mode) {
        return mode == MODE_RESTORE ? t.restoreAmount : BigInteger.ZERO;
    }

    /** 额度的人读形式：无限与 0 这两种占了绝大多数，单独给词，免得日志里全是 115 位数字 */
    public static String humanAmt(BigInteger v, Target t) {
        if (v == null) return "未知";
        if (BigInteger.ZERO.equals(v)) return "0（未授权/已撤销）";
        if (HelixConfig.MAX_UINT256.equals(v)) return "无限";
        return HelixContractApi.plain(v, t.dispDecimals) + " " + t.tokenName;
    }

    /** gasPrice 取不到也要能跑：用实测的 12 Gwei 兜底，并在日志里说明用的是兜底值 */
    private static BigInteger safeGasPrice(Web3j web3j) {
        try {
            return Web3AndroidUtil.getGasPrice(web3j);
        } catch (Exception e) {
            return HelixConfig.GAS_PRICE_FALLBACK_RAW;
        }
    }

    /** 本轮计数：只统计到底发生了多少笔，避免「跑完了但一笔没改」被看成成功 */
    static class Tally {
        int changed;   // 真的签了 approve 并已上链
        int same;      // 已经是目标值，没花 gas
        int blocked;   // 读不到 / 干跑被拒 / 发送失败
    }

    // endregion

    // region 进度持久化

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(SP_PROGRESS, Context.MODE_PRIVATE);
    }

    private static void saveState(Context ctx, String addr, TaskAccountState st) {
        if (st.getStatus() == TaskAccountState.STATUS_SUCCESS || st.getStatus() == TaskAccountState.STATUS_FAIL) return;
        sp(ctx).edit().putString("task_" + addr.toLowerCase(), gson.toJson(st)).apply();
    }

    private static TaskAccountState getState(Context ctx, String addr) {
        String json = sp(ctx).getString("task_" + addr.toLowerCase(), null);
        if (json == null) return new TaskAccountState();
        TaskAccountState st = gson.fromJson(json, TaskAccountState.class);
        if (st == null) return new TaskAccountState();
        if (st.getChainId() != 0 && st.getChainId() != ArenaConfig.CHAIN_ID) {
            sp(ctx).edit().remove("task_" + addr.toLowerCase()).apply();
            return new TaskAccountState();
        }
        if (st.getStatus() != TaskAccountState.STATUS_IDLE && st.getStatus() != TaskAccountState.STATUS_TX_SENT) {
            st = new TaskAccountState();
        }
        return st;
    }

    // endregion

    // region 运行选项与入口

    /** 本轮改哪些授权、改成什么 */
    public static class Options {
        /** {@link #TARGET_ALL} 巡检全部三项，或 {@link #TARGETS} 的下标 */
        public int targetIndex = TARGET_ALL;
        /** {@link #MODE_REVOKE} 撤回到 0，{@link #MODE_RESTORE} 恢复成原本额度 */
        public int mode = MODE_REVOKE;
    }

    /**
     * 主入口。调用方负责放在后台线程上跑，并准备一个 {@code sessionCryptoKey} 会话口令。
     *
     * @param selected 本次要处理的账号（调用方已按勾选/启用筛过一遍）
     */
    public static void run(Context ctx, TaskLogCallback cb, List<KeyListItem> selected,
                           char[] sessionCryptoKey, Options opt) throws Exception {
        if (selected == null || selected.isEmpty()) {
            cb.onLog("", TAG, "⚠没有选中任何账号");
            return;
        }
        if (opt == null) opt = new Options();
        final List<Target> scope = scopeOf(opt.targetIndex);
        if (scope.isEmpty()) {
            cb.onLog("", TAG, "⚠授权项下标无效,本轮不处理任何关系");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            cb.onLog("", TAG, "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        final String action = opt.mode == MODE_RESTORE ? "恢复" : "撤销";
        final Map<String, BigInteger> nonceMap = new HashMap<>();
        final Tally tally = new Tally();
        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            cb.onLog("", TAG, "开始" + action + "授权 · 范围=" + describeScope(opt.targetIndex)
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID);
            BigInteger gasPrice = safeGasPrice(web3j);
            int ok = 0, skip = 0, fail = 0;
            List<String> failNos = new ArrayList<>();
            int total = selected.size();

            for (int i = 0; i < total; i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    cb.onLog("", TAG, "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selected.get(i);
                String keyNo = keyNoOf(item);
                if (!item.enabled || item.cipherText == null || item.cipherText.isEmpty()) {
                    cb.onLog(keyNo, TAG, "跳过:账号未启用或无密钥");
                    skip++;
                    continue;
                }

                String addr;
                ECKeyPair keyPair;
                try {
                    String plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    keyPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    plainPk = null;
                    addr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
                } catch (Exception ex) {
                    cb.onLog(keyNo, TAG, "✖ 解密失败:" + msgOf(ex));
                    cb.onKeyFailure(keyNo, TAG, msgOf(ex));
                    fail++;
                    failNos.add(keyNo);
                    continue;
                }

                // 在途交易先收拾干净：这个号的上一笔 approve 还没定局就动新交易，必定撞 nonce
                if (!resumeInFlight(ctx, cb, web3j, keyNo, addr, nonceMap)) {
                    skip++;
                    continue;
                }

                try {
                    processOne(ctx, cb, web3j, keyNo, addr, keyPair, nonceMap, gasPrice, scope, opt.mode, tally);
                    ok++;
                } catch (Exception e) {
                    cb.onLog(keyNo, TAG, "✖ 本轮失败:" + msgOf(e));
                    cb.onKeyFailure(keyNo, TAG, msgOf(e));
                    fail++;
                    failNos.add(keyNo);
                } finally {
                    keyPair = null;
                }
                saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }

            cb.onLog("", TAG, action + "授权结束 · 账号 成功=" + ok + " 跳过=" + skip + " 失败=" + fail
                    + " · 已改=" + tally.changed + "笔 本就符合=" + tally.same + "条 没改成=" + tally.blocked
                    + (failNos.isEmpty() ? "" : " · 失败编号:" + String.join(",", failNos)));
        } catch (IOException dialFail) {
            cb.onLog("", TAG, "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            Long height = BlockscoutApi.fetchLatestBlockHeight();
            cb.onLog("", TAG, height != null
                    ? "Blockscout 备用探测：链高度=" + height + "（链正常，稍后重试即可）"
                    : "Blockscout 备用探测：浏览器也不可达，链可能停摆");
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region 在途交易恢复

    /**
     * 处理磁盘上留下的 {@code TX_SENT}。
     *
     * @return true = 可以继续跑本号的新交易；false = 本轮跳过这个号
     */
    private static boolean resumeInFlight(Context ctx, TaskLogCallback cb, Web3j web3j, String keyNo,
                                          String addr, Map<String, BigInteger> nonceMap) {
        TaskAccountState st = getState(ctx, addr);
        if (st.getStatus() != TaskAccountState.STATUS_TX_SENT || st.txHash == null) return true;
        cb.onLog(keyNo, TAG, "发现在途交易 " + st.txHash.substring(0, Math.min(18, st.txHash.length())) + "，先确认结果");
        try {
            EthGetTransactionReceipt rc = web3j.ethGetTransactionReceipt(st.txHash).send();
            if (rc.getTransactionReceipt().isPresent()) {
                TransactionReceipt r = rc.getTransactionReceipt().get();
                saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                nonceMap.remove(addr);
                if (r.isStatusOK()) {
                    // 已经上链就不必重发：额度在链上已经变了，下面 processOne 会读到新值再比对
                    cb.onLog(keyNo, TAG, "在途交易已成功上链，重新读一次额度再决定");
                    return true;
                }
                cb.onLog(keyNo, TAG, "在途交易已上链但 revert（重发也还会 revert），跳过本号");
                return false;
            }
            // 没回执：可能是节点排队，也可能根本没广播出去。rawTx 在原样重发时 nonce 固定，
            // 真要是不小心发了两笔也只会成一笔，不会双花
            if (st.rawTx != null) {
                cb.onLog(keyNo, TAG, "在途交易还没有回执，原样重发一次 rawTx");
                String rpcHash = ArenaContractApi.broadcastRaw(web3j, st.rawTx);
                cb.onLog(keyNo, TAG, "重发已广播：" + (rpcHash != null ? rpcHash : st.txHash) + "，本轮到此为止，下次进来再确认");
            } else {
                cb.onLog(keyNo, TAG, "在途交易没有回执且本地没存 rawTx，无法原样重发，清记录重来");
                saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }
            return false;
        } catch (Exception e) {
            // 查不动回执就不要动这个号：这时候继续发新交易很可能撞上同一个 nonce
            cb.onLog(keyNo, TAG, "⚠ 在途交易回查失败，本轮跳过本号:" + msgOf(e));
            return false;
        }
    }

    // endregion

    // region 单号流程

    /**
     * 一个账号的一轮：对范围内的每条关系「读 allowance → 比对 → 需要才发」。
     *
     * <p>每条关系独立 try，一条读不到不影响其他条：撤销是运维动作，能改一条是一条。</p>
     */
    private static void processOne(Context ctx, TaskLogCallback cb, Web3j web3j, String keyNo, String addr,
                                   ECKeyPair keyPair, Map<String, BigInteger> nonceMap, BigInteger gasPrice,
                                   List<Target> scope, int mode, Tally tally) {
        StringBuilder sb = new StringBuilder();
        for (Target t : scope) {
            String token = t.tokenAddr;

            BigInteger allow;
            try {
                allow = HelixContractApi.callErc20Allowance(web3j, token, addr, t.spenderAddr);
            } catch (Exception e) {
                // 查失败不能当「没授权」：那会给本来已经撤销过的号再签一笔 approve，白付 gas
                sb.append(t.name).append(":allowance 查不到(").append(shortMsg(e)).append("),未发交易 | ");
                tally.blocked++;
                continue;
            }

            BigInteger want = wantOf(t, mode);
            if (allow == null) {
                // 返回空字（节点给了回包但解不出 uint）也算查不到：拿 null 当 0 会给已经无限授权的号重签一笔无限
                sb.append(t.name).append(":allowance 解不出值,未发交易 | ");
                tally.blocked++;
                continue;
            }
            if (allow.equals(want)) {
                sb.append(t.name).append(":已是 ").append(humanAmt(allow, t)).append(",无需交易 | ");
                tally.same++;
                continue;
            }

            String data = HelixContractApi.encodeApprove(t.spenderAddr, want);
            // 干跑：币地址不是 ERC20、合约把自己 lock 住（UNAUTHORIZED 那类）都会在这里露出来，
            // 这类确定性错误没必要花真 gas 去链上收一条 revert
            try {
                HelixContractApi.simulateWrite(web3j, addr, token, data);
            } catch (Exception e) {
                sb.append(t.name).append(":当前 ").append(humanAmt(allow, t)).append(" → 目标 ")
                        .append(humanAmt(want, t)).append(",干跑被拒未发交易(").append(shortMsg(e)).append(") | ");
                tally.blocked++;
                continue;
            }

            sb.append(t.name).append(":当前 ").append(humanAmt(allow, t)).append(" → 目标 ")
                    .append(humanAmt(want, t)).append(" ")
                    .append(sendApprove(ctx, web3j, addr, keyPair, token, t, want, data, nonceMap, gasPrice, tally));
        }
        cb.onLog(keyNo, TAG, sb.length() == 0 ? "范围内没有可处理的授权关系" : sb.toString());
    }

    /** 发一笔 approve：广播前落盘，成功进 nonce、失败回链上重对齐，失败不外抛，只回一行给汇总 */
    private static String sendApprove(Context ctx, Web3j web3j, String addr, ECKeyPair keyPair, String token,
                                      Target t, BigInteger want, String data,
                                      Map<String, BigInteger> nonceMap, BigInteger gasPrice, Tally tally) {
        BigInteger nonce;
        try {
            nonce = nonceMap.get(addr) != null
                    ? nonceMap.get(addr) : Web3AndroidUtil.getPendingNonce(web3j, addr);
        } catch (Exception e) {
            tally.blocked++;
            return "取不到 nonce,未发交易 | ";
        }
        try {
            TransactionReceipt r = HelixContractApi.sendWrite(web3j, keyPair, nonce, gasPrice,
                    HelixConfig.GAS_LIMIT_ERC20, token, data,
                    (h, raw) -> saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
            nonceMap.put(addr, nonce.add(BigInteger.ONE));
            tally.changed++;
            return "已上链 gas=" + r.getGasUsed() + " | ";
        } catch (Exception e) {
            nonceMap.put(addr, resyncNonce(web3j, addr, nonce));
            tally.blocked++;
            if (isOutOfGas(e, HelixConfig.GAS_LIMIT_ERC20)) {
                return "⚠️OOG(gas 上限 " + HelixConfig.GAS_LIMIT_ERC20 + " 不够,不是合约拒绝) | ";
            }
            return "失败(" + shortMsg(e) + ") | ";
        }
    }

    /**
     * 失败后重新对齐 nonce。
     *
     * <p>两种失败对 nonce 的影响相反：已上链但 revert（被消耗）、广播没发出去（未消耗）。
     * 本地猜哪一种都会让后面的交易卡住，所以一律回链上取；取不到时按「已消耗」处理。</p>
     */
    private static BigInteger resyncNonce(Web3j web3j, String addr, BigInteger fallback) {
        try {
            return Web3AndroidUtil.getPendingNonce(web3j, addr);
        } catch (Exception e) {
            return fallback.add(BigInteger.ONE);
        }
    }

    /**
     * 这次失败是「gas 上限没给够」还是「合约按业务规则拒绝」。
     *
     * <p>跟 {@link HelixTurbineTask} 同一套判据：OOG 时回执的 gasUsed 恰好等于挂的上限。
     * approve 的 10 万上限是照 py 抄来、本工程未实测的值，盖不住时要说清是额度问题而不是合约不让改。</p>
     */
    private static boolean isOutOfGas(Exception e, BigInteger gasLimit) {
        if (!(e instanceof ArenaContractApi.RevertedTxException)) return false;
        BigInteger used = ((ArenaContractApi.RevertedTxException) e).gasUsed;
        return used != null && gasLimit != null && used.compareTo(gasLimit) >= 0;
    }

    // endregion
}
