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

public class BatchEnterTask {

    public static final int MODE_FLEXIBLE = 0;
    public static final int MODE_TOGETHER = 1;
    public static final BigInteger ENTRY_FEE = ArenaConfig.ENTRY_FEE;
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;
    public static final long PENDING_WARN_THRESHOLD_MS = 60_000L;

    public static final Set<String> enterFailedAddrSet = new HashSet<>();
    private static final java.util.Map<String, Long> revertedCooldownMap = new HashMap<>();
    private static final long REVERTED_COOLDOWN_MS = 10 * 60 * 1000L;
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("(0x[0-9a-fA-F]{64})");

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

    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
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

    // 返回值含义: 0=失败, 1=跳过(含成功入场), 2=真正的成功(有txHash)
    private static final int ENTER_FAIL = 0;
    private static final int ENTER_SKIP = 1;
    private static final int ENTER_SUCCESS = 2;

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

    private static Pair<String, BigInteger> enterOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                    Web3j web3j, ECKeyPair keyPair, BigInteger nonce) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        String enterData = org.web3j.abi.FunctionEncoder.encode(
                new org.web3j.abi.datatypes.Function("enterArena",
                        new ArrayList<>(), new ArrayList<>()));
        
        // 使用 ArenaConfig 常量，避免 eth_estimateGas 返回值格式错误
        BigInteger estGas = ArenaConfig.GAS_LIMIT_ENTER_ARENA;
        
        BigInteger nativeBalance = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return new Pair<>(String.format("%s... 跳过:原生币不足，差约 %.4f", addr.substring(0, 10), diffEth), nonce);
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
            Thread.sleep(2000);
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
}
