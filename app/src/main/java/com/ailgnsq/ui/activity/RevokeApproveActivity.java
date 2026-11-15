package com.ailgnsq.ui.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ailgnsq.R;
import com.ailgnsq.batch.RevokeApproveTask;
import com.ailgnsq.contract.HelixContractApi;
import com.ailgnsq.helper.CryptoKeyPrompt;
import com.ailgnsq.manager.AppGlobal;
import com.ailgnsq.manager.AppLogDbHelper;
import com.ailgnsq.manager.KeyStoreManager;
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
 * 授权巡检与撤销页：把「这些号到底把币划走了多少额度给合约」变成可查、可收的动作。
 *
 * <p>页面结构照 {@link HelixRunActivity}（提示条 + 配置区 + 停止键 + 日志区），闸门与口令流程也照它：
 * {@link AppGlobal#acquireTask()} 抢到才弹密码框，密码框被取消必须释放。这样两套写入口对
 * 「同一时刻只有一条链路在签名」这件事的保证是同一个，不额外增加约定。</p>
 *
 * <p>与 HELIX 那页的两处有意差别：</p>
 * <ul>
 *   <li>配置区直接把选中授权项的<b>币与合约地址</b>摆出来（{@link #refreshTargetDetail()}）：
 *       撤销本身花不了多少钱，真正的风险是改错对象——把无限授权签给一个错 spender 比不撤销更糟；</li>
 *   <li>动作是单选而不是开关：「撤回 0」与「恢复原值」方向相反，用一个开关承载两个反向语义，
 *       很容易在只想核对一遍时误发恢复交易。</li>
 * </ul>
 *
 * <p>重跑是安全的：这一轮做的事是「读 allowance → 与目标值不同才发」，
 * 已经改到位的号只会留下「无需交易」一行，不会重复签。</p>
 */
public class RevokeApproveActivity extends BaseActivity implements TaskLogCallback {

    private static final String TAG = "RevokeApproveActivity";

    /** 日志区最多留多少字符：一轮几百行，全堆在 TextView 上会让 UI 越写越卡 */
    private static final int LOG_MAX_CHARS = 60000;

    private TextView tvTip;
    private TextView tvAccounts;
    private Spinner spTarget;
    private TextView tvTargetDetail;
    private RadioGroup rgAction;
    private RadioButton rbRevoke, rbRestore;
    private View btnStart, btnStop;
    private TextView tvLog;
    private ScrollView svLog;

    /** 本次要处理的账号：由 Intent 带进来的地址 + 密钥库里的密文拼成 */
    private final List<KeyListItem> runItems = new ArrayList<>();
    /** 与 runItems 同序的展示编号 */
    private final List<String> labels = new ArrayList<>();
    /** 下拉第 0 项是「全部巡检」，所以第 i 个预设项对应位置 i+1 */
    private final List<Integer> targetIndexes = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean destroyed;
    /** 只记「本页是否真的抢到了闸门」：释放别人占的锁会把另一条链路放到一半的任务放出去 */
    private volatile boolean gateOwned;

    /** 落库专用单线程：批量一轮要打上百行日志，每行起一个线程纯属浪费 */
    private ExecutorService logWriter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_revoke_approve);

        tvTip = findViewById(R.id.tv_revoke_tip);
        tvAccounts = findViewById(R.id.tv_revoke_accounts);
        spTarget = findViewById(R.id.sp_revoke_target);
        tvTargetDetail = findViewById(R.id.tv_revoke_target_detail);
        rgAction = findViewById(R.id.rg_revoke_action);
        rbRevoke = findViewById(R.id.rb_revoke_to_zero);
        rbRestore = findViewById(R.id.rb_revoke_restore);
        btnStart = findViewById(R.id.btn_revoke_start);
        btnStop = findViewById(R.id.btn_revoke_stop);
        tvLog = findViewById(R.id.tv_revoke_log);
        svLog = findViewById(R.id.sv_revoke_log);
        logWriter = Executors.newSingleThreadExecutor();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnStart.setOnClickListener(v -> handleStartClick());
        btnStop.setOnClickListener(v -> handleStopClick());
        findViewById(R.id.btn_revoke_clear).setOnClickListener(v -> tvLog.setText(""));

        prepareAccounts();
        buildTargetSpinner();
        // 授权项与动作都会改掉下面那几行地址/目标值，两个控件都得刷一次
        spTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refreshTargetDetail();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        rgAction.setOnCheckedChangeListener((group, checkedId) -> refreshTargetDetail());
        refreshTargetDetail();
    }

    // region 账号准备

    /**
     * 把 Intent 里的地址换成可签名的 {@link KeyListItem}。
     *
     * <p>与 {@link HelixRunActivity} 同一口径：地址由上一跳按「已勾选 + 已启用 + 有地址」筛过，
     * 这里只补一件事——从密钥库查回密文。撤销授权要替账号签名，查不到密文的号签不出交易，
     * 所以它进不了执行列表，只能在提示条上说明被跳过了。</p>
     *
     * <p>{@code loadKeyEntryList} 只读 SQLite、不解密，会话过期也能查。</p>
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
            showTip(getString(R.string.revoke_no_account));
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
            showTip(getString(R.string.revoke_key_missing_fmt, joinFirst(missing, 12)));
        }
        if (runItems.isEmpty()) {
            showTip(getString(R.string.revoke_no_account));
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

    // endregion

    // region 授权项与动作

    /** 下拉：第 0 项是全量巡检，默认就停在那里——进来这一页的人多半想知道「还有哪些额度在外面」 */
    private void buildTargetSpinner() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.revoke_target_all, RevokeApproveTask.TARGETS.length));
        targetIndexes.add(RevokeApproveTask.TARGET_ALL);
        for (int i = 0; i < RevokeApproveTask.TARGETS.length; i++) {
            items.add(RevokeApproveTask.TARGETS[i].name);
            targetIndexes.add(i);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTarget.setAdapter(adapter);
    }

    private int selectedTargetIndex() {
        int pos = spTarget.getSelectedItemPosition();
        return (pos > 0 && pos < targetIndexes.size()) ? targetIndexes.get(pos) : RevokeApproveTask.TARGET_ALL;
    }

    /** 当前动作：默认「撤回到 0」，只有选中恢复档才走另一个值 */
    private int selectedMode() {
        return rgAction.getCheckedRadioButtonId() == R.id.rb_revoke_restore
                ? RevokeApproveTask.MODE_RESTORE : RevokeApproveTask.MODE_REVOKE;
    }

    /**
     * 把选中项的币地址、合约地址与本次目标值摆出来，撤销档再补一行影响说明。
     *
     * <p>这一栏是这一页唯一让人能核对「我要动的到底是哪条关系」的地方，
     * 所以宁可占几行高度也不缩成一句概括；全量巡检时每条都列出来。</p>
     */
    private void refreshTargetDetail() {
        if (destroyed) return;
        int mode = selectedMode();
        List<RevokeApproveTask.Target> scope = RevokeApproveTask.scopeOf(selectedTargetIndex());
        StringBuilder sb = new StringBuilder();
        for (RevokeApproveTask.Target t : scope) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('·').append(getString(R.string.revoke_target_line_fmt,
                    t.tokenDesc(),
                    t.spenderName + " " + HelixContractApi.shortAddr(t.spenderAddr),
                    RevokeApproveTask.humanAmt(RevokeApproveTask.wantOf(t, mode), t)));
        }
        // 影响只在单项 + 撤销档时给：全量巡检时几行影响堆起来会把配置区撑爆，确认框里再完整列
        if (scope.size() == 1 && mode == RevokeApproveTask.MODE_REVOKE) {
            sb.append('\n').append(getString(R.string.revoke_impact_fmt, scope.get(0).revokeImpact));
        }
        tvTargetDetail.setText(sb.toString());
    }

    // endregion

    // region 起跑

    private void handleStartClick() {
        if (running) return;
        if (runItems.isEmpty()) {
            showTip(getString(R.string.revoke_no_account));
            return;
        }
        if (AppGlobal.isTaskBusy()) {
            showTip(getString(R.string.revoke_task_busy));
            return;
        }
        final RevokeApproveTask.Options opt = collectOptions();
        String action = opt.mode == RevokeApproveTask.MODE_RESTORE
                ? getString(R.string.revoke_act_restore) : getString(R.string.revoke_act_revoke);
        String msg = getString(R.string.revoke_confirm_msg_fmt, runItems.size(), action,
                RevokeApproveTask.describeScope(opt.targetIndex));
        // 撤销档把影响逐条列进确认框：这是点下去之前最后一次提示「撤回之后哪条功能会停」
        if (opt.mode == RevokeApproveTask.MODE_REVOKE) {
            StringBuilder impacts = new StringBuilder();
            for (RevokeApproveTask.Target t : RevokeApproveTask.scopeOf(opt.targetIndex)) {
                if (impacts.length() > 0) impacts.append('\n');
                impacts.append(getString(R.string.revoke_impact_fmt, t.revokeImpact));
            }
            if (impacts.length() > 0) msg = msg + "\n\n" + impacts;
        }
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle(R.string.revoke_confirm_title)
                .setMessage(msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_ok, (dialog, which) -> askPassword(opt))
                .show();
    }

    private RevokeApproveTask.Options collectOptions() {
        RevokeApproveTask.Options opt = new RevokeApproveTask.Options();
        opt.targetIndex = selectedTargetIndex();
        opt.mode = selectedMode();
        return opt;
    }

    /**
     * 确认之后才抢闸门、再弹密码框。
     *
     * <p>顺序不能反：先抢锁再弹框，才能保证密码框开着的那段时间里别处起不了任务；
     * {@code onCancel} 必须传，用户直接关掉密码框时不释放就会把整个 App 的写操作锁死。</p>
     */
    private void askPassword(RevokeApproveTask.Options opt) {
        if (!AppGlobal.acquireTask()) {
            showTip(getString(R.string.revoke_task_busy));
            return;
        }
        gateOwned = true;
        setRunning(true);
        CryptoKeyPrompt.show(this, pwd -> startRun(opt, pwd), this::onPromptCanceled);
    }

    private void onPromptCanceled() {
        releaseGate();
        setRunning(false);
        appendUiLog(getString(R.string.revoke_pwd_canceled));
    }

    private void startRun(final RevokeApproveTask.Options opt, final char[] pwd) {
        // 与主界面 beginTask 对称：清掉上一次可能残留的停止/取消标记，否则一开跑就自行退出
        Web3AndroidUtil.resetStop();
        Web3AndroidUtil.resetCancel();
        final List<KeyListItem> items = new ArrayList<>(runItems);
        appendUiLog(getString(R.string.revoke_running_fmt, items.size()));
        // 每个号每条关系各一次读、改动还要等回执，几十号一轮同样要熄屏保活
        TaskKeepAliveService.begin(this, getString(R.string.notif_task_detail_fmt,
                getString(R.string.common_title_revoke_approve)));
        new Thread(() -> {
            String err = null;
            try {
                RevokeApproveTask.run(getApplicationContext(), this, items, pwd, opt);
            } catch (Exception e) {
                err = Web3AndroidUtil.safeMessage(e);
            } finally {
                Arrays.fill(pwd, (char) 0);
                TaskKeepAliveService.end(getApplicationContext());
                releaseGate();
            }
            final String fErr = err;
            if (fErr != null) appendUiLog("✖ " + fErr);
            appendUiLog(getString(R.string.revoke_finish_hint));
            if (destroyed || isFinishing()) return;
            runOnUiThread(() -> setRunning(false));
        }).start();
    }

    /** 唯一的停止出口：置取消标记，当前账号处理完就跳出，已上链的交易不回滚 */
    private void handleStopClick() {
        if (!running) return;
        Web3AndroidUtil.requestCancel();
        appendUiLog(getString(R.string.revoke_stop_requested));
        btnStop.setEnabled(false);
        btnStop.setAlpha(0.5f);
    }

    /** 只释放自己抢到的那把锁 */
    private void releaseGate() {
        if (!gateOwned) return;
        gateOwned = false;
        AppGlobal.releaseTask();
    }

    /** 运行态：锁掉会改变这轮输入的控件，只留「停止」 */
    private void setRunning(boolean r) {
        running = r;
        btnStart.setVisibility(r ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(r ? View.VISIBLE : View.GONE);
        btnStop.setEnabled(true);
        btnStop.setAlpha(1f);
        spTarget.setEnabled(!r);
        // RadioGroup.setEnabled 不管子项（子 RadioButton 各自持有 enabled），要逐个锁
        rbRevoke.setEnabled(!r);
        rbRestore.setEnabled(!r);
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
