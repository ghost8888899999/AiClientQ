package com.ailgnsq.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ailgnsq.R;
import com.ailgnsq.model.ArenaConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号统计列表适配器：把「分组头 + 账号行」摊平成单列表，分组头负责折叠/展开。
 *
 * <p>职责边界：只做展示与折叠态维护，统计口径与排序全部由页面算好后以
 * {@code DisplayItem} 列表塞进来；适配器不查链、不读存储。</p>
 *
 * <p>展开状态按分组名存在 {@code expandedMap} 里，刷新数据时保持用户当前的展开结果，
 * 避免每次定时刷新都把分组弹回默认状态。</p>
 */
public class AccountStatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 分组头行：显示分组名、人数与折叠箭头，点击切换折叠 */
    private static final int TYPE_HEADER = 0;
    /** 账号行：一行一个账号的链上统计 */
    private static final int TYPE_ACCOUNT = 1;

    /**
     * 分组胶囊颜色盘。同一分组名按 hash 取模，颜色在同一次运行内稳定；
     * 不依赖分组顺序，重命名才会换色。
     */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };
    // region 数据源与分组状态

    /**
     * 账号行点击回调：点某一行进入该地址的详情页。
     * 只在账号行（TYPE_ACCOUNT）触发，分组头行的折叠点击不走这里。
     */
    public interface OnAccountClickListener {
        void onAccountClick(AccountStatsItem item);
    }

    /** 页面设置的行点击监听，null 时账号行不可点 */
    private OnAccountClickListener accountClickListener;

    /** 装配账号行点击回调（详情页跳转由此触发） */
    public void setOnAccountClickListener(OnAccountClickListener listener) {
        this.accountClickListener = listener;
    }

    /** 页面塞进来的完整数据（含所有分组与账号），用于计数与回填 */
    private final List<DisplayItem> allList = new ArrayList<>();
    /** 实际交给 RecyclerView 的可见列表：折叠的分组只保留组头 */
    private final List<DisplayItem> visibleList = new ArrayList<>();
    /** 分组名 → 是否展开；LinkedHashMap 保证组头顺序与传入顺序一致 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();

    // endregion

    // region 数据模型

    /** 主界面一个账号行的数据快照：链上数值 + 展示用名称，不含密文与私钥 */
    public static class AccountStatsItem {
        /** 展示用标签（优先自定义编号，没填用 3 位 displayNo） */
        public String name;
        /** 账号地址，也是与链上结果匹配的 key */
        public String address;
        /** 钱包内 fLGNS 余额（wei）：历史字段，新代列表不再直接展示。保留是为了不让外部快照行写处一片红 */
        public BigInteger balance;
        /** LGNS 余额（wei，9 位小数）：HELIX 写链路主金（烧币、日得 gas），预检与启动都看它 */
        public BigInteger lgnsBalance;
        /** DAI 余额（wei，18 位小数）：HELIX 写链路的 gas 货币，不够时激活/烧币都会直接跳过 */
        public BigInteger daiBalance;
        /** AiLGNS 钱包余额（wei，18 位小数）：第三波与 LGNS/DAI 同批查回，列表第 6 列与汇总「AiLGNS余额」 */
        public BigInteger ailgnsBalance;
        /** 今日已入场次数 */
        public int entriesToday;
        /** 今日剩余入场次数（为 0 表示已打满） */
        public int entriesRemaining;
        /** 今日冠军次数 */
        public int todayChampion;
        /** 累计冠军次数 */
        public int totalChampion;
        /** 可领奖金（wei） */
        public BigInteger prize;
        /** 待领取的甲子总数（wei），汇总行展示用 */
        public BigInteger totalEarnings;
        /** 所属分组名，用于按组折叠与上色 */
        public String groupNo;
        /** 甲子可提（wei）：随 playerSummary 免费带回，第一波即有值 */
        public BigInteger jiaziClaimable;
        /** 擂台可领（wei）：第三波逐地址 claimable() 查回，未查到时为 null */
        public BigInteger arenaClaimable;
        /** 师徒可领（wei）：第三波逐地址 lineagePending() 查回，未查到时为 null */
        public BigInteger lineagePending;

        // ══ HELIX 侧持仓读数（2026-11-03 新增，主界面列表/汇总栏新主字段） ══
        /** 涡轮已领取、待激活的 AiLGNS（wei， 18 位小数）——列表第 3 列、汇总「待激活」 */
        public BigInteger helixClaimed;
        /** 涡轮已解锁可领的 AiLGNS（wei）——列表第 4 列、汇总「已解锁可领」 */
        public BigInteger helixClaimable;
        /** 涡轮仍在锁仓的 AiLGNS（wei）——列表未直接展示，详情页与行中卡用 */
        public BigInteger helixLocked;
        /** 金库待领的静态释放（wei）——列表第 5 列、汇总「金库待发」 */
        public BigInteger helixVaultPending;
        /** 功率账本累计功率（原始整数）：2026-11-04 已从主列表第 6 列换下（改显 AiLGNS 余额），仍由 callPositions 查回、供详情页与快照回写用 */
        public BigInteger helixPower;
        /** 本轮结果还没回来（首次上屏/刷新中）：数值显示占位，状态位留空 */
        public boolean pending;
        /** 本轮查询结束但链上没返回这个地址：显示“未查到” */
        public boolean unknown;

        /** 账号行数据全量填充：只存字段不做校验，调用方保证地址非空 */
        /**
         * 与旧 9-参构造器保持向后兼容（上一代快照回写与外部测试行依赖它）；
         * HELIX 侧字段默认 null，由主页面第三波回填补上。
         */
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

    /** 列表行：组头与账号行共用同一个实体，用 {@link #type} 区分 */
    public static class DisplayItem {
        /** 行类型，取值同 {@code TYPE_HEADER} / {@code TYPE_ACCOUNT} */
        public int type;
        /** 所属分组名：组头行直接显示，账号行用于折叠筛选与上色 */
        public String groupName;
        /** 账号数据；组头行为 null，绑定时必须先判 type 再取 */
        public AccountStatsItem account;

        /** 构造一个分组头行（type=TYPE_HEADER，不带账号数据） */
        public static DisplayItem header(String groupName) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = groupName;
            return d;
        }

        /** 构造一个账号行（type=TYPE_ACCOUNT，必须带 account，否则绑定时会空指针） */
        public static DisplayItem account(String groupName, AccountStatsItem account) {
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
            AccountVH a = (AccountVH) holder;
            a.bind(item.account);
            // 账号行整行可点→详情页；分组头行的折叠在上方 header 分支单独处理
            if (accountClickListener != null && item.account != null) {
                a.itemView.setOnClickListener(v ->
                        accountClickListener.onAccountClick(item.account));
            } else {
                a.itemView.setOnClickListener(null);
                a.itemView.setClickable(false);
            }
        }
    }

    @Override
    public int getItemCount() {
        return visibleList.size();
    }

    // endregion

    // region 数据更新与分组展开

    /**
     * 整体换数据：保留各分组上一次的折叠态（新分组默认展开），再重建可见列表。
     * 定时刷新也走这里，所以不能把展开状态重置，否则用户每次刷新都会被弹回首屏形态。
     */
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

    /** 快照当前展示中的账号数据（地址→数据引用），供本地结构刷新时继承已加载的链上数值 */
    public Map<String, AccountStatsItem> snapshotAccountItems() {
        Map<String, AccountStatsItem> map = new java.util.HashMap<>();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_ACCOUNT && d.account != null && d.account.address != null) {
                map.put(d.account.address, d.account);
            }
        }
        return map;
    }

    /** 当前展示中的账号行（引用直连：分批回填时直接写字段，再调 {@link #notifyStatsChanged}） */
    public List<AccountStatsItem> accountItems() {
        List<AccountStatsItem> out = new ArrayList<>();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_ACCOUNT && d.account != null) out.add(d.account);
        }
        return out;
    }

    /** 局部回填后刷新：行结构不变、只改数值，不会打乱滚动位置 */
    public void notifyStatsChanged() {
        notifyDataSetChanged();
    }

    private void toggleGroup(String groupName) {
        expandedMap.put(groupName, !expandedMap.getOrDefault(groupName, true));
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    /** 按展开状态摊平：折叠的组只留组头，组内账号不进可见列表 */
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

    /** 分组名的稳定取色：同一分组每次绑定颜色一致，不会随滚动变色 */
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

    /** 账号行 ViewHolder：标签/地址/LGNS+DAI/HELIX 四列，具体取值在 bind 里回填 */
    static class AccountVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAddr, tvStatusTag;
        TextView tvBalance, tvDaiBalance, tvEntries, tvTodayChampion, tvTotalChampion, tvPrize;
        /** HELIX 行内卡遗留提示条（目前隐去）：与旧时代徒分层同位，新链路无同语义项 */
        TextView tvLineageHint;
        /** 旧擂台三个待领小标签：新链路不再展示，保留引用只为兼容 layout */
        TextView tvTagJiazi, tvTagArena, tvTagLineage;

        AccountVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_account_name);
            tvAddr = itemView.findViewById(R.id.tv_account_addr);
            tvStatusTag = itemView.findViewById(R.id.tv_status_tag);
            tvBalance = itemView.findViewById(R.id.tv_balance);
            tvDaiBalance = itemView.findViewById(R.id.tv_dai_balance);
            // 下面 4 个 ID 沿用了旧擂台列的 xml id，但语义已换到 HELIX：
            // tv_entries  → 待激活（helixClaimed）
            // tv_today_champion → 已解锁可领（helixClaimable）
            // tv_total_champion → 金库待发（helixVaultPending）
            // tv_prize → 累计功率（helixPower）
            // 不重命名 ID 是为了不牵动 MainActivity/快照回写/旧测试行那一片引用
            tvEntries = itemView.findViewById(R.id.tv_entries);
            tvTodayChampion = itemView.findViewById(R.id.tv_today_champion);
            tvTotalChampion = itemView.findViewById(R.id.tv_total_champion);
            tvPrize = itemView.findViewById(R.id.tv_prize);
            tvLineageHint = itemView.findViewById(R.id.tv_lineage_hint);
            tvTagJiazi = itemView.findViewById(R.id.tv_tag_jiazi);
            tvTagArena = itemView.findViewById(R.id.tv_tag_arena);
            tvTagLineage = itemView.findViewById(R.id.tv_tag_lineage);
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

            // 还没查到结果：批量查询是一次性返回的，行与行不会先后完成，
            // 所以这里不伪造逐行“同步中”进度，只在全局汇总栏提示同步中；
            // 整行降透明度做骨架化处理，让“未就绪”和“已就绪”一眼能分（对标大厂骨架屏）
            if (item.pending) {
                itemView.setAlpha(0.45f);
                tvStatusTag.setVisibility(View.GONE);
                tvStatusTag.setText("");
                tvBalance.setText(R.string.common_placeholder);
                tvDaiBalance.setText(R.string.common_placeholder);
                tvEntries.setText(R.string.common_placeholder);
                tvTodayChampion.setText(R.string.common_placeholder);
                tvTotalChampion.setText(R.string.common_placeholder);
                tvPrize.setText(R.string.common_placeholder);
                tvLineageHint.setVisibility(View.GONE);
                hideTags();
                return;
            }
            // 视图复用：非 pending 行必须把状态位与透明度恢复出来
            itemView.setAlpha(1f);
            tvStatusTag.setVisibility(View.VISIBLE);

            // 整行未查到：所有数值位显示 -- ，避免把“查不到”误读成“余额 0 / 今日未入场”
            if (item.unknown) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_unknown));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
                tvBalance.setText(R.string.common_placeholder);
                tvDaiBalance.setText(R.string.common_placeholder);
                tvEntries.setText(R.string.common_placeholder);
                tvTodayChampion.setText(R.string.common_placeholder);
                tvTotalChampion.setText(R.string.common_placeholder);
                tvPrize.setText(R.string.common_placeholder);
                tvLineageHint.setVisibility(View.GONE);
                hideTags();
                return;
            }

            int maxEntries = item.entriesToday + item.entriesRemaining;
            // 行状态胶囊：HELIX 口径。先看 LGNS 够不够 500（烧币一次），不够就标红提醒先补币
            if (item.lgnsBalance == null) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_unknown));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_gray);
            } else if (item.lgnsBalance.compareTo(com.ailgnsq.model.HelixConfig.BURN_AMOUNT_RAW) >= 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_ready_burn));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_green);
            } else {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_lgns_low));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
            }

            tvBalance.setText(formatLgns(item.lgnsBalance));
            tvDaiBalance.setText(formatDai(item.daiBalance));
            // 4 列语义已换到 HELIX，不再读 entriesToday/todayChampion/totalChampion/totalEarnings
            tvEntries.setText(formatAiLgns(item.helixClaimed));
            tvTodayChampion.setText(formatAiLgns(item.helixClaimable));
            tvTotalChampion.setText(formatAiLgns(item.helixVaultPending));
            // 第 6 列原为「累计功率」（链上原始整数、小数口径未实测、读不出量级），
            // 2026-11-04 改为 AiLGNS 钱包余额（18 位）：与前三列同族、可直接动用的币量，信息量更高
            tvPrize.setText(formatAiLgns(item.ailgnsBalance));
            // HELIX 行不拼擂台待领小标签，也不拼师徒解锁进度（两个都是擂台概念），直接隐去
            hideTags();
            tvLineageHint.setVisibility(View.GONE);
            // maxEntries 保留局部变量仅为向后兼容旧快照回写行为（目前不展示）
            if (maxEntries < 0) tvStatusTag.setText("");
        }

        /**
         * AiLGNS 展示：18 位小数。≥1 保留 2 位；小于 1 的零头保留到看得清为止
         * （最多 6 位、去尾随 0，至少显 2 位），避免 0.0033 这类小额被 2 位截成 0.00。
         * null / 0 归 "0.00"。
         */
        private String formatAiLgns(BigInteger wei) {
            if (wei == null || wei.signum() == 0) return "0.00";
            BigDecimal v = new BigDecimal(wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(com.ailgnsq.model.HelixConfig.DEC_AILGNS)),
                            6, RoundingMode.HALF_UP);
            if (v.compareTo(BigDecimal.ONE) >= 0) {
                return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
            }
            BigDecimal s = v.stripTrailingZeros();
            if (s.signum() == 0) return "0.00";
            if (s.scale() < 2) s = s.setScale(2);
            return s.toPlainString();
        }

        /** 通用 18 位代币展示（甲子/擂台/师徒等待领标签与 DAI 列共用）：null 时归 0.00 */
        private String formatToken(BigInteger wei) {
            if (wei == null) return "0.00";
            BigDecimal bd = new BigDecimal(wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
            return bd.toPlainString();
        }

        /** LGNS 展示：链上精度 9 位小数，列表里保留 2 位（与 DAI 行保持相同的可读粒度） */
        private String formatLgns(BigInteger wei) {
            if (wei == null) return "0.00";
            BigDecimal bd = new BigDecimal(wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(9)), 2, RoundingMode.HALF_UP);
            return bd.toPlainString();
        }

        /** DAI 展示：18 位小数，保留 2 位 */
        private String formatDai(BigInteger wei) {
            return formatToken(wei);
        }

        /** 三个待领标签全隐藏（pending/unknown 行不展示半成品数值） */
        private void hideTags() {
            tvTagJiazi.setVisibility(View.GONE);
            tvTagArena.setVisibility(View.GONE);
            tvTagLineage.setVisibility(View.GONE);
        }

        /**
         * 绑定甲子/擂台/师徒三个待领胶囊：
         * <p>数值为 null 表示本轮没查到，直接隐藏该标签（不伪造 0）；查到就显示，
         * &gt;0 用绿底一眼看出有钱可领、=0 用灰底。甲子随 playerSummary 免费带回，
         * 擂台/师徒靠第三波逐地址查询。口径与批量领取一致。</p>
         */
        private void bindTags(AccountStatsItem item) {
            android.content.Context ctx = itemView.getContext();
            applyTag(tvTagJiazi, item.jiaziClaimable,
                    ctx.getString(R.string.account_tag_jiazi_fmt, formatToken(item.jiaziClaimable)));
            applyTag(tvTagArena, item.arenaClaimable,
                    ctx.getString(R.string.account_tag_arena_fmt, formatToken(item.arenaClaimable)));
            applyTag(tvTagLineage, item.lineagePending,
                    ctx.getString(R.string.account_tag_lineage_fmt, formatToken(item.lineagePending)));
        }

        /** 单个待领标签：null→隐藏；否则显文本并按 >0/0 切绿/灰底 */
        private void applyTag(TextView tv, BigInteger wei, String label) {
            if (wei == null) {
                tv.setVisibility(View.GONE);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(label);
            tv.setBackgroundResource(wei.signum() > 0
                    ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);
        }
    }

    // endregion

}
