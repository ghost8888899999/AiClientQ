package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.tabs.TabLayout;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.ArenaConfig;
import com.lgns.model.DailyStatusItem;
import com.lgns.model.FailureLogItem;
import com.lgns.model.KeyListItem;
import com.lgns.batch.BatchFundTask;
import com.lgns.batch.BatchEnterTask;
import com.lgns.batch.BatchClaimTask;
import com.lgns.batch.BatchWorkflowTask;
import com.lgns.ui.adapter.DailyStatusAdapter;
import com.lgns.ui.adapter.FailureLogAdapter;
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
    private TextView tvTokenCount, tvQdCount, tvEnabledCount;
    private LinearLayout btnBatchFund, btnBatchEnter, btnBatchClaim, btnFullWorkflow;
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

    private volatile boolean isTaskRunning = false;
    private int currentTabPosition = 0;

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
                currentTabPosition = tab.getPosition();
                if (tab.getPosition() == 0) {
                    rvDailyStatus.setVisibility(View.VISIBLE);
                    rvFailureList.setVisibility(View.GONE);
                    tvEmptyFailure.setVisibility(View.GONE);
                } else {
                    rvDailyStatus.setVisibility(View.GONE);
                    rvFailureList.setVisibility(View.VISIBLE);
                    tvEmptyFailure.setVisibility(View.GONE);
                }
                loadTodayStatus();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                loadTodayStatus();
            }
        });

        rvDailyStatus.setLayoutManager(new LinearLayoutManager(this));
        dailyStatusAdapter = new DailyStatusAdapter();
        rvDailyStatus.setAdapter(dailyStatusAdapter);

        rvFailureList.setLayoutManager(new LinearLayoutManager(this));
        rvFailureList.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.top = 8;
                outRect.bottom = 8;
            }
        });
        failureLogAdapter = new FailureLogAdapter();
        rvFailureList.setAdapter(failureLogAdapter);

        spAppSetting = getSharedPreferences("app_setting", MODE_PRIVATE);
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
                    refreshCipherKeyList();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "错误：密钥管理器初始化失败:" + e.getMessage()));
            }
        }).start();
    }

    private void bindViews() {
        btnSettingGear = findViewById(R.id.btn_setting_gear);
        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        tvTokenCount = findViewById(R.id.tv_token_count);
        tvQdCount = findViewById(R.id.tv_qd_count);
        tvEnabledCount = findViewById(R.id.tv_enabled_count);
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
                    runOnUiThread(() -> appendLog("", "", "警告：检测到自动清除计时到期，打开 APP 自动清空全部私钥！"));

                    spAppSetting.edit()
                            .putBoolean("auto_clear_enable", false)
                            .putLong("auto_clear_ms", 0)
                            .putLong("auto_clear_start_ts", 0)
                            .apply();
                } catch (Exception ignored) {}
            }).start();
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
        MainDataLoader.refreshCipherKeyList(this, this, keyListItemList,
                tvEnabledCount, tvTokenCount, tvQdCount);
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

    public MainDialogHelper getDialogHelper() { return dialogHelper; }

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
        refreshCipherKeyList();
        loadTodayStatus();
        Log.d("MainActivity", "onResume: loadTodayStatus called");
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
                Log.d("MainActivity", "loadTodayStatus: querying date=" + today);
                AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                List<DailyStatusItem> statusList = db.queryDailyStatus(today);
                List<FailureLogItem> failList = db.queryFailureLog(today);
                Log.d("MainActivity", "loadTodayStatus: statusCount=" + statusList.size() + ", failCount=" + failList.size());
                runOnUiThread(() -> {
                    dailyStatusAdapter.setItemList(statusList);
                    dailyStatusAdapter.notifyDataSetChanged();
                    failureLogAdapter.setItemList(failList);
                    failureLogAdapter.notifyDataSetChanged();

                    tvEmptyStatus.setVisibility(View.GONE);
                    tvEmptyFailure.setVisibility(View.GONE);

                    if (currentTabPosition == 0) {
                        if (statusList.isEmpty()) tvEmptyStatus.setVisibility(View.VISIBLE);
                    } else {
                        if (failList.isEmpty()) tvEmptyFailure.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                Log.e("MainActivity", "loadTodayStatus error", e);
            }
        }).start();
    }
}