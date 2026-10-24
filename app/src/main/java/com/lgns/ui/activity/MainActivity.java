package com.lgns.ui.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    // 当前实例弱引用：私钥管理页修改加解密密码后同步会话密码用，不阻止 GC
    public static java.lang.ref.WeakReference<MainActivity> sInstance;
    public List<KeyListItem> keyListItemList = new ArrayList<>();
    // 调试专用：地址池展开出的展示行（仅主界面/锦标赛统计用；keyId 为负、无密文，不参与解密和转账）
    private volatile List<KeyListItem> debugKeyItems;

    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    private static final long MIN_ALLOW_MINUTE = 15;
    private SharedPreferences spAppSetting;

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
    private TextView tvSummaryEnterLabel;
    // 汇总行下方的同步细进度条：批量查询期间显示，查完隐藏
    private ProgressBar pbStatsSync;

    private volatile boolean isTaskRunning = false;
    // 本地账号清单是否已就绪：未就绪时禁止发起批量操作，避免拿半成品清单去跑任务
    private volatile boolean accountDataReady = false;
    // 链上统计状态：主界面上的余额/入场/冠军全是占位 0 时不允许点按钮，
    // 否则用户会据此误判（以为都没充值/都没入场）而重复操作
    private static final int STATS_IDLE = 0;      // 未就绪：等待首轮链上回填
    private static final int STATS_READY = 1;     // 已回填：数据可读
    private static final int STATS_FAILED = 2;    // 首轮失败：保持禁用，汇总栏点按重试
    private volatile int statsPhase = STATS_IDLE;
    // 统计请求序号：onResume/任务结束/本地变更都可能并发触发，只让最新一次结果上屏
    private final java.util.concurrent.atomic.AtomicInteger statsSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean skipRefreshOnResume = false;
    private boolean refreshLocalOnlyOnResume = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = new java.lang.ref.WeakReference<>(this);
        setContentView(R.layout.activity_main);

        rvAccountList = findViewById(R.id.rv_account_list);

        tvSummaryEnter = findViewById(R.id.tv_summary_enter);
        tvSummaryEnterLabel = findViewById(R.id.tv_summary_enter_label);
        pbStatsSync = findViewById(R.id.pb_stats_sync);
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
        // 本地清单尚未加载：先把四个操作按钮置灰，加载完成（或加载失败）后再放开
        applyButtonEnabledState();
        setupGearMenu();
        checkAutoClearBoot();
        setupIdleLockCallback();

        // 注册 AppGlobal 的日志回调
        AppGlobal.setLogCallback(this);
        // 自动过程提示（定时清理等）只上屏、不落库
        AppGlobal.setUiLogCallback((keyNo, type, msg) -> appendUiLog(keyNo, msg));

        new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    // 初始化成功不再上屏：本地私钥加载属于后台准备，成功时对用户无实际意义
                    // 初始化只加载本地私钥列表，不触发 RPC
                    loadKeyListLocalOnly(this::applyLocalDefaults);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendUiLog(null, "错误：密钥管理器初始化失败:" + e.getMessage());
                    // 初始化失败也要放开按钮：此时没有可查账号，交给点击时的“无启用密钥”提示
                    statsPhase = STATS_READY;
                    accountDataReady = true;
                    applyButtonEnabledState();
                });
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
            tvLogToggle.setText(visible
                    ? getString(R.string.arrow_collapsed)
                    : getString(R.string.arrow_expanded));
        });
        scrollView.setVisibility(View.VISIBLE);

        btnBatchFund.setOnClickListener(v -> handleBatchFundClick());
        btnBatchEnter.setOnClickListener(v -> handleBatchEnterClick());
        btnBatchClaim.setOnClickListener(v -> handleBatchClaimClick());
        btnFullWorkflow.setOnClickListener(v -> handleFullWorkflowClick());

        // 统计加载失败时，汇总栏兼作重试入口（其他状态下点按无副作用）
        tvSummaryEnter.setOnClickListener(v -> {
            if (statsPhase == STATS_FAILED) loadStatsOverview();
        });
    }

    private void handleBatchFundClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        dialogHelper.showBatchFundDialog();
    }

    private void handleBatchEnterClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量入场】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            setTaskButtonsEnabled(false);
            new Thread(() -> {
                try {
                    BatchEnterTask.batchEnterAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "ENTER", "批量入场外层异常：" + e.getMessage()));
                    Log.e("BATCH_ENTER", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(() -> setTaskButtonsEnabled(true));
                }
            }).start();
        });
    }

    private void handleBatchClaimClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量领取】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            setTaskButtonsEnabled(false);
            new Thread(() -> {
                try {
                    BatchClaimTask.batchClaimAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "CLAIM", "错误：批量领取外层异常：" + e.getMessage()));
                    Log.e("BATCH_CLAIM", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(() -> setTaskButtonsEnabled(true));
                }
            }).start();
        });
    }

    private void handleFullWorkflowClick() {
        if (isTaskRunning) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【完整工作流】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            setTaskButtonsEnabled(false);
            new Thread(() -> {
                try {
                    BatchWorkflowTask.fullArenaWorkflowResume(getApplicationContext(), this, 2, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "", "完整工作流外层异常：" + e.getMessage()));
                    Log.e("FULL_WORKFLOW", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(() -> setTaskButtonsEnabled(true));
                }
            }).start();
        });
    }

    private void setTaskButtonsEnabled(boolean enabled) {
        isTaskRunning = !enabled;
        applyButtonEnabledState();
    }

    /**
     * 按钮可用 = 本地清单已就绪 && 首轮链上统计已回填 && 当前没有任务在跑。
     * 关键在第二项：统计没回来之前，界面上的余额/入场/冠军全是占位 0，
     * 与真实的 0 无法区分，此时放行等于让用户基于错数据去点批量充值/入场。
     */
    private void applyButtonEnabledState() {
        runOnUiThread(() -> {
            boolean enabled = accountDataReady && statsPhase == STATS_READY && !isTaskRunning;
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

    // 清单/统计未就绪时点击（按钮已置灰，此处兼顾无障碍/代码路径）：明确提示，不拿半成品数据跑任务
    private boolean ensureAccountDataReady() {
        if (!accountDataReady) {
            android.widget.Toast.makeText(this, R.string.account_loading_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        if (statsPhase == STATS_FAILED) {
            android.widget.Toast.makeText(this, R.string.stats_load_failed_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        if (statsPhase != STATS_READY) {
            android.widget.Toast.makeText(this, R.string.account_loading_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
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
                        runOnUiThread(() -> appendUiLog(null, "警告：检测到自动清除计时到期，打开 APP 自动清空全部私钥！"));
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        }

        // 检查日志自动清理（独立开关：只删除早于保留时长的旧记录）
        if (ArenaConfig.logClearEnable) {
            long lastLogClearTs = spTs.getLong("last_log_clear_timestamp", 0);
            long logClearIntervalMs = getClearIntervalMs(ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);

            if (now - lastLogClearTs >= logClearIntervalMs) {
                new Thread(() -> {
                    try {
                        long cutoff = now - logClearIntervalMs;
                        AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
                        int deletedCount = dbHelper.deleteLogsOlderThan(cutoff)
                                + dbHelper.deleteDailyStatusOlderThan(cutoff)
                                + dbHelper.deleteFailureLogsOlderThan(cutoff);
                        spTs.edit().putLong("last_log_clear_timestamp", now).apply();
                        // 没删到东西就不刷屏：启动即校验，每次都写一行会把日志区冲满
                        if (deletedCount > 0) {
                            runOnUiThread(() -> appendUiLog(null, "日志清理到期，已清理 " + deletedCount + " 条旧记录"));
                        }
                    } catch (Exception ignored) {
                    }
                }).start();
            }

            // 启动定时清理日志调度器（App 运行期间按间隔删除旧记录）
            long logClearMinutes = AppGlobal.getIntervalInMinutes(
                    ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
            AppGlobal.startLogClearScheduler(logClearMinutes);
        } else {
            // 开关关闭：停掉运行中的日志清理线程
            AppGlobal.stopLogClearScheduler();
        }
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
            appendUiLog(null, "会话已过期，请重新输入密码解锁");
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                try {
                    AppGlobal.initKeyManager(getApplicationContext());
                    runOnUiThread(() -> {
                        appendUiLog(null, "密码验证成功，会话已恢复");
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
        // 调试模式：锦标赛统计按地址池展示行出数，与主界面看到的地址一致
        List<KeyListItem> debug = debugKeyItems;
        if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && debug != null && !debug.isEmpty()) {
            return new ArrayList<>(debug);
        }
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

    // 密钥数据变更（补全地址/迁移等）后：本地结构重建 + 数值继承 + 链上回填
    public void refreshLocalAndStats() {
        loadKeyListLocalOnly(this::applyLocalDefaults);
    }

    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final long timeMs = System.currentTimeMillis();
        appendLogToUi(safeKeyNo, msg, timeMs);
        // 只有用户主动触发的操作（批量任务、密钥增删等）才落库
        new Thread(() -> AppLogDbHelper.getInstance(this).insertLog(safeKeyNo, safeType, msg, timeMs)).start();
    }

    // 非用户点击触发的过程日志（启动初始化、定时清理、自动刷新等）：只上屏，不写数据库，避免 db 越积越大
    public void appendUiLog(String keyNo, String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        appendLogToUi(safeKeyNo, msg, System.currentTimeMillis());
    }

    private void appendLogToUi(final String safeKeyNo, final String msg, final long timeMs) {
        runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) sb.append("[").append(safeKeyNo).append("]");
            sb.append(msg);
            tvLog.setText(tvLog.getText() + sb.toString() + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
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
        if (sInstance != null && sInstance.get() == this) sInstance = null;
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

    // 列表里的有效地址数（调试模式=地址池行数）：今日入场分母 = 地址数 × 12
    private static int countAddresses(List<KeyListItem> items) {
        int n = 0;
        for (KeyListItem it : items) {
            if (it != null && it.address != null && !it.address.isEmpty()) n++;
        }
        return n;
    }

    // 私钥管理变更/冷启动回调：数据库最新结构立即上屏；
    // 未变更地址继承已加载的链上数值（避免归零闪烁），新增地址零值兜底；汇总栏按合并值重算；随后链上异步回填
    private void applyLocalDefaults(List<AccountStatsAdapter.DisplayItem> items) {
        Map<String, AccountStatsAdapter.AccountStatsItem> oldMap = accountStatsAdapter.snapshotAccountItems();
        int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0, knownToday = 0;
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
                item.pending = old.pending;
                item.unknown = old.unknown;
            } else {
                // 从未查过的地址：零值只是占位，标成“同步中”（不是查不到），别当成真实的 0
                item.pending = true;
            }
            sumEnter += item.entriesToday;
            // 未就绪/未查到的行不参与今日冠军合计：它们带着占位值或 -1，累加会把总计算错
            if (!item.pending && !item.unknown && item.todayChampion >= 0) {
                sumTodayWin += item.todayChampion;
                knownToday++;
            }
            sumTotalWin += item.totalChampion;
            if (item.totalEarnings != null) sumEarnings = sumEarnings.add(item.totalEarnings);
        }
        accountStatsAdapter.setItems(items);
        int addrCount = countAddresses(getDisplayKeyItems());
        // 标签里直接写出地址数：分母是“地址数 × 12”，不写出来很容易被误读
        tvSummaryEnterLabel.setText(getString(R.string.summary_enter_label_fmt, addrCount));
        if (statsPhase == STATS_READY) {
            tvSummaryEnter.setText(sumEnter + "/" + (addrCount * 12));
            tvSummaryTodayWin.setText((addrCount > 0 && knownToday == 0)
                    ? getString(R.string.common_placeholder) : String.valueOf(sumTodayWin));
            tvSummaryTotalWin.setText(String.valueOf(sumTotalWin));
            tvSummaryPrize.setText(formatToken(sumEarnings));
        } else if (statsPhase == STATS_FAILED) {
            showStatsUnavailable();
        } else {
            // 首轮链上统计还没回来：数值位一律占位（进度由汇总行下方的细进度条表达），
            // 别把占位 0 当成真实合计
            tvSummaryEnter.setText(R.string.common_placeholder);
            tvSummaryTodayWin.setText(R.string.common_placeholder);
            tvSummaryTotalWin.setText(R.string.common_placeholder);
            tvSummaryPrize.setText(R.string.common_placeholder);
        }
        loadStatsOverview();
    }

    // 统计不可用：汇总栏改成提示语兼重试入口，避免用户把占位 0 当真实数据
    private void showStatsUnavailable() {
        showSyncBar(false);
        tvSummaryEnter.setText(R.string.stats_load_failed_retry);
        tvSummaryTodayWin.setText(R.string.common_placeholder);
        tvSummaryTotalWin.setText(R.string.common_placeholder);
        tvSummaryPrize.setText(R.string.common_placeholder);
    }

    // 同步细进度条开关：整列表的同步信号只在这里表达，不在行上重复；
    // 显示时把进度归零，避免上一次刷新的比例残留
    private void showSyncBar(boolean show) {
        if (pbStatsSync != null) {
            if (show) pbStatsSync.setProgress(0);
            pbStatsSync.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // 进度按“两波查询”计：第一波主体数据、第二波今日冠军，各占一半；
    // 用确定进度而不是一直转的不确定条，地址多时用户能看到数字在涨
    private void updateSyncProgress(int doneUnits, int totalUnits) {
        if (pbStatsSync == null || totalUnits <= 0) return;
        pbStatsSync.setProgress((int) Math.min(100L, doneUnits * 100L / totalUnits));
    }

    // 供锦标赛统计页继承已加载的冠军次数（对标 applyLocalDefaults 的快照继承机制）
    public Map<String, AccountStatsAdapter.AccountStatsItem> getAccountStatsSnapshot() {
        return accountStatsAdapter.snapshotAccountItems();
    }

    // 初始化时只加载本地私钥列表，不触发 RPC；
    // 同一后台线程内顺带完成分组查询与占位列表构建，仅一次主线程提交，保证毫秒级上屏
    private void loadKeyListLocalOnly(StatsReadyCallback onLoaded) {
        new Thread(() -> {
            List<AccountStatsAdapter.DisplayItem> placeholderItems = new ArrayList<>();
            List<KeyListItem> newItems = new ArrayList<>();
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
                    newItems.add(item);
                }

                // 调试专用：主界面展示行改为地址池全部地址（按分组平分，与私钥数量无关）；
                // 只影响展示与统计，不动真实清单，批量任务仍按真实私钥执行
                debugKeyItems = MainDataLoader.buildDebugKeyItems(this);
                // 清单还没交给主线程，展示源只能从本地变量取（getDisplayKeyItems 读的是旧字段）
                List<KeyListItem> displaySource = new ArrayList<>();
                if (debugKeyItems != null && !debugKeyItems.isEmpty()) {
                    displaySource.addAll(debugKeyItems);
                } else {
                    displaySource.addAll(newItems);
                }

                // 同一线程内立即构建占位展示列表（数据库分组 + 零值默认），避免额外线程跳转
                List<AccountStatsAdapter.AccountStatsItem> statItems = new ArrayList<>();
                for (KeyListItem keyItem : displaySource) {
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
            final List<AccountStatsAdapter.DisplayItem> fItems = placeholderItems;
            final List<KeyListItem> fNewItems = newItems;
            runOnUiThread(() -> {
                // 清单在主线程整体替换：后台线程 clear/add 会让 UI 线程读到半成品（并发修改集合）
                keyListItemList.clear();
                keyListItemList.addAll(fNewItems);
                // 清单换新：在途的旧统计作废，按钮重新进入“等统计回填”状态
                statsSeq.incrementAndGet();
                statsPhase = STATS_IDLE;
                // 本地清单已就绪（含“空清单/读失败”）；链上统计另由 statsPhase 单独门控
                accountDataReady = true;
                applyButtonEnabledState();
                if (onLoaded != null) onLoaded.onReady(fItems);
            });
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
            String ungroupedName = getString(R.string.common_ungrouped);
            displayItems.add(AccountStatsAdapter.DisplayItem.header(ungroupedName));
            for (AccountStatsAdapter.AccountStatsItem ai : ungrouped) {
                displayItems.add(AccountStatsAdapter.DisplayItem.account(ungroupedName, ai));
            }
        }
        return displayItems;
    }

    private void loadStatsOverview() {
        final int seq = statsSeq.incrementAndGet();
        final List<KeyListItem> enabledList = getDisplayKeyItems();
        // 整列表同步的唯一信号：顶部细进度条（行上不写任何进度）；
        // onBatchResult 等回调不保证在主线程，这里统一回到 UI 线程操作 View
        runOnUiThread(() -> showSyncBar(true));
        if (enabledList.isEmpty()) {
            // 没有可查账号：不存在“数据没查完”，直接放行到点击时的“无启用密钥”提示
            statsPhase = STATS_READY;
            runOnUiThread(() -> showSyncBar(false));
            applyButtonEnabledState();
            return;
        }

        // 行结构已由 applyLocalDefaults 建好（pending 行），这里只做数值回填：
        // 分批到达就分批刷新，不再等全部地址查完再整表替换
        final List<AccountStatsAdapter.AccountStatsItem> rows = accountStatsAdapter.accountItems();
        // 从未加载过的行先把今日冠军置为未知，免得二波回填前被当成真实的 0 计入汇总
        for (AccountStatsAdapter.AccountStatsItem it : rows) {
            if (it.pending) it.todayChampion = -1;
        }

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
                final int total = addrs.size();

                // 同一地址可能对应多行（重复导入），回填时逐行都要写
                Map<String, List<AccountStatsAdapter.AccountStatsItem>> byAddr =
                        new java.util.HashMap<>();
                for (AccountStatsAdapter.AccountStatsItem it : rows) {
                    if (it.address == null || it.address.isEmpty()) continue;
                    List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(it.address);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        byAddr.put(it.address, bucket);
                    }
                    bucket.add(it);
                }

                int done = 0, unknownTotal = 0;

                // 没有可查地址（行没有地址）：等同于无需同步，必须显式置 READY，
                // 否则门控会一直停在 IDLE，按钮永远不可点
                if (total == 0) {
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        statsPhase = STATS_READY;
                        showSyncBar(false);
                        applyButtonEnabledState();
                        refreshSummary(rows, 0);
                    });
                    return;
                }

                // 第一波：余额/入场/冠军/收益。分片回填，首片回来就放开按钮，
                // 地址多时用户不必盯着“同步中”等到底
                for (int start = 0; start < total; start += ArenaConfig.STATS_CHUNK_SIZE) {
                    int end = Math.min(start + ArenaConfig.STATS_CHUNK_SIZE, total);
                    final List<String> slice = new ArrayList<>(addrs.subList(start, end));
                    final Map<String, ArenaContractApi.PlayerSummary> part =
                            ArenaContractApi.callPlayerSummaryBatch(web3j, slice);
                    done += slice.size();
                    for (String addr : slice) {
                        if (!part.containsKey(addr)) unknownTotal++;
                    }

                    final int fDone = done;
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;   // 已被更新的请求取代
                        applySummaryChunk(byAddr, slice, part);
                        // 首片到达就放开按钮：后面的分片继续在后台补齐
                        if (statsPhase != STATS_READY) {
                            statsPhase = STATS_READY;
                            applyButtonEnabledState();
                        }
                        accountStatsAdapter.notifyStatsChanged();
                        refreshSummary(rows, total);
                        updateSyncProgress(fDone, total * 2);
                    });
                }

                // 一个地址都没拿到：等同于“查不到”，降级为失败态，不再跑第二波
                if (total > 0 && unknownTotal == total) {
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        statsPhase = STATS_FAILED;
                        showStatsUnavailable();
                        applyButtonEnabledState();
                        appendUiLog(null, "链上未返回任何账号数据，请点击汇总栏重试");
                    });
                    return;
                }

                // 第二波：今日冠军（每地址一次 eth_call，最重），只刷新该列，不影响按钮
                int doneToday = 0, todayMiss = 0;
                for (int start = 0; start < total; start += ArenaConfig.STATS_CHUNK_SIZE) {
                    int end = Math.min(start + ArenaConfig.STATS_CHUNK_SIZE, total);
                    final List<String> slice = new ArrayList<>(addrs.subList(start, end));
                    final Map<String, Integer> winMap =
                            ArenaContractApi.callPlayerHistoryLatestWendTodayBatch(web3j, slice);
                    for (String a : slice) {
                        if (winMap.get(a) == null) todayMiss++;
                    }
                    doneToday += slice.size();
                    final int fDoneToday = doneToday;
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        for (String addr : slice) {
                            List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(addr);
                            if (bucket == null) continue;
                            Integer win = winMap.get(addr);
                            for (AccountStatsAdapter.AccountStatsItem it : bucket) {
                                it.todayChampion = win != null ? win : -1;
                            }
                        }
                        accountStatsAdapter.notifyStatsChanged();
                        refreshSummary(rows, total);
                        updateSyncProgress(total + fDoneToday, total * 2);
                    });
                }

                final int fUnknown = unknownTotal, fTodayMiss = todayMiss;
                runOnUiThread(() -> {
                    if (seq != statsSeq.get()) return;
                    refreshSummary(rows, total);
                    // 两波都结束：进度条退场，界面回到静止态
                    showSyncBar(false);
                    if (fUnknown > 0) {
                        appendUiLog(null, "有 " + fUnknown + " 个账号链上数据未返回，已标记为未查到");
                    }
                    if (fTodayMiss > 0) {
                        appendUiLog(null, "有 " + fTodayMiss + " 个账号今日冠军未查到，该列不计入合计");
                    }
                });

            } catch (Exception e) {
                Log.e("StatsOverview", "加载失败", e);
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    if (seq != statsSeq.get()) return;
                    showSyncBar(false);
                    appendUiLog(null, "加载统计失败：" + msg);
                    // 已有成功结果时（手动/任务后刷新失败）保留旧数据，不打断操作；
                    // 首轮就失败则保持禁用，汇总栏给出重试入口
                    if (statsPhase != STATS_READY) {
                        statsPhase = STATS_FAILED;
                        showStatsUnavailable();
                    }
                    applyButtonEnabledState();
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    // 把一片 playerSummary 写回对应行（主线程调用）
    private void applySummaryChunk(
            Map<String, List<AccountStatsAdapter.AccountStatsItem>> byAddr,
            List<String> slice,
            Map<String, ArenaContractApi.PlayerSummary> part) {
        for (String addr : slice) {
            List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(addr);
            if (bucket == null) continue;
            ArenaContractApi.PlayerSummary s = part.get(addr);
            for (AccountStatsAdapter.AccountStatsItem it : bucket) {
                it.pending = false;
                if (s == null) {
                    // 本片没返回这个地址：标未查到，保留行，不清掉之前的展示位置
                    it.unknown = true;
                    it.todayChampion = -1;
                } else {
                    it.unknown = false;
                    it.balance = s.walletBalance;
                    it.entriesToday = s.entriesToday.intValue();
                    it.entriesRemaining = s.entriesRemainingToday.intValue();
                    it.totalChampion = s.championships.intValue();
                    it.prize = s.prize;
                    it.totalEarnings = calcNetEarnings(s);
                }
            }
        }
    }

    // 按当前已回填的行重算汇总栏；同步进度由顶部细进度条表达，这里只出数字
    private void refreshSummary(List<AccountStatsAdapter.AccountStatsItem> rows, int total) {
        int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0, knownToday = 0;
        BigInteger sumEarnings = BigInteger.ZERO;
        for (AccountStatsAdapter.AccountStatsItem it : rows) {
            if (it.pending || it.unknown) continue;
            sumEnter += it.entriesToday;
            // todayChampion 为 -1 表示该行今日冠军还没查到：不计入合计，也不能显示成 0
            if (it.todayChampion >= 0) {
                sumTodayWin += it.todayChampion;
                knownToday++;
            }
            sumTotalWin += it.totalChampion;
            if (it.totalEarnings != null) sumEarnings = sumEarnings.add(it.totalEarnings);
        }
        tvSummaryEnterLabel.setText(getString(R.string.summary_enter_label_fmt, total));
        tvSummaryEnter.setText(sumEnter + "/" + (total * 12));
        // 一个今日冠军都没拿到时给占位：写 0 会被误读成“今天都没赢”
        tvSummaryTodayWin.setText((total > 0 && knownToday == 0)
                ? getString(R.string.common_placeholder) : String.valueOf(sumTodayWin));
        tvSummaryTotalWin.setText(String.valueOf(sumTotalWin));
        tvSummaryPrize.setText(formatToken(sumEarnings));
    }

    // 净收益：胜场×100.8 + 冠军×200 - 场次×100（不足 0 记 0）
    private static BigInteger calcNetEarnings(ArenaContractApi.PlayerSummary s) {
        BigInteger battles = s.battles != null ? s.battles : BigInteger.ZERO;
        BigInteger championships = s.championships != null ? s.championships : BigInteger.ZERO;
        BigInteger loserPayout = new BigDecimal("100.8").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger jiaziGrant = new BigDecimal("200").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger entryFee = new BigDecimal("100").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger net = battles.multiply(loserPayout)
                .add(championships.multiply(jiaziGrant))
                .subtract(battles.multiply(entryFee));
        return net.signum() < 0 ? BigInteger.ZERO : net;
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
            // 用户确认充值：密码校验通过后才锁定按钮，取消弹窗不影响按钮
            dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
                setTaskButtonsEnabled(false);
                new Thread(() -> {
                    try {
                        BatchFundTask.executeAutoFund(getEnabledKeyItems(), pwdCharArray, this);
                    } catch (Exception e) {
                        runOnUiThread(() -> appendLog("", "FUND",
                                getString(R.string.fund_query_fail, Web3AndroidUtil.safeMessage(e))));
                    } finally {
                        Arrays.fill(pwdCharArray, (char) 0);
                        runOnUiThread(() -> setTaskButtonsEnabled(true));
                    }
                }).start();
            });
        }
    }

    private static final int REQUEST_BATCH_FUND = 1001;

    // 调试专用探针（领取链路调试完毕，整体注释停用；往后需要时整段取消注释）
    // 固定地址复现领取流程的只读查询链，每步打日志定位崩溃点；全程只读，不涉及私钥与签名

    // 主界面展示/统计口径：调试开关打开时用地址池展开行（与私钥数量无关），否则用真实启用私钥
    private List<KeyListItem> getDisplayKeyItems() {
        List<KeyListItem> debug = debugKeyItems;
        if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && debug != null && !debug.isEmpty()) return debug;
        return getEnabledKeyItems();
    }

    // 全量账号列表（含禁用/主号），供充值预览等观察场景使用
    public List<KeyListItem> getAllKeyItems() {
        return keyListItemList;
    }
}