package com.ailgnsq.batch;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
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

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HELIX 每日链路：结到期锁仓 → 领金库发行 → 补 DAI → 授权 → 涡轮激活 → 归集 AiLGNS。
 *
 * <p>移植对象是 {@code E:\py\AIlgns\ling1.py}，但有三处刻意不像它：</p>
 * <ul>
 *   <li><b>私钥只从 App 自己的 KeyStore 取</b>（{@code item.cipherText} + 会话口令），
 *       不去解 py 侧那份加密文件——两把钥匙的保管强度不一样，混用等于把 App 的
 *       安全边界降到最弱的那一份。</li>
 *   <li><b>发交易前先 {@code eth_call} 干跑</b>。py 是「拿价格算个 max_dai 就直接签广播」，
 *       挂了就是真金白银的 gas；干跑不要 gas，能提前拦掉「没得激活 / 滑点不够 / 没得结」。</li>
 *   <li><b>查不到就跳过，不当 0 处理</b>。py 那两份脚本每个查询都 {@code try/except: pass}，
 *       签名写错也照样跑完、一笔不发。这里读数失败就不发对应那笔，并在日志里说清是哪一步。</li>
 * </ul>
 *
 * <p>串行执行、逐账号 {@link TaskAccountState} 落盘，与 {@link BatchClaimTask} 同一套恢复口径：
 * 广播前先把 txHash + rawTx 写进 SP，进程被回收后能识别在途交易。</p>
 */
public class HelixTurbineTask {

    // region 常量与轻量工具

    /** 日志分类，与 {@code TaskLogItem.logType} 同一套约定 */
    private static final String TAG = "HELIX";

    /**
     * 账号级进度所在的 SP 文件。
     *
     * <p>刻意不复用擂台那条线的 {@code "task_progress"}：两边都用 {@code task_<地址>} 当 key，
     * 撞在同一个文件里会互相把对方的在途交易抹掉——同一个地址完全可以既在擂台有在途领取、
     * 又在这里有在途激活。</p>
     */
    private static final String SP_PROGRESS = "helix_progress";

    private static final Gson gson = new Gson();

