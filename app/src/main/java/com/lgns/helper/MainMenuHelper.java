package com.lgns.helper;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupWindow;

import com.lgns.R;
import com.lgns.ui.activity.AddressManageActivity;
import com.lgns.ui.activity.BatchGasFundActivity;
import com.lgns.ui.activity.BatchTransferActivity;
import com.lgns.ui.activity.GroupManageActivity;
import com.lgns.ui.activity.MainActivity;
import com.lgns.ui.activity.PayerManageActivity;
import com.lgns.ui.activity.PrivateKeyManageActivity;

/**
 * 主界面齿轮下拉菜单：一级项直接跳页，充值/转账两个分组可展开，
 * 其余工具项（看日志/系统配置/结算统计/迁移地址）以弹窗形式打开。
 *
 * <p>为什么要两个分组互斥展开：菜单本身是 WRAP_CONTENT 的 PopupWindow，
 * 两个分组同时展开会把窗口顶出屏幕（小屏下下面的项直接点不到），
 * 所以展开一个就强制收起另一个。</p>
 *
 * <p>尺寸约定：窗口宽度固定为卡片宽 212dp 加两侧各 14dp 投影边距，
 * 这个数值必须与 {@code layout_pop_menu.xml} 里的卡片尺寸同步修改，否则阴影会被裁掉。</p>
 */
public class MainMenuHelper {
    private final MainActivity activity;
    /** 应用设置 SP：构造时传入用于保持与其他页面的同一份实例，当前菜单逻辑未直接读写它 */
    private final SharedPreferences spAppSetting;
    private PopupWindow mPopupWindow;

    /** 菜单卡片宽度（dp），取值必须与 {@code layout_pop_menu.xml} 中的固定尺寸一致 */
    private static final int CARD_WIDTH_DP = 212;
    /** 卡片四周留给 MaterialCardView 投影的边距（dp），单边值 */
    private static final int SHADOW_PAD_DP = 14;

    /**
     * @param activity     宿主页面，用于取 Context、起 Activity 与拿弹窗助手
     * @param spAppSetting 应用设置 SP，与其他页面共用同一份实例（当前菜单未直接读写）
     */
    public MainMenuHelper(MainActivity activity, SharedPreferences spAppSetting) {
        this.activity = activity;
        this.spAppSetting = spAppSetting;
    }

    /** 关闭菜单；未创建或已关闭时无副作用，可直接重复调用 */
    public void dismissPopup() {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
    }

    /**
     * 在齿轮按钮下方弹出折叠菜单。
     *
     * <p>流程：如果菜单已在显示，本方法只负责关闭（齿轮当成开关用）；
     * 否则 inflate 布局、逐项绑定点击、最后按「卡片右边缘对齐齿轮右边缘」定位弹窗。</p>
     *
     * <p>跳页类入口统一先调 {@code setRefreshLocalOnlyOnResume()}，
     * 表示回主界面时只重读本地数据、不重新拉链上状态，避免返回后发一串 RPC 请求。</p>
     *
     * @param anchorView 齿轮按钮，既作位置锚点也用于计算水平偏移
     */
    public void showCollapseMenu(View anchorView) {
        if (mPopupWindow != null && mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
            return;
        }
        // 带临时 parent 保证根布局的尺寸属性能被解析（实际窗口宽度以下面的代码为准）
        View popView = LayoutInflater.from(activity)
                .inflate(R.layout.layout_pop_menu, new FrameLayout(activity), false);

        // ---- 一级项：分组管理置顶、私钥管理 ----
        popView.findViewById(R.id.menu_group_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, GroupManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_key_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, PrivateKeyManageActivity.class));
            dismissPopup();
        });

        // ---- 两个可展开分组：同时只展开一个，避免菜单顶到屏幕外 ----
        final View rechargeChildren = popView.findViewById(R.id.menu_recharge_children);
        final ImageView rechargeArrow = popView.findViewById(R.id.iv_recharge_arrow);
        final View transferChildren = popView.findViewById(R.id.menu_transfer_children);
        final ImageView transferArrow = popView.findViewById(R.id.iv_transfer_arrow);

        // 折叠态箭头朝右(-90°)，展开态朝下
        rechargeArrow.setRotation(-90f);
        transferArrow.setRotation(-90f);

        popView.findViewById(R.id.menu_recharge_group).setOnClickListener(v -> {
            boolean expand = rechargeChildren.getVisibility() != View.VISIBLE;
            setExpanded(rechargeChildren, rechargeArrow, expand);
            if (expand) setExpanded(transferChildren, transferArrow, false);
            resizePopup();
        });

        popView.findViewById(R.id.menu_transfer_group).setOnClickListener(v -> {
            boolean expand = transferChildren.getVisibility() != View.VISIBLE;
            setExpanded(transferChildren, transferArrow, expand);
            if (expand) setExpanded(rechargeChildren, rechargeArrow, false);
            resizePopup();
        });

        // ---- 充值管理子项 ----
        // 批量充值：直接复用首页「批量充值」按钮的入口（同一个确认页）
        popView.findViewById(R.id.menu_batch_recharge).setOnClickListener(v -> {
            activity.getDialogHelper().showBatchFundDialog();
            dismissPopup();
        });

        popView.findViewById(R.id.menu_batch_gas_fund).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, BatchGasFundActivity.class));
            dismissPopup();
        });

        // ---- 转账管理子项 ----
        popView.findViewById(R.id.menu_address_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, AddressManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_payer_manage).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, PayerManageActivity.class));
            dismissPopup();
        });

        popView.findViewById(R.id.menu_batch_transfer).setOnClickListener(v -> {
            activity.setRefreshLocalOnlyOnResume();
            activity.startActivity(new Intent(activity, BatchTransferActivity.class));
            dismissPopup();
        });

        // ---- 工具与信息（行为不变） ----
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
        int cardWidth = dp(CARD_WIDTH_DP);
        int shadowPad = dp(SHADOW_PAD_DP);
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

    /**
     * 展开/收起子容器，并同步箭头角度。
     *
     * @param children 子项容器，为空时直接忽略
     * @param arrow    左侧箭头图标，收起时 -90°（朝右）、展开时 0°（朝下），旋转 120ms
     * @param expand   true 展开、false 收起
     */
    private void setExpanded(View children, ImageView arrow, boolean expand) {
        if (children == null) return;
        children.setVisibility(expand ? View.VISIBLE : View.GONE);
        if (arrow != null) {
            arrow.animate().rotation(expand ? 0f : -90f).setDuration(120).start();
        }
    }

    /**
     * 展开后内容高度变了：PopupWindow 高度是 WRAP_CONTENT，
     * 必须显式 update 一次才会重新测量，否则子项会被窗口底边裁掉。
     */
    private void resizePopup() {
        View content = mPopupWindow == null ? null : mPopupWindow.getContentView();
        if (content == null) return;
        content.post(() -> {
            if (mPopupWindow != null && mPopupWindow.isShowing()) {
                mPopupWindow.update(dp(CARD_WIDTH_DP) + dp(SHADOW_PAD_DP) * 2,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    /** dp 转当前屏幕像素，四舍五入避免累计误差导致窗口宽度差 1px */
    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 打开系统配置页（自动清理/定时等）。
     *
     * <p>用 {@code setSkipRefreshOnResume} 而非普通刷新：配置页只改本地首选项，
     * 回主界面时链上数据不可能变化，重拉一遍纯属浪费一次 RPC 配额。</p>
     */
    public void openSysConfig() {
        activity.setSkipRefreshOnResume();
        activity.startActivity(new Intent(activity, com.lgns.ui.activity.AutoClearConfigActivity.class));
    }
}
