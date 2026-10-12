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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArenaTask {
    public static final int MODE_FLEXIBLE = 0; //能打就打，过滤不可执行账号
    public static final int MODE_TOGETHER = 1; //等齐再打，全部模拟通过才提交
    public static final BigInteger ENTRY_FEE = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));
    public static final BigInteger MAX_JIAZI_BATCH = BigInteger.valueOf(64);
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;
    public static final long PENDING_WARN_THRESHOLD_MS = 60_000L; //60秒告警，暂不做自动加速
    // 保存本批次入场失败的钱包小写地址，用于领取阶段过滤；每轮任务结束clear，线程安全集合
    public static final Set<String> enterFailedAddrSet = ConcurrentHashMap.newKeySet();

    private static final Gson gson = new Gson();
    private static final String SP_TASK_PROGRESS = "task_progress";

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
     * 处理单个账号入场，传入本轮任务的nonce内存map
     */
    private static boolean processEnterOneResume(Context ctx, Web3j web3j, String privateKey, String keyNo,
                                                 TaskLogCallback logCallback, HashMap<String, BigInteger> accountLocalNonceMap) {
        ECKeyPair keyPair = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            String fromAddr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
            TaskAccountState diskState = getAccountState(ctx, fromAddr);
            // 存在已经提交未确认的交易
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
                        // 交易reverted，彻底失败，重置状态允许重跑
                        logCallback.onLog(keyNo, "ENTER", "✖ [" + fromAddr.substring(0, 10) + "...] 历史入场交易reverted，重置状态准备重执行");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else {
                    // Pending状态：告警，本轮直接跳过，保留TX_SENT，下一轮继续轮询回执；暂不自动加速
                    if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场pending已超过60s，交易仍在节点池中，请观察链上状态，本轮跳过");
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场交易在节点Pending池中，等待打包，本轮跳过");
                    }
                    // 不重置IDLE，保留STATUS_TX_SENT，本轮直接跳过账号
                    return true;
                }
            }

            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                logCallback.onLog(keyNo, "ENTER", "➖ [" + fromAddr.substring(0, 10) + "...] 合约查询已经入座，跳过入场");
                return true;
            }

            // 获取本地nonce，不存在则从RPC PENDING初始化一次
            BigInteger nonce;
            if (accountLocalNonceMap.containsKey(fromAddr)) {
                nonce = accountLocalNonceMap.get(fromAddr);
            } else {
                nonce = web3j.ethGetTransactionCount(fromAddr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
                accountLocalNonceMap.put(fromAddr, nonce);
            }

            // 改造后的入场函数，外部传入nonce
            Pair<String, BigInteger> ret = enterOneAccountOriginal(web3j, keyPair, nonce);
            String logLine = ret.first;
            BigInteger nextNonce = ret.second;
            logCallback.onLog(keyNo, "ENTER", logLine);

            // 正则提取txHash，替代split括号解析
            String txHash = null;
            Pattern pattern = Pattern.compile("(0x[0-9a-fA-F]{64})");
            Matcher matcher = pattern.matcher(logLine);
            if (matcher.find()) {
                txHash = matcher.group(1);
            }

            if (txHash != null && !txHash.isEmpty()) {
                TaskAccountState txSentSt = new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash);
                saveAccountState(ctx, fromAddr, txSentSt);
                accountLocalNonceMap.put(fromAddr, nextNonce);
            } else {
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }

            ArenaContractApi.PlayerSummary afterSummary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            return afterSummary.seatedInCurrentRound;
        } catch (Exception e) {
            e.printStackTrace();
            logCallback.onLog(keyNo, "ENTER", "✖ 【入场包装异常】:" + e.getClass().getSimpleName() + " | " + e.getMessage());
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
            callback.onLog("", "ENTER", "❌密钥管理器未初始化，请等待初始化完成");
            return;
        }
        enterFailedAddrSet.clear();
        // 本轮任务内存维护每个账号nonce
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();

        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("", "ENTER", "\n--------开始批量入场【断点续跑版】 mode=" + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打") + "--------");

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        List<String> failNoList = new ArrayList<>();
        int total = selectedList.size();

        for (int i = 0; i < selectedList.size(); i++) {
            KeyListItem item = selectedList.get(i);
            String keyNo = (item.userGroupNo != null && !item.userGroupNo.isEmpty()) ? item.userGroupNo : item.displayNo;
            callback.onLog(keyNo, "ENTER", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");

            String plainPk;
            String fromAddr = null;
            try {
                plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                ECKeyPair tempPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                fromAddr = Web3AndroidUtil.getAddress(tempPair).toLowerCase();
            } catch (Exception ex) {
                callback.onLog(keyNo, "ENTER", "✖ 解密失败：会话自定义加解密Key与加密该密钥时的Key不匹配，跳过此账号");
                failCount++;
                failNoList.add(keyNo);
                continue;
            }

            boolean ok = processEnterOneResume(ctx, web3j, plainPk, keyNo, callback, accountLocalNonceMap);
            if (ok) {
                ArenaContractApi.PlayerSummary sum = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
                if (sum.seatedInCurrentRound) {
                    skipCount++;
                } else {
                    successCount++;
                }
            } else {
                failCount++;
                failNoList.add(keyNo);
                if (fromAddr != null) {
                    enterFailedAddrSet.add(fromAddr);
                }
            }
        }
        web3j.shutdown();

        String failNosStr = String.join(",", failNoList);
        String enterSummary = "--------断点入场结束：成功=" + successCount + "，跳过=" + skipCount + "，失败=" + failCount
                + " ｜入场失败私钥编号：" + failNosStr + " --------";
        callback.onLog("", "ENTER", enterSummary);
    }

    /**
     * 改造：外部传入起始nonce；返回消耗完成之后的nextNonce
     */
    private static Pair<String, BigInteger> enterOneAccountOriginal(Web3j web3j, ECKeyPair keyPair, BigInteger nonce) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger estGas = BigInteger.valueOf(1600000);
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
        boolean seatedInCurrentRound = summary.seatedInCurrentRound;
        if (seatedInCurrentRound) {
            return new Pair<>(addr.substring(0, 10) + "... 跳过: 当前场已入座", nonce);
        }
        if (entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) {
            return new Pair<>(addr.substring(0, 10) + "... 跳过: 今日已打满12场", nonce);
        }
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
            TransactionReceipt approveReceipt = ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, currentNonce, gasPrice);
            currentNonce = currentNonce.add(BigInteger.ONE);
            Thread.sleep(2000);
        }
        TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, currentNonce, gasPrice);
        String txHash = enterReceipt.getTransactionHash();
        String logText = String.format("%s... 成功 %s (%s)", addr.substring(0, 10), payDesc, txHash);
        return new Pair<>(logText, currentNonce.add(BigInteger.ONE));
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

            Pair<ClaimOneResult, BigInteger> ret = claimOneAccountOriginal(web3j, keyPair, nonce, 0);
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
                    callback.onLog(keyNo, "CLAIM", "⚠️校验发现仍有可领取甲子：" + remainJiazi + " ,准备重试(" + retryCount + "/" + maxRetryPerAccount + ")");
                    Thread.sleep(1500);
                    BigInteger retryNonce = accountLocalNonceMap.get(fromAddr);
                    Pair<ClaimOneResult, BigInteger> retryRet = claimOneAccountOriginal(web3j, keyPair, retryNonce, 0);
                    res = retryRet.first;
                    nextNonce = retryRet.second;
                    accountLocalNonceMap.put(fromAddr, nextNonce);
                    callback.onLog(keyNo, "CLAIM", res.logText);
                } else {
                    callback.onLog(keyNo, "CLAIM", "✅校验：甲子已经全部领取完毕");
                }
            } while (remainJiazi.compareTo(BigInteger.ZERO) > 0 && retryCount < maxRetryPerAccount);
            if (remainJiazi.compareTo(BigInteger.ZERO) > 0) {
                callback.onLog(keyNo, "CLAIM", "❌该账号重试耗尽，仍然存在未领取甲子：" + remainJiazi + "，需要人工处理");
            }
        } finally {
            keyPair = null;
        }
    }

    /**
     * 改造领取函数：外部传入nonce，返回结果+消耗后的nextNonce
     */
    private static Pair<ClaimOneResult, BigInteger> claimOneAccountOriginal(Web3j web3j, ECKeyPair keyPair, BigInteger startNonce, int index) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger currentNonce = startNonce;
        StringBuilder logSb = new StringBuilder();
        BigInteger jiaziTotal = ArenaContractApi.callJiaziCount(web3j, addr);
        BigInteger claimedJ = BigInteger.ZERO;
        if (jiaziTotal.compareTo(BigInteger.ZERO) > 0) {
            for (BigInteger start = BigInteger.ZERO; start.compareTo(jiaziTotal) < 0; start = start.add(MAX_JIAZI_BATCH)) {
                BigInteger cnt = MAX_JIAZI_BATCH.min(jiaziTotal.subtract(start));
                ArenaContractApi.doClaimJiazi(web3j, keyPair, start, cnt, currentNonce, gasPrice);
                currentNonce = currentNonce.add(BigInteger.ONE);
                claimedJ = claimedJ.add(cnt);
                Thread.sleep(1000);
            }
        }
        logSb.append("甲子:领了 ").append(claimedJ).append(" | ");
        try {
            ArenaContractApi.doClaimLineage(web3j, keyPair, currentNonce, gasPrice);
            currentNonce = currentNonce.add(BigInteger.ONE);
            logSb.append("师徒:已领 | ");
            Thread.sleep(1000);
        } catch (Exception e) {
            logSb.append("师徒失败:").append(e.getMessage().substring(0, Math.min(50, e.getMessage().length()))).append(" | ");
        }
        try {
            ArenaContractApi.doClaimRewards(web3j, keyPair, currentNonce, gasPrice);
            currentNonce = currentNonce.add(BigInteger.ONE);
            logSb.append("擂台:已领");
        } catch (Exception e) {
            logSb.append("擂台失败:").append(e.getMessage().substring(0, Math.min(50, e.getMessage().length())));
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
            callback.onLog("", "CLAIM", "❌密钥管理器未初始化，请等待初始化完成");
            return;
        }
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("", "CLAIM", "\n--------开始批量领取【断点续跑版】 mode=" + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打") + "--------");

        int claimSuccessCount = 0;
        int claimSkipCount = 0;
        int claimFailCount = 0;
        List<String> claimFailNoList = new ArrayList<>();
        int total = selectedList.size();

        for (int i = 0; i < selectedList.size(); i++) {
            KeyListItem item = selectedList.get(i);
            String keyNo = (item.userGroupNo != null && !item.userGroupNo.isEmpty()) ? item.userGroupNo : item.displayNo;
            callback.onLog(keyNo, "CLAIM", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");

            String plainPk;
            String fromAddr;
            try {
                plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                ECKeyPair pair = Web3AndroidUtil.pkToKeyPair(plainPk);
                fromAddr = Web3AndroidUtil.getAddress(pair).toLowerCase();
            } catch (Exception ex) {
                callback.onLog(keyNo, "CLAIM", "✖ 解密失败：会话自定义加解密Key与加密该密钥时的Key不匹配，跳过此账号");
                claimFailCount++;
                claimFailNoList.add(keyNo);
                continue;
            }

            if (enterFailedAddrSet.contains(fromAddr)) {
                claimSkipCount++;
                callback.onLog(keyNo, "CLAIM", "➖ 该账号入场执行失败，跳过领取操作");
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
                        callback.onLog(keyNo, "CLAIM", "✖ [" + fromAddr.substring(0, 10) + "...]历史领取交易reverted，重置状态准备重执行");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else {
                    if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                        callback.onLog(keyNo, "CLAIM", "⏳ [" + fromAddr.substring(0, 10) + "...]领取pending已超过60s，交易仍在节点池中，本轮跳过");
                    } else {
                        callback.onLog(keyNo, "CLAIM", "⏳ [" + fromAddr.substring(0, 10) + "...]领取交易Pending池中，等待打包，本轮跳过");
                    }
                    claimSkipCount++;
                    continue;
                }
            }

            BigInteger remainJiazi = ArenaContractApi.callJiaziCount(web3j, fromAddr);
            if (remainJiazi.compareTo(BigInteger.ZERO) <= 0) {
                callback.onLog(keyNo, "CLAIM", "➖ [" + fromAddr.substring(0, 10) + "...] 链上查询：无待领取甲子奖励，跳过");
                claimSkipCount++;
                continue;
            }
            try {
                batchClaimAndVerifyAllSingleAccount(ctx, plainPk, keyNo, web3j, callback, 2, accountLocalNonceMap);
                claimSuccessCount++;
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            } catch (Exception e) {
                callback.onLog(keyNo, "CLAIM", "✖ 账号领取整体失败:" + e.getMessage());
                claimFailCount++;
                claimFailNoList.add(keyNo);
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }
        }
        web3j.shutdown();

        String claimFailNos = String.join(",", claimFailNoList);
        String claimSummary = "--------断点批量领取结束：成功=" + claimSuccessCount
                + "，跳过=" + claimSkipCount
                + "，失败=" + claimFailCount
                + " ｜领取失败私钥编号：" + claimFailNos + " --------";
        callback.onLog("", "CLAIM", claimSummary);
    }

    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry, int playMode,
                                               List<KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        enterFailedAddrSet.clear();
    }

    public static final BigInteger ENTRY_FEE_OLD = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));
    public static void batchEnterAll(Context ctx, TaskLogCallback callback) throws Exception { /*省略，保留原版 */ }

    public static class ClaimOneResult {
        public String logText;
        public Integer wendToday;

        public ClaimOneResult(String logText, Integer wendToday) {
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }
}
