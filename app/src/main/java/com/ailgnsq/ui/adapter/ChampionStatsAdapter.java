package com.ailgnsq.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ailgnsq.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冠军榜适配器：按分组展示各账号的夺冠/名次统计，分组头可折叠。
 *
 * <p>与 {@link AccountStatsAdapter} 同构：页面把统计结果组装成 {@code DisplayItem}，
 * 适配器只负责摊平渲染与维护展开态，不做任何计算。</p>
 */
public class ChampionStatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 分组头行 */
    private static final int TYPE_HEADER = 0;
    /** 账号行 */
    private static final int TYPE_ACCOUNT = 1;

    /** 分组徽标颜色盘：按分组名 hash 稳定取色，与账号统计页同一套色值 */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };
    // region 数据源与分组状态

    /** 全量行：包含所有分组头与账号行 */
    private final List<DisplayItem> allList = new ArrayList<>();
    /** 可见行：折叠的分组只留组头 */
    private final List<DisplayItem> visibleList = new ArrayList<>();
    /** 分组名 → 是否展开，刷新时沿用 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();

    // endregion

    // region 数据模型

    /** 锦标赛统计页的一行账号：场次/冠军数均来自链上累计值 */
    public static class ChampionStatsItem {
        /** 展示用标签（优先自定义编号，没填用 displayNo） */
        public String name;
        /** 账号地址，也是与链上结果匹配的 key */
        public String address;
        /** 所属分组名，用于折叠与上色 */
        public String groupNo;
        /** 累计对局场次（{@code battles}） */
        public long battles;
        /** 累计冠军次数；小于 0 表示还没拿到真实值，绑定时要显示占位符 */
        public long championships;
        /** 本轮链上数据还没回来：场次/胜率给占位，不把 0 当成真实值 */
        public boolean pending;
        /** 本轮查询结束但链上没返回这个地址：整行占位 + “未查到” */
        public boolean unknown;

    }

    /** 列表行：组头与账号行共用，用 {@link #type} 区分 */
    public static class DisplayItem {
        /** 行类型，取值同 {@code TYPE_HEADER} / {@code TYPE_ACCOUNT} */
        public int type;
        /** 所属分组名：组头行直接显示，账号行用于折叠筛选 */
        public String groupName;
        /** 账号数据；组头行为 null，绑定时需先判 type */
        public ChampionStatsItem account;

        /** 构造分组头行（type=TYPE_HEADER） */
        public static DisplayItem header(String groupName) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = groupName;
            return d;
        }

        /** 构造账号行（type=TYPE_ACCOUNT，必须带 account） */
        public static DisplayItem account(String groupName, ChampionStatsItem account) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_ACCOUNT;
            d.groupName = groupName;
            d.account = account;
            return d;
        }
    }

    // endregion

    // region 列表回调

    @Override
    public int getItemViewType(int position) {
        return visibleList.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_champion_stats, parent, false);
        return new AccountVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem item = visibleList.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.tvGroupName.setText(item.groupName);
            h.viewColor.setBackgroundColor(getBadgeColor(item.groupName));
            int count = 0;
            for (DisplayItem d : allList) {
                if (d.type == TYPE_ACCOUNT && d.groupName.equals(item.groupName)) count++;
            }
            h.tvGroupCount.setText(h.itemView.getContext().getString(R.string.common_count_fmt, count));
            boolean expanded = expandedMap.getOrDefault(item.groupName, true);
            h.ivExpand.setRotation(expanded ? 90 : 0);
            h.itemView.setOnClickListener(v -> toggleGroup(item.groupName));
        } else if (holder instanceof AccountVH) {
            ((AccountVH) holder).bind(item.account);
        }
    }

    @Override
    public int getItemCount() {
        return visibleList.size();
    }

    // endregion

    // region 数据更新与分组展开

    /** 整体换数据：沿用旧的分组展开态（新分组默认展开），再重建可见列表 */
    public void setItems(List<DisplayItem> items) {
        Map<String, Boolean> prev = new LinkedHashMap<>(expandedMap);
        allList.clear();
        allList.addAll(items);
        expandedMap.clear();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_HEADER) {
                Boolean p = prev.get(d.groupName);
                expandedMap.put(d.groupName, p != null ? p : true);
            }
        }
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    private void toggleGroup(String groupName) {
        expandedMap.put(groupName, !expandedMap.getOrDefault(groupName, true));
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    private void rebuildVisibleList() {
        visibleList.clear();
        String currentGroup = null;
        boolean currentExpanded = true;
        for (DisplayItem item : allList) {
            if (item.type == TYPE_HEADER) {
                currentGroup = item.groupName;
                currentExpanded = expandedMap.getOrDefault(currentGroup, true);
                visibleList.add(item);
            } else if (currentExpanded) {
                visibleList.add(item);
            }
        }
    }

    /** 分组名的稳定取色：同一分组每次绑定颜色一致，不随滚动变色 */
    private int getBadgeColor(String name) {
        return BADGE_COLORS[Math.abs(name.hashCode()) % BADGE_COLORS.length];
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
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            viewColor = v.findViewById(R.id.view_color);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }

    /** 账号行 ViewHolder：场次/冠军/胜率三列 + 状态胶囊，占位逻辑在 bind 里 */
    static class AccountVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAddr, tvStatusTag;
        TextView tvBattles, tvChampion, tvWinRate;

        AccountVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_account_name);
            tvAddr = itemView.findViewById(R.id.tv_account_addr);
            tvStatusTag = itemView.findViewById(R.id.tv_status_tag);
            tvBattles = itemView.findViewById(R.id.tv_total_battles);
            tvChampion = itemView.findViewById(R.id.tv_champion);
            tvWinRate = itemView.findViewById(R.id.tv_win_rate);
        }

        /** 绑定一行：地址缩略 + 状态胶囊 + 三项指标；pending / unknown 用占位，不伪造 0 值 */
        void bind(ChampionStatsItem item) {
            android.content.Context ctx = itemView.getContext();
            tvName.setText(item.name);

            if (item.address != null && item.address.length() > 10) {
                tvAddr.setText(item.address.substring(0, 6) + "..." +
                        item.address.substring(item.address.length() - 4));
            } else {
                tvAddr.setText(item.address != null ? item.address : ctx.getString(R.string.common_placeholder));
            }

            // 还没查到结果：不伪造状态位；场次/胜率留占位，冠军沿用主界面已加载值（>=0 才可信）
            if (item.pending) {
                tvStatusTag.setVisibility(View.GONE);
                tvBattles.setText(R.string.common_placeholder);
                tvChampion.setText(item.championships >= 0
                        ? String.valueOf(item.championships)
                        : ctx.getString(R.string.common_placeholder));
                tvWinRate.setText(R.string.common_placeholder);
                return;
            }
            // 视图复用：非 pending 行必须把状态位恢复出来
            tvStatusTag.setVisibility(View.VISIBLE);
            if (item.unknown) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_unknown));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
                tvBattles.setText(R.string.common_placeholder);
                tvChampion.setText(R.string.common_placeholder);
                tvWinRate.setText(R.string.common_placeholder);
                return;
            }

            if (item.championships > 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_champion));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_green);
            } else if (item.battles > 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_joined));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_gray);
            } else {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_not_entered));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_gray);
            }

            tvBattles.setText(String.valueOf(item.battles));
            tvChampion.setText(String.valueOf(item.championships));
            // 胜率 = 冠军次数(赢场) / 总场次；保留 1 位小数，避免 1/3 被截成 33% 丢掉精度
            tvWinRate.setText(item.battles > 0
                    ? String.format(java.util.Locale.US, "%.1f%%", item.championships * 100.0 / item.battles)
                    : ctx.getString(R.string.common_placeholder));
        }
    }

    // endregion

}
