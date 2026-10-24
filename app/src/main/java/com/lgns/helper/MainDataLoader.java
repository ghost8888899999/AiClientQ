package com.lgns.helper;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
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

    /**
     * 调试专用：把调试地址池直接展开成主界面的展示行（与私钥数量无关，池里有几条就显示几条），
     * 地址按分组顺序连续平分，每组行数差不超过 1。
     * 返回的是纯展示用 KeyListItem：keyId 为负数、cipherText 为 null，不能拿去解密或转账；
     * 只改内存不写库，私钥管理页读库因此完全不受影响。
     */
    public static List<KeyListItem> buildDebugKeyItems(Activity activity) {
        List<KeyListItem> rows = new ArrayList<>();
        if (!ArenaConfig.DEBUG_FIXED_ADDR_ENABLE) return rows;

        String[] pool = ArenaConfig.DEBUG_FIXED_ADDRs;
        if (pool == null || pool.length == 0) {
            // 地址池为空时开关不产生效果：主界面仍按真实私钥清单展示
            android.util.Log.w("DEBUG_ADDR", "调试开关已打开，但 DEBUG_FIXED_ADDRs 为空，按真实清单展示");
            return rows;
        }

        List<String> groups = new ArrayList<>();
        for (String g : AppLogDbHelper.getInstance(activity).queryAllGroups()) {
            if (g != null && !g.isEmpty()) groups.add(g);
        }
        int groupCount = groups.size();

        for (int i = 0; i < pool.length; i++) {
            KeyListItem item = new KeyListItem();
            item.keyId = -(i + 1);                          // 负数：不是真实密钥，避免与主号 id 撞上
            item.seqNo = i + 1;
            item.displayNo = String.format(Locale.getDefault(), "%03d", i + 1);
            item.customCode = "";
            item.address = pool[i];
            item.cipherText = null;
            item.isChecked = true;
            item.isUsable = true;
            item.enabled = true;
            if (groupCount > 0) {
                // 连续平分：第 i 条地址落在第 (i*组数/地址数) 组
                int gi = (int) ((long) i * groupCount / pool.length);
                if (gi >= groupCount) gi = groupCount - 1;
                item.groupNo = groups.get(gi);
            }
            rows.add(item);
        }
        android.util.Log.d("DEBUG_ADDR", "调试展示行: 地址数=" + pool.length + ", 分组数=" + groupCount);
        return rows;
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
