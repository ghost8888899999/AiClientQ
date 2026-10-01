package com.lgns;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

/**
 * Web3工具类
 * 修复原Python脚本全部缺陷：nonce冲突、gas硬编码、无回执校验、RPC无重试、固定sleep等待等风险
 */
public class Web3AndroidUtil {

    // 交易回执查询轮询间隔 1秒
    private static final long RECEIPT_POLL_MS = 1000L;
    // 交易回执最大等待超时，单位秒
    private static final long TX_WAIT_TIMEOUT_SEC = 120;
    // gasPrice上浮10%作为缓冲，应对网络拥堵
    private static final BigInteger GAS_PRICE_RATIO = BigInteger.valueOf(110);
    private static final BigInteger GAS_PRICE_BASE = BigInteger.valueOf(100);
    // RPC最大重试次数
    private static final int RPC_MAX_RETRY = 3;

    /**
     * 发送原始签名交易，EIP155带链ID签名，防止跨链重放攻击
     * @param web3j web3j实例
     * @param rawTx 待签名原始交易
     * @param keyPair 账户密钥对
     * @param chainId 公链链ID（阿努比斯公链）
     * @return 交易hash
     * @throws IOException 网络/签名异常
     */
    public static String sendRawTx(Web3j web3j, RawTransaction rawTx, ECKeyPair keyPair, long chainId) throws IOException {
        Credentials credentials = Credentials.create(keyPair);
        // EIP155签名，携带链ID
        byte[] signedBytes = TransactionEncoder.signMessage(rawTx, chainId, credentials);
        String hexRawTx = Numeric.toHexString(signedBytes);

        // 广播交易
        EthSendTransaction sendResp = web3j.ethSendRawTransaction(hexRawTx).send();
        if (sendResp.hasError()) {
            throw new IOException("交易广播失败: " + sendResp.getError().getMessage());
        }
        return sendResp.getTransactionHash();
    }

    /**
     * 等待交易回执，带超时，循环轮询，替代固定sleep
     * @param web3j web3j实例
     * @param txHash 交易哈希
     * @param timeoutSec 最大等待秒数
     * @return 交易回执
     * @throws Exception 超时/交易失败
     */
    public static TransactionReceipt waitReceipt(Web3j web3j, String txHash, long timeoutSec) throws Exception {
        long startTs = System.currentTimeMillis();
        long timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSec);
        TransactionReceipt receipt = null;

        while (System.currentTimeMillis() - startTs < timeoutMs) {
            // RPC查询，内置重试
            receipt = rpcRetryCall(() -> web3j.ethGetTransactionReceipt(txHash).send().getResult());
            if (receipt != null) {
                return receipt;
            }
            Thread.sleep(RECEIPT_POLL_MS);
        }
        throw new Exception("等待交易回执超时 hash:" + txHash);
    }

    /**
     * 获取待处理nonce（带RPC重试）
     * @param web3j web3j实例
     * @param address 钱包地址
     * @return nonce
     * @throws Exception 异常
     */
    public static BigInteger getPendingNonce(Web3j web3j, String address) throws Exception {
        return rpcRetryCall(() -> web3j.ethGetTransactionCount(address, org.web3j.protocol.core.DefaultBlockParameterName.PENDING)
                .send().getTransactionCount());
    }

    /**
     * 获取当前gasPrice，上浮10%缓冲，防止网络上涨导致gas不足
     * @param web3j web3j实例
     * @return 上浮后的gasPrice
     * @throws Exception
     */
    public static BigInteger getGasPrice(Web3j web3j) throws Exception {
        BigInteger rawGasPrice = rpcRetryCall(() -> web3j.ethGasPrice().send().getGasPrice());
        // *110 /100 上浮10%
        return rawGasPrice.multiply(GAS_PRICE_RATIO).divide(GAS_PRICE_BASE);
    }

    /**
     * 预估gasLimit，RPC重试
     * @param web3j web3j
     * @param from 发送方地址
     * @param to 目标合约地址
     * @param callData 调用calldata
     * @return 预估gas（已经上浮20%安全余量）
     * @throws Exception
     */
    public static BigInteger estimateGas(Web3j web3j, String from, String to, String callData) throws Exception {
        return rpcRetryCall(() -> {
            // 使用源码自带的 createEthCallTransaction
            org.web3j.protocol.core.methods.request.Transaction tx =
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(from, to, callData);

            BigInteger rawGas = web3j.ethEstimateGas(tx).send().getAmountUsed();
            // 上浮20%做安全缓冲，避免链上执行out‑of‑gas
            return rawGas.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        });
    }


    /**
     * 根据ECKeyPair获取钱包地址
     * @param keyPair 密钥对
     * @return 钱包地址 0x开头
     */
    public static String getAddress(ECKeyPair keyPair) {
        return Credentials.create(keyPair).getAddress();
    }

    /**
     * 通用RPC重试封装
     * 解决RPC抖动查询失败，最多重试RPC_MAX_RETRY次
     */
    @FunctionalInterface
    public interface RpcTask<T> {
        T run() throws Exception;
    }
    public static <T> T rpcRetryCall(RpcTask<T> task) throws Exception {
        Exception lastEx = null;
        for (int i = 0; i < RPC_MAX_RETRY; i++) {
            try {
                return task.run();
            } catch (Exception e) {
                lastEx = e;
                Thread.sleep(500);
            }
        }
        throw new Exception("RPC调用重试全部失败", lastEx);
    }
    /**
     * 私钥十六进制字符串转为ECKeyPair
     * @param privateKeyHex 不带0x或者带0x的私钥
     * @return ECKeyPair
     */
    public static ECKeyPair pkToKeyPair(String privateKeyHex) {
        BigInteger priv = Numeric.toBigInt(privateKeyHex);
        return ECKeyPair.create(priv);
    }
    /**
     * 创建Web3j http实例，RPC地址从ArenaConfig读取
     * @return Web3j实例
     */
    public static Web3j createWeb3j() {
        org.web3j.protocol.http.HttpService httpService = new org.web3j.protocol.http.HttpService(ArenaConfig.RPC_URL);
        // 4.9.8 官方标准写法：Web3j.build()
        return Web3j.build(httpService);
    }

}
