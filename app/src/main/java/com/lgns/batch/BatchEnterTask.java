package com.lgns.batch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;
import com.google.gson.Gson;
import com.lgns.manager.AppGlobal;
import com.lgns.model.ArenaConfig;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.task.TaskLogCallback;
import com.lgns.task.TaskAccountState;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量入场任务：把选中账号按两种模式送进 Arena，并维护可续跑的账号级进度。
 *
 * <p>模式差异：{@code MODE_FLEXIBLE} 让每个账号尽快占到座位，{@code MODE_TOGETHER}
 * 尽量把一批账号压进同一轮，便于集中结算与对账。</p>
 *
 * <p>职责边界：只负责入场与必要的 approve；主号补币在上游 {@link BatchFundTask} 完成，
 * 手续费不足只做跳过与提示，本类不替账号转账。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>入场失败会记入 {@code enterFailedAddrSet}，供完整流程跳过已失败的账号。</li>
 *   <li>一笔入场可能顺带结算多轮（最多 {@code MAX_AUTO_SETTLE}），gas 消耗因此波动很大，
 *       余额检查要用 gasLimit 上限而不是历史值。</li>
 *   <li>手续费币是 ERC20 DAI，原生 value 转账在该链不可用。</li>
 * </ul>
 */
public class BatchEnterTask {
    // region 常量与运行状态

    /** 入场模式：能打就打——每个账号各自入座，不等其它账号，速度优先 */
    public static final int MODE_FLEXIBLE = 0;

    /** 入场模式：等齐再打——先整批模拟校验，全部通过才真实提交，避免只进场一半 */
    public static final int MODE_TOGETHER = 1;

    /**
     * 单席门票 100 fLGNS，与合约 {@code ENTRY_FEE = 100e18} 对齐。
     * 支付顺序由合约决定：奖金余额够 100 时先扣奖金（不产生 ERC20 转账），不够才扣钱包。
     */
    public static final BigInteger ENTRY_FEE = ArenaConfig.ENTRY_FEE;

    /**
     * ERC20 无限授权额度（2^256-1）。
     * 入场要合约从钱包划走 100 fLGNS，授权一次即长期有效，可以省掉每轮一次 approve 交易。
     */
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    /** 账号进度状态码，取值与 {@link TaskAccountState} 一一对应：0 待执行 / 1 交易已广播 / 2 成功 / 3 失败 */
    public static final int STATUS_IDLE = 0;
    /** 交易已广播但未拿到回执：仅此状态会落 SharedPreferences，下次启动可续跑查回执 */
    public static final int STATUS_TX_SENT = 1;
    /** 本账号本轮已成功入场（不落盘，只是本次运行的统计口径） */
    public static final int STATUS_SUCCESS = 2;
    /** 本账号本轮失败（不落盘）：可重试失败进失败名单，后续领取阶段跳过 */
    public static final int STATUS_FAIL = 3;

    /**
     * 交易在节点池停留超过 60s 仍未打包就提示一次。
     * 参考值：正常的 transfer 数秒内出块，超过这个时长通常意味着节点拥塞或 gasPrice 偏低。
     */
    public static final long PENDING_WARN_THRESHOLD_MS = 60_000L;

    /** 本次批量里确定失败的地址，完整流程第 3 步领取前据此跳过，避免给失败账号白烧手续费 */
    public static final Set<String> enterFailedAddrSet = new HashSet<>();

    /**
     * 地址 → 最近一次 revert 的时间戳。
     * revert 属于确定性失败（条件不满足），同一状态下立刻重发只会再失败一次，冷却 10 分钟再试。
     */
    private static final java.util.Map<String, Long> revertedCooldownMap = new HashMap<>();
    private static final long REVERTED_COOLDOWN_MS = 10 * 60 * 1000L;

    /** 从执行日志里抠出 32 字节 txHash，用来判断这一笔是否真的发出了交易 */
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("(0x[0-9a-fA-F]{64})");

    private static final Gson gson = new Gson();

    /** 账号级进度所在的 SharedPreferences 文件名，条目 key 为 {@code task_<地址小写>} */
    private static final String SP_TASK_PROGRESS = "task_progress";

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    // endregion

    // region 任务进度持久化

    private static SharedPreferences getTaskSp(Context ctx) {
        return ctx.getSharedPreferences(SP_TASK_PROGRESS, Context.MODE_PRIVATE);
    }

