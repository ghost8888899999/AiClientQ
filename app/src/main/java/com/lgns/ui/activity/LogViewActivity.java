package com.lgns.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.task.TaskLogItem;
import com.lgns.ui.adapter.LogAdapter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 操作日志页：把本地日志库里的任务日志按「日期分组 + 卡片」展示，并支持搜索、按类型筛选、复制与清空。
 *
 * <p>职责边界：页面只读不写，日志由各任务在运行时写入；复制走系统剪贴板便于贴给他人排查，
 * 清空需要二次确认。</p>
 *
 * <p>数据流：整表只在 {@link #loadLogs()} 读一次库，搜索与类型筛选都在内存里由
 * {@link LogAdapter} 完成，输入框敲字不会反复查库。</p>
 */
public class LogViewActivity extends BaseActivity {

    /** 列表：日期分组与卡片行都由适配器生成 */
    private LogAdapter logAdapter;
    /** 标题栏副标题：总条数；有筛选时显示「匹配 M / 共 N 条」 */
    private TextView tvLogCount;
    /** 空态主文案：无日志与无匹配两套文案 */
    private TextView tvLogEmpty;
    /** 空态说明：告诉用户下一步该做什么 */
    private TextView tvLogEmptyHint;
    /** 空态整块（图标 + 文案），与列表互斥显示 */
    private View layoutEmpty;
    /** 搜索与筛选区：库里没有日志时整体收起 */
    private View layoutFilter;
    /** 类型筛选标签容器：标签由实际出现过的日志类型生成 */
    private LinearLayout layoutTypeChips;
    /** 筛选标签外层滚动容器：类型不足两种时整行收起 */
    private View scrollTypeChips;
    private EditText etSearch;
    private ImageButton btnSearchClear;
    private RecyclerView rvLog;

    /** 当前选中的类型；null 表示「全部」 */
    private String selectedType;

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_view);

        tvLogCount = findViewById(R.id.tv_log_count);
        tvLogEmpty = findViewById(R.id.tv_log_empty);
        tvLogEmptyHint = findViewById(R.id.tv_log_empty_hint);
        layoutEmpty = findViewById(R.id.layout_empty);
        layoutFilter = findViewById(R.id.layout_filter);
        layoutTypeChips = findViewById(R.id.layout_type_chips);
        scrollTypeChips = findViewById(R.id.scroll_type_chips);
        etSearch = findViewById(R.id.et_log_search);
        btnSearchClear = findViewById(R.id.btn_search_clear);
        rvLog = findViewById(R.id.rv_log);

        logAdapter = new LogAdapter(this);
        rvLog.setLayoutManager(new LinearLayoutManager(this));
        rvLog.setAdapter(logAdapter);

        initViews();
        loadLogs();
    }

    // endregion

    // region 视图初始化

    /**
     * 绑定返回、复制、清空与搜索框。
     *
     * <p>搜索框用 TextWatcher 实时过滤，同时控制「清空搜索」图标的出现，
     * 这样用户不必长按删字符才能回到全量列表。</p>
     */
    private void initViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_export_log).setOnClickListener(v -> copyLogsToClipboard());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> confirmClearLogs());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String keyword = s == null ? "" : s.toString();
                btnSearchClear.setVisibility(keyword.isEmpty() ? View.GONE : View.VISIBLE);
                logAdapter.setQuery(keyword);
                refreshHeaderAndEmpty();
            }
        });
        btnSearchClear.setOnClickListener(v -> etSearch.setText(""));
    }

    /**
     * 按当前数据里实际出现的类型生成筛选标签，「全部」固定在最前。
     *
     * <p>每次加载完数据整块重建：类型集合会随日志变化，复用旧标签会留下点了没结果的死标签。
     * 只有一种类型时筛选没有意义，整行收起。</p>
     */
    private void buildTypeChips() {
        List<String> types = logAdapter.getPresentTypes();
        layoutTypeChips.removeAllViews();

        if (types.size() <= 1) {
            selectedType = null;
            logAdapter.setTypeFilter(null);
            scrollTypeChips.setVisibility(View.GONE);
            return;
        }

        // 之前选中的类型如果在新数据里消失了，回到「全部」，否则会停在空列表
        if (selectedType != null && !types.contains(selectedType)) {
            selectedType = null;
            logAdapter.setTypeFilter(null);
        }

        scrollTypeChips.setVisibility(View.VISIBLE);
        layoutTypeChips.addView(createChip(getString(R.string.log_view_filter_all), null));
        for (String type : types) {
            layoutTypeChips.addView(createChip(logAdapter.labelOf(type), type));
        }
        applyChipSelection();
    }

    /** 创建一个筛选胶囊：选中态由 {@link #applyChipSelection()} 统一刷新 */
    private TextView createChip(String label, String type) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setBackgroundResource(R.drawable.bg_pill_gray);
        chip.setTag(type);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);

        chip.setOnClickListener(v -> {
            Object tag = v.getTag();
            selectedType = tag == null ? null : tag.toString();
            logAdapter.setTypeFilter(selectedType);
            applyChipSelection();
            refreshHeaderAndEmpty();
            rvLog.scrollToPosition(0);
        });
        return chip;
    }

    /** 选中态用主色实心 + 白字，未选中用浅灰底 + 中性字，避免整行标签都抢注意力 */
    private void applyChipSelection() {
        for (int i = 0; i < layoutTypeChips.getChildCount(); i++) {
            TextView chip = (TextView) layoutTypeChips.getChildAt(i);
            Object tag = chip.getTag();
            boolean active = selectedType == null ? tag == null : selectedType.equals(tag);
            int bgColor = active ? Color.parseColor("#FF1976D2") : Color.parseColor("#FFF1F5F9");
            int fgColor = active ? Color.WHITE : Color.parseColor("#FF607D8B");
            if (chip.getBackground() != null) {
                chip.getBackground().mutate().setTintList(ColorStateList.valueOf(bgColor));
            }
            chip.setTextColor(fgColor);
        }
    }

    // endregion

    // region 日志加载与空态

    /**
     * 后台读全量日志（时间倒序）并上屏。
     *
     * <p>读库只做一次，搜索与类型筛选都在内存里完成；查询失败只把副标题换成错误文案，
     * 不影响已经显示的内容。</p>
     */
    private void loadLogs() {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();
                runOnUiThread(() -> {
                    logAdapter.submit(logList);
                    buildTypeChips();
                    refreshHeaderAndEmpty();
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvLogCount.setText(R.string.common_error));
            }
        }).start();
    }

    /**
     * 刷新副标题与空态。
     *
     * <p>空态分两种：库里本来就没日志（引导去跑任务）与筛选没命中（引导换条件），
     * 两种文案混用会让用户以为日志被清掉了。库里为空时连搜索区一起收起。</p>
     */
    private void refreshHeaderAndEmpty() {
        int total = logAdapter.getTotalCount();
        int shown = logAdapter.getShownCount();

        if (shown == total) {
            tvLogCount.setText(getString(R.string.common_items_fmt, total));
        } else {
            tvLogCount.setText(getString(R.string.log_view_count_filtered_fmt, shown, total));
        }

        layoutFilter.setVisibility(total == 0 ? View.GONE : View.VISIBLE);

        if (shown > 0) {
            layoutEmpty.setVisibility(View.GONE);
            rvLog.setVisibility(View.VISIBLE);
            return;
        }

        layoutEmpty.setVisibility(View.VISIBLE);
        rvLog.setVisibility(View.GONE);
        boolean noData = total == 0;
        tvLogEmpty.setText(noData ? R.string.log_view_tv_empty : R.string.log_view_empty_filtered);
        tvLogEmptyHint.setText(noData
                ? R.string.log_view_tv_empty_hint
                : R.string.log_view_empty_filtered_hint);
    }

    // endregion

    // region 用户操作

    /** 清空确认：与其它危险操作共用一套弹窗样式，确认后才真正删库 */
    private void confirmClearLogs() {
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_trash_svg,
                getString(R.string.dialog_clear_log_title),
                getString(R.string.dialog_clear_log_msg),
                getString(R.string.btn_clear_confirm),
                this::doClearLogs,
                getString(R.string.btn_cancel), null);
    }

    /**
     * 删库放后台线程；成功后清掉搜索框再重新加载。
     *
     * <p>不先清搜索框的话，清空后会停在「没有匹配的日志」空态上，看起来像没清干净。</p>
     */
    private void doClearLogs() {
        new Thread(() -> {
            try {
                AppLogDbHelper.getInstance(this).clearAllLogData();
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.toast_log_cleared, Toast.LENGTH_SHORT).show();
                    etSearch.setText("");
                    loadLogs();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.toast_clear_failed_fmt, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 复制全部日志到剪贴板：重新查一遍库（避免用屏上数据漏掉新日志，也忽略当前筛选），
     * 每行拼成「时间 编号 类型 内容」的纯文本，方便直接贴到聊天窗口。
     */
    private void copyLogsToClipboard() {
        new Thread(() -> {
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(this).queryAllLogDesc();

                if (logList == null || logList.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.toast_no_log_to_copy, Toast.LENGTH_SHORT).show());
                    return;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
                StringBuilder sb = new StringBuilder();

                for (TaskLogItem item : logList) {
                    String time = sdf.format(new Date(item.timeMs));
                    String keyNo = item.keyNo != null ? item.keyNo : "-";
                    String type = item.logType != null ? item.logType : "";
                    String content = item.content != null ? item.content : "";

                    // 纯文本格式：时间 编号 类型 内容
                    sb.append(String.format("%s %s %s %s\n", time, keyNo, type, content));
                }

                String logText = sb.toString();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText(getString(R.string.clip_label_operation_log), logText);
                clipboard.setPrimaryClip(clip);

                final int copiedCount = logList.size();
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.toast_log_copied_fmt, copiedCount), Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.toast_copy_failed_fmt, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // endregion

    // region 工具

    /** dp 转 px：筛选胶囊的内边距需要按密度换算，避免小屏上文字贴边 */
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // endregion

}
