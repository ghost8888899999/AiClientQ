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

public class MainDialogHelper {
    private final MainActivity activity;
    // 防止连点重复发起链上余额查询
    private boolean fundQuerying = false;

    public MainDialogHelper(MainActivity activity) {
        this.activity = activity;
    }

    public AlertDialog.Builder styledDialog() {
        return new AlertDialog.Builder(activity, R.style.AppDialog);
    }

    // 锦标赛统计
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

    // 一键为旧密钥补全地址
    public void showMigrateAddressesDialog() {
        showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
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
                        migrated++;
                    } catch (Exception ignore) {
                        failed++;
                    }
                }
                mgr.saveKeyEntryList(entries);
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

    // 添加密钥弹窗
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
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
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

    // 查看日志
    public void showViewLogDialog() {
        android.content.Intent intent = new android.content.Intent(activity, LogViewActivity.class);
        activity.startActivity(intent);
    }

    // 解密密码弹窗
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
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    mgr.testDecrypt(firstCipher, pwdCharArray);
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

    // 让密码输入框直接拿到焦点并唤起键盘（不改动光标/选区，便于错误重输时保留全选）
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

    private void showPwdError(TextView tvError, String msg) {
        if (tvError == null) return;
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hidePwdError(TextView tvError) {
        if (tvError != null && tvError.getVisibility() == View.VISIBLE) {
            tvError.setVisibility(View.GONE);
        }
    }

    // 安全提醒弹窗：风险清单 + 密码建议，按钮在布局内自绘，避免系统按钮栏贴边
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

    // 清空日志确认
    public void showConfirmClearLogDb() {
        new Thread(() -> {
            AppLogDbHelper.getInstance(activity).clearAllLog();
            activity.runOnUiThread(() -> activity.appendLog("", "", "数据库日志已全部清空"));
        }).start();
    }

    // 清空密钥确认
    public void showConfirmClearAllKey() {
        styledDialog()
                .setTitle(R.string.dialog_clear_all_title)
                .setMessage(R.string.dialog_clear_all_msg)
                .setPositiveButton(R.string.btn_clear_confirm, (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
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

    // 删除选中密钥
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
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
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

    // 批量充值确认页面
    public void showBatchFundDialog() {
        if (activity.isFinishing() || fundQuerying) return;
        fundQuerying = true;

        // 链上余额逐账户读取，账号多时需要数秒，先给出明确的查询反馈
        final AlertDialog loadingDialog = StatusTipDialog.showLoading(activity,
                activity.getString(R.string.fund_loading_title),
                activity.getString(R.string.fund_loading_msg));

        new Thread(() -> {
            BatchFundTask.FundPreviewResult preview;
            try {
                preview = BatchFundTask.queryFundPreview(activity.getAllKeyItems());
            } catch (Exception e) {
                preview = new BatchFundTask.FundPreviewResult();
                preview.error = e;
            }
            final BatchFundTask.FundPreviewResult fPreview = preview;

            activity.runOnUiThread(() -> {
                fundQuerying = false;
                if (loadingDialog != null && loadingDialog.isShowing()) {
                    loadingDialog.dismiss();
                }
                if (activity.isFinishing() || activity.isDestroyed()) return;

                // 网络/节点失败与「无需充值」必须区分，避免误导
                if (fPreview.error != null) {
                    StatusTipDialog.show(activity, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                            activity.getString(R.string.fund_query_fail_title),
                            activity.getString(R.string.fund_query_fail_msg_fmt,
                                    Web3AndroidUtil.safeMessage(fPreview.error)),
                            activity.getString(R.string.btn_retry), this::showBatchFundDialog,
                            activity.getString(R.string.btn_cancel), null);
                    return;
                }

                if (!fPreview.hasMaster) {
                    StatusTipDialog.show(activity, StatusTipDialog.TONE_WARN, R.drawable.ic_key_svg,
                            activity.getString(R.string.master_not_set_title),
                            activity.getString(R.string.master_not_set_msg),
                            activity.getString(R.string.fund_go_settings),
                            () -> activity.startActivity(new Intent(activity, PrivateKeyManageActivity.class)),
                            activity.getString(R.string.btn_cancel), null);
                    return;
                }

                if (!fPreview.needsFunding()) {
                    StatusTipDialog.show(activity, StatusTipDialog.TONE_SUCCESS,
                            R.drawable.ic_menu_check_svg,
                            activity.getString(R.string.fund_no_need_title),
                            activity.getString(R.string.fund_no_need_msg_v2));
                    return;
                }

                // 构建 FundItem 列表：全量启用账号（含主号与余额充足者），
                // 地址/差额/足额标记均取自 fundDetails，不再解析标签字符串
                ArrayList<BatchFundConfirmActivity.FundItem> fundItems = new ArrayList<>();
                for (int i = 0; i < fPreview.fundDetails.size(); i++) {
                    BatchFundTask.FundPreviewResult.FundDetail d = fPreview.fundDetails.get(i);
                    fundItems.add(new BatchFundConfirmActivity.FundItem(
                            d.keyNo,
                            d.currentBalance.toPlainString(),
                            d.neededAmount.toPlainString(),
                            d.address,
                            d.sufficient));
                }

                // 启动确认页面
                Intent intent = new Intent(activity, BatchFundConfirmActivity.class);
                // 主号卡片与列表主号行同源：开关 ON 时展示替换后地址及其实查余额，OFF 时即真实数据
                intent.putExtra(BatchFundConfirmActivity.EXTRA_MASTER_ADDR,
                        fPreview.masterShowAddr != null ? fPreview.masterShowAddr : fPreview.masterEntry.address);
                intent.putExtra(BatchFundConfirmActivity.EXTRA_MASTER_KEYNO,
                        fPreview.masterKeyNo == null ? "" : fPreview.masterKeyNo);
                java.math.BigDecimal masterShowBalF = new java.math.BigDecimal(fPreview.masterShowBalance).divide(
                        new java.math.BigDecimal(java.math.BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP);
                intent.putExtra(BatchFundConfirmActivity.EXTRA_MASTER_BALANCE,
                        String.valueOf(masterShowBalF));
                intent.putExtra(BatchFundConfirmActivity.EXTRA_FUND_LIST, fundItems);
                intent.putExtra(BatchFundConfirmActivity.EXTRA_TOTAL_AMOUNT,
                        String.valueOf(fPreview.getTotalNeededFormatted()));
                activity.startActivityForResult(intent, REQUEST_BATCH_FUND);
            });
        }).start();
    }

    private static final int REQUEST_BATCH_FUND = 1001;

}