    /**
     * 落盘账号进度。只允许 IDLE / TX_SENT 进磁盘：
     * 成功与失败是本次运行的结果，没有续跑价值，写进去反而会让下次启动误判成「上一轮没跑完」。
     */
    private static void saveAccountState(Context ctx, String accountAddr, TaskAccountState state) {
        if (state.getStatus() == TaskAccountState.STATUS_SUCCESS || state.getStatus() == TaskAccountState.STATUS_FAIL) return;
        SharedPreferences sp = getTaskSp(ctx);
        String json = gson.toJson(state);
        sp.edit().putString("task_" + accountAddr.toLowerCase(), json).apply();
    }

    /**
     * 读取上次中断时留下的账号进度，并做两项清理：
     * chainId 与当前链不符（换链或升级前旧数据）直接丢弃；状态码越界的脏数据回落到 IDLE。
     */
    private static TaskAccountState getAccountState(Context ctx, String accountAddr) {
        SharedPreferences sp = getTaskSp(ctx);
        String json = sp.getString("task_" + accountAddr.toLowerCase(), null);
        if (json == null) return new TaskAccountState();
        TaskAccountState st = gson.fromJson(json, TaskAccountState.class);
        if (st == null) return new TaskAccountState();
        if (st.getChainId() != 0 && st.getChainId() != ArenaConfig.CHAIN_ID) {
            sp.edit().remove("task_" + accountAddr.toLowerCase()).apply();
            return new TaskAccountState();
        }
        if (st.getStatus() != TaskAccountState.STATUS_IDLE && st.getStatus() != TaskAccountState.STATUS_TX_SENT) {
            st = new TaskAccountState();
        }
        return st;
    }

    /**
     * 清空所有账号的入场进度（含 pending 的 txHash/rawTx）。
     *
     * <p>相当于放弃“可续跑”：清完之后再点批量入场会从头开始，
     * 已发出但未确认的交易不会再被回查（链上仍可能上链，需人工确认）。
     */
    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
    }

    // endregion

    // region 批量入场入口

    /**
     * 批量入场入口（可续跑）。
     *
     * <p>过程分四段：</p>
     * <ol>
     *   <li>{@code MODE_TOGETHER} 先整批 {@code previewEnter} 模拟，任一账号被拒就整批不发；</li>
     *   <li>逐账号解密 → 命中 revert 冷却则跳过 → 交给 {@link #processEnterOneResume} 执行；</li>
     *   <li>单号遇到 IOException 时轮换 RPC 重试，其它异常只记失败不重试；</li>
     *   <li>结束后回读一遍 playerSummary 批量数据，让主界面直接按链上真实状态刷新统计。</li>
     * </ol>
     *
     * <p>串行执行 + 逐账号落盘：中途退出后再次点击会先按磁盘上的 TX_SENT 查回执，
     * 不会重新发一笔，因此重复点击不会造成重复入场。</p>
     */
    public static void batchEnterAllResume(Context ctx, TaskLogCallback callback, int playMode,
                                           List<KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "ENTER", "⚠没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "ENTER", "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();
        enterFailedAddrSet.clear();
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "ENTER", "开始批量入场 · mode="
                    + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打")
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID);

            // 等齐再打：整批先干跑一遍（eth_call 模拟），任一账号不满足条件就整批不发，
            // 避免只打进去一半、另一半因为余额/次数不够被拒
            if (playMode == MODE_TOGETHER) {
                callback.onLog("", "ENTER", "🔍 等齐再打模式:开始模拟预览");
                List<String> rejects = new ArrayList<>();
                for (KeyListItem item : selectedList) {
                    String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    String plainPk;
                    try {
                        plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    } catch (Exception ex) {
                        rejects.add(keyNo + ":解密失败");
                        continue;
                    }
                    ECKeyPair kp = Web3AndroidUtil.pkToKeyPair(plainPk);
                    String addr = Web3AndroidUtil.getAddress(kp).toLowerCase();
                    String reason = ArenaContractApi.previewEnter(web3j, addr, kp);
                    kp = null;
                    if (reason != null) rejects.add(keyNo + ":" + reason);
                    if (Web3AndroidUtil.isCancelRequested()) {
                        callback.onLog("", "ENTER", "🛑 预览阶段被用户取消");
                        return;
                    }
                }
                if (!rejects.isEmpty()) {
                    callback.onLog("", "ENTER", "✖ 预览失败 " + rejects.size() + " 个账号,整批拒发:"
                            + String.join(" / ", rejects));
                    return;
                }
                callback.onLog("", "ENTER", "✅ 预览全通过,开始真实提交");
            }

            // 逐账号串行：账号之间互不依赖，单号失败只进失败名单，不影响后面的账号
            int successCount = 0, skipCount = 0, failCount = 0;
            List<String> failNoList = new ArrayList<>();
            int total = selectedList.size();

            for (int i = 0; i < selectedList.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "ENTER", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                        ? item.customCode : item.displayNo;
                callback.onLog(keyNo, "ENTER", "处理账号 " + (i + 1) + "/" + total);

                String plainPk;
                String fromAddr = null;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair tempPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(tempPair).toLowerCase();
                    tempPair = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "ENTER", "✖ 解密失败:" + msgOf(ex));
                    callback.onKeyFailure(keyNo, "解密失败", msgOf(ex));
                    failCount++;
                    failNoList.add(keyNo);
                    continue;
                }

                Long revertTs = revertedCooldownMap.get(fromAddr);
                if (revertTs != null && System.currentTimeMillis() - revertTs < REVERTED_COOLDOWN_MS) {
                    long remainMin = (REVERTED_COOLDOWN_MS - (System.currentTimeMillis() - revertTs)) / 60000;
                    callback.onLog(keyNo, "ENTER", " [" + fromAddr.substring(0, 10) + "...] reverted冷却中(" + remainMin + "min),跳过");
                    skipCount++;
                    continue;
                }

                // 只有连接类异常才换节点重试：revert / 业务失败换个节点结果一样，重试只会多烧一笔手续费
                int result = ENTER_FAIL;
                for (int rpcTry = 0; rpcTry < ArenaConfig.RPC_URLS.length; rpcTry++) {
                    try {
                        result = processEnterOneResume(ctx, web3j, plainPk, keyNo, callback, accountLocalNonceMap);
                        break;
                    } catch (java.io.IOException ioEx) {
                        callback.onLog(keyNo, "ENTER", "⚠ 网络异常(" + (rpcTry + 1) + "/"
                                + ArenaConfig.RPC_URLS.length + "):" + msgOf(ioEx) + ",轮换 RPC 重试");
                        Web3j next = Web3AndroidUtil.rotateWeb3j(web3j);
                        if (next == web3j) break;
                        web3j = next;
                    } catch (Exception e) {
                        callback.onLog(keyNo, "ENTER", "✖ 非 IOException 兜底:" + msgOf(e));
                        callback.onKeyFailure(keyNo, "执行异常", msgOf(e));
                        result = ENTER_FAIL;
                        break;
                    }
                }

                if (result == ENTER_SUCCESS) {
                    successCount++;
                } else if (result == ENTER_SKIP) {
                    skipCount++;
                } else {
                    failCount++;
                    failNoList.add(keyNo);
                    if (fromAddr != null) enterFailedAddrSet.add(fromAddr);
                    callback.onKeyFailure(keyNo, "入场失败", "RPC重试耗尽或执行失败");
                }
            }

            String failNosStr = String.join(",", failNoList);
            String enterSummary = "批量入场结束 · 成功=" + successCount + " 跳过=" + skipCount + " 失败=" + failCount
                    + (failNosStr.isEmpty() ? "" : " · 失败编号:" + failNosStr);
            callback.onLog("", "ENTER", enterSummary);

            // 收尾回读：把链上最新状态（座位、奖金、今日场次）一次性交给界面，避免界面拿旧数据
            java.util.Map<String, ArenaContractApi.PlayerSummary> summaries = null;
            try {
                java.util.List<String> addrList = new ArrayList<>();
                for (KeyListItem item : selectedList) {
                    if (item.address != null && !item.address.isEmpty()) addrList.add(item.address);
                }
                if (!addrList.isEmpty()) {
                    summaries = ArenaContractApi.callPlayerSummaryBatch(web3j, addrList);
                }
            } catch (Throwable t) {
                callback.onLog("", "ENTER", "⚠查询链上数据异常(不影响入场结果):" + t.getClass().getSimpleName());
            }
            callback.onBatchResult(summaries);
        } catch (java.io.IOException dialFail) {
            callback.onLog("", "ENTER", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region 单号入场

    /**
     * 单号结果：可重试的失败，计入失败名单。
     *
     * <p>三个值依次为：0=失败 / 1=跳过（含已在座、交易在途）/ 2=真正成功（拿到 txHash）。</p>
     */
    private static final int ENTER_FAIL = 0;
    /** 单号结果：本轮不需要再发交易（已在座 / 交易还在路上 / 条件不满足），不算失败 */
    private static final int ENTER_SKIP = 1;
    /** 单号结果：本轮真的发出了一笔入场交易 */
    private static final int ENTER_SUCCESS = 2;

    /**
     * 单号入场（可续跑）。
     *
     * <p>先处理上一次留下的尾巴，再决定要不要发新交易：</p>
     * <ol>
     *   <li>磁盘记录是 TX_SENT：查回执——成功则结束；reverted 则清状态重执行；
     *       长时间无回执则用 PENDING nonce 判断是否已被消费（nonce-consumed）；</li>
     *   <li>链上 {@code seatedInCurrentRound} 已为真 → 跳过，不重复买座；</li>
     *   <li>取 nonce（优先用本批缓存）后进入 {@link #enterOneAccountOriginal} 真实入场；</li>
     *   <li>从日志里抠 txHash 判定是否真发出，没发出就清状态，交给下次重试。</li>
     * </ol>
     */
    private static int processEnterOneResume(Context ctx, Web3j web3j, String privateKey, String keyNo,
                                                 TaskLogCallback logCallback, HashMap<String, BigInteger> accountLocalNonceMap)
            throws java.io.IOException {
        ECKeyPair keyPair = null;
        String fromAddr = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            fromAddr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
            TaskAccountState diskState = getAccountState(ctx, fromAddr);
            if (diskState.getStatus() == TaskAccountState.STATUS_TX_SENT && diskState.getTxHash() != null) {
                long elapsed = System.currentTimeMillis() - diskState.getTimestamp();
                logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 查询历史入场交易回执 txHash:" + diskState.getTxHash());
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(diskState.getTxHash()).send();
                Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                TransactionReceipt receipt = optReceipt.orElse(null);

                if (receipt != null) {
                    boolean txSuccess = "0x1".equalsIgnoreCase(receipt.getStatus());
                    if (txSuccess) {
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        logCallback.onLog(keyNo, "ENTER", "✔ [" + fromAddr.substring(0, 10) + "...] 历史入场交易链上确认成功");
                        return ENTER_SKIP;
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "✖ [" + fromAddr.substring(0, 10) + "...] 历史入场交易reverted,重置状态准备重执行");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else if (elapsed > ArenaConfig.PENDING_STALE_SEC * 1000L) {
                    BigInteger currentNonce = web3j.ethGetTransactionCount(fromAddr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    BigInteger recorded = accountLocalNonceMap.get(fromAddr);
                    boolean consumed = (recorded == null) || currentNonce.compareTo(recorded) > 0;
                    if (consumed) {
                        logCallback.onLog(keyNo, "ENTER", "⚠ [" + fromAddr.substring(0, 10) + "...] 交易 "
                                + diskState.getTxHash().substring(0, 10) + "... 超 5min 未回执且 nonce="
                                + currentNonce + ",判 nonce-consumed,清状态");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        accountLocalNonceMap.put(fromAddr, currentNonce);
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...] 超 5min 仍在节点池,保持 TX_SENT");
                    }
                    return ENTER_SKIP;
                } else {
                    if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场pending已超过60s,交易仍在节点池中,请观察链上状态,本轮跳过");
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场交易在节点Pending池中,等待打包,本轮跳过");
                    }
                    return ENTER_SKIP;
                }
            }

            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                logCallback.onLog(keyNo, "ENTER", "➖ [" + fromAddr.substring(0, 10) + "...] 合约查询已经入座,跳过入场");
                return ENTER_SKIP;
            }

            BigInteger nonce;
            if (accountLocalNonceMap.containsKey(fromAddr)) {
                nonce = accountLocalNonceMap.get(fromAddr);
            } else {
                nonce = web3j.ethGetTransactionCount(fromAddr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
                accountLocalNonceMap.put(fromAddr, nonce);
            }

            Pair<String, BigInteger> ret = enterOneAccountOriginal(ctx, fromAddr, web3j, keyPair, nonce);
            String logLine = ret.first;
            BigInteger nextNonce = ret.second;
            logCallback.onLog(keyNo, "ENTER", logLine);

            String txHash = null;
            Matcher matcher = TX_HASH_PATTERN.matcher(logLine);
            if (matcher.find()) {
                txHash = matcher.group(1);
            }

            if (txHash != null && !txHash.isEmpty()) {
                accountLocalNonceMap.put(fromAddr, nextNonce);
                return ENTER_SUCCESS;
            } else {
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                return ENTER_SKIP;
            }
        } catch (java.io.IOException ioEx) {
            throw ioEx;
        } catch (Throwable t) {
            t.printStackTrace();
            String msg = msgOf(t);
            if (msg != null && msg.contains("reverted")) {
                revertedCooldownMap.put(fromAddr, System.currentTimeMillis());
            }
            logCallback.onLog(keyNo, "ENTER", " 【入场致命异常】:" + t.getClass().getSimpleName() + " | " + msg);
            return ENTER_FAIL;
        } finally {
            keyPair = null;
        }
    }

    /**
     * 真实入场：先做本地可用性判断，再按「奖金 / 钱包」两条支付路径发交易。
     *
     * <p>顺序不能调：</p>
     * <ol>
     *   <li>用固定 gasLimit 估网络费，原生币（本链就是 DAI）不够直接跳过；</li>
     *   <li>读 playerSummary：今日剩余场次为 0 直接跳过（合约 MAX_ENTRIES_PER_DAY = 12）；</li>
     *   <li>奖金 ≥ 100 走奖金抵扣，不动钱包也不用 approve；否则查钱包与 allowance，
     *       不足 100 跳过，够但授权不足时先 approve 无限额度；</li>
     *   <li>发 enterArena 并等回执，日志带 block / nonce / gasUsed 方便对账。</li>
     * </ol>
     */
    private static Pair<String, BigInteger> enterOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                    Web3j web3j, ECKeyPair keyPair, BigInteger nonce) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        String enterData = org.web3j.abi.FunctionEncoder.encode(
                new org.web3j.abi.datatypes.Function("enterArena",
                        new ArrayList<>(), new ArrayList<>()));

        // 使用 ArenaConfig 常量，避免 eth_estimateGas 返回值格式错误。
        // 入场可能顺带结算前面轮次（MAX_AUTO_SETTLE = 1），gas 波动很大，只能用上限估算
        BigInteger estGas = ArenaConfig.GAS_LIMIT_ENTER_ARENA;

        BigInteger nativeBalance = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return new Pair<>(String.format("%s... 跳过:DAI 不足，差约 %.4f DAI", addr.substring(0, 10), diffEth), nonce);
        }
        ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, addr);
        BigInteger walletBalance = summary.walletBalance;
        BigInteger allowance = summary.allowance;
        BigInteger prize = summary.prize;
        BigInteger entriesRemainingToday = summary.entriesRemainingToday;

        // 检查今日是否还能入场
        if (entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) {
            return new Pair<>(String.format("%s... 跳过:今日已打满12场", addr.substring(0, 10)), nonce);
        }

        // 支付优先级与合约一致：奖金余额够就先扣奖金，既不用 approve 也不受钱包余额影响
        String payDesc;
        boolean needApprove = false;
        if (prize.compareTo(ENTRY_FEE) >= 0) {
            payDesc = "参与游戏 (奖金抵扣)";
        } else {
            if (walletBalance.compareTo(ENTRY_FEE) >= 0) {
                payDesc = "参与游戏 (钱包扣币)";
                if (allowance.compareTo(ENTRY_FEE) < 0) {
                    needApprove = true;
                }
            } else {
                double prizeD = Convert.fromWei(prize.toString(), Convert.Unit.ETHER).doubleValue();
                double walletD = Convert.fromWei(walletBalance.toString(), Convert.Unit.ETHER).doubleValue();
                return new Pair<>(String.format("%s... 跳过:奖金 %.2f、钱包 %.2f 都不足 100", addr.substring(0, 10), prizeD, walletD), nonce);
            }
        }
        BigInteger currentNonce = nonce;
        if (needApprove) {
            try {
                ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, currentNonce, gasPrice,
                        (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                                new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
            } catch (Exception approveEx) {
                throw new Exception("approve 失败:" + Web3AndroidUtil.safeMessage(approveEx), approveEx);
            }
            currentNonce = currentNonce.add(BigInteger.ONE);
        }
        try {
            TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, currentNonce, gasPrice, estGas,
                    (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
            String txHash = enterReceipt.getTransactionHash();
            String blockNumber = enterReceipt.getBlockNumber() != null ? enterReceipt.getBlockNumber().toString() : "未知";
            String gasUsed = enterReceipt.getGasUsed() != null ? enterReceipt.getGasUsed().toString() : "未知";
            String logText = String.format("%s... 成功 %s | txHash=%s | block=%s | nonce=%s | gasUsed=%s | gasLimit=%s",
                addr.substring(0, 10), payDesc, txHash, blockNumber, currentNonce.toString(), gasUsed, estGas.toString());
            return new Pair<>(logText, currentNonce.add(BigInteger.ONE));
        } catch (Exception enterEx) {
            String errMsg = Web3AndroidUtil.safeMessage(enterEx);
            if (errMsg.contains("reverted")) {
                throw new Exception("enterArena reverted:" + errMsg, enterEx);
            }
            throw new Exception("enterArena 失败:" + errMsg + " | nonce=" + currentNonce, enterEx);
        }
    }

    // endregion

}
