package com.ailgnsq.util;

import android.util.Log;

import com.ailgnsq.model.ArenaConfig;

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
 * Web3 访问的底层工具：节点选择、重试、超时、gas 估算与取消开关。
 *
 * <p>已经踩过的坑都收在这一个类里：nonce 写到冲突、gas 硬编码、不等回执、
 * RPC 无重试、固定 sleep 等待；以及多 RPC 节点熔断轮换 + chainId 上线校验 +
 * revert 类错误不重试 + 并行探测节点 + 合约字节码就绪校验 + OkHttp 显式超时。</p>
 *
 * <p>重试策略是最容易改坏的地方：{@link #rpcRetryCall} 对普通网络错误做指数退避重试，
 * 但只要 {@link #isRevert} 判定为业务失败就直接抛出——这类错误重试只会重复失败，
 * 还会把节点的 RPC 配额烧光。</p>
 */
public class Web3AndroidUtil {

    /**
     * RPC 任务函数式接口，用于重试机制
     */
    @FunctionalInterface
    public interface RpcTask<T> {
        T run() throws Exception;
    }

    // region 常量

    /** 回执轮询间隔（毫秒）：节点出块几秒内完成，1s 既能及时拿到又不打满节点 */
    private static final long RECEIPT_POLL_MS = 1000L;
    /** 等回执总超时（秒），与 ArenaConfig 共用一份，避免两处口径不一致 */
    private static final long TX_WAIT_TIMEOUT_SEC = ArenaConfig.TX_TIMEOUT_SEC;
    /** gasPrice 上浮比例分子：110/100 = 链上报价再提 10%，防被抢跑后卡在 pending */
    private static final BigInteger GAS_PRICE_RATIO = BigInteger.valueOf(110);
    /** gasPrice 上浮比例分母 */
    private static final BigInteger GAS_PRICE_BASE = BigInteger.valueOf(100);
    /** gasLimit 上浮比例分子：120/100，估算值再留 20% 余量防 OOG */
    private static final BigInteger GAS_LIMIT_RATIO = BigInteger.valueOf(120);
    /** gasLimit 上浮比例分母 */
    private static final BigInteger GAS_LIMIT_BASE = BigInteger.valueOf(100);
    /** 重试总次数，取自配置（节点数 * 2） */
    private static final int RPC_MAX_RETRY = ArenaConfig.RPC_MAX_RETRY;
    /** 重试基础退避（毫秒），之后按 2 的幂递增，上限封在 16 倍 */
    private static final long RPC_RETRY_INTERVAL_MS = 500L;

    /** 当前活跃RPC节点索引,跨线程共享,失败时原子递增 */
    private static final AtomicInteger CUR_RPC = new AtomicInteger(0);

    // endregion

    /**
     * 等待交易回执：循环轮询直到拿到回执或超时，替代固定 sleep。
     *
     * @param timeoutSec 总超时秒数；超时抛异常，由调用方决定是重查还是标 pending
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

    /**
     * 取 pending 状态的 nonce（包含已发出但未上链的）。
     *
     * <p>必须用 PENDING 而不是 LATEST：用 LATEST 会在上一笔还没出块时又发一笔同 nonce 的交易，
     * 两者只能活一笔，结果就是“交易神秘消失”。</p>
     */
    public static BigInteger getPendingNonce(Web3j web3j, String address) throws Exception {
        return rpcRetryCall(() -> web3j.ethGetTransactionCount(address, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
                .send().getTransactionCount());
    }

    /** 取链上 gasPrice 并上浮 10%（{@link #GAS_PRICE_RATIO}/{@link #GAS_PRICE_BASE}） */
    public static BigInteger getGasPrice(Web3j web3j) throws Exception {
        BigInteger rawGasPrice = rpcRetryCall(() -> web3j.ethGasPrice().send().getGasPrice());
        return rawGasPrice.multiply(GAS_PRICE_RATIO).divide(GAS_PRICE_BASE);
    }

    /**
     * 估算 gas 并上浮 20%。
     *
     * <p>估算失败时把 from/to/节点/原因一起打进 logcat 再抛：这种失败常见于
     * 参数错、节点不同步，只看一句“estimate gas failed”根本查不出来。
     * 不直接落库：自动估算每轮每账号都会跑，写 DB 会迅速撑大日志表。</p>
     */
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

    /**
     * 带重试地执行一次 RPC 调用（所有网络请求的公共入口）。
     *
     * <p>退避策略：{@code 500ms * 2^min(i,4)}，即 0.5s/1s/2s/4s/8s……封在 8s，
     * 避免节点长时间宕机时把一次任务拖到几分钟。
     * revert 这类“再来一次也一样”的错误不重试，直接抛。</p>
     *
     * @throws Exception 重试耗尽后抛出，cause 为最后一次异常
     */
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

    /** 十六进制私钥（带不带 0x 均可）转 ECKeyPair */
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

    /** 当前活跃节点 URL；下标取模，避免轮换溢出数组 */
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
    /**
     * 请求取消当前批量任务。
     *
     * <p>采用「标记 + 轮询」而不是中断线程：批量循环在账号切换处检查此标记，
     * 保证当前账号的交易能跑完再退出，不会留下半截状态。</p>
     */
    public static void requestCancel() { cancelRequested = true; }

    /** 新任务开始前清掉上一次的取消标记，否则任务一启动就自行退出 */
    public static void resetCancel() { cancelRequested = false; }

    /** 批量循环在每个账号切换处调用，true 则停止后续账号 */
    public static boolean isCancelRequested() { return cancelRequested; }

    /**
     * 「打完当前这一圈再停」请求标记。
     *
     * <p>与 {@link #cancelRequested} 是两套语义，不能合并：</p>
     * <ul>
     *   <li>{@code cancelRequested}——立刻停。当前账号处理完就跳出，可能停在只坐了一半的轮次里。</li>
     *   <li>{@code stopAfterRound}——只在圈与圈之间生效。当前这一圈会跑完、凑满
     *       {@code SEATS_PER_ROUND} 座并封盘，然后才退出。</li>
     * </ul>
     *
     * <p>为什么界面只暴露后者：合约<b>没有</b>「轮次超时关闭」的机制，{@code enterArena}
     * 里只在 {@code seat + 1 == SEATS} 时才把状态置为 {@code SEALED}；没凑满就一直停在
     * {@code OPEN}，已入座者那 100 fLGNS 记在 {@code inMatchDeposit} 里，既不能退也不能结算。
     * 停在半轮的代价远大于多跑完当前这 24 笔。</p>
     */
    private static volatile boolean stopAfterRound = false;

    /**
     * 请求「打完当前这一圈再停」。
     *
     * <p>不影响正在进行的账号循环，也不碰 {@link #cancelRequested}：
     * 真正需要立刻中断时仍然走取消路径。</p>
     */
    public static void requestStopAfterRound() { stopAfterRound = true; }

    /** 连打循环在圈边界与封盘轮询处检查，true 则不再开始新的一圈 */
    public static boolean isStopAfterRoundRequested() { return stopAfterRound; }

    /**
     * 「用户按过停止」的总判定：立即取消与「打完这圈再停」任一为真即为真。
     *
     * <p>只给<b>没有圈边界</b>的阶段用。充值阶段排在入场之前，按下停止时还没有任何人入座，
     * 停就是停，不会留下半轮锁仓，所以两条标记都当「立刻停」处理；
     * 而入场阶段必须继续逐个检查 {@link #isStopAfterRoundRequested()}，
     * 不能改用这个方法——入场停在圈内会让已入座的号那一笔押金卡到下一轮封盘。</p>
     */
    public static boolean isStopRequested() { return cancelRequested || stopAfterRound; }

    /**
     * 每次任务启动前清掉上一次的停止请求。
     *
     * <p>不能并进 {@link #resetCancel()}：那个方法在每圈开头都会被
     * {@code batchEnterAllResume} 调用，并进去会把用户刚按下的请求直接抹掉。</p>
     */
    public static void resetStop() { stopAfterRound = false; }

}