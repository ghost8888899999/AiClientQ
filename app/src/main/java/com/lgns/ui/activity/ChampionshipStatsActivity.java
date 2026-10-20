package com.lgns.ui.activity;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.lgns.contract.ArenaContractApi;
import com.lgns.R;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChampionshipStatsActivity extends AppCompatActivity {

    private ProgressBar pbLoading;
    private TextView tvError;
    private LinearLayout layoutTable;
    private LinearLayout tableContainer;
    private TextView tvSummary;
    private TextView tvStatsCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_championship_stats);

        ImageButton btnBack = findViewById(R.id.btn_back);
        pbLoading = findViewById(R.id.pb_loading);
        tvError = findViewById(R.id.tv_error);
        layoutTable = findViewById(R.id.layout_table);
        tableContainer = findViewById(R.id.table_container);
        tvSummary = findViewById(R.id.tv_summary);
        tvStatsCount = findViewById(R.id.tv_stats_count);

        btnBack.setOnClickListener(v -> finish());

        List<String> addrs = getIntent().getStringArrayListExtra("addrs");
        List<String> labels = getIntent().getStringArrayListExtra("labels");

        if (addrs == null || addrs.isEmpty()) {
            tvError.setText("没有可查询的账号");
            tvError.setVisibility(View.VISIBLE);
            pbLoading.setVisibility(View.GONE);
            return;
        }

        tvStatsCount.setText("共 " + addrs.size() + " 个");

        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();
                Map<String, long[]> result = ArenaContractApi.callChampionshipCounts(web3j, addrs);

                long grandTotal = 0, grandWins = 0;
                List<long[]> dataList = new ArrayList<>();
                for (int i = 0; i < addrs.size(); i++) {
                    long[] counts = result.get(addrs.get(i));
                    long total = counts != null ? counts[0] : 0;
                    long wins = counts != null ? counts[1] : 0;
                    grandTotal += total;
                    grandWins += wins;
                    dataList.add(new long[]{total, wins});
                }

                final long gTotal = grandTotal;
                final long gWins = grandWins;
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    layoutTable.setVisibility(View.VISIBLE);
                    buildTable(labels, addrs, dataList);
                    tvSummary.setText("合计：" + gTotal + " 场    赢 " + gWins + " 场"
                            + (gTotal > 0 ? "    胜率 " + (gWins * 100 / gTotal) + "%" : ""));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    tvError.setText("查询失败：" + Web3AndroidUtil.safeMessage(e));
                    tvError.setVisibility(View.VISIBLE);
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    private void buildTable(List<String> labels, List<String> addrs, List<long[]> dataList) {
        tableContainer.removeAllViews();
        int dp1 = (int) (getResources().getDisplayMetrics().density + 0.5f);

        addTableRow("#", "编号", "地址", "总场次", "赢场", "胜率", true, dp1);

        for (int i = 0; i < addrs.size(); i++) {
            String label = labels.get(i);
            String addr = addrs.get(i).length() > 10
                    ? addrs.get(i).substring(0, 6) + "..." + addrs.get(i).substring(addrs.get(i).length() - 4)
                    : addrs.get(i);
            long total = dataList.get(i)[0];
            long wins = dataList.get(i)[1];
            String rate = total > 0 ? (wins * 100 / total) + "%" : "-";
            addTableRow(String.valueOf(i + 1), label, addr,
                    String.valueOf(total), String.valueOf(wins), rate, false, dp1);
        }
    }

    private void addTableRow(String col1, String col2, String col3,
                             String col4, String col5, String col6,
                             boolean isHeader, int dp1) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 10, 0, 10);

        if (!isHeader) {
            row.setBackgroundColor(Color.WHITE);
        } else {
            row.setBackgroundColor(0xFFF5F5F5);
        }

        int[] widths = {28, 56, 90, 56, 56, 56};
        String[] texts = {col1, col2, col3, col4, col5, col6};
        int[] gravities = {Gravity.CENTER, Gravity.START, Gravity.START,
                Gravity.CENTER, Gravity.CENTER, Gravity.CENTER};

        for (int i = 0; i < texts.length; i++) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            tv.setLayoutParams(lp);
            tv.setPadding(4 * dp1, 2 * dp1, 4 * dp1, 2 * dp1);
            tv.setGravity(gravities[i]);
            tv.setText(texts[i]);
            tv.setTextSize(12);

            if (isHeader) {
                tv.setTextColor(0xFF757575);
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTextColor(0xFF212121);
            }
            row.addView(tv);
        }

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp1));
        divider.setBackgroundColor(0xFFEEEEEE);

        tableContainer.addView(row);
        tableContainer.addView(divider);
    }
}
