package com.ailgnsq.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ailgnsq.R;
import com.ailgnsq.batch.HelixTurbineTask;
import com.ailgnsq.contract.HelixContractApi;
import com.ailgnsq.helper.CryptoKeyPrompt;
import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.manager.KeyStoreManager;
import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.model.KeyListItem;
import com.ailgnsq.service.TaskKeepAliveService;
import com.ailgnsq.task.TaskLogCallback;
import com.ailgnsq.util.Web3AndroidUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * HELIX 每日链路的执行页（本工程里 HELIX 唯一的写入口）。
 *
 * <p>与 {@link HelixActivity}（只读面板）分两页是有意的：只读页可以从齿轮随时点进去核对数值，
 * 这一页每一笔都要花 gas、还要碰私钥，所以入口后面压了三道门——
 * 账号来自上一跳的勾选结果（本页不再自选，免得两处口径不一致）、明确的确认框、解密密码。</p>
 *
 * <p>闸门沿用 {@code AccountDetailActivity} 那套「本地先判、全局后判」：
 * {@link AppGlobal#acquireTask()} 抢到才弹密码框，密码框被取消也必须释放，
 * 否则主界面的四个批量按钮会被永久占住。</p>
 */
public class HelixRunActivity extends BaseActivity implements TaskLogCallback {

    private static final String TAG = "HelixRunActivity";

    /** 日志区最多留多少字符：一轮几百行，全堆在 TextView 上会让 UI 越写越卡 */
    private static final int LOG_MAX_CHARS = 60000;

    private TextView tvTip;
    private TextView tvAccounts;
    private Spinner spFund;
    private View btnStart, btnStop;
    private TextView tvLog;
    private ScrollView svLog;

    /** 本次要处理的账号：由 Intent 带进来的地址 + 密钥库里的密文拼成 */
    private final List<KeyListItem> runItems = new ArrayList<>();
    /** 与 runItems 同序的展示编号 */
    private final List<String> labels = new ArrayList<>();
    /** 下拉框第 0 项是「不补差、不归集」，所以第 i 个账号对应位置 i+1 */
    private final List<String> fundAddrs = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean destroyed;
    /** 只记「本页是否真的抢到了闸门」：释放别人占的锁会把另一条链路放到一半的任务放出去 */
    private volatile boolean gateOwned;

    /** 落库专用单线程：批量一轮要打上百行日志，每行起一个线程纯属浪费 */
    private ExecutorService logWriter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_helix_run);

        tvTip = findViewById(R.id.tv_helix_run_tip);
        tvAccounts = findViewById(R.id.tv_helix_run_accounts);
        spFund = findViewById(R.id.sp_helix_run_fund);
        btnStart = findViewById(R.id.btn_helix_run_start);
        btnStop = findViewById(R.id.btn_helix_run_stop);
        tvLog = findViewById(R.id.tv_helix_run_log);
        svLog = findViewById(R.id.sv_helix_run_log);
        logWriter = Executors.newSingleThreadExecutor();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnStart.setOnClickListener(v -> handleStartClick());
        btnStop.setOnClickListener(v -> handleStopClick());
        findViewById(R.id.btn_helix_run_clear).setOnClickListener(v -> tvLog.setText(""));

        prepareAccounts();
    }

    // region 账号准备

    /**
     * 把 Intent 里的地址换成可签名的 {@link KeyListItem}。
     *
     * <p>地址是上一跳（{@code MainDialogHelper#openHelixRun}）按「已勾选 + 已启用 + 有地址」筛过的，
     * 这里只补一件事：从密钥库查回密文。查不到密文的号直接不进执行列表——
     * 只读面板照样能显示它们（不需要私钥），但这一页签不出名的交易不如一开始就说明白。</p>
     *
     * <p>{@code loadKeyEntryList} 只读 SQLite、不解密，会话过期也能查（与详情页同一口径）。</p>
     */
    private void prepareAccounts() {
        List<String> addrs = getIntent().getStringArrayListExtra(HelixActivity.EXTRA_ADDRS);
        List<String> inLabels = getIntent().getStringArrayListExtra(HelixActivity.EXTRA_LABELS);
        if (addrs == null) addrs = new ArrayList<>();

        Map<String, KeyStoreManager.KeyEntry> byAddr = new HashMap<>();
        try {
            for (KeyStoreManager.KeyEntry e : AppGlobal.getMetaManager().loadKeyEntryList()) {
                if (e.address != null && !e.address.isEmpty()) {
                    byAddr.put(e.address.toLowerCase(Locale.US), e);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "load key entries failed", ex);
            showTip(getString(R.string.helix_run_no_account));
            tvAccounts.setText(getString(R.string.common_placeholder));
            return;
        }

        List<String> missing = new ArrayList<>();
        for (int i = 0; i < addrs.size(); i++) {
            String addr = addrs.get(i);
            KeyStoreManager.KeyEntry e = byAddr.get(addr.toLowerCase(Locale.US));
            if (e == null || e.cipherText == null || e.cipherText.isEmpty() || !e.enabled) {
                missing.add(labelOf(inLabels, i));
                continue;
            }
            KeyListItem item = new KeyListItem();
            item.keyId = e.id;
            item.displayNo = String.format(Locale.getDefault(), "%03d", e.id);
            item.customCode = e.customCode;
            item.groupNo = e.groupNo;
            item.cipherText = e.cipherText;
            item.address = e.address;
            item.enabled = e.enabled;
            item.isUsable = true;
            runItems.add(item);
            labels.add(labelOf(inLabels, i));
        }

        tvAccounts.setText(getString(R.string.helix_run_lbl_accounts) + "：" + runItems.size()
                + " 个" + (runItems.isEmpty() ? "" : "（" + joinFirst(labels, 12) + "）"));
        if (!missing.isEmpty()) {
            showTip(getString(R.string.helix_run_key_missing_fmt, joinFirst(missing, 12)));
        }
        buildFundSpinner();
        if (runItems.isEmpty()) {
            showTip(getString(R.string.helix_run_no_account));
        }
    }

    private static String labelOf(List<String> src, int i) {
        return (src != null && i < src.size() && src.get(i) != null) ? src.get(i) : String.valueOf(i + 1);
    }

    private static String joinFirst(List<String> list, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i >= max) {
                sb.append(" …");
                break;
            }
            if (sb.length() > 0) sb.append("、");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /** 主钱包下拉：第 0 项是「不补差、不归集」，默认选第一个账号——对应脚本里拿首号当资金来源的用法 */
    private void buildFundSpinner() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.helix_run_fund_none));
        fundAddrs.add(null);
        for (int i = 0; i < runItems.size(); i++) {
            KeyListItem it = runItems.get(i);
            items.add(labels.get(i) + " " + HelixContractApi.shortAddr(it.address));
            fundAddrs.add(it.address.toLowerCase(Locale.US));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFund.setAdapter(adapter);
        if (!runItems.isEmpty()) spFund.setSelection(1);
    }

    // endregion

    // region 起跑

    private void handleStartClick() {
        if (running) return;
        if (runItems.isEmpty()) {
            showTip(getString(R.string.helix_run_no_account));
            return;
        }
        if (AppGlobal.isTaskBusy()) {
            showTip(getString(R.string.helix_run_task_busy));
            return;
        }
        final HelixTurbineTask.Options opt = collectOptions();
        String fund = opt.fundAddr == null ? null : HelixContractApi.shortAddr(opt.fundAddr);
        String msg = fund == null
                ? getString(R.string.helix_run_confirm_msg_nofund_fmt, runItems.size())
                : getString(R.string.helix_run_confirm_msg_fmt, runItems.size(), fund);
        if (fund == null && (opt.allowTopup || opt.aggregate)) {
            msg = msg + "\n\n" + getString(R.string.helix_run_no_fund_warn);
        }
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle(R.string.helix_run_confirm_title)
                .setMessage(msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> askPassword(opt))
                .show();
    }

    private HelixTurbineTask.Options collectOptions() {
        HelixTurbineTask.Options opt = new HelixTurbineTask.Options();
        // 三个步骤开关已迁到系统配置页，起跑时从 ArenaConfig 读（loadConfig 已在本页 onCreate 前由启动链路跑过）
        ArenaConfig.loadConfig(this);
        opt.allowActivate = ArenaConfig.helixAllowActivate;
        opt.allowTopup = ArenaConfig.helixAllowTopup;
        opt.aggregate = ArenaConfig.helixAggregate;
        int pos = spFund.getSelectedItemPosition();
        opt.fundAddr = (pos > 0 && pos < fundAddrs.size()) ? fundAddrs.get(pos) : null;
        return opt;
    }

    /**
     * 确认之后才抢闸门、再弹密码框。
     *
     * <p>顺序不能反：先抢锁再弹框，才能保证密码框开着的那段时间里别处起不了任务；
     * {@code onCancel} 必须传，用户直接关掉密码框时不释放就会把整个 App 的写操作锁死。</p>
     */
    private void askPassword(HelixTurbineTask.Options opt) {
        if (!AppGlobal.acquireTask()) {
            showTip(getString(R.string.helix_run_task_busy));
            return;
        }
        gateOwned = true;
        setRunning(true);
        CryptoKeyPrompt.show(this, pwd -> startRun(opt, pwd), this::onPromptCanceled);
    }

    private void onPromptCanceled() {
        releaseGate();
        setRunning(false);
        appendUiLog(getString(R.string.helix_run_pwd_canceled));
    }

    private void startRun(final HelixTurbineTask.Options opt, final char[] pwd) {
        // 与主界面 beginTask 对称：清掉上一次可能残留的停止/取消标记，否则一开跑就自行退出
        Web3AndroidUtil.resetStop();
        Web3AndroidUtil.resetCancel();
        final List<KeyListItem> items = new ArrayList<>(runItems);
        appendUiLog(getString(R.string.helix_run_running_fmt, items.size()));
        // 几十号一轮的链路比擂台还长（每号最多四笔、每笔等回执），熄屏必须保活
        TaskKeepAliveService.begin(this, getString(R.string.notif_task_detail_fmt,
                getString(R.string.common_title_helix_run)));
        new Thread(() -> {
            String err = null;
            try {
                HelixTurbineTask.run(getApplicationContext(), this, items, pwd, opt);
            } catch (Exception e) {
                err = Web3AndroidUtil.safeMessage(e);
            } finally {
                Arrays.fill(pwd, (char) 0);
                TaskKeepAliveService.end(getApplicationContext());
                releaseGate();
            }
            final String fErr = err;
            if (fErr != null) appendUiLog("✖ " + fErr);
            appendUiLog(getString(R.string.helix_run_finish_hint));
            if (destroyed || isFinishing()) return;
            runOnUiThread(() -> setRunning(false));
        }).start();
    }

    /** 唯一的停止出口：置取消标记，当前账号处理完就跳出，已上链的交易不回滚 */
    private void handleStopClick() {
        if (!running) return;
        Web3AndroidUtil.requestCancel();
        appendUiLog(getString(R.string.helix_run_stop_requested));
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
    }

    /** 只释放自己抢到的那把锁 */
    private void releaseGate() {
        if (!gateOwned) return;
        gateOwned = false;
        AppGlobal.releaseTask();
    }

    /** 运行态：锁掉会改变这轮输入的控件（本页只剩主钱包下拉），只留「停止」 */
    private void setRunning(boolean r) {
        running = r;
        btnStart.setVisibility(r ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(r ? View.VISIBLE : View.GONE);
        btnStop.setEnabled(true);
        btnStop.setAlpha(1f);
        spFund.setEnabled(!r);
    }

    // endregion

    // region 日志上屏与落库

    @Override
    public void onLog(String keyNo, String type, String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        final long timeMs = System.currentTimeMillis();
        final String text = msg;
        // 上屏只在本页还活着时做；落库不管页面在不在——人走了，交易结果还得留个凭证
        if (!destroyed) appendUiLog(safeKeyNo == null ? text : "[" + safeKeyNo + "] " + text);
        try {
            logWriter.execute(() -> AppLogDbHelper.getInstance(getApplicationContext())
                    .insertLog(safeKeyNo, type, text, timeMs));
        } catch (RejectedExecutionException e) {
            // onDestroy 已经关队：本行仍在任务线程上，就地直写——落库用的是 Application Context，
            // 页面已销毁也不影响，不能因为划掉了页就把刚上链的那笔凭证丢了
            AppLogDbHelper.getInstance(getApplicationContext())
                    .insertLog(safeKeyNo, type, text, timeMs);
        }
    }

    private void appendUiLog(String line) {
        if (destroyed || line == null) return;
        final String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        runOnUiThread(() -> {
            if (destroyed) return;
            CharSequence cur = tvLog.getText();
            StringBuilder sb = new StringBuilder();
            if (cur.length() > LOG_MAX_CHARS) {
                sb.append(cur, cur.length() - LOG_MAX_CHARS, cur.length());
                // 截断点多半落在某一行的中间：把那一截丢掉，开头看着才不像乱码
                int nl = sb.indexOf("\n");
                if (nl >= 0 && nl + 1 < sb.length()) sb.delete(0, nl + 1);
            } else {
                sb.append(cur);
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append(stamp).append(' ').append(line);
            tvLog.setText(sb.toString());
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** 顶部的红条：只在「这一轮根本跑不了」时出现，跑过程中的信息一律走日志区 */
    private void showTip(String text) {
        tvTip.setText(text);
        tvTip.setVisibility(View.VISIBLE);
    }

    // endregion

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (logWriter != null) {
            // 只停止接收新任务，已在排队的落库照常写完；再往后的行走 onLog 里的兜底分支
            logWriter.shutdown();
        }
        // 任务还在跑就不放闸门：取消标记只是「当前账号处理完就停」，线程可能还要签几笔，
        // 这时候把锁交出去等于允许另一个入口同时签名抢 nonce。收尾交给任务线程的 finally
        if (running) {
            Web3AndroidUtil.requestCancel();
        } else {
            releaseGate();
        }
        // 任务还在跑时不动通知：线程收尾会调 end()，中途划掉页面只会让通知多挂几分钟
        super.onDestroy();
    }
}
