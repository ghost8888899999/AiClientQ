package com.lgns.helper;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.service.AutoClearWorker;
import com.lgns.service.AutoFundService;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.R;
import com.lgns.util.Web3AndroidUtil;
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

    // ========== 锦标赛统计 ==========
    public void showChampionshipStatsDialog() {
        List<KeyListItem> selectedList = activity.getSelectedKeyItem();
        if (selectedList.isEmpty()) {
            styledDialog()
                    .setMessage("请先勾选要查询的账号")
                    .setPositiveButton("知道了", null)
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
                    .setTitle("地址未生成")
                    .setMessage("所选账号均为旧密钥，没有地址信息。\n\n请使用菜单「补全密钥地址」输入密码一次性迁移。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        if (addrs.isEmpty()) return;

        android.content.Intent intent = new android.content.Intent(activity, ChampionshipStatsActivity.class);
        intent.putStringArrayListExtra("addrs", new ArrayList<>(addrs));
        intent.putStringArrayListExtra("labels", new ArrayList<>(labels));
        activity.startActivity(intent);
    }

    // ========== 一键为旧密钥补全地址 ==========
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
                    activity.appendLog("", "", "已为 " + count + " 个旧密钥补全地址");
                    activity.refreshCipherKeyList();
                    styledDialog()
                            .setMessage("已为 " + count + " 个密钥补全地址")
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> styledDialog()
                        .setMessage("迁移失败：" + Web3AndroidUtil.safeMessage(e))
                        .setPositiveButton("知道了", null)
                        .show());
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start());
    }

    // ========== 添加密钥弹窗 ==========
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
                .setTitle("密码安全说明")
                .setMessage("若设备丢失，黑客可拿到加密数据离线暴力破解：\n\n" +
                        "6 位纯数字 → 约 3~14 小时可破解 ❌\n\n" +
                        "7 位纯数字 → 约 1~6 天可破解 ❌\n\n" +
                        "7 位字母+数字 → 约 1100 年 ✅\n\n" +
                        "8 位字母+数字 → 约 10 万年 ✅✅\n\n" +
                        "10 位混合 → 千万年级别 ✅✅✅\n\n" +
                        "建议：至少 7 位字母+数字组合，越长越安全。\n密码仅在内存中使用，关闭 APP 即清除。")
                .setPositiveButton("知道了", null)
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
        builder.setPositiveButton("确认添加", null);
        builder.setNegativeButton("取消", null);
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
                        .setMessage("请选择分组！")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            if (fullPrivateKey.isEmpty()) {
                styledDialog()
                        .setMessage("密钥内容不能为空！")
                        .setPositiveButton("知道了", null)
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

    // ========== 查看日志 ==========
    public void showViewLogDialog() {
        android.content.Intent intent = new android.content.Intent(activity, LogViewActivity.class);
        activity.startActivity(intent);
    }

    // ========== 解密密码弹窗 ==========
    public void showDecryptPasswordDialog(final MainActivity.PasswordCallback callback) {
        final AlertDialog.Builder builder = styledDialog();
        final View view = LayoutInflater.from(activity).inflate(R.layout.dialog_decrypt_password, null);
        final EditText etPwd = view.findViewById(R.id.et_decrypt_pwd);
        builder.setView(view);
        builder.setPositiveButton("确认", null);
        builder.setNegativeButton("取消", null);
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
                                .setTitle("密码错误")
                                .setMessage("解密密码校验失败，请核对密码后重新输入！")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }).start();
        });

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> dialog.dismiss());
    }

    // ========== 安全提醒弹窗 ==========
    public void showSecurityWarningDialog() {
        styledDialog()
                .setTitle("安全风险提醒")
                .setMessage("1. 本APP仅为链上操作工具，不是硬件钱包，请勿存放大额资产私钥\n\n2. 手机若已Root，任何APP都可能读取私钥，存在被盗风险\n\n3. 解密密码仅在运行时使用，关闭APP后即清除，不会保存在手机中\n\n4. 私钥会自动清除，若手机开启了省电/后台限制，清除时间可能延迟，重新打开APP会立即清除")
                .setPositiveButton("我知道了", null)
                .show();
    }

    // ========== 清空日志确认 ==========
    public void showConfirmClearLogDb() {
        new Thread(() -> {
            AppLogDbHelper.getInstance(activity).clearAllLog();
            activity.runOnUiThread(() -> activity.appendLog("", "", "数据库日志已全部清空"));
        }).start();
    }

    // ========== 清空密钥确认 ==========
    public void showConfirmClearAllKey() {
        styledDialog()
                .setTitle("确认清空全部密钥")
                .setMessage("将删除本地全部保存的密钥密文，不可恢复！")
                .setPositiveButton("确认清空", (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        mgr.clearAllKeys();
                        activity.runOnUiThread(() -> {
                            activity.appendLog("", "", "全部密钥已清空");
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "", "错误：清空密钥异常：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 删除选中密钥 ==========
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
                    .setMessage("请先勾选需要删除的密钥")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        styledDialog()
                .setTitle("确认删除")
                .setMessage("确定删除选中 " + deletePosList.size() + " 条密钥？该操作不可恢复！")
                .setPositiveButton("确认删除", (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        for (int i = deletePosList.size() - 1; i >= 0; i--) {
                            int pos = deletePosList.get(i);
                            mgr.removeByIndex(pos);
                        }
                        activity.runOnUiThread(() -> {
                            activity.appendLog("", "", "已删除选中 " + deletePosList.size() + " 条密钥");
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "", "删除选中密钥异常：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 批量充值弹窗 ==========
    public void showBatchFundDialog() {
        new Thread(() -> {
            try {
                AutoFundService.FundPreviewResult preview = AutoFundService.queryFundPreview(
                        activity.getEnabledKeyItems());

                if (!preview.hasMaster) {
                    activity.runOnUiThread(() -> styledDialog()
                            .setTitle("未设置主号")
                            .setMessage("请先在私钥管理中编辑某个私钥，勾选「设为主号」")
                            .setPositiveButton("知道了", null)
                            .show());
                    return;
                }

                final AutoFundService.FundPreviewResult fPreview = preview;

                activity.runOnUiThread(() -> {
                    if (!fPreview.needsFunding()) {
                        styledDialog()
                                .setTitle("批量充值")
                                .setMessage("所有账号余额均 ≥ 100，无需充值")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("主号: ").append(fPreview.masterEntry.address.substring(0, 10)).append("...\n");
                    sb.append("主号余额: ").append(fPreview.getMasterBalanceFormatted()).append(" fLGNS\n\n");
                    sb.append("以下账号需要补齐到 100：\n");
                    for (int i = 0; i < fPreview.needFundLabels.size(); i++) {
                        sb.append("  ").append(i + 1).append(". ").append(fPreview.needFundLabels.get(i)).append("\n");
                    }
                    sb.append("\n共 ").append(fPreview.needFundAddrs.size()).append(" 个账号，需转入合计 ")
                            .append(fPreview.getTotalNeededFormatted()).append(" fLGNS");

                    styledDialog()
                            .setTitle("批量充值确认")
                            .setMessage(sb.toString())
                            .setPositiveButton("确认充值", (d, w) -> {
                                showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
                                    try {
                                        AutoFundService.executeAutoFund(
                                                activity.getEnabledKeyItems(), pwdCharArray, activity);
                                    } catch (Exception e) {
                                        activity.runOnUiThread(() -> activity.appendLog("", "FUND",
                                                "❌ 批量充值失败: " + Web3AndroidUtil.safeMessage(e)));
                                    } finally {
                                        Arrays.fill(pwdCharArray, (char) 0);
                                    }
                                }).start());
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> styledDialog()
                        .setMessage("查询失败: " + Web3AndroidUtil.safeMessage(e))
                        .setPositiveButton("知道了", null)
                        .show());
            }
        }).start();
    }
}
