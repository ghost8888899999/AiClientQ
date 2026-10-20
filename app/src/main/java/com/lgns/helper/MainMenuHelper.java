package com.lgns.helper;

import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.lgns.R;
import com.lgns.ui.activity.GroupManageActivity;
import com.lgns.ui.activity.MainActivity;
import com.lgns.ui.activity.PrivateKeyManageActivity;

public class MainMenuHelper {
    private final MainActivity activity;
    private final SharedPreferences spAppSetting;
    private PopupWindow mPopupWindow;

    public MainMenuHelper(MainActivity activity, SharedPreferences spAppSetting) {
        this.activity = activity;
        this.spAppSetting = spAppSetting;
    }

    public void dismissPopup() {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
    }

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

    public void showCollapseMenu(View anchorView) {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
            return;
        }
        View popView = LayoutInflater.from(activity).inflate(R.layout.layout_pop_menu, null);

        bindGroupToggle(popView, R.id.group_key_title, R.id.group_key_container, R.id.iv_arrow_key);
        bindGroupToggle(popView, R.id.group_mgmt_title, R.id.group_mgmt_container, R.id.iv_arrow_mgmt);
        bindGroupToggle(popView, R.id.group_log_title, R.id.group_log_container, R.id.iv_arrow_log);
        bindGroupToggle(popView, R.id.group_auto_title, R.id.group_auto_container, R.id.iv_arrow_auto);
        bindGroupToggle(popView, R.id.group_mode_title, R.id.group_mode_container, R.id.iv_arrow_mode);
        bindGroupToggle(popView, R.id.group_champ_title, R.id.group_champ_container, R.id.iv_arrow_champ);

        bindMenuItem(popView, R.id.menu_add_key, "私钥管理", () -> {
            activity.startActivity(new android.content.Intent(activity, PrivateKeyManageActivity.class));
        });

        bindMenuItem(popView, R.id.menu_add_group, "分组管理", () -> {
            activity.startActivity(new android.content.Intent(activity, GroupManageActivity.class));
        });

        bindMenuItem(popView, R.id.menu_view_log, "查看日志(最新优先)", () -> activity.getDialogHelper().showViewLogDialog());

        bindMenuItem(popView, R.id.menu_sys_schedule_setting, "自动清除配置", () -> {
            activity.startActivity(new android.content.Intent(activity, com.lgns.ui.activity.AutoClearConfigActivity.class));
        });

        setupModeMenu(popView);

        bindMenuItem(popView, R.id.menu_championship_stats, "查看锦标赛统计", () -> activity.getDialogHelper().showChampionshipStatsDialog());
        bindMenuItem(popView, R.id.menu_migrate_addresses, "补全密钥地址(旧数据迁移)", () -> activity.getDialogHelper().showMigrateAddressesDialog());

        mPopupWindow = new PopupWindow(popView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.showAsDropDown(anchorView, 0, 4);
    }

    private void bindGroupToggle(View popView, int titleId, int containerId, int arrowId) {
        View container = popView.findViewById(containerId);
        ImageView arrow = popView.findViewById(arrowId);
        popView.findViewById(titleId).setOnClickListener(v -> toggleGroup(container, arrow));
    }

    private void bindMenuItem(View popView, int menuId, String text, Runnable action) {
        LinearLayout itemLayout = popView.findViewById(menuId);
        TextView tv = (TextView) itemLayout.getChildAt(1);
        tv.setText(text);
        itemLayout.setOnClickListener(v -> {
            action.run();
            dismissPopup();
        });
    }

    private void setupModeMenu(View popView) {
        LinearLayout menuFlex = popView.findViewById(R.id.menu_mode_flexible);
        LinearLayout menuTogether = popView.findViewById(R.id.menu_mode_together);
        ImageView ivCheckFlex = menuFlex.findViewById(R.id.iv_mode_check_flex);
        ImageView ivCheckTogether = menuTogether.findViewById(R.id.iv_mode_check_together);
        TextView tvFlex = (TextView) menuFlex.getChildAt(1);
        TextView tvTogether = (TextView) menuTogether.getChildAt(1);
        int mode = spAppSetting.getInt("play_mode", MainActivity.MODE_FLEXIBLE);

        if (mode == MainActivity.MODE_FLEXIBLE) {
            tvFlex.setText("能打就打 (过滤不可执行账号)");
            tvTogether.setText("等齐再打 (全部模拟通过才提交)");
            ivCheckFlex.setColorFilter(0xFF4CAF50);
            ivCheckTogether.setColorFilter(0xFFBDBDBD);
        } else {
            tvFlex.setText("能打就打 (过滤不可执行账号)");
            tvTogether.setText("等齐再打 (全部模拟通过才提交)");
            ivCheckFlex.setColorFilter(0xFFBDBDBD);
            ivCheckTogether.setColorFilter(0xFF4CAF50);
        }

        menuFlex.setOnClickListener(v -> {
            spAppSetting.edit().putInt("play_mode", MainActivity.MODE_FLEXIBLE).apply();
            activity.appendLog("", "", "已切换入场模式：能打就打，自动过滤不可执行账号");
            dismissPopup();
        });
        menuTogether.setOnClickListener(v -> {
            spAppSetting.edit().putInt("play_mode", MainActivity.MODE_TOGETHER).apply();
            activity.appendLog("", "", "已切换入场模式：等齐再打，全部模拟通过才提交交易");
            dismissPopup();
        });
    }
}
