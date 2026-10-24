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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BatchClaimTask {

    private static final long PENDING_WARN_THRESHOLD_MS = 60_000L;
    private static final BigInteger MAX_JIAZI_BATCH = ArenaConfig.MAX_JIAZI_BATCH;

    private static final Map<String, Long> revertedCooldownMap = new HashMap<>();
    private static final long REVERTED_COOLDOWN_MS = 10 * 60 * 1000L;

    private static final Gson gson = new Gson();
    private static final String SP_TASK_PROGRESS = "task_progress";

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

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

                if (BatchEnterTask.enterFailedAddrSet.contains(fromAddr)) {
                    claimSkipCount++;
                    callback.onLog(keyNo, "CLAIM", "➖ 该账号入场执行失败,跳过领取操作");
                    continue;
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
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
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

    public static class ClaimOneResult {
        public String logText;
        public Integer wendToday;

        public ClaimOneResult(String logText, Integer wendToday) {
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }
}