    /** 激活 revert 后对该地址冷却 10 分钟：原因是确定性的，重发只会再花一笔 gas */
    private static final Map<String, Long> revertedCooldown = new HashMap<>();
    private static final long COOLDOWN_MS = 10 * 60 * 1000L;

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    private static String keyNoOf(KeyListItem item) {
        return (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
    }

    /**
     * 这次失败是「gas 上限没给够」还是「合约按业务规则拒绝」。
     *
     * <p>OOG 时回执的 gasUsed 恰好等于发交易挂的上限。两件事必须分开说：前者是
     * {@link HelixConfig} 里那四档照抄 py 的固定上限盖不住（数组长了、笔数多了都会费更多），
     * 后者是正常结果。混在一起记就会得出「今天没得领」的错误结论，然后一直不领。</p>
     */
    private static boolean isOutOfGas(Exception e, BigInteger gasLimit) {
        if (!(e instanceof ArenaContractApi.RevertedTxException)) return false;
        BigInteger used = ((ArenaContractApi.RevertedTxException) e).gasUsed;
        return used != null && gasLimit != null && used.compareTo(gasLimit) >= 0;
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

    private static BigInteger takeNonce(Web3j web3j, Map<String, BigInteger> map, String addr) throws Exception {
        BigInteger n = map.get(addr);
        if (n == null) {
            n = Web3AndroidUtil.getPendingNonce(web3j, addr);
        }
        return n;
    }

    /** DAI wei 的人读形式（18 位，截断 6 位：这笔链路里最小金额是零点几，4 位容易看不出差别） */
    private static String fmtDai(BigInteger wei) {
        if (wei == null) return "0";
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(HelixConfig.DEC_DAI)), 6, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /** AiLGNS wei 的人读形式 */
    private static String fmtAi(BigInteger wei) {
        if (wei == null) return "0";
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(HelixConfig.DEC_AILGNS)), 8, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
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

    /** 这一轮要不要做某几步；默认全开 */
    public static class Options {
        /**
         * 主钱包地址（小写）：既是从里面补 DAI 的来源，也是 AiLGNS 的归集目标。
         *
         * <p>传 null 就两件事都不做。它<b>必须同时出现在本次选中的账号列表里</b>，
         * 因为私钥只存在每个账号自己的 {@code cipherText} 里，不在列表里就无法替它签名。</p>
         */
        public String fundAddr;
        /** DAI 不足时允许从主钱包补差 */
        public boolean allowTopup = true;
        /** 收尾把各子号的 AiLGNS 归集到主钱包 */
        public boolean aggregate = true;
        /** false = 只结锁仓和领发行，不激活（先跑一轮看读数对不对，再放开花钱的那步） */
        public boolean allowActivate = true;
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
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            cb.onLog("", TAG, "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        final Map<String, BigInteger> nonceMap = new HashMap<>();
        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            cb.onLog("", TAG, "开始 HELIX 链路 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID
                    + (opt.fundAddr == null ? " · 未设主钱包:不补 DAI、不归集"
                    : " · 主钱包=" + HelixContractApi.shortAddr(opt.fundAddr)));

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
                Long cool = revertedCooldown.get(item.address == null ? "" : item.address.toLowerCase());
                if (cool != null && System.currentTimeMillis() - cool < COOLDOWN_MS) {
                    cb.onLog(keyNo, TAG, "跳过:上次激活被合约拒绝,"
                            + (COOLDOWN_MS - (System.currentTimeMillis() - cool)) / 60000 + " 分钟内不再重试");
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

                // 在途交易先收拾干净：链上已有回执就只清记录，没上链才原样重发一次
                if (!resumeInFlight(ctx, cb, web3j, keyNo, addr, nonceMap)) {
                    skip++;
                    continue;
                }

                try {
                    processOne(ctx, cb, web3j, keyNo, addr, keyPair, nonceMap, gasPrice, opt, ksMgr, sessionCryptoKey, selected);
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

            if (opt.aggregate && opt.fundAddr != null) {
                aggregateAll(ctx, cb, web3j, selected, sessionCryptoKey, opt, nonceMap, ksMgr, gasPrice);
            }

            String sum = "HELIX 链路结束 · 成功=" + ok + " 跳过=" + skip + " 失败=" + fail
                    + (failNos.isEmpty() ? "" : " · 失败编号:" + String.join(",", failNos));
            cb.onLog("", TAG, sum);
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

    /** gasPrice 取不到也要能跑：用实测的 12 Gwei 兜底，并在日志里说明用的是兜底值 */
    private static BigInteger safeGasPrice(Web3j web3j) {
        try {
            return Web3AndroidUtil.getGasPrice(web3j);
        } catch (Exception e) {
            return HelixConfig.GAS_PRICE_FALLBACK_RAW;
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
                cb.onLog(keyNo, TAG, r.isStatusOK()
                        ? "在途交易已成功上链，本号本轮不再重复发"
                        : "在途交易已上链但 revert（重发也还会 revert），跳过本号");
                if (!r.isStatusOK()) revertedCooldown.put(addr, System.currentTimeMillis());
                saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                nonceMap.remove(addr);
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
     * 一个账号的一轮：结锁仓 → 领发行 → 激活。三步各自独立 try。
     *
     * <p>顺序是有原因的：{@code claimStatic} 确认之后，这笔发行额才会出现在
     * {@code claimedBalance} 里，而 {@code claimedBalance} 正好是 {@code activate} 的额度上限。
     * 所以激活前必须重读一次，不能拿本轮开头那份快照。</p>
     */
    private static void processOne(Context ctx, TaskLogCallback cb, Web3j web3j, String keyNo, String addr,
                                   ECKeyPair keyPair, Map<String, BigInteger> nonceMap, BigInteger gasPrice,
                                   Options opt, KeyStoreManager ksMgr, char[] sessionKey,
                                   List<KeyListItem> selected) throws Exception {
        StringBuilder sb = new StringBuilder();
        HelixContractApi.TurbineBalance tb;
        BigInteger vaultPending;
        try {
            tb = HelixContractApi.callTurbineBalance(web3j, addr);
            vaultPending = HelixContractApi.callVaultPending(web3j, addr);
        } catch (Exception e) {
            // 读数失败就整号跳过：这三个数决定要不要发交易、发多少，瞎猜的代价是真金白银的 gas
            cb.onLog(keyNo, TAG, "✖ 涡轮/金库读数失败,本轮不动这个号:" + msgOf(e));
            return;
        }
        cb.onLog(keyNo, TAG, "读数 · 待激活=" + fmtAi(tb.claimed) + " 已解锁可领=" + fmtAi(tb.claimable)
                + " 锁仓中=" + fmtAi(tb.locked) + " 金库待领=" + fmtAi(vaultPending) + " AiLGNS="
                + fmtAi(safeBalance(web3j, HelixConfig.AILGNS_ADDR, addr)) + " DAI="
                + fmtDai(safeBalance(web3j, HelixConfig.DAI_ADDR, addr)));

        // ---- 1. 结到期锁仓 ----
        if (tb.claimable != null && tb.claimable.signum() > 0) {
            List<BigInteger> ids;
            try {
                ids = HelixContractApi.callLockIds(web3j, addr);
            } catch (Exception e) {
                ids = null;
                sb.append("结锁仓:locksOf 查不到(").append(shortMsg(e)).append(") | ");
            }
            if (ids != null && !ids.isEmpty()) {
                sb.append(stepClaimUnlocked(ctx, cb, web3j, addr, keyPair, ids, nonceMap, gasPrice));
            } else if (ids != null) {
                // claimableOf>0 但一笔锁仓都没有：这是口径变了，不是「没得领」，必须说清
                sb.append("结锁仓:claimableOf>0 但 locksOf 为空,口径可疑,已跳过 | ");
            }
        } else {
            sb.append("结锁仓:无已解锁 | ");
        }

        // ---- 2. 领金库发行 ----
        if (vaultPending != null && vaultPending.signum() > 0) {
            sb.append(stepClaimStatic(ctx, cb, web3j, addr, keyPair, vaultPending, nonceMap, gasPrice));
        } else {
            sb.append(vaultPending == null ? "领发行:额度未知,跳过" : "领发行:无待领");
            sb.append(" | ");
        }

        // ---- 3. 激活 ----
        if (!opt.allowActivate) {
            sb.append(" | 激活:本轮未开启(allowActivate=false)");
            cb.onLog(keyNo, TAG, sb.toString());
            return;
        }
        BigInteger pendingActivate;
        try {
            pendingActivate = HelixContractApi.callTurbineBalance(web3j, addr).claimed;
        } catch (Exception e) {
            cb.onLog(keyNo, TAG, sb.append(" | 激活:重读 claimedBalance 失败(")
                    .append(shortMsg(e)).append("),已跳过").toString());
            return;
        }
        if (pendingActivate == null || pendingActivate.signum() == 0) {
            cb.onLog(keyNo, TAG, sb.append(" | 激活:无待激活量").toString());
            return;
        }
        sb.append(stepActivate(ctx, cb, web3j, addr, keyPair, pendingActivate, nonceMap, gasPrice, opt, ksMgr, sessionKey, selected));
        cb.onLog(keyNo, TAG, sb.toString());
    }

    private static BigInteger safeBalance(Web3j web3j, String token, String addr) {
        try {
            return HelixContractApi.callErc20Balance(web3j, token, addr);
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortMsg(Exception e) {
        String m = msgOf(e);
        return m.length() <= 60 ? m : m.substring(0, 60);
    }

    // endregion

    // region 各步交易

    /** 通用发送：落盘 hook + 成功进 nonce / 失败重对齐，失败不外抛，只回一行给汇总 */
    private static String sendStep(Context ctx, TaskLogCallback cb, Web3j web3j, String addr, ECKeyPair keyPair,
                                   String name, BigInteger gasLimit, String to, String data,
                                   Map<String, BigInteger> nonceMap, BigInteger gasPrice) {
        BigInteger nonce = takeNonceQuiet(web3j, nonceMap, addr);
        try {
            TransactionReceipt r = HelixContractApi.sendWrite(web3j, keyPair, nonce, gasPrice, gasLimit, to, data,
                    (h, raw) -> saveState(ctx, addr, new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
            nonceMap.put(addr, nonce.add(BigInteger.ONE));
            return name + ":已上链 gas=" + r.getGasUsed() + " | ";
        } catch (Exception e) {
            nonceMap.put(addr, resyncNonce(web3j, addr, nonce));
            if (isOutOfGas(e, gasLimit)) {
                return name + ":⚠️OOG(gas 上限 " + gasLimit + " 不够,不是没得领) | ";
            }
            if (e instanceof ArenaContractApi.RevertedTxException) {
                revertedCooldown.put(addr, System.currentTimeMillis());
            }
            return name + ":失败(" + shortMsg(e) + ") | ";
        }
    }

    private static BigInteger takeNonceQuiet(Web3j web3j, Map<String, BigInteger> map, String addr) {
        try {
            return takeNonce(web3j, map, addr);
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    private static String stepClaimUnlocked(Context ctx, TaskLogCallback cb, Web3j web3j, String addr,
                                            ECKeyPair keyPair, List<BigInteger> ids,
                                            Map<String, BigInteger> nonceMap, BigInteger gasPrice) {
        String data = HelixContractApi.encodeClaimUnlocked(ids);
        try {
            HelixContractApi.simulateWrite(web3j, addr, HelixConfig.TURBINE_ADDR, data);
        } catch (Exception e) {
            // 干跑不过就不要发：省一笔 gas，也省一条看不出所以然的 revert
            return "结锁仓:干跑被拒(" + shortMsg(e) + "),未发交易 | ";
        }
        return sendStep(ctx, cb, web3j, addr, keyPair, "结锁仓(" + ids.size() + " 笔)",
                HelixConfig.GAS_LIMIT_CLAIM_UNLOCKED, HelixConfig.TURBINE_ADDR, data, nonceMap, gasPrice);
    }

    private static String stepClaimStatic(Context ctx, TaskLogCallback cb, Web3j web3j, String addr,
                                          ECKeyPair keyPair, BigInteger amount,
                                          Map<String, BigInteger> nonceMap, BigInteger gasPrice) {
        String data = HelixContractApi.encodeClaimStatic(amount);
        try {
            HelixContractApi.simulateWrite(web3j, addr, HelixConfig.COMMUNITY_VAULT_ADDR, data);
        } catch (Exception e) {
            return "领发行:干跑被拒(" + shortMsg(e) + "),未发交易 | ";
        }
        return sendStep(ctx, cb, web3j, addr, keyPair, "领发行 " + fmtAi(amount),
                HelixConfig.GAS_LIMIT_CLAIM_STATIC, HelixConfig.COMMUNITY_VAULT_ADDR, data, nonceMap, gasPrice);
    }

    /**
     * 激活：算 maxDaiIn → 补 DAI → 授权 → 干跑 → 发交易。
     *
     * <p>补差与授权都是「不够才做」：实测全网对 Turbine 的 DAI 授权本来就是
     * {@code MAX_UINT256}，那些号一轮下来只花 activate 一笔。</p>
     */
    private static String stepActivate(Context ctx, TaskLogCallback cb, Web3j web3j, String addr, ECKeyPair keyPair,
                                       BigInteger amount, Map<String, BigInteger> nonceMap, BigInteger gasPrice,
                                       Options opt, KeyStoreManager ksMgr, char[] sessionKey,
                                       List<KeyListItem> selected) {
        HelixContractApi.ActivateArgs args;
        try {
            args = HelixContractApi.planActivate(web3j, amount);
        } catch (Exception e) {
            return "激活:组参数失败(" + shortMsg(e) + ") | ";
        }
        cb.onLog(HelixContractApi.shortAddr(addr), TAG, "激活参数 · " + args.describe());

        String data = HelixContractApi.encodeActivate(args.amountRaw, args.maxDaiIn, args.deadlineSec);
        StringBuilder sb = new StringBuilder("激活:");

        // 余额够不够：先看 DAI，不够就从主钱包补
        BigInteger daiBal;
        try {
            daiBal = HelixContractApi.callErc20Balance(web3j, HelixConfig.DAI_ADDR, addr);
        } catch (Exception e) {
            return "激活:DAI 余额查不到(" + shortMsg(e) + "),未发交易 | ";
        }
        if (daiBal == null) return "激活:DAI 余额读数为空,未发交易 | ";
        if (daiBal.compareTo(args.maxDaiIn) < 0) {
            String topup = stepTopupDai(ctx, cb, web3j, addr, nonceMap, gasPrice, opt, ksMgr, sessionKey,
                    selected, args.maxDaiIn.subtract(daiBal));
            sb.append(topup);
            if (topup.startsWith("补差:未")) return sb.append("已跳过 | ").toString();
        }

        // 授权够不够
        BigInteger allow;
        try {
            allow = HelixContractApi.callErc20Allowance(web3j, HelixConfig.DAI_ADDR, addr, HelixConfig.TURBINE_ADDR);
        } catch (Exception e) {
            allow = null;
        }
        if (allow == null) return sb.append("授权额度查不到,未发交易 | ").toString();
        if (allow.compareTo(args.maxDaiIn) < 0) {
            sb.append(sendStep(ctx, cb, web3j, addr, keyPair, "授权 DAI", HelixConfig.GAS_LIMIT_ERC20,
                    HelixConfig.DAI_ADDR, HelixContractApi.encodeApprove(HelixConfig.TURBINE_ADDR, HelixConfig.MAX_UINT256),
                    nonceMap, gasPrice));
            if (sb.toString().contains("失败") || sb.toString().contains("OOG")) {
                return sb.append("授权没上链,不再激活 | ").toString();
            }
        } else {
            sb.append("授权已够;");
        }

        try {
            HelixContractApi.simulateWrite(web3j, addr, HelixConfig.TURBINE_ADDR, data);
        } catch (Exception e) {
            revertedCooldown.put(addr, System.currentTimeMillis());
            return sb.append("干跑被拒(").append(shortMsg(e)).append("),未发交易 | ").toString();
        }
        sb.append(sendStep(ctx, cb, web3j, addr, keyPair, "激活 " + fmtAi(args.amountRaw),
                HelixConfig.GAS_LIMIT_ACTIVATE, HelixConfig.TURBINE_ADDR, data, nonceMap, gasPrice));
        return sb.toString();
    }

    /**
     * 从主钱包补 DAI。
     *
     * <p>补的是「缺口 + 后面几笔的 gas」再乘 {@link HelixConfig#DAI_TOPUP_BUFFER_BPS} 的余量，
     * 不是 py 那个写死的 2 DAI：实测一笔 80 万 gas 上限的激活只要 0.0096 DAI，
     * 补 2 DAI 等于每个子号沉下两百倍的钱，而 py 又不归集 DAI，那些钱就再也回不来。</p>
     */
    private static String stepTopupDai(Context ctx, TaskLogCallback cb, Web3j web3j, String addr,
                                       Map<String, BigInteger> nonceMap, BigInteger gasPrice, Options opt,
                                       KeyStoreManager ksMgr, char[] sessionKey, List<KeyListItem> selected,
                                       BigInteger gap) {
        if (!opt.allowTopup) return "补差:未开启(allowTopup=false),";
        if (opt.fundAddr == null) return "补差:未设主钱包,";
        if (opt.fundAddr.equalsIgnoreCase(addr)) return "补差:本号就是主钱包,";
        KeyListItem fund = null;
        for (KeyListItem it : selected) {
            if (it.address != null && it.address.equalsIgnoreCase(opt.fundAddr)) { fund = it; break; }
        }
        if (fund == null || fund.cipherText == null || fund.cipherText.isEmpty()) {
            // 私钥只在各账号自己的 cipherText 里：主钱包不在本次选中列表，就没人能替它签这笔
            return "补差:主钱包不在本次选中账号里,无法替它签名,";
        }
        BigInteger gasReserve = gasPrice.multiply(
                HelixConfig.GAS_LIMIT_ERC20.add(HelixConfig.GAS_LIMIT_ACTIVATE));
        BigInteger need = gap.add(gasReserve).multiply(BigInteger.valueOf(HelixConfig.DAI_TOPUP_BUFFER_BPS))
                .divide(BigInteger.valueOf(10000));
        BigInteger fundBal;
        try {
            fundBal = HelixContractApi.callErc20Balance(web3j, HelixConfig.DAI_ADDR, opt.fundAddr);
        } catch (Exception e) {
            return "补差:主钱包余额查不到(" + shortMsg(e) + "),";
        }
        if (fundBal == null || fundBal.compareTo(need.add(gasReserve)) < 0) {
            return "补差:主钱包 DAI 不足(需 " + fmtDai(need) + " 有 " + fmtDai(fundBal) + "),";
        }
        ECKeyPair fundPair;
        try {
            String plainPk = ksMgr.decryptCipherText(fund.cipherText, sessionKey);
            fundPair = Web3AndroidUtil.pkToKeyPair(plainPk);
            plainPk = null;
        } catch (Exception e) {
            return "补差:主钱包解密失败(" + shortMsg(e) + "),";
        }
        try {
            cb.onLog(HelixContractApi.shortAddr(addr), TAG, "DAI 不足，从主钱包转入 " + fmtDai(need) + " DAI");
            return sendStep(ctx, cb, web3j, opt.fundAddr, fundPair, "补差 " + fmtDai(need),
                    HelixConfig.GAS_LIMIT_ERC20, HelixConfig.DAI_ADDR,
                    HelixContractApi.encodeTransfer(addr, need), nonceMap, gasPrice);
        } finally {
            fundPair = null;
        }
    }

    // endregion

    // region 归集

    /**
     * 把各子号剩余的 AiLGNS 全部转到主钱包。
     *
     * <p>单独走一轮而不是混在每号流程里：归集必须在所有激活之后，不然转走了本金就没得激活。
     * 主钱包自己不动（它既是收款方也是这一笔的付款方，转给自己只是白付 gas）。</p>
     */
    private static void aggregateAll(Context ctx, TaskLogCallback cb, Web3j web3j, List<KeyListItem> selected,
                                     char[] sessionKey, Options opt, Map<String, BigInteger> nonceMap,
                                     KeyStoreManager ksMgr, BigInteger gasPrice) {
        String fund = opt.fundAddr.toLowerCase();
        BigInteger total = BigInteger.ZERO;
        int moved = 0;
        for (KeyListItem item : selected) {
            if (Web3AndroidUtil.isCancelRequested()) {
                cb.onLog("", TAG, "🛑 取消归集，已转 " + moved + " 个号");
                break;
            }
            if (item.address == null || item.cipherText == null || item.cipherText.isEmpty()) continue;
            String addr = item.address.toLowerCase();
            if (addr.equals(fund)) continue;
            String keyNo = keyNoOf(item);
            BigInteger bal = safeBalance(web3j, HelixConfig.AILGNS_ADDR, addr);
            if (bal == null) {
                cb.onLog(keyNo, TAG, "归集:AiLGNS 余额查不到,跳过");
                continue;
            }
            if (bal.signum() == 0) continue;
            ECKeyPair pair = null;
            try {
                String plainPk = ksMgr.decryptCipherText(item.cipherText, sessionKey);
                pair = Web3AndroidUtil.pkToKeyPair(plainPk);
                plainPk = null;
                if (!Web3AndroidUtil.getAddress(pair).toLowerCase().equals(addr)) {
                    cb.onLog(keyNo, TAG, "归集:密钥与表里的地址不匹配,跳过（不替它签名）");
                    continue;
                }
                String r = sendStep(ctx, cb, web3j, addr, pair, "归集 " + fmtAi(bal),
                        HelixConfig.GAS_LIMIT_ERC20, HelixConfig.AILGNS_ADDR,
                        HelixContractApi.encodeTransfer(opt.fundAddr, bal), nonceMap, gasPrice);
                cb.onLog(keyNo, TAG, r);
                if (r.contains("已上链")) {
                    total = total.add(bal);
                    moved++;
                }
            } catch (Exception e) {
                cb.onLog(keyNo, TAG, "✖ 归集失败:" + msgOf(e));
            } finally {
                pair = null;
            }
        }
        cb.onLog("", TAG, "归集完成 · " + moved + " 个号共 " + fmtAi(total) + " AiLGNS → "
                + HelixContractApi.shortAddr(opt.fundAddr));
    }

    // endregion
}
