package com.lgns.service;

import com.lgns.manager.AppGlobal;
import com.lgns.model.ArenaConfig;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动充值服务：批量入场前或手动触发时，从主号向余额不足的账号转入 fLGNS
 */
public class AutoFundService {

    /**
     * 执行自动充值（同步方法，需在后台线程调用）
     *
     @param enabledList   启用的密钥列表
     * @param pwdCharArray    解密密码
     * @param callback        日志回调
     */
    public static void executeAutoFund(List<KeyListItem> enabledList,
                                       char[] pwdCharArray,
                                       TaskLogCallback callback) {
        if (enabledList == null || enabledList.isEmpty()) return;

        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            long masterId = mgr.getMasterAccountId();
            if (masterId <= 0) {
                if (callback != null) {
                    callback.onLog("", "FUND", "⚠未设置主号，跳过充值");
                }
                return;
            }

            KeyStoreManager.KeyEntry masterEntry = null;
            List<KeyStoreManager.KeyEntry> allEntries = mgr.loadKeyEntryList();
            for (KeyStoreManager.KeyEntry e : allEntries) {
                if (e.id == masterId) {
                    masterEntry = e;
                    break;
                }
            }

            if (masterEntry == null || masterEntry.address == null) {
                if (callback != null) {
                    callback.onLog("", "FUND", "⚠主号信息异常，跳过充值");
                }
                return;
            }

            Web3j web3j = Web3AndroidUtil.dialAny();
            try {
                List<String> needFundAddrs = new ArrayList<>();
                List<String> needFundLabels = new ArrayList<>();

                for (KeyListItem item : enabledList) {
                    if (item.address == null || item.address.equalsIgnoreCase(masterEntry.address)) {
                        continue;
                    }
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                        String no = (item.customCode != null && !item.customCode.isEmpty())
                                ? item.customCode
                                : item.displayNo;
                        needFundAddrs.add(item.address);
                        needFundLabels.add(no);
                    }
                }

                if (needFundAddrs.isEmpty()) {
                    if (callback != null) {
                        callback.onLog("", "FUND", "所有账号余额充足，无需充值");
                    }
                    return;
                }

                String masterPk = mgr.decryptCipherText(masterEntry.cipherText, pwdCharArray);
                ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
                String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair);

                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                BigInteger nonce = web3j.ethGetTransactionCount(masterAddr,
                        DefaultBlockParameterName.PENDING).send().getTransactionCount();

                for (int i = 0; i < needFundAddrs.size(); i++) {
                    if (Web3AndroidUtil.isCancelRequested()) {
                        if (callback != null) {
                            callback.onLog("", "FUND", "⚠用户取消充值");
                        }
                        break;
                    }

                    String addr = needFundAddrs.get(i);
                    final int index = i;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, addr);

                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) >= 0) {
                        final String label = needFundLabels.get(index);
                        if (callback != null) {
                            callback.onLog("", "FUND", "➖ " + label + " 余额已够,跳过");
                        }
                        continue;
                    }

                    BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                    try {
                        ArenaContractApi.doErc20Transfer(web3j, masterKeyPair,
                                addr, diff, nonce, gasPrice);
                        nonce = nonce.add(BigInteger.ONE);

                        final String label = needFundLabels.get(index);
                        final BigDecimal amount = new BigDecimal(diff).divide(
                                new BigDecimal(BigInteger.TEN.pow(18)),
                                2, java.math.RoundingMode.HALF_UP);

                        if (callback != null) {
                            callback.onLog("", "FUND", label + " 转入 " + amount + " fLGNS");
                        }
                    } catch (Exception e) {
                        final String label = needFundLabels.get(index);
                        if (callback != null) {
                            callback.onLog("", "FUND",
                                    "错误：" + label + " 转账失败: " +
                                            Web3AndroidUtil.safeMessage(e));
                        }
                    }
                }

                if (callback != null) {
                    callback.onLog("", "FUND", "批量充值完成，继续入场");
                }
            } finally {
                try {
                    web3j.shutdown();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            if (callback != null) {
                callback.onLog("", "FUND",
                        "错误：自动充值异常：" + Web3AndroidUtil.safeMessage(e));
            }
        }
    }

    /**
     * 查询主号余额并计算需要充值的账号列表（用于弹窗确认前的预览）
     *
     * @return FundPreviewResult 包含主号余额、需充值列表、总金额等信息
     */
    public static FundPreviewResult queryFundPreview(List<KeyListItem> enabledList) {
        FundPreviewResult result = new FundPreviewResult();

        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            long masterId = mgr.getMasterAccountId();

            if (masterId <= 0) {
                result.hasMaster = false;
                return result;
            }

            List<KeyStoreManager.KeyEntry> allEntries = mgr.loadKeyEntryList();
            for (KeyStoreManager.KeyEntry e : allEntries) {
                if (e.id == masterId) {
                    result.masterEntry = e;
                    break;
                }
            }

            if (result.masterEntry == null || result.masterEntry.address == null) {
                result.hasMaster = false;
                return result;
            }

            result.hasMaster = true;
            Web3j web3j = Web3AndroidUtil.dialAny();
            try {
                result.masterBalance = ArenaContractApi.callErc20Balance(
                        web3j, result.masterEntry.address);

                for (KeyListItem item : enabledList) {
                    if (item.address == null ||
                        item.address.equalsIgnoreCase(result.masterEntry.address)) {
                        continue;
                    }

                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                        BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                        result.totalNeeded = result.totalNeeded.add(diff);

                        String no = (item.customCode != null && !item.customCode.isEmpty())
                                ? item.customCode
                                : item.displayNo;
                        BigDecimal balF = new BigDecimal(bal).divide(
                                new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);

                        result.needFundAddrs.add(item.address);
                        result.needFundLabels.add(no + " (余额:" + balF + ")");
                    }
                }
            } finally {
                try {
                    web3j.shutdown();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            android.util.Log.e("AutoFundService", "queryFundPreview error", e);
        }

        return result;
    }

    /**
     * 充值预览结果数据类
     */
    public static class FundPreviewResult {
        public boolean hasMaster = false;
        public KeyStoreManager.KeyEntry masterEntry = null;
        public BigInteger masterBalance = BigInteger.ZERO;
        public List<String> needFundAddrs = new ArrayList<>();
        public List<String> needFundLabels = new ArrayList<>();
        public BigInteger totalNeeded = BigInteger.ZERO;

        public boolean needsFunding() {
            return hasMaster && !needFundAddrs.isEmpty();
        }

        public BigDecimal getMasterBalanceFormatted() {
            return new BigDecimal(masterBalance).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        public BigDecimal getTotalNeededFormatted() {
            return new BigDecimal(totalNeeded).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }
    }
}
