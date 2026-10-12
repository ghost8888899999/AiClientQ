package com.lgns;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.RotateAnimation;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements TaskLogCallback {
    //入场模式
    public static final int MODE_FLEXIBLE = 0; //能打就打，过滤不可执行账号
    public static final int MODE_TOGETHER = 1; //等齐再打，全部模拟通过才提交
    private int currentPlayMode = MODE_FLEXIBLE;
    //临时自定义加解密key，仅内存，不持久化；解密密钥密文用
    private char[] tempUserCryptoKey;
    //WorkManager自动清除配置
    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    private static final long MIN_ALLOW_MINUTE = 15;
    private SharedPreferences spAppSetting;
    private final String[] unitArray = {"分钟", "小时", "天"};
    //UI控件
    private ImageButton btnSettingGear;
    private Button btnSecurityWarn;
    private TextView tvCountInfo;
    private ListView lvCipherKeys;
    private Button btnBatchEnter;
    private Button btnBatchClaim;
    private Button btnFullWorkflow;
    private Button btnResetTask;
    private TextView tvLog;
    private ScrollView scrollView;
    private PopupWindow mPopupWindow;

    //密钥列表Model，带永久keyId
    static class KeyListItem {
        public long keyId; //全局永久ID，删除其他条目，此id不变
        public int seqNo; //内存列表位置（可变，仅UI排序用）
        public String displayNo; //降级备用：格式化001，使用keyId
        public String userGroupNo; //✅用户手动输入分组编号，优先展示
        public String cipherText;
        public boolean isUsable = true;
        public boolean isChecked;
    }


    private List<KeyListItem> keyListItemList = new ArrayList<>();
    private ArrayAdapter<KeyListItem> keyAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        spAppSetting = getSharedPreferences("app_setting", MODE_PRIVATE);
        //读取持久化入场模式
        currentPlayMode = spAppSetting.getInt("play_mode", MODE_FLEXIBLE);
        bindViews();
        //增加长按监听
        lvCipherKeys.setOnItemLongClickListener((parent, view, position, id) -> {
            showSortMenu(position);
            return true;
        });
        setupGearMenu();
        setupListViewCheckListener();
        //APP冷启动兜底自动清除校验
        checkAutoClearBoot();

        //APP启动后台线程初始化密钥管理器
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AppGlobal.initKeyManager(getApplicationContext());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("", "", "✅密钥管理器初始化完成");
                            refreshCipherKeyList();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("", "", "❌密钥管理器初始化失败:" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** ListView条目点击：更新model勾选标记 */
    private void setupListViewCheckListener() {
        lvCipherKeys.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                KeyListItem item = keyListItemList.get(position);
                item.isChecked = !item.isChecked;
                CheckedTextView tv = (CheckedTextView) view;
                tv.setChecked(item.isChecked);
                refreshCheckedStatText();
            }
        });
    }

    /** 统计勾选数量，遍历model */
    private void refreshCheckedStatText() {
        int total = keyListItemList.size();
        int checked = 0;
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) {
                checked++;
            }
        }
        String info = String.format(Locale.getDefault(), "总:%d条密钥，已选:%d条", total, checked);
        tvCountInfo.setText(info);
    }

    /**
     * 自动清除校验
     * 时机：onCreate冷启动 + onResume切回页面
     * 使用System.currentTimeMillis() 毫秒时间戳
     */
    private void checkAutoClearBoot() {
        boolean enable = spAppSetting.getBoolean("auto_clear_enable", false);
        long saveTotalMs = spAppSetting.getLong("auto_clear_ms", 0);
        long saveStartTs = spAppSetting.getLong("auto_clear_start_ts", 0);

        //开关关闭 或者 时长为0，直接返回，不执行清除
        if (!enable || saveTotalMs <= 0 || saveStartTs <=0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - saveStartTs >= saveTotalMs) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        mgr.clearAllKeys();
                        runOnUiThread(() -> appendLog("", "", "⚠检测到自动清除计时到期，打开APP自动清空全部私钥！"));
                    } catch (Exception ignored) {}
                    //只重置计时起点，auto_clear_ms（用户设置时长）、auto_clear_enable（开关状态）全部保留不动
                    long newStartTs = System.currentTimeMillis();
                    spAppSetting.edit()
                            .putLong("auto_clear_start_ts", newStartTs)
                            .apply();
                }
            }).start();
        }
    }



    private void bindViews() {
        btnSettingGear = findViewById(R.id.btn_setting_gear);
        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        tvCountInfo = findViewById(R.id.tv_count_info);
        lvCipherKeys = findViewById(R.id.lv_cipher_keys);
        btnBatchEnter = findViewById(R.id.btn_batch_enter);
        btnBatchClaim = findViewById(R.id.btn_batch_claim);
        btnFullWorkflow = findViewById(R.id.btn_full_workflow);
        btnResetTask = findViewById(R.id.btn_reset_task);
        tvLog = findViewById(R.id.tv_log);
        scrollView = findViewById(R.id.scrollView);
    }

    private void setupGearMenu() {
        btnSettingGear.setOnClickListener(v -> showCollapseMenu(v));
    }

    /** 切换分组展开/收起，箭头旋转动画 */
    private void toggleGroup(View container, ImageView ivArrow) {
        RotateAnimation anim;
        if (container.getVisibility() == View.VISIBLE) {
            container.setVisibility(View.GONE);
            anim = new RotateAnimation(180f, 0f, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
            ivArrow.setRotation(0f);
        } else {
            container.setVisibility(View.VISIBLE);
            anim = new RotateAnimation(0f, 180f, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
            ivArrow.setRotation(180f);
        }
        anim.setDuration(200);
        anim.setFillAfter(true);
        ivArrow.startAnimation(anim);
    }

    private void showCollapseMenu(View anchorView) {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
            return;
        }
        View popView = LayoutInflater.from(this).inflate(R.layout.layout_pop_menu, null);
        //分组控件
        final View groupKeyContainer = popView.findViewById(R.id.group_key_container);
        final ImageView ivArrowKey = popView.findViewById(R.id.iv_arrow_key);
        popView.findViewById(R.id.group_key_title).setOnClickListener(v -> toggleGroup(groupKeyContainer, ivArrowKey));

        final View groupLogContainer = popView.findViewById(R.id.group_log_container);
        final ImageView ivArrowLog = popView.findViewById(R.id.iv_arrow_log);
        popView.findViewById(R.id.group_log_title).setOnClickListener(v -> toggleGroup(groupLogContainer, ivArrowLog));

        final View groupAutoContainer = popView.findViewById(R.id.group_auto_container);
        final ImageView ivArrowAuto = popView.findViewById(R.id.iv_arrow_auto);
        popView.findViewById(R.id.group_auto_title).setOnClickListener(v -> toggleGroup(groupAutoContainer, ivArrowAuto));

        final View groupModeContainer = popView.findViewById(R.id.group_mode_container);
        final ImageView ivArrowMode = popView.findViewById(R.id.iv_arrow_mode);
        popView.findViewById(R.id.group_mode_title).setOnClickListener(v -> toggleGroup(groupModeContainer, ivArrowMode));

        //二级菜单项绑定点击事件
        TextView menuAddKey = popView.findViewById(R.id.menu_add_key);
        menuAddKey.setText("添加密钥");
        menuAddKey.setOnClickListener(v -> {
            showAddKeyDialog();
            mPopupWindow.dismiss();
        });

        TextView menuRefreshList = popView.findViewById(R.id.menu_refresh_list);
        menuRefreshList.setText("刷新密钥列表");
        menuRefreshList.setOnClickListener(v -> {
            refreshCipherKeyList();
            mPopupWindow.dismiss();
        });

        TextView menuRemoveSel = popView.findViewById(R.id.menu_remove_selected);
        menuRemoveSel.setText("删除选中密钥");
        menuRemoveSel.setOnClickListener(v -> {
            removeSelectedKeys();
            mPopupWindow.dismiss();
        });

        TextView menuClearAllKey = popView.findViewById(R.id.menu_clear_all_key);
        menuClearAllKey.setText("清空全部密钥");
        menuClearAllKey.setOnClickListener(v -> {
            showConfirmClearAllKey();
            mPopupWindow.dismiss();
        });

        TextView menuViewLog = popView.findViewById(R.id.menu_view_log);
        menuViewLog.setText("查看日志(最新优先)");
        menuViewLog.setOnClickListener(v -> {
            showViewLogDialog();
            mPopupWindow.dismiss();
        });

        TextView menuClearLogDb = popView.findViewById(R.id.menu_clear_log_db);
        menuClearLogDb.setText("清空数据库全部日志");
        menuClearLogDb.setOnClickListener(v -> {
            showConfirmClearLogDb();
            mPopupWindow.dismiss();
        });

        TextView menuSysSchedule = popView.findViewById(R.id.menu_sys_schedule_setting);
        menuSysSchedule.setText("自动清除配置");
        menuSysSchedule.setOnClickListener(v -> {
            openSysScheduleDialog();
            mPopupWindow.dismiss();
        });

        //==== 根据当前模式动态设置选中标记 ● ====
        TextView menuModeFlex = popView.findViewById(R.id.menu_mode_flexible);
        TextView menuModeTogether = popView.findViewById(R.id.menu_mode_together);
        if (currentPlayMode == MODE_FLEXIBLE) {
            menuModeFlex.setText("● 能打就打(过滤不可执行账号)");
            menuModeTogether.setText("  等齐再打(全部模拟通过才提交)");
        } else {
            menuModeFlex.setText("  能打就打(过滤不可执行账号)");
            menuModeTogether.setText("● 等齐再打(全部模拟通过才提交)");
        }

        //切换：能打就打
        menuModeFlex.setOnClickListener(v -> {
            currentPlayMode = MODE_FLEXIBLE;
            spAppSetting.edit().putInt("play_mode", currentPlayMode).apply();
            appendLog("", "", "✅已切换入场模式：能打就打，自动过滤不可执行账号");
            mPopupWindow.dismiss();
        });
        //切换：等齐再打
        menuModeTogether.setOnClickListener(v -> {
            currentPlayMode = MODE_TOGETHER;
            spAppSetting.edit().putInt("play_mode", currentPlayMode).apply();
            appendLog("", "", "✅已切换入场模式：等齐再打，全部模拟通过才提交交易");
            mPopupWindow.dismiss();
        });

        mPopupWindow = new PopupWindow(popView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.showAsDropDown(anchorView, 0, 4);
    }

    private void setupButtonEvents() {
        btnSecurityWarn.setOnClickListener(v -> showSecurityWarningDialog());

        //批量入场
        btnBatchEnter.setOnClickListener(v -> showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                List<KeyListItem> selectedList = getSelectedKeyItem();
                ArenaTask.batchEnterAllResume(getApplicationContext(), MainActivity.this, currentPlayMode, selectedList, tempUserCryptoKey);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "ENTER", "❌批量入场外层异常：" + e.getMessage()));
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start()));

        //批量领取
        btnBatchClaim.setOnClickListener(v -> showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                List<KeyListItem> selectedList = getSelectedKeyItem();
                ArenaTask.batchClaimAllResume(getApplicationContext(), MainActivity.this, currentPlayMode, selectedList, tempUserCryptoKey);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "CLAIM", "❌批量领取外层异常：" + e.getMessage()));
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start()));

        //完整工作流
        btnFullWorkflow.setOnClickListener(v -> showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                List<KeyListItem> selectedList = getSelectedKeyItem();
                ArenaTask.fullArenaWorkflowResume(getApplicationContext(), MainActivity.this, 2, currentPlayMode, selectedList, tempUserCryptoKey);
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "❌完整工作流外层异常：" + e.getMessage()));
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start()));

        //重置任务进度
        btnResetTask.setOnClickListener(v -> {
            ArenaTask.resetTaskProgress(getApplicationContext());
            appendLog("", "", "✅任务进度已重置");
        });
    }

    /** 获取选中条目 */
    private List<KeyListItem> getSelectedKeyItem() {
        List<KeyListItem> res = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) {
                res.add(item);
            }
        }
        return res;
    }

    //添加密钥弹窗
