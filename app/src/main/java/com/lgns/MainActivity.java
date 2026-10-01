package com.lgns;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.util.SparseBooleanArray;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements TaskLogCallback, SharedPreferences.OnSharedPreferenceChangeListener {
    private EditText etPrivateKey;
    private TextView tvLog;
    private TextView tvCountInfo;
    private ListView lvCipherKeys;
    private ArrayAdapter<String> cipherAdapter;

    private Button btnSecurityWarn;
    private EditText etAutoClearVal;
    private Spinner spTimeUnit;
    private Button btnSetAutoClear;
    private Button btnSysScheduleSetting;
    private TextView tvAutoClearStatus;
    private TextView tvServiceStatus;

    private long mAutoClearMilli = 0;
    private SharedPreferences sp;
    private final String[] unitArray = {"分钟","小时","天"};
    private SharedPreferences.OnSharedPreferenceChangeListener mSpListener;

    // workManager任务唯一名称
    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    // WorkManager系统强制最小周期：15分钟
    private static final long MIN_ALLOW_MINUTE = 15;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        sp = getSharedPreferences("app_setting",MODE_PRIVATE);

        etPrivateKey = findViewById(R.id.et_private_key);
        tvLog = findViewById(R.id.tv_log);
        tvCountInfo = findViewById(R.id.tv_count_info);
        lvCipherKeys = findViewById(R.id.lv_cipher_keys);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvServiceStatus.setText("后台：WorkManager系统调度（最小15分钟，受电池策略影响）");

        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        btnSetAutoClear = findViewById(R.id.btn_set_auto_clear);
        btnSysScheduleSetting = findViewById(R.id.btn_sys_schedule_setting);
        tvAutoClearStatus = findViewById(R.id.tv_auto_clear_status);

        Button btnAddKey = findViewById(R.id.btn_add_key);
        Button btnRefreshList = findViewById(R.id.btn_refresh_list);
        Button btnRemoveSelected = findViewById(R.id.btn_remove_selected);
        Button btnClearAll = findViewById(R.id.btn_clear_all);
        Button btnBatchEnter = findViewById(R.id.btn_batch_enter);
        Button btnBatchClaim = findViewById(R.id.btn_batch_claim);
        Button btnFullWorkflow = findViewById(R.id.btn_full_workflow);
        Button btnResetTask = findViewById(R.id.btn_reset_task);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,unitArray);
        spTimeUnit.setAdapter(unitAdapter);

        // APP打开兜底校验：如果计时已经超时，直接清空密钥
        long saveTotalMs = sp.getLong("auto_clear_ms",0);
        long saveStartTs = sp.getLong("auto_clear_start_ts",0);
        if(saveTotalMs >0 && saveStartTs>0){
            long now = System.currentTimeMillis();
            if(now - saveStartTs >= saveTotalMs){
                new Thread(()->{
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        mgr.clearAllKeys();
                        runOnUiThread(()->{
                            appendLog("⚠检测到上次会话计时已超时，打开APP自动清空全部私钥！");
                            refreshCipherKeyList();
                        });
                    }catch (Exception ignored){}
                }).start();
                sp.edit().putLong("auto_clear_ms",0).putLong("auto_clear_start_ts",0).apply();
                mAutoClearMilli =0;
                // 取消work任务
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
            }else{
                mAutoClearMilli = saveTotalMs - (now - saveStartTs);
            }
        }else{
            mAutoClearMilli = saveTotalMs;
        }
        refreshAutoClearUi();

        cipherAdapter = new ArrayAdapter<String>(this, R.layout.item_cipher_key) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                CheckedTextView tv = (CheckedTextView) v;
                String full = getItem(position);
                if(full != null && full.length() > 32){
                    tv.setText(full.substring(0,32)+"...");
                }else{
                    tv.setText(full);
                }
                return v;
            }
        };
        lvCipherKeys.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        lvCipherKeys.setAdapter(cipherAdapter);

        new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    appendLog("✅密钥管理器初始化完成（AndroidKeyStore持久存储）");
                    refreshCipherKeyList();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("❌初始化失败:" + e.getMessage()));
                e.printStackTrace();
            }
        }).start();

        btnSecurityWarn.setOnClickListener(v->{
            String warnText = "【安全风险提醒】\n" +
                    "1.本APP只是普通工具，不等同硬件钱包。\n" +
                    "2.手机丢失：如果锁屏密码被破解，私钥存在泄露风险。\n" +
                    "3.Root手机禁止存放真实资产私钥。\n" +
                    "4.已关闭系统备份；但请关闭厂商云整机备份。\n" +
                    "5.AndroidKeyStore防护离线密文破解，不能防护解锁后的手机。\n" +
                    "6.重要私钥建议纸钱包离线备份，不要只依赖本APP存储。\n" +
                    "7.⚠自动清除使用系统WorkManager调度，最小设置15分钟，受省电策略影响，时间会有浮动，不能精确计时。\n" +
                    "8.⚠进程被杀不会实时计时；Worker唤醒后校验时间戳；打开APP会执行兜底超时清除。\n" +
                    "9.⚠批量任务：直接点任务按钮默认延续上次进度；如需全部账号从头跑，请先点【强制重置任务进度】。\n" +
                    "10.⚠清空全部只是删除磁盘密文；安全收尾建议：清空后强制停止应用消除内存明文残留。\n" +
                    "【WorkManager自动清除注意事项】\n" +
                    "· 系统最小间隔15分钟，受手机省电策略影响，时间会有浮动，不能做到精确计时。\n" +
                    "· 请在手机系统设置为本APP：开启【允许后台活动】、关闭电池省电限制，多任务界面下拉APP卡片加锁。\n" +
                    "· 如果系统深度休眠，任务可能延迟；打开APP会执行兜底超时清除。\n" +
                    "· 重要场景建议优先手动点【清空全部】，不要完全依赖自动清除。";
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("安全风险⚠重要")
                    .setMessage(warnText)
                    .setPositiveButton("知道了",null)
                    .show();
        });

        // 系统调度设置按钮
        btnSysScheduleSetting.setOnClickListener(v -> {
            String helpText = "【WorkManager系统调度设置指引】\n" +
                    "⚠注意：APP无法自动开启后台权限，全部需要用户手动在系统设置操作。\n\n" +
                    "【小米 / Redmi】\n" +
                    "1.进入本应用信息 → 电池 → 设置为【无限制】\n" +
                    "2.打开自启动；多任务下拉APP卡片加小锁锁定后台\n\n" +
                    "【华为 / 荣耀】\n" +
                    "1.应用启动管理 → 关闭自动管理\n" +
                    "2.开启允许自启动、允许后台活动\n" +
                    "3.电池优化设置为本应用【不允许】\n\n" +
                    "【OPPO / realme】\n" +
                    "1.应用信息→电池→关闭智能耗电限制\n" +
                    "2.开启允许后台活动、开启自启动\n\n" +
                    "【vivo / iQOO】\n" +
                    "1.后台耗电管理，打开【允许后台高耗电】\n" +
                    "2.开启自启动\n\n" +
                    "【全部机型通用】\n" +
                    "①关闭手机全局省电/低电量模式\n" +
                    "②多任务界面下拉APP卡片加锁\n" +
                    "③WorkManager最小周期15分钟，省电模式会推迟执行\n" +
                    "④兜底保障：打开APP会自动校验并执行超时清除\n\n" +
                    "点击【跳转应用信息】进入本APP系统设置页面，手动调整电池与后台权限。";

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("系统调度后台设置")
                    .setMessage(helpText)
                    .setPositiveButton("跳转应用信息", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        });

        //强制重置任务进度，增加二次确认弹窗
        btnResetTask.setOnClickListener(v->{
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("确认强制重置任务进度")
                    .setMessage("⚠说明：\n" +
                            "• 正常继续任务：直接点击【批量入场/批量领取】，默认自动延续进度，跳过已完成账号，不需要点此按钮。\n" +
                            "• 本操作会清除全部账号本地任务状态记录，下次任务所有账号强制从头开始执行。\n" +
                            "• 适合：开启新一轮任务、本地状态脏数据修复。\n\n确定要重置吗？")
                    .setPositiveButton("确认重置", (dialog, which) -> {
                        new Thread(()->{
                            ArenaTask.resetAllTaskProgress(getApplicationContext());
                            runOnUiThread(()->appendLog("✅已强制重置全部批量任务进度，下次任务从头开始"));
                        }).start();
                    })
                    .setNegativeButton("取消",null)
                    .show();
        });

        btnSetAutoClear.setOnClickListener(v->{
            String valStr = etAutoClearVal.getText().toString().trim();
            if(valStr.isEmpty()){
                //关闭自动清除，取消work任务
                sp.edit()
                        .putLong("auto_clear_ms",0)
                        .putLong("auto_clear_start_ts",0L)
                        .apply();
                mAutoClearMilli =0;
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
                appendLog("✅已关闭自动清除，取消系统定时任务");
                refreshAutoClearUi();
                return;
            }

            int val;
            try{
                val = Integer.parseInt(valStr);
            }catch (Exception e){
                appendLog("⚠数值输入错误，关闭自动清除");
                sp.edit()
                        .putLong("auto_clear_ms",0)
                        .putLong("auto_clear_start_ts",0L)
                        .apply();
                mAutoClearMilli=0;
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
                refreshAutoClearUi();
                return;
            }
            int unitPos = spTimeUnit.getSelectedItemPosition();
            long oneUnitMs;
            long inputMinute;
            if(unitPos ==0){
                oneUnitMs = 60*1000L;
                inputMinute = val;
            }else if(unitPos ==1){
                oneUnitMs = 60*60*1000L;
                inputMinute = val * 60L;
            }else{
                oneUnitMs = 24*60*60*1000L;
                inputMinute = val * 24*60L;
            }

            // =========WorkManager强制最小15分钟校验=========
            if(inputMinute < MIN_ALLOW_MINUTE){
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("输入限制")
                        .setMessage("系统WorkManager最小周期为15分钟，请设置≥15分钟！")
                        .setPositiveButton("确定",null)
                        .show();
                return;
            }

            mAutoClearMilli = val * oneUnitMs;
            sp.edit()
                    .putLong("auto_clear_ms",mAutoClearMilli)
                    .putLong("auto_clear_start_ts", System.currentTimeMillis())
                    .apply();

            // 启动WorkManager周期任务，最小15min
            long periodMin = Math.max(inputMinute, MIN_ALLOW_MINUTE);
            PeriodicWorkRequest workReq = new PeriodicWorkRequest.Builder(
                    AutoClearWorker.class,
                    periodMin, TimeUnit.MINUTES
            ).build();

            WorkManager.getInstance(getApplicationContext())
                    .enqueueUniquePeriodicWork(WORK_AUTO_CLEAR,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            workReq);

            refreshAutoClearUi();
            appendLog("✅自动清除设置完成："+ getAutoClearDesc() +" 系统调度已启动");
        });

        btnAddKey.setOnClickListener(v -> {
            String pk = etPrivateKey.getText().toString().trim();
            if (pk.isEmpty()) {
                appendLog("⚠️请输入私钥");
                return;
            }
            new Thread(() -> {
                try {
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.addPrivateKey(pk);
                    runOnUiThread(() -> {
                        appendLog("✅私钥添加成功，已加密存入");
                        etPrivateKey.setText("");
                        refreshCipherKeyList();
                    });
                } catch (IllegalStateException ie) {
                    runOnUiThread(() -> appendLog("❌密钥管理器尚未初始化完成，请等待"));
                } catch (Exception ex) {
                    String msg = ex.getMessage();
                    if ("DUPLICATE_KEY".equals(msg)) {
                        runOnUiThread(() -> {
                            appendLog("⚠️该私钥已经存在，跳过添加");
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("重复私钥")
                                    .setMessage("当前私钥已经存在于列表中，请勿重复添加！")
                                    .setPositiveButton("确定", null)
                                    .show();
                        });
                    } else {
                        runOnUiThread(() -> appendLog("❌添加私钥异常:" + ex.getMessage()));
                    }
                    ex.printStackTrace();
                }
            }).start();
        });

        btnRefreshList.setOnClickListener(v-> refreshCipherKeyList());

        btnRemoveSelected.setOnClickListener(v->{
            SparseBooleanArray checkedArray = lvCipherKeys.getCheckedItemPositions();
            final List<Integer> deleteIndexList = new ArrayList<>();
            for(int i=0; i< checkedArray.size();i++){
                int pos = checkedArray.keyAt(i);
                if(checkedArray.get(pos)){
                    deleteIndexList.add(pos);
                }
            }
            if(deleteIndexList.isEmpty()){
                appendLog("⚠️请在列表勾选需要删除的条目");
                return;
            }
            new Thread(()->{
                try{
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.removeByIndexList(deleteIndexList);
                    runOnUiThread(()->{
                        appendLog("✅已删除选中 "+deleteIndexList.size()+" 条密钥");
                        lvCipherKeys.clearChoices();
                        refreshCipherKeyList();
                    });
                }catch (Exception e){
                    runOnUiThread(()->appendLog("❌批量删除异常:"+e.getMessage()));
                }
            }).start();
        });

        btnClearAll.setOnClickListener(v->{
            new Thread(()->{
                try{
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.clearAllKeys();
                    runOnUiThread(()->{
                        appendLog("✅全部密钥已清空");
                        lvCipherKeys.clearChoices();
                        refreshCipherKeyList();
                    });
                }catch (Exception e){
                    runOnUiThread(()->appendLog("❌清空异常:"+e.getMessage()));
                }
            }).start();
        });

        // ==========任务按钮：调用断点续跑Resume版本 ==========
        btnBatchEnter.setOnClickListener(v -> new Thread(() -> {
            try {
                appendLog("\n--------开始批量入场【断点续跑版】--------");
                appendLog("💡提示：直接执行延续历史进度；如需全部账号从头跑，请先点击【强制重置任务进度】");
                ArenaTask.batchEnterAllResume(getApplicationContext(), MainActivity.this);
                appendLog("--------批量入场任务执行结束--------");
            } catch (IllegalStateException ie) {
                runOnUiThread(() -> appendLog("❌密钥管理器未初始化"));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("❌批量入场异常：" + e.getMessage()));
                e.printStackTrace();
            }
        }).start());

        btnBatchClaim.setOnClickListener(v -> new Thread(() -> {
            try {
                appendLog("\n--------开始批量领取奖励【断点续跑版】--------");
                appendLog("💡提示：直接执行延续历史进度；如需全部账号从头跑，请先点击【强制重置任务进度】");
                ArenaTask.batchClaimAllResume(getApplicationContext(), MainActivity.this,2);
                appendLog("--------批量领取任务执行结束--------");
            } catch (IllegalStateException ie) {
                runOnUiThread(() -> appendLog("❌密钥管理器未初始化"));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("❌批量领取异常：" + e.getMessage()));
                e.printStackTrace();
            }
        }).start());

        btnFullWorkflow.setOnClickListener(v -> new Thread(() -> {
            try {
                appendLog("\n--------开始完整工作流：入场 -> 领取【断点续跑版】--------");
                appendLog("💡提示：直接执行延续历史进度；如需全部账号从头跑，请先点击【强制重置任务进度】");
                ArenaTask.fullArenaWorkflowResume(getApplicationContext(), MainActivity.this,2);
                appendLog("--------完整工作流执行结束--------");
            } catch (IllegalStateException ie) {
                runOnUiThread(() -> appendLog("❌密钥管理器未初始化"));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("❌完整工作流异常：" + e.getMessage()));
                e.printStackTrace();
            }
        }).start());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSpListener = this;
        sp.registerOnSharedPreferenceChangeListener(mSpListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sp.unregisterOnSharedPreferenceChangeListener(mSpListener);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if("key_auto_cleared".equals(key)){
            boolean isAutoCleared = sharedPreferences.getBoolean("key_auto_cleared",false);
            if(isAutoCleared){
                appendLog("⚠【WorkManager调度到期自动清除密钥，刷新界面】");
                refreshCipherKeyList();
                sp.edit().putBoolean("key_auto_cleared",false).apply();
            }
        }
    }

    private String getAutoClearDesc(){
        if(mAutoClearMilli <=0){
            return "关闭自动清除(0)";
        }
        if(mAutoClearMilli >= 1000L*60*60*24){
            return "自动清除："+(mAutoClearMilli/(1000L*60*60*24)) + " 天(系统调度，≥15分钟)";
        }else if(mAutoClearMilli >= 1000L*60*60){
            return "自动清除："+(mAutoClearMilli/(1000L*60*60)) + " 小时(系统调度，≥15分钟)";
        }else{
            return "自动清除："+(mAutoClearMilli/(1000L*60)) + " 分钟(系统调度，≥15分钟)";
        }
    }

    private void refreshAutoClearUi(){
        tvAutoClearStatus.setText("当前："+ getAutoClearDesc());
    }

    @Override
    public void onLog(String msg) {
        appendLog(msg);
    }

    private void refreshCipherKeyList(){
        new Thread(()->{
            try{
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<String> cipherList = mgr.loadCipherTextList();
                runOnUiThread(()->{
                    cipherAdapter.clear();
                    cipherAdapter.addAll(cipherList);
                    cipherAdapter.notifyDataSetChanged();
                    lvCipherKeys.clearChoices();
                    tvCountInfo.setText("共 " + cipherList.size() + " 条密钥");
                    appendLog("ℹ️密文列表已刷新，共"+cipherList.size()+"条");
                });
            }catch (Exception e){
                runOnUiThread(()->appendLog("❌读取密文列表异常:"+e.getMessage()));
            }
        }).start();
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            String old = tvLog.getText().toString();
            tvLog.setText(old + "\n" + text);
            ViewParent parent = tvLog.getParent();
            if (parent instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
