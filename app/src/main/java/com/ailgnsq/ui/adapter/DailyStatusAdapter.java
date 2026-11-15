package com.ailgnsq.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ailgnsq.model.DailyStatusItem;
import com.ailgnsq.R;
import com.ailgnsq.manager.AppGlobal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日状态适配器：按分组展示每个账号在当天的入场/领取完成情况，分组头可折叠。
 *
 * <p>数据来自 {@code DailyStatusItem}，适配器只做摊平静态展示，不发起任何网络请求；
 * 页面定时刷新时整表替换，展开态随之重建（第一个分组默认展开）。</p>
 */
public class DailyStatusAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /** 分组头行 */
    private static final int TYPE_HEADER = 0;
    /** 账号每日状态行 */
    private static final int TYPE_ITEM = 1;

    private List<Object> allItemList; // 完整列表（所有分组+数据）
    private List<Object> visibleItemList; // 当前可见列表
    private Map<String, Boolean> groupExpandedMap; // 分组展开状态
    private List<String> groupOrder; // 分组顺序
    // region 构造与数据入口

    /** 空表构造：四个容器全部初始化，展开状态默认全折叠，等 setItems 时再重建 */
    public DailyStatusAdapter() {
        this.allItemList = new ArrayList<>();
        this.visibleItemList = new ArrayList<>();
        this.groupExpandedMap = new LinkedHashMap<>();
        this.groupOrder = new ArrayList<>();
    }

    /**
     * 整表替换数据：按传入顺序切分组，第一个分组默认展开，其余默认折叠。
     * 展开状态每次重建（不跨刷新沿用）——这份表看的是「今天」的进度，每天重来一次更符合直觉。
     */
    public void setItemList(List<DailyStatusItem> list) {
        allItemList.clear();
        visibleItemList.clear();
        groupExpandedMap.clear();
        groupOrder.clear();

        if (list == null || list.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        // 按分组构建完整列表
        String currentGroup = null;
        int groupIndex = 0;
        String ungroupedName = com.ailgnsq.manager.AppGlobal.getAppContext()
                .getString(R.string.common_ungrouped);
        for (DailyStatusItem item : list) {
            String group = item.groupNo != null && !item.groupNo.isEmpty() ? item.groupNo : ungroupedName;
            if (!group.equals(currentGroup)) {
                allItemList.add(group);
                groupOrder.add(group);
                // 第一个分组展开，其它折叠
                groupExpandedMap.put(group, groupIndex == 0);
                currentGroup = group;
                groupIndex++;
            }
            allItemList.add(item);
        }

        // 构建可见列表（只包含展开的分组）
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    private void rebuildVisibleList() {
        visibleItemList.clear();
        String currentGroup = null;
        boolean currentExpanded = false;
        for (Object obj : allItemList) {
            if (obj instanceof String) {
                currentGroup = (String) obj;
                currentExpanded = groupExpandedMap.getOrDefault(currentGroup, false);
                visibleItemList.add(obj); // 分组标题始终可见
            } else if (currentExpanded) {
                visibleItemList.add(obj); // 只添加展开分组的数据项
            }
        }
    }

    // endregion

    // region 列表回调

    @Override
    public int getItemViewType(int position) {
        return visibleItemList.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_header, parent, false);
            return new HeaderVH(v);
        } else {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_daily_status, parent, false);
            return new ItemVH(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object obj = visibleItemList.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH vh = (HeaderVH) holder;
            String groupName = (String) obj;
            vh.tvGroupName.setText(groupName);
            // 统计该分组的数据项数量
            int count = 0;
            boolean counting = false;
            for (Object item : allItemList) {
                if (item instanceof String) {
                    if (counting) break;
                    if (item.equals(groupName)) counting = true;
                } else if (counting) {
                    count++;
                }
            }
            vh.tvGroupCount.setText(vh.itemView.getContext().getString(R.string.daily_group_count_fmt, count));
            // 展开/折叠图标旋转（对标私钥管理）
            boolean expanded = groupExpandedMap.getOrDefault(groupName, false);
            vh.ivExpand.setRotation(expanded ? 90 : 0);
            // 点击展开/折叠
            vh.itemView.setOnClickListener(v -> toggleGroup(groupName));
        } else if (holder instanceof ItemVH) {
            DailyStatusItem item = (DailyStatusItem) obj;
            ItemVH vh = (ItemVH) holder;
            vh.tvSeq.setText(String.valueOf(getItemPosition(position)));
            vh.tvKeyNo.setText(item.keyNo);
            vh.tvTotalQd.setText(String.valueOf(item.totalQd));
            vh.tvTodayQd.setText(String.valueOf(item.todayQd));
            vh.tvTodayEnter.setText(String.valueOf(item.todayEnter));
            if (item.isFailed) {
                vh.tvStatusBadge.setVisibility(View.VISIBLE);
                vh.tvStatusBadge.setText(vh.itemView.getContext().getString(R.string.daily_status_failed));
                vh.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_fail);
                vh.itemRoot.setBackgroundColor(0xFFFFFBFB);
            } else {
                vh.tvStatusBadge.setVisibility(View.VISIBLE);
                vh.tvStatusBadge.setText(vh.itemView.getContext().getString(R.string.daily_status_normal));
                vh.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge);
                vh.itemRoot.setBackgroundColor(0xFFFFFFFF);
            }
        }
    }

    private void toggleGroup(String groupName) {
        boolean expanded = groupExpandedMap.getOrDefault(groupName, false);
        groupExpandedMap.put(groupName, !expanded);
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    /** 组内序号：从当前位置往前数到本组组头，得到「本组第几」而不是全局第几 */
    private int getItemPosition(int position) {
        int seq = 1;
        for (int i = position - 1; i >= 0; i--) {
            if (visibleItemList.get(i) instanceof String) break;
            seq++;
        }
        return seq;
    }

    @Override
    public int getItemCount() {
        return visibleItemList.size();
    }

    // endregion

    // region ViewHolder

    /** 分组头 ViewHolder：组名 + 人数 + 颜色条 + 展开箭头 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvGroupName, tvGroupCount;
        View viewColor;
        ImageView ivExpand;
        HeaderVH(View v) {
            super(v);
            viewColor = v.findViewById(R.id.view_color);
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }

    /** 日结行 ViewHolder：序号、编号、累计次数、今日次数、入场次数、状态徽章 */
    static class ItemVH extends RecyclerView.ViewHolder {
        TextView tvSeq, tvKeyNo, tvTotalQd, tvTodayQd, tvTodayEnter, tvStatusBadge;
        View itemRoot;
        ItemVH(View v) {
            super(v);
            itemRoot = v.findViewById(R.id.item_root);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvTotalQd = v.findViewById(R.id.tv_total_qd);
            tvTodayQd = v.findViewById(R.id.tv_today_qd);
            tvTodayEnter = v.findViewById(R.id.tv_today_enter);
            tvStatusBadge = v.findViewById(R.id.tv_status_badge);
        }
    }

    // endregion

}
