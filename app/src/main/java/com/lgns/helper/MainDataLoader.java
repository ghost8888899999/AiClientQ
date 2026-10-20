package com.lgns.helper;

import android.app.Activity;
import android.widget.TextView;
import org.web3j.protocol.Web3j;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

public class MainDataLoader {

    public static void refreshCipherKeyList(Activity activity, TaskLogCallback callback,
                                             List<KeyListItem> keyListItemList,
                                             TextView tvEnabledCount, TextView tvTokenCount,
                                             TextView tvQdCount) {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();
                List<KeyStoreManager.KeyEntry> unique = removeDuplicates(originEntryList);
                List<KeyStoreManager.KeyEntry> sorted = applyCustomSort(unique, mgr.getCustomSortIdList());
                
                keyListItemList.clear();
                buildKeyItemList(keyListItemList, sorted);
                
                updateEnabledCount(activity, keyListItemList, tvEnabledCount, tvTokenCount, tvQdCount);
                loadOnChainData(activity, callback, keyListItemList, tvTokenCount, tvQdCount);
            } catch (Exception e) {
                activity.runOnUiThread(() -> callback.onLog("", "", "错误：刷新密钥列表异常：" + e.getMessage()));
            }
        }).start();
    }

    private static List<KeyStoreManager.KeyEntry> removeDuplicates(List<KeyStoreManager.KeyEntry> list) {
        List<KeyStoreManager.KeyEntry> unique = new ArrayList<>();
        for (KeyStoreManager.KeyEntry e : list) {
            boolean dup = false;
            for (KeyStoreManager.KeyEntry u : unique) {
                if (u.id == e.id) { dup = true; break; }
            }
            if (!dup) unique.add(e);
        }
        return unique;
    }

    private static List<KeyStoreManager.KeyEntry> applyCustomSort(
            List<KeyStoreManager.KeyEntry> unique, List<Long> customSortIds) {
        List<KeyStoreManager.KeyEntry> sorted = new ArrayList<>();
        for (Long sortId : customSortIds) {
            for (KeyStoreManager.KeyEntry e : unique) {
                if (e.id == sortId) { sorted.add(e); break; }
            }
        }
        for (KeyStoreManager.KeyEntry e : unique) {
            boolean exist = false;
            for (KeyStoreManager.KeyEntry s : sorted) {
                if (s.id == e.id) { exist = true; break; }
            }
            if (!exist) sorted.add(e);
        }
        return sorted;
    }

    private static void buildKeyItemList(List<KeyListItem> keyListItemList,
                                          List<KeyStoreManager.KeyEntry> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            KeyStoreManager.KeyEntry entry = sorted.get(i);
            KeyListItem item = new KeyListItem();
            item.keyId = entry.id;
            item.seqNo = i + 1;
            item.displayNo = String.format(Locale.getDefault(), "%03d", entry.id);
            item.cipherText = entry.cipherText;
            item.address = entry.address;
            item.isUsable = true;
            item.isChecked = true;
            item.customCode = entry.customCode;
            item.groupNo = entry.groupNo;
            item.enabled = entry.enabled;
            keyListItemList.add(item);
        }
    }

    private static void updateEnabledCount(Activity activity, List<KeyListItem> keyListItemList,
                                            TextView tvEnabledCount, TextView tvTokenCount,
                                            TextView tvQdCount) {
        activity.runOnUiThread(() -> {
            int enabledCount = 0;
            for (KeyListItem item : keyListItemList) {
                if (item.enabled) enabledCount++;
            }
            tvEnabledCount.setText(String.valueOf(enabledCount));
            tvTokenCount.setText("0");
            tvQdCount.setText("0");
        });
    }

    private static void loadOnChainData(Activity activity, TaskLogCallback callback,
                                         List<KeyListItem> keyListItemList,
                                         TextView tvTokenCount, TextView tvQdCount) {
        List<KeyListItem> enabledItems = getEnabledItems(keyListItemList);
        if (enabledItems.isEmpty()) return;

        new Thread(() -> {
            try {
                Web3j web3j = Web3AndroidUtil.dialAny();
                List<String> addrs = extractAddresses(enabledItems);
                if (addrs.isEmpty()) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                    return;
                }
                
                Map<String, ArenaContractApi.PlayerSummary> summaries =
                        ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);
                try { web3j.shutdown(); } catch (Exception ignore) {}

                long totalTokens = 0, totalQd = 0;
                for (ArenaContractApi.PlayerSummary s : summaries.values()) {
                    if (s.walletBalance != null) totalTokens += s.walletBalance.longValue();
                    if (s.championships != null) totalQd += s.championships.longValue();
                }
                
                final String tokenStr = formatToken(totalTokens);
                final String qdStr = String.valueOf(totalQd);
                activity.runOnUiThread(() -> {
                    tvTokenCount.setText(tokenStr);
                    tvQdCount.setText(qdStr);
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> callback.onLog("", "", "查询链上数据异常：" + t.getClass().getSimpleName()));
            }
        }).start();
    }

    private static List<KeyListItem> getEnabledItems(List<KeyListItem> list) {
        List<KeyListItem> enabled = new ArrayList<>();
        for (KeyListItem item : list) {
            if (item.enabled) enabled.add(item);
        }
        return enabled;
    }

    private static List<String> extractAddresses(List<KeyListItem> items) {
        List<String> addrs = new ArrayList<>();
        for (KeyListItem item : items) {
            if (item.address != null && !item.address.isEmpty()) {
                addrs.add(item.address);
            }
        }
        return addrs;
    }

    private static String formatToken(long weiAmount) {
        if (weiAmount == 0) return "0";
        BigDecimal wei = BigDecimal.valueOf(weiAmount);
        BigDecimal divisor = BigDecimal.TEN.pow(18);
        BigDecimal result = wei.divide(divisor, 2, java.math.RoundingMode.DOWN);
        return result.toPlainString();
    }
}
