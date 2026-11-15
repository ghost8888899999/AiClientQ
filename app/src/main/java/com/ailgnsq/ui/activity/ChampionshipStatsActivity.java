package com.ailgnsq.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ailgnsq.R;
import com.ailgnsq.contract.ArenaContractApi;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.ui.adapter.ChampionStatsAdapter;
import com.ailgnsq.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 冠军榜统计页：汇总各账号的名次/夺冠数据，按分组折叠展示。
 *
 * <p>职责边界：页面负责取数与聚合，列表渲染交给 {@code ChampionStatsAdapter}；
 * 统计口径集中在 {@code buildItems}，改口径时只需要动这一处。</p>
 */
public class ChampionshipStatsActivity extends BaseActivity {

    private TextView tvError;
    private TextView tvStatsCount;
    private TextView tvSummaryBattles;
    private TextView tvSummaryChamps;
    private TextView tvSummaryRate;
    private ProgressBar pbLoading;

    private ChampionStatsAdapter adapter;

    /** 待统计的账号地址（Intent 传入），与 labels/groups/initChamps 按下标一一对应 */
    private List<String> addrs;
    /** 账号显示名（编号），缺失时用 1-based 下标兜底 */
    private List<String> labels;
    /** 账号所属分组名，用于分组折叠展示；为 null 或已失效的归入「未分组」 */
    private List<String> groups;
    /** 主界面已加载的冠军次数：链上数据回来前先用它占位，避免首屏显示 0 冠军 */
    private List<Integer> initChamps;

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_championship_stats);

        ImageButton btnBack = findViewById(R.id.btn_back);
        tvError = findViewById(R.id.tv_error);
        tvStatsCount = findViewById(R.id.tv_stats_count);
        tvSummaryBattles = findViewById(R.id.tv_summary_battles);
        tvSummaryChamps = findViewById(R.id.tv_summary_champs);
        tvSummaryRate = findViewById(R.id.tv_summary_rate);
        pbLoading = findViewById(R.id.pb_loading);

        btnBack.setOnClickListener(v -> finish());

        addrs = getIntent().getStringArrayListExtra("addrs");
