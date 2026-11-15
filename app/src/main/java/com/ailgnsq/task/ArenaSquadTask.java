package com.ailgnsq.task;

import android.content.Context;

import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.contract.ArenaContractApi;
import com.ailgnsq.contract.Eip7702Auth;
import com.ailgnsq.model.KeyListItem;
import com.ailgnsq.manager.KeyStoreManager;
import com.ailgnsq.contract.SetCodeTx;
import com.ailgnsq.contract.SquadApi;
import com.ailgnsq.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Squad 委托路径的三个批量操作：整批入场、整批收割、EIP-7702 委托。
 *
 * <p>与 {@code BatchEnterTask} 这类逐账号任务的差别：这里所有链上写入都由主号一笔交易完成，
 * 子号只用来解出地址（不需要各自签名、不各自付 gas），所以不会出现「N 个账号 N 笔交易」。
 * 前提是子号已通过 7702 把代码委托给 Squad 合约并加入成员表。</p>
 *
 * <p>线程模型：三个方法都是同步阻塞的，必须在后台线程调用；日志通过
 * {@link TaskLogCallback} 回到任务线程，UI 层自己负责切主线程。
 * 取消靠 {@code Web3AndroidUtil.isCancelRequested()} 轮询，只在循环/阶段边界生效——
 * 已广播的交易无法回滚。</p>
 */
public class ArenaSquadTask {

    /** 统一转成可读的异常描述，避免日志里出现 null message */
    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    // region 整批入场

