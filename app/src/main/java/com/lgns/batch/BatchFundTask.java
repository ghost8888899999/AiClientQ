package com.lgns.batch;

import android.app.Activity;
import org.web3j.protocol.Web3j;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.core.DefaultBlockParameterName;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.lgns.R;
import com.lgns.helper.MainDialogHelper;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

public class BatchFundTask {

    public interface ButtonStateController {
        boolean isRunning();
        void setRunning(boolean running);
    }

    // 独立按钮触发（含 UI 弹窗确认）

    public static void handleBatchFund(Activity activity, TaskLogCallback callback,
                                       List<KeyListItem> keyListItemList,
                                       MainDialogHelper dialogHelper,
                                       ButtonStateController buttonController) {
        if (buttonController.isRunning()) {
            android.widget.Toast.makeText(activity, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        buttonController.setRunning(true);

        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                long masterId = mgr.getMasterAccountId();
                if (masterId < 0) {
                    activity.runOnUiThread(() -> {
                        dialogHelper.styledDialog()
                                .setTitle(R.string.master_not_set_title)
                                .setMessage(R.string.master_not_set_msg)
                                .setPositiveButton(R.string.btn_ok, (d, w) -> buttonController.setRunning(false))
                                .setOnCancelListener(d -> buttonController.setRunning(false))
                                .show();
                    });
                    return;
                }

                KeyStoreManager.KeyEntry masterEntry = findMasterEntry(mgr, masterId);
                if (masterEntry == null || masterEntry.address == null) {
                    showMasterErrorDialog(activity, dialogHelper, buttonController);
                    return;
                }

                Web3j web3j = Web3AndroidUtil.dialAny();
                try {
                    processBatchFund(activity, callback, keyListItemList, dialogHelper,
                            buttonController, mgr, masterEntry, web3j);
                } finally {
                    web3j.shutdown();
                }
            } catch (Exception e) {
                showFundErrorDialog(activity, dialogHelper, buttonController, e);
            }
        }).start();
    }

    // 工作流调用（无 UI，直接执行转账）

    public static void executeAutoFund(List<KeyListItem> enabledList,
                                       char[] pwdCharArray,
                                       TaskLogCallback callback) {
        if (enabledList == null || enabledList.isEmpty()) return;

        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            long masterId = mgr.getMasterAccountId();
            if (masterId <= 0) {
                if (callback != null) callback.onLog("", "FUND", "⚠未设置主号，跳过充值");
                return;
            }

            KeyStoreManager.KeyEntry masterEntry = findMasterEntry(mgr, masterId);
            if (masterEntry == null || masterEntry.address == null) {
                if (callback != null) callback.onLog("", "FUND", "⚠主号信息异常，跳过充值");
                return;
            }

            Web3j web3j = Web3AndroidUtil.dialAny();
            try {
                List<String> needFundAddrs = new ArrayList<>();
                List<String> needFundLabels = new ArrayList<>();

                for (KeyListItem item : enabledList) {
                    // 主号保护用 keyId：地址可能已被调试开关替换，地址比较不可靠
                    if (item.keyId == masterId) continue;
                    // 护栏：调试地址永不作为真实转账目的地
                    if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                            && item.address != null
                            && item.address.equalsIgnoreCase(ArenaConfig.DEBUG_FIXED_ADDR)) continue;
                    if (item.address == null || item.address.equalsIgnoreCase(masterEntry.address)) continue;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                        String no = (item.customCode != null && !item.customCode.isEmpty())
                                ? item.customCode : item.displayNo;
                        needFundAddrs.add(item.address);
                        needFundLabels.add(no);
                    }
                }

                if (needFundAddrs.isEmpty()) {
                    if (callback != null) callback.onLog("", "FUND", "所有账号余额充足，无需充值");
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
                        if (callback != null) callback.onLog("", "FUND", "⚠用户取消充值");
                        break;
                    }

                    String addr = needFundAddrs.get(i);
                    final int index = i;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, addr);

                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) >= 0) {
                        final String label = needFundLabels.get(index);
                        if (callback != null) callback.onLog("", "FUND", "➖ " + label + " 余额已够,跳过");
                        continue;
                    }

                    BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                    try {
                        ArenaContractApi.doErc20Transfer(web3j, masterKeyPair, addr, diff, nonce, gasPrice);
                        nonce = nonce.add(BigInteger.ONE);

                        final String label = needFundLabels.get(index);
                        final BigDecimal amount = new BigDecimal(diff).divide(
                                new BigDecimal(BigInteger.TEN.pow(18)),
                                2, java.math.RoundingMode.HALF_UP);

                        if (callback != null) callback.onLog("", "FUND", label + " 转入 " + amount + " fLGNS");
                    } catch (Exception e) {
                        final String label = needFundLabels.get(index);
                        if (callback != null) callback.onLog("", "FUND",
                                "错误：" + label + " 转账失败: " + Web3AndroidUtil.safeMessage(e));
                    }
                }

                if (callback != null) callback.onLog("", "FUND", "批量充值完成");
            } finally {
                try { web3j.shutdown(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (callback != null) callback.onLog("", "FUND",
                    "错误：自动充值异常：" + Web3AndroidUtil.safeMessage(e));
        }
    }

    // 预览查询（供 MainDialogHelper 调用）

    public static FundPreviewResult queryFundPreview(List<KeyListItem> enabledList) {
        FundPreviewResult result = new FundPreviewResult();
        try {
            // 调试专用：无条件替换"列表第一个账号（即主号）"的地址为 DEBUG_FIXED_ADDR，
            // 不再看 enabled（主号设主后自动禁用，看 enabled 会跳过主号）
            if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && !enabledList.isEmpty()) {
                KeyListItem first = enabledList.get(0);
                if (first.address != null && !first.address.isEmpty()) {
                    android.util.Log.d("DEBUG_ADDR", "充值预览地址替换: "
                            + first.address + " -> " + ArenaConfig.DEBUG_FIXED_ADDR);
                    first.address = ArenaConfig.DEBUG_FIXED_ADDR;
                }
            }

            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            long masterId = mgr.getMasterAccountId();
            if (masterId <= 0) { result.hasMaster = false; return result; }

            List<KeyStoreManager.KeyEntry> allEntries = mgr.loadKeyEntryList();
            for (KeyStoreManager.KeyEntry e : allEntries) {
                if (e.id == masterId) { result.masterEntry = e; break; }
            }
            if (result.masterEntry == null || result.masterEntry.address == null) {
                result.hasMaster = false;
                return result;
            }

            result.hasMaster = true;
            Web3j web3j = Web3AndroidUtil.dialAny();
            try {
                // 主号真实余额（资金源口径）
                result.masterBalance = ArenaContractApi.callErc20Balance(web3j, result.masterEntry.address);

                // 主号信息卡片：独立于列表与启用状态——主号即使禁用也永远显示
                // 展示地址与主界面主号行同源（调试开关 ON 时为替换后地址）
                String masterShowAddr = result.masterEntry.address;
                for (KeyListItem item : enabledList) {
                    if (item.keyId == masterId && item.address != null && !item.address.isEmpty()) {
                        masterShowAddr = item.address;
                        break;
                    }
                }
                result.masterShowAddr = masterShowAddr;
                result.masterShowBalance = masterShowAddr.equalsIgnoreCase(result.masterEntry.address)
                        ? result.masterBalance
                        : ArenaContractApi.callErc20Balance(web3j, masterShowAddr);
                result.masterKeyNo = (result.masterEntry.customCode != null && !result.masterEntry.customCode.isEmpty())
                        ? result.masterEntry.customCode
                        : String.format(java.util.Locale.getDefault(), "%03d", result.masterEntry.id);

                // 展示行：仅非主号的启用账号（主号只进卡片；禁用账号不充值不上列表）
                for (KeyListItem item : enabledList) {
                    if (item.address == null) continue;
                    if (item.keyId == masterId) continue;
                    if (!item.enabled) continue;
                    String no = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    BigDecimal balF = new BigDecimal(bal).divide(
                            new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);

                    BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                    BigInteger showDiff = diff.signum() > 0 ? diff : BigInteger.ZERO;
                    BigDecimal needF = new BigDecimal(showDiff).divide(
                            new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
                    FundPreviewResult.FundDetail d =
                            new FundPreviewResult.FundDetail(no, item.address, balF, needF);
                    d.sufficient = bal.compareTo(ArenaConfig.ENTRY_FEE) >= 0;
                    result.fundDetails.add(d);

                    // 合计与真实充值只统计：启用 + 非主号 + 余额不足 + 非调试地址
                    boolean willTransfer = bal.compareTo(ArenaConfig.ENTRY_FEE) < 0
                            && !(ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                                 && item.address.equalsIgnoreCase(ArenaConfig.DEBUG_FIXED_ADDR));
                    if (willTransfer) {
                        result.totalNeeded = result.totalNeeded.add(diff);
                        result.needFundAddrs.add(item.address);
                        result.needFundLabels.add(no + " (余额:" + balF + ")");
                    }
                }
            } finally {
                try { web3j.shutdown(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            android.util.Log.e("BatchFundTask", "queryFundPreview error", e);
            result.error = e;
        }
        return result;
    }

    // 内部辅助

    private static KeyStoreManager.KeyEntry findMasterEntry(KeyStoreManager mgr, long masterId) {
        List<KeyStoreManager.KeyEntry> allEntries = mgr.loadKeyEntryList();
        for (KeyStoreManager.KeyEntry e : allEntries) {
            if (e.id == masterId) return e;
        }
        return null;
    }

    private static void showMasterErrorDialog(Activity activity, MainDialogHelper dialogHelper,
                                               ButtonStateController buttonController) {
        activity.runOnUiThread(() -> {
            dialogHelper.styledDialog()
                    .setMessage(R.string.master_error_msg)
                    .setPositiveButton(R.string.btn_ok, (d, w) -> buttonController.setRunning(false))
                    .setOnCancelListener(d -> buttonController.setRunning(false))
                    .show();
        });
    }

    private static void processBatchFund(Activity activity, TaskLogCallback callback,
                                          List<KeyListItem> keyListItemList,
                                          MainDialogHelper dialogHelper,
                                          ButtonStateController buttonController,
                                          KeyStoreManager ksMgr,
                                          KeyStoreManager.KeyEntry masterEntry,
                                          Web3j web3j) throws Exception {
        BigInteger masterBalance = ArenaContractApi.callErc20Balance(web3j, masterEntry.address);
        BigDecimal masterBalF = new BigDecimal(masterBalance)
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);

        List<String> needFundAddrs = new ArrayList<>();
        List<String> needFundLabels = new ArrayList<>();
        BigInteger totalNeeded = BigInteger.ZERO;

        for (KeyListItem item : getEnabledItems(keyListItemList)) {
            // 主号保护用 keyId：地址可能已被调试开关替换，地址比较不可靠
            if (item.keyId == masterEntry.id) continue;
            // 护栏：调试地址永不作为真实转账目的地
            if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                    && item.address != null
                    && item.address.equalsIgnoreCase(ArenaConfig.DEBUG_FIXED_ADDR)) continue;
            if (item.address == null || item.address.equalsIgnoreCase(masterEntry.address)) continue;
            BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
            if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                totalNeeded = totalNeeded.add(diff);
                String no = (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
                BigDecimal balF = new BigDecimal(bal)
                        .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
                needFundAddrs.add(item.address);
                needFundLabels.add(no + " (余额:" + balF + ")");
            }
        }

        showConfirmDialog(activity, callback, dialogHelper, buttonController, ksMgr, masterEntry,
                needFundAddrs, needFundLabels, totalNeeded, masterEntry.address, masterBalF);
    }

    private static List<KeyListItem> getEnabledItems(List<KeyListItem> list) {
        List<KeyListItem> enabled = new ArrayList<>();
        for (KeyListItem item : list) {
            if (item.enabled) enabled.add(item);
        }
        return enabled;
    }

    private static void showConfirmDialog(Activity activity, TaskLogCallback callback,
                                           MainDialogHelper dialogHelper,
                                           ButtonStateController buttonController,
                                           KeyStoreManager ksMgr,
                                           KeyStoreManager.KeyEntry masterEntry,
                                           List<String> addrs, List<String> labels,
                                           BigInteger total, String masterAddr,
                                           BigDecimal masterBal) {
        activity.runOnUiThread(() -> {
            if (addrs.isEmpty()) {
                dialogHelper.styledDialog()
                        .setTitle(R.string.title_batch_fund)
                        .setMessage(R.string.fund_no_need_msg)
                        .setPositiveButton(R.string.btn_ok, (d, w) -> buttonController.setRunning(false))
                        .setOnCancelListener(d -> buttonController.setRunning(false))
                        .show();
                return;
            }

            BigDecimal totalF = new BigDecimal(total)
                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
            StringBuilder sb = new StringBuilder();
            sb.append("主号: ").append(masterAddr.substring(0, 10)).append("...\n");
            sb.append("主号余额: ").append(masterBal).append(" fLGNS\n\n");
            sb.append("以下账号需要补齐到 100：\n");
            for (int i = 0; i < labels.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(labels.get(i)).append("\n");
            }
            sb.append("\n共 ").append(addrs.size()).append(" 个账号，需转入合计 ").append(totalF).append(" fLGNS");

            dialogHelper.styledDialog()
                    .setTitle(R.string.title_batch_fund)
                    .setMessage(sb.toString())
                    .setPositiveButton(R.string.btn_fund_confirm, (d, w) -> {
                        dialogHelper.showDecryptPasswordDialog(pwdCharArray ->
                            executeBatchTransfer(activity, callback, ksMgr, masterEntry,
                                    addrs, labels, pwdCharArray, buttonController));
                    })
                    .setNegativeButton(R.string.btn_cancel, (d, w) -> buttonController.setRunning(false))
                    .setOnCancelListener(d -> buttonController.setRunning(false))
                    .show();
        });
    }

    private static void executeBatchTransfer(Activity activity, TaskLogCallback callback,
                                              KeyStoreManager ksMgr,
                                              KeyStoreManager.KeyEntry masterEntry,
                                              List<String> addrs, List<String> labels,
                                              char[] pwdCharArray,
                                              ButtonStateController buttonController) {
        new Thread(() -> {
            try {
                String masterPk = ksMgr.decryptCipherText(masterEntry.cipherText, pwdCharArray);
                ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
                String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair);

                Web3j w3 = Web3AndroidUtil.dialAny();
                BigInteger gasPrice = w3.ethGasPrice().send().getGasPrice();
                BigInteger nonce = w3.ethGetTransactionCount(masterAddr,
                        DefaultBlockParameterName.PENDING).send().getTransactionCount();

                for (int i = 0; i < addrs.size(); i++) {
                    if (Web3AndroidUtil.isCancelRequested()) break;
                    String addr = addrs.get(i);
                    final int index = i;
                    BigInteger bal = ArenaContractApi.callErc20Balance(w3, addr);
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) >= 0) {
                        final String label = labels.get(index);
                        activity.runOnUiThread(() -> callback.onLog("", "FUND",
                                "➖ " + label + " 余额已够,跳过"));
                        continue;
                    }
                    BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                    try {
                        ArenaContractApi.doErc20Transfer(w3, masterKeyPair, addr, diff, nonce, gasPrice);
                        nonce = nonce.add(BigInteger.ONE);
                        final String label = labels.get(index);
                        final BigDecimal amount = new BigDecimal(diff).divide(
                                new BigDecimal(BigInteger.TEN.pow(18)),
                                2, java.math.RoundingMode.HALF_UP);
                        activity.runOnUiThread(() -> callback.onLog("", "FUND",
                                "✅ " + label + " 转入 " + amount + " fLGNS"));
                    } catch (Exception e) {
                        final String label = labels.get(index);
                        activity.runOnUiThread(() -> callback.onLog("", "FUND",
                                "❌ " + label + " 转账失败: " + Web3AndroidUtil.safeMessage(e)));
                    }
                }
                activity.runOnUiThread(() -> callback.onLog("", "FUND", "批量充值结束"));
            } catch (Exception e) {
                activity.runOnUiThread(() -> callback.onLog("", "FUND",
                        "❌ 批量充值失败: " + Web3AndroidUtil.safeMessage(e)));
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
                activity.runOnUiThread(() -> buttonController.setRunning(false));
            }
        }).start();
    }

    private static void showFundErrorDialog(Activity activity, MainDialogHelper dialogHelper,
                                             ButtonStateController buttonController, Exception e) {
        activity.runOnUiThread(() -> {
            dialogHelper.styledDialog()
                    .setMessage(activity.getString(R.string.fund_query_fail, Web3AndroidUtil.safeMessage(e)))
                    .setPositiveButton(R.string.btn_ok, (d, w) -> buttonController.setRunning(false))
                    .setOnCancelListener(d -> buttonController.setRunning(false))
                    .show();
        });
    }

    public static class FundPreviewResult {
        public boolean hasMaster = false;
        // 查询异常：非空表示余额没能读完（网络/节点问题），不能当成“无需充值”
        public Exception error = null;
        public KeyStoreManager.KeyEntry masterEntry = null;
        public BigInteger masterBalance = BigInteger.ZERO;
        // 主号行展示数据：与列表主号行同源（调试开关 ON 时为替换后地址及其实查余额）
        public String masterKeyNo = null;
        public String masterShowAddr = null;
        public BigInteger masterShowBalance = BigInteger.ZERO;
        public List<String> needFundAddrs = new ArrayList<>();
        public List<String> needFundLabels = new ArrayList<>();
        public List<FundDetail> fundDetails = new ArrayList<>();
        public BigInteger totalNeeded = BigInteger.ZERO;

        public boolean needsFunding() { return hasMaster && !needFundAddrs.isEmpty(); }

        public BigDecimal getMasterBalanceFormatted() {
            return new BigDecimal(masterBalance).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        public BigDecimal getTotalNeededFormatted() {
            return new BigDecimal(totalNeeded).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        public static class FundDetail {
            public String keyNo;
            public String address;
            public BigDecimal currentBalance;
            public BigDecimal neededAmount;
            public boolean sufficient;

            public FundDetail(String keyNo, String address, BigDecimal currentBalance, BigDecimal neededAmount) {
                this.keyNo = keyNo;
                this.address = address;
                this.currentBalance = currentBalance;
                this.neededAmount = neededAmount;
            }
        }
    }
}
