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

/**
 * 批量充值任务（fLGNS）：由私钥管理的主号给其余启用账号补齐入场所需的代币。
 *
 * <p>与 {@link BatchGasFundTask} 的区别：这里充的是入场门票 fLGNS，手续费币 DAI 由后者负责，
 * 两者不能合并——入场需要「fLGNS 余额 + DAI 手续费」同时满足。</p>
 *
 * <p>流程：预览差额（{@code queryFundPreview}）→ 主号余额复核 → 弹窗确认 → 逐笔转账。
 * 预览与执行之间余额可能变化，因此执行前必须重新校验主号余额，不能直接沿用预览结果。</p>
 *
 * <p>依赖 UI 回调（{@link ButtonStateController}）驱动按钮状态，任务本身不直接操作控件。</p>
 */
public class BatchFundTask {
    // region 回调接口

    /**
     * 按钮互斥状态的最小接口：由页面实现（通常是 Activity 的 isRunning/setRunning），
     * 任务只靠它判定“是否已有批量任务在跑”并负责收尾解锁。
     */
    public interface ButtonStateController {
        /** @return 当前是否已有任务在执行 */
        boolean isRunning();

        /** 由任务在入口/出口置位；任何提前 return 的分支都必须记得改回 false */
        void setRunning(boolean running);
    }

    // endregion

    // region 对外入口

    /**
     * 独立按钮触发的批量充值：确认主号 → 算差额 → 弹窗确认 → 输密码 → 逐笔转账。
     *
     * <p>整条链路在后台线程执行，弹窗与按钮状态一律切回主线程；任一步退出都要把按钮
     * 恢复可点，否则界面会卡在「执行中」。</p>
     */
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

    /**
     * 工作流调用版充值：没有弹窗，直接把差额补齐后返回。
     *
     * <p>先筛出 fLGNS 余额低于单席门票（100）的账号，再由主号逐笔转差额。
     * 每笔转账前重新读一次余额，已够就跳过；单笔失败只记日志，剩下的账号继续。</p>
     */
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

                // 先筛一遍需要充值的账号：余额已过 100 的不进转账队列
                for (KeyListItem item : enabledList) {
                    // 主号保护用 keyId：地址可能已被调试开关替换，地址比较不可靠
                    if (item.keyId == masterId) continue;
                    // 护栏：调试地址（单条/地址池）永不作为真实转账目的地
                    if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                            && ArenaConfig.isDebugAddr(item.address)) continue;
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

                // nonce 在主号上一路递增：同一出资方的多笔 transfer 必须串行，不能并发
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

    // endregion

    // region 预演与校验

    /**
     * 查询进度回调（供 BatchFundConfirmActivity 使用）。
     */
    public interface ProgressCallback {
        /**
         * 每查完一个账号回调一次。
         *
         * @param done   已完成数量
         * @param total  待查总数（只算非主号且启用的账号）
         * @param detail 这一行的余额/缺口，页面拿它原位回填
         */
        void onProgress(int done, int total, FundPreviewResult.FundDetail detail);
    }

    /** 不带进度回调的预览重载（一次性拿全量结果） */
    public static FundPreviewResult queryFundPreview(List<KeyListItem> enabledList) {
        return queryFundPreview(enabledList, null);
    }

