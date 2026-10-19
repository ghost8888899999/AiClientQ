package com.lgns.task;

import android.content.Context;

import com.lgns.manager.AppGlobal;
import com.lgns.model.ArenaConfig;
import com.lgns.contract.ArenaContractApi;
import com.lgns.contract.Eip7702Auth;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.contract.SetCodeTx;
import com.lgns.contract.SquadApi;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class ArenaSquadTask {

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    public static void squadBatchEnter(Context ctx, TaskLogCallback callback,
                                       List<KeyListItem> selectedList, char[] sessionCryptoKey,
                                       String masterPk, String squadAddr) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "SQUAD", "⚠ 没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "SQUAD", "❌ 密钥管理器未初始化");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "SQUAD", "\n--------Squad 整批入场 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · 合约=" + squadAddr.substring(0, 10) + "...--------");

            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair).toLowerCase();

            if (!SquadApi.isDelegated(web3j, masterAddr, squadAddr)) {
                callback.onLog("", "SQUAD", "❌ 主号未委托到 Squad 合约,请先执行委托");
                return;
            }

            List<String> memberAddrs = new ArrayList<>();
            for (KeyListItem item : selectedList) {
                if (Web3AndroidUtil.isCancelRequested()) break;
                try {
                    String pk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair kp = Web3AndroidUtil.pkToKeyPair(pk);
                    memberAddrs.add(Web3AndroidUtil.getAddress(kp));
                    kp = null;
                } catch (Exception e) {
                    String no = (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
                    callback.onLog(no, "SQUAD", "✖ 解密失败:" + msgOf(e));
                }
            }
            if (memberAddrs.isEmpty()) {
                callback.onLog("", "SQUAD", "❌ 没有有效账号");
                return;
            }

            callback.onLog("", "SQUAD", "📋 共 " + memberAddrs.size() + " 个成员,开始整批入场");

            callback.onLog("", "SQUAD", "🔍 整批模拟预览中...");
            SquadApi.PlayPreview preview = SquadApi.preparePlay(
                    web3j, squadAddr, masterAddr, memberAddrs, false, callback);

            if (preview == null) {
                callback.onLog("", "SQUAD", "❌ 整批模拟全部失败,无账号可入场");
                return;
            }

            List<String> playable = preview.who;
            if (playable.size() < memberAddrs.size()) {
                callback.onLog("", "SQUAD", "⚠ 仅 " + playable.size() + "/" + memberAddrs.size()
                        + " 个账号可入场,其余暂缓");
            }
            callback.onLog("", "SQUAD", "📝 主号补币 " + preview.funded + " · Gas " + preview.gas);

            String playData = preview.data;
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger gasFloor = SquadApi.estimateGasFloor("playSome", playable.size());
            BigInteger estGas = preview.gas;
            if (estGas.compareTo(gasFloor) < 0) estGas = gasFloor;

            BigInteger masterNonce = web3j.ethGetTransactionCount(masterAddr,
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();

            TransactionReceipt receipt = ArenaContractApi.sendSetCodeRawAndWaitReceipt(web3j,
                    buildSquadLegacyRawTx(masterKeyPair, masterNonce, gasPrice, estGas, squadAddr, playData,
                            (h, raw) -> { }),
                    null, null);

            callback.onLog("", "SQUAD", "✅ 整批入场完成 " + playable.size() + "/" + memberAddrs.size()
                    + " 个账号 txHash=" + receipt.getTransactionHash()
                    + " gas=" + receipt.getGasUsed());

        } catch (java.io.IOException dialFail) {
            callback.onLog("", "SQUAD", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    public static void squadBatchHarvest(Context ctx, TaskLogCallback callback,
                                         List<KeyListItem> selectedList, char[] sessionCryptoKey,
                                         String masterPk, String squadAddr) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "SQUAD", "⚠ 没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "SQUAD", "❌ 密钥管理器未初始化");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "SQUAD", "\n--------Squad 整批收割 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · 合约=" + squadAddr.substring(0, 10) + "...--------");

            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair).toLowerCase();

            if (!SquadApi.isDelegated(web3j, masterAddr, squadAddr)) {
                callback.onLog("", "SQUAD", "❌ 主号未委托到 Squad 合约");
                return;
            }

            List<String> memberAddrs = new ArrayList<>();
            for (KeyListItem item : selectedList) {
                if (Web3AndroidUtil.isCancelRequested()) break;
                try {
                    String pk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair kp = Web3AndroidUtil.pkToKeyPair(pk);
                    memberAddrs.add(Web3AndroidUtil.getAddress(kp));
                    kp = null;
                } catch (Exception e) {
                    String no = (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
                    callback.onLog(no, "SQUAD", "✖ 解密失败:" + msgOf(e));
                }
            }
            if (memberAddrs.isEmpty()) {
                callback.onLog("", "SQUAD", "❌ 没有有效账号");
                return;
            }

            callback.onLog("", "SQUAD", "📋 共 " + memberAddrs.size() + " 个成员,开始整批收割");

            for (int pass = 1; pass <= 5; pass++) {
                if (Web3AndroidUtil.isCancelRequested()) break;
                AppGlobal.touch();

                String harvestData = SquadApi.encodeHarvestSome(memberAddrs);
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                BigInteger gasFloor = SquadApi.estimateGasFloor("harvestSome", memberAddrs.size());

                BigInteger estGas;
                try {
                    estGas = Web3AndroidUtil.estimateGasWithCeiling(web3j, masterAddr, squadAddr, harvestData);
                    if (estGas.compareTo(gasFloor) < 0) estGas = gasFloor;
                } catch (Exception e) {
                    estGas = gasFloor;
                }

                BigInteger masterNonce = web3j.ethGetTransactionCount(masterAddr,
                        DefaultBlockParameterName.PENDING).send().getTransactionCount();

                try {
                    TransactionReceipt receipt = ArenaContractApi.sendSetCodeRawAndWaitReceipt(web3j,
                            buildSquadLegacyRawTx(masterKeyPair, masterNonce, gasPrice, estGas, squadAddr, harvestData,
                                    null),
                            null, null);
                    callback.onLog("", "SQUAD", "收割第 " + pass + " 趟完成 txHash=" + receipt.getTransactionHash()
                            + " gas=" + receipt.getGasUsed());
                } catch (ArenaContractApi.RevertedTxException rtx) {
                    callback.onLog("", "SQUAD", "收割第 " + pass + " 趟 reverted:" + rtx.txHash);
                    break;
                }

                SquadApi.OverviewData ov = SquadApi.callOverview(web3j, squadAddr);
                callback.onLog("", "SQUAD", "主号余额: " + ov.masterBalance);

                if (ov.masterBalance.compareTo(BigInteger.ZERO) <= 0) {
                    callback.onLog("", "SQUAD", "✅ 收割完毕,主号无可收余额");
                    break;
                }
                if (pass < 5) {
                    callback.onLog("", "SQUAD", "仍有可收余额,3 秒后继续...");
                    Thread.sleep(3000);
                }
            }

        } catch (java.io.IOException dialFail) {
            callback.onLog("", "SQUAD", "❌ 节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    public static void squadDelegate(Context ctx, TaskLogCallback callback,
                                     List<KeyListItem> selectedList, char[] sessionCryptoKey,
                                     String masterPk, String squadAddr) throws Exception {
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "DELEGATE", "❌ 密钥管理器未初始化");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "DELEGATE", "\n--------EIP-7702 委托 · 目标=" + squadAddr.substring(0, 10) + "...--------");

            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair).toLowerCase();

            boolean alreadyDelegated = SquadApi.isDelegated(web3j, masterAddr, squadAddr);
            if (alreadyDelegated) {
                callback.onLog("", "DELEGATE", "➖ 主号已委托,跳过");
            }

            List<Eip7702Auth> authList = new ArrayList<>();
            BigInteger masterNonce = web3j.ethGetTransactionCount(masterAddr,
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();

            if (!alreadyDelegated) {
                long authNonce = masterNonce.longValue() + 1;
                Eip7702Auth masterAuth = Eip7702Auth.sign(
                        BigInteger.valueOf(ArenaConfig.CHAIN_ID), squadAddr, authNonce, masterKeyPair);
                authList.add(masterAuth);
                callback.onLog("", "DELEGATE", "✔ 主号授权已签名 (nonce=" + authNonce + ")");
            }

            int memberAuthCount = 0;
            for (KeyListItem item : selectedList) {
                if (Web3AndroidUtil.isCancelRequested()) break;
                try {
                    String pk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair kp = Web3AndroidUtil.pkToKeyPair(pk);
                    String addr = Web3AndroidUtil.getAddress(kp);

                    if (addr.equalsIgnoreCase(masterAddr)) {
                        kp = null;
                        continue;
                    }

                    boolean memberDelegated = SquadApi.isDelegated(web3j, addr, squadAddr);
                    if (memberDelegated) {
                        callback.onLog("", "DELEGATE", "➖ " + addr.substring(0, 10) + "... 已委托,跳过");
                        kp = null;
                        continue;
                    }

                    long memberNonce = web3j.ethGetTransactionCount(addr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount().longValue();
                    Eip7702Auth auth = Eip7702Auth.sign(
                            BigInteger.valueOf(ArenaConfig.CHAIN_ID), squadAddr, memberNonce, kp);
                    authList.add(auth);
                    memberAuthCount++;
                    callback.onLog("", "DELEGATE", "✔ " + addr.substring(0, 10) + "... 授权已签名 (nonce=" + memberNonce + ")");
                    kp = null;
                } catch (Exception e) {
                    String no = (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
                    callback.onLog(no, "DELEGATE", "✖ 签名失败:" + msgOf(e));
                }
            }

            if (authList.isEmpty()) {
                callback.onLog("", "DELEGATE", "✅ 所有账号已委托,无需操作");
                return;
            }

            int n = authList.size();
            BigInteger gasLimit = BigInteger.valueOf(60_000L + (long) n * 40_000L);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();

            callback.onLog("", "DELEGATE", "📝 构建 SetCodeTx: " + n + " 个授权, gas=" + gasLimit);

            SetCodeTx.SignedSetCode signedTx = SetCodeTx.sign(
                    masterNonce, gasPrice, gasLimit, masterAddr, "0x", authList, masterKeyPair);

            callback.onLog("", "DELEGATE", "📡 广播委托交易... txHash=" + signedTx.txHash);

            TransactionReceipt receipt = ArenaContractApi.waitReceiptLocal(web3j, signedTx.txHash);
            callback.onLog("", "DELEGATE", "✅ 委托交易已确认 gas=" + receipt.getGasUsed());

            for (Eip7702Auth auth : authList) {
                boolean ok = SquadApi.isDelegated(web3j, auth.address, squadAddr);
                callback.onLog("", "DELEGATE", (ok ? "✔ " : "✖ ") + auth.address.substring(0, 10)
                        + "... 委托" + (ok ? "生效" : "未生效"));
            }

        } catch (java.io.IOException dialFail) {
            callback.onLog("", "DELEGATE", "❌ 节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    private static String buildSquadLegacyRawTx(ECKeyPair masterKeyPair, BigInteger nonce,
                                                BigInteger gasPrice, BigInteger gasLimit,
                                                String toAddr, String data,
                                                ArenaContractApi.PreBroadcastHook hook) throws Exception {
        ArenaContractApi.SignedTx signed = ArenaContractApi.signRawTx(masterKeyPair, nonce, gasPrice, gasLimit, toAddr, data);
        if (hook != null) {
            hook.onSigned(signed.txHash, signed.rawTxHex);
        }
        return signed.rawTxHex;
    }
}
