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
 * 自动充值服务：批量入场前或手动触发时，从主号向余额不足的账号转入 fLGNS。
 *
 * <p>判阈值口径：子号余额低于 {@code ENTRY_FEE}（一场门票 100 fLGNS）就补到刚好够一场，
 * 不多给也不做「按差额百分比」之类的策略——补多了冒资产留在子号上的风险。</p>
 *
 * <p>转账走 ERC20 transfer（不是原生币），由主号一笔笔发出，nonce 在本地递增；
 * 单笔失败只记日志继续下一个，不因一个账号拖死整批。</p>
 */
public class AutoFundService {

    /**
     * 执行自动充值（同步方法，需在后台线程调用）。
     *
     * <p>过程：找主号（未设置或地址异常则直接返回）→ 逐账号查 ERC20 余额筛出不足的
     * → 解主号私钥 → 取 gasPrice 与 PENDING nonce → 逐个转差额（发一笔 nonce 加一）
     * → 关闭 Web3j。</p>
     *
     * <p>为什么发之前要再查一次余额：预览之后用户可能已经手动补过，这里复查可以避免重复充值；
     * 两次之间仍可能存在并发，但那属于外部手动操作，不加锁。</p>
     *
     * @param enabledList   启用的密钥列表（只处理启用行）
     * @param pwdCharArray  解密密码，调用方负责用完清零
     * @param callback      日志回调，可传 null
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
     * 充值预览：只读，不发任何交易。用于弹窗确认前把「主号余额 / 需充值账号 / 总额」算出来。
     *
     * <p>与 {@link #executeAutoFund} 用同一套阈值口径（低于一场门票就补），
     * 所以预览的条数就是实际会转账的条数（除非期间余额变动）。
     * 内部异常只记 Log 不上抛，失败时返回的是 {@code hasMaster=true} 但列表为空的空结果。</p>
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
     * 充值预览结果。金额字段均为最小单位（10^18 = 1 枚），展示时走 {@code *Formatted} 换算。
     */
    public static class FundPreviewResult {
        /** 是否已设置可用主号；false 时其余字段不可信，界面应提示先设置主号 */
        public boolean hasMaster = false;
        /** 主号条目（含密文与地址），供确认弹窗展示与后续执行复用 */
        public KeyStoreManager.KeyEntry masterEntry = null;
        /** 主号当前 fLGNS 余额 */
        public BigInteger masterBalance = BigInteger.ZERO;
        /** 需充值账号地址 */
        public List<String> needFundAddrs = new ArrayList<>();
        /** 与地址一一对应的展示标签，带当前余额，便于用户核对 */
        public List<String> needFundLabels = new ArrayList<>();
        /** 本次预计总支出（各账号差额之和） */
        public BigInteger totalNeeded = BigInteger.ZERO;

        /** 是否有主号且有账号需要充值：两个条件同时成立才弹确认框 */
        public boolean needsFunding() {
            return hasMaster && !needFundAddrs.isEmpty();
        }

        /** 主号余额转换成「枚」（保留两位小数），仅用于展示 */
        public BigDecimal getMasterBalanceFormatted() {
            return new BigDecimal(masterBalance).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        /** 预计总支出转换成「枚」（保留两位小数），仅用于展示 */
        public BigDecimal getTotalNeededFormatted() {
            return new BigDecimal(totalNeeded).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }
    }
}
