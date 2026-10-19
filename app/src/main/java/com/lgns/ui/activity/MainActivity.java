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
import com.lgns.task.ArenaTask;
import com.lgns.service.AutoFundService;
import com.lgns.ui.adapter.DailyStatusAdapter;
import com.lgns.ui.adapter.FailureLogAdapter;
import com.lgns.manager.KeyStoreManager;
import com.lgns.helper.MainDialogHelper;
import com.lgns.helper.MainMenuHelper;
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

public class MainActivity extends AppCompatActivity implements TaskLogCallback {
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
                    long masterId = AppGlobal.getKeyStoreManager().getMasterAccountId();
                    if (masterId > 0) {
                        appendLog("", "FUND", "检测到主号，先执行批量充值...");
                        AutoFundService.executeAutoFund(enabledList, pwdCharArray, this);
                    }
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


    public void showBatchFundDialog() {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                long masterId = mgr.getMasterAccountId();
                if (masterId < 0) {
                    runOnUiThread(() -> dialogHelper.styledDialog()
                            .setTitle("未设置主号")
                            .setMessage("请先在私钥管理中编辑某个私钥，勾选「设为主号」")
                            .setPositiveButton("知道了", null)
                            .show());
                    return;
                }

                KeyStoreManager.KeyEntry masterEntry = null;
                List<KeyStoreManager.KeyEntry> allEntries = mgr.loadKeyEntryList();
                for (KeyStoreManager.KeyEntry e : allEntries) {
                    if (e.id == masterId) { masterEntry = e; break; }
                }
                if (masterEntry == null || masterEntry.address == null) {
                    runOnUiThread(() -> dialogHelper.styledDialog()
                            .setMessage("主号信息异常，请重新设置")
                            .setPositiveButton("知道了", null)
                            .show());
                    return;
                }

                final KeyStoreManager.KeyEntry fMasterEntry = masterEntry;

                Web3j web3j = Web3AndroidUtil.dialAny();
                try {
                    BigInteger masterBalance = ArenaContractApi.callErc20Balance(web3j, masterEntry.address);
                    BigDecimal masterBalF = new BigDecimal(masterBalance)
                            .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);

                    List<String> needFundAddrs = new ArrayList<>();
                    List<String> needFundLabels = new ArrayList<>();
                    BigInteger totalNeeded = BigInteger.ZERO;

                    for (KeyListItem item : getEnabledKeyItems()) {
                        if (item.address == null || item.address.equalsIgnoreCase(masterEntry.address)) continue;
                        BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                        if (bal.compareTo(ArenaConfig.ENTRY_FEE) < 0) {
                            BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                            totalNeeded = totalNeeded.add(diff);
                            String no = (item.customCode != null && !item.customCode.isEmpty())
                                    ? item.customCode : item.displayNo;
                            BigDecimal balF = new BigDecimal(bal)
                                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
                            needFundAddrs.add(item.address);
                            needFundLabels.add(no + " (余额:" + balF + ")");
                        }
                    }

                    final List<String> fAddrs = needFundAddrs;
                    final List<String> fLabels = needFundLabels;
                    final BigInteger fTotal = totalNeeded;
                    final String fMasterAddr = masterEntry.address;
                    final BigDecimal fMasterBal = masterBalF;
                    final BigDecimal fTotalF = new BigDecimal(fTotal)
                            .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);