//添加密钥弹窗
    //添加密钥弹窗
    private void showAddKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_key, null);

        EditText etGroupNo = view.findViewById(R.id.et_group_no);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etUserCrypto = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEyeToggle = view.findViewById(R.id.iv_eye_toggle);
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
            String groupNoInput = etGroupNo.getText().toString().trim();
            String part1 = etPk1.getText().toString().trim();
            String part2 = etPk2.getText().toString().trim();
            String fullPrivateKey = (part1 + part2).trim();
            String userKeyStr = etUserCrypto.getText().toString().trim();

            if (groupNoInput.isEmpty()) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("分组编号不能为空！")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            if (fullPrivateKey.isEmpty()) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("密钥内容不能为空！")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }

            tempUserCryptoKey = userKeyStr.toCharArray();
            final String userInputGroupNo = groupNoInput;

            new Thread(() -> {
                try {
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    //✅传入用户手动填写分组编号保存进密钥条目
                    mgr.addPrivateKey(fullPrivateKey, tempUserCryptoKey, userInputGroupNo);

                    runOnUiThread(() -> {
                        appendLog(userInputGroupNo, "", "✅密钥添加完成");
                        refreshCipherKeyList();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        appendLog("", "", "❌添加密钥失败：" + e.getMessage());
                    });
                }
            }).start();
        });
    }


    //查看数据库日志弹窗，修复日期特殊字符，增加try‑catch
    private void showViewLogDialog() {
        new Thread(() -> {
            String showText;
            try {
                List<TaskLogItem> logList = AppLogDbHelper.getInstance(MainActivity.this).queryAllLogDesc();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss", Locale.getDefault());
                StringBuilder sb = new StringBuilder();
                for (TaskLogItem item : logList) {
                    String timeStr = sdf.format(new Date(item.timeMs));
                    sb.append("[").append(timeStr).append("]");
                    if(item.keyNo != null){
                        sb.append("[").append(item.keyNo).append("]");
                    }
                    sb.append(item.content);
                    sb.append("\n");
                }
                showText = sb.length() == 0 ? "暂无日志记录" : sb.toString();
            } catch (Exception e) {
                showText = "读取日志异常：" + e.getMessage();
            }
            final String finalShow = showText;
            runOnUiThread(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_view_log, null);
                TextView tvLogContent = v.findViewById(R.id.tv_dialog_log_content);
                tvLogContent.setText(finalShow);
                builder.setView(v);
                builder.setPositiveButton("关闭", null);
                builder.show();
            });
        }).start();
    }

    //解密密码弹窗，密码预校验
    private void showDecryptPasswordDialog(final PasswordCallback callback) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_decrypt_password, null);
        final EditText etPwd = view.findViewById(R.id.et_decrypt_pwd);
        builder.setView(view);
        builder.setPositiveButton("确认", null);
        builder.setNegativeButton("取消", null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final char[] pwdCharArray = etPwd.getText().toString().toCharArray();
            if (keyListItemList.isEmpty()) {
                dialog.dismiss();
                callback.onInputPwd(pwdCharArray);
                return;
            }
            final String firstCipher = keyListItemList.get(0).cipherText;
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
                runOnUiThread(() -> {
                    if (result) {
                        dialog.dismiss();
                        callback.onInputPwd(pwdCharArray);
                    } else {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("密码错误")
                                .setMessage("解密密码校验失败，请核对密码后重新输入！")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }).start();
        });
    }

    private interface PasswordCallback {
        void onInputPwd(char[] pwd);
    }

    /** 刷新密钥列表，使用永久keyId */
    private void refreshCipherKeyList() {
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();
                List<Long> customSortIds = mgr.getCustomSortIdList();

                //✅按照用户自定义顺序重新排序
                List<KeyStoreManager.KeyEntry> sortedEntryList = new ArrayList<>();
                //1、先放自定义排序中存在的id
                for(Long sortId : customSortIds){
                    for(KeyStoreManager.KeyEntry e : originEntryList){
                        if(e.id == sortId){
                            sortedEntryList.add(e);
                            break;
                        }
                    }
                }
                //2、追加不在自定义排序里面的新密钥（兼容旧数据、新增条目）
                for(KeyStoreManager.KeyEntry e : originEntryList){
                    boolean exist = false;
                    for(KeyStoreManager.KeyEntry s : sortedEntryList){
                        if(s.id == e.id){
                            exist = true;
                            break;
                        }
                    }
                    if(!exist){
                        sortedEntryList.add(e);
                    }
                }

                keyListItemList.clear();
                for (int i = 0; i < sortedEntryList.size(); i++) {
                    KeyStoreManager.KeyEntry entry = sortedEntryList.get(i);
                    KeyListItem item = new KeyListItem();
                    item.keyId = entry.id;
                    item.seqNo = i + 1;
                    item.displayNo = String.format(Locale.getDefault(), "%03d", entry.id);
                    item.userGroupNo = entry.userGroupNo;
                    item.cipherText = entry.cipherText;
                    item.isUsable = true;
                    item.isChecked = true;
                    keyListItemList.add(item);
                }
                runOnUiThread(() -> {
                    if (keyAdapter == null) {
                        keyAdapter = new ArrayAdapter<KeyListItem>(MainActivity.this, R.layout.item_cipher_key, keyListItemList) {
                            @Override
                            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                                CheckedTextView tv = (CheckedTextView) super.getView(position, convertView, parent);
                                KeyListItem it = getItem(position);
                                String showNo;
                                if(it.userGroupNo != null && !it.userGroupNo.isEmpty()){
                                    showNo = it.userGroupNo;
                                }else{
                                    showNo = it.displayNo;
                                }
                                tv.setText(showNo + " · " + it.cipherText);
                                tv.setChecked(it.isChecked);
                                if (!it.isUsable) {
                                    tv.setBackgroundColor(0xFFFFCDD2);
                                } else {
                                    tv.setBackgroundColor(0x00000000);
                                }
                                return tv;
                            }
                        };
                        lvCipherKeys.setAdapter(keyAdapter);
                    } else {
                        keyAdapter.notifyDataSetChanged();
                    }
                    refreshCheckedStatText();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "❌刷新密钥列表异常：" + e.getMessage()));
            }
        }).start();
    }


    /** 删除选中密钥，倒序删除 */
    private void removeSelectedKeys() {
        final List<Integer> deletePosList = new ArrayList<>();
        for (int i = 0; i < keyListItemList.size(); i++) {
            KeyListItem item = keyListItemList.get(i);
            if (item.isChecked) {
                deletePosList.add(i);
            }
        }
        if (deletePosList.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage("请先在列表勾选需要删除的密钥")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定删除选中 " + deletePosList.size() + " 条密钥？该操作不可恢复！")
                .setPositiveButton("确认删除", (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        for (int i = deletePosList.size() - 1; i >= 0; i--) {
                            int pos = deletePosList.get(i);
                            mgr.removeByIndex(pos);
                        }
                        runOnUiThread(() -> {
                            appendLog("", "", "✅已删除选中 " + deletePosList.size() + " 条密钥");
                            refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> appendLog("", "", "❌删除选中密钥异常：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    /**清空全部密钥 */
    private void showConfirmClearAllKey() {
        new AlertDialog.Builder(this)
                .setTitle("⚠确认清空全部密钥")
                .setMessage("将删除本地全部保存的密钥密文，不可恢复！")
                .setPositiveButton("确认清空", (d, w) -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                        mgr.clearAllKeys();
                        runOnUiThread(() -> {
                            appendLog("", "", "✅全部密钥已清空");
                            refreshCipherKeyList();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> appendLog("", "", "❌清空密钥异常：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showConfirmClearLogDb() {
        new Thread(() -> {
            AppLogDbHelper.getInstance(MainActivity.this).clearAllLog();
            runOnUiThread(() -> appendLog("", "", "✅数据库日志已全部清空"));
        }).start();
    }

    /** 自动清除配置弹窗，分钟/小时/天 */
    private void openSysScheduleDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_sys_auto_clear, null);

        final Switch swAutoEnable = view.findViewById(R.id.sw_auto_enable);
        final EditText etVal = view.findViewById(R.id.et_auto_clear_val);
        final Spinner spUnit = view.findViewById(R.id.sp_time_unit);
        final TextView tvStatus = view.findViewById(R.id.tv_auto_clear_status);

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, unitArray);
        spUnit.setAdapter(unitAdapter);

        //读取：开关状态 + 用户保存的时长（开关关闭时长也保留）
        boolean isEnable = spAppSetting.getBoolean("auto_clear_enable", false);
        long oldMs = spAppSetting.getLong("auto_clear_ms", 0);

        //回填UI
        swAutoEnable.setChecked(isEnable);
        if (oldMs > 0) {
            if (oldMs >= 1000L * 60 * 60 * 24) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60 * 24)));
                spUnit.setSelection(2);
            } else if (oldMs >= 1000L * 60 * 60) {
                etVal.setText(String.valueOf(oldMs / (1000L * 60 * 60)));
                spUnit.setSelection(1);
            } else {
                etVal.setText(String.valueOf(oldMs / (1000L * 60)));
                spUnit.setSelection(0);
            }
        }

        if(isEnable){
            tvStatus.setText("当前：已启用，到达时长会自动清除私钥");
            etVal.setEnabled(true);
            spUnit.setEnabled(true);
        }else{
            //✅修正描述”
            tvStatus.setText("当前：已禁用（配置已保存，不会校验清除；系统时间持续流逝）");
            etVal.setEnabled(false);
            spUnit.setEnabled(false);
        }

        //开关监听
        swAutoEnable.setOnCheckedChangeListener((buttonView, checked) -> {
            etVal.setEnabled(checked);
            spUnit.setEnabled(checked);
            if(checked){
                tvStatus.setText("当前：已启用，到达时长会自动清除私钥");
            }else{
                tvStatus.setText("当前：已禁用（配置已保存，不会校验清除；系统时间持续流逝）");
            }
        });


        final AlertDialog dialog = builder.setView(view)
                .setPositiveButton("保存", (d, i) -> {})
                .setNegativeButton("关闭", (d, i) -> d.dismiss())
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean newEnable = swAutoEnable.isChecked();

            if(!newEnable){
                //====开关关闭：仅标记为禁用，【不抹除auto_clear_ms时长配置】====
                spAppSetting.edit()
                        .putBoolean("auto_clear_enable", false)
                        .apply();
                //取消WorkManager，但是auto_clear_ms保留原值
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
                appendLog("", "", "✅已禁用自动清除（配置已保留）");
                dialog.dismiss();
                return;
            }

            //开关打开：需要有有效时长
            String valStr = etVal.getText().toString().trim();
            if(valStr.isEmpty()){
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("请输入时间数值，最小15！")
                        .setPositiveButton("知道了",null)
                        .show();
                return;
            }
            int val;
            try {
                val = Integer.parseInt(valStr);
            } catch (Exception e) {
                new AlertDialog.Builder(MainActivity.this).setMessage("请输入合法数字").setPositiveButton("OK", null).show();
                return;
            }
            int selPos = spUnit.getSelectedItemPosition();
            long oneUnitMs;
            long inputMinute;
            if (selPos == 0) {
                oneUnitMs = 60 * 1000L;
                inputMinute = val;
            } else if (selPos == 1) {
                oneUnitMs = 60 * 60 * 1000L;
                inputMinute = val * 60L;
            } else {
                oneUnitMs = 24 * 60 * 60 * 1000L;
                inputMinute = val * 24 * 60L;
            }
            if (inputMinute < MIN_ALLOW_MINUTE) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("输入限制")
                        .setMessage("最小设置15分钟！")
                        .setPositiveButton("确定", null).show();
                return;
            }

            long totalMs = val * oneUnitMs;
            long startTs = System.currentTimeMillis();
            spAppSetting.edit()
                    .putBoolean("auto_clear_enable", true)
                    .putLong("auto_clear_ms", totalMs)
                    .putLong("auto_clear_start_ts", startTs)
                    .apply();

            long periodMin = Math.max(inputMinute, MIN_ALLOW_MINUTE);
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AutoClearWorker.class, periodMin, TimeUnit.MINUTES).build();
            WorkManager.getInstance(getApplicationContext())
                    .enqueueUniquePeriodicWork(WORK_AUTO_CLEAR, ExistingPeriodicWorkPolicy.UPDATE, req);

            appendLog("", "", "✅自动清除设置完成：" + val + " " + unitArray[selPos]);
            dialog.dismiss();
        });
    }



    private void showSecurityWarningDialog() {
        new AlertDialog.Builder(this)
                .setTitle("安全风险提醒")
                .setMessage("1.本APP为工具，非硬件钱包\n2.Root设备禁止存放大额资产私钥\n3.解密密码仅内存使用，不会持久化保存\n4.自动清除受手机省电策略影响，APP打开会兜底执行")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ============ TaskLogCallback接口实现，同时写数据库+UI ============
//    Log.e("dd",finalMsg);
    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final String finalMsg = msg;
        Log.e("onLog","keyNo:"+keyNo+"type:"+type+"msg:"+msg);
        final long timeMs = System.currentTimeMillis();

        runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) {
                sb.append("[").append(safeKeyNo).append("]");
            }
            sb.append(finalMsg);
            String newLine = sb.toString() + "\n";
            tvLog.setText(tvLog.getText() + newLine);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });

        new Thread(() -> {
            AppLogDbHelper.getInstance(this).insertLog(safeKeyNo, safeType, finalMsg, timeMs);
        }).start();
    }

    /** 长按条目弹出排序菜单 */
    private void showSortMenu(final int pos){
        KeyListItem item = keyListItemList.get(pos);
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("操作："+ (item.userGroupNo!=null?item.userGroupNo:item.displayNo));
        String[] menus;
        if(pos == 0 && pos == keyListItemList.size()-1){
            //只有1条，不能移动
            menus = new String[]{"取消"};
        }else if(pos == 0){
            //第一条，只能下移
            menus = new String[]{"下移","取消"};
        }else if(pos == keyListItemList.size()-1){
            //最后一条，只能上移
            menus = new String[]{"上移","取消"};
        }else{
            menus = new String[]{"上移","下移","取消"};
        }
        builder.setItems(menus, (dialog, which) -> {
            int newPos = pos;
            if("上移".equals(menus[which])){
                newPos = pos - 1;
            }else if("下移".equals(menus[which])){
                newPos = pos + 1;
            }else{
                return;
            }
            //内存集合交换位置
            java.util.Collections.swap(keyListItemList, pos, newPos);
            keyAdapter.notifyDataSetChanged();

            //✅同步保存自定义排序id序列到SP
            List<Long> newSortIdList = new ArrayList<>();
            for(KeyListItem li : keyListItemList){
                newSortIdList.add(li.keyId);
            }
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            mgr.saveCustomSortIdList(newSortIdList);
            refreshCheckedStatText();
        });
        builder.show();
    }

        public void appendLog(String unusedKeyNo, String unusedType, String msg) {
        onLog(unusedKeyNo, unusedType, msg);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
        if (tempUserCryptoKey != null) {
            Arrays.fill(tempUserCryptoKey, (char) 0);
            tempUserCryptoKey = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //每次切回页面执行自动清除校验，锁屏点亮回到页面也会触发
        checkAutoClearBoot();
    }
}