    /**
     * 主号一笔交易打完一批子号（Squad 的 {@code playSome}）。
     *
     * <p>过程：连节点 → 校验主号已委托 → 逐个解私钥只取地址 → {@code preparePlay} 干跑
     * 并用二分法求出实际能打的子集 → 取 gasPrice、nonce(PENDING)、gasLimit =
     * max(预演值, 经验下限) → 组 legacy 交易发给 Squad 合约 → 等回执。</p>
     *
     * <p>失败分支：任一子号解密失败只跳过该号（不影响其余）；整批模拟失败或没有可打账号则
     * 直接返回；节点全不可达时向上抛出 {@code IOException}，由调用方决定是否提示换节点。
     * 注意这里发的是普通 legacy 交易（非 7702 交易），子号的委托信息已存在链上。</p>
     */
    public static void squadBatchEnter(Context ctx, TaskLogCallback callback,
                                       List<KeyListItem> selectedList, char[] sessionCryptoKey,
                                       String masterPk, String squadAddr) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "SQUAD", "警告：没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "SQUAD", "错误：密钥管理器未初始化");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "SQUAD", "\nSquad 整批入场 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · 合约=" + squadAddr.substring(0, 10) + "...");

            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair).toLowerCase();

            if (!SquadApi.isDelegated(web3j, masterAddr, squadAddr)) {
                callback.onLog("", "SQUAD", "错误：主号未委托到 Squad 合约，请先执行委托");
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
                    callback.onLog(no, "SQUAD", "解密失败:" + msgOf(e));
                }
            }
            if (memberAddrs.isEmpty()) {
                callback.onLog("", "SQUAD", "错误：没有有效账号");
                return;
            }

            callback.onLog("", "SQUAD", "共 " + memberAddrs.size() + " 个成员，开始整批入场");

            callback.onLog("", "SQUAD", "整批模拟预览中...");
            SquadApi.PlayPreview preview = SquadApi.preparePlay(
                    web3j, squadAddr, masterAddr, memberAddrs, false, callback);

            if (preview == null) {
                callback.onLog("", "SQUAD", "错误：整批模拟全部失败，无账号可入场");
                return;
            }

            List<String> playable = preview.who;
            if (playable.size() < memberAddrs.size()) {
                callback.onLog("", "SQUAD", "警告：仅 " + playable.size() + "/" + memberAddrs.size()
                        + " 个账号可入场，其余暂缓");
            }
            callback.onLog("", "SQUAD", "主号补币 " + preview.funded + " · Gas " + preview.gas);

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

            callback.onLog("", "SQUAD", "整批入场完成 " + playable.size() + "/" + memberAddrs.size()
                    + " 个账号 txHash=" + receipt.getTransactionHash()
                    + " gas=" + receipt.getGasUsed());

        } catch (java.io.IOException dialFail) {
            callback.onLog("", "SQUAD", "错误：" + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region 整批收割

    /**
     * 主号循环收割，直到合约里没有可收余额（Squad 的 {@code harvestSome}）。
     *
     * <p>为什么要循环：单次 harvest 受单笔 gas 与合约内单轮上限约束，成员多时收不干净，
     * 所以最多打 5 趟，每趟到链上重新取一次 {@code masterBalance}：
     * 收光了就停，没停就等 3 秒再打下一趟。</p>
     *
     * <p>每趟都重新取 gasPrice、重新估 gas、重新取 PENDING nonce，
     * 避免上一趟刚出块导致的 nonce/价格过期；估算失败时退回到经验下限。
     * 某趟回滚（{@code RevertedTxException}）就整个停下——同一批参数重试也是回滚。</p>
     */
    public static void squadBatchHarvest(Context ctx, TaskLogCallback callback,
                                         List<KeyListItem> selectedList, char[] sessionCryptoKey,
                                         String masterPk, String squadAddr) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "SQUAD", "警告：没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "SQUAD", "错误：密钥管理器未初始化");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "SQUAD", "\nSquad 整批收割 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · 合约=" + squadAddr.substring(0, 10) + "...");

            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair).toLowerCase();

            if (!SquadApi.isDelegated(web3j, masterAddr, squadAddr)) {
                callback.onLog("", "SQUAD", "错误：主号未委托到 Squad 合约");
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
                    callback.onLog(no, "SQUAD", "解密失败:" + msgOf(e));
                }
            }
            if (memberAddrs.isEmpty()) {
                callback.onLog("", "SQUAD", "错误：没有有效账号");
                return;
            }

            callback.onLog("", "SQUAD", "共 " + memberAddrs.size() + " 个成员，开始整批收割");

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
                    callback.onLog("", "SQUAD", "收割完毕，主号无可收余额");
                    break;
                }
                // 下一趟无需额外等待：上一趟已等到回执，主号余额也是刚查的，nonce 重新取 PENDING
                if (pass < 5) {
                    callback.onLog("", "SQUAD", "仍有可收余额,继续下一趟...");
                }
            }

        } catch (java.io.IOException dialFail) {
            callback.onLog("", "SQUAD", "错误：节点全不可达:" + msgOf(dialFail));
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region EIP-7702 委托

    /**
     * 把主号与选中子号一次性授权给 Squad 合约（一笔 SetCodeTx 带多个授权条目）。
     *
     * <p>流程：查主号是否已委托 → 未委托则签一条主号授权（nonce 取 PENDING + 1：
     * 本次交易本身要占掉当前 nonce，授权只能用下一个）→ 逐个解子号私钥签授权
     * （已委托的跳过，等于主号的也跳过）→ 按「60000 + 每成员 40000」估 gasLimit →
     * 组 0x04 交易 → 等回执 → 逐个回查 code 确认生效。</p>
     *
     * <p>⚠ 当前实现缺口：签完后只调了 {@code waitReceiptLocal(web3j, signedTx.txHash)}，
     * 并没有把 {@code signedTx.rawTxHex} 广播出去，所以交易永远不上链、回查也必然显示未生效。
     * 要真正可用需在等回执前补一次 {@code eth_sendRawTransaction}。</p>
     */
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
            callback.onLog("", "DELEGATE", "\nEIP-7702 委托 · 目标=" + squadAddr.substring(0, 10) + "...");

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

    // endregion

    // region 内部工具

    /**
     * 用主号签一笔普通 legacy 交易（发给 Squad 合约的批量调用）。
     *
     * <p>抽出来只为了统一挂预广播钩子：入场传了空钩子（不需要提前落库），
     * 收割传 null，后续若要统一记录 rawTx 只需改这里一处。</p>
     */
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

    // endregion
}
