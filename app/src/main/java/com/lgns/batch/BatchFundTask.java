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
                result.masterBalance = ArenaContractApi.callErc20Balance(web3j, result.masterEntry.address);

                for (KeyListItem item : enabledList) {
                    if (item.address == null || item.address.equalsIgnoreCase(result.masterEntry.address)) continue;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                        BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                        result.totalNeeded = result.totalNeeded.add(diff);
                        String no = (item.customCode != null && !item.customCode.isEmpty())
                                ? item.customCode : item.displayNo;
                        BigDecimal balF = new BigDecimal(bal).divide(
                                new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
                        result.needFundAddrs.add(item.address);
                        result.needFundLabels.add(no + " (余额:" + balF + ")");
                    }
                }
            } finally {
                try { web3j.shutdown(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            android.util.Log.e("BatchFundTask", "queryFundPreview error", e);
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
                        .setTitle(R.string.fund_no_need_title)
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
                    .setTitle(R.string.fund_confirm_title)
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
        public KeyStoreManager.KeyEntry masterEntry = null;
        public BigInteger masterBalance = BigInteger.ZERO;
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

            public FundDetail(String keyNo, String address, BigDecimal currentBalance, BigDecimal neededAmount) {
                this.keyNo = keyNo;
                this.address = address;
                this.currentBalance = currentBalance;
                this.neededAmount = neededAmount;
            }
        }
    }
}
