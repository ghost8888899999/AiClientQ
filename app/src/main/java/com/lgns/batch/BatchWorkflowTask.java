package com.lgns.batch;

import android.content.Context;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.List;

public class BatchWorkflowTask {

    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry,
                                               int playMode, List<KeyListItem> selectedList,
                                               char[] sessionCryptoKey) throws Exception {
        callback.onLog("", "FUND", "完整流程 · 第1步：批量充值");
        BatchFundTask.executeAutoFund(selectedList, sessionCryptoKey, callback);

        callback.onLog("", "FUND", "等待充值交易确认...");
        Thread.sleep(20000);

        callback.onLog("", "ENTER", "完整流程 · 第2步：批量入场");
        BatchEnterTask.batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);

        if (Web3AndroidUtil.isCancelRequested()) {
            callback.onLog("", "", "用户取消，跳过领取阶段");
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        callback.onLog("", "ENTER", "等待入场交易确认及游戏状态更新...");
        Thread.sleep(30000);

        callback.onLog("", "CLAIM", "完整流程 · 第3步：批量领取");
        BatchClaimTask.batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        BatchEnterTask.enterFailedAddrSet.clear();

        callback.onLog("", "", "完整流程结束");
    }
}
