package com.ailgnsq.helper;

import android.app.Activity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.manager.KeyStoreManager;
import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.model.KeyListItem;
import com.ailgnsq.task.TaskLogCallback;

/**
 * 主界面密钥清单的加载工具：把数据库里的 {@link KeyStoreManager.KeyEntry} 转成界面用的
 * {@link KeyListItem}，并在转换前做「去重 + 恢复自定义排序」两步整理。
 *
 * <p>为什么不在读库时直接排好：读库返回的是 insertion 顺序，用户手工拖拽出来的顺序
 * 单独存在 {@code customSortIdList} 里，两者必须在这里合并，顺序才是用户看到的样子。</p>
 *
 * <p>线程模型：{@link #refreshCipherKeyList} 自己在后台线程读库并原地改写传入的 List，
 * 调用方负责在回调/后续刷新里切回主线程；只有异常提示会主动 post 回 UI 线程。</p>
 */
public class MainDataLoader {

    /**
     * 后台刷新主界面密钥清单：读库 → 按 id 去重 → 按用户自定义顺序重排 → 拼成展示行。
     *
     * <p>整个过程直接改写调用方传入的 {@code keyListItemList}（先 clear 再填充），
     * 因此调用方必须保证：</p>
     * <ul>
     *   <li>传入的是主界面 Adapter 共享的那个 List 实例，而不是副本；</li>
     *   <li>读库期间不要在 UI 线程上遍历同一个 List（clear 发生在后台线程，并发遍历会读到半成品）。</li>
     * </ul>
     *
     * @param activity        用于出异常时把提示切回 UI 线程
     * @param callback        日志回调，只用来提示刷新失败的原因
     * @param keyListItemList 主界面的展示行容器，方法返回时已被原地替换为新内容
     */
    public static void refreshCipherKeyList(Activity activity, TaskLogCallback callback,
                                             List<KeyListItem> keyListItemList) {
        new Thread(() -> {
            try {
                // 主界面清单只读元数据（编号/地址/分组/启用），不需要解密会话：
                // 用会话实例会在空闲超时后抛异常，导致刷新整段失败
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();
                // 历史迁移/并发写入可能留下同 id 多条，先收敛再排序，否则界面上会出现重复行
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

    /**
     * 按 {@code id} 去重，保留首次出现的那条。
     *
     * <p>用 O(n²) 的两层循环而不是 HashSet：这里的量级是几十到几百条，
     * 省掉包装对象的分配和装箱开销反而更直接；同时不需要额外引入排序语义，
     * 天然保持原来的读库顺序，交给 {@link #applyCustomSort} 再决定最终次序。</p>
     */
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

    /**
     * 按用户保存的自定义顺序重排，未出现在排序表里的条目按原顺序追加到末尾。
     *
     * <p>两段式处理的原因：{@code customSortIds} 只记录被拖拽过的 id，
     * 新增的密钥、从 SP 迁移进来的旧数据都不会在其中；
     * 如果只按排序表输出，这些条目会从界面上「消失」。
     * 第二段循环保证任何一条都不会漏掉，且相对顺序保持不变。</p>
     *
     * @param unique        已去重的条目，作为追加顺序的依据
     * @param customSortIds 用户自定义的 id 序列；为空时整段退化为原顺序
     */
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

    /**
     * 把存储层条目拼成界面展示行。
     *
     * <p>{@code displayNo} 取的是 {@code %03d(entry.id)}（不是列表序号），
     * 这样用户拖拽排序后编号不会跟着变，报错日志里的编号也能直接对回数据库主键；
     * {@code seqNo} 才是当前显示顺序。两个默认开关都置 true，实际的启用/勾选状态由界面再按业务覆盖。</p>
     */
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
