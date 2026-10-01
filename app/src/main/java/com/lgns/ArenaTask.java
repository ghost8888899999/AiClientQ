package com.lgns;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public class ArenaTask {
    // 和py脚本常量保持完全一致
    public static final BigInteger ENTRY_FEE = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));
    public static final BigInteger MAX_JIAZI_BATCH = BigInteger.valueOf(64);
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    // ============ 断点续跑：任务状态常量【新增】============
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_TX_SENT = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAIL = 3;
    private static final Gson gson = new Gson();

    private static SharedPreferences getTaskSp(Context ctx) {
        return ctx.getSharedPreferences("task_progress", Context.MODE_PRIVATE);
    }

    /** 保存单账号任务状态到磁盘【新增】 */
    private static void saveAccountState(Context ctx, String accountAddr, TaskAccountState state) {
        SharedPreferences sp = getTaskSp(ctx);
        String json = gson.toJson(state);
        sp.edit().putString("task_" + accountAddr.toLowerCase(), json).apply();
    }

    /** 读取账号任务状态，没有记录返回IDLE【新增】 */
    private static TaskAccountState getAccountState(Context ctx, String accountAddr) {
        SharedPreferences sp = getTaskSp(ctx);
        String json = sp.getString("task_" + accountAddr.toLowerCase(), null);
        if (json == null) {
            return new TaskAccountState();
        }
        return gson.fromJson(json, TaskAccountState.class);
    }

    /** 清空全部批量任务进度，UI重置按钮调用【新增】 */
    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
    }

    /**
     * 包装：处理单个账号（断点续跑外壳，内部调用原版enterOneAccountOriginal）【新增】
     * @param ctx context
     * @param web3j web3实例
     * @param privateKey 明文私钥
     * @param logCallback 日志回调
     * @return true 账号业务完成(跳过/成功) false失败
     */
    private static boolean processEnterOneResume(Context ctx, Web3j web3j, String privateKey, TaskLogCallback logCallback) {
        ECKeyPair keyPair = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            String fromAddr = Web3AndroidUtil.getAddress(keyPair);
            TaskAccountState state = getAccountState(ctx, fromAddr);

            //磁盘标记成功直接跳过
            if (state.status == STATUS_SUCCESS) {
                logCallback.onLog("[" + fromAddr.substring(0, 10) + "...] 磁盘记录已完成，跳过入场");
                return true;
            }

            //状态：已经发送交易，查询历史回执
            if (state.status == STATUS_TX_SENT && state.txHash != null) {
                logCallback.onLog("[" + fromAddr.substring(0,10)+"...] 查询历史入场交易回执 txHash:" + state.txHash);
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(state.txHash).send();
                Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                TransactionReceipt receipt = optReceipt.orElse(null);

                if (receipt != null) {
                    String statusHex = receipt.getStatus();
                    boolean txSuccess = "0x1".equalsIgnoreCase(statusHex);
                    if (txSuccess) {
                        saveAccountState(ctx, fromAddr, new TaskAccountState(STATUS_SUCCESS, null));
                        logCallback.onLog("[" + fromAddr.substring(0,10)+"...] 历史入场交易链上确认成功");
                        return true;
                    } else {
                        logCallback.onLog("[" + fromAddr.substring(0,10)+"...] 历史入场交易reverted，重新执行入场");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(STATUS_IDLE, null));
                    }
                } else {
                    logCallback.onLog("[" + fromAddr.substring(0,10)+"...] 入场交易未被打包，丢弃旧tx，重新执行");
                    saveAccountState(ctx, fromAddr, new TaskAccountState(STATUS_IDLE, null));
                }
            }



            // 【幂等前置校验：查询是否已经入座，避免重复合约调用】
            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                saveAccountState(ctx, fromAddr, new TaskAccountState(STATUS_SUCCESS, null));
                logCallback.onLog("[" + fromAddr.substring(0,10)+"...] 合约查询已经入座，跳过入场");
                return true;
            }

            // =====调用原版1:1py复刻的入场逻辑，不改动业务内部=====
            String logLine = enterOneAccountOriginal(web3j, keyPair);
            logCallback.onLog(logLine);

            // 从返回日志提取txHash(原版返回字符串 "...成功 参与游戏(奖金抵扣) (0xaaaaaaaaaaa......)")
            String txHash = null;
            String[] splitArr = logLine.split("\\(");
            if(splitArr.length >=2){
                String candidate = splitArr[splitArr.length-1].trim();
                if(candidate.startsWith("0x")){
                    txHash = candidate.replace(")","").trim();
                }
            }
            if(txHash!=null && txHash.startsWith("0x")){
                //拿到txHash立刻落盘
                saveAccountState(ctx,fromAddr,new TaskAccountState(STATUS_TX_SENT,txHash));
            }
            //执行完成后，再次读取链上状态确认是否入座，标记成功
            ArenaContractApi.PlayerSummary afterSummary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if(afterSummary.seatedInCurrentRound){
                saveAccountState(ctx,fromAddr,new TaskAccountState(STATUS_SUCCESS,null));
                return true;
            }else{
                saveAccountState(ctx,fromAddr,new TaskAccountState(STATUS_FAIL,txHash));
                return false;
            }
        } catch (Exception e) {
            logCallback.onLog("❌【入场包装异常】:" + e.getMessage());
            return false;
        } finally {
            keyPair = null;
        }
    }

    /**
     * 断点续跑版批量入场【新增对外入口】
     */
    public static void batchEnterAllResume(Context ctx, TaskLogCallback callback) throws Exception {
        KeyStoreManager keyStoreManager = AppGlobal.getKeyStoreManager();
        List<String> privateKeyList = keyStoreManager.loadAllPrivateKey();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("\n--------开始批量入场【断点续跑版】--------");
        int okCnt = 0, failCnt = 0;
        int total = privateKeyList.size();
        for (int i = 0; i < privateKeyList.size(); i++) {
            String pk = privateKeyList.get(i);
            callback.onLog("\n-----处理账号 " + (i + 1) + "/" + total + " -----");
            boolean ok = processEnterOneResume(ctx, web3j, pk, callback);
            if (ok) okCnt++;
            else failCnt++;
        }
        web3j.shutdown();
        callback.onLog(String.format("--------断点入场结束：成功/跳过=%d，失败=%d --------", okCnt, failCnt));
    }

    /**
     * 断点续跑版批量领取（内部调用原版batchClaimAndVerifyAll，注意：领取是多笔交易(甲子循环+师徒+擂台)，本层只做整体账号级状态）【新增对外入口】
     * 说明：领取一个账号会发出多笔tx，本实现以【账号整体完成】作为成功标记；内部多笔交易失败由原版内部重试逻辑处理。
     */
    public static void batchClaimAllResume(Context ctx, TaskLogCallback callback, int maxRetryPerAccount) throws Exception {
        KeyStoreManager keyStoreManager = AppGlobal.getKeyStoreManager();
        List<String> privateKeyList = keyStoreManager.loadAllPrivateKey();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("\n--------开始批量领取【断点续跑版】--------");
        int okCnt = 0, failCnt = 0;
        int total = privateKeyList.size();
        for (int i = 0; i < privateKeyList.size(); i++) {
            String pk = privateKeyList.get(i);
            ECKeyPair keyPair = null;
            String fromAddr;
            try {
                keyPair = Web3AndroidUtil.pkToKeyPair(pk);
                fromAddr = Web3AndroidUtil.getAddress(keyPair);
            } finally {
                keyPair = null;
            }
            TaskAccountState state = getAccountState(ctx, fromAddr);
            if(state.status == STATUS_SUCCESS){
                callback.onLog("["+fromAddr.substring(0,10)+"...] 磁盘记录领取已完成，跳过");
                okCnt++;
                continue;
            }
            callback.onLog("\n-----处理账号 " + (i + 1) + "/" + total + " -----");
            //调用原版带校验重试的领取整套逻辑
            try{
                batchClaimAndVerifyAllSingleAccount(ctx,pk,web3j,callback,maxRetryPerAccount);
                saveAccountState(ctx,fromAddr,new TaskAccountState(STATUS_SUCCESS,null));
                okCnt++;
            }catch (Exception e){
                callback.onLog("❌账号领取整体失败:"+e.getMessage());
                saveAccountState(ctx,fromAddr,new TaskAccountState(STATUS_FAIL,null));
                failCnt++;
            }
        }
        web3j.shutdown();
        callback.onLog(String.format("--------断点领取结束：成功/跳过=%d，失败=%d --------", okCnt, failCnt));
    }

    /** 辅助：只处理单个账号的原版领取+校验重试逻辑【新增私有】 */
    private static void batchClaimAndVerifyAllSingleAccount(Context ctx,String pk,Web3j web3j,TaskLogCallback callback,int maxRetryPerAccount) throws Exception{
        ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        try{
            ClaimOneResult res = claimOneAccountOriginal(web3j,keyPair,0);
            callback.onLog(res.logText);
            int retryCount =0;
            BigInteger remainJiazi;
            do{
                remainJiazi = ArenaContractApi.callJiaziCount(web3j, Web3AndroidUtil.getAddress(keyPair));
                retryCount++;
                if(remainJiazi.compareTo(BigInteger.ZERO) >0){
                    callback.onLog("⚠️校验发现仍有可领取甲子："+remainJiazi+" ,准备重试("+retryCount+"/"+maxRetryPerAccount+")");
                    Thread.sleep(1500);
                    res = claimOneAccountOriginal(web3j,keyPair,0);
                    callback.onLog(res.logText);
                }else{
                    callback.onLog("✅校验：甲子已经全部领取完毕");
                }
            }while (remainJiazi.compareTo(BigInteger.ZERO)>0 && retryCount < maxRetryPerAccount);
            if(remainJiazi.compareTo(BigInteger.ZERO)>0){
                callback.onLog("❌该账号重试耗尽，仍然存在未领取甲子："+remainJiazi+"，需要人工处理");
            }
        }finally {
            keyPair = null;
        }
    }

    /** 断点续跑完整工作流：入场(断点) → 领取(断点)【新增对外入口】 */
    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry) throws Exception {
        batchEnterAllResume(ctx,callback);
        batchClaimAllResume(ctx,callback,claimMaxRetry);
    }

    //==================== 以下全部为原版代码，完全不动，保持和你原始文件一模一样 ====================
    public static final BigInteger ENTRY_FEE_OLD = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));

    /**
     * 【原版入场，对齐zhixing.py】批量全部账号入场，无任何增强
     */
    public static void batchEnterAll(Context ctx, TaskLogCallback callback) throws Exception {
        KeyStoreManager keyStoreManager = AppGlobal.getKeyStoreManager();
        List<String> privateKeyList = keyStoreManager.loadAllPrivateKey();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        int total = privateKeyList.size();
        callback.onLog("\n--------开始批量入场(原版对齐zhixing.py)--------");
        for (int i = 0; i < privateKeyList.size(); i++) {
            String pk = privateKeyList.get(i);
            ECKeyPair keyPair = null;
            try {
                keyPair = Web3AndroidUtil.pkToKeyPair(pk);
                String addr = Web3AndroidUtil.getAddress(keyPair);
                callback.onLog("\n-----处理账号 " + (i + 1) + "/" + total + " : " + addr.substring(0, 10) + "... -----");
                String enterResult = enterOneAccountOriginal(web3j, keyPair);
                callback.onLog(enterResult);
            } catch (Exception e) {
                callback.onLog("❌账号异常:" + e.getMessage());
            } finally {
                keyPair = null;
            }
        }
        web3j.shutdown();
        callback.onLog("--------批量入场任务执行结束--------");
    }

    /**
     * 单账号入场【1:1复刻zhixing.py全部分支逻辑，顺序不能改动】
     * 返回日志文本
     */
    private static String enterOneAccountOriginal(Web3j web3j, ECKeyPair keyPair) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger estGas = BigInteger.valueOf(1600000);
        BigInteger nativeBalance = web3j.ethGetBalance(addr, org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send().getBalance();
        // 1.原生币gas不足直接跳过
        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return String.format("%s... 跳过:原生币不足,差约 %.4f", addr.substring(0, 10), diffEth);
        }
        // 查询playerSummary
        ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, addr);
        BigInteger walletBalance = summary.walletBalance;
        BigInteger allowance = summary.allowance;
        BigInteger prize = summary.prize;
        BigInteger entriesRemainingToday = summary.entriesRemainingToday;
        boolean seatedInCurrentRound = summary.seatedInCurrentRound;
        if (seatedInCurrentRound) {
            return addr.substring(0,10)+"... 跳过: 当前场已入座";
        }
        if (entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) {
            return addr.substring(0,10)+"... 跳过: 今日已打满12场";
        }
        String payDesc;
        boolean needApprove = false;
        // ========== 复刻py判断顺序：分支A 奖金足够直接抵扣，不走approve ==========
        if (prize.compareTo(ENTRY_FEE) >= 0) {
            payDesc = "参与游戏(奖金抵扣)";
        } else {
            // 分支B：奖金不足，检查钱包代币
            if (walletBalance.compareTo(ENTRY_FEE) >=0) {
                payDesc = "参与游戏(钱包扣币)";
                if (allowance.compareTo(ENTRY_FEE) < 0) {
                    needApprove = true;
                }
            } else {
                //分支C：全部不足，跳过
                double prizeD = Convert.fromWei(prize.toString(),Convert.Unit.ETHER).doubleValue();
                double walletD = Convert.fromWei(walletBalance.toString(),Convert.Unit.ETHER).doubleValue();
                return String.format("%s... 跳过:奖金 %.2f、钱包 %.2f 都不足100",addr.substring(0,10),prizeD,walletD);
            }
        }
        // 【本地维护nonce，py原版行为：只取一次pending nonce】
        BigInteger localNonce = web3j.ethGetTransactionCount(addr, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
        // 需要授权：执行无限approve
        if (needApprove) {
            TransactionReceipt approveReceipt = ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, localNonce, gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
            Thread.sleep(2000); //py sleep 2s
        }
        // 执行 enterArena
        TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, localNonce, gasPrice);
        String txHash = enterReceipt.getTransactionHash();
        return String.format("%s... 成功 %s (%s)", addr.substring(0,10), txHash.substring(0,16), payDesc, txHash);
    }

    //==================== 领取模块 ====================
    /**
     * 原版批量领取，等价 lingqu.py，不带任何校验重试
     */
    public static void batchClaimAll(Context ctx, TaskLogCallback callback) throws Exception {
        KeyStoreManager keyStoreManager = AppGlobal.getKeyStoreManager();
        List<String> privateKeyList = keyStoreManager.loadAllPrivateKey();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("\n--------开始批量领取【原版lingqu.py，无重试】--------");
        int totalWend = 0;
        int failQuery = 0;
        int okAccounts = 0;
        int total = privateKeyList.size();
        for(int i=0;i<privateKeyList.size();i++){
            String pk = privateKeyList.get(i);
            ECKeyPair keyPair = null;
            Integer wendToday = null;
            try{
                keyPair = Web3AndroidUtil.pkToKeyPair(pk);
                ClaimOneResult res = claimOneAccountOriginal(web3j,keyPair,i+1);
                callback.onLog(res.logText);
                wendToday = res.wendToday;
            }catch (Exception e){
                callback.onLog("❌账号领取异常:"+e.getMessage());
            }finally {
                keyPair = null;
            }
            if(wendToday == null){
                failQuery++;
            }else{
                totalWend += wendToday;
                okAccounts++;
            }
            Thread.sleep(500); //py sleep 0.5
        }
        web3j.shutdown();
        // 输出全局统计，对齐py
        callback.onLog("\n--------批量领取全部完成--------");
        if(failQuery>0){
            callback.onLog(String.format("当天总问鼎次数: %d（已统计 %d 个号，另有 %d 个号查询失败未计入）",totalWend,okAccounts,failQuery));
        }else{
            callback.onLog(String.format("当天总问鼎次数: %d（共 %d 个号）",totalWend,okAccounts));
        }
    }

    /**
     * 单账号原版领取：1:1复刻lingqu.py；返回日志与问鼎次数
     */
    private static ClaimOneResult claimOneAccountOriginal(Web3j web3j, ECKeyPair keyPair, int index) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        BigInteger localNonce = web3j.ethGetTransactionCount(addr, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
        StringBuilder logSb = new StringBuilder();
        // 1.批量领取甲子 claimJiazi
        BigInteger jiaziTotal = ArenaContractApi.callJiaziCount(web3j,addr);
        BigInteger claimedJ = BigInteger.ZERO;
        if(jiaziTotal.compareTo(BigInteger.ZERO)>0){
            for(BigInteger start=BigInteger.ZERO; start.compareTo(jiaziTotal)<0; start=start.add(MAX_JIAZI_BATCH)){
                BigInteger cnt = MAX_JIAZI_BATCH.min(jiaziTotal.subtract(start));
                ArenaContractApi.doClaimJiazi(web3j,keyPair,start,cnt,localNonce,gasPrice);
                localNonce = localNonce.add(BigInteger.ONE);
                claimedJ = claimedJ.add(cnt);
                Thread.sleep(1000);
            }
        }
        logSb.append("甲子:领了 ").append(claimedJ).append(" | ");
        //2. claimLineage 师徒
        try{
            ArenaContractApi.doClaimLineage(web3j,keyPair,localNonce,gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
            logSb.append("师徒:已领 | ");
            Thread.sleep(1000);
        }catch (Exception e){
            logSb.append("师徒失败:").append(e.getMessage().substring(0,Math.min(50,e.getMessage().length()))).append(" | ");
        }
        //3. claimRewards 擂台
        try{
            ArenaContractApi.doClaimRewards(web3j,keyPair,localNonce,gasPrice);
            localNonce = localNonce.add(BigInteger.ONE);
            logSb.append("擂台:已领");
        }catch (Exception e){
            logSb.append("擂台失败:").append(e.getMessage().substring(0,Math.min(50,e.getMessage().length())));
        }
        // 查询附加信息：今日入场、今日问鼎
        Integer matchesToday = ArenaContractApi.callActivityOfMatchesToday(web3j,addr);
        Integer wendToday = ArenaContractApi.callPlayerHistoryLatestWendToday(web3j,addr);
        logSb.append(" | 第").append(index).append("个号");
        if(wendToday == null){
            logSb.append(" | 当日问鼎:查询失败");
        }else{
            logSb.append(" | 当日问鼎:").append(wendToday).append("次");
        }
        if(matchesToday != null){
            logSb.append(" | 今日入场:").append(matchesToday);
        }
        String finalLog = addr.substring(0,10)+"... "+logSb;
        return new ClaimOneResult(finalLog,wendToday);
    }

    /**
     * 【增强版领取：原版领取 + RPC校验 + 自动重试】
     * 注意：内部调用原版claimOneAccountOriginal，不修改原版业务
     */
    public static void batchClaimAndVerifyAll(Context ctx, TaskLogCallback callback, int maxRetryPerAccount) throws Exception {
        KeyStoreManager keyStoreManager = AppGlobal.getKeyStoreManager();
        List<String> privateKeyList = keyStoreManager.loadAllPrivateKey();
        Web3j web3j = Web3AndroidUtil.createWeb3j();
        callback.onLog("\n==========【开始：奖励领取+校验重试流程】==========");
        for(int i=0;i<privateKeyList.size();i++){
            String pk = privateKeyList.get(i);
            ECKeyPair keyPair = null;
            try{
                keyPair = Web3AndroidUtil.pkToKeyPair(pk);
                String addr = Web3AndroidUtil.getAddress(keyPair);
                callback.onLog("\n-----账号 "+(i+1)+"/"+privateKeyList.size()+" -----");
                int retryCount = 0;
                BigInteger remainJiazi;
                do{
                    //执行原版完整领取逻辑
                    ClaimOneResult res = claimOneAccountOriginal(web3j,keyPair,i+1);
                    callback.onLog(res.logText);
                    remainJiazi = ArenaContractApi.callJiaziCount(web3j,addr);
                    retryCount++;
                    if(remainJiazi.compareTo(BigInteger.ZERO) >0){
                        callback.onLog("⚠️校验发现仍有可领取甲子："+remainJiazi+" ,准备重试("+retryCount+"/"+maxRetryPerAccount+")");
                        Thread.sleep(1500);
                    }else{
                        callback.onLog("✅校验：甲子已经全部领取完毕");
                    }
                }while (remainJiazi.compareTo(BigInteger.ZERO)>0 && retryCount < maxRetryPerAccount);
                if(remainJiazi.compareTo(BigInteger.ZERO)>0){
                    callback.onLog("❌该账号重试耗尽，仍然存在未领取甲子："+remainJiazi+"，需要人工处理");
                }
            }catch (Exception e){
                callback.onLog("❌账号领取校验整体异常："+e.getMessage());
            }finally {
                keyPair = null;
            }
        }
        web3j.shutdown();
        callback.onLog("\n==========【领取+校验流程全部结束】==========");
    }

    /**
     * 完整工作流：原版全部入场 → 增强版(原版领取+校验重试)
     */
    public static void fullArenaWorkflow(Context ctx, TaskLogCallback callback, int claimMaxRetry) throws Exception {
        batchEnterAll(ctx,callback);
        batchClaimAndVerifyAll(ctx,callback,claimMaxRetry);
    }

    //====================内部实体====================
    public static class ClaimOneResult{
        public String logText;
        public Integer wendToday;
        public ClaimOneResult(String logText, Integer wendToday){
            this.logText = logText;
            this.wendToday = wendToday;
        }
    }
}
