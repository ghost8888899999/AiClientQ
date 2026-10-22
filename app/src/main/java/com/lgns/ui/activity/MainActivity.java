package com.lgns.ui.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppPrefs;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.batch.BatchFundTask;
import com.lgns.batch.BatchEnterTask;
import com.lgns.batch.BatchClaimTask;
import com.lgns.batch.BatchWorkflowTask;
import com.lgns.ui.adapter.AccountStatsAdapter;
import com.lgns.manager.KeyStoreManager;
import com.lgns.helper.MainDialogHelper;
import com.lgns.helper.MainMenuHelper;
import com.lgns.helper.MainDataLoader;
import com.lgns.R;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.web3j.protocol.Web3j;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements TaskLogCallback, BatchFundTask.ButtonStateController {
    public static final int MODE_FLEXIBLE = 0;
    public static final int MODE_TOGETHER = 1;
    private int currentPlayMode;
    public char[] tempUserCryptoKey;
    public List<KeyListItem> keyListItemList = new ArrayList<>();

    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    private static final long MIN_ALLOW_MINUTE = 15;
    private SharedPreferences spAppSetting;
    private final String[] unitArray = {"分钟", "小时", "天"};

    private ImageButton btnSettingGear;
    private TextView btnSecurityWarn;
    private LinearLayout btnBatchFund, btnBatchEnter, btnBatchClaim, btnFullWorkflow;
    private TextView tvLog;
    private ScrollView scrollView;
    private LinearLayout layoutLogHeader;
    private TextView tvLogToggle;

    private MainDialogHelper dialogHelper;
    private MainMenuHelper menuHelper;

    private RecyclerView rvAccountList;
    private AccountStatsAdapter accountStatsAdapter;

    private TextView tvSummaryEnter, tvSummaryTodayWin, tvSummaryTotalWin, tvSummaryPrize;

    private volatile boolean isTaskRunning = false;
    private boolean skipRefreshOnResume = false;
    private boolean refreshLocalOnlyOnResume = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rvAccountList = findViewById(R.id.rv_account_list);

        tvSummaryEnter = findViewById(R.id.tv_summary_enter);
        tvSummaryTodayWin = findViewById(R.id.tv_summary_today_win);
        tvSummaryTotalWin = findViewById(R.id.tv_summary_total_win);
        tvSummaryPrize = findViewById(R.id.tv_summary_prize);

        rvAccountList.setLayoutManager(new LinearLayoutManager(this));
        accountStatsAdapter = new AccountStatsAdapter();
        rvAccountList.setAdapter(accountStatsAdapter);

        spAppSetting = AppPrefs.get(this);
        currentPlayMode = spAppSetting.getInt("play_mode", MODE_FLEXIBLE);

        dialogHelper = new MainDialogHelper(this);
        menuHelper = new MainMenuHelper(this, spAppSetting);

        bindViews();
        setupButtonEvents();
        setupGearMenu();
        checkAutoClearBoot();
        setupIdleLockCallback();

        // 注册 AppGlobal 的日志回调
        AppGlobal.setLogCallback(this);

        new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    appendLog("", "", "密钥管理器初始化完成");
                    // 初始化只加载本地私钥列表，不触发 RPC
                    loadKeyListLocalOnly(this::applyLocalDefaults);
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "错误：密钥管理器初始化失败:" + e.getMessage()));
            }
        }).start();
    }

    private void bindViews() {
        btnSettingGear = findViewById(R.id.btn_setting_gear);
        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        btnBatchFund = findViewById(R.id.btn_batch_fund);
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

        btnBatchFund.setOnClickListener(v -> handleBatchFundClick());
        btnBatchEnter.setOnClickListener(v -> handleBatchEnterClick());
        btnBatchClaim.setOnClickListener(v -> handleBatchClaimClick());
        btnFullWorkflow.setOnClickListener(v -> handleFullWorkflowClick());
    }

    private void handleBatchFundClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, "任务执行中，请勿重复点击", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        dialogHelper.showBatchFundDialog();
    }

    private void handleBatchEnterClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, "任务执行中，请勿重复点击", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量入场】按钮");
        setTaskButtonsEnabled(false);
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                BatchEnterTask.batchEnterAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "ENTER", "批量入场外层异常：" + e.getMessage()));
                Log.e("BATCH_ENTER", "异常", e);
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
                runOnUiThread(() -> setTaskButtonsEnabled(true));
            }
        }).start());
    }

    private void handleBatchClaimClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, "任务执行中，请勿重复点击", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量领取】按钮");
        setTaskButtonsEnabled(false);
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                BatchClaimTask.batchClaimAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "CLAIM", "错误：批量领取外层异常：" + e.getMessage()));
                Log.e("BATCH_CLAIM", "异常", e);
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
                runOnUiThread(() -> setTaskButtonsEnabled(true));
            }
        }).start());
    }

    private void handleFullWorkflowClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, "任务执行中，请勿重复点击", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, "没有启用的私钥，请先在私钥管理中启用", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【完整工作流】按钮");
        setTaskButtonsEnabled(false);
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                BatchWorkflowTask.fullArenaWorkflowResume(getApplicationContext(), this, 2, currentPlayMode, enabledList, pwdCharArray);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "完整工作流外层异常：" + e.getMessage()));
                Log.e("FULL_WORKFLOW", "异常", e);
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
                runOnUiThread(() -> setTaskButtonsEnabled(true));
            }
        }).start());
    }

    private void setTaskButtonsEnabled(boolean enabled) {
        isTaskRunning = !enabled;
        runOnUiThread(() -> {
            btnBatchFund.setEnabled(enabled);
            btnBatchEnter.setEnabled(enabled);
            btnBatchClaim.setEnabled(enabled);
            btnFullWorkflow.setEnabled(enabled);
            float alpha = enabled ? 1.0f : 0.5f;
            btnBatchFund.setAlpha(alpha);
            btnBatchEnter.setAlpha(alpha);
            btnBatchClaim.setAlpha(alpha);
            btnFullWorkflow.setAlpha(alpha);
        });
    }

    private void setupGearMenu() {
        btnSettingGear.setOnClickListener(v -> menuHelper.showCollapseMenu(v));
    }

    private void checkAutoClearBoot() {
        ArenaConfig.loadConfig(this);
        SharedPreferences spTs = AppPrefs.get(this);
        long now = System.currentTimeMillis();

        // 检查私钥自动清除
        if (ArenaConfig.autoClearEnable) {
            long lastClearTs = spTs.getLong("last_clear_timestamp", 0);
            long clearIntervalMs = getClearIntervalMs(ArenaConfig.autoClearIntervalValue, ArenaConfig.autoClearIntervalUnitIndex);

            if (now - lastClearTs >= clearIntervalMs) {
                new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        mgr.clearAllKeys();
                        spTs.edit().putLong("last_clear_timestamp", now).apply();
                        runOnUiThread(() -> appendLog("", "", "警告：检测到自动清除计时到期，打开 APP 自动清空全部私钥！"));
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        }

        // 检查日志自动清除（打开 App 时立即检查）
        long lastLogClearTs = spTs.getLong("last_log_clear_timestamp", 0);
        long logClearIntervalMs = getClearIntervalMs(ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);

        if (now - lastLogClearTs >= logClearIntervalMs) {
            new Thread(() -> {
                try {
                    AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
                    int deletedCount = dbHelper.clearAllLogData();
                    spTs.edit().putLong("last_log_clear_timestamp", now).apply();
                    runOnUiThread(() -> appendLog("", "CONFIG", "日志清除到期，已清除 " + deletedCount + " 条数据"));
                } catch (Exception ignored) {
                }
            }).start();
        }

        // 启动定时清除日志调度器（App 运行期间每间隔配置时长自动清除）
        long logClearMinutes = AppGlobal.getIntervalInMinutes(
                ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
        AppGlobal.startLogClearScheduler(logClearMinutes);
    }

    private long getClearIntervalMs(int value, int unitIndex) {
        switch (unitIndex) {
            case 0:
                return java.util.concurrent.TimeUnit.MINUTES.toMillis(value);
            case 1:
                return java.util.concurrent.TimeUnit.HOURS.toMillis(value);
            case 2:
                return java.util.concurrent.TimeUnit.DAYS.toMillis(value);
            default:
                return java.util.concurrent.TimeUnit.HOURS.toMillis(value);
        }
    }

    private void setupIdleLockCallback() {
        AppGlobal.setOnIdleLock(() -> runOnUiThread(() -> {
            appendLog("", "", "会话已过期，请重新输入密码解锁");
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    AppGlobal.initKeyManager(getApplicationContext());
                    runOnUiThread(() -> {
                        appendLog("", "", "密码验证成功，会话已恢复");
                        refreshCipherKeyList();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "", "错误：密码验证失败：" + e.getMessage()));
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                }
            }).start());
        }));
    }

    public List<KeyListItem> getEnabledKeyItems() {
        List<KeyListItem> enabled = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.enabled) enabled.add(item);
        }
        return enabled;
    }

    public List<KeyListItem> getSelectedKeyItem() {
        List<KeyListItem> res = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) res.add(item);
        }
        return res;
    }

    public List<String> loadGroupList() {
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

    public void saveGroupList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        spAppSetting.edit().putString("group_list", sb.toString()).apply();
    }

    public void refreshCipherKeyList() {
        MainDataLoader.refreshCipherKeyList(this, this, keyListItemList);
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

    public void appendLog(String keyNo, String type, String msg) {
        onLog(keyNo, type, msg);
    }

    public interface PasswordCallback {
        void onInputPwd(char[] pwd);
    }

    // 本地数据就绪回调：items 为后台线程构建好的占位展示列表
    public interface StatsReadyCallback {
        void onReady(List<AccountStatsAdapter.DisplayItem> items);
    }

    public MainDialogHelper getDialogHelper() {
        return dialogHelper;
    }

    @Override
    public boolean isRunning() {
        return isTaskRunning;
    }

    @Override
    public void setRunning(boolean running) {
        setTaskButtonsEnabled(!running);
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
        
        if (skipRefreshOnResume) {
            skipRefreshOnResume = false;
            Log.d("MainActivity", "onResume: skip refresh from sys config");
        } else if (refreshLocalOnlyOnResume) {
            refreshLocalOnlyOnResume = false;
            loadKeyListLocalOnly(this::applyLocalDefaults);
            Log.d("MainActivity", "onResume: refresh local only from key manage");
        } else {
            // 其他情况（如从日志查看返回），不刷新
            Log.d("MainActivity", "onResume: no refresh");
        }
    }

    public void setSkipRefreshOnResume() {
        skipRefreshOnResume = true;
    }

    public void setRefreshLocalOnlyOnResume() {
        refreshLocalOnlyOnResume = true;
    }

    @Override
    public void onBatchResult(java.util.Map<String, ArenaContractApi.PlayerSummary> summaries) {
        // 批量任务结束后直接用链上最新数据刷新账号列表
        loadStatsOverview();
    }

    @Override
    public void onKeyFailure(String keyNo, String failType, String reason) {
        new Thread(() -> {
            try {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                // 查找对应的 groupNo
                String groupNo = "";
                for (KeyListItem item : keyListItemList) {
                    String no = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    if (no.equals(keyNo)) {
                        groupNo = item.groupNo != null ? item.groupNo : "";
                        break;
                    }
                }
                // 失败留档（日志中亦有记录），不再驱动任何 UI
                AppLogDbHelper.getInstance(this).insertFailure(0, keyNo, groupNo, today, failType, reason);
            } catch (Exception ignored) {}
        }).start();
    }

    // 私钥管理变更/冷启动回调：数据库最新结构立即上屏；
    // 未变更地址继承已加载的链上数值（避免归零闪烁），新增地址零值兜底；汇总栏按合并值重算；随后链上异步回填
    private void applyLocalDefaults(List<AccountStatsAdapter.DisplayItem> items) {
        Map<String, AccountStatsAdapter.AccountStatsItem> oldMap = accountStatsAdapter.snapshotAccountItems();
        int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0;
        BigInteger sumEarnings = BigInteger.ZERO;
        for (AccountStatsAdapter.DisplayItem d : items) {
            AccountStatsAdapter.AccountStatsItem item = d.account;
            if (item == null) continue;
            AccountStatsAdapter.AccountStatsItem old = oldMap.get(item.address);
            if (old != null) {
                item.balance = old.balance;
                item.entriesToday = old.entriesToday;
                item.entriesRemaining = old.entriesRemaining;
                item.todayChampion = old.todayChampion;
                item.totalChampion = old.totalChampion;
                item.prize = old.prize;
                item.totalEarnings = old.totalEarnings;
            }
            sumEnter += item.entriesToday;
            sumTodayWin += item.todayChampion;
            sumTotalWin += item.totalChampion;
            if (item.totalEarnings != null) sumEarnings = sumEarnings.add(item.totalEarnings);
        }
        accountStatsAdapter.setItems(items);
        tvSummaryEnter.setText(sumEnter + "/" + (getEnabledKeyItems().size() * 12));
        tvSummaryTodayWin.setText(String.valueOf(sumTodayWin));
        tvSummaryTotalWin.setText(String.valueOf(sumTotalWin));
        tvSummaryPrize.setText(formatToken(sumEarnings));
        loadStatsOverview();
    }

    // 初始化时只加载本地私钥列表，不触发 RPC；
    // 同一后台线程内顺带完成分组查询与占位列表构建，仅一次主线程提交，保证毫秒级上屏
    private void loadKeyListLocalOnly(StatsReadyCallback onLoaded) {
        new Thread(() -> {
            List<AccountStatsAdapter.DisplayItem> placeholderItems = new ArrayList<>();
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
                
                List<Long> customSortIds = mgr.getCustomSortIdList();
                List<KeyStoreManager.KeyEntry> sorted = new ArrayList<>();
                for (Long sortId : customSortIds) {
                    for (KeyStoreManager.KeyEntry e : unique) {
                        if (e.id == sortId) { sorted.add(e); break; }
                    }
                }
                for (KeyStoreManager.KeyEntry e : unique) {
                    boolean exist = false;
                    for (KeyStoreManager.KeyEntry s : sorted) {
                        if (s.id == e.id) { exist = true; break; }
                    }
                    if (!exist) sorted.add(e);
                }

                keyListItemList.clear();
                for (int i = 0; i < sorted.size(); i++) {
                    KeyStoreManager.KeyEntry entry = sorted.get(i);
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

                // 同一线程内立即构建占位展示列表（数据库分组 + 零值默认），避免额外线程跳转
                List<AccountStatsAdapter.AccountStatsItem> statItems = new ArrayList<>();
                for (KeyListItem keyItem : keyListItemList) {
                    if (!keyItem.enabled) continue;
                    String addr = keyItem.address;
                    if (addr == null || addr.isEmpty()) continue;
                    String name = (keyItem.customCode != null && !keyItem.customCode.isEmpty())
                            ? keyItem.customCode : keyItem.displayNo;
                    AccountStatsAdapter.AccountStatsItem statItem = new AccountStatsAdapter.AccountStatsItem(
                            name, addr, BigInteger.ZERO, 0, 12, 0, 0, BigInteger.ZERO, BigInteger.ZERO);
                    statItem.groupNo = keyItem.groupNo;
                    statItems.add(statItem);
                }
                placeholderItems = groupStatsItems(statItems);
            } catch (Exception e) {
                Log.e("MainActivity", "loadKeyListLocalOnly error", e);
            }
            if (onLoaded != null) {
                final List<AccountStatsAdapter.DisplayItem> fItems = placeholderItems;
                runOnUiThread(() -> onLoaded.onReady(fItems));
            }
        }).start();
    }

    // 按数据库分组构建展示列表（仅限后台线程调用）
    private List<AccountStatsAdapter.DisplayItem> groupStatsItems(List<AccountStatsAdapter.AccountStatsItem> statItems) {
        List<String> groupNames = AppLogDbHelper.getInstance(this).queryAllGroups();
        Map<String, List<AccountStatsAdapter.AccountStatsItem>> groupMap = new java.util.LinkedHashMap<>();
        for (String g : groupNames) groupMap.put(g, new ArrayList<>());
        List<AccountStatsAdapter.AccountStatsItem> ungrouped = new ArrayList<>();
        for (AccountStatsAdapter.AccountStatsItem item : statItems) {
            String group = (item.groupNo != null && !item.groupNo.isEmpty()) ? item.groupNo : null;
            if (group != null && groupMap.containsKey(group)) {
                groupMap.get(group).add(item);
            } else {
                ungrouped.add(item);
            }
        }
        List<AccountStatsAdapter.DisplayItem> displayItems = new ArrayList<>();
        for (Map.Entry<String, List<AccountStatsAdapter.AccountStatsItem>> me : groupMap.entrySet()) {
            if (!me.getValue().isEmpty()) {
                displayItems.add(AccountStatsAdapter.DisplayItem.header(me.getKey()));
                for (AccountStatsAdapter.AccountStatsItem ai : me.getValue()) {
                    displayItems.add(AccountStatsAdapter.DisplayItem.account(me.getKey(), ai));
                }
            }
        }
        if (!ungrouped.isEmpty()) {
            displayItems.add(AccountStatsAdapter.DisplayItem.header("未分组"));
            for (AccountStatsAdapter.AccountStatsItem ai : ungrouped) {
                displayItems.add(AccountStatsAdapter.DisplayItem.account("未分组", ai));
            }
        }
        return displayItems;
    }

    private void loadStatsOverview() {
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) return;

        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();

                List<String> addrs = new ArrayList<>();
                for (KeyListItem item : enabledList) {
                    if (item.address != null && !item.address.isEmpty()) {
                        addrs.add(item.address);
                    }
                }

                Map<String, ArenaContractApi.PlayerSummary> summaryMap =
                        ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);

                Map<String, Integer> todayWinMap = new java.util.LinkedHashMap<>();
                for (String addr : addrs) {
                    Integer todayWin = ArenaContractApi.callPlayerHistoryLatestWendToday(web3j, addr);
                    todayWinMap.put(addr, todayWin != null ? todayWin : 0);
                }

                int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0;
                BigInteger sumPrize = BigInteger.ZERO;
                BigInteger sumTotalEarnings = BigInteger.ZERO;

                List<AccountStatsAdapter.AccountStatsItem> statItems = new ArrayList<>();

                for (KeyListItem keyItem : enabledList) {
                    String addr = keyItem.address;
                    if (addr == null || addr.isEmpty()) continue;

                    ArenaContractApi.PlayerSummary s = summaryMap.get(addr);
                    if (s == null) continue;

                    int todayWin = todayWinMap.getOrDefault(addr, 0);
                    sumEnter += s.entriesToday.intValue();
                    sumTodayWin += todayWin;
                    sumTotalWin += s.championships.intValue();
                    sumPrize = sumPrize.add(s.prize);

                    BigInteger battles = s.battles != null ? s.battles : BigInteger.ZERO;
                    BigInteger championships = s.championships != null ? s.championships : BigInteger.ZERO;
                    BigInteger loserPayout = new BigDecimal("100.8").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
                    BigInteger jiaziGrant = new BigDecimal("200").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
                    BigInteger entryFee = new BigDecimal("100").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
                    BigInteger netEarnings = battles.multiply(loserPayout)
                            .add(championships.multiply(jiaziGrant))
                            .subtract(battles.multiply(entryFee));
                    if (netEarnings.signum() < 0) netEarnings = BigInteger.ZERO;
                    sumTotalEarnings = sumTotalEarnings.add(netEarnings);

                    String name = (keyItem.customCode != null && !keyItem.customCode.isEmpty())
                            ? keyItem.customCode : keyItem.displayNo;

                    AccountStatsAdapter.AccountStatsItem statItem = new AccountStatsAdapter.AccountStatsItem(
                            name, addr, s.walletBalance,
                            s.entriesToday.intValue(), s.entriesRemainingToday.intValue(),
                            todayWin, s.championships.intValue(), s.prize, netEarnings
                    );
                    statItem.groupNo = keyItem.groupNo;
                    statItems.add(statItem);

                }

                final int fSumEnter = sumEnter;
                final int fSumTodayWin = sumTodayWin;
                final int fSumTotalWin = sumTotalWin;
                final BigInteger fSumPrize = sumPrize;
                final BigInteger fSumTotalEarnings = sumTotalEarnings;
                final int fTotalMax = enabledList.size() * 12;
                final List<AccountStatsAdapter.DisplayItem> fItems = groupStatsItems(statItems);

                runOnUiThread(() -> {
                    tvSummaryEnter.setText(fSumEnter + "/" + fTotalMax);
                    tvSummaryTodayWin.setText(String.valueOf(fSumTodayWin));
                    tvSummaryTotalWin.setText(String.valueOf(fSumTotalWin));
                    tvSummaryPrize.setText(formatToken(fSumTotalEarnings));
                    accountStatsAdapter.setItems(fItems);
                });

            } catch (Exception e) {
                Log.e("StatsOverview", "加载失败", e);
                runOnUiThread(() -> appendLog("", "", "加载统计失败：" + Web3AndroidUtil.safeMessage(e)));
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    private String formatToken(BigInteger wei) {
        if (wei == null) return "0.00";
        BigDecimal bd = new BigDecimal(wei)
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
        return bd.toPlainString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BATCH_FUND && resultCode == RESULT_OK) {
            // 用户确认充值，执行充值操作
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    BatchFundTask.executeAutoFund(getEnabledKeyItems(), pwdCharArray, this);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "FUND", 
                            getString(R.string.fund_query_fail, Web3AndroidUtil.safeMessage(e))));
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                }
            }).start());
        }
    }

    private static final int REQUEST_BATCH_FUND = 1001;
}