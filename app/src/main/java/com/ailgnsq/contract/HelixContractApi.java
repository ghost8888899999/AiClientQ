package com.ailgnsq.contract;

import com.ailgnsq.model.HelixConfig;
import com.ailgnsq.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HELIX（AiLGNS）侧的链上访问层：读数、写交易两套都在这个类。
 *
 * <p>编码口径：calldata 全部自己拼——选择器 = {@code keccak256(签名字符串)} 前 4 字节，
 * 参数按 32 字节左补齐顺序排。<b>刻意不走 {@code FunctionEncoder}</b>：它要先由
 * 「Java 类型列表」反推签名文本，而 {@link HelixConfig} 里存的就是已经链上实测过的签名文本，
 * 中间多一道类型映射等于多一处可能写错、而且错了也对不上那张表的地方。
 * <b>解码一律手工按 32 字节切字</b>（复用 {@link ArenaContractApi#decodeUint256At}），
 * 因为 {@code FunctionReturnDecoder} 内部调用 {@code BigInteger(byte[],int,int)} 这个 Java 9
 * 才有的构造器，低版本 Android 上直接 {@code NoSuchMethodError} 崩溃——这个坑已经在擂台那条线上踩过一次。</p>
 *
 * <p>失败语义：每个公开方法查不到就抛异常，<b>绝不返回 0 代替「没查到」</b>。
 * {@code E:\py\AIlgns} 那两份脚本把每个查询都写成 {@code try/except: pass}，签名写错 → revert →
 * 当成 0 → 脚本静默空转，看上去跑完了一笔没发。这里把判定交给页面：调用方失败就显示 {@code --}，
 * 并在同一段落里标出原因。</p>
 *
 * <p>写入部分（文件末尾那一段 {@code doXxx}）<b>不另开一套交易流水线</b>：签名、广播、等回执
 * 全部复用 {@link ArenaContractApi} 已经跑通过的那套零件（{@code signRawTx} →
 * {@link ArenaContractApi.PreBroadcastHook} → {@code broadcastRaw} → {@code waitReceiptLocal}）。
 * 之所以不直接调它的 {@code sendRawTxAndWaitReceipt}：那个方法是 private，而把它括成 public
 * 会把擂台那侧的改动面拉开。这里只组一行四步，失败分类（revert / OOG / 超时）与擂台保持一致。</p>
 */
public class HelixContractApi {

    /**
     * 合约 revert（业务失败，不是网络问题）。
     *
     * <p>消息刻意带上 {@code "execution reverted: "} 前缀：{@link Web3AndroidUtil#isRevert}
     * 靠这个关键字判定「重试也没用」，少了它就会被当成网络错误重试 6 次、退避到几十秒，
     * 而 revert 的原因是确定性的。</p>
     */
    public static class RevertException extends Exception {
        /** 合约里的 require 文案（{@code Error(string)} 解出来的），拿不到时为 null */
        public final String reason;
        /** 原始 revert 数据（自定义 error 只有这段） */
        public final String data;

        public RevertException(String reason, String data, String rawMessage) {
            super("execution reverted: " + (reason != null ? reason
                    : (rawMessage != null ? rawMessage : (data == null ? "无数据" : data))));
            this.reason = reason;
            this.data = data;
        }
    }

    // region 底层：编码 + 调用

    /**
     * 签名 → 4 字节选择器。
     *
     * <p>{@code Hash.sha3} 用的 keccak-256 与步骤 0 那个验证工具（BouncyCastle
     * {@code KeccakDigest}）同源，所以这里算出来的值和当时链上实测的那份表可以直接比——
     * {@link #abiSelfCheck()} 就是干这件事的。</p>
     */
    private static String selector(String sig) {
        return "0x" + Numeric.toHexStringNoPrefix(Hash.sha3(sig.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
    }

    /** 无参数视图函数的 calldata：只有选择器 */
    private static String callNoArg(String sig) {
        return selector(sig);
    }

    /** 单个 uint256 入参 */
    private static String callUint(String sig, BigInteger v) {
        return selector(sig) + pad32(v.toString(16));
    }

    /** 单个 address 入参 */
    private static String callAddr(String sig, String addr) {
        return selector(sig) + pad32(Numeric.cleanHexPrefix(addr).toLowerCase());
    }

    /** 两个 address 入参（{@code allowance(owner,spender)}）：参数顺序就是签名里写的顺序 */
    private static String callAddrAddr(String sig, String a, String b) {
        return selector(sig) + pad32(Numeric.cleanHexPrefix(a).toLowerCase())
                + pad32(Numeric.cleanHexPrefix(b).toLowerCase());
    }

    /** 补齐到 64 个 hex（一个 32 字节字），不足位在左边补 0 */
    private static String pad32(String hex) {
        if (hex.length() >= 64) return hex.substring(hex.length() - 64);
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64 - hex.length(); i++) sb.append('0');
        return sb.append(hex).toString();
    }

    /**
     * 发一次 {@code eth_call}，返回原始 hex。
     *
     * <p>走 {@link Web3AndroidUtil#rpcRetryCall}：网络类错误自动退避重试并轮换节点，
     * revert 直接抛 {@link RevertException} 不重试。</p>
     *
     * @param from 发起地址，只读探针用它判断 msg.sender（普通查询传 null 即可）
     */
    public static String ethCall(Web3j web3j, String from, String to, String data) throws Exception {
        return Web3AndroidUtil.rpcRetryCall(() -> {
            EthCall res = web3j.ethCall(Transaction.createEthCallTransaction(from, to, data),
                    DefaultBlockParameterName.LATEST).send();
            if (!res.hasError()) {
                String v = res.getValue();
                // 有些节点把 revert 放在 result 而不是 error：0x 开头的短数据按失败处理
                if (v == null || "0x".equals(v)) {
                    throw new RevertException(null, v, "返回空数据（revert）");
                }
                return v;
            }
            String msg = res.getError() == null ? "" : res.getError().getMessage();
            String data0 = res.getError() == null ? null : res.getError().getData();
            if (msg != null && msg.toLowerCase().contains("revert")) {
                throw new RevertException(extractReason(msg, data0), data0, msg);
            }
            // 节点自己报错（超时、块不存在）：交给外层重试
            throw new Exception("eth_call 失败 to=" + to + "：" + msg);
        });
    }

    /** 无 from 的普通只读调用 */
    public static String ethCall(Web3j web3j, String to, String data) throws Exception {
        return ethCall(web3j, null, to, data);
    }

    /**
     * 从错误信息/数据里挖 require 文案。
     *
     * <p>三条路依次试：① 节点已经把 reason 拼在 message 里（{@code "execution reverted: Turbine: zero amount"}）；
     * ② data 是标准的 {@code Error(string)}（选择器 0x08c379a0）自己解 ABI；
     * ③ 自定义 error（如 {@code 0xfb8f41b2}）解不出文案，原样回吐 hex——这本身就是有用信息，
     * 说明「进了函数体、撞到一个带参数的自定义错误」，和「选择器没命中」是两回事。</p>
     */
    private static String extractReason(String message, String data) {
        if (message != null) {
            int i = message.toLowerCase().indexOf("reverted:");
            if (i >= 0) {
                String tail = message.substring(i + "reverted:".length()).trim();
                int bar = tail.indexOf(": 0x");
                if (bar > 0) tail = tail.substring(0, bar);
                int brace = tail.indexOf(" ||");
                if (brace > 0) tail = tail.substring(0, brace);
                if (!tail.isEmpty()) return tail;
            }
        }
        if (data != null && data.startsWith("0x08c379a0")) {
            try {
                // 布局：8 个 hex 的选择器 + offset 字 + length 字 + 字符串数据，
                // 偏移量必须先把那 4 字节选择器让开，否则读到的是字符串开头的数
                String hex = Numeric.cleanHexPrefix(data);
                int lenWord = 8 + 64;
                BigInteger len = new BigInteger(hex.substring(lenWord, lenWord + 64), 16);
                int chars = len.intValue() * 2;
                int strFrom = 8 + 64 * 2;
                String sHex = hex.substring(strFrom, Math.min(strFrom + chars, hex.length()));
                return new String(Numeric.hexStringToByteArray(sHex), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                // 长度字段被截断就回落到原始 message
            }
        }
        return data;
    }

    /** 读一个 uint256 返回值 */
    private static BigInteger readUint(Web3j web3j, String to, String data) throws Exception {
        return ArenaContractApi.decodeUint256At(ethCall(web3j, to, data), 0);
    }

    /**
     * 解 {@code uint256[]} 返回值。
     *
     * <p>动态数组的 ABI 布局固定是「[0]=数据区偏移、[1]=长度、[2..]=元素」，
     * 长度直接读第 2 个字，不猜。越界的元素按缺省 0 处理并截断到 {@code maxLen}。</p>
     */
    public static List<BigInteger> decodeUintArray(String hexWith0x, int maxLen) {
        List<BigInteger> out = new ArrayList<>();
        String hex = Numeric.cleanHexPrefix(hexWith0x);
        if (hex == null || hex.length() < 128) return out;
        BigInteger len = ArenaContractApi.decodeUint256At(hexWith0x, 1);
        int n = Math.min(len.intValue(), maxLen);
        for (int i = 0; i < n; i++) {
            BigInteger v = ArenaContractApi.decodeUint256At(hexWith0x, 2 + i);
            if (v == null) break;
            out.add(v);
        }
        return out;
    }

    /** 解 {@code address} 返回值（取最后一个字的后 20 字节） */
    public static String decodeAddress(String hexWith0x, int wordIndex) {
        String hex = Numeric.cleanHexPrefix(hexWith0x);
        int from = wordIndex * 64 + 24;
        if (hex == null || hex.length() < from + 40) return null;
        return "0x" + hex.substring(from, from + 40);
    }

    // endregion

    // region 价格源

    /** TWAP 读数：价格 + 更新时间戳（价格是「每 1e18 AiLGNS 折多少 DAI wei」，与主池口径一致） */
    public static class OracleView {
        public BigInteger lastTwapPrice;
        public BigInteger lastTimestamp;
    }

    public static OracleView callOracle(Web3j web3j) throws Exception {
        OracleView v = new OracleView();
        v.lastTwapPrice = readUint(web3j, HelixConfig.ORACLE_ADDR, callNoArg(HelixConfig.SIG_LAST_TWAP_PRICE));
        v.lastTimestamp = readUint(web3j, HelixConfig.ORACLE_ADDR, callNoArg(HelixConfig.SIG_LAST_TIMESTAMP));
        return v;
    }

    /**
     * 主池瞬时价：{@code reserve1(DAI) * 1e18 / reserve0(AiLGNS)}。
     *
     * <p>两个币都是 18 位小数，所以按比例放大 1e18 就得到「1 AiLGNS 值多少 DAI wei」，
     * 与 oracle 的 {@code lastTwapPrice} 同口径，可以直接摆在一起比——实测两者偏差约 1/10000。
     * 这一步是校验，不是价格源：瞬时价能被单笔交易拉动，正式算账要用 {@link #callQuote}。</p>
     */
    public static BigInteger callMainPairSpotPrice(Web3j web3j) throws Exception {
        String hex = ethCall(web3j, HelixConfig.PAIR_MAIN_ADDR, callNoArg(HelixConfig.SIG_GET_RESERVES));
        BigInteger r0 = ArenaContractApi.decodeUint256At(hex, 0);   // AiLGNS
        BigInteger r1 = ArenaContractApi.decodeUint256At(hex, 1);   // DAI
        if (r0 == null || r0.signum() == 0) return null;
        return r1.multiply(BigInteger.TEN.pow(HelixConfig.DEC_AILGNS)).divide(r0);
    }

    /** {@code Turbine.quote(uint256)} 的三返回值 */
    public static class Quote {
        public BigInteger amountIn;
        /** 输出 DAI（未含手续费） */
        public BigInteger out;
        /** 手续费 DAI，实测恒等于 {@code out / 40}（feeBps 250 / 10000） */
        public BigInteger fee;
        /** {@code out + fee}：从钱包实际扣走的量，也就是 activate 该填的 maxDaiIn */
        public BigInteger total;
    }

    /**
     * 问合约 itself 要报价——这是算 {@code activate} 的 maxDaiIn 的官方口径。
     *
     * <p>实测三个含义：以 {@code quote(1e18)} 为例返回
     * {@code (824883026875584243537, 20622075671889606088, 845505102547473849625)}，
     * 中间那个正好是第一个的 1/40，第三个是前两个之和。
     * 零地址也能调，不消耗 gas。</p>
     *
     * <p><b>入参不能为 0</b>：会被 Uniswap 库以 {@code INSUFFICIENT_OUTPUT_AMOUNT} revert，
     * 从外面看却像「这个函数不存在」——步骤 0 就是靠这个把 {@code quote} 认出来的。</p>
     */
    public static Quote callQuote(Web3j web3j, BigInteger amountInAiLgnsWei) throws Exception {
        String hex = ethCall(web3j, HelixConfig.TURBINE_ADDR,
                callUint(HelixConfig.SIG_TURBINE_QUOTE, amountInAiLgnsWei));
        Quote q = new Quote();
        q.amountIn = amountInAiLgnsWei;
        q.out = ArenaContractApi.decodeUint256At(hex, 0);
        q.fee = ArenaContractApi.decodeUint256At(hex, 1);
        q.total = ArenaContractApi.decodeUint256At(hex, 2);
        return q;
    }

    // endregion

    // region 涡轮参数与持仓

    public static class TurbineParams {
        public BigInteger feeBps;
        public BigInteger burnBps;
        public BigInteger lockSeconds;
        public BigInteger lockCount;
        public BigInteger maxFeeBps;
        public BigInteger totalClaimed;
        /** 手续费基数：实测 {@code feeBps/maxFeeBps} 的分母是 BPS()=10000，但读不到时按 10000 显示 */
        public BigInteger bps;
    }

    public static TurbineParams callTurbineParams(Web3j web3j) throws Exception {
        TurbineParams p = new TurbineParams();
        p.feeBps = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_FEE_BPS));
        p.burnBps = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_BURN_BPS));
        p.lockSeconds = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_LOCK_SECONDS));
        p.totalClaimed = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_TOTAL_CLAIMED));
        // 下面三个不是关键路径：读不到就让对应行显示 --，不能连累前面四项
        try { p.lockCount = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_LOCK_COUNT)); } catch (Exception ignore) {}
        try { p.maxFeeBps = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg(HelixConfig.SIG_MAX_FEE_BPS)); } catch (Exception ignore) {}
        try { p.bps = readUint(web3j, HelixConfig.TURBINE_ADDR, callNoArg("BPS()")); } catch (Exception ignore) {}
        return p;
    }

    /** 单个账号在 HELIX 侧的全部持仓读数；某项查不到时那一项为 null（不当 0） */
    public static class Position {
        public String addr;
        /** 涡轮：锁仓到期、已解锁可领的 AiLGNS（非 0 就先走 {@code claimUnlocked}） */
        public BigInteger claimable;
        /** 涡轮：仍在锁仓里的 AiLGNS */
        public BigInteger locked;
        /** 涡轮：已领取、还没激活的 AiLGNS——也正好是 {@code activate} 能用的上限 */
        public BigInteger claimed;
        /** 涡轮：锁仓笔数（{@code locksOf} 数组长度） */
        public Integer lockCount;
        /** 金库：待领的静态释放 */
        public BigInteger vaultPending;
        /** 账本：累计功率 */
        public BigInteger power;
        /** 各项失败原因，null 下标 → 文案；页面用它标出「这项没查到」而不是显示 0 */
        public final List<String> errors = new ArrayList<>();
    }

    /** 查询项与 {@link Position} 字段的对应关系，批量与降级串行共用一套口径 */
    private static final String[] POS_SIGS = {
            HelixConfig.SIG_CLAIMABLE_OF,
            HelixConfig.SIG_LOCKED_BALANCE_OF,
            HelixConfig.SIG_CLAIMED_BALANCE,
            HelixConfig.SIG_LOCKS_OF,
            HelixConfig.SIG_PENDING_OF,
            HelixConfig.SIG_TOTAL_POWER_OF,
    };
    /** 每个查询项打到哪个合约：0=涡轮 1=金库 2=账本 */
    private static final int[] POS_TARGET = {0, 0, 0, 0, 1, 2};

    private static String posTargetAddr(int which) {
        switch (which) {
            case 1: return HelixConfig.COMMUNITY_VAULT_ADDR;
            case 2: return HelixConfig.POWER_LEDGER_ADDR;
            default: return HelixConfig.TURBINE_ADDR;
        }
    }

    /**
     * 批量拉多个账号的持仓。
     *
     * <p>并发口径与 {@code ArenaContractApi.callPlayerSummaryBatch} 一致：
     * {@code sendAsync} 真并发、整批超时后降级逐个串行。降级那条路必须留着——
     * 一批里只要有一笔排队超时就全体作废，不逐个补查会凭空少掉几十行数据。</p>
     *
     * <p>刻意<b>不</b>在这里做「查不到就报错」：一个号没激活过涡轮，{@code locksOf} 可能直接 revert，
     * 这是正常状态，不该影响同批其他账号。失败只记进 {@link Position#errors}。</p>
     */
    public static List<Position> callPositions(Web3j web3j, List<String> addrs) {
        List<Position> out = new ArrayList<>();
        if (addrs == null || addrs.isEmpty()) return out;
        List<String> limited = addrs.size() > HelixConfig.MAX_POSITION_ACCOUNTS
                ? addrs.subList(0, HelixConfig.MAX_POSITION_ACCOUNTS) : addrs;

        // 先把所有请求一次性发出去：6 项 × N 个账号
        List<CompletableFuture<EthCall>> futures = new ArrayList<>();
        List<Integer> ownRow = new ArrayList<>();   // 属于第几个账号
        List<Integer> ownField = new ArrayList<>(); // 是该账号的第几项
        for (int r = 0; r < limited.size(); r++) {
            for (int f = 0; f < POS_SIGS.length; f++) {
                String data = callAddr(POS_SIGS[f], limited.get(r));
                futures.add(web3j.ethCall(
                        Transaction.createEthCallTransaction(null, posTargetAddr(POS_TARGET[f]), data),
                        DefaultBlockParameterName.LATEST).sendAsync());
                ownRow.add(r);
                ownField.add(f);
            }
        }

        for (String a : limited) {
            Position p = new Position();
            p.addr = a;
            out.add(p);
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(com.ailgnsq.model.ArenaConfig.RPC_CALL_TIMEOUT_SEC * 4L, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                applyReply(out.get(ownRow.get(i)), ownField.get(i), futures.get(i).get());
            }
        } catch (Throwable t) {
            // 整批没等齐：逐个补查，宁可慢
            for (int r = 0; r < limited.size(); r++) {
                Position p = out.get(r);
                for (int f = 0; f < POS_SIGS.length; f++) {
                    if (fieldFilled(p, f)) continue;
                    try {
                        String hex = ethCall(web3j, posTargetAddr(POS_TARGET[f]), callAddr(POS_SIGS[f], p.addr));
                        applyReplyHex(p, f, hex);
                    } catch (Exception e) {
                        markError(p, f, Web3AndroidUtil.safeMessage(e));
                    }
                }
            }
        }
        return out;
    }

    private static boolean fieldFilled(Position p, int f) {
        switch (f) {
            case 0: return p.claimable != null;
            case 1: return p.locked != null;
            case 2: return p.claimed != null;
            case 3: return p.lockCount != null;
            case 4: return p.vaultPending != null;
            case 5: return p.power != null;
            default: return true;
        }
    }

    private static void applyReply(Position p, int f, EthCall res) {
        if (res.hasError()) {
            String msg = res.getError() == null ? "未知错误" : res.getError().getMessage();
            markError(p, f, extractReason(msg, res.getError() == null ? null : res.getError().getData()));
            return;
        }
        applyReplyHex(p, f, res.getValue());
    }

    private static void applyReplyHex(Position p, int f, String hex) {
        switch (f) {
            case 0: p.claimable = ArenaContractApi.decodeUint256At(hex, 0); break;
            case 1: p.locked = ArenaContractApi.decodeUint256At(hex, 0); break;
            case 2: p.claimed = ArenaContractApi.decodeUint256At(hex, 0); break;
            // locksOf 是动态数组：这里只取笔数，明细留给步骤 2 的锁仓列表
            case 3: p.lockCount = decodeUintArray(hex, HelixConfig.MAX_LOCK_ROWS_SHOW).size(); break;
            case 4: p.vaultPending = ArenaContractApi.decodeUint256At(hex, 0); break;
            case 5: p.power = ArenaContractApi.decodeUint256At(hex, 0); break;
            default: break;
        }
    }

    private static void markError(Position p, int f, String msg) {
        while (p.errors.size() <= f) p.errors.add(null);
        p.errors.set(f, msg);
    }

    // endregion

    // region 代币与金库总量

    /** 一枚 ERC20 的余额 + 授权 + 精度 + 总量 */
    public static class TokenView {
        public String token;
        public BigInteger balance;
        public BigInteger allowance;
        public BigInteger totalSupply;
        public Integer decimals;
    }

    /**
     * 读一枚 ERC20。
     *
     * <p>{@code decimals} 单独查而不写死：LGNS 是 9 位、AiLGNS 与 DAI 是 18 位，
     * 写死一次就会在某一天悄悄错 10^9 倍。查不到时 {@code decimals} 留 null，
     * 让调用方回落到 {@link HelixConfig} 里的常量并标出来。</p>
     */
    public static TokenView callTokenView(Web3j web3j, String token, String owner, String spender) throws Exception {
        TokenView v = new TokenView();
        v.token = token;
        v.balance = readUint(web3j, token, callAddr(HelixConfig.SIG_BALANCE_OF, owner));
        try { v.decimals = readUint(web3j, token, callNoArg(HelixConfig.SIG_DECIMALS)).intValue(); } catch (Exception ignore) {}
        if (spender != null) {
            try { v.allowance = readUint(web3j, token, callAddrAddr(HelixConfig.SIG_ALLOWANCE, owner, spender)); } catch (Exception ignore) {}
        }
        try { v.totalSupply = readUint(web3j, token, callNoArg(HelixConfig.SIG_TOTAL_SUPPLY)); } catch (Exception ignore) {}
        return v;
    }

    /** 金库累计已释放量（判断发放进度用） */
    public static BigInteger callVaultTotalReleased(Web3j web3j) throws Exception {
        return readUint(web3j, HelixConfig.COMMUNITY_VAULT_ADDR, callNoArg(HelixConfig.SIG_TOTAL_RELEASED));
    }

    // endregion

    // region 功率账本两个入口的干跑探针（不发交易）

    /**
     * 探针结果：能不能调通，以及合约给的原文案。
     *
     * <p>{@code burnForPower} 与 {@code participate} 共用这一份结构：两个入口的判据完全同形
     * （同一个合约、同一个 uint256 入参、都靠 revert 文案表态），区别只在选哪个选择器。</p>
     */
    public static class BurnProbe {
        /** true = 干跑不 revert，说明额度窗口是开的（可以发交易）；false = 现在必失败 */
        public boolean wouldSucceed;
        /** revert 文案或错误摘要，可直接显示 */
        public String reason;
        /** 探针用的额度（人读小数，LGNS） */
        public String amountHuman;
    }

    /**
     * 用 {@code eth_call} 干跑一次 {@code burnForPower}，看合约此刻放不放行。
     *
     * <p>为什么是只读：{@code eth_call} 不写状态、不进块、不要 gas，效果与
     * {@code eth_estimateGas} 同一套模拟机制。所以这一步不会烧到币。</p>
     *
     * <p>实测结论（2026-11-03）：合约里的判定是分层的，同一个真实地址上能看到三种不同的文案——
     * 1 / 10 / 100 / 200 LGNS 均判 {@code PowerLedger: below minimum}（下限就是 500 这一档），
     * 正好 500 判 {@code LgnsBurnGate: not open today}（每日闸门：ai.py 那两个 90 秒窗口就是给它用的），
     * 而 11-02 那次拿到的是第三层：{@code stake below minimum} / 加 1 wei 就
     * {@code above maximum}，当时区间为空集（按这个顺序反推，那次采样时闸门是开着的——这句是推断）。
     * 所以这一页报的是<b>此刻这一层</b>的结论，
     * 区间和闸门都可能被项目方改，每次进页面现查，不写死。</p>
     *
     * @param from 用哪个地址试；传 null 时用零地址（零地址只能暴露纯额度判定，不带余额因素）
     */
    public static BurnProbe probeBurnForPower(Web3j web3j, String from, BigInteger amountRaw) {
        return probePowerEntry(web3j, from, HelixConfig.SIG_BURN_FOR_POWER, amountRaw);
    }

    /**
     * 用 {@code eth_call} 干跑一次 {@code participate}——官方 App 现在实际在用的加功率入口。
     *
     * <p>为什么要和 {@link #probeBurnForPower} 并排干跑同一个人数的数：这两条是同一个合约上的
     * 两个入口，只有<b>同一个入参</b>各试一次，才能把「是这条通道关了」和
     * 「是这个地址没资格」分开。选择器 {@code 0x845c9306} 是链上取样实测来的
     * （2026-11-03：打到 PowerLedger 的最近 300 笔直接交易里 197 笔是它），
     * 已经在 {@link HelixConfig#VERIFIED_SELECTORS} 里锚着。</p>
     *
     * <p>注意：这一路的入参口径<b>没有逐档实测过</b>（{@code burnForPower} 那档是拿 500 LGNS
     * 反复差分出来的）。这里传的是同一个 500 LGNS 的数，所以它给出的 revert 文案只用来判
     * 「通 / 不通」和看合约原话，不能拿来反推 participate 的计价单位。</p>
     *
     * <p>2026-11-03 实测佐证了上面这句担心不是多余的：同一个真实地址上 1 / 10 / 100 / 500 LGNS
     * 四档干跑<b>全部通过</b>，而零地址同样 500 回的是个自定义错误（{@code 0xfb8f41b2}，合约未开源、
     * 解不出文案）。也就是说这一路卡的是发起方自己的资格，而不是额度。</p>
     */
    public static BurnProbe probeParticipate(Web3j web3j, String from, BigInteger amountRaw) {
        return probePowerEntry(web3j, from, HelixConfig.SIG_PARTICIPATE, amountRaw);
    }

    /**
     * 两条入口共用的一次干跑：组 calldata、发 {@code eth_call}、把结果与合约原话装进
     * {@link BurnProbe}。任何异常都在这里收住——探针失败是<b>一个结论</b>而不是一个中断，
     * 不能让只读页因为一次 revert 就少掉一整节数据。
     */
    private static BurnProbe probePowerEntry(Web3j web3j, String from, String sig, BigInteger amountRaw) {
        BurnProbe probe = new BurnProbe();
        probe.amountHuman = plain(amountRaw, HelixConfig.DEC_LGNS);
        try {
            ethCall(web3j, from == null ? "0x0000000000000000000000000000000000000000" : from,
                    HelixConfig.POWER_LEDGER_ADDR, callUint(sig, amountRaw));
            probe.wouldSucceed = true;
            probe.reason = "干跑通过：额度窗口是开的";
        } catch (RevertException e) {
            probe.wouldSucceed = false;
            probe.reason = e.reason != null ? e.reason : Web3AndroidUtil.safeMessage(e);
        } catch (Exception e) {
            probe.wouldSucceed = false;
            probe.reason = Web3AndroidUtil.safeMessage(e);
        }
        return probe;
    }

    // endregion

    // region 写链路要用的单点读数

    /** 涡轮三个标量读数；查不到就抛，不拿 0 顶替——写链路拿它们当「要不要发交易」的判据 */
    public static class TurbineBalance {
        /** 已解锁可领（{@code claimableOf}） */
        public BigInteger claimable;
        /** 还在锁仓（{@code lockedBalanceOf}） */
        public BigInteger locked;
        /** 已领取待激活（{@code claimedBalance}，也就是 activate 的额度上限） */
        public BigInteger claimed;
    }

    /**
     * 单点读涡轮三个数。
     *
     * <p>写链路里每一步都要重读一次，不能用 {@link #callPositions} 批查回来的旧值：
     * {@code claimStatic} 确认之后 {@code claimedBalance} 才会动起来，
     * 拿激活前的快照去算额度就是算错一笔。</p>
     */
    public static TurbineBalance callTurbineBalance(Web3j web3j, String addr) throws Exception {
        TurbineBalance t = new TurbineBalance();
        t.claimable = readUint(web3j, HelixConfig.TURBINE_ADDR, callAddr(HelixConfig.SIG_CLAIMABLE_OF, addr));
        t.locked = readUint(web3j, HelixConfig.TURBINE_ADDR, callAddr(HelixConfig.SIG_LOCKED_BALANCE_OF, addr));
        t.claimed = readUint(web3j, HelixConfig.TURBINE_ADDR, callAddr(HelixConfig.SIG_CLAIMED_BALANCE, addr));
        return t;
    }

    /** 金库待领的静态释放额（{@code claimStatic} 的入参） */
    public static BigInteger callVaultPending(Web3j web3j, String addr) throws Exception {
        return readUint(web3j, HelixConfig.COMMUNITY_VAULT_ADDR, callAddr(HelixConfig.SIG_PENDING_OF, addr));
    }

    /**
     * 取某地址的锁仓 id 列表（直接就是 {@code claimUnlocked} 的入参原值）。
     *
     * <p>实测最多的一个号 37 笔，所以上限给 1024 而不是 {@code MAX_LOCK_ROWS_SHOW}：
     * 展示层只摆前 40 条，写链路却必须把全部 id 递给合约，否则剩下的锁仓永远结不掉。</p>
     */
    public static List<BigInteger> callLockIds(Web3j web3j, String addr) throws Exception {
        return decodeUintArray(ethCall(web3j, HelixConfig.TURBINE_ADDR,
                callAddr(HelixConfig.SIG_LOCKS_OF, addr)), 1024);
    }

    /** 一枚 ERC20 的余额（写链路里的单点抽查，不需要授权与精度那一整套） */
    public static BigInteger callErc20Balance(Web3j web3j, String token, String addr) throws Exception {
        return readUint(web3j, token, callAddr(HelixConfig.SIG_BALANCE_OF, addr));
    }

    /** 一枚 ERC20 对某个 spender 的授权额度 */
    public static BigInteger callErc20Allowance(Web3j web3j, String token, String owner, String spender) throws Exception {
        return readUint(web3j, token, callAddrAddr(HelixConfig.SIG_ALLOWANCE, owner, spender));
    }

    // endregion

    // region 写入：calldata 构造与参数编排

    /**
     * {@code approve(spender,amount)}。
     *
     * <p>额度传 {@link HelixConfig#MAX_UINT256} 是无限授权，传 0 就是撤销——
     * 撤销授权工具用的是同一个编码器，不另写一份。</p>
     */
    public static String encodeApprove(String spender, BigInteger amount) {
        return selector(HelixConfig.SIG_APPROVE)
                + pad32(Numeric.cleanHexPrefix(spender).toLowerCase()) + pad32(amount.toString(16));
    }

    /** {@code transfer(to,amount)}：补 DAI 与归集 AiLGNS 都走它 */
    public static String encodeTransfer(String to, BigInteger amount) {
        return selector(HelixConfig.SIG_TRANSFER)
                + pad32(Numeric.cleanHexPrefix(to).toLowerCase()) + pad32(amount.toString(16));
    }

    /** {@code burnForPower(uint256)}：入参是 LGNS wei（9 位小数） */
    public static String encodeBurnForPower(BigInteger amountRaw) {
        return callUint(HelixConfig.SIG_BURN_FOR_POWER, amountRaw);
    }

    /** {@code participate(uint256)}：入参口径未逐档实测，探针用同一个 500 LGNS 的数 */
    public static String encodeParticipate(BigInteger amountRaw) {
        return callUint(HelixConfig.SIG_PARTICIPATE, amountRaw);
    }

    /** {@code claimStatic(uint256)}：入参就是金库 {@code pendingOf} 的原值 */
    public static String encodeClaimStatic(BigInteger amountRaw) {
        return callUint(HelixConfig.SIG_CLAIM_STATIC, amountRaw);
    }

    /** {@code activate(amount,maxDaiIn,deadlineSec)}：第三参是<b>秒级时间戳</b>，填块号必被 {@code EXPIRED} */
    public static String encodeActivate(BigInteger amountRaw, BigInteger maxDaiIn, BigInteger deadlineSec) {
        return selector(HelixConfig.SIG_ACTIVATE) + pad32(amountRaw.toString(16))
                + pad32(maxDaiIn.toString(16)) + pad32(deadlineSec.toString(16));
    }

    /**
     * {@code claimUnlocked(uint256[])}：把 {@code locksOf} 拿到的列表原样传进去。
     *
     * <p>动态数组的 ABI 布局固定是「offset(0x20) → length → 元素」，和返回值那边对称。
     * 空数组不要发：进去就是白付一笔 gas。</p>
     */
    public static String encodeClaimUnlocked(List<BigInteger> ids) {
        StringBuilder sb = new StringBuilder(selector(HelixConfig.SIG_CLAIM_UNLOCKED));
        sb.append(pad32(BigInteger.valueOf(32).toString(16)));
        int n = ids == null ? 0 : ids.size();
        sb.append(pad32(BigInteger.valueOf(n).toString(16)));
        for (int i = 0; i < n; i++) {
            sb.append(pad32(ids.get(i).toString(16)));
        }
        return sb.toString();
    }

    /**
     * 把 {@code quote().total} 放大 {@link HelixConfig#MAX_DAI_SLIPPAGE_PERMILLE} 千分比，
     * 得到 {@code activate} 的 {@code maxDaiIn}。
     */
    public static BigInteger maxDaiInOf(Quote q) throws Exception {
        if (q == null || q.total == null || q.total.signum() <= 0) {
            throw new Exception("涡轮报价为空，不能组 maxDaiIn");
        }
        return q.total.multiply(BigInteger.valueOf(1000L + HelixConfig.MAX_DAI_SLIPPAGE_PERMILLE))
                .divide(BigInteger.valueOf(1000L));
    }

    /** 最新块的 timestamp（秒）：{@code activate} 的 deadline 用它算，<b>不用手机本地时间</b> */
    public static BigInteger blockTimestampSec(Web3j web3j) throws Exception {
        return Web3AndroidUtil.rpcRetryCall(() -> web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock().getTimestamp());
    }

    /** {@code activate} 的三个最终参数，加报价快照（日志里要能复算当时为什么这么填） */
    public static class ActivateArgs {
        public BigInteger amountRaw;
        public BigInteger maxDaiIn;
        public BigInteger deadlineSec;
        /** 组 {@link #maxDaiIn} 时用的那次报价 */
        public Quote quote;

        /** 给日志用的一行描述：人都读得懂的 DAI 数 + 原始 wei，两个都摆 */
        public String describe() {
            return "待激活 " + plain(amountRaw, HelixConfig.DEC_AILGNS) + " AiLGNS"
                    + "，maxDaiIn=" + plain(maxDaiIn, HelixConfig.DEC_DAI) + " DAI"
                    + "（报价 " + plain(quote == null ? null : quote.total, HelixConfig.DEC_DAI)
                    + " ×千分之" + (1000 + HelixConfig.MAX_DAI_SLIPPAGE_PERMILLE) + "）"
                    + "，截止秒=" + deadlineSec;
        }
    }

    /**
     * 现取报价、加滑点、算秒级截止，一次组好 {@code activate} 的三个参数。
     *
     * <p>{@code quote} 必须在发交易前现算：它返的是池子当前水位，上一秒的报价放到今天
     * 实测就能差出万分之一，直接拿陈数当 {@code maxDaiIn} 会挂在
     * {@code Turbine: exceeds max dai}。{@link HelixConfig#MAX_DAI_SLIPPAGE_PERMILLE} 就是给
     * 这段间隙留的余量。</p>
     *
     * <p>末尾那个量级哨兵不是安全检查而是<b>错档检查</b>：精度搞错 10 倍、地址拿错币，
     * 都会让 maxDaiIn 变成十或百分之一这种量级，而它看起来仍然是一串合法数字。</p>
     */
    public static ActivateArgs planActivate(Web3j web3j, BigInteger amountRaw) throws Exception {
        if (amountRaw == null || amountRaw.signum() <= 0) {
            throw new Exception("待激活数量为 0，不必发 activate");
        }
        ActivateArgs a = new ActivateArgs();
        a.amountRaw = amountRaw;
        a.quote = callQuote(web3j, amountRaw);
        a.maxDaiIn = maxDaiInOf(a.quote);
        a.deadlineSec = blockTimestampSec(web3j).add(BigInteger.valueOf(HelixConfig.ACTIVATE_DEADLINE_SEC));
        BigInteger unit = a.quote.total.multiply(BigInteger.TEN.pow(HelixConfig.DEC_AILGNS)).divide(amountRaw);
        if (unit.compareTo(HelixConfig.PRICE_SANITY_MAX_RAW) > 0) {
            throw new Exception("报价量级异常：每 AiLGNS 折 " + plain(unit, HelixConfig.DEC_DAI)
                    + " DAI，超过哨兵 " + plain(HelixConfig.PRICE_SANITY_MAX_RAW, HelixConfig.DEC_DAI)
                    + " DAI，已拒发（先确认精度与币地址，别改阈值）");
        }
        return a;
    }

    /**
     * 发交易前拿同一串 calldata 干跑一次。
     *
     * <p>这是相对 py 版唯一的实质改进：ling1.py 是「拿价格算个 max_dai 就直接签广播」，
     * 挂了就是真金白银的 gas。{@code eth_call} 不要 gas、不改状态，却能把
     * 「没得激活 / 滑点不够 / 没得结」这三类必挂提前拦下来——实测那两句文案
     * （{@code Turbine: insufficient claimed}、{@code Turbine: exceeds max dai}）
     * 本来就是干跑拿到的。</p>
     *
     * <p>不能把它当「一定成功」：从干跑到上链之间状态会变（别人也在激活），
     * 所以只用来避开确定性的错，真成功了还是要处理 {@link ArenaContractApi.RevertedTxException}。</p>
     */
    public static void simulateWrite(Web3j web3j, String from, String to, String data) throws Exception {
        ethCall(web3j, from, to, data);
    }

    // endregion

    // region 写入：签名 → 广播 → 等回执

    /**
     * 一笔写入交易的全链路：签名 → 广播前落盘 hook → 广播 → 等回执。
     *
     * <p>顺序里「先落盘后广播」是关键：只有这样才能把「链上已经有了、本地却没记录」
     * 这个窗口压到 0，进程被杀之后能拿 rawTx 原重发。 {@code value} 固定 0（由
     * {@link ArenaContractApi#signRawTx} 保证）：本链的 gas 是用 ERC20 DAI 付的，
     * 带原生币上去会被节点拒。</p>
     */
    public static TransactionReceipt sendWrite(Web3j web3j, ECKeyPair keyPair, BigInteger nonce,
                                               BigInteger gasPrice, BigInteger gasLimit, String to,
                                               String data, ArenaContractApi.PreBroadcastHook hook) throws Exception {
        ArenaContractApi.SignedTx signed = ArenaContractApi.signRawTx(keyPair, nonce, gasPrice, gasLimit, to, data);
        if (hook != null) {
            hook.onSigned(signed.txHash, signed.rawTxHex);
        }
        String rpcHash = ArenaContractApi.broadcastRaw(web3j, signed.rawTxHex);
        return ArenaContractApi.waitReceiptLocal(web3j, rpcHash != null ? rpcHash : signed.txHash);
    }

    /** 领金库的静态释放（发行额）；成功后这笔额度会迵到涡轮的 {@code claimedBalance} 里 */
    public static TransactionReceipt doClaimStatic(Web3j web3j, ECKeyPair keyPair, BigInteger amountRaw,
                                                  BigInteger nonce, BigInteger gasPrice,
                                                  ArenaContractApi.PreBroadcastHook hook) throws Exception {
        return sendWrite(web3j, keyPair, nonce, gasPrice, HelixConfig.GAS_LIMIT_CLAIM_STATIC,
                HelixConfig.COMMUNITY_VAULT_ADDR, encodeClaimStatic(amountRaw), hook);
    }

    /** 结掉已到期锁仓：直接把 {@code locksOf} 的返回表递进去 */
    public static TransactionReceipt doClaimUnlocked(Web3j web3j, ECKeyPair keyPair, List<BigInteger> lockIds,
                                                    BigInteger nonce, BigInteger gasPrice,
                                                    ArenaContractApi.PreBroadcastHook hook) throws Exception {
        if (lockIds == null || lockIds.isEmpty()) throw new Exception("锁仓 id 列表为空，不必发 claimUnlocked");
        return sendWrite(web3j, keyPair, nonce, gasPrice, HelixConfig.GAS_LIMIT_CLAIM_UNLOCKED,
                HelixConfig.TURBINE_ADDR, encodeClaimUnlocked(lockIds), hook);
    }

    /** 涡轮激活：三参数已由 {@link #planActivate} 组好 */
    public static TransactionReceipt doActivate(Web3j web3j, ECKeyPair keyPair, ActivateArgs args,
                                               BigInteger nonce, BigInteger gasPrice,
                                               ArenaContractApi.PreBroadcastHook hook) throws Exception {
        return sendWrite(web3j, keyPair, nonce, gasPrice, HelixConfig.GAS_LIMIT_ACTIVATE,
                HelixConfig.TURBINE_ADDR, encodeActivate(args.amountRaw, args.maxDaiIn, args.deadlineSec), hook);
    }

    /**
     * 授权一枚 ERC20 给某个 spender。
     *
     * <p>额度用 {@link HelixConfig#MAX_UINT256} 就是无限授权。实测全链对 Turbine 的 DAI
     * 授权就是这一串 F，所以这里跟现实而不是跟安全理想；但撤销入口必须同时存在，
     * 否则这批号的无限授权就再也没机会收回来。</p>
     */
    public static TransactionReceipt doErc20Approve(Web3j web3j, ECKeyPair keyPair, String token, String spender,
                                                   BigInteger amount, BigInteger nonce, BigInteger gasPrice,
                                                   ArenaContractApi.PreBroadcastHook hook) throws Exception {
        return sendWrite(web3j, keyPair, nonce, gasPrice, HelixConfig.GAS_LIMIT_ERC20,
                token, encodeApprove(spender, amount), hook);
    }

    /** ERC20 转账：给子号补 DAI、把 AiLGNS 归集到主号，都是这一笔 */
    public static TransactionReceipt doErc20Transfer(Web3j web3j, ECKeyPair keyPair, String token, String to,
                                                    BigInteger amount, BigInteger nonce, BigInteger gasPrice,
                                                    ArenaContractApi.PreBroadcastHook hook) throws Exception {
        return sendWrite(web3j, keyPair, nonce, gasPrice, HelixConfig.GAS_LIMIT_ERC20,
                token, encodeTransfer(to, amount), hook);
    }

    // endregion

    // region 链基线与 ABI 自检

    /** 当前节点与区块：页面顶部用一行说明「这些数据是从哪个节点、哪个块高度读的」 */
    public static class Baseline {
        public long chainId;
        public BigInteger blockNumber;
        public BigInteger blockTimestamp;
        public BigInteger gasPrice;
        public String rpcUrl;
    }

    public static Baseline callBaseline(Web3j web3j) throws Exception {
        Baseline b = new Baseline();
        b.rpcUrl = Web3AndroidUtil.currentRpcUrl();
        b.chainId = Web3AndroidUtil.rpcRetryCall(() -> web3j.ethChainId().send().getChainId().longValue());
        b.blockNumber = Web3AndroidUtil.rpcRetryCall(() ->
                web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                        .send().getBlock().getNumber());
        b.blockTimestamp = Web3AndroidUtil.rpcRetryCall(() ->
                web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                        .send().getBlock().getTimestamp());
        try { b.gasPrice = Web3AndroidUtil.rpcRetryCall(() -> web3j.ethGasPrice().send().getGasPrice()); } catch (Exception ignore) {}
        return b;
    }

    /**
     * ABI 自检：把 App 现算的选择器与步骤 0 在链上实测的那份表逐条比。
     *
     * <p>为什么值得专门做：签名写错<b>不会有任何编译错误</b>，只会在运行时 revert，
     * 而 revert 在展示层很容易看成「这个号没数据」。原 py 脚本就是这个形态：
     * 每个查询都 {@code try/except: pass}，签名错了也照样跑完，一笔没发。
     * 自检只是几十次本地 keccak，不上链，代价可以忽略。</p>
     *
     * <p>表里除了 HELIX 自己的函数，还塞了 {@code transfer}/{@code approve} 这两个全链通用的
     * 标准值：它们对不上就说明 keccak 实现本身走歪了，而不是某个函数名写错——
     * 两种错法得能区分开。</p>
     *
     * @return 全部一致时返回 {@code "ABI 自检通过（N 条…）"}；否则返回逐条不符的清单
     */
    public static String abiSelfCheck() {
        StringBuilder bad = new StringBuilder();
        int n = 0;
        for (String[] row : HelixConfig.VERIFIED_SELECTORS) {
            n++;
            String sig = row[0];
            String expect = row[1];
            String got;
            try {
                got = selector(sig);
            } catch (Exception e) {
                bad.append(sig).append(" 取选择器失败：").append(Web3AndroidUtil.safeMessage(e)).append("；");
                continue;
            }
            if (!got.equalsIgnoreCase(expect)) {
                bad.append(sig).append(" 算出 ").append(got).append(" / 实测 ").append(expect).append("；");
            }
        }
        if (bad.length() == 0) return "ABI 自检通过（" + n + " 条选择器与链上实测一致）";
        return "⚠ ABI 自检不符：" + bad;
    }

    // endregion

    // region 展示小工具

    /** wei 转人读小数：按精度定点截断，不用科学计数法（{@code BigDecimal.toPlainString}） */
    public static String plain(BigInteger raw, int decimals) {
        if (raw == null) return "--";
        return new java.math.BigDecimal(raw)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(decimals)), decimals, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /** 定点读数保留指定小数位（四舍五入），用于价格这类要固定位数的场合 */
    public static String plain(BigInteger raw, int decimals, int keep) {
        if (raw == null) return "--";
        return new java.math.BigDecimal(raw)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(decimals)), keep, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** 地址短显：0x + 前 6 … 后 4，与其他页面同一口径 */
    public static String shortAddr(String a) {
        if (a == null || a.isEmpty()) return "--";
        if (a.length() <= 12) return a;
        return a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }

    // endregion
}
