package com.lgns.batch;

import android.content.Context;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整流程编排：把「充值 → 入场 → 领取」串成一次可中断、可续跑的批量作业。
 *
 * <p>本类只做步骤调度、等待与取消检查，具体动作全部委托给
 * {@link BatchFundTask}、{@link BatchEnterTask}、{@link BatchClaimTask}，
 * 自身不直接发交易，也不读写进度存储。</p>
 *
 * <p>每一步都会检查取消标记：用户中途取消时只停止后续步骤，已完成的部分不回滚。</p>
 */
public class BatchWorkflowTask {
    /** 充值阶段结束后的额外等待：只补节点同步延迟，交易本身已在上一步等过回执 */
    private static final long WAIT_AFTER_FUND_MS = 5000L;

    /** 入场阶段结束后的额外等待：等轮次结算状态在轮换到的节点上可见 */
    private static final long WAIT_AFTER_ENTER_MS = 5000L;

    /** 可取消等待的检查粒度：按下取消后最迟这么久就能退出，不必等整段睡完 */
    private static final long WAIT_SLICE_MS = 250L;

    // region 工作流入口

    /**
     * 完整流程：充值 → 等待入账 → 入场 → 等待结算 → 领取。
     *
     * <p>两处等待等的是「节点间可见性」，不是交易本身：{@link BatchFundTask} 与
     * {@link BatchEnterTask} 对每笔交易都会等到回执才返回，回到这里时交易已经上链。
     * 留几秒只是给轮换到的其它 RPC 节点同步区块——不然子号可能读到旧余额被误判“不足”跳过入场，
     * 或者领到还没结算完的甲子俸。等待切成 {@value #WAIT_SLICE_MS}ms 小段，期间可随时响应取消。</p>
     *
     * <p>{@code claimMaxRetry} 透传给领取阶段：甲子俸一页 64 个，序号多的账号需要多笔。</p>
     */
    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry,
                                               int playMode, List<KeyListItem> selectedList,
                                               char[] sessionCryptoKey) throws Exception {
        callback.onLog("", "FUND", "完整流程 · 第1步：批量充值");
        BatchFundTask.executeAutoFund(selectedList, sessionCryptoKey, callback);

        callback.onLog("", "FUND", "等待充值交易确认...");
        if (!sleepUnlessCancelled(WAIT_AFTER_FUND_MS)) {
            callback.onLog("", "", "用户取消，停止流程");
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        callback.onLog("", "ENTER", "完整流程 · 第2步：批量入场");
        BatchEnterTask.batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);

        if (Web3AndroidUtil.isCancelRequested()) {
            callback.onLog("", "", "用户取消，跳过领取阶段");
            // 入场失败的地址只在本次流程内有意义，退出前清掉，避免下次流程误跳
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        callback.onLog("", "ENTER", "等待入场交易确认及游戏状态更新...");
        if (!sleepUnlessCancelled(WAIT_AFTER_ENTER_MS)) {
            callback.onLog("", "", "用户取消，跳过领取阶段");
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        callback.onLog("", "CLAIM", "完整流程 · 第3步：批量领取");
        BatchClaimTask.batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        BatchEnterTask.enterFailedAddrSet.clear();

        callback.onLog("", "", "完整流程结束");
    }

    // endregion

    // region 可取消等待

    /**
     * 分段休眠，任一段之间检查取消标记。
     *
     * <p>直接用 {@link Thread#sleep(long)} 的话，用户在这段时间按下取消要等整段睡完才生效；
     * 切成小段后取消最多延迟一个 {@link #WAIT_SLICE_MS}。中途被打断（InterruptedException）
     * 按取消处理，异常继续上抛给调用方收尾。</p>
     *
     * @return {@code true} 表示睡满且期间未取消；{@code false} 表示期间收到取消请求
     */
    private static boolean sleepUnlessCancelled(long totalMs) throws InterruptedException {
        long waited = 0L;
        while (waited < totalMs) {
            if (Web3AndroidUtil.isCancelRequested()) return false;
            long step = Math.min(WAIT_SLICE_MS, totalMs - waited);
            Thread.sleep(step);
            waited += step;
        }
        return !Web3AndroidUtil.isCancelRequested();
    }

    // endregion

}
