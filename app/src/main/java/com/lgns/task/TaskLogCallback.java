package com.lgns.task;

import com.lgns.contract.ArenaContractApi;

public interface TaskLogCallback {
    void onLog(String keyNo, String type, String msg);

    default void onBatchResult(java.util.Map<String, ArenaContractApi.PlayerSummary> summaries) {}

    default void onKeyFailure(String keyNo, String failType, String reason) {}
}