                    runOnUiThread(() -> {
                        if (fAddrs.isEmpty()) {
                            dialogHelper.styledDialog()
                                    .setTitle("批量充值")
                                    .setMessage("所有账号余额均 ≥ 100，无需充值")
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("主号: ").append(fMasterAddr.substring(0, 10)).append("...\n");
                        sb.append("主号余额: ").append(fMasterBal).append(" fLGNS\n\n");
                        sb.append("以下账号需要补齐到 100：\n");
                        for (int i = 0; i < fLabels.size(); i++) {
                            sb.append("  ").append(i + 1).append(". ").append(fLabels.get(i)).append("\n");
                        }
                        sb.append("\n共 ").append(fAddrs.size()).append(" 个账号，需转入合计 ")
                                .append(fTotalF).append(" fLGNS");

                        dialogHelper.styledDialog()
                                .setTitle("批量充值确认")
                                .setMessage(sb.toString())
                                .setPositiveButton("确认充值", (d, w) -> {
                                    dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                                        try {
                                            KeyStoreManager ksMgr = AppGlobal.getKeyStoreManager();
                                            String masterPk = ksMgr.decryptCipherText(fMasterEntry.cipherText, pwdCharArray);
                                            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
                                            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair);

                                            Web3j w3 = Web3AndroidUtil.dialAny();
                                            BigInteger gasPrice = w3.ethGasPrice().send().getGasPrice();
                                            BigInteger nonce = w3.ethGetTransactionCount(masterAddr,
                                                    DefaultBlockParameterName.PENDING).send().getTransactionCount();

                                            for (int i = 0; i < fAddrs.size(); i++) {
                                                if (Web3AndroidUtil.isCancelRequested()) break;
                                                String addr = fAddrs.get(i);
                                                final int index = i;
                                                BigInteger bal = ArenaContractApi.callErc20Balance(w3, addr);
                                                if (bal.compareTo(ArenaConfig.ENTRY_FEE) >= 0) {
                                                    final String label = fLabels.get(index);
                                                    runOnUiThread(() -> appendLog("", "FUND",
                                                            "➖ " + label + " 余额已够,跳过"));
                                                    continue;
                                                }
                                                BigInteger diff = ArenaConfig.ENTRY_FEE.subtract(bal);
                                                try {
                                                    ArenaContractApi.doErc20Transfer(w3, masterKeyPair,
                                                            addr, diff, nonce, gasPrice);
                                                    nonce = nonce.add(BigInteger.ONE);
                                                    final String label = fLabels.get(index);
                                                    final BigDecimal amount = new BigDecimal(diff).divide(
                                                            new BigDecimal(BigInteger.TEN.pow(18)),
                                                            2, java.math.RoundingMode.HALF_UP);
                                                    runOnUiThread(() -> appendLog("", "FUND",
                                                            "✅ " + label + " 转入 " + amount + " fLGNS"));
                                                } catch (Exception e) {
                                                    final String label = fLabels.get(index);
                                                    runOnUiThread(() -> appendLog("", "FUND",
                                                            "❌ " + label + " 转账失败: " +
                                                                    Web3AndroidUtil.safeMessage(e)));
                                                }
                                            }
                                            runOnUiThread(() -> appendLog("", "FUND",
                                                    "--------批量充值结束--------"));
                                        } catch (Exception e) {
                                            runOnUiThread(() -> appendLog("", "FUND",
                                                    "❌ 批量充值失败: " + Web3AndroidUtil.safeMessage(e)));
                                        } finally {
                                            Arrays.fill(pwdCharArray, (char) 0);
                                        }
                                    }).start());
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    });
                } finally {
                    web3j.shutdown();
                }
            } catch (Exception e) {
                runOnUiThread(() -> dialogHelper.styledDialog()
                        .setMessage("查询失败: " + Web3AndroidUtil.safeMessage(e))
                        .setPositiveButton("知道了", null)
                        .show());
            }
        }).start();
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

    public void appendLog(String unusedKeyNo, String unusedType, String msg) {
        onLog(unusedKeyNo, unusedType, msg);
    }

    public interface PasswordCallback {
        void onInputPwd(char[] pwd);
    }

    public MainDialogHelper getDialogHelper() { return dialogHelper; }

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
        android.util.Log.d("MainActivity", "onResume: loadTodayStatus called");
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
                android.util.Log.d("MainActivity", "loadTodayStatus: querying date=" + today);
                AppLogDbHelper db = AppLogDbHelper.getInstance(this);
                List<DailyStatusItem> statusList = db.queryDailyStatus(today);
                List<FailureLogItem> failList = db.queryFailureLog(today);
                android.util.Log.d("MainActivity", "loadTodayStatus: statusCount=" + statusList.size() + ", failCount=" + failList.size());
                runOnUiThread(() -> {
                    dailyStatusAdapter.setItemList(statusList);
                    dailyStatusAdapter.notifyDataSetChanged();
                    tvEmptyStatus.setVisibility(statusList.isEmpty() ? View.VISIBLE : View.GONE);

                    failureLogAdapter.setItemList(failList);
                    failureLogAdapter.notifyDataSetChanged();
                    tvEmptyFailure.setVisibility(failList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "loadTodayStatus error", e);
            }
        }).start();
    }
}