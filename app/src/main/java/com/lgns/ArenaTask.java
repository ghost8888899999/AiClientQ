package com.lgns;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;
import com.google.gson.Gson;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArenaTask {
    public static final int MODE_FLEXIBLE = 0;
    public static final int MODE_TOGETHER = 1;
    public static final BigInteger ENTRY_FEE = ArenaConfig.ENTRY_FEE;
    public static final BigInteger MAX_JIAZI_BATCH = ArenaConfig.MAX_JIAZI_BATCH;
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;
    public static final long PENDING_WARN_THRESHOLD_MS = 60_000L;
    public static final Set<String> enterFailedAddrSet = ConcurrentHashMap.newKeySet();

    private static final Pattern TX_HASH_PATTERN = Pattern.compile("(0x[0-9a-fA-F]{64})");

    private static final Gson gson = new Gson();
    private static final String SP_TASK_PROGRESS = "task_progress";

    /** 全类内部统一用它拿异常消息,防 e.getMessage() NPE */
    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    private static SharedPreferences getTaskSp(Context ctx) {
        return ctx.getSharedPreferences(SP_TASK_PROGRESS, Context.MODE_PRIVATE);
    }

    private static void saveAccountState(Context ctx, String accountAddr, TaskAccountState state) {
        if (state.getStatus() == TaskAccountState.STATUS_SUCCESS || state.getStatus() == TaskAccountState.STATUS_FAIL) {
            return;
        }
        SharedPreferences sp = getTaskSp(ctx);
        String json = gson.toJson(state);
        sp.edit().putString("task_" + accountAddr.toLowerCase(), json).apply();
    }

    private static TaskAccountState getAccountState(Context ctx, String accountAddr) {
        SharedPreferences sp = getTaskSp(ctx);
        String json = sp.getString("task_" + accountAddr.toLowerCase(), null);
        if (json == null) {
            return new TaskAccountState();
        }
        TaskAccountState st = gson.fromJson(json, TaskAccountState.class);
        if (st == null) {
            return new TaskAccountState();
        }
        // 跨链残留的 pending 一律丢弃
        if (st.getChainId() != 0 && st.getChainId() != ArenaConfig.CHAIN_ID) {
            sp.edit().remove("task_" + accountAddr.toLowerCase()).apply();
            return new TaskAccountState();
        }
        if (st.getStatus() != TaskAccountState.STATUS_IDLE && st.getStatus() != TaskAccountState.STATUS_TX_SENT) {
            st = new TaskAccountState();
        }
        return st;
    }

    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
    }

    public static void resetTaskProgress(Context ctx) {
        resetAllTaskProgress(ctx);
    }

    /**
     * 处理单个账号入场,传入本轮任务的 nonce 内存 map
     * 广播前先落 SP(rawTx+txHash) → 广播 → 等回执 → 清 SP
     * pending 超 5min + nonce 已消费 → 判 consumed 清状态
     * IOException 透传给上层做 RPC 轮转;其它 Exception 仍本地吞掉返回 false
     */
    private static boolean processEnterOneResume(Context ctx, Web3j web3j, String privateKey, String keyNo,
                                                 TaskLogCallback logCallback, HashMap<String, BigInteger> accountLocalNonceMap)
            throws java.io.IOException {
        ECKeyPair keyPair = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            String fromAddr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
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
                        return true;
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "✖ [" + fromAddr.substring(0, 10) + "...] 历史入场交易reverted,重置状态准备重执行");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else if (elapsed > ArenaConfig.PENDING_STALE_SEC * 1000L) {
                    // 超 5min 未回执 + nonce 已消费 → 判 nonce-consumed
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
                    return true;
                } else {
                    if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场pending已超过60s,交易仍在节点池中,请观察链上状态,本轮跳过");
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场交易在节点Pending池中,等待打包,本轮跳过");
                    }
                    return true;
                }
            }

            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                logCallback.onLog(keyNo, "ENTER", "➖ [" + fromAddr.substring(0, 10) + "...] 合约查询已经入座,跳过入场");
                return true;
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
            } else {
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }

            return txHash != null && !txHash.isEmpty();
        } catch (java.io.IOException ioEx) {
            // B7 · 让网络层 IOException 穿透,由 batchEnterAllResume 里 rpcTry 循环处理
            throw ioEx;
        } catch (Exception e) {
            e.printStackTrace();
            logCallback.onLog(keyNo, "ENTER", "✖ 【入场包装异常】:" + e.getClass().getSimpleName() + " | " + msgOf(e));
            return false;
        } finally {
            keyPair = null;
        }
    }

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
        // 每次批量任务开始重置取消位
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();
        enterFailedAddrSet.clear();
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();

        // web3j 一定要在 finally 里 shutdown,防异常路径泄漏 OkHttp 线程池
        Web3j web3j = null;
        try {
            // 起手 dialAny:并行探测 3 节点,首个 chainId 对+合约就绪的胜出
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "ENTER", "\n--------开始批量入场【断点续跑版】 mode="
                    + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打")
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID + "--------");

            // MODE_TOGETHER 干跑预览,任一失败整批拒发
            if (playMode == MODE_TOGETHER) {
                callback.onLog("", "ENTER", "🔍 等齐再打模式:开始模拟预览");
                List<String> rejects = new ArrayList<>();
                for (KeyListItem item : selectedList) {
                    String keyNo = (item.userGroupNo != null && !item.userGroupNo.isEmpty())
                            ? item.userGroupNo : item.displayNo;
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

            int successCount = 0, skipCount = 0, failCount = 0;
            List<String> failNoList = new ArrayList<>();
            int total = selectedList.size();

            for (int i = 0; i < selectedList.size(); i++) {
                // 每账号起手检查取消位
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "ENTER", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.userGroupNo != null && !item.userGroupNo.isEmpty())
                        ? item.userGroupNo : item.displayNo;
                callback.onLog(keyNo, "ENTER", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");

                String plainPk;
                String fromAddr = null;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair tempPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(tempPair).toLowerCase();
                    tempPair = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "ENTER", "✖ 解密失败:" + msgOf(ex));
                    failCount++;
                    failNoList.add(keyNo);
                    continue;
                }

                // IOException 走节点轮转,同账号最多再试 RPC_URLS.length 次
                boolean ok = false;
                for (int rpcTry = 0; rpcTry < ArenaConfig.RPC_URLS.length; rpcTry++) {
                    try {
                        ok = processEnterOneResume(ctx, web3j, plainPk, keyNo, callback, accountLocalNonceMap);
                        break;
                    } catch (java.io.IOException ioEx) {
                        callback.onLog(keyNo, "ENTER", "⚠ 网络异常(" + (rpcTry + 1) + "/"
                                + ArenaConfig.RPC_URLS.length + "):" + msgOf(ioEx) + ",轮换 RPC 重试");
                        Web3j next = Web3AndroidUtil.rotateWeb3j(web3j);
                        if (next == web3j) {
                            break;
                        }
                        web3j = next;
                    } catch (Exception e) {
                        callback.onLog(keyNo, "ENTER", "✖ 非 IOException 兜底:" + msgOf(e));
                        ok = false;
                        break;
                    }
                }

                if (ok) {
                    skipCount++;
                } else {
                    failCount++;
                    failNoList.add(keyNo);
                    if (fromAddr != null) enterFailedAddrSet.add(fromAddr);
                }
            }

            String failNosStr = String.join(",", failNoList);
            String enterSummary = "--------断点入场结束:成功=" + successCount + ",跳过=" + skipCount + ",失败=" + failCount
                    + " ｜入场失败私钥编号:" + failNosStr + " --------";
            callback.onLog("", "ENTER", enterSummary);
        } catch (java.io.IOException dialFail) {
            // dialAny 三节点全灭 → 整批终止
            callback.onLog("", "ENTER", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            // 无论成功/异常/取消,一定要 shutdown
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 改造:外部传入起始 nonce;返回消耗完成之后的 nextNonce
     * approve/enterArena 广播前把 rawTx+txHash 落 SP
     */
    private static Pair<String, BigInteger> enterOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                    Web3j web3j, ECKeyPair keyPair, BigInteger nonce) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        String enterData = org.web3j.abi.FunctionEncoder.encode(
                new org.web3j.abi.datatypes.Function("enterArena",
                        new java.util.ArrayList<>(), new java.util.ArrayList<>()));
        BigInteger estGas = Web3AndroidUtil.estimateGasWithCeiling(web3j, addr, ArenaConfig.ARENA_ADDR, enterData);
        BigInteger nativeBalance = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return new Pair<>(String.format("%s... 跳过:原生币不足,差约 %.4f", addr.substring(0, 10), diffEth), nonce);
        }
        ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, addr);
        BigInteger walletBalance = summary.walletBalance;
        BigInteger allowance = summary.allowance;
        BigInteger prize = summary.prize;
        BigInteger entriesRemainingToday = summary.entriesRemainingToday;
        String payDesc;
        boolean needApprove = false;
        if (prize.compareTo(ENTRY_FEE) >= 0) {
            payDesc = "参与游戏(奖金抵扣)";
        } else {
            if (walletBalance.compareTo(ENTRY_FEE) >= 0) {
                payDesc = "参与游戏(钱包扣币)";
                if (allowance.compareTo(ENTRY_FEE) < 0) {
                    needApprove = true;
                }
            } else {
                double prizeD = Convert.fromWei(prize.toString(), Convert.Unit.ETHER).doubleValue();
                double walletD = Convert.fromWei(walletBalance.toString(), Convert.Unit.ETHER).doubleValue();
                return new Pair<>(String.format("%s... 跳过:奖金 %.2f、钱包 %.2f 都不足100", addr.substring(0, 10), prizeD, walletD), nonce);
            }
        }
        BigInteger currentNonce = nonce;
        if (needApprove) {
            ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, currentNonce, gasPrice,
                    (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
            currentNonce = currentNonce.add(BigInteger.ONE);
            Thread.sleep(2000);
        }
        TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, currentNonce, gasPrice, estGas,
                (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                        new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
        String txHash = enterReceipt.getTransactionHash();
        String logText = String.format("%s... 成功 %s (%s)", addr.substring(0, 10), payDesc, txHash);
        return new Pair<>(logText, currentNonce.add(BigInteger.ONE));
    }

    /**
     * 改造领取函数:外部传入 nonce,返回结果+消耗后的 nextNonce
     * 广播前把 rawTx+txHash 落 SP(每笔 claimXxx 都独立落,便于崩溃恢复)
     * msgOf 兜底防 NPE
     */
    private static Pair<ClaimOneResult, BigInteger> claimOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                            Web3j web3j, ECKeyPair keyPair,
                                                                            BigInteger startNonce, int index) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger currentNonce = startNonce;
        StringBuilder logSb = new StringBuilder();
        BigInteger jiaziTotal = ArenaContractApi.callJiaziCount(web3j, addr);
        BigInteger claimedJ = BigInteger.ZERO;
        if (jiaziTotal.compareTo(BigInteger.ZERO) > 0) {
            for (BigInteger start = BigInteger.ZERO; start.compareTo(jiaziTotal) < 0; start = start.add(MAX_JIAZI_BATCH)) {
                BigInteger cnt = MAX_JIAZI_BATCH.min(jiaziTotal.subtract(start));
                ArenaContractApi.doClaimJiazi(web3j, keyPair, start, cnt, currentNonce, gasPrice,
                        (h, raw) -> saveAccountState(ctx, fromAddrLower,
                                new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
                currentNonce = currentNonce.add(BigInteger.ONE);
                claimedJ = claimedJ.add(cnt);
                Thread.sleep(1000);
            }
        }
        logSb.append("甲子:领了 ").append(claimedJ).append(" | ");
        try {
            ArenaContractApi.doClaimLineage(web3j, keyPair, currentNonce, gasPrice,
                    (h, raw) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
            currentNonce = currentNonce.add(BigInteger.ONE);
            logSb.append("师徒:已领 | ");
            Thread.sleep(1000);
        } catch (Exception e) {
            String m = msgOf(e);
            logSb.append("师徒失败:").append(m.substring(0, Math.min(50, m.length()))).append(" | ");
        }
        try {
            ArenaContractApi.doClaimRewards(web3j, keyPair, currentNonce, gasPrice,
                    (h, raw) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, h, raw)));
            currentNonce = currentNonce.add(BigInteger.ONE);
            logSb.append("擂台:已领");
        } catch (Exception e) {
            String m = msgOf(e);
            logSb.append("擂台失败:").append(m.substring(0, Math.min(50, m.length())));
        }
        Integer matchesToday = ArenaContractApi.callActivityOfMatchesToday(web3j, addr);
        Integer wendToday = ArenaContractApi.callPlayerHistoryLatestWendToday(web3j, addr);
        logSb.append(" | 第").append(index).append("个号");
        if (wendToday == null) {
            logSb.append(" | 当日问鼎:查询失败");
        } else {
            logSb.append(" | 当日问鼎:").append(wendToday).append("次");
        }
        if (matchesToday != null) {
            logSb.append(" | 今日入场:").append(matchesToday);
        }
        String finalLog = addr.substring(0, 10) + "... " + logSb;
        ClaimOneResult cr = new ClaimOneResult(finalLog, wendToday);
        return new Pair<>(cr, currentNonce);
    }

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

            int retryCount = 0;
            BigInteger remainJiazi;
            do {
                remainJiazi = ArenaContractApi.callJiaziCount(web3j, Web3AndroidUtil.getAddress(keyPair));
                retryCount++;
                if (remainJiazi.compareTo(BigInteger.ZERO) > 0) {
                    callback.onLog(keyNo, "CLAIM", "⚠️校验发现仍有可领取甲子:" + remainJiazi + " ,准备重试(" + retryCount + "/" + maxRetryPerAccount + ")");
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
            } while (remainJiazi.compareTo(BigInteger.ZERO) > 0 && retryCount < maxRetryPerAccount);
            if (remainJiazi.compareTo(BigInteger.ZERO) > 0) {
                callback.onLog(keyNo, "CLAIM", "❌该账号重试耗尽,仍然存在未领取甲子:" + remainJiazi + ",需要人工处理");
            }
        } finally {
            keyPair = null;
        }
    }

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
            callback.onLog("", "CLAIM", "\n--------开始批量领取【断点续跑版】 mode="
                    + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打")
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID + "--------");

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
                String keyNo = (item.userGroupNo != null && !item.userGroupNo.isEmpty())
                        ? item.userGroupNo : item.displayNo;
                callback.onLog(keyNo, "CLAIM", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");

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

                if (enterFailedAddrSet.contains(fromAddr)) {
                    claimSkipCount++;
                    callback.onLog(keyNo, "CLAIM", "➖ 该账号入场执行失败,跳过领取操作");
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
                        boolean txOk = "0x1".equalsIgnoreCase(receipt.getStatus());
                        if (txOk) {
                            saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                            callback.onLog(keyNo, "CLAIM", "✔ [" + fromAddr.substring(0, 10) + "...]历史领取交易确认完成");
                            claimSuccessCount++;
                            continue;
                        } else {
                            callback.onLog(keyNo, "CLAIM", "✖ [" + fromAddr.substring(0, 10) + "...]历史领取交易reverted,重置状态准备重执行");
                            saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        }
                    } else if (elapsed > ArenaConfig.PENDING_STALE_SEC * 1000L) {
                        // 超 5min 未回执 + nonce 已消费 → 判 nonce-consumed
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

                BigInteger remainJiazi = ArenaContractApi.callJiaziCount(web3j, fromAddr);
                if (remainJiazi.compareTo(BigInteger.ZERO) <= 0) {
                    callback.onLog(keyNo, "CLAIM", "➖ [" + fromAddr.substring(0, 10) + "...] 链上查询:无待领取甲子奖励,跳过");
                    claimSkipCount++;
                    continue;
                }

                // IOException 轮转重试
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
                        callback.onLog(keyNo, "CLAIM", "✖ 账号领取整体失败:" + msgOf(e));
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
            String claimSummary = "--------断点批量领取结束:成功=" + claimSuccessCount
                    + ",跳过=" + claimSkipCount
                    + ",失败=" + claimFailCount
                    + " ｜领取失败私钥编号:" + claimFailNos + " --------";
            callback.onLog("", "CLAIM", claimSummary);
        } catch (java.io.IOException dialFail) {
            callback.onLog("", "CLAIM", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry, int playMode,
                                               List<KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        if (Web3AndroidUtil.isCancelRequested()) {
            callback.onLog("", "", "🛑 用户取消,跳过领取阶段");
            enterFailedAddrSet.clear();
            return;
        }
        batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        enterFailedAddrSet.clear();
    }

    public static void batchEnterAll(Context ctx, TaskLogCallback callback) throws Exception { /* 保留原版 */ }

    public static class ClaimOneResult {
        public String logText;
        public Integer wendToday;

        public ClaimOneResult(String logText, Integer wendToday) {
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }
}