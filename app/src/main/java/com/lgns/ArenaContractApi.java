package com.lgns;

import android.util.Log;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ArenaContractApi {
    // 值统一收敛到 ArenaConfig，类内保留字段名以最小改动
    public static final String ARENA_ADDR = ArenaConfig.ARENA_ADDR;
    public static final long CHAIN_ID = ArenaConfig.CHAIN_ID;
    public static final long TX_WAIT_POLL_MS = ArenaConfig.TX_WAIT_POLL_MS;
    public static final int TX_WAIT_MAX_CNT =
            (int) (ArenaConfig.TX_TIMEOUT_SEC * 1000L / ArenaConfig.TX_WAIT_POLL_MS);

    private static volatile String cachedTokenAddress;

    /** PlayerSummary 输出 ABI 原型(共用,避免重复构造) */
    private static final List<TypeReference<?>> PLAYER_SUMMARY_OUTPUTS = buildPlayerSummaryOutputs();

    /**
     * Represents a summary of a player's game statistics and financial information.
     */
    public static class PlayerSummary {
        public BigInteger walletBalance;
        public BigInteger allowance;
        public BigInteger prize;
        public BigInteger inMatchDeposit;
        public BigInteger arenaTotal;
        public BigInteger battles;
        public BigInteger championships;
        public BigInteger jiaziTotalGranted;
        public BigInteger jiaziReleased;
        public BigInteger jiaziClaimable;
        public boolean jiaziTruncated;
        public BigInteger entriesToday;
        public BigInteger entriesRemainingToday;
        public BigInteger roundsFromPrize;
        public boolean nextEntryFromPrize;
        public boolean seatedInCurrentRound;
    }

    // ==================================================================
    // 只读查询
    // ==================================================================

    public static PlayerSummary callPlayerSummary(Web3j web3j, String playerAddr) throws Exception {
        String data = encodePlayerSummaryData(playerAddr);
        EthCall ethCall = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (ethCall.hasError()) {
            throw new Exception("playerSummary rpc error:" + ethCall.getError().getMessage());
        }
        return decodePlayerSummary(ethCall.getValue());
    }

    /**
     * 批量查询 playerSummary
     * 用 web3j 的 sendAsync() + CompletableFuture 做真并发(OkHttp 默认 5 连接/host * 池)
     * 失败降级到逐个 send();单批最多 ArenaConfig.BATCH_CALL_SIZE,超过自动切片
     */
    public static Map<String, PlayerSummary> callPlayerSummaryBatch(
            Web3j web3j, Collection<String> playerAddrs) {
        Map<String, PlayerSummary> out = new LinkedHashMap<>();
        if (playerAddrs == null || playerAddrs.isEmpty()) return out;
        List<String> addrs = new ArrayList<>(playerAddrs);
        for (int start = 0; start < addrs.size(); start += ArenaConfig.BATCH_CALL_SIZE) {
            int end = Math.min(start + ArenaConfig.BATCH_CALL_SIZE, addrs.size());
            List<String> slice = addrs.subList(start, end);
            try {
                out.putAll(doOneBatchViaSendAsync(web3j, slice));
            } catch (Throwable t) {
                // 兜底:逐个查
                for (String a : slice) {
                    try { out.put(a, callPlayerSummary(web3j, a)); } catch (Exception ignore) {}
                }
            }
        }
        return out;
    }

    private static Map<String, PlayerSummary> doOneBatchViaSendAsync(Web3j web3j, List<String> slice) throws Exception {
        List<CompletableFuture<EthCall>> futures = new ArrayList<>(slice.size());
        for (String addr : slice) {
            futures.add(web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, encodePlayerSummaryData(addr)),
                    DefaultBlockParameterName.LATEST).sendAsync());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(ArenaConfig.RPC_CALL_TIMEOUT_SEC * 2L, TimeUnit.SECONDS);
        Map<String, PlayerSummary> out = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            EthCall ec = futures.get(i).get();
            if (ec.hasError()) continue;
            try { out.put(slice.get(i), decodePlayerSummary(ec.getValue())); } catch (Exception ignore) {}
        }
        return out;
    }

    public static BigInteger callJiaziCount(Web3j web3j, String owner) throws Exception {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(owner));
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Uint256.class));
        Function f = new Function("jiaziCount", inputs, outputs);
        String callData = FunctionEncoder.encode(f);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                DefaultBlockParameterName.LATEST
        ).send();
        if (res.hasError()) throw new Exception("jiaziCount error:" + res.getError().getMessage());
        List<Type> dec = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
        return ((Uint256) dec.get(0)).getValue();
    }

    public static Integer callActivityOfMatchesToday(Web3j web3j, String owner) {
        try {
            List<Type> inputs = new ArrayList<Type>();
            inputs.add(new Address(owner));
            List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            Function f = new Function("activityOf", inputs, outputs);
            String callData = FunctionEncoder.encode(f);
            EthCall res = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                    DefaultBlockParameterName.LATEST
            ).send();
            if (res.hasError()) return null;
            List<Type> dec = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
            BigInteger val = ((Uint256) dec.get(2)).getValue();
            return val.intValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * playerHistoryLatest(player,count) selector:0x0810151a
     * 修复:增加 hex 解析异常捕获,畸形数据直接返回 null,不向外抛 NumberFormatException
     */
    public static Integer callPlayerHistoryLatestWendToday(Web3j web3j, String owner) {
        try {
            String addrNo0x = Numeric.cleanHexPrefix(owner);
            String countHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(100), 64);
            String data = "0x0810151a"
                    + String.format("%64s", addrNo0x).replace(' ', '0')
                    + countHex;
            EthCall res = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                    DefaultBlockParameterName.LATEST
            ).send();
            if (res.hasError()) return null;
            String hexRaw = Numeric.cleanHexPrefix(res.getValue());
            if (hexRaw.length() < 64) return 0;

            int arrOffset;
            try { arrOffset = Integer.parseInt(hexRaw.substring(0, 64), 16) * 2; }
            catch (NumberFormatException e) { return null; }
            if (arrOffset + 64 > hexRaw.length()) return null;
            int arrLen;
            try { arrLen = Integer.parseInt(hexRaw.substring(arrOffset, arrOffset + 64), 16); }
            catch (NumberFormatException e) { return null; }

            int itemPtr = arrOffset + 64;
            int winCount = 0;
            long nowSec = System.currentTimeMillis() / 1000L;
            long todayDayId = (nowSec + ArenaConfig.DAY_OFFSET) / 86400L;

            for (int idx = 0; idx < arrLen; idx++) {
                int base = itemPtr + idx * 512;
                if (base + 512 > hexRaw.length()) break;
                String tsHex = hexRaw.substring(base + 64, base + 128);
                long ts;
                try { ts = new BigInteger(tsHex, 16).longValue(); }
                catch (NumberFormatException e) { continue; }
                long rowDayId = (ts + ArenaConfig.DAY_OFFSET) / 86400L;
                String wonHex = hexRaw.substring(base + 192, base + 256);
                boolean won = !"0000000000000000000000000000000000000000000000000000000000000000".equals(wonHex);
                if (won && rowDayId == todayDayId) winCount++;
            }
            return winCount;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTokenAddress(Web3j web3j) throws Exception {
        String cached = cachedTokenAddress;
        if (cached != null) return cached;
        List<Type> inputs = new ArrayList<Type>();
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Address.class));
        Function f = new Function("TOKEN", inputs, outputs);
        String data = FunctionEncoder.encode(f);
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (call.hasError()) throw new Exception("read TOKEN address fail:" + call.getError().getMessage());
        List<Type> dec = FunctionReturnDecoder.decode(call.getValue(), f.getOutputParameters());
        String addr = ((Address) dec.get(0)).getValue();
        cachedTokenAddress = addr;
        return addr;
    }

    // ==================================================================
    // 交易广播三段式:签名 → PreBroadcastHook → 广播 → 等回执
    // ==================================================================

    /** 广播前 hook:让业务方先把 txHash+rawTx 落 SP */
    public interface PreBroadcastHook {
        void onSigned(String txHash, String rawTxHex);
    }

    /** 让业务侧区分 reverted / timeout,便于写 outcome */
    public static class RevertedTxException extends Exception {
        public final String txHash;
        public RevertedTxException(String msg, String txHash) { super(msg); this.txHash = txHash; }
    }

    public static class SignedTx {
        public final String rawTxHex;
        public final String txHash;
        public SignedTx(String rawTxHex, String txHash) {
            this.rawTxHex = rawTxHex;
            this.txHash = txHash;
        }
    }

    /**
     * 只做签名,不广播
     * txHash = keccak256(signedRLP),本地算,不依赖 RPC
     */
    public static SignedTx signRawTx(ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                     BigInteger gasLimit, String toAddr, String data) {
        Credentials credentials = Credentials.create(ecKeyPair);
        RawTransaction rawTx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, toAddr, BigInteger.ZERO, data);
        byte[] signedBytes = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
        String hexSigned = Numeric.toHexString(signedBytes);
        byte[] hashBytes = Hash.sha3(signedBytes);
        String txHash = Numeric.toHexString(hashBytes);
        return new SignedTx(hexSigned, txHash);
    }

    /**
     * 广播一次;若节点返回 already known/imported,视为已在链上,吞掉异常继续 waitReceipt
     * @return RPC 返回的 txHash(可能与本地算的一致);alreadyInFlight 时返回 null
     */
    public static String broadcastRaw(Web3j web3j, String hexSigned) throws Exception {
        EthSendTransaction sendResp = web3j.ethSendRawTransaction(hexSigned).send();
        if (sendResp.hasError()) {
            String err = sendResp.getError().getMessage();
            Exception wrap = new Exception(err == null ? "" : err);
            if (Web3AndroidUtil.isAlreadyInFlight(wrap)) {
                return null;
            }
            throw new Exception("sendRawTx error: " + err);
        }
        return sendResp.getTransactionHash();
    }

    /** 等回执(带 revert / timeout 分类,方便写 outcome) */
    public static TransactionReceipt waitReceiptLocal(Web3j web3j, String txHash) throws Exception {
        for (int i = 0; i < TX_WAIT_MAX_CNT; i++) {
            EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResp.getTransactionReceipt().isPresent()) {
                TransactionReceipt rcpt = receiptResp.getTransactionReceipt().get();
                if (!rcpt.isStatusOK()) {
                    throw new RevertedTxException("tx reverted, hash=" + txHash, txHash);
                }
                return rcpt;
            }
            Thread.sleep(TX_WAIT_POLL_MS);
        }
        throw new Exception("wait receipt timeout, hash=" + txHash);
    }

    private static TransactionReceipt sendRawTxAndWaitReceipt(
            Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
            String toAddr, String data, PreBroadcastHook preBroadcastHook) throws Exception {
        SignedTx signed = signRawTx(ecKeyPair, nonce, gasPrice, gasLimit, toAddr, data);
        if (preBroadcastHook != null) {
            preBroadcastHook.onSigned(signed.txHash, signed.rawTxHex);
        }
        String rpcHash = broadcastRaw(web3j, signed.rawTxHex);
        String txHash = rpcHash != null ? rpcHash : signed.txHash;
        return waitReceiptLocal(web3j, txHash);
    }

    /** 旧签名保留兼容,内部委托到新版 */
    private static TransactionReceipt sendRawTxAndWaitReceipt(
            Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
            String toAddr, String data) throws Exception {
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, toAddr, data, null);
    }

    // ==================================================================
    // SetCode (EIP-7702) 交易:接受预签名的 rawTxHex
    // ==================================================================

    public static TransactionReceipt sendSetCodeRawAndWaitReceipt(
            Web3j web3j, String rawTxHex, String localTxHash,
            PreBroadcastHook preBroadcastHook) throws Exception {
        if (preBroadcastHook != null) {
            preBroadcastHook.onSigned(localTxHash, rawTxHex);
        }
        String rpcHash = broadcastRaw(web3j, rawTxHex);
        String txHash = rpcHash != null ? rpcHash : localTxHash;
        return waitReceiptLocal(web3j, txHash);
    }

    // ==================================================================
    // 业务写入函数(带 / 不带 PreBroadcastHook 两版)
    // ==================================================================

    public static TransactionReceipt doApproveArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger amount,
                                                    BigInteger nonce, BigInteger gasPrice,
                                                    PreBroadcastHook hook) throws Exception {
        String tokenContract = readTokenAddress(web3j);
        List<Type> inputs = Arrays.<Type>asList(new Address(ARENA_ADDR), new Uint256(amount));
        Function func = new Function("approve", inputs, new ArrayList<TypeReference<?>>());
        String txData = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, tokenContract, txData, hook);
    }

    public static TransactionReceipt doApproveArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger amount,
                                                    BigInteger nonce, BigInteger gasPrice) throws Exception {
        return doApproveArena(web3j, ecKeyPair, amount, nonce, gasPrice, null);
    }

    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice, PreBroadcastHook hook) throws Exception {
        return doEnterArena(web3j, ecKeyPair, nonce, gasPrice, BigInteger.valueOf(1500000), hook);
    }

    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice, BigInteger gasLimit,
                                                  PreBroadcastHook hook) throws Exception {
        Function func = new Function("enterArena", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data, hook);
    }

    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice) throws Exception {
        return doEnterArena(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    public static void doClaimJiazi(Web3j web3j, ECKeyPair ecKeyPair, BigInteger start, BigInteger count,
                                    BigInteger nonce, BigInteger gasPrice, PreBroadcastHook hook) throws Exception {
        List<Type> inputs = Arrays.<Type>asList(new Uint256(start), new Uint256(count));
        Function func = new Function("claimJiazi", inputs, new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(300000);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data, hook);
    }

    public static void doClaimJiazi(Web3j web3j, ECKeyPair ecKeyPair, BigInteger start, BigInteger count,
                                    BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimJiazi(web3j, ecKeyPair, start, count, nonce, gasPrice, null);
    }

    public static void doClaimLineage(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                      PreBroadcastHook hook) throws Exception {
        Function func = new Function("claimLineage", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice,
                BigInteger.valueOf(300000), ARENA_ADDR, data, hook);
    }

    public static void doClaimLineage(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimLineage(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    public static void doClaimRewards(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                      PreBroadcastHook hook) throws Exception {
        Function func = new Function("claimRewards", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice,
                BigInteger.valueOf(200000), ARENA_ADDR, data, hook);
    }

    public static void doClaimRewards(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimRewards(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    // ==================================================================
    // 干跑:estimateGas + 余额检查,不上链
    // @return null = 可执行;非 null = 拒绝原因
    // ==================================================================
    public static String previewEnter(Web3j web3j, String addr, ECKeyPair keyPair) {
        try {
            PlayerSummary s = callPlayerSummary(web3j, addr);
            if (s.seatedInCurrentRound) return "已入座";
            if (s.entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) return "今日已打满12场";
            boolean payable = s.prize.compareTo(ArenaConfig.ENTRY_FEE) >= 0
                    || s.walletBalance.compareTo(ArenaConfig.ENTRY_FEE) >= 0;
            if (!payable) {
                double prizeD = new java.math.BigDecimal(s.prize)
                        .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                return String.format("奖金 %.2f、钱包不足以 100", prizeD);
            }
            Function f = new Function("enterArena", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
            String data = FunctionEncoder.encode(f);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger gas = Web3AndroidUtil.estimateGasWithCeiling(web3j, addr, ARENA_ADDR, data);
            BigInteger need = gas.multiply(gasPrice);
            BigInteger nativeBal = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
            if (nativeBal.compareTo(need) < 0) {
                return "原生币不足,需 " + need + " 现有 " + nativeBal;
            }
            return null;
        } catch (Exception e) {
            return Web3AndroidUtil.safeMessage(e);
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static List<TypeReference<?>> buildPlayerSummaryOutputs() {
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        for (int i = 0; i < 10; i++) outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Bool.class));          // 10 jiaziTruncated
        outputs.add(TypeReference.create(Uint256.class));       // 11 entriesToday
        outputs.add(TypeReference.create(Uint256.class));       // 12 entriesRemainingToday
        outputs.add(TypeReference.create(Uint256.class));       // 13 roundsFromPrize
        outputs.add(TypeReference.create(Bool.class));          // 14 nextEntryFromPrize
        outputs.add(TypeReference.create(Bool.class));          // 15 seatedInCurrentRound
        return outputs;
    }

    private static String encodePlayerSummaryData(String playerAddr) {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(playerAddr));
        Function func = new Function("playerSummary", inputs, PLAYER_SUMMARY_OUTPUTS);
        return FunctionEncoder.encode(func);
    }

    private static PlayerSummary decodePlayerSummary(String hexValue) {
        PlayerSummary s = new PlayerSummary();
        try {
            List<Type> dec = FunctionReturnDecoder.decode(hexValue, castOutputs(buildPlayerSummaryOutputs()));
            if (dec.size() < 16) return s;
            s.walletBalance = safeUint(dec, 0);
            s.allowance = safeUint(dec, 1);
            s.prize = safeUint(dec, 2);
            s.inMatchDeposit = safeUint(dec, 3);
            s.arenaTotal = safeUint(dec, 4);
            s.battles = safeUint(dec, 5);
            s.championships = safeUint(dec, 6);
            s.jiaziTotalGranted = safeUint(dec, 7);
            s.jiaziReleased = safeUint(dec, 8);
            s.jiaziClaimable = safeUint(dec, 9);
            s.jiaziTruncated = safeBool(dec, 10);
            s.entriesToday = safeUint(dec, 11);
            s.entriesRemainingToday = safeUint(dec, 12);
            s.roundsFromPrize = safeUint(dec, 13);
            s.nextEntryFromPrize = safeBool(dec, 14);
            s.seatedInCurrentRound = safeBool(dec, 15);
        } catch (Throwable t) {
            Log.w("ArenaContractApi", "decodePlayerSummary partial fail: " + t.getClass().getSimpleName());
        }
        return s;
    }

    private static BigInteger safeUint(List<Type> dec, int idx) {
        try { return ((Uint256) dec.get(idx)).getValue(); } catch (Throwable t) { return BigInteger.ZERO; }
    }

    private static boolean safeBool(List<Type> dec, int idx) {
        try { return ((Bool) dec.get(idx)).getValue(); } catch (Throwable t) { return false; }
    }

    /**
     * web3j decode() 签名要求 List&lt;TypeReference&lt;Type&gt;&gt;,
     * 但 TypeReference.create() 返回 TypeReference&lt;?&gt;;
     * 运行时 ABI 解码保证类型正确,此处仅做编译期桥接.
     */
    @SuppressWarnings("unchecked")
    private static List<TypeReference<Type>> castOutputs(List<TypeReference<?>> outputs) {
        return (List<TypeReference<Type>>) (List<?>) outputs;
    }

    /**
     * 锦标赛统计:批量查询每个地址的总场次 + 冠军次数
     * 对标 Go championships.go ChampionshipCounts
     * playerHistoryLatest(player,count) selector: 0x0810151a
     * 返回 Map: addr → [totalCount, winCount]
     */
    public static Map<String, long[]> callChampionshipCounts(
            Web3j web3j, Collection<String> playerAddrs) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (playerAddrs == null || playerAddrs.isEmpty()) return out;

        List<String> addrs = new ArrayList<>(playerAddrs);
        List<CompletableFuture<EthCall>> futures = new ArrayList<>(addrs.size());
        for (String addr : addrs) {
            String addrPadded = String.format("%64s", Numeric.cleanHexPrefix(addr)).replace(' ', '0');
            String countHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(100), 64);
            String data = "0x0810151a" + addrPadded + countHex;
            futures.add(web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                    DefaultBlockParameterName.LATEST).sendAsync());
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ArenaConfig.RPC_CALL_TIMEOUT_SEC * 2L, TimeUnit.SECONDS);
        } catch (Exception ignore) {}

        for (int i = 0; i < futures.size(); i++) {
            String addr = addrs.get(i);
            try {
                EthCall ec = futures.get(i).get();
                if (ec.hasError()) continue;
                String hex = Numeric.cleanHexPrefix(ec.getValue());
                long[] counts = decodeChampionships(hex);
                if (counts != null) out.put(addr, counts);
            } catch (Exception ignore) {}
        }

        for (String addr : addrs) {
            if (!out.containsKey(addr)) {
                out.put(addr, new long[]{0, 0});
            }
        }
        return out;
    }

    /**
     * 解码 playerHistoryLatest 返回的 struct[] + total
     * @return [totalCount, winCount] 或 null
     */
    private static long[] decodeChampionships(String hex) {
        if (hex.length() < 128) return new long[]{0, 0};
        int arrPtr, arrLen, total;
        try {
            arrPtr = Integer.parseInt(hex.substring(0, 64), 16) * 2;
            if (arrPtr + 64 > hex.length()) return null;
            arrLen = Integer.parseInt(hex.substring(arrPtr, arrPtr + 64), 16);
            total = Integer.parseInt(hex.substring(64, 128), 16);
        } catch (NumberFormatException e) { return null; }

        int itemPtr = arrPtr + 64;
        int winCount = 0;
        for (int idx = 0; idx < arrLen; idx++) {
            int base = itemPtr + idx * 512;
            if (base + 512 > hex.length()) break;
            String settledHex = hex.substring(base + 128, base + 192);
            boolean settled = !"0000000000000000000000000000000000000000000000000000000000000000".equals(settledHex);
            String wonHex = hex.substring(base + 192, base + 256);
            boolean won = !"0000000000000000000000000000000000000000000000000000000000000000".equals(wonHex);
            if (settled && won) winCount++;
        }
        return new long[]{total, winCount};
    }
}