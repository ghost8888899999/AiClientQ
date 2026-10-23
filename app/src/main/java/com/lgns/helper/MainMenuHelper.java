package com.lgns.helper;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

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

    public void showCollapseMenu(View anchorView) {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
            return;
        }
        // 带临时 parent 保证根布局的尺寸属性能被解析（实际窗口宽度以下面的代码为准）
        View popView = LayoutInflater.from(activity)
                .inflate(R.layout.layout_pop_menu, new FrameLayout(activity), false);

        popView.findViewById(R.id.menu_key_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new android.content.Intent(activity, PrivateKeyManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_group_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new android.content.Intent(activity, GroupManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_view_log).setOnClickListener(v -> {
            activity.getDialogHelper().showViewLogDialog();
            dismissPopup();
        });

        popView.findViewById(R.id.menu_sys_config).setOnClickListener(v -> {
            openSysConfig();
            dismissPopup();
        });

        popView.findViewById(R.id.menu_championship_stats).setOnClickListener(v -> {
            activity.getDialogHelper().showChampionshipStatsDialog();
            dismissPopup();
        });

        popView.findViewById(R.id.menu_migrate_addresses).setOnClickListener(v -> {
            activity.getDialogHelper().showMigrateAddressesDialog();
            dismissPopup();
        });

        // 窗口比卡片四周多 14dp，留给 MaterialCardView 的投影，避免阴影被窗口边界裁掉
        int cardWidth = dp(212);
        int shadowPad = dp(14);
        mPopupWindow = new PopupWindow(popView, cardWidth + shadowPad * 2,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopupWindow.setOutsideTouchable(true);
        // 透明背景保证点外部可关闭
        mPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mPopupWindow.setAnimationStyle(R.style.PopMenuAnim);

        // 卡片右边缘对齐齿轮右边缘，卡片顶边距齿轮底部 6dp
        int dx = anchorView.getWidth() - cardWidth - shadowPad;
        mPopupWindow.showAsDropDown(anchorView, dx, dp(6) - shadowPad);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public void openSysConfig() {
        activity.setSkipRefreshOnResume();
        activity.startActivity(new android.content.Intent(activity, com.lgns.ui.activity.AutoClearConfigActivity.class));
    }
}
