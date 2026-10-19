package com.lgns;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;

import org.web3j.protocol.Web3j;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainDialogHelper {
    private final MainActivity activity;

    public MainDialogHelper(MainActivity activity) {
        this.activity = activity;
    }

    private AlertDialog.Builder styledDialog() {
        return new AlertDialog.Builder(activity, R.style.AppDialog);
    }

    // ========== 锦标赛统计弹窗 ==========
    public void showChampionshipStatsDialog() {
        List<KeyListItem> selectedList = activity.getSelectedKeyItem();
        if (selectedList.isEmpty()) {
            styledDialog()
                    .setMessage("请先在列表中勾选要查询的账号")
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
                    .setMessage("所选账号均为旧密钥，没有地址信息。\n\n请删除旧密钥后重新添加（添加时会自动保存地址），\n或使用菜单「补全密钥地址」输入密码一次性迁移。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        if (missingCount > 0) {
            styledDialog()
                    .setMessage(missingCount + " 个旧密钥无地址已跳过，查询 " + addrs.size() + " 个")
                    .setPositiveButton("继续", null)
                    .show();
        }

        if (addrs.isEmpty()) return;

        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();
                java.util.Map<String, long[]> result = ArenaContractApi.callChampionshipCounts(web3j, addrs);

                StringBuilder sb = new StringBuilder();
                long grandTotal = 0, grandWins = 0;
                for (int i = 0; i < addrs.size(); i++) {
                    String addr = addrs.get(i);
                    long[] counts = result.get(addr);
                    long total = counts != null ? counts[0] : 0;
                    long wins = counts != null ? counts[1] : 0;
                    grandTotal += total;
                    grandWins += wins;
                    sb.append(labels.get(i)).append(": ").append(addr.substring(0, 10))
                            .append("... 总").append(total).append("场 赢").append(wins).append("场\n");
                }
                sb.append("\n合计: ").append(grandTotal).append("场 赢").append(grandWins).append("场");
                String showText = sb.toString();
                activity.runOnUiThread(() -> styledDialog()
                        .setTitle("锦标赛统计")
                        .setMessage(showText)
                        .setPositiveButton("关闭", null)
                        .show());
            } catch (Exception e) {
                activity.runOnUiThread(() -> styledDialog()
                        .setMessage("查询失败：" + Web3AndroidUtil.safeMessage(e))
                        .setPositiveButton("知道了", null)
                        .show());
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
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
                    activity.appendLog("", "", "✅已为 " + count + " 个旧密钥补全地址");
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
                        activity.appendLog(userInputGroupNo, "", "✅密钥添加完成");
                        activity.refreshCipherKeyList();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        activity.appendLog("", "", "❌添加密钥失败：" + e.getMessage());
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
            activity.runOnUiThread(() -> activity.appendLog("", "", "✅数据库日志已全部清空"));
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
                            activity.appendLog("", "", "✅全部密钥已清空");
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "", "❌清空密钥异常：" + e.getMessage()));
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
                    .setMessage("请先在列表勾选需要删除的密钥")
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
                            activity.appendLog("", "", "✅已删除选中 " + deletePosList.size() + " 条密钥");
                            activity.refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> activity.appendLog("", "", "删除选中密钥异常：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ========== 自动清除配置弹窗 ==========
    public void openSysScheduleDialog() {
        // ... 此方法太长，留在 MainActivity 中 ...
    }
}