    /**
     * 预览充值需求：主号卡片数据 + 每个启用账号的「当前余额 / 需要补多少」。
     *
     * <p>只读不写：不发交易、不改私钥库，结果交给确认页展示与二次确认。
     * 主号自己、禁用账号都不计入缺口。</p>
     */
    public static FundPreviewResult queryFundPreview(List<KeyListItem> enabledList, ProgressCallback callback) {
        FundPreviewResult result = new FundPreviewResult();
        try {
            // 调试地址替换已上移到 MainDataLoader.applyDebugFixedAddr（按分组平分地址池）：
            // 这份 KeyListItem 与主界面共用，就地改写会把主界面那一行的地址串掉

            // 预览只算缺口，不解密：会话过期也要能进充值页
            KeyStoreManager mgr = AppGlobal.getMetaManager();
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
                int total = 0;
                for (KeyListItem item : enabledList) {
                    if (item.address == null || item.keyId == masterId || !item.enabled) continue;
                    total++;
                }
                int done = 0;
                for (KeyListItem item : enabledList) {
                    if (item.address == null) continue;
                    if (item.keyId == masterId) continue;
                    if (!item.enabled) continue;
                    String no = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                    done++;
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
                    // 结果先入列表再回调，页面拿到 detail 即可立刻回填这一行
                    if (callback != null) callback.onProgress(done, total, d);

                    // 合计只用于页面展示：调试地址也要计入，否则调试模式下合计恒为 0、
                    // 确认按钮被置灰，看不到真实效果；真实转账由 executeAutoFund /
                    // processBatchFund 里的 isDebugAddr 护栏拦截
                    if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
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

    // endregion

    // region 主号与错误提示

    /** 按 id 从库里重新加载主号条目；找不到返回 null（主号可能已被删除） */
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

    // endregion

    // region 执行流程

    /**
     * 统计本轮缺口并弹确认框。
     * 主号自身与调试地址都不进名单：前者不该给自己转账，后者只是界面演示数据。
     */
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
            // 护栏：调试地址（单条/地址池）永不作为真实转账目的地
            if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && ArenaConfig.isDebugAddr(item.address)) continue;
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

    /** 确认框：列出每个待补账号与合计金额，确认后走密码校验再执行 */
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

    /**
     * 真正执行转账（后台线程）：主号签名逐笔转 fLGNS。
     *
     * <p>每笔前重查余额（确认页停留期间对方可能已经自行充过），结束后清零密码数组
     * 并恢复按钮状态，避免明文密码在内存里停留。</p>
     */
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

    // endregion

    // region 结果模型

    /**
     * 充值预览结果：主号卡片数据 + 每个启用账号的余额/缺口。
     *
     * <p>关键约定：{@link #error} 非空表示余额没读完，此时不能当成“无需充值”——
     * 否则网络一抖就会把有缺口的账号当成已足够。
     */
    public static class FundPreviewResult {
        /** 主号是否存在且带地址；false 时其余字段均无意义 */
        public boolean hasMaster = false;
        /** 查询异常：非空表示余额没能读完（网络/节点问题），不能当成“无需充值” */
        public Exception error = null;
        /** 主号在库里的条目（含密文），执行阶段靠它取私钥 */
        public KeyStoreManager.KeyEntry masterEntry = null;
        /** 主号真实余额（资金源口径，单位 wei） */
        public BigInteger masterBalance = BigInteger.ZERO;
        /** 主号行展示编号（自定义编号优先，否则 id 的 3 位格式） */
        public String masterKeyNo = null;
        /** 主号行展示地址：与列表主号行同源（调试开关 ON 时为替换后地址） */
        public String masterShowAddr = null;
        /** 主号行展示余额：展示地址被替换时会与 {@link #masterBalance} 不同 */
        public BigInteger masterShowBalance = BigInteger.ZERO;
        /** 需补款的地址（执行阶段的转账目的地） */
        public List<String> needFundAddrs = new ArrayList<>();
        /** 与 {@link #needFundAddrs} 同序的展示标签：编号 + 当前余额 */
        public List<String> needFundLabels = new ArrayList<>();
        /** 每个启用账号一行的明细，确认页直接拿来铺列表 */
        public List<FundDetail> fundDetails = new ArrayList<>();
        /** 全部缺口汇总（单位 wei） */
        public BigInteger totalNeeded = BigInteger.ZERO;

        /** 是否真的需要补款：主号存在且有缺口地址 */
        public boolean needsFunding() { return hasMaster && !needFundAddrs.isEmpty(); }

        /** 主号余额转展示用小数值（18 位精度、保留 2 位） */
        public BigDecimal getMasterBalanceFormatted() {
            return new BigDecimal(masterBalance).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        /** 汇总缺口转展示用小数值（18 位精度、保留 2 位） */
        public BigDecimal getTotalNeededFormatted() {
            return new BigDecimal(totalNeeded).divide(
                    new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
        }

        /**
         * 单个账号的充值明细（金额已换算成小数，直接可上屏）。
         */
        public static class FundDetail {
            /** 账号展示编号 */
            public String keyNo;
            /** 收款地址 */
            public String address;
            /** 当前余额（已换算，2 位小数） */
            public BigDecimal currentBalance;
            /** 还需补多少（已换算，2 位小数；已够时为 0） */
            public BigDecimal neededAmount;
            /** 余额是否已达入场费；由查询方回填，确定后才能决定行样式 */
            public boolean sufficient;

            /**
             * 构造明细行：金额必须已经换算成小数（不是 wei），本类不再做单位转换。
             *
             * <p>{@code sufficient} 不在这里赋值：余额要等链上查询回来才知道，
             * 由调用方在拿到结果后置位，默认 false 表示“还没确认够不够”。</p>
             */
            public FundDetail(String keyNo, String address, BigDecimal currentBalance, BigDecimal neededAmount) {
                this.keyNo = keyNo;
                this.address = address;
                this.currentBalance = currentBalance;
                this.neededAmount = neededAmount;
            }
        }
    }

    // endregion

}
