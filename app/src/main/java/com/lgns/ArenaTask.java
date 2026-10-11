package com.lgns;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
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

    private static final Gson gson = new Gson();
    private static final String SP_TASK_PROGRESS = "task_progress";
    private static final String SP_PENDING_RAW_TX = "pending_raw_tx_hex";

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

    private static void savePendingRawTxHex(Context ctx, String rawHex) {
        getTaskSp(ctx).edit().putString(SP_PENDING_RAW_TX, rawHex).apply();
    }

    private static void clearPendingRawTxHex(Context ctx) {
        getTaskSp(ctx).edit().remove(SP_PENDING_RAW_TX).apply();
    }

    private static String getPendingRawTxHex(Context ctx) {
        return getTaskSp(ctx).getString(SP_PENDING_RAW_TX, null);
    }

    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
    }

    public static void resetTaskProgress(Context ctx) {
        resetAllTaskProgress(ctx);
    }

    private static boolean processEnterOneResume(Context ctx, Web3j web3j, String privateKey, String keyNo, TaskLogCallback logCallback) {
        ECKeyPair keyPair = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            String fromAddr = Web3AndroidUtil.getAddress(keyPair);
            TaskAccountState diskState = getAccountState(ctx, fromAddr);

            if (diskState.getStatus() == TaskAccountState.STATUS_TX_SENT && diskState.getTxHash() != null) {
                logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 查询历史入场交易回执 txHash:" + diskState.getTxHash());
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(diskState.getTxHash()).send();
                Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                TransactionReceipt receipt = optReceipt.orElse(null);
                if (receipt != null) {
                    boolean txSuccess = "0x1".equalsIgnoreCase(receipt.getStatus());
                    if (txSuccess) {
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        clearPendingRawTxHex(ctx);
                        logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 历史入场交易链上确认成功");
                        return true;
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 历史入场交易reverted，重新执行入场");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        clearPendingRawTxHex(ctx);
                    }
                } else {
                    logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 入场交易未被打包，丢弃旧tx，重新执行");
                    saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    clearPendingRawTxHex(ctx);
                }
            }

            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 合约查询已经入座，跳过入场");
                return true;
            }

            String logLine = enterOneAccountOriginal(web3j, keyPair);
            logCallback.onLog(keyNo, "ENTER", logLine);

            String txHash = null;
            String[] splitArr = logLine.split("\\(");
            if (splitArr.length >= 2) {
                String candidate = splitArr[splitArr.length - 1].trim();
                if (candidate.startsWith("0x")) {
                    txHash = candidate.replace(")", "").trim();
                }
            }
            if (txHash != null && !txHash.isEmpty()) {
                TaskAccountState txSentSt = new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash);
                saveAccountState(ctx, fromAddr, txSentSt);
            } else {
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }
            ArenaContractApi.PlayerSummary afterSummary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            return afterSummary.seatedInCurrentRound;
        } catch (Exception e) {
            logCallback.onLog(keyNo, "ENTER", "❌【入场包装异常】:" + e.getMessage());
            return false;
        } finally {
            keyPair = null;
        }
    }

    public static void batchEnterAllResume(Context ctx, TaskLogCallback callback, int playMode,
                                           List<MainActivity.KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "ENTER", "⚠没有选中任何账号");
            return;
        }

        // 校验密钥管理器是否初始化
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "ENTER", "❌密钥管理器未初始化，请等待初始化完成");
            return;
        }

        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("", "ENTER", "\n--------开始批量入场【断点续跑版】 mode=" + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打") + "--------");

        int okCnt = 0, failCnt = 0;
        int total = selectedList.size();
        for (int i = 0; i < selectedList.size(); i++) {
            MainActivity.KeyListItem item = selectedList.get(i);
            String keyNo = item.displayNo;
            callback.onLog(keyNo, "ENTER", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");
            String plainPk;
            try {
                plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
            } catch (Exception ex) {
                callback.onLog(keyNo, "ENTER", "❌解密失败：会话自定义加解密Key与加密该密钥时的Key不匹配，跳过此账号");
                failCnt++;
                continue;
            }
            boolean ok = processEnterOneResume(ctx, web3j, plainPk, keyNo, callback);
            if (ok) okCnt++;
            else failCnt++;
        }
        web3j.shutdown();
        callback.onLog("", "ENTER", String.format("--------断点入场结束：成功/跳过=%d，失败=%d --------", okCnt, failCnt));
    }

    private static void batchClaimAndVerifyAllSingleAccount(Context ctx, String pk, String keyNo, Web3j web3j, TaskLogCallback callback, int maxRetryPerAccount) throws Exception {
        ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        try {
            ClaimOneResult res = claimOneAccountOriginal(web3j, keyPair, 0);
            callback.onLog(keyNo, "CLAIM", res.logText);
            int retryCount = 0;
            BigInteger remainJiazi;
            do {
                remainJiazi = ArenaContractApi.callJiaziCount(web3j, Web3AndroidUtil.getAddress(keyPair));
                retryCount++;
                if (remainJiazi.compareTo(BigInteger.ZERO) > 0) {
                    callback.onLog(keyNo, "CLAIM", "⚠️校验发现仍有可领取甲子：" + remainJiazi + " ,准备重试(" + retryCount + "/" + maxRetryPerAccount + ")");
                    Thread.sleep(1500);
                    res = claimOneAccountOriginal(web3j, keyPair, 0);
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

    public static void batchClaimAllResume(Context ctx, TaskLogCallback callback, int playMode,
                                           List<MainActivity.KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "CLAIM", "⚠没有选中任何账号");
            return;
        }

        // 校验密钥管理器是否初始化
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "CLAIM", "❌密钥管理器未初始化，请等待初始化完成");
            return;
        }

        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("", "CLAIM", "\n--------开始批量领取【断点续跑版】 mode=" + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打") + "--------");

        int okCnt = 0, failCnt = 0;
        int total = selectedList.size();
        for (int i = 0; i < selectedList.size(); i++) {
            MainActivity.KeyListItem item = selectedList.get(i);
            String keyNo = item.displayNo;
            callback.onLog(keyNo, "CLAIM", "\n-----处理账号 " + (i + 1) + "/" + total + " -----");
            String plainPk;
            try {
                plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
            } catch (Exception ex) {
                callback.onLog(keyNo, "CLAIM", "❌解密失败：会话自定义加解密Key与加密该密钥时的Key不匹配，跳过此账号");
                failCnt++;
                continue;
            }
            String fromAddr = Web3AndroidUtil.getAddress(Web3AndroidUtil.pkToKeyPair(plainPk));
            TaskAccountState diskState = getAccountState(ctx, fromAddr);
            if (diskState.getStatus() == TaskAccountState.STATUS_TX_SENT && diskState.getTxHash() != null) {
                callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...] 查询历史领取交易回执 txHash:" + diskState.getTxHash());
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(diskState.getTxHash()).send();
                Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                TransactionReceipt receipt = optReceipt.orElse(null);
                if (receipt != null) {
                    boolean txOk = "0x1".equalsIgnoreCase(receipt.getStatus());
                    if (txOk) {
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...]历史领取交易确认完成");
                        okCnt++;
                        continue;
                    } else {
                        callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...]历史领取交易reverted，重新执行领取");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else {
                    callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...]领取交易未打包，丢弃旧tx，重新执行");
                    saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                }
            }
            BigInteger remainJiazi = ArenaContractApi.callJiaziCount(web3j, fromAddr);
            if (remainJiazi.compareTo(BigInteger.ZERO) <= 0) {
                callback.onLog(keyNo, "CLAIM", "[" + fromAddr.substring(0, 10) + "...] 链上查询：无待领取甲子奖励，跳过");
                okCnt++;
                continue;
            }
            try {
                batchClaimAndVerifyAllSingleAccount(ctx, plainPk, keyNo, web3j, callback, 2);
                okCnt++;
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            } catch (Exception e) {
                callback.onLog(keyNo, "CLAIM", "❌账号领取整体失败:" + e.getMessage());
                failCnt++;
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
            }
        }
        web3j.shutdown();
        callback.onLog("", "CLAIM", String.format("--------断点领取结束：成功/跳过=%d，失败=%d --------", okCnt, failCnt));
    }

    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry, int playMode,
                                               List<MainActivity.KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
    }

    //================原版业务函数【禁止修改，1:1复刻py逻辑】====================
    public static final BigInteger ENTRY_FEE_OLD = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));

    public static void batchEnterAll(Context ctx, TaskLogCallback callback) throws Exception { /*省略，保留原版 */ }

    private static String enterOneAccountOriginal(Web3j web3j, ECKeyPair keyPair) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger estGas = BigInteger.valueOf(1600000);
        BigInteger nativeBalance = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();

        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return String.format("%s... 跳过:原生币不足,差约 %.4f", addr.substring(0, 10), diffEth);
        }

        ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, addr);
        BigInteger walletBalance = summary.walletBalance;
        BigInteger allowance = summary.allowance;
        BigInteger prize = summary.prize;
        BigInteger entriesRemainingToday = summary.entriesRemainingToday;
        boolean seatedInCurrentRound = summary.seatedInCurrentRound;

        if (seatedInCurrentRound) {
            return addr.substring(0, 10) + "... 跳过: 当前场已入座";
        }
        if (entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) {
            return addr.substring(0, 10) + "... 跳过: 今日已打满12场";
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
                return String.format("%s... 跳过:奖金 %.2f、钱包 %.2f 都不足100", addr.substring(0, 10), prizeD, walletD);
            }
        }

        BigInteger localNonce = web3j.ethGetTransactionCount(addr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        if (needApprove) {
            TransactionReceipt approveReceipt = ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, localNonce, gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
            Thread.sleep(2000);
        }
        TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, localNonce, gasPrice);
        String txHash = enterReceipt.getTransactionHash();
        return String.format("%s... 成功 %s (%s)", addr.substring(0, 10), payDesc, txHash);
    }

    private static ClaimOneResult claimOneAccountOriginal(Web3j web3j, ECKeyPair keyPair, int index) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger localNonce = web3j.ethGetTransactionCount(addr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        StringBuilder logSb = new StringBuilder();

        BigInteger jiaziTotal = ArenaContractApi.callJiaziCount(web3j, addr);
        BigInteger claimedJ = BigInteger.ZERO;
        if (jiaziTotal.compareTo(BigInteger.ZERO) > 0) {
            for (BigInteger start = BigInteger.ZERO; start.compareTo(jiaziTotal) < 0; start = start.add(MAX_JIAZI_BATCH)) {
                BigInteger cnt = MAX_JIAZI_BATCH.min(jiaziTotal.subtract(start));
                ArenaContractApi.doClaimJiazi(web3j, keyPair, start, cnt, localNonce, gasPrice);
                localNonce = localNonce.add(BigInteger.ONE);
                claimedJ = claimedJ.add(cnt);
                Thread.sleep(1000);
            }
        }
        logSb.append("甲子:领了 ").append(claimedJ).append(" | ");

        try {
            ArenaContractApi.doClaimLineage(web3j, keyPair, localNonce, gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
            logSb.append("师徒:已领 | ");
            Thread.sleep(1000);
        } catch (Exception e) {
            logSb.append("师徒失败:").append(e.getMessage().substring(0, Math.min(50, e.getMessage().length()))).append(" | ");
        }

        try {
            ArenaContractApi.doClaimRewards(web3j, keyPair, localNonce, gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
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
        return new ClaimOneResult(finalLog, wendToday);
    }

    public static class ClaimOneResult {
        public String logText;
        public Integer wendToday;

        public ClaimOneResult(String logText, Integer wendToday) {
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }
}
