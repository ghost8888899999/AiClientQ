package com.lgns.util;

import android.util.Log;

import com.lgns.model.ArenaConfig;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Web3工具类
 * 修复脚本全部缺陷：nonce冲突、gas硬编码、无回执校验、RPC无重试、固定sleep等待等风险
 * 新增:多RPC节点熔断轮换 + ChainId 上线校验 + revert 类错误不重试
 * 更佳优化：并行探测三节点 + 合约字节码就绪校验 + OkHttp 显式超时
 */
public class Web3AndroidUtil {

    /**
     * RPC 任务函数式接口，用于重试机制
     */
    @FunctionalInterface
    public interface RpcTask<T> {
        T run() throws Exception;
    }

    private static final long RECEIPT_POLL_MS = 1000L;
    private static final long TX_WAIT_TIMEOUT_SEC = ArenaConfig.TX_TIMEOUT_SEC;
    private static final BigInteger GAS_PRICE_RATIO = BigInteger.valueOf(110);
    private static final BigInteger GAS_PRICE_BASE = BigInteger.valueOf(100);
    private static final BigInteger GAS_LIMIT_RATIO = BigInteger.valueOf(120);
    private static final BigInteger GAS_LIMIT_BASE = BigInteger.valueOf(100);
    private static final int RPC_MAX_RETRY = ArenaConfig.RPC_MAX_RETRY;
    private static final long RPC_RETRY_INTERVAL_MS = 500L;

    /** 当前活跃RPC节点索引,跨线程共享,失败时原子递增 */
    private static final AtomicInteger CUR_RPC = new AtomicInteger(0);

    /**
     * 等待交易回执，带超时，循环轮询，替代固定sleep
     */
    public static TransactionReceipt waitReceipt(Web3j web3j, String txHash, long timeoutSec) throws Exception {
        long startTs = System.currentTimeMillis();
        long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        TransactionReceipt receipt = null;
        while (System.currentTimeMillis() - startTs < timeoutMs) {
            receipt = rpcRetryCall(() -> web3j.ethGetTransactionReceipt(txHash).send().getResult());
            if (receipt != null) {
                return receipt;
            }
            Thread.sleep(RECEIPT_POLL_MS);
        }
        throw new Exception("等待交易回执超时 hash:" + txHash);
    }

