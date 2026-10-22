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
            tvError.setText("没有可查询的账号");
            tvError.setVisibility(View.VISIBLE);
            return;
        }
        if (labels == null) labels = new ArrayList<>();
        if (groups == null) groups = new ArrayList<>();

        tvStatsCount.setText("共 " + addrs.size() + " 个");

        adapter = new ChampionStatsAdapter();
        RecyclerView rvStatsList = findViewById(R.id.rv_stats_list);
        rvStatsList.setLayoutManager(new LinearLayoutManager(this));
        rvStatsList.setAdapter(adapter);

        new Thread(this::loadData).start();
    }

    private void loadData() {
        // 第一步：数据库分组 + 零值默认立即上屏，冠军次数继承主界面已加载值（对标 applyLocalDefaults）
        List<String> groupNames = AppLogDbHelper.getInstance(this).queryAllGroups();
        List<ChampionStatsAdapter.ChampionStatsItem> placeholderItems = buildItems(groupNames, null);
        long inheritChamps = 0;
        for (ChampionStatsAdapter.ChampionStatsItem it : placeholderItems) {
            inheritChamps += it.championships;
        }
        final long fInheritChamps = inheritChamps;
        runOnUiThread(() -> {
            adapter.setItems(groupItems(groupNames, placeholderItems));
            tvSummaryBattles.setText("0");
            tvSummaryChamps.setText(String.valueOf(fInheritChamps));
            tvSummaryRate.setText("--");
        });

        // 第二步：链上真实数据异步回填，数据源与主界面一致（playerSummary）
        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            Map<String, ArenaContractApi.PlayerSummary> result =
                    ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);

            List<ChampionStatsAdapter.ChampionStatsItem> realItems = buildItems(groupNames, result);

            long grandBattles = 0, grandChamps = 0;
            for (ChampionStatsAdapter.ChampionStatsItem it : realItems) {
                grandBattles += it.battles;
                grandChamps += it.championships;
            }
            final long gBattles = grandBattles;
            final long gChamps = grandChamps;

            runOnUiThread(() -> {
                adapter.setItems(groupItems(groupNames, realItems));
                tvSummaryBattles.setText(String.valueOf(gBattles));
                tvSummaryChamps.setText(String.valueOf(gChamps));
                // 汇总胜率 = 全部账号冠军总次数 × 100 / 总场次
                tvSummaryRate.setText(gBattles > 0 ? (gChamps * 100 / gBattles) + "%" : "--");
            });
        } catch (Exception e) {
            Log.e("ChampionshipStats", "load chain data error", e);
            runOnUiThread(() -> tvError.setText(
                    "链上数据加载失败：" + Web3AndroidUtil.safeMessage(e) + "（当前展示本地账号）"));
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    private List<ChampionStatsAdapter.ChampionStatsItem> buildItems(
            List<String> groupNames, Map<String, ArenaContractApi.PlayerSummary> result) {
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
                // 零值默认；冠军次数继承主界面快照（-1 表示主界面尚未加载，退回 0）
                int inherit = i < initChamps.size() ? initChamps.get(i) : -1;
                item.championships = inherit >= 0 ? inherit : 0;
            } else {
                ArenaContractApi.PlayerSummary s = result.get(addr);
                item.battles = (s != null && s.battles != null) ? s.battles.longValue() : 0;
                item.championships = (s != null && s.championships != null) ? s.championships.longValue() : 0;
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
            displayItems.add(ChampionStatsAdapter.DisplayItem.header("未分组"));
            for (ChampionStatsAdapter.ChampionStatsItem ai : ungrouped) {
                displayItems.add(ChampionStatsAdapter.DisplayItem.account("未分组", ai));
            }
        }
        return displayItems;
    }
}