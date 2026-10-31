package com.lgns.batch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;
import com.google.gson.Gson;
import com.lgns.manager.AppGlobal;
import com.lgns.model.ArenaConfig;
import com.lgns.contract.ArenaContractApi;
import com.lgns.contract.BlockscoutApi;
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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批量领取任务：对选中的启用账号逐个领取「甲子俸 / 师徒分成 / 擂台奖金」三类收益。
 *
 * <p>职责边界：只负责领取，不参与入场与充值；所有链上调用统一走 {@link ArenaContractApi}，
 * 本类只做串行调度、进度落盘与失败归因，不涉及 UI。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>串行执行 + 逐账号落盘，进程被回收后可由 {@code batchClaimAllResume} 续跑。</li>
 *   <li>revert 类失败会在冷却表里记 10 分钟，避免对同一账号反复提交白烧手续费。</li>
 *   <li>交易长时间 pending 只告警不重发，防止同一 nonce 重复上链造成双花假象。</li>
 * </ul>
 */
public class BatchClaimTask {
    // region 常量与运行状态

    /** 交易在节点池超过 60s 未打包就告警一次，超时通常意味着节点拥塞或 gasPrice 偏低 */
    private static final long PENDING_WARN_THRESHOLD_MS = 60_000L;

    /**
     * 单笔 {@code claimJiazi(from,count)} 的页大小，与合约 {@code MAX_JIAZI_BATCH = 64} 一致。
     * 甲子俸按序号连续释放，一页 64 个；页大小超过上限会被合约直接拒绝。
     * 一个账号拿到 200 个甲子时，需要 4 笔才能领完。
     */
    private static final BigInteger MAX_JIAZI_BATCH = ArenaConfig.MAX_JIAZI_BATCH;

    /** 地址 → 最近一次 revert 时刻。领取 revert 多为「今天没得领」，10 分钟内不再重复提交 */
    private static final Map<String, Long> revertedCooldownMap = new HashMap<>();
    private static final long REVERTED_COOLDOWN_MS = 10 * 60 * 1000L;

    private static final Gson gson = new Gson();

    /** 账号级进度所在的 SharedPreferences 文件名，条目 key 为 {@code task_<地址小写>} */
    private static final String SP_TASK_PROGRESS = "task_progress";

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    /**
     * 这次 revert 是不是「把 gas 上限吃光」的 OOG，而不是「这一页没得领」的正常 revert。
     *
     * <p>OOG 时回执的 gasUsed 恰好等于发交易时挂的上限；NothingToClaim 只消耗真实计算量，
     * 远低于上限。两者必须在日志里分开：前者意味着 {@code GAS_LIMIT_CLAIM_JIAZI}
     * 盖不住俸禄条数（旧版 300k 对 ≥10 笔俸禄的号必 OOG，却被记成「未释放」），
     * 后者才是正常结果。</p>
     */
    private static boolean isOutOfGas(Exception e) {
        if (!(e instanceof ArenaContractApi.RevertedTxException)) return false;
        java.math.BigInteger used = ((ArenaContractApi.RevertedTxException) e).gasUsed;
        return used != null && used.compareTo(ArenaConfig.GAS_LIMIT_CLAIM_JIAZI) >= 0;
    }

    /**
     * catch 之后重新对齐 nonce。
     *
     * <p>领取链路的失败有两种，对 nonce 的影响相反：交易已上链但 revert（nonce 被消耗）、
     * 广播根本没发出去（nonce 未消耗）。本地猜任何一种都会让后面的交易卡住，
     * 所以一律回链上取一次；取不到时按「已消耗」处理——revert 才是这条链路的主要失败形态。</p>
     */
    private static BigInteger resyncNonce(Web3j web3j, String addr, BigInteger fallback) {
        try {
            return web3j.ethGetTransactionCount(addr, DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
        } catch (Exception e) {
            return fallback.add(BigInteger.ONE);
        }
    }

    /**
     * 师徒分成的解锁说明：今日场次不足 10 场时补一句，把「为什么领得少」讲清楚。
     *
     * <p>合约按 10%/场线性解锁，且只认「今天」的场次：打 3 场就只能领三成，
     * 当天不补齐，昨天那份到 D+2 就被烧掉了。返回空串表示已打满或场次未知，不必附带说明。</p>
     */
    private static String lineageUnlockHint(Integer matchesToday) {
        int full = ArenaConfig.FULL_ACTIVITY_MATCHES;
        if (matchesToday == null || matchesToday >= full) return "";
        return "(今日入场 " + matchesToday + "/" + full + "，仅解锁 " + (matchesToday * 100 / full) + "%)";
    }

    /**
     * 18 位小数的金额转可读 DAI（截断 4 位）。
     *
     * <p>日志里直接打 wei 会出现一串 18 位数字，用户读不出「这是 3 块还是 300 块」。</p>
     */
    private static String fmtDai(BigInteger wei) {
        if (wei == null) return "0";
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 4, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    // endregion

    // region 任务进度持久化

    private static SharedPreferences getTaskSp(Context ctx) {
        return ctx.getSharedPreferences(SP_TASK_PROGRESS, Context.MODE_PRIVATE);
    }

    private static void saveAccountState(Context ctx, String accountAddr, TaskAccountState state) {
        if (state.getStatus() == TaskAccountState.STATUS_SUCCESS || state.getStatus() == TaskAccountState.STATUS_FAIL) return;
        SharedPreferences sp = getTaskSp(ctx);
        String json = gson.toJson(state);
        sp.edit().putString("task_" + accountAddr.toLowerCase(), json).apply();
    }

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

    // endregion

    // region 批量领取入口

    /**
     * 批量领取入口（可续跑）。
     *
     * <p>单个账号的判定顺序固定，前面命中就直接跳过，不进入发交易阶段：</p>
     * <ol>
     *   <li>revert 冷却期内的账号不领；</li>
     *   <li>磁盘上有 TX_SENT 记录：先查回执，成功只清状态、继续检查另外两类，reverted 才重执行；</li>
     *   <li>甲子 / 擂台 / 师徒三笔都查不出待领额度时跳过；</li>
     *   <li>以上都不命中，交给 {@link #batchClaimAndVerifyAllSingleAccount} 真正领取。</li>
     * </ol>
     *
     * <p><b>入场失败的号不再跳过领取</b>（以前 {@code enterFailedAddrSet} 命中就直接
     * {@code continue}）。失败只说明这一场没打进去，而它昨天攒的师徒分成与擂台奖金并不会因此消失：
     * 分成按「今天的场次」解锁，当天不补齐到 D+2 就被合约烧掉。入口失败恰恰是最需要把钱收回来的时候。
     * 现在只打一行提示，后面三个 0 额度判断照样会帮它省下无意义的 gas。</p>
     */
    public static void batchClaimAllResume(Context ctx, TaskLogCallback callback, int playMode,
                                           List<KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "CLAIM", "⚠没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "CLAIM", "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "CLAIM", "开始批量领取 · mode="
                    + (playMode == BatchEnterTask.MODE_FLEXIBLE ? "能打就打" : "等齐再打")
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID);

            int claimSuccessCount = 0, claimSkipCount = 0, claimFailCount = 0;
            List<String> claimFailNoList = new ArrayList<>();
            int total = selectedList.size();

            for (int i = 0; i < selectedList.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "CLAIM", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                        ? item.customCode : item.displayNo;
                callback.onLog(keyNo, "CLAIM", "处理账号 " + (i + 1) + "/" + total);

                String plainPk;
                String fromAddr;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair pair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(pair).toLowerCase();
                    pair = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "CLAIM", "✖ 解密失败:" + msgOf(ex));
                    claimFailCount++;
                    claimFailNoList.add(keyNo);
                    continue;
                }

                // 入场失败不等于没东西可领：这一场没打进去，但它昨天攒的师徒分成、
                // 擂台奖金、以及可能已释放的甲子都还在。只打一行提示，不跳过——
                // 分成按今天的场次解锁，当天不补齐到 D+2 就被合约烧掉。
                if (BatchEnterTask.enterFailedAddrSet.contains(fromAddr)) {
                    callback.onLog(keyNo, "CLAIM", "ℹ 该账号本次入场失败，但奖金/甲子/分成照常领取");
                }

                Long revertTs = revertedCooldownMap.get(fromAddr);
                if (revertTs != null && System.currentTimeMillis() - revertTs < REVERTED_COOLDOWN_MS) {
                    long remainMin = (REVERTED_COOLDOWN_MS - (System.currentTimeMillis() - revertTs)) / 60000;
                    callback.onLog(keyNo, "CLAIM", "🚫 [" + fromAddr.substring(0, 10) + "...] reverted冷却中(" + remainMin + "min),跳过领取");
                    claimSkipCount++;
                    continue;
                }

                TaskAccountState diskState = getAccountState(ctx, fromAddr);
                if (diskState.getStatus() == TaskAccountState.STATUS_TX_SENT && diskState.getTxHash() != null) {
                    long elapsed = System.currentTimeMillis() - diskState.getTimestamp();
                    callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...] 查询历史领取交易回执 txHash:" + diskState.getTxHash());
                    EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(diskState.getTxHash()).send();
                    Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                    TransactionReceipt receipt = optReceipt.orElse(null);
                    if (receipt != null) {
                        // 不管成功还是 revert，链上都已消耗过一个 nonce，本地那份可能还是旧值；
                        // 从表里摘掉，后面真要发交易时由 batchClaimAndVerifyAllSingleAccount 重新取 PENDING
                        accountLocalNonceMap.remove(fromAddr);
                        boolean txOk = "0x1".equalsIgnoreCase(receipt.getStatus());
                        if (txOk) {
                            // 成功的只是「历史那一笔」，不代表这个号领完了：甲子/师徒/擂台各自落一次
                            // TX_SENT，磁盘上只留着最后一笔的 txHash。以前这里 claimSuccessCount++
                            // 然后 continue，等于把另外两类一并判成已完成 —— 崩在「师徒成功、擂台还没发」
                            // 之间的账号，下一轮看到师徒回执就整号跳过，擂台那 100.8 白少领一轮。
                            // 现在只清状态，落回下面的三额度判断，缺哪类补哪类。
                            saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                            callback.onLog(keyNo, "CLAIM", "✔ [" + fromAddr.substring(0, 10) + "...]历史领取交易确认完成,继续检查其余待领");
                        } else {
                            callback.onLog(keyNo, "CLAIM", "✖ [" + fromAddr.substring(0, 10) + "...]历史领取交易reverted,重置状态准备重执行");
                            saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        }
                    } else if (elapsed > ArenaConfig.PENDING_STALE_SEC * 1000L) {
                        BigInteger currentNonce = web3j.ethGetTransactionCount(fromAddr,
                                DefaultBlockParameterName.PENDING).send().getTransactionCount();
                        BigInteger recorded = accountLocalNonceMap.get(fromAddr);
                        boolean consumed = (recorded == null) || currentNonce.compareTo(recorded) > 0;
                        if (consumed) {
                            callback.onLog(keyNo, "CLAIM", "⚠ [" + fromAddr.substring(0, 10) + "...] 超 5min 未回执,nonce="
                                    + currentNonce + " 判 consumed,清状态");
                            saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                            accountLocalNonceMap.put(fromAddr, currentNonce);
                        } else {
                            callback.onLog(keyNo, "CLAIM", "⏳ [" + fromAddr.substring(0, 10) + "...]仍在节点池,保持 TX_SENT");
                        }
                        claimSkipCount++;
                        continue;
                    } else {
                        if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                            callback.onLog(keyNo, "CLAIM", "⏳ [" + fromAddr.substring(0, 10) + "...]领取pending已超过60s,交易仍在节点池中,本轮跳过");
                        } else {
                            callback.onLog(keyNo, "CLAIM", "⏳ [" + fromAddr.substring(0, 10) + "...]领取交易Pending池中,等待打包,本轮跳过");
                        }
                        claimSkipCount++;
                        continue;
                    }
                }

                // 三类待领额度都要看：擂台奖金与甲子俸互不相干，只拿甲子当门槛会把
                // 「从没拿过冠军」的账号整个跳过，它们每输一场累积的 100.8 就永远领不到。
                // 只有三类都为 0 才跳过，省掉三笔必然 revert 的交易。
                //
                // 甲子这一类必须用 jiaziClaimable（此刻真正可领的额度），不能用 jiaziCount：
                // 后者是 _jiazi[owner].length，即「历史上拿过几次冠军」，拿过一次就恒大于 0，
                // 老号于是永远过不了这道门槛，每次领取都把全部分页重发一遍、全部 revert，白烧 gas。
                BigInteger remainJiazi = ArenaContractApi.callPlayerSummary(web3j, fromAddr).jiaziClaimable;
                BigInteger pendingPrize = ArenaContractApi.callClaimable(web3j, fromAddr);
                BigInteger pendingLineage = ArenaContractApi.callLineagePending(web3j, fromAddr);
                if (remainJiazi.signum() <= 0 && pendingPrize.signum() <= 0 && pendingLineage.signum() <= 0) {
                    callback.onLog(keyNo, "CLAIM", "➖ [" + fromAddr.substring(0, 10) + "...] 链上查询:甲子/擂台/师徒均无待领,跳过");
                    claimSkipCount++;
                    continue;
                }

                boolean claimed = false;
                for (int rpcTry = 0; rpcTry < ArenaConfig.RPC_URLS.length; rpcTry++) {
                    try {
                        batchClaimAndVerifyAllSingleAccount(ctx, plainPk, keyNo, web3j, callback, 2, accountLocalNonceMap);
                        claimed = true;
                        break;
                    } catch (java.io.IOException ioEx) {
                        callback.onLog(keyNo, "CLAIM", "⚠ 网络异常(" + (rpcTry + 1) + "/"
                                + ArenaConfig.RPC_URLS.length + "):" + msgOf(ioEx) + ",轮换 RPC 重试");
                        Web3j next = Web3AndroidUtil.rotateWeb3j(web3j);
                        if (next == web3j) break;
                        web3j = next;
                    } catch (Exception e) {
                        String em = msgOf(e);
                        if (em != null && em.contains("reverted")) {
                            revertedCooldownMap.put(fromAddr, System.currentTimeMillis());
                        }
                        callback.onLog(keyNo, "CLAIM", "✖ 账号领取整体失败:" + em);
                        break;
                    }
                }
                if (claimed) {
                    claimSuccessCount++;
                } else {
                    claimFailCount++;
                    claimFailNoList.add(keyNo);
                }
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }

            String claimFailNos = String.join(",", claimFailNoList);
            String claimSummary = "批量领取结束 · 成功=" + claimSuccessCount
                    + " 跳过=" + claimSkipCount
                    + " 失败=" + claimFailCount
                    + (claimFailNos.isEmpty() ? "" : " · 失败编号:" + claimFailNos);
            callback.onLog("", "CLAIM", claimSummary);
        } catch (java.io.IOException dialFail) {
            callback.onLog("", "CLAIM", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            // 降级探测：用浏览器 API 判断「链活着、只是 RPC 网关挂」还是「链真停了」
            Long height = BlockscoutApi.fetchLatestBlockHeight();
            callback.onLog("", "CLAIM", height != null
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

    // region 单号领取

    /**
     * 单号领取 + 回读校验。
     *
     * <p>领完不等于领完：甲子俸一页最多 64 个，序号多的账号需要多笔 claim，
     * 发完再回读一次「还能领多少」，仍有剩余就补，最多 {@code maxRetryPerAccount} 轮。</p>
     *
     * <p>判据必须是 {@code playerSummary.jiaziClaimable}（此刻真正可领的额度），
     * 不能用 {@code jiaziCount}——后者返回的是 {@code _jiazi[owner].length}，
     * 即这个地址历史上一共拿过几次冠军。拿它当判据的话，只要拿过一次冠军就永远大于 0，
     * 于是每个老号每次领取都白跑满重试轮数：每轮再把全部分页发一遍、全部 revert
     * {@code NothingToClaim}，最后还打一条「需要人工处理」，把正常状态说成故障。</p>
     */
    private static void batchClaimAndVerifyAllSingleAccount(Context ctx, String pk, String keyNo, Web3j web3j,
                                                            TaskLogCallback callback, int maxRetryPerAccount,
                                                            HashMap<String, BigInteger> accountLocalNonceMap) throws Exception {
        ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        try {
            String fromAddr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
            BigInteger nonce;
            if (accountLocalNonceMap.containsKey(fromAddr)) {
                nonce = accountLocalNonceMap.get(fromAddr);
            } else {
                nonce = web3j.ethGetTransactionCount(fromAddr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
                accountLocalNonceMap.put(fromAddr, nonce);
            }

            Pair<ClaimOneResult, BigInteger> ret = claimOneAccountOriginal(ctx, fromAddr, web3j, keyPair, nonce, 0);
            ClaimOneResult res = ret.first;
            BigInteger nextNonce = ret.second;
            accountLocalNonceMap.put(fromAddr, nextNonce);
            callback.onLog(keyNo, "CLAIM", res.logText);

            // 判据取 jiaziClaimable（此刻还能领多少），不是 jiaziCount（拿过几次冠军）：
            // 后者只要拿过一次冠军就恒大于 0，会让每个老号每次都白跑满重试轮数
            int retryCount = 0;
            BigInteger remainJiazi = BigInteger.ZERO;
            do {
                try {
                    remainJiazi = ArenaContractApi.callPlayerSummary(web3j, fromAddr).jiaziClaimable;
                } catch (Exception e) {
                    // 账都查不出来就不要再补发交易：重试是给节点同步延迟兜底的，
                    // 查询失败时重发只会继续烧 gas
                    callback.onLog(keyNo, "CLAIM", "⚠️甲子回读失败,不再重试:" + msgOf(e));
                    remainJiazi = BigInteger.ZERO;
                    break;
                }
                retryCount++;
                if (remainJiazi.signum() > 0) {
                    callback.onLog(keyNo, "CLAIM", "⚠️校验发现仍有可领取甲子 " + fmtDai(remainJiazi)
                            + " fLGNS,准备重试(" + retryCount + "/" + maxRetryPerAccount + ")");
                    // 重试要再花 gas：先停一下，让刚确认的回执在轮换到的节点上可见，
                    // 否则重试里那次回读可能还是旧值，白发一笔大概率 revert 的领取
                    Thread.sleep(1500);
                    BigInteger retryNonce = accountLocalNonceMap.get(fromAddr);
                    Pair<ClaimOneResult, BigInteger> retryRet = claimOneAccountOriginal(ctx, fromAddr, web3j, keyPair, retryNonce, 0);
                    res = retryRet.first;
                    nextNonce = retryRet.second;
                    accountLocalNonceMap.put(fromAddr, nextNonce);
                    callback.onLog(keyNo, "CLAIM", res.logText);
                } else {
                    callback.onLog(keyNo, "CLAIM", "✅校验:甲子已经全部领取完毕");
                }
            } while (remainJiazi.signum() > 0 && retryCount < maxRetryPerAccount);
            if (remainJiazi.signum() > 0) {
                // 这里固定补一句 gas 提示：重试耗尽最常见的原因就是 OOG（每轮都撞同一上限），
                // 而单号日志里的 OOG 计数只反映本号，不在这条汇总里重复算一遍
                callback.onLog(keyNo, "CLAIM", "❌重试耗尽,仍有 " + fmtDai(remainJiazi)
                        + " fLGNS 甲子未领,下次点领取会继续补;若上方日志有 OOG 提示,是 gas 上限问题,不是没得领");
            }
        } finally {
            keyPair = null;
        }
    }

    /**
     * 单个账号连续领取三类收益，顺序固定：甲子俸 → 师徒分成 → 擂台奖金。
     *
     * <p>三笔各自 try 独立：师徒分成与擂台奖金在没有可领额度时会 revert，属于正常结果，
     * 记到日志里继续往下走，不能因为它阻断后面的领取。</p>
     *
     * <p>nonce 在本方法内逐笔自增，并把最新值交回调用方，同批多账号不必重复去节点取 nonce。</p>
     */
    private static Pair<ClaimOneResult, BigInteger> claimOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                            Web3j web3j, ECKeyPair keyPair,
                                                                            BigInteger startNonce, int index) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger currentNonce = startNonce;
        StringBuilder logSb = new StringBuilder();
        // 这里要的是「总条数」，只用于决定分页范围；判断「还能领多少」必须用 jiaziClaimable，
        // 两者混用会让老号每次都白跑一遍全部分页，详见 batchClaimAndVerifyAllSingleAccount
        BigInteger jiaziTotal = ArenaContractApi.callJiaziCount(web3j, addr);
        BigInteger claimedJ = BigInteger.ZERO;
        int jiaziRevertBatch = 0;
        int jiaziOogBatch = 0;
        if (jiaziTotal.compareTo(BigInteger.ZERO) > 0) {
            // 甲子俸按序号分批：合约一页最多 64 个，超出必须拆成多笔
            for (BigInteger start = BigInteger.ZERO; start.compareTo(jiaziTotal) < 0; start = start.add(MAX_JIAZI_BATCH)) {
                BigInteger cnt = MAX_JIAZI_BATCH.min(jiaziTotal.subtract(start));
                try {
                    ArenaContractApi.doClaimJiazi(web3j, keyPair, start, cnt, currentNonce, gasPrice,
                            (h, raw) -> saveAccountState(ctx, fromAddrLower,
                                    new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
                    currentNonce = currentNonce.add(BigInteger.ONE);
                    claimedJ = claimedJ.add(cnt);
                } catch (Exception e) {
                    // 这一页一天都还没释放时合约 revert NothingToClaim，属于正常结果而不是失败：
                    // 以前这里不接异常，于是「有冠军但还没打满一天」的账号整轮领取直接崩掉，
                    // 连后面能领的师徒分成和擂台奖金一起丢掉。
                    // 不 break：释放虽然「越靠前的 stipend 越早满」，但前面的页全部领完之后，
                    // 后面的页仍可能有小额可领，逐页试才不会漏。
                    // OOG 单独计数：它不是「没得领」，是 gas 上限不够，日志必须分开说
                    if (isOutOfGas(e)) jiaziOogBatch++;
                    jiaziRevertBatch++;
                    currentNonce = resyncNonce(web3j, addr, currentNonce);
                }
            }
        }
        // claimedJ 累加的是「条数」（每笔 claim 覆盖的 stipend 数），不是金额
        logSb.append("甲子:领了 ").append(claimedJ).append(" 条");
        if (jiaziRevertBatch - jiaziOogBatch > 0) logSb.append("(未释放 ").append(jiaziRevertBatch - jiaziOogBatch).append(" 批)");
        if (jiaziOogBatch > 0) logSb.append(" | ⚠️").append(jiaziOogBatch).append(" 批 OOG:gas 上限盖不住俸禄条数");
        logSb.append(" | ");

        // 师徒分成只结算「已封盘」那天，且按今天的场次比例解锁（10% 一场，打满 10 场才 100%）。
        // 今天一场没打就是 0%，直接发交易必然 revert NothingToClaimLineage——先查一次，
        // 为 0 就跳过：既省 gas，也不会把一个正常结果记成失败。
        // 场次一并提前取：解锁比例就挂在它上面，日志里要把「打了多少 / 解锁多少」说清楚，
        // 否则用户只看到「怎么领得这么少」，却不知道再打几场就能全额。
        Integer matchesToday = ArenaContractApi.callActivityOfMatchesToday(web3j, addr);
        BigInteger lineagePendingAmt;
        try {
            lineagePendingAmt = ArenaContractApi.callLineagePending(web3j, addr);
        } catch (Exception e) {
            // 查不到按「可能有」处理：宁可多发一笔，也不漏领当天就会过期的分成
            lineagePendingAmt = null;
        }
        if (lineagePendingAmt != null && lineagePendingAmt.signum() == 0) {
            logSb.append("师徒:今日无可领").append(lineageUnlockHint(matchesToday)).append(" | ");
        } else {
            try {
                ArenaContractApi.doClaimLineage(web3j, keyPair, currentNonce, gasPrice,
                        (h, raw) -> saveAccountState(ctx, fromAddrLower,
                                new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
                currentNonce = currentNonce.add(BigInteger.ONE);
                logSb.append("师徒:已领").append(lineageUnlockHint(matchesToday)).append(" | ");
            } catch (Exception e) {
                // revert 的交易已经上链，nonce 被消耗；不同步的话下一笔擂台会用同一个 nonce 被节点拒掉
                currentNonce = resyncNonce(web3j, addr, currentNonce);
                String m = msgOf(e);
                logSb.append("师徒失败:").append(m.substring(0, Math.min(50, m.length()))).append(" | ");
            }
        }
        try {
            ArenaContractApi.doClaimRewards(web3j, keyPair, currentNonce, gasPrice,
                    (h, raw) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
            currentNonce = currentNonce.add(BigInteger.ONE);
            logSb.append("擂台:已领");
        } catch (Exception e) {
            currentNonce = resyncNonce(web3j, addr, currentNonce);
            String m = msgOf(e);
            logSb.append("擂台失败:").append(m.substring(0, Math.min(50, m.length())));
        }
        Integer wendToday = ArenaContractApi.callPlayerHistoryLatestWendToday(web3j, addr);
        logSb.append(" | 第").append(index).append("个号");
        if (wendToday == null) {
            logSb.append(" | 今日冠军:查询失败");
        } else {
            logSb.append(" | 今日冠军:").append(wendToday).append("次");
        }
        if (matchesToday != null) {
            logSb.append(" | 今日入场:").append(matchesToday);
        }
        String finalLog = addr.substring(0, 10) + "... " + logSb;
        ClaimOneResult cr = new ClaimOneResult(finalLog, wendToday);
        return new Pair<>(cr, currentNonce);
    }

    // endregion

    // region 结果模型

    /** 单个账号领取的结果：一条现成日志 + 今日夺冠次数（用于回填统计） */
    public static class ClaimOneResult {
        /** 单号领取的中文日志，由任务层直接追加到日志面板 */
        public String logText;
        /** 今日夺冠次数；查询失败为 null，与「0 次」区分 */
        public Integer wendToday;

        /** 纯数据载体：只做字段赋值，不在此做日志格式化 */
        public ClaimOneResult(String logText, Integer wendToday) {
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }

    // endregion

}