    public static BigInteger getPendingNonce(Web3j web3j, String address) throws Exception {
        return rpcRetryCall(() -> web3j.ethGetTransactionCount(address, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
                .send().getTransactionCount());
    }

    public static BigInteger getGasPrice(Web3j web3j) throws Exception {
        BigInteger rawGasPrice = rpcRetryCall(() -> web3j.ethGasPrice().send().getGasPrice());
        return rawGasPrice.multiply(GAS_PRICE_RATIO).divide(GAS_PRICE_BASE);
    }

    public static BigInteger estimateGas(Web3j web3j, String from, String to, String callData) throws Exception {
        return rpcRetryCall(() -> {
            org.web3j.protocol.core.methods.request.Transaction tx =
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(from, to, callData);
            try {
                BigInteger rawGas = web3j.ethEstimateGas(tx).send().getAmountUsed();
                return rawGas.multiply(GAS_LIMIT_RATIO).divide(GAS_LIMIT_BASE);
            } catch (Exception e) {
                String errMsg = "eth_estimateGas 失败：" + e.getMessage() + " | from=" + from + " | to=" + to + " | 节点:" + currentRpcUrl();
                // 仅 logcat：这是自动估算过程，不落库，避免 db 膨胀
                Log.e("GAS_ESTIMATE", errMsg);
                // 不直接 throw，让 rpcRetryCall 决定是否重试
                throw new RuntimeException(errMsg, e);
            }
        });
    }

    public static <T> T rpcRetryCall(RpcTask<T> task) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < RPC_MAX_RETRY; i++) {
            try {
                return task.run();
            } catch (Exception e) {
                lastEx = e;
                if (isRevert(e)) throw e;
                // 自动重试只打 logcat，不写数据库：节点不稳定时一轮任务就能刷出成百上千条
                Log.w("RPC_RETRY", "重试 " + (i + 1) + "/" + RPC_MAX_RETRY + "：" + safeMessage(e) + " | 节点:" + currentRpcUrl());
                long delay = RPC_RETRY_INTERVAL_MS * (1L << Math.min(i, 4));
                Thread.sleep(delay);
            }
        }
        throw new Exception("RPC 调用重试全部失败 | 节点:" + currentRpcUrl(), lastEx);
    }

    public static ECKeyPair pkToKeyPair(String privateKeyHex) {
        BigInteger priv = Numeric.toBigInt(privateKeyHex);
        return ECKeyPair.create(priv);
    }

    /**
     * 从 ECKeyPair 获取以太坊地址（带 0x 前缀）
     */
    public static String getAddress(ECKeyPair keyPair) {
        return "0x" + org.web3j.crypto.Keys.getAddress(keyPair);
    }

    /**
     * 创建 Web3j http 实例，按当前活跃节点选择 RPC URL
     * 更佳优化:补齐 OkHttp connect/read/write 超时，防节点不回永久挂死
     */
    public static Web3j createWeb3j() {
        String url = currentRpcUrl();
        OkHttpClient ok = new OkHttpClient.Builder()
                .connectTimeout(ArenaConfig.RPC_DIAL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(ArenaConfig.RPC_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(ArenaConfig.RPC_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        org.web3j.protocol.http.HttpService httpService =
                new org.web3j.protocol.http.HttpService(url, ok, false);
        return Web3j.build(httpService);
    }

    public static String currentRpcUrl() {
        int idx = Math.floorMod(CUR_RPC.get(), ArenaConfig.RPC_URLS.length);
        return ArenaConfig.RPC_URLS[idx];
    }

    /**
     * 失败时切下一个节点,返回重建后的 Web3j
     */
    public static synchronized Web3j rotateWeb3j(Web3j old) {
        if (old != null) {
            try { old.shutdown(); } catch (Exception ignore) {}
        }
        CUR_RPC.incrementAndGet();
        return createWeb3j();
    }

    /**
     * 连接后校验远端 chainId == 本地常量,防配错链
     */
    public static void verifyChainId(Web3j web3j) throws Exception {
        BigInteger remoteId = rpcRetryCall(() -> web3j.ethChainId().send().getChainId());
        if (remoteId.longValue() != ArenaConfig.CHAIN_ID) {
            throw new IOException("RPC 链 ID 不匹配:远端=" + remoteId + " 期望=" + ArenaConfig.CHAIN_ID
                    + " URL=" + currentRpcUrl());
        }
    }

    /**
     * Dial 增强:
     * 1) 并行探测 3 节点,首个 chainId 对+合约就绪的胜出
     * 2) 三节点全灭才抛 IOException
     * 冷启动最坏 8s(单个 dial 超时),而不是 3*8s=24s
     */
    @SuppressWarnings("unchecked")
    public static Web3j dialAny() throws Exception {
        final int n = ArenaConfig.RPC_URLS.length;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            Future<Web3j>[] futures = new Future[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures[i] = pool.submit((Callable<Web3j>) () -> {
                    String url = ArenaConfig.RPC_URLS[idx];
                    OkHttpClient ok = new OkHttpClient.Builder()
                            .connectTimeout(ArenaConfig.RPC_DIAL_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .readTimeout(ArenaConfig.RPC_DIAL_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .writeTimeout(ArenaConfig.RPC_DIAL_TIMEOUT_SEC, TimeUnit.SECONDS)
                            .build();
                    Web3j w = Web3j.build(new org.web3j.protocol.http.HttpService(url, ok, false));
                    try {
                        BigInteger id = w.ethChainId()
                                .sendAsync()
                                .get(ArenaConfig.RPC_DIAL_TIMEOUT_SEC, TimeUnit.SECONDS)
                                .getChainId();
                        if (id.longValue() != ArenaConfig.CHAIN_ID) {
                            throw new IOException("chainId 不匹配: " + url + " 远端=" + id
                                    + " 期望=" + ArenaConfig.CHAIN_ID);
                        }
                        org.web3j.protocol.core.methods.response.EthGetCode code =
                                w.ethGetCode(ArenaConfig.ARENA_ADDR,
                                        org.web3j.protocol.core.DefaultBlockParameterName.LATEST).send();
                        if (code.getCode() == null || "0x".equals(code.getCode())) {
                            throw new IOException("合约未在该节点生效: " + url
                                    + " addr=" + ArenaConfig.ARENA_ADDR);
                        }
                        return w;
                    } catch (Exception e) {
                        try { w.shutdown(); } catch (Exception ignore) {}
                        throw e;
                    }
                });
            }
            Exception lastErr = null;
            for (int i = 0; i < n; i++) {
                try {
                    Web3j w = futures[i].get(ArenaConfig.RPC_DIAL_TIMEOUT_SEC + 2L, TimeUnit.SECONDS);
                    CUR_RPC.set(i);
                    return w;
                } catch (Exception e) {
                    lastErr = e;
                }
            }
            throw new IOException(n + " 个 RPC 节点都连不上或链不对/合约未就绪", lastErr);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 明确分类,这类错误不重试
     */
    public static boolean isRevert(Throwable e) {
        if (e == null) return false;
        String m = safeMessage(e).toLowerCase();
        return m.contains("execution reverted") || m.contains("out of gas")
                || m.contains("insufficient funds") || m.contains("nonce too low")
                || m.contains("invalid opcode") || m.contains("gas required exceeds");
    }

    /**
     * 明确分类,这类错误不重试
     */
    public static boolean isAlreadyInFlight(Throwable e) {
        if (e == null) return false;
        String m = safeMessage(e).toLowerCase();
        return m.contains("already known") || m.contains("already imported")
                || m.contains("known transaction") || m.contains("transaction with the same hash");
    }

    /**
     * B5 · 兜底 e.getMessage() 返回 null 的场景(如 NullPointerException 无 cause)
     */
    public static String safeMessage(Throwable e) {
        if (e == null) return "";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m;
    }

    /**
     * 拿最新区块 gasLimit,失败返回 0
     */
    public static BigInteger getLatestBlockGasLimit(Web3j web3j) throws Exception {
        return rpcRetryCall(() -> {
            org.web3j.protocol.core.methods.response.EthBlock.Block blk =
                    web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameterName.LATEST, false)
                            .send().getBlock();
            return blk == null ? BigInteger.ZERO : blk.getGasLimit();
        });
    }

    /**
     * 预估 gas + 20% 上浮,并按 BLOCK_GAS_LIMIT_MARGIN_PCT 卡上限
     */
    public static BigInteger estimateGasWithCeiling(Web3j web3j, String from, String to, String callData) throws Exception {
        BigInteger est = estimateGas(web3j, from, to, callData); // 已 *120/100
        BigInteger blockLimit = getLatestBlockGasLimit(web3j);
        if (blockLimit.signum() > 0) {
            BigInteger ceiling = blockLimit.multiply(BigInteger.valueOf(ArenaConfig.BLOCK_GAS_LIMIT_MARGIN_PCT))
                    .divide(BigInteger.valueOf(100));
            if (est.compareTo(ceiling) > 0) {
                throw new IOException("整批所需 gas " + est + " 超过区块上限 " + blockLimit
                        + " 的 " + ArenaConfig.BLOCK_GAS_LIMIT_MARGIN_PCT + "%,请减少账号数");
            }
        }
        return est;
    }

    /**
     * 请求取消,当前批量循环会在下一次账号切换时跳出
     */
    private static volatile boolean cancelRequested = false;
    public static void requestCancel() { cancelRequested = true; }
    public static void resetCancel() { cancelRequested = false; }
    public static boolean isCancelRequested() { return cancelRequested; }


}