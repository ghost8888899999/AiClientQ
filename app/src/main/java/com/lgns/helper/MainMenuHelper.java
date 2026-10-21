package com.lgns.helper;

import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        View popView = LayoutInflater.from(activity).inflate(R.layout.layout_pop_menu, null);

        popView.findViewById(R.id.menu_key_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new android.content.Intent(activity, PrivateKeyManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_group_manage).setOnClickListener(v -> {
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

        mPopupWindow = new PopupWindow(popView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        mPopupWindow.setOutsideTouchable(true);
        mPopupWindow.showAsDropDown(anchorView, 0, 4);
    }

    public void openSysConfig() {
        activity.setSkipRefreshOnResume();
        activity.startActivity(new android.content.Intent(activity, com.lgns.ui.activity.AutoClearConfigActivity.class));
    }
}
