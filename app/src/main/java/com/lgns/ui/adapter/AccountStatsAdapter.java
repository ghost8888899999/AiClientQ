package com.lgns.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AccountStatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ACCOUNT = 1;

    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    private final List<DisplayItem> allList = new ArrayList<>();
    private final List<DisplayItem> visibleList = new ArrayList<>();
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();

    public static class AccountStatsItem {
        public String name;
        public String address;
        public BigInteger balance;
        public int entriesToday;
        public int entriesRemaining;
        public int todayChampion;
        public int totalChampion;
        public BigInteger prize;
        public BigInteger totalEarnings;
        public String groupNo;

        public AccountStatsItem(String name, String address, BigInteger balance,
                                int entriesToday, int entriesRemaining,
                                int todayChampion, int totalChampion, BigInteger prize,
                                BigInteger totalEarnings) {
            this.name = name;
            this.address = address;
            this.balance = balance;
            this.entriesToday = entriesToday;
            this.entriesRemaining = entriesRemaining;
            this.todayChampion = todayChampion;
            this.totalChampion = totalChampion;
            this.prize = prize;
            this.totalEarnings = totalEarnings;
        }
    }

    public static class DisplayItem {
        public int type;
        public String groupName;
        public AccountStatsItem account;

        public static DisplayItem header(String groupName) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = groupName;
            return d;
        }

        public static DisplayItem account(String groupName, AccountStatsItem account) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_ACCOUNT;
            d.groupName = groupName;
            d.account = account;
            return d;
        }
    }

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
                .inflate(R.layout.item_account_stats, parent, false);
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

    // 快照当前展示中的账号数据（地址→数据），供本地结构刷新时继承已加载的链上数值
    public Map<String, AccountStatsItem> snapshotAccountItems() {
        Map<String, AccountStatsItem> map = new java.util.HashMap<>();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_ACCOUNT && d.account != null && d.account.address != null) {
                map.put(d.account.address, d.account);
            }
        }
        return map;
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

    private int getBadgeColor(String name) {
        return BADGE_COLORS[Math.abs(name.hashCode()) % BADGE_COLORS.length];
    }

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

    static class AccountVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAddr, tvStatusTag;
        TextView tvBalance, tvEntries, tvTodayChampion, tvTotalChampion, tvPrize;

        AccountVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_account_name);
            tvAddr = itemView.findViewById(R.id.tv_account_addr);
            tvStatusTag = itemView.findViewById(R.id.tv_status_tag);
            tvBalance = itemView.findViewById(R.id.tv_balance);
            tvEntries = itemView.findViewById(R.id.tv_entries);
            tvTodayChampion = itemView.findViewById(R.id.tv_today_champion);
            tvTotalChampion = itemView.findViewById(R.id.tv_total_champion);
            tvPrize = itemView.findViewById(R.id.tv_prize);
        }

        void bind(AccountStatsItem item) {
            android.content.Context ctx = itemView.getContext();
            tvName.setText(item.name);

            if (item.address != null && item.address.length() > 10) {
                tvAddr.setText(item.address.substring(0, 6) + "..." +
                        item.address.substring(item.address.length() - 4));
            } else {
                tvAddr.setText(item.address != null ? item.address : ctx.getString(R.string.common_placeholder));
            }

            int maxEntries = item.entriesToday + item.entriesRemaining;
            if (item.entriesRemaining == 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_full));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
            } else if (item.entriesToday > 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_running));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_green);
            } else {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_not_entered));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_gray);
            }

            tvBalance.setText(formatToken(item.balance));
            tvEntries.setText(item.entriesToday + "/" + maxEntries);
            tvTodayChampion.setText(String.valueOf(item.todayChampion));
            tvTotalChampion.setText(String.valueOf(item.totalChampion));
            tvPrize.setText(formatToken(item.totalEarnings));
        }

        private String formatToken(BigInteger wei) {
            if (wei == null) return "0.00";
            BigDecimal bd = new BigDecimal(wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
            return bd.toPlainString();
        }
    }
}