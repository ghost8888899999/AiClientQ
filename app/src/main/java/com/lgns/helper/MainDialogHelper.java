package com.lgns.helper;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import com.lgns.batch.BatchFundTask;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.service.AutoClearWorker;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.R;
import com.lgns.util.Web3AndroidUtil;
import com.lgns.ui.activity.BatchFundConfirmActivity;
import com.lgns.ui.activity.ChampionshipStatsActivity;
import com.lgns.ui.activity.LogViewActivity;
import com.lgns.ui.activity.MainActivity;
import com.lgns.ui.activity.PrivateKeyManageActivity;
import com.lgns.ui.adapter.AccountStatsAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 主界面所有弹窗与“确认后开子线程干活”的入口集合。
 *
 * <p>职责边界：这里只负责「收集参数 / 二次确认 / 提示结果」，真正的链上或落库动作一律推给
 * {@link KeyStoreManager}、{@link BatchFundTask} 等下游，并且都在子线程里执行，
 * 回到 UI 线程只做 {@code appendLog} / 刷新列表 / 弹提示三件事。</p>
 *
 * <p>密码约定：所有需要解密的地方统一走 {@link #showDecryptPasswordDialog}，
 * 密码先用首条密文试解一次验真，确认后才交回业务；密码字符数组用完必须
 * {@code Arrays.fill(pwd, (char) 0)} 清零，不要留着给 GC 处理。</p>
 */
public class MainDialogHelper {
    private final MainActivity activity;
    /**
     * 预留给链上余额查询的防重入开关。
     *
     * <p>当前本类内没有任何读写：批量充值已改成只做本地校验就跳确认页，
     * 余额查询移到 {@code BatchFundConfirmActivity} 内异步执行，主页不再长时间遮挡。</p>
     */
    private boolean fundQuerying = false;

    /** 只持有宿主 Activity：弹窗需要它的 Context 与当前列表快照，不另建状态 */
    public MainDialogHelper(MainActivity activity) {
        this.activity = activity;
    }

    /** 返回统一使用 {@code AppDialog} 样式的 Builder：全局只此一处，避免各弹窗样式分叉 */
    public AlertDialog.Builder styledDialog() {
        return new AlertDialog.Builder(activity, R.style.AppDialog);
    }

    /**
     * 结算/锦标赛统计：把当前选中且启用的账号丢给 {@code ChampionshipStatsActivity} 展示。
     *
     * <p>几个约定：</p>
     * <ul>
     *   <li>禁用账号不参与统计（与主界面展示一致）；</li>
     *   <li>标签优先用自定义编号 {@code customCode}，没填才回退到 {@code displayNo}；</li>
     *   <li>已加载的冠军次数从主界面快照带过去（{@code initChamps}），避免统计页先归零再跳数；
     *   快照里没有的条目传 -1，让统计页自己拉。</li>
     * </ul>
     *
     * <p>三种提前退出：没选中任何账号 / 选中了但全部缺地址 / 选中了但全部被禁用。</p>
     */
    public void showChampionshipStatsDialog() {
        List<KeyListItem> selectedList = activity.getSelectedKeyItem();
        if (selectedList.isEmpty()) {
            styledDialog()
                    .setMessage(R.string.dialog_select_accounts)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
            return;
        }

        List<String> addrs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        List<Integer> initChamps = new ArrayList<>();
        // 快照继承：主界面已加载的冠军次数直接带过去，避免统计页归零闪烁
        Map<String, AccountStatsAdapter.AccountStatsItem> snapshot = activity.getAccountStatsSnapshot();
        int missingCount = 0;
        for (KeyListItem item : selectedList) {
            // 对标主界面：禁用账号不参与统计展示
            if (!item.enabled) continue;
            String no = (item.customCode != null && !item.customCode.isEmpty())
                    ? item.customCode : item.displayNo;
            if (item.address == null || item.address.isEmpty()) {
                missingCount++;
                continue;
            }
            addrs.add(item.address);
            labels.add(no);
            groups.add(item.groupNo != null ? item.groupNo : "");
            AccountStatsAdapter.AccountStatsItem old = snapshot.get(item.address);
            initChamps.add(old != null ? old.totalChampion : -1);
        }

        if (missingCount > 0 && addrs.isEmpty()) {
            styledDialog()
                    .setTitle(R.string.dialog_no_addr_title)
                    .setMessage(R.string.dialog_no_addr_msg)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
            return;
        }

        if (addrs.isEmpty()) {
            styledDialog()
                    .setMessage(R.string.stats_no_enabled_account)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
            return;
        }

        android.content.Intent intent = new android.content.Intent(activity, ChampionshipStatsActivity.class);
        intent.putStringArrayListExtra("addrs", new ArrayList<>(addrs));
        intent.putStringArrayListExtra("labels", new ArrayList<>(labels));
        intent.putStringArrayListExtra("groups", new ArrayList<>(groups));
        intent.putIntegerArrayListExtra("initChamps", new ArrayList<>(initChamps));
        activity.startActivity(intent);
    }

    /**
     * 一键为旧密钥补全地址：先验密码，再拿密码逐条解出私钥推导地址。
     *
     * <p>只处理 {@code address} 为空的条目，已有的跳过；单条解密失败只计数不中断，
     * 最后按「成功数 > 0 / 全失败 / 无待迁移」三种情况给不同文案。</p>
     *
     * <p>注意写库方式：每条只调 {@code updateKeyEntryAddress} 改自己那一行，
     * 而不是最后整表回存——否则解密期间用户改过的启用状态会被整体冲回旧值。</p>
     */
    public void showMigrateAddressesDialog() {
        showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                // 解密用弹窗里现输的密码，不依赖会话；会话过期时也能就地补全地址
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();
                int migrated = 0;
                int failed = 0;
                for (KeyStoreManager.KeyEntry entry : entries) {
                    if (entry.address != null && !entry.address.isEmpty()) continue;
                    try {
                        String pk = mgr.decryptCipherText(entry.cipherText, pwdCharArray);
                        String addr = "0x" + org.web3j.crypto.Keys.getAddress(
                                org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(pk)));
                        entry.address = addr;
                        // 只写这一条的 address：旧实现最后 saveKeyEntryList 整表覆盖，
                        // 会把解密期间用户改的启用状态全部冲回旧值
                        mgr.updateKeyEntryAddress((int) entry.id, addr);
                        migrated++;
                    } catch (Exception ignore) {
                        failed++;
                    }
                }
                final int count = migrated;
                final int failCount = failed;
                activity.runOnUiThread(() -> {
                    String msg;
                    if (count > 0) {
                        msg = activity.getString(R.string.migrate_success_fmt, count);
                    } else if (failCount > 0) {
                        msg = activity.getString(R.string.migrate_decrypt_fail);
                    } else {
                        msg = activity.getString(R.string.migrate_none);
                    }
                    activity.appendLog("", "", msg);
                    if (count > 0) activity.refreshLocalAndStats();
                    styledDialog()
                            .setMessage(msg)
                            .setPositiveButton(R.string.btn_ok, null)
                            .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> styledDialog()
                        .setMessage(activity.getString(R.string.migrate_fail_fmt, Web3AndroidUtil.safeMessage(e)))
                        .setPositiveButton(R.string.btn_ok, null)
                        .show());
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start());
    }

    /**
     * 添加密钥弹窗：分组下拉 + 私钥两段输入（方便分段粘贴）+ 加密密码 + 密码强度说明入口。
     *
     * <p>两段输入在提交时直接拼接，不做长度校验——真正的合法性由
     * {@code KeyStoreManager.addPrivateKey} 解密/推导地址时决定，失败就回一条错误日志。</p>
     *
     * <p>确认按钮不用系统默认行为：点击后先做本地非空校验，通过才在子线程加密入库，
     * 成功后才 dismiss 并刷新列表，失败时弹窗保留让用户重试。</p>
     */
    public void showAddKeyDialog() {
        AlertDialog.Builder builder = styledDialog();
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_key, null);
        Spinner spGroupNo = view.findViewById(R.id.sp_group_no);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etUserCrypto = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEyeToggle = view.findViewById(R.id.iv_eye_toggle);
        ImageButton ivCryptoHelp = view.findViewById(R.id.iv_crypto_help);

        ivCryptoHelp.setOnClickListener(v -> PasswordStrengthDialog.show(activity));

        List<String> groups = activity.loadGroupList();
        if (groups.isEmpty()) {
            groups.add("default");
            activity.saveGroupList(groups);
        }
        ArrayAdapter<String> groupAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, groups);
        spGroupNo.setAdapter(groupAdapter);

        final boolean[] isPasswordVisible = {false};
        ivEyeToggle.setOnClickListener(v -> {
            isPasswordVisible[0] = !isPasswordVisible[0];
            if (isPasswordVisible[0]) {
                etUserCrypto.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
                ivEyeToggle.setImageResource(R.drawable.ic_eye_on_svg);
            } else {
                etUserCrypto.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                ivEyeToggle.setImageResource(R.drawable.ic_eye_off_svg);
            }
        });
        builder.setView(view);
        builder.setPositiveButton(R.string.btn_add_confirm, null);
        builder.setNegativeButton(R.string.btn_cancel, null);
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String groupNoInput = ((String) spGroupNo.getSelectedItem()).trim();
            String part1 = etPk1.getText().toString().trim();
            String part2 = etPk2.getText().toString().trim();
            String fullPrivateKey = (part1 + part2).trim();
            String userKeyStr = etUserCrypto.getText().toString().trim();
            if (groupNoInput.isEmpty()) {
                styledDialog()
                        .setMessage(R.string.dialog_select_group)
                        .setPositiveButton(R.string.btn_ok, null)
                        .show();
                return;
            }
            if (fullPrivateKey.isEmpty()) {
                styledDialog()
                        .setMessage(R.string.dialog_key_empty)
                        .setPositiveButton(R.string.btn_ok, null)
                        .show();
                return;
            }
            activity.tempUserCryptoKey = userKeyStr.toCharArray();
            final String userInputGroupNo = groupNoInput;
            new Thread(() -> {
                try {
                    // 加密用的是弹窗里自带的密码，与会话无关；走会话门会让会话过期后永远“添加失败”
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    mgr.addPrivateKey(fullPrivateKey, activity.tempUserCryptoKey, userInputGroupNo);
                    activity.runOnUiThread(() -> {
                        activity.appendLog(userInputGroupNo, "", "密钥添加完成");
                        activity.refreshCipherKeyList();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        activity.appendLog("", "", "错误：添加密钥失败：" + e.getMessage());
                    });
                }
            }).start();
        });
    }

    /** 打开数据库日志页（独立 Activity，不在弹窗里做分页） */
    public void showViewLogDialog() {
        android.content.Intent intent = new android.content.Intent(activity, LogViewActivity.class);
        activity.startActivity(intent);
    }

    /**
     * 解密密码弹窗：整个应用的密码输入唯一入口。
     *
     * <p>为什么先试解：GCM 没有独立的密码校验位，所以拿当前列表第一条密文做一次 {@code testDecrypt}，
     * 能解开才算密码正确。这一步放在子线程，期间只禁用本弹窗的确认按钮（主界面按钮不受影响），
     * 避免把整个界面锁住。</p>
     *
     * <p>交互细节：</p>
     * <ul>
     *   <li>弹窗一出现就唤起键盘（{@code SOFT_INPUT_STATE_ALWAYS_VISIBLE}）；</li>
     *   <li>错误提示内联在 {@code tvError}，重新输入即清掉，不弹二级弹框；</li>
     *   <li>键盘“完成”等同点击确认；</li>
     *   <li>校验期间用户取消/返回则不再回调，并把密码字符数组归零。</li>
     * </ul>
     *
     * <p>回调里的字符数组所有权交给调用方，用完自行清零。</p>
     *
     * @param callback 密码校验通过后的回调，在 UI 线程执行
     */
    public void showDecryptPasswordDialog(final MainActivity.PasswordCallback callback) {
        final AlertDialog.Builder builder = styledDialog();
        final View view = LayoutInflater.from(activity).inflate(R.layout.dialog_decrypt_password, null);
        final EditText etPwd = view.findViewById(R.id.et_decrypt_pwd);
        final TextView tvError = view.findViewById(R.id.tv_error);
        builder.setView(view);
        final AlertDialog dialog = builder.create();

        // 先声明软键盘策略：弹窗一出现就唤起键盘，无需用户再点一次输入框
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        focusPwdInput(etPwd);

        final View btnConfirm = view.findViewById(R.id.btn_pwd_confirm);
        view.findViewById(R.id.btn_pwd_cancel).setOnClickListener(v -> dialog.dismiss());

        // 密码说明按钮：说明弹窗出现的原因、密码报错排查，并提供改密入口
        view.findViewById(R.id.btn_pwd_help).setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etPwd.getWindowToken(), 0);
            StatusTipDialog.show(activity, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                    activity.getString(R.string.dialog_pwd_help_title),
                    // 资源中的 | 在运行时换成换行，避免 XML 空白被折叠
                    activity.getString(R.string.dialog_pwd_help_msg).replace("|", "\n"),
                    activity.getString(R.string.pwd_help_go_manage),
                    () -> {
                        dialog.dismiss();
                        activity.startActivity(new Intent(activity, PrivateKeyManageActivity.class));
                    },
                    activity.getString(R.string.btn_ok), null);
        });

        // 重新输入即清除上一次的错误态，避免错误提示长期挂在界面上
        etPwd.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                hidePwdError(tvError);
            }
        });

        // 键盘“完成”等同点击确认
        etPwd.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterDown = event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_DONE || enterDown) {
                btnConfirm.performClick();
                return true;
            }
            return false;
        });

        btnConfirm.setOnClickListener(v -> {
            final String pwdStr = etPwd.getText().toString();
            if (pwdStr.isEmpty()) {
                // 空密码只做内联提醒，不锁按钮、不关闭弹窗
                showPwdError(tvError, activity.getString(R.string.err_pwd_empty));
                focusPwdInput(etPwd);
                return;
            }
            hidePwdError(tvError);
            final char[] pwdCharArray = pwdStr.toCharArray();
            if (activity.keyListItemList.isEmpty()) {
                dialog.dismiss();
                callback.onInputPwd(pwdCharArray);
                return;
            }
            final String firstCipher = activity.keyListItemList.get(0).cipherText;
            // 仅校验期间禁用本弹窗的确认按钮，失败立即恢复；主界面按钮始终不受影响
            btnConfirm.setEnabled(false);
            btnConfirm.setClickable(false);
            btnConfirm.setAlpha(0.6f);
            new Thread(() -> {
                boolean testOk;
                try {
                    // 试解必须用元数据实例：会话过期时 getKeyStoreManager() 会直接抛，
                    // 结果被当成“密码错误”，用户输多少遍都解不开
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    mgr.testDecrypt(firstCipher, pwdCharArray);
                    // 密码已通过 GCM 认证：顺手恢复会话，后面的批量任务才能解密
                    AppGlobal.ensureSession(activity);
                    testOk = true;
                } catch (Exception e) {
                    testOk = false;
                }
                final boolean result = testOk;
                activity.runOnUiThread(() -> {
                    btnConfirm.setEnabled(true);
                    btnConfirm.setClickable(true);
                    btnConfirm.setAlpha(1f);
                    if (result) {
                        if (!dialog.isShowing()) {
                            // 校验期间用户已取消/返回，不再启动任务
                            Arrays.fill(pwdCharArray, (char) 0);
                            return;
                        }
                        dialog.dismiss();
                        callback.onInputPwd(pwdCharArray);
                    } else {
                        // 密码错误：弹窗保持打开，内联报错并全选原内容，直接重输即可
                        Arrays.fill(pwdCharArray, (char) 0);
                        showPwdError(tvError, activity.getString(R.string.dialog_pwd_error_msg));
                        etPwd.selectAll();
                        focusPwdInput(etPwd);
                    }
                });
            }).start();
        });
    }

    /** 让密码输入框直接拿到焦点并唤起键盘（不改动光标/选区，便于错误重输时保留全选） */
    private void focusPwdInput(EditText et) {
        et.requestFocus();
        et.post(() -> {
            et.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** 在输入框下方显示内联错误文案 */
    private void showPwdError(TextView tvError, String msg) {
        if (tvError == null) return;
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    /** 隐藏内联错误文案；已是隐藏态时不做无意义的重设，避免重复触发布局 */
    private void hidePwdError(TextView tvError) {
        if (tvError != null && tvError.getVisibility() == View.VISIBLE) {
            tvError.setVisibility(View.GONE);
        }
    }

    /**
     * 安全提醒弹窗：风险清单 + 密码建议。
     *
     * <p>按钮在布局内自绘（不走系统按钮栏），否则在 {@code AppDialog} 样式下会贴边。
     * 次级入口「密码强度说明」关闭后会重新弹出本提醒，保证用户回到原链路。</p>
     */
    public void showSecurityWarningDialog() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_security_warn, null);
        final AlertDialog dialog = styledDialog().setView(view).create();
        dialog.show();

        view.findViewById(R.id.btn_sec_confirm).setOnClickListener(v -> dialog.dismiss());

        // 次要入口：关闭后回到本提醒
        view.findViewById(R.id.btn_sec_strength).setOnClickListener(v -> {
            dialog.dismiss();
            PasswordStrengthDialog.show(activity, d -> showSecurityWarningDialog());
        });
    }

    /** 清空数据库日志（不再弹二次确认，调用方已在菜单里做过提示） */
    public void showConfirmClearLogDb() {
        new Thread(() -> {
            AppLogDbHelper.getInstance(activity).clearAllLog();
            activity.runOnUiThread(() -> activity.appendLog("", "", "数据库日志已全部清空"));
        }).start();
    }

    /**
     * 清空全部密钥：二次确认后子线程执行，成功后写一条日志并刷新主界面列表。
     *
     * <p>不删分组与地址簿——只清私钥本体，避免用户重建密钥后还要重新录入分组配置。</p>
     */
    public void showConfirmClearAllKey() {
        styledDialog()
                .setTitle(R.string.dialog_clear_all_title)
                .setMessage(R.string.dialog_clear_all_msg)
                .setPositiveButton(R.string.btn_clear_confirm, (d, w) -> new Thread(() -> {
                    try {
                        // 清空私钥是纯数据库操作，不需要密码
                        KeyStoreManager mgr = AppGlobal.getMetaManager();
                        mgr.clearAllKeys();
                        activity.runOnUiThread(() -> {
                            activity.appendLog("", "", activity.getString(R.string.log_keys_cleared));
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "",
                                activity.getString(R.string.log_clear_error_fmt, e.getMessage())));
                    }
                }).start())
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /**
     * 删除勾选的密钥：二次确认后逐条删库。
     *
     * <p>必须先收集位置再回退遍历删除：{@code removeByIndex} 会让后面元素整体前移，
     * 正向删会跳掉一条。位置收集的是主界面列表下标，所以删除期间不要触发列表刷新。</p>
     */
    public void removeSelectedKeys() {
        final List<Integer> deletePosList = new ArrayList<>();
        for (int i = 0; i < activity.keyListItemList.size(); i++) {
            KeyListItem item = activity.keyListItemList.get(i);
            if (item.isChecked) {
                deletePosList.add(i);
            }
        }
        if (deletePosList.isEmpty()) {
            styledDialog()
                    .setMessage(R.string.dialog_select_delete)
                    .setPositiveButton(R.string.btn_ok, null).show();
            return;
        }
        styledDialog()
                .setTitle(R.string.dialog_delete_title)
                .setMessage(activity.getString(R.string.dialog_delete_msg_fmt, deletePosList.size()))
                .setPositiveButton(R.string.btn_delete_confirm, (d, w) -> new Thread(() -> {
                    try {
                        // 删除也是纯数据库操作，不需要密码
                        KeyStoreManager mgr = AppGlobal.getMetaManager();
                        for (int i = deletePosList.size() - 1; i >= 0; i--) {
                            int pos = deletePosList.get(i);
                            mgr.removeByIndex(pos);
                        }
                        activity.runOnUiThread(() -> {
                            activity.appendLog("", "", activity.getString(R.string.log_deleted_fmt, deletePosList.size()));
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "",
                                activity.getString(R.string.log_delete_error_fmt, e.getMessage())));
                    }
                }).start())
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /**
     * 批量充值入口：只做毫秒级本地校验，然后立即跳确认页。
     *
     * <p>链上余额查询已挪到 {@code BatchFundConfirmActivity} 内异步执行，
     * 避免主页被模态弹框长时间遮住；这里只负责拦两种前置条件：
     * Activity 已销毁直接返回，以及主号未设置时提示去设置。</p>
     *
     * <p>用 {@code startActivityForResult} 是为了充值结束后能回到主页刷新本地清单。</p>
     */
    public void showBatchFundDialog() {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        long masterId;
        try {
            // 读主号只是元数据：走会话门会让会话过期后永远弹“未设置主号”
            masterId = AppGlobal.getMetaManager().getMasterAccountId();
        } catch (Exception e) {
            masterId = -1;
        }
        if (masterId <= 0) {
            StatusTipDialog.show(activity, StatusTipDialog.TONE_WARN, R.drawable.ic_key_svg,
                    activity.getString(R.string.master_not_set_title),
                    activity.getString(R.string.master_not_set_msg),
                    activity.getString(R.string.fund_go_settings),
                    () -> activity.startActivity(new Intent(activity, PrivateKeyManageActivity.class)),
                    activity.getString(R.string.btn_cancel), null);
            return;
        }

        Intent intent = new Intent(activity, BatchFundConfirmActivity.class);
        activity.startActivityForResult(intent, REQUEST_BATCH_FUND);
    }

    /** 批量充值确认页的请求码，用于 {@code onActivityResult} 里区分回包来源 */
    private static final int REQUEST_BATCH_FUND = 1001;

}
