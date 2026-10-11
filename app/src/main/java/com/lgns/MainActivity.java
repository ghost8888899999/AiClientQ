package com.lgns;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
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

    //密钥列表Model
    static class KeyListItem {
        public long keyId; //全局永久ID，删除其他条目，此id不变
        public int seqNo; //内存列表位置（可变，仅UI排序用）
        public String displayNo; //格式化001，使用keyId
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
        setupGearMenu();
        setupButtonEvents();
        setupListViewCheckListener();

        //====APP打开兜底自动清除校验====
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

    /** 统计勾选数量，遍历model，不再依赖ListView内部勾选集合 */
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

    /** APP打开兜底校验自动清除计时，解决WorkManager被省电杀掉不执行 */
    private void checkAutoClearBoot() {
        long saveTotalMs = spAppSetting.getLong("auto_clear_ms", 0);
        long saveStartTs = spAppSetting.getLong("auto_clear_start_ts", 0);
        if (saveTotalMs > 0 && saveStartTs > 0) {
            long now = System.currentTimeMillis();
            if (now - saveStartTs >= saveTotalMs) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            mgr.clearAllKeys();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    appendLog("", "", "⚠检测到自动清除计时到期，打开APP自动清空全部私钥！");
                                    refreshCipherKeyList();
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    }
                }).start();
                spAppSetting.edit()
                        .putLong("auto_clear_ms", 0)
                        .putLong("auto_clear_start_ts", 0)
                        .apply();
                WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
            }
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
        btnSettingGear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCollapseMenu(v);
            }
        });
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
        popView.findViewById(R.id.group_key_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGroup(groupKeyContainer, ivArrowKey);
            }
        });

        final View groupLogContainer = popView.findViewById(R.id.group_log_container);
        final ImageView ivArrowLog = popView.findViewById(R.id.iv_arrow_log);
        popView.findViewById(R.id.group_log_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGroup(groupLogContainer, ivArrowLog);
            }
        });

        final View groupAutoContainer = popView.findViewById(R.id.group_auto_container);
        final ImageView ivArrowAuto = popView.findViewById(R.id.iv_arrow_auto);
        popView.findViewById(R.id.group_auto_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGroup(groupAutoContainer, ivArrowAuto);
            }
        });

        final View groupModeContainer = popView.findViewById(R.id.group_mode_container);
        final ImageView ivArrowMode = popView.findViewById(R.id.iv_arrow_mode);
        popView.findViewById(R.id.group_mode_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGroup(groupModeContainer, ivArrowMode);
            }
        });

        //二级菜单项绑定点击事件
        TextView menuAddKey = popView.findViewById(R.id.menu_add_key);
        menuAddKey.setText("添加密钥");
        menuAddKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddKeyDialog();
                mPopupWindow.dismiss();
            }
        });

        TextView menuRefreshList = popView.findViewById(R.id.menu_refresh_list);
        menuRefreshList.setText("刷新密钥列表");
        menuRefreshList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshCipherKeyList();
                mPopupWindow.dismiss();
            }
        });

        TextView menuRemoveSel = popView.findViewById(R.id.menu_remove_selected);
        menuRemoveSel.setText("删除选中密钥");
        menuRemoveSel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeSelectedKeys();
                mPopupWindow.dismiss();
            }
        });

        TextView menuClearAllKey = popView.findViewById(R.id.menu_clear_all_key);
        menuClearAllKey.setText("清空全部密钥");
        menuClearAllKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfirmClearAllKey();
                mPopupWindow.dismiss();
            }
        });

        TextView menuViewLog = popView.findViewById(R.id.menu_view_log);
        menuViewLog.setText("查看日志(最新优先)");
        menuViewLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showViewLogDialog();
                mPopupWindow.dismiss();
            }
        });

        TextView menuClearLogDb = popView.findViewById(R.id.menu_clear_log_db);
        menuClearLogDb.setText("清空数据库全部日志");
        menuClearLogDb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConfirmClearLogDb();
                mPopupWindow.dismiss();
            }
        });

        TextView menuSysSchedule = popView.findViewById(R.id.menu_sys_schedule_setting);
        menuSysSchedule.setText("自动清除配置");
        menuSysSchedule.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSysScheduleDialog();
                mPopupWindow.dismiss();
            }
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
        menuModeFlex.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentPlayMode = MODE_FLEXIBLE;
                spAppSetting.edit()
                        .putInt("play_mode", currentPlayMode)
                        .apply();
                appendLog("", "", "✅已切换入场模式：能打就打，自动过滤不可执行账号");
                mPopupWindow.dismiss();
            }
        });

        //切换：等齐再打
        menuModeTogether.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentPlayMode = MODE_TOGETHER;
                spAppSetting.edit()
                        .putInt("play_mode", currentPlayMode)
                        .apply();
                appendLog("", "", "✅已切换入场模式：等齐再打，全部模拟通过才提交交易");
                mPopupWindow.dismiss();
            }
        });

        mPopupWindow = new PopupWindow(popView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.showAsDropDown(anchorView, 0, 4);
    }

    private void setupButtonEvents() {
        btnSecurityWarn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSecurityWarningDialog();
            }
        });

        //批量入场
        btnBatchEnter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDecryptPasswordDialog(new PasswordCallback() {
                    @Override
                    public void onInputPwd(char[] pwdCharArray) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    List<KeyListItem> selectedList = getSelectedKeyItem();
                                    ArenaTask.batchEnterAllResume(getApplicationContext(), MainActivity.this, currentPlayMode, selectedList, tempUserCryptoKey);
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "ENTER", "❌批量入场外层异常：" + e.getMessage());
                                        }
                                    });
                                } finally {
                                    Arrays.fill(pwdCharArray, (char) 0);
                                }
                            }
                        }).start();
                    }
                });
            }
        });

        //批量领取
        btnBatchClaim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDecryptPasswordDialog(new PasswordCallback() {
                    @Override
                    public void onInputPwd(char[] pwdCharArray) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    List<KeyListItem> selectedList = getSelectedKeyItem();
                                    ArenaTask.batchClaimAllResume(getApplicationContext(), MainActivity.this, currentPlayMode, selectedList, tempUserCryptoKey);
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "CLAIM", "❌批量领取外层异常：" + e.getMessage());
                                        }
                                    });
                                } finally {
                                    Arrays.fill(pwdCharArray, (char) 0);
                                }
                            }
                        }).start();
                    }
                });
            }
        });

        //完整工作流
        btnFullWorkflow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDecryptPasswordDialog(new PasswordCallback() {
                    @Override
                    public void onInputPwd(char[] pwdCharArray) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    List<KeyListItem> selectedList = getSelectedKeyItem();
                                    ArenaTask.fullArenaWorkflowResume(getApplicationContext(), MainActivity.this, 2, currentPlayMode, selectedList, tempUserCryptoKey);
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "", "❌完整工作流外层异常：" + e.getMessage());
                                        }
                                    });
                                } finally {
                                    Arrays.fill(pwdCharArray, (char) 0);
                                }
                            }
                        }).start();
                    }
                });
            }
        });

        //重置任务进度
        btnResetTask.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArenaTask.resetTaskProgress(getApplicationContext());
                appendLog("", "", "✅任务进度已重置");
            }
        });
    }

    /** 获取选中条目，读取model的isChecked，不再依赖ListView */
    private List<KeyListItem> getSelectedKeyItem() {
        List<KeyListItem> res = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) {
                res.add(item);
            }
        }
        return res;
    }

    //添加密钥弹窗【眼睛：默认隐藏明文】
    private void showAddKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_key, null);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etUserCrypto = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEyeToggle = view.findViewById(R.id.iv_eye_toggle);

        final boolean[] isPasswordVisible = {false};
        ivEyeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isPasswordVisible[0] = !isPasswordVisible[0];
                if (isPasswordVisible[0]) {
                    etUserCrypto.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
                    ivEyeToggle.setImageResource(R.drawable.ic_eye_on_svg);
                } else {
                    etUserCrypto.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    ivEyeToggle.setImageResource(R.drawable.ic_eye_off_svg);
                }
            }
        });

        builder.setView(view);
        builder.setPositiveButton("确认添加", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String part1 = etPk1.getText().toString().trim();
                String part2 = etPk2.getText().toString().trim();
                String fullPrivateKey = (part1 + part2).trim();
                String userKeyStr = etUserCrypto.getText().toString().trim();
                tempUserCryptoKey = userKeyStr.toCharArray();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            mgr.addPrivateKey(fullPrivateKey, tempUserCryptoKey);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    appendLog("", "", "✅密钥添加完成");
                                    refreshCipherKeyList();
                                }
                            });
                        } catch (Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    appendLog("", "", "❌添加密钥失败：" + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    //查看数据库日志弹窗，按时间倒序，最新优先
    private void showViewLogDialog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String showText;
                try {
                    List<TaskLogItem> logList = AppLogDbHelper.getInstance(MainActivity.this).queryAllLogDesc();
                    //使用标准半角短横线
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    StringBuilder sb = new StringBuilder();
                    for (TaskLogItem item : logList) {
                        String timeStr = sdf.format(new Date(item.timeMs));
                        sb.append("[").append(timeStr).append("] ");
                        sb.append("[").append(item.keyNo).append("] ");
                        sb.append("[").append(item.logType).append("] ");
                        sb.append(item.content);
                        sb.append("\n");
                    }
                    if (sb.length() == 0) {
                        showText = "暂无日志记录";
                    } else {
                        showText = sb.toString();
                    }
                } catch (Exception e) {
                    showText = "读取日志异常：" + e.getMessage();
                }
                final String finalShow = showText;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                        View v = LayoutInflater.from(MainActivity.this).inflate(R.layout.dialog_view_log, null);
                        TextView tvLogContent = v.findViewById(R.id.tv_dialog_log_content);
                        tvLogContent.setText(finalShow);
                        builder.setView(v);
                        builder.setPositiveButton("关闭", null);
                        builder.show();
                    }
                });
            }
        }).start();
    }


    //解密密码弹窗，执行任务才弹出
    private void showDecryptPasswordDialog(final PasswordCallback callback) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final View view = LayoutInflater.from(this).inflate(R.layout.dialog_decrypt_password, null);
        final EditText etPwd = view.findViewById(R.id.et_decrypt_pwd);
        builder.setView(view);
        builder.setPositiveButton("确认", null);
        builder.setNegativeButton("取消", null);
        final AlertDialog dialog = builder.create();
        dialog.show();

        //重写确认按钮，不要dismiss，密码错误保留弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final char[] pwdCharArray = etPwd.getText().toString().toCharArray();
                //没有密钥，直接放行
                if(keyListItemList.isEmpty()){
                    dialog.dismiss();
                    callback.onInputPwd(pwdCharArray);
                    return;
                }
                //拿第一条密文做解密测试，校验密码正确性
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean testOk;
                        try{
                            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                            String firstCipher = keyListItemList.get(0).cipherText;
                            //尝试解密第一条，只做测试，丢弃结果
                            mgr.testDecrypt(firstCipher, pwdCharArray);
                            testOk = true;
                        }catch (Exception e){
                            testOk = false;
                        }
                        final boolean result = testOk;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if(result){
                                    //密码正确，关闭弹窗，执行任务
                                    dialog.dismiss();
                                    callback.onInputPwd(pwdCharArray);
                                }else{
                                    //密码错误，不清空输入框，弹窗提示，密码弹窗不关闭，让用户继续输入
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("密码错误")
                                            .setMessage("解密密码校验失败，请核对密码后重新输入！")
                                            .setPositiveButton("知道了",null)
                                            .show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private interface PasswordCallback {
        void onInputPwd(char[] pwd);
    }

    /**
     * 刷新密钥列表，序号001；model默认全部勾选；不再调用lvCipherKeys.setItemChecked
     */
    private void refreshCipherKeyList() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                    List<KeyStoreManager.KeyEntry> entryList = mgr.loadKeyEntryList();
                    keyListItemList.clear();
                    for (int i = 0; i < entryList.size(); i++) {
                        KeyStoreManager.KeyEntry entry = entryList.get(i);
                        KeyListItem item = new KeyListItem();
                        item.keyId = entry.id; //永久不变ID
                        item.seqNo = i + 1;
                        item.displayNo = String.format(Locale.getDefault(), "%03d", entry.id); //显示永久ID
                        item.cipherText = entry.cipherText;
                        item.isUsable = true;
                        item.isChecked = true;
                        keyListItemList.add(item);
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (keyAdapter == null) {
                                keyAdapter = new ArrayAdapter<KeyListItem>(MainActivity.this, R.layout.item_cipher_key, keyListItemList) {
                                    @Override
                                    public View getView(int position, View convertView, android.view.ViewGroup parent) {
                                        CheckedTextView tv = (CheckedTextView) super.getView(position, convertView, parent);
                                        KeyListItem it = getItem(position);
                                        tv.setText(it.displayNo + " · " + it.cipherText);
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
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("", "", "❌刷新密钥列表异常：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }


    /** 删除选中密钥：遍历model收集下标，倒序删除防止索引错乱 */
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
                .setPositiveButton("确认删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                                    //倒序删除
                                    for (int i = deletePosList.size() - 1; i >= 0; i--) {
                                        int pos = deletePosList.get(i);
                                        mgr.removeByIndex(pos);
                                    }
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "", "✅已删除选中 " + deletePosList.size() + " 条密钥");
                                            refreshCipherKeyList();
                                        }
                                    });
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "", "❌删除选中密钥异常：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**清空全部密钥 */
    private void showConfirmClearAllKey() {
        new AlertDialog.Builder(this)
                .setTitle("⚠确认清空全部密钥")
                .setMessage("将删除本地全部保存的密钥密文，不可恢复！")
                .setPositiveButton("确认清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
                                    mgr.clearAllKeys();
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "", "✅全部密钥已清空");
                                            refreshCipherKeyList();
                                        }
                                    });
                                } catch (Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            appendLog("", "", "❌清空密钥异常：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showConfirmClearLogDb() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppLogDbHelper.getInstance(MainActivity.this).clearAllLog();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        appendLog("", "", "✅数据库日志已全部清空");
                    }
                });
            }
        }).start();
    }

    /** 系统调度/自动清除配置弹窗完整实现 */
    private void openSysScheduleDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sys_auto_clear, null);
        EditText etVal = view.findViewById(R.id.et_auto_clear_val);
        Spinner spUnit = view.findViewById(R.id.sp_time_unit);
        TextView tvStatus = view.findViewById(R.id.tv_auto_clear_status);
        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, unitArray);
        spUnit.setAdapter(unitAdapter);

        long ms = spAppSetting.getLong("auto_clear_ms", 0);
        if (ms > 0) {
            if (ms >= 1000L * 60 * 60 * 24) {
                etVal.setText(String.valueOf(ms / (1000L * 60 * 60 * 24)));
                spUnit.setSelection(2);
            } else if (ms >= 1000L * 60 * 60) {
                etVal.setText(String.valueOf(ms / (1000L * 60 * 60)));
                spUnit.setSelection(1);
            } else {
                etVal.setText(String.valueOf(ms / (1000L * 60)));
                spUnit.setSelection(0);
            }
            tvStatus.setText("当前已开启自动清除");
        } else {
            tvStatus.setText("当前：关闭自动清除");
        }

        builder.setView(view);
        builder.setPositiveButton("保存设置", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String valStr = etVal.getText().toString().trim();
                if (valStr.isEmpty()) {
                    spAppSetting.edit()
                            .putLong("auto_clear_ms", 0)
                            .putLong("auto_clear_start_ts", 0)
                            .apply();
                    WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(WORK_AUTO_CLEAR);
                    appendLog("", "", "✅已关闭自动清除");
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
                            .setMessage("系统WorkManager最小周期为15分钟，请设置≥15分钟！")
                            .setPositiveButton("确定", null).show();
                    return;
                }
                long totalMs = val * oneUnitMs;
                spAppSetting.edit()
                        .putLong("auto_clear_ms", totalMs)
                        .putLong("auto_clear_start_ts", System.currentTimeMillis())
                        .apply();
                long periodMin = Math.max(inputMinute, MIN_ALLOW_MINUTE);
                PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                        AutoClearWorker.class, periodMin, TimeUnit.MINUTES
                ).build();
                WorkManager.getInstance(getApplicationContext())
                        .enqueueUniquePeriodicWork(WORK_AUTO_CLEAR, ExistingPeriodicWorkPolicy.UPDATE, req);
                appendLog("", "", "✅自动清除设置完成：" + val + " " + unitArray[selPos] + "；系统调度已启动，APP打开会兜底校验超时清除");
            }
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    private void showSecurityWarningDialog() {
        new AlertDialog.Builder(this)
                .setTitle("安全风险提醒")
                .setMessage("1.本APP为工具，非硬件钱包\n2.Root设备禁止存放大额资产私钥\n3.解密密码仅内存使用，不会持久化保存\n4.自动清除受手机省电策略影响，APP打开会兜底执行")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ============ TaskLogCallback接口实现 ==============
    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        //写入数据库持久化日志
        AppLogDbHelper.getInstance(this).insertLog(keyNo, type, msg);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                String keyNoTrimmed = (keyNo == null) ? "" : keyNo.trim();
                String typeTrimmed = (type == null) ? "" : type.trim();
                if (typeTrimmed.length() > 0) {
                    if (keyNoTrimmed.length() == 0) {
                        sb.append("[").append(typeTrimmed).append("] ");
                    } else {
                        sb.append("[").append(keyNoTrimmed).append("][").append(typeTrimmed).append("] ");
                    }
                }
                sb.append(msg);
                String newLine = sb.toString() + "\n";
                tvLog.setText(tvLog.getText() + newLine);
                tvLog.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
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
}