//        addrs.set(0,"0x4171222Dfb000f1b9A2E7FB9C213F3B7C656Ba9F");
        labels = getIntent().getStringArrayListExtra("labels");
        groups = getIntent().getStringArrayListExtra("groups");
        List<Integer> ic = getIntent().getIntegerArrayListExtra("initChamps");
        initChamps = ic != null ? ic : new ArrayList<>();

        if (addrs == null || addrs.isEmpty()) {
            tvError.setText(R.string.stats_no_account);
            tvError.setVisibility(View.VISIBLE);
            return;
        }
        if (labels == null) labels = new ArrayList<>();
        if (groups == null) groups = new ArrayList<>();

        tvStatsCount.setText(getString(R.string.common_total_fmt, addrs.size()));

        adapter = new ChampionStatsAdapter();
        RecyclerView rvStatsList = findViewById(R.id.rv_stats_list);
        rvStatsList.setLayoutManager(new LinearLayoutManager(this));
        rvStatsList.setAdapter(adapter);

        new Thread(this::loadData).start();
    }

    // endregion

    // region 数据加载

    /**
     * 加载流程分两段：先把分组与占位行立即上屏（场次/胜率显示占位），
     * 再用 {@code callPlayerSummaryBatch} 拉链上战绩回填。
     *
     * <p>口径要点：</p>
     * <ul>
     *   <li>未查到的地址标 {@code unknown} 并排除在合计之外，不当成 0 场计入；</li>
     *   <li>一个都没返回时汇总位全部显示占位并提示「全部未查到」；</li>
     *   <li>部分缺失时汇总照算，但页顶提示缺失条数；</li>
     *   <li>链上访问失败则把占位行改标未查到，不让它们永远停在骨架态。</li>
     * </ul>
     */
    private void loadData() {
        // 第一步：数据库分组 + 零值默认立即上屏，冠军次数继承主界面已加载值（对标 applyLocalDefaults）
        List<String> groupNames = AppLogDbHelper.getInstance(this).queryAllGroups();
        List<ChampionStatsAdapter.ChampionStatsItem> placeholderItems = buildItems(groupNames, null, false);
        long inheritChamps = 0;
        for (ChampionStatsAdapter.ChampionStatsItem it : placeholderItems) {
            if (it.championships > 0) inheritChamps += it.championships;
        }
        final long fInheritChamps = inheritChamps;
        runOnUiThread(() -> {
            adapter.setItems(groupItems(groupNames, placeholderItems));
            // 场次/胜率还没查：占位，避免把占位的 0 当成真实战绩
            tvSummaryBattles.setText(R.string.common_placeholder);
            tvSummaryChamps.setText(String.valueOf(fInheritChamps));
            tvSummaryRate.setText(R.string.common_placeholder);
        });

        // 第二步：链上真实数据异步回填，数据源与主界面一致（playerSummary）
        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            Map<String, ArenaContractApi.PlayerSummary> result =
                    ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);

            List<ChampionStatsAdapter.ChampionStatsItem> realItems = buildItems(groupNames, result, false);

            long grandBattles = 0, grandChamps = 0;
            int valid = 0, missing = 0;
            for (ChampionStatsAdapter.ChampionStatsItem it : realItems) {
                // 未查到的行不计入合计：它们盖着占位值，算进去会把总战绩拉低
                if (it.unknown) { missing++; continue; }
                valid++;
                grandBattles += it.battles;
                grandChamps += it.championships;
            }
            final long gBattles = grandBattles;
            final long gChamps = grandChamps;
            final int fValid = valid;
            final int fMissing = missing;

            runOnUiThread(() -> {
                adapter.setItems(groupItems(groupNames, realItems));
                if (fValid == 0) {
                    // 一个地址都没返回：汇总没有意义，明说而不是给一堆 0
                    tvSummaryBattles.setText(R.string.common_placeholder);
                    tvSummaryChamps.setText(R.string.common_placeholder);
                    tvSummaryRate.setText(R.string.common_placeholder);
                    tvError.setText(R.string.stats_all_unknown);
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                tvSummaryBattles.setText(String.valueOf(gBattles));
                tvSummaryChamps.setText(String.valueOf(gChamps));
                // 汇总胜率 = 全部账号冠军总次数 / 总场次；保留 1 位小数
                tvSummaryRate.setText(gBattles > 0
                        ? String.format(java.util.Locale.US, "%.1f%%", gChamps * 100.0 / gBattles)
                        : getString(R.string.common_placeholder));
                if (fMissing > 0) {
                    tvError.setText(getString(R.string.stats_partial_unknown_fmt, fMissing));
                    tvError.setVisibility(View.VISIBLE);
                } else {
                    tvError.setVisibility(View.GONE);
                }
            });
        } catch (Exception e) {
            Log.e("ChampionshipStats", "load chain data error", e);
            // 首轮失败：把占位行改成“未查到”，别让它们永远停在骨架态
            List<ChampionStatsAdapter.ChampionStatsItem> failedItems = buildItems(groupNames, null, true);
            runOnUiThread(() -> {
                adapter.setItems(groupItems(groupNames, failedItems));
                tvSummaryBattles.setText(R.string.common_placeholder);
                tvSummaryChamps.setText(R.string.common_placeholder);
                tvSummaryRate.setText(R.string.common_placeholder);
                tvError.setText(getString(R.string.stats_chain_load_failed_fmt,
                        Web3AndroidUtil.safeMessage(e)));
                tvError.setVisibility(View.VISIBLE);
            });
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region 列表构建

    /**
     * 按传入的账号/标签/分组拼出列表行。
     *
     * <p>{@code result == null} 分两种情况：{@code failed=false} 是加载中（骨架态，冠军次数继承主界面值，
     * -1 表示主界面也没有）；{@code failed=true} 是首轮失败（直接标未查到）。
     * 有结果时链上没返回这个地址也标未查到，不显示成 0 场 0 冠军。</p>
     */
    private List<ChampionStatsAdapter.ChampionStatsItem> buildItems(
            List<String> groupNames, Map<String, ArenaContractApi.PlayerSummary> result, boolean failed) {
        List<ChampionStatsAdapter.ChampionStatsItem> items = new ArrayList<>();
        for (int i = 0; i < addrs.size(); i++) {
            String addr = addrs.get(i);
            if (addr == null || addr.isEmpty()) continue;
            ChampionStatsAdapter.ChampionStatsItem item = new ChampionStatsAdapter.ChampionStatsItem();
            item.name = (i < labels.size() && labels.get(i) != null && !labels.get(i).isEmpty())
                    ? labels.get(i) : String.valueOf(i + 1);
            item.address = addr;
            item.groupNo = i < groups.size() ? groups.get(i) : null;
            if (result == null) {
                if (failed) {
                    // 首轮失败：明确标未查到，不要留成永远骨架
                    item.unknown = true;
                } else {
                    // 加载中：场次未知，但冠军次数可继承主界面已加载值（-1 表示主界面也没有）
                    item.pending = true;
                    item.championships = i < initChamps.size() ? initChamps.get(i) : -1;
                }
            } else {
                ArenaContractApi.PlayerSummary s = result.get(addr);
                if (s == null) {
                    // 链上没返回这个地址：保留行并标未查到，不显示成 0 场 0 冠军
                    item.unknown = true;
                } else {
                    item.battles = (s.battles != null) ? s.battles.longValue() : 0;
                    item.championships = (s.championships != null) ? s.championships.longValue() : 0;
                }
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 按数据库分组构建展示列表：分组顺序跟分组表走，空组不占位；
     * groupNo 为空或指向已删分组的账号统一归入「未分组」放在末尾。
     */
    private List<ChampionStatsAdapter.DisplayItem> groupItems(
            List<String> groupNames, List<ChampionStatsAdapter.ChampionStatsItem> statItems) {
        List<ChampionStatsAdapter.DisplayItem> displayItems = new ArrayList<>();
        List<ChampionStatsAdapter.ChampionStatsItem> ungrouped = new ArrayList<>();
        java.util.LinkedHashSet<String> usedGroups = new java.util.LinkedHashSet<>();
        if (groupNames != null) {
            for (String g : groupNames) {
                List<ChampionStatsAdapter.ChampionStatsItem> inGroup = new ArrayList<>();
                for (ChampionStatsAdapter.ChampionStatsItem it : statItems) {
                    if (g.equals(it.groupNo)) inGroup.add(it);
                }
                if (!inGroup.isEmpty()) {
                    usedGroups.add(g);
                    displayItems.add(ChampionStatsAdapter.DisplayItem.header(g));
                    for (ChampionStatsAdapter.ChampionStatsItem ai : inGroup) {
                        displayItems.add(ChampionStatsAdapter.DisplayItem.account(g, ai));
                    }
                }
            }
        }
        for (ChampionStatsAdapter.ChampionStatsItem it : statItems) {
            if (it.groupNo == null || it.groupNo.isEmpty() || !usedGroups.contains(it.groupNo)) {
                ungrouped.add(it);
            }
        }
        if (!ungrouped.isEmpty()) {
            String ungroupedName = getString(R.string.common_ungrouped);
            displayItems.add(ChampionStatsAdapter.DisplayItem.header(ungroupedName));
            for (ChampionStatsAdapter.ChampionStatsItem ai : ungrouped) {
                displayItems.add(ChampionStatsAdapter.DisplayItem.account(ungroupedName, ai));
            }
        }
        return displayItems;
    }

    // endregion

}
