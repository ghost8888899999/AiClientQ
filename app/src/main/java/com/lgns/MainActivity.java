package com.lgns;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.tabs.TabLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.web3j.protocol.Web3j;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements TaskLogCallback {
    public static final int MODE_FLEXIBLE = 0;
    public static final int MODE_TOGETHER = 1;
    private int currentPlayMode;
    char[] tempUserCryptoKey;
    List<KeyListItem> keyListItemList = new ArrayList<>();

    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    private static final long MIN_ALLOW_MINUTE = 15;
    private SharedPreferences spAppSetting;
    private final String[] unitArray = {"分钟", "小时", "天"};

    private ImageButton btnSettingGear;
    private TextView btnSecurityWarn;
    private TextView tvTokenCount, tvQdCount, tvEnabledCount;
    private Button btnBatchEnter, btnBatchClaim, btnFullWorkflow;
    private TextView tvLog;
    private ScrollView scrollView;
    private LinearLayout layoutLogHeader;
    private TextView tvLogToggle;

    private MainDialogHelper dialogHelper;
    private MainMenuHelper menuHelper;

    private TabLayout tabLayout;
    private RecyclerView rvDailyStatus, rvFailureList;
    private TextView tvEmptyStatus, tvEmptyFailure;
    private DailyStatusAdapter dailyStatusAdapter;
    private FailureLogAdapter failureLogAdapter;
    private List<DailyStatusItem> dailyStatusList = new ArrayList<>();
    private List<FailureLogItem> failureLogList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabLayout = findViewById(R.id.tab_layout);
        rvDailyStatus = findViewById(R.id.rv_daily_status);
        rvFailureList = findViewById(R.id.rv_failure_list);
        tvEmptyStatus = findViewById(R.id.tv_empty_status);
        tvEmptyFailure = findViewById(R.id.tv_empty_failure);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    rvDailyStatus.setVisibility(View.VISIBLE);
                    rvFailureList.setVisibility(View.GONE);
                } else {
                    rvDailyStatus.setVisibility(View.GONE);
                    rvFailureList.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        rvDailyStatus.setLayoutManager(new LinearLayoutManager(this));
        dailyStatusAdapter = new DailyStatusAdapter();
        rvDailyStatus.setAdapter(dailyStatusAdapter);

        rvFailureList.setLayoutManager(new LinearLayoutManager(this));
        failureLogAdapter = new FailureLogAdapter();
        rvFailureList.setAdapter(failureLogAdapter);

        spAppSetting = getSharedPreferences("app_setting", MODE_PRIVATE);
        currentPlayMode = spAppSetting.getInt("play_mode", MODE_FLEXIBLE);

        dialogHelper = new MainDialogHelper(this);
        menuHelper = new MainMenuHelper(this, spAppSetting);

        bindViews();
        setupButtonEvents();
        setupGearMenu();
        setupListViewCheckListener();
        checkAutoClearBoot();

        new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    appendLog("", "", "✅密钥管理器初始化完成");
                    refreshCipherKeyList();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "❌密钥管理器初始化失败:" + e.getMessage()));
            }
        }).start();
    }

    private void bindViews() {
        btnSettingGear = findViewById(R.id.btn_setting_gear);
        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        tvTokenCount = findViewById(R.id.tv_token_count);
        tvQdCount = findViewById(R.id.tv_qd_count);
        tvEnabledCount = findViewById(R.id.tv_enabled_count);
        btnBatchEnter = findViewById(R.id.btn_batch_enter);
        btnBatchClaim = findViewById(R.id.btn_batch_claim);
        btnFullWorkflow = findViewById(R.id.btn_full_workflow);
        tvLog = findViewById(R.id.tv_log);
        scrollView = findViewById(R.id.scrollView);
        layoutLogHeader = findViewById(R.id.layout_log_header);
        tvLogToggle = findViewById(R.id.tv_log_toggle);
    }

    private void setupButtonEvents() {
        btnSecurityWarn.setOnClickListener(v -> dialogHelper.showSecurityWarningDialog());

        layoutLogHeader.setOnClickListener(v -> {
            boolean visible = scrollView.getVisibility() == View.VISIBLE;
            scrollView.setVisibility(visible ? View.GONE : View.VISIBLE);
            tvLogToggle.setText(visible ? "▶" : "▼");
        });
        scrollView.setVisibility(View.VISIBLE);

        btnBatchEnter.setOnClickListener(v -> {
            List<KeyListItem> enabledList = getEnabledKeyItems();
            if (enabledList.isEmpty()) {
                android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d("UI_CLICK","点击【批量入场】按钮");
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    ArenaTask.batchEnterAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "ENTER", "❌批量入场外层异常：" + e.getMessage()));
                    Log.e("BATCH_ENTER","异常",e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                }
            }).start());
        });

        btnBatchClaim.setOnClickListener(v -> {
            List<KeyListItem> enabledList = getEnabledKeyItems();
            if (enabledList.isEmpty()) {
                android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d("UI_CLICK","点击【批量领取】按钮");
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    ArenaTask.batchClaimAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "CLAIM", "❌批量领取外层异常：" + e.getMessage()));
                    Log.e("BATCH_CLAIM","异常",e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                }
            }).start());
        });

        btnFullWorkflow.setOnClickListener(v -> {
            List<KeyListItem> enabledList = getEnabledKeyItems();
            if (enabledList.isEmpty()) {
                android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d("UI_CLICK","点击【完整工作流】按钮");
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    ArenaTask.fullArenaWorkflowResume(getApplicationContext(), this, 2, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "", "❌完整工作流外层异常：" + e.getMessage()));
                    Log.e("FULL_WORKFLOW","异常",e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                }
            }).start());
        });
    }

    private void setupGearMenu() {
        btnSettingGear.setOnClickListener(v -> menuHelper.showCollapseMenu(v));
    }

    private void setupListViewCheckListener() {
    }

    private void checkAutoClearBoot() {
        boolean enable = spAppSetting.getBoolean("auto_clear_enable", false);
        long saveTotalMs = spAppSetting.getLong("auto_clear_ms", 0);
        long saveStartTs = spAppSetting.getLong("auto_clear_start_ts", 0);
        if (!enable || saveTotalMs <= 0 || saveStartTs <= 0) return;

        long now = System.currentTimeMillis();
        if (now - saveStartTs >= saveTotalMs) {
            new Thread(() -> {
                try {
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.clearAllKeys();
                    runOnUiThread(() -> appendLog("", "", "⚠检测到自动清除计时到期，打开APP自动清空全部私钥！"));
                } catch (Exception ignored) {}
                spAppSetting.edit().putLong("auto_clear_start_ts", System.currentTimeMillis()).apply();
            }).start();
        }
    }

    // 获取启用的密钥列表
    List<KeyListItem> getEnabledKeyItems() {
        List<KeyListItem> enabled = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.enabled) enabled.add(item);
        }
        return enabled;
    }

    List<KeyListItem> getSelectedKeyItem() {
        List<KeyListItem> res = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) res.add(item);
        }
        return res;
    }

    List<String> loadGroupList() {
        String raw = spAppSetting.getString("group_list", "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
        }
        return list;
    }

    void saveGroupList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        spAppSetting.edit().putString("group_list", sb.toString()).apply();
    }

    void refreshCipherKeyList() {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();

                List<KeyStoreManager.KeyEntry> unique = new ArrayList<>();
                for (KeyStoreManager.KeyEntry e : originEntryList) {
                    boolean dup = false;
                    for (KeyStoreManager.KeyEntry u : unique) {
                        if (u.id == e.id) { dup = true; break; }
                    }
                    if (!dup) unique.add(e);
                }
                originEntryList = unique;

                List<Long> customSortIds = mgr.getCustomSortIdList();
                List<KeyStoreManager.KeyEntry> sortedEntryList = new ArrayList<>();
                for(Long sortId : customSortIds){
                    for(KeyStoreManager.KeyEntry e : originEntryList){
                        if(e.id == sortId){ sortedEntryList.add(e); break; }
                    }
                }
                for(KeyStoreManager.KeyEntry e : originEntryList){
                    boolean exist = false;
                    for(KeyStoreManager.KeyEntry s : sortedEntryList){
                        if(s.id == e.id){ exist = true; break; }
                    }
                    if(!exist) sortedEntryList.add(e);
                }
                keyListItemList.clear();
                for (int i = 0; i < sortedEntryList.size(); i++) {
                    KeyStoreManager.KeyEntry entry = sortedEntryList.get(i);
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
                runOnUiThread(() -> {
                    int enabledCount = 0;
                    for (KeyListItem item : keyListItemList) {
                        if (item.enabled) enabledCount++;
                    }
                    tvEnabledCount.setText(String.valueOf(enabledCount));
                    tvTokenCount.setText("0");
                    tvQdCount.setText("0");
                });

                List<KeyListItem> enabledItems = getEnabledKeyItems();
                if (!enabledItems.isEmpty()) {
                    new Thread(() -> {
                        try {
                            Web3j web3j = Web3AndroidUtil.dialAny();
                            List<String> addrs = new ArrayList<>();
                            for (KeyListItem item : enabledItems) {
                                if (item.address != null && !item.address.isEmpty()) {
                                    addrs.add(item.address);
                                }
                            }
                            if (addrs.isEmpty()) {
                                try { web3j.shutdown(); } catch (Exception ignore) {}
                                return;
                            }
                            Map<String, ArenaContractApi.PlayerSummary> summaries =
                                    ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);
                            try { web3j.shutdown(); } catch (Exception ignore) {}

                            long totalTokens = 0;
                            long totalQd = 0;
                            for (ArenaContractApi.PlayerSummary s : summaries.values()) {
                                if (s.walletBalance != null) {
                                    totalTokens += s.walletBalance.longValue();
                                }
                                if (s.championships != null) {
                                    totalQd += s.championships.longValue();
                                }
                            }
                            final String tokenStr = formatToken(totalTokens);
                            final String qdStr = String.valueOf(totalQd);
                            runOnUiThread(() -> {
                                tvTokenCount.setText(tokenStr);
                                tvQdCount.setText(qdStr);
                            });
                        } catch (Throwable t) {
                            runOnUiThread(() -> appendLog("", "", "查询链上数据异常：" + t.getClass().getSimpleName()));
                        }
                    }).start();
                }
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "❌刷新密钥列表异常：" + e.getMessage()));
            }
        }).start();
    }

    void openSysScheduleDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        final View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_sys_auto_clear, null);
        final Switch swAutoEnable = view.findViewById(R.id.sw_auto_enable);
        final android.widget.EditText etVal = view.findViewById(R.id.et_auto_clear_val);
        final android.widget.Spinner spUnit = view.findViewById(R.id.sp_time_unit);
        final TextView tvStatus = view.findViewById(R.id.tv_auto_clear_status);
        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, unitArray);
        spUnit.setAdapter(unitAdapter);
        boolean isEnable = spAppSetting.getBoolean("auto_clear_enable", false);
        long oldMs = spAppSetting.getLong("auto_clear_ms", 0);
        swAutoEnable.setChecked(isEnable);
        if (oldMs > 0) {
            if (oldMs >= 1000L * 60 * 60 * 24) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60 * 24)));
                spUnit.setSelection(2);
            } else if (oldMs >= 1000L * 60 * 60) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60)));
                spUnit.setSelection(1);
            } else {
                etVal.setText(String.valueOf(oldMs / (1000L * 60)));
                spUnit.setSelection(0);
            }
        }
        if(isEnable){
            tvStatus.setText("当前：已启用，到达时长会自动清除私钥");
            etVal.setEnabled(true); spUnit.setEnabled(true);
        }else{
            tvStatus.setText("当前：已禁用（配置已保存，不会校验清除；系统时间持续流逝）");
            etVal.setEnabled(false); spUnit.setEnabled(false);
        }
        swAutoEnable.setOnCheckedChangeListener((buttonView, checked) -> {
            etVal.setEnabled(checked); spUnit.setEnabled(checked);
            tvStatus.setText(checked ? "当前：已启用，到达时长会自动清除私钥" : "当前：已禁用（配置已保存，不会校验清除；系统时间持续流逝）");
        });
        final android.app.AlertDialog dialog = builder.setView(view)
                .setPositiveButton("保存", (d, i) -> {})
                .setNegativeButton("关闭", (d, i) -> d.dismiss())
                .create();
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean newEnable = swAutoEnable.isChecked();
            if(!newEnable){
                spAppSetting.edit().putBoolean("auto_clear_enable", false).apply();
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
                appendLog("", "", "✅已禁用自动清除（配置已保留）");
                dialog.dismiss();
                return;
            }
            String valStr = etVal.getText().toString().trim();
            if(valStr.isEmpty()){
                new android.app.AlertDialog.Builder(this).setMessage("请输入时间数值，最小15！").setPositiveButton("知道了",null).show();
                return;
            }
            int val;
            try { val = Integer.parseInt(valStr); } catch (Exception e) {
                new android.app.AlertDialog.Builder(this).setMessage("请输入合法数字").setPositiveButton("OK", null).show();
                return;
            }
            int selPos = spUnit.getSelectedItemPosition();
            long oneUnitMs, inputMinute;
            if (selPos == 0) { oneUnitMs = 60 * 1000L; inputMinute = val; }
            else if (selPos == 1) { oneUnitMs = 60 * 60 * 1000L; inputMinute = val * 60L; }
            else { oneUnitMs = 24 * 60 * 60 * 1000L; inputMinute = val * 24 * 60L; }
            if (inputMinute < MIN_ALLOW_MINUTE) {
                new android.app.AlertDialog.Builder(this).setTitle("输入限制").setMessage("最小设置15分钟！").setPositiveButton("确定", null).show();
                return;
            }
            spAppSetting.edit().putBoolean("auto_clear_enable", true).putLong("auto_clear_ms", val * oneUnitMs).putLong("auto_clear_start_ts", System.currentTimeMillis()).apply();
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AutoClearWorker.class, Math.max(inputMinute, MIN_ALLOW_MINUTE), TimeUnit.MINUTES).build();
            WorkManager.getInstance(getApplicationContext()).enqueueUniquePeriodicWork(WORK_AUTO_CLEAR, ExistingPeriodicWorkPolicy.UPDATE, req);
            appendLog("", "", "✅自动清除设置完成：" + val + " " + unitArray[selPos]);
            dialog.dismiss();
        });
    }

    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final long timeMs = System.currentTimeMillis();
        runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) sb.append("[").append(safeKeyNo).append("]");
            sb.append(msg);
            tvLog.setText(tvLog.getText() + sb.toString() + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
        new Thread(() -> AppLogDbHelper.getInstance(this).insertLog(safeKeyNo, safeType, msg, timeMs)).start();
    }

    void appendLog(String unusedKeyNo, String unusedType, String msg) {
        onLog(unusedKeyNo, unusedType, msg);
    }

    interface PasswordCallback {
        void onInputPwd(char[] pwd);
    }

    MainDialogHelper getDialogHelper() { return dialogHelper; }

    private String formatToken(long weiAmount) {
        if (weiAmount == 0) return "0";
        java.math.BigDecimal wei = java.math.BigDecimal.valueOf(weiAmount);
        java.math.BigDecimal divisor = java.math.BigDecimal.TEN.pow(18);
        java.math.BigDecimal result = wei.divide(divisor, 2, java.math.RoundingMode.DOWN);
        return result.toPlainString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        menuHelper.dismissPopup();
        if (tempUserCryptoKey != null) {
            Arrays.fill(tempUserCryptoKey, (char) 0);
            tempUserCryptoKey = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAutoClearBoot();
        refreshCipherKeyList();
        loadTodayStatus();
    }

    @Override
    public void onBatchResult(java.util.Map<String, ArenaContractApi.PlayerSummary> summaries) {
        new Thread(() -> {
            try {
                AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

                java.util.Set<String> failedKeyNos = new java.util.HashSet<>();
                List<FailureLogItem> failList = db.queryFailureLog(today);
                for (FailureLogItem f : failList) {
                    failedKeyNos.add(f.keyNo);
                }

                final java.util.Map<String, ArenaContractApi.PlayerSummary> safeSummaries =
                        summaries != null ? summaries : new java.util.HashMap<>();

                for (KeyListItem item : keyListItemList) {
                    if (!item.enabled || item.address == null) continue;
                    String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    boolean failed = failedKeyNos.contains(keyNo);
                    int totalQd = 0, todayQd = 0, todayEnter = 0;
                    long tokens = 0;
                    ArenaContractApi.PlayerSummary s = safeSummaries.get(item.address);
                    if (s != null) {
                        if (s.championships != null) totalQd = s.championships.intValue();
                        if (s.entriesToday != null) todayEnter = s.entriesToday.intValue();
                        if (s.walletBalance != null) tokens = s.walletBalance.longValue();
                    }
                    db.upsertDailyStatus(item.keyId, keyNo, today, totalQd, todayQd, todayEnter, tokens, failed);
                }
                runOnUiThread(() -> loadTodayStatus());
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    public void onKeyFailure(String keyNo, String failType, String reason) {
        new Thread(() -> {
            try {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                AppLogDbHelper.getInstance(this).insertFailure(0, keyNo, today, failType, reason);
                runOnUiThread(() -> loadTodayStatus());
            } catch (Exception ignored) {}
        }).start();
    }

    private void loadTodayStatus() {
        new Thread(() -> {
            try {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                List<DailyStatusItem> statusList = db.queryDailyStatus(today);
                List<FailureLogItem> failList = db.queryFailureLog(today);
                runOnUiThread(() -> {
                    dailyStatusList.clear();
                    dailyStatusList.addAll(statusList);
                    dailyStatusAdapter.notifyDataSetChanged();
                    tvEmptyStatus.setVisibility(statusList.isEmpty() ? View.VISIBLE : View.GONE);

                    failureLogList.clear();
                    failureLogList.addAll(failList);
                    failureLogAdapter.notifyDataSetChanged();
                    tvEmptyFailure.setVisibility(failList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception ignored) {}
        }).start();
    }

    class DailyStatusAdapter extends RecyclerView.Adapter<DailyStatusAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_daily_status, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            DailyStatusItem item = dailyStatusList.get(position);
            holder.tvSeq.setText(String.valueOf(position + 1));
            holder.tvKeyNo.setText(item.keyNo);
            holder.tvTotalQd.setText(String.valueOf(item.totalQd));
            holder.tvTodayQd.setText(String.valueOf(item.todayQd));
            holder.tvTodayEnter.setText(String.valueOf(item.todayEnter));
            if (item.isFailed) {
                holder.tvStatusBadge.setVisibility(View.VISIBLE);
                holder.tvStatusBadge.setText("失败");
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_fail);
                holder.itemRoot.setBackgroundColor(0xFFFFFBFB);
            } else {
                holder.tvStatusBadge.setVisibility(View.VISIBLE);
                holder.tvStatusBadge.setText("正常");
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge);
                holder.itemRoot.setBackgroundColor(0xFFFFFFFF);
            }
        }

        @Override
        public int getItemCount() {
            return dailyStatusList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvSeq, tvKeyNo, tvTotalQd, tvTodayQd, tvTodayEnter, tvStatusBadge;
            View itemRoot;
            VH(View v) {
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
    }

    class FailureLogAdapter extends RecyclerView.Adapter<FailureLogAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_failure_log, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            FailureLogItem item = failureLogList.get(position);
            holder.tvKeyNo.setText(item.keyNo);
            holder.tvFailType.setText(item.failType);
            holder.tvReason.setText(item.reason);
            holder.tvTime.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(item.timeMs)));
        }

        @Override
        public int getItemCount() {
            return failureLogList.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvKeyNo, tvFailType, tvReason, tvTime;
            VH(View v) {
                super(v);
                tvKeyNo = v.findViewById(R.id.tv_key_no);
                tvFailType = v.findViewById(R.id.tv_fail_type);
                tvReason = v.findViewById(R.id.tv_reason);
                tvTime = v.findViewById(R.id.tv_time);
            }
        }
    }
}