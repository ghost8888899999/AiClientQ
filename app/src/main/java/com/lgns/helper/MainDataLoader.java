package com.lgns.helper;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;

public class MainDataLoader {

    public static void refreshCipherKeyList(Activity activity, TaskLogCallback callback,
                                             List<KeyListItem> keyListItemList) {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();
                List<KeyStoreManager.KeyEntry> unique = removeDuplicates(originEntryList);
                List<KeyStoreManager.KeyEntry> sorted = applyCustomSort(unique, mgr.getCustomSortIdList());

                keyListItemList.clear();
                buildKeyItemList(keyListItemList, sorted);
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
}
