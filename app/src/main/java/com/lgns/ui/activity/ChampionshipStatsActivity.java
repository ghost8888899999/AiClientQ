package com.lgns.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.ui.adapter.ChampionStatsAdapter;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChampionshipStatsActivity extends AppCompatActivity {

    private TextView tvError;
    private TextView tvStatsCount;
    private TextView tvSummaryBattles;
    private TextView tvSummaryChamps;
    private TextView tvSummaryRate;
    private ProgressBar pbLoading;

    private ChampionStatsAdapter adapter;

    private List<String> addrs;
    private List<String> labels;
    private List<String> groups;
    private List<Integer> initChamps;

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

    // 按数据库分组构建展示列表（对标主界面 groupStatsItems）
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
}