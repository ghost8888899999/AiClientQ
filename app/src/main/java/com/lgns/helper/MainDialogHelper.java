package com.lgns.helper;

import android.app.AlertDialog;
import android.content.Intent;
import java.util.ArrayList;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainDialogHelper {
    private final MainActivity activity;

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
        int missingCount = 0;
        for (KeyListItem item : selectedList) {
            String no = (item.customCode != null && !item.customCode.isEmpty())
                    ? item.customCode : item.displayNo;
            if (item.address == null || item.address.isEmpty()) {
                missingCount++;
                continue;
            }
            addrs.add(item.address);
            labels.add(no);
        }

        if (missingCount > 0 && addrs.isEmpty()) {
            styledDialog()
                    .setTitle(R.string.dialog_no_addr_title)
                    .setMessage(R.string.dialog_no_addr_msg)
                    .setPositiveButton(R.string.btn_ok, null)
                    .show();
            return;
        }

        if (addrs.isEmpty()) return;

        android.content.Intent intent = new android.content.Intent(activity, ChampionshipStatsActivity.class);
        intent.putStringArrayListExtra("addrs", new ArrayList<>(addrs));
        intent.putStringArrayListExtra("labels", new ArrayList<>(labels));
        activity.startActivity(intent);
    }

    // 一键为旧密钥补全地址
    public void showMigrateAddressesDialog() {
        showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();
                int migrated = 0;
                for (KeyStoreManager.KeyEntry entry : entries) {
                    if (entry.address != null && !entry.address.isEmpty()) continue;
                    try {
                        String pk = mgr.decryptCipherText(entry.cipherText, pwdCharArray);
                        String addr = org.web3j.crypto.Keys.getAddress(
                                org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(pk)));
                        entry.address = addr;
                        migrated++;
                    } catch (Exception ignore) {}
                }
                mgr.saveKeyEntryList(entries);
                final int count = migrated;
                activity.runOnUiThread(() -> {
                    activity.appendLog("", "", activity.getString(R.string.migrate_success_fmt, count));
                    activity.refreshCipherKeyList();
                    styledDialog()
                            .setMessage(activity.getString(R.string.migrate_success_fmt, count))
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

        ivCryptoHelp.setOnClickListener(v -> styledDialog()
                .setTitle(R.string.dialog_security_title)
                .setMessage(R.string.dialog_security_msg)
                .setPositiveButton(R.string.btn_ok, null)
                .show());

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
        builder.setView(view);
        builder.setPositiveButton(R.string.btn_confirm, null);
        builder.setNegativeButton(R.string.btn_cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final char[] pwdCharArray = etPwd.getText().toString().toCharArray();
            if (activity.keyListItemList.isEmpty()) {
                dialog.dismiss();
                callback.onInputPwd(pwdCharArray);
                return;
            }
            final String firstCipher = activity.keyListItemList.get(0).cipherText;
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
                    if (result) {
                        dialog.dismiss();
                        callback.onInputPwd(pwdCharArray);
                    } else {
                        styledDialog()
                                .setTitle(R.string.dialog_pwd_error_title)
                                .setMessage(R.string.dialog_pwd_error_msg)
                                .setPositiveButton(R.string.btn_ok, null)
                                .show();
                    }
                });
            }).start();
        });

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
    }

    // 安全提醒弹窗
    public void showSecurityWarningDialog() {
        styledDialog()
                .setTitle(R.string.dialog_security_warn_title)
                .setMessage(R.string.dialog_security_warn_msg)
                .setPositiveButton(R.string.btn_got_it, null)
                .show();
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
        new Thread(() -> {
            try {
                BatchFundTask.FundPreviewResult preview = BatchFundTask.queryFundPreview(
                        activity.getEnabledKeyItems());

                if (!preview.hasMaster) {
                    activity.runOnUiThread(() -> styledDialog()
                            .setTitle(R.string.master_not_set_title)
                            .setMessage(R.string.master_not_set_msg)
                            .setPositiveButton(R.string.btn_ok, null)
                            .show());
                    return;
                }

                final BatchFundTask.FundPreviewResult fPreview = preview;

                activity.runOnUiThread(() -> {
                    if (!fPreview.needsFunding()) {
                        styledDialog()
                                .setTitle(R.string.fund_no_need_title)
                                .setMessage(R.string.fund_no_need_msg)
                                .setPositiveButton(R.string.btn_ok, null)
                                .show();
                        return;
                    }

                    // 构建 FundItem 列表
                    ArrayList<BatchFundConfirmActivity.FundItem> fundItems = new ArrayList<>();
                    for (int i = 0; i < fPreview.needFundLabels.size(); i++) {
                        String label = fPreview.needFundLabels.get(i);
                        // 解析标签格式："a01 (余额:0.00)"
                        String keyNo = label.split(" ")[0];
                        String balance = label.contains("余额:") ? label.split("余额:")[1].replace(")", "") : "0.00";
                        String needAmount = "100.00"; // 目标余额
                        fundItems.add(new BatchFundConfirmActivity.FundItem(keyNo, balance, needAmount));
                    }

                    // 启动确认页面
                    Intent intent = new Intent(activity, BatchFundConfirmActivity.class);
                    intent.putExtra(BatchFundConfirmActivity.EXTRA_MASTER_ADDR, 
                            fPreview.masterEntry.address);
                    intent.putExtra(BatchFundConfirmActivity.EXTRA_MASTER_BALANCE, 
                            fPreview.getMasterBalanceFormatted());
                    intent.putExtra(BatchFundConfirmActivity.EXTRA_FUND_LIST, fundItems);
                    intent.putExtra(BatchFundConfirmActivity.EXTRA_TOTAL_AMOUNT, 
                            fPreview.getTotalNeededFormatted());
                    activity.startActivityForResult(intent, REQUEST_BATCH_FUND);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> styledDialog()
                        .setMessage(activity.getString(R.string.fund_query_fail, Web3AndroidUtil.safeMessage(e)))
                        .setPositiveButton(R.string.btn_ok, null)
                        .show());
            }
        }).start();
    }

    private static final int REQUEST_BATCH_FUND = 1001;

}
