package com.ailgnsq.contract;

import com.ailgnsq.model.ArenaConfig;
import com.ailgnsq.util.Web3AndroidUtil;
import com.ailgnsq.task.TaskLogCallback;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Squad 委托合约的读接口与 calldata 编码工具。
 *
 * <p>职责：把「子号委托 → 主号一次性批量入场/收割」这条 7702 路径的所有链上交互封在这里，
 * 包括委托状态检查、pulse/overview 只读查询、playSome/harvestSome 编码与 gas 预估。</p>
 *
 * <p>两层使用方：{@code ArenaSquadTask} 负责编排任务流程，本类只负责「怎么问、怎么编、怎么解」，
 * 不持有状态、不写日志的文件 IO、不做任何 UI 操作。</p>
 *
 * <p>所有返回值都做了长度校验：节点返回异常或合约不匹配时返回空值/0，
 * 而不是把半截数据当成有效值往上抛。</p>
 */
public class SquadApi {

    /** Squad 合约部署 gas 上限：仅作参考值，当前部署走预部署地址，不走应用内部署 */
    public static final long GAS_DEPLOY = 6_000_000L;
    /** playSome 每成员预估 gas：内部含补币、入场与结算，单账号按 500k 估算 */
    private static final BigInteger PER_MEMBER_PLAY = BigInteger.valueOf(500_000);
    /** harvestSome 每成员预估 gas：要逐个遍历结算与转账，按 2M 估算，是 playSome 的 4 倍 */
    private static final BigInteger PER_MEMBER_HARVEST = BigInteger.valueOf(2_000_000);
    /** 批量调用的固定开销（交易固有成本 + 循环外的合约逻辑） */
    private static final BigInteger BASE_GAS = BigInteger.valueOf(300_000);

    /**
     * {@code pulse(who)} 的返回结构：字段顺序与合约 {@code Squad.Pulse} 一一对应，
     * 解码按下标取值，合约增删字段就必须同步改 {@link #decodePulse}。
     *
     * <p>资金口径：wallet 是原生币余额；settled/unsettled 是已结算与待结算收益；
     * lineage 是师徒分成；jiazi 是甲子俸可领部分（jiaziTruncated 表示因单次上限被截断）；
     * total 是汇总，ready 为当前可提，locked 为仍锁定的部分。</p>
     *
     * <p>状态口径：canHarvest 是否值得收割，blocksToWait 表示还需等几个块才能再动作；
     * seated 是否在座，matchesToday 今日已入场次数，seats/openSeats 为席位总数与剩余，
     * backlog 为积压轮次，seatNo 为当前席位号；gasBalance 是 gas 代币余额。</p>
     */
    public static class PulseData {
        /** wallet 原生币余额；settled/unsettled 已结算与待结算收益；lineage 师徒分成；jiazi 甲子俸可领；total/ready/locked 汇总、可提、锁定 */
        public BigInteger wallet, settled, unsettled, lineage, jiazi, total, ready, locked;
        /** 当前是否值得发一笔 harvest（无收益时最好别白烧手续费） */
        public boolean canHarvest;
        /** 还需等几个块才能再动作（合约的冷却限制） */
        public BigInteger blocksToWait;
        /** 是否已委托 / 是否在座 / 甲子是否因单次上限被截断 */
        public boolean seated, delegated, jiaziTruncated;
        /** 今日已入场次数 / 上轮 / 当前轮 / 剩余席位 / 总席位 / 积压轮次 / 席位号 */
        public BigInteger matchesToday, lastRound, currentRound, openSeats, seats, backlog, seatNo;
        /** gas 代币（ERC20）余额，isDelegated 只能判断代码，余额还得看这里 */
        public BigInteger gasBalance;
    }

    /** {@code overview()} 的返回：全局计数与主号可收余额 */
    public static class OverviewData {
        /** 成员总数 / 已升级（已委托）数 / 当前轮次 ID / 当前天 ID */
        public BigInteger total, upgraded, roundId, dayId;
        /** 合约里记的待分配池（不是主号钱包余额） / 主号 gas 代币余额 */
        public BigInteger masterBalance, masterGas;
    }

    /** {@code config()} 的返回：合约内部引用的各个地址，可用于校验部署是否配对 */
    public static class ConfigData {
        /** arena 主合约 / 代币 / 师徒分成 / gas 代币 */
        public String arena, token, lineage, gasToken;
        /** 师父地址 / 主号 / 委托目标实现（即 0xef0100|| 后面那段） */
        public String shifu, master, delegationTarget;
    }

    /**
     * 整批预演的结果：{@code who} 为实际可执行的子集，{@code data} 是对应的 playSome calldata。
     */
    public static class PlayPreview {
        /** 实际会参与本次调用的成员地址（已过滤掉不满足条件的） */
        public List<String> who;
        /** 与 {@link #who} 对应的 playSome calldata，直接当 transaction 的 data */
        public String data;
        /** 预估 gas 上限（已含余量，不再自乘系数） */
        public BigInteger gas;
        /** 本次要往合约里补的 gas 代币总额（wei），只用于日志与展示 */
        public BigInteger funded;
    }

    // region 委托检查（EIP-7702 是否已指向目标实现）

    /**
     * 判断账号是否已把代码委托给目标实现。
     *
     * <p>EIP-7702 委托后的账号，其 code 固定为 {@code 0xef0100 || 实现地址}（23 字节），
     * 所以直接比对 code 内容即可，不需要再额外查状态。账号没 code（普通 EOA）返回 false。</p>
     */
    public static boolean isDelegated(Web3j web3j, String accountAddr, String implAddr) throws Exception {
        EthGetCode codeResp = web3j.ethGetCode(accountAddr, DefaultBlockParameterName.LATEST).send();
        String code = codeResp.getCode();
        if (code == null || code.equals("0x")) return false;
        String expected = delegationCode(implAddr);
        return code.equalsIgnoreCase(expected);
    }

    /**
     * 拼出「已委托给 implAddr」时应该看到的 code：{@code 0xef0100} 前缀 + 实现地址。
     *
     * <p>统一小写后比较（{@link #isDelegated} 用 {@code equalsIgnoreCase}），
     * 避免节点返回大小写混合地址时误判为未委托。</p>
     */
    public static String delegationCode(String implAddr) {
        return "0xef0100" + Numeric.cleanHexPrefix(implAddr).toLowerCase();
    }

    // endregion

    // region 只读查询（通过 ethCall，不需要委托）

    /**
     * 读 Squad 的配置（各外部合约地址）与主号地址。
     *
     * <p>当前界面未接入，保留给排障：部署时把 arena/token/lineage/gasToken 传错，
     * 现象是批量任务全部 reverted，靠这个方法能直接看出合约里到底指向哪个地址。</p>
     */
    public static ConfigData callConfig(Web3j web3j, String squadAddr) throws Exception {
        String data = encodeFunction("config");
        EthCall res = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, data),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("config 调用失败:" + res.getError().getMessage());
        String hex = Numeric.cleanHexPrefix(res.getValue());
        ConfigData cfg = new ConfigData();
        cfg.arena = decodeAddress(hex, 0);
        cfg.token = decodeAddress(hex, 1);
        cfg.lineage = decodeAddress(hex, 2);
        cfg.gasToken = decodeAddress(hex, 3);
        cfg.shifu = decodeAddress(hex, 4);
        cfg.master = decodeAddress(hex, 5);
        cfg.delegationTarget = decodeAddress(hex, 6);
        return cfg;
    }

    /**
     * 读全局概览：成员总数、已升级数、当前轮次/天 ID，以及主号可收余额与 gas 代币余额。
     *
     * <p>收割流程靠 {@code masterBalance} 判断还要不要继续打下一趟，
     * 它是合约里记的待分配池，不是主号钱包余额。</p>
     */
    public static OverviewData callOverview(Web3j web3j, String squadAddr) throws Exception {
        String data = encodeFunction("overview");
        EthCall res = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, data),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("overview 调用失败:" + res.getError().getMessage());
        // 手动逐字解析，规避 web3j TypeDecoder 在低版本 Android 的 NoSuchMethodError
        OverviewData ov = new OverviewData();
        ov.total = ArenaContractApi.decodeUint256At(res.getValue(), 0);
        ov.upgraded = ArenaContractApi.decodeUint256At(res.getValue(), 1);
        ov.roundId = ArenaContractApi.decodeUint256At(res.getValue(), 2);
        ov.dayId = ArenaContractApi.decodeUint256At(res.getValue(), 3);
        ov.masterBalance = ArenaContractApi.decodeUint256At(res.getValue(), 4);
        ov.masterGas = ArenaContractApi.decodeUint256At(res.getValue(), 5);
        return ov;
    }

    /** 读全部已加入 Squad 的成员地址（动态地址数组，需按 ABI 的 offset 跳转后解码） */
    public static List<String> callAllMembers(Web3j web3j, String squadAddr) throws Exception {
        String data = encodeFunction("allMembers");
        EthCall res = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, data),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("allMembers 调用失败:" + res.getError().getMessage());
        return decodeAddressArray(Numeric.cleanHexPrefix(res.getValue()));
    }

    /**
     * 查单账号的 pulse（资金、席位、是否可收割等）。
     *
     * <p>注意：这里的 ethCall 默认失败会直接抛异常，批量场景请用 {@link #callPulseBatch}。</p>
     */
    public static PulseData callPulse(Web3j web3j, String squadAddr, String memberAddr) throws Exception {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new Address(memberAddr));
        Function func = new Function("pulse", inputs, new ArrayList<TypeReference<?>>());
        String callData = FunctionEncoder.encode(func);
        EthCall res = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, callData),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("pulse 调用失败:" + res.getError().getMessage());
        return decodePulse(Numeric.cleanHexPrefix(res.getValue()));
    }

    /**
     * 批量查询多成员的 pulse。
     *
     * <p>先用 {@code sendAsync} 一次性并发发出，等一个总超时再统一收结果；
     * 没能拿到的（超时、节点报错、数据长度不够）再逐条补查一次。
     * 目的是把「一条请求卡住拖死整批」降级成「只丢那一条」。返回的 Map 保持入参顺序。</p>
     */
    public static Map<String, PulseData> callPulseBatch(
            Web3j web3j, String squadAddr, List<String> memberAddrs) {
        Map<String, PulseData> out = new LinkedHashMap<>();
        if (memberAddrs == null || memberAddrs.isEmpty()) return out;

        List<CompletableFuture<EthCall>> futures = new ArrayList<>(memberAddrs.size());
        for (String addr : memberAddrs) {
            List<Type> inputs = new ArrayList<>();
            inputs.add(new Address(addr));
            Function func = new Function("pulse", inputs, new ArrayList<TypeReference<?>>());
            String callData = FunctionEncoder.encode(func);
            futures.add(web3j.ethCall(
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, callData),
                    DefaultBlockParameterName.LATEST).sendAsync());
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(ArenaConfig.RPC_CALL_TIMEOUT_SEC * 2L, TimeUnit.SECONDS);
        } catch (Exception ignore) {
        }

        for (int i = 0; i < futures.size(); i++) {
            String addr = memberAddrs.get(i);
            try {
                EthCall ec = futures.get(i).get();
                if (ec.hasError()) continue;
                out.put(addr, decodePulse(Numeric.cleanHexPrefix(ec.getValue())));
            } catch (Exception ignore) {
            }
        }

        if (out.size() < memberAddrs.size()) {
            for (String addr : memberAddrs) {
                if (!out.containsKey(addr)) {
                    try {
                        out.put(addr, callPulse(web3j, squadAddr, addr));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        return out;
    }

    // endregion

    // region 整批预演（playSome 干跑 + 二分法找最大可执行子集）

    /**
     * 整批干跑：用 {@code ethCall} 把 playSome 模拟一遍，确认这批成员确实能入场。
     *
     * <p>{@code masterAddr} 作为 from，模拟的是「主号发起的这笔交易」。
     * calldata 直接复用为后续真实交易用，不用重复编码。</p>
     *
     * <p>注意 {@code pv.gas} 的取值：它取的是返回值第三个字（entered 计数），
     * 不是真实 gas 消耗，不能单独当 gasLimit 用；调用方必须再与
     * {@link #estimateGasFloor} 取大值兜底。</p>
     *
     * @return null 表示模拟失败（节点报错、合约 reverted 或整批不可执行）
     */
    public static PlayPreview previewPlay(Web3j web3j, String squadAddr,
                                           String masterAddr, List<String> members) {
        if (members.isEmpty()) return null;
        String data = encodePlaySome(members);

        try {
            EthCall callRes = web3j.ethCall(
                    org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                            masterAddr, squadAddr, data),
                    DefaultBlockParameterName.LATEST).send();

            if (callRes.hasError()) return null;

            String hex = Numeric.cleanHexPrefix(callRes.getValue());
            PlayPreview pv = new PlayPreview();
            pv.who = new ArrayList<>(members);
            pv.data = data;

            if (hex.length() >= 3 * 64) {
                pv.funded = new BigInteger(hex.substring(0, 64), 16);
                pv.gas = new BigInteger(hex.substring(2 * 64, 3 * 64), 16);
            }

            BigInteger estGas;
            try {
                // 尝试使用 eth_estimateGas（组队功能有 fallback 机制）
                estGas = Web3AndroidUtil.estimateGasWithCeiling(web3j, masterAddr, squadAddr, data);
            } catch (Exception e) {
                // fallback 到硬编码公式计算
                estGas = estimateGasFloor("playSome", members.size());
            }
            if (estGas.compareTo(pv.gas) < 0) pv.gas = estGas;

            return pv;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 整批预演并给出「实际能打」的最大子集。
     *
     * <p>先整批试一次；通过就直接用全部。不通过时：</p>
     * <ul>
     *   <li>{@code together} 为真（等齐再打模式）或只剩 1 个：放弃二分直接返回 null，
     *   宁可一个不打也不拆批——拆了就不是同一个轮次，等齐就失去意义；</li>
     *   <li>否则二分搜索：一半可执行就收下，不可执行就拆成两半继续试，
     *   最后得到一个尽量大的可执行集合。</li>
     * </ul>
     *
     * <p>失败原因通常是：未委托、席位不足、今日次数到顶、余额不够补币、不在成员列表，
     * 二分只能定位到「哪个账号打不进去」，具体原因需回日志或 pulse 看。</p>
     *
     * @param together 等齐再打模式下不做二分，全部失败则直接返回 null
     * @return null 表示无法组出任何可执行子集
     */
    public static PlayPreview preparePlay(Web3j web3j, String squadAddr,
                                           String masterAddr, List<String> members,
                                           boolean together, TaskLogCallback callback) {
        PlayPreview full = previewPlay(web3j, squadAddr, masterAddr, members);
        if (full != null && full.gas.signum() > 0) return full;

        if (together || members.size() <= 1) return null;

        if (callback != null) {
            callback.onLog("", "SQUAD", "⚠ 整批模拟未通过,开始二分查找最大可执行子集...");
        }

        List<String> accepted = new ArrayList<>();
        PlayPreview[] best = {null};
        boolean[] fullFlag = {false};

        selectChunk(web3j, squadAddr, masterAddr, accepted, members, best, fullFlag, callback);

        return best[0];
    }

    /**
     * 二分递归主体：{@code accepted} 为已确认可执行的成员（会被原地修改），
     * {@code chunk} 为当前待验证的候选段。
     *
     * <p>先把整段拼上再试：能过就整段收下并直接返回（后续不必再细分）；
     * 过不了且只剩一个元素，说明这个账号本身进不去，记日志后丢弃；
     * 否则从中间劈成两半递归。</p>
     */
    private static void selectChunk(Web3j web3j, String squadAddr, String masterAddr,
                                     List<String> accepted, List<String> chunk,
                                     PlayPreview[] best, boolean[] fullFlag,
                                     TaskLogCallback callback) {
        if (chunk.isEmpty() || fullFlag[0]) return;

        List<String> candidate = new ArrayList<>(accepted);
        candidate.addAll(chunk);

        PlayPreview trial = previewPlay(web3j, squadAddr, masterAddr, candidate);
        if (trial != null && trial.gas.signum() > 0) {
            accepted.clear();
            accepted.addAll(candidate);
            best[0] = trial;
            return;
        }

        if (chunk.size() == 1) {
            if (callback != null) {
                callback.onLog("", "SQUAD", "⚠ " + chunk.get(0).substring(0, 10)
                        + "... 暂缓(占位/委托/余额/上限需核对)");
            }
            return;
        }

        int mid = chunk.size() / 2;
        selectChunk(web3j, squadAddr, masterAddr, accepted, chunk.subList(0, mid), best, fullFlag, callback);
        selectChunk(web3j, squadAddr, masterAddr, accepted, chunk.subList(mid, chunk.size()), best, fullFlag, callback);
    }

    // endregion

    // region 部署

    /**
     * 拼接部署字节码 + 构造函数参数（arena/token/lineage/gasToken/master/shifu）。
     *
     * <p>用空函数名让 {@code FunctionEncoder} 只编参数，再截掉前 10 个字符
     * （0x + 4 字节 selector），剩下的就是纯构造参数。
     * 注意 {@link SquadContract#BYTECODE} 目前是残缺片段，本方法实际不可用。</p>
     */
    public static String encodeDeployData(String arena, String token, String lineage,
                                           String gasToken, String master, String shifu) {
        List<Type> args = Arrays.<Type>asList(
                new Address(arena), new Address(token), new Address(lineage),
                new Address(gasToken), new Address(master), new Address(shifu));
        Function func = new Function("", args, new ArrayList<TypeReference<?>>());
        String encoded = FunctionEncoder.encode(func);
        String constructorArgs = encoded.substring(10);
        return Numeric.cleanHexPrefix(SquadContract.BYTECODE) + constructorArgs;
    }

    // endregion

    // region 写入操作编码（function selector + ABI 编码的参数）

    /** 编码 playSome(address[])：入场 + 顺带结算（主号一次调用打完一批） */
    public static String encodePlaySome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("playSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    /** 编码 harvestSome(address[])：把子号可收的收益收拢到合约池再转给主号 */
    public static String encodeHarvestSome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("harvestSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    /** 编码 sweepGasSome(address[])：把子号残留的 gas 代币回收给主号 */
    public static String encodeSweepGasSome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("sweepGasSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    /** 编码 addMembers(address[])：把地址加入 Squad 成员表（之后才能被批量操作） */
    public static String encodeAddMembers(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("addMembers", inputs, new ArrayList<TypeReference<?>>()));
    }

    /** 编码 removeMember(address)：移出成员表（不影响已上链的委托） */
    public static String encodeRemoveMember(String member) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new Address(member));
        return FunctionEncoder.encode(new Function("removeMember", inputs, new ArrayList<TypeReference<?>>()));
    }

    /**
     * 编码 fundSome(address[], uint256)：给子号补币。
     *
     * <p>注意：当前 {@link SquadContract#ABI} 里没有 {@code fundSome}，
     * 调用会因 selector 不匹配而 revert；现在也没有调用方（补币在 playSome 内部完成），
     * 若要启用需先确认合约确实暴露了该入口。</p>
     */
    public static String encodeFundSome(List<String> members, BigInteger amountEach) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        inputs.add(new Uint256(amountEach));
        return FunctionEncoder.encode(new Function("fundSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    // endregion

    // region Gas 估算公式

    /**
     * 批量调用的 gas 下限（经验公式）：基础开销 + 成员数 × 单人估算。
     *
     * <p>下限只用来兜底：节点估算失败、或估算值小得离谱时，直接用它作 gasLimit。
     * {@code addMembers / sweepGasSome / removeMember} 返回 0，表示不参与下限约束，
     * 这三个方法调用方需自行估算。</p>
     */
    public static BigInteger estimateGasFloor(String method, int memberCount) {
        BigInteger perMember;
        if (method.startsWith("harvest")) {
            perMember = PER_MEMBER_HARVEST;
        } else {
            perMember = PER_MEMBER_PLAY;
        }
        if (method.equals("addMembers") || method.equals("sweepGasSome") || method.equals("removeMember")) {
            return BigInteger.ZERO;
        }
        return BASE_GAS.add(BigInteger.valueOf(memberCount).multiply(perMember));
    }

    // endregion

    // region 内部解码工具（按下标取字，不用 web3j TypeDecoder）

    /** 无参函数编码：先取 selector（config/overview/allMembers 这类查询用） */
    private static String encodeFunction(String name) {
        return FunctionEncoder.encode(new Function(name, new ArrayList<Type>(), new ArrayList<TypeReference<?>>()));
    }

    /**
     * 按字（32 字节）取地址：地址只占每字的低 20 字节，所以跳过前 24 个 hex 字符。
     * 越界返回空串，由调用方决定当异常还是当缺省值。
     */
    private static String decodeAddress(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return "";
        return "0x" + hex.substring(offset + 24, offset + 64);
    }

    /** 按字取 bool：全 0 为 false，其余非 0 均视为 true */
    private static boolean decodeBool(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return false;
        String word = hex.substring(offset, offset + 64);
        return !word.equals("0000000000000000000000000000000000000000000000000000000000000000");
    }

    /** 按字取 uint256；越界返回 0（宁可当成没余额，也不抛异常打断整批） */
    private static BigInteger decodeUint(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return BigInteger.ZERO;
        return new BigInteger(hex.substring(offset, offset + 64), 16);
    }

    /**
     * 解 pulse：共 21 个字，按下标逐个取。
     *
     * <p>先校验长度而不是静默返回空对象：长度不够基本说明调用打到了别的合约
     * （地址配错/未部署），这种错误必须显式报出来。</p>
     */
    private static PulseData decodePulse(String hex) {
        if (hex.length() < 21 * 64) throw new IllegalStateException("pulse 返回数据不足: " + hex.length() + " chars");
        PulseData p = new PulseData();
        p.wallet = decodeUint(hex, 0);
        p.settled = decodeUint(hex, 1);
        p.unsettled = decodeUint(hex, 2);
        p.lineage = decodeUint(hex, 3);
        p.jiazi = decodeUint(hex, 4);
        p.total = decodeUint(hex, 5);
        p.ready = decodeUint(hex, 6);
        p.locked = decodeUint(hex, 7);
        p.canHarvest = decodeBool(hex, 8);
        p.blocksToWait = decodeUint(hex, 9);
        p.seated = decodeBool(hex, 10);
        p.delegated = decodeBool(hex, 11);
        p.jiaziTruncated = decodeBool(hex, 12);
        p.matchesToday = decodeUint(hex, 13);
        p.lastRound = decodeUint(hex, 14);
        p.currentRound = decodeUint(hex, 15);
        p.openSeats = decodeUint(hex, 16);
        p.seats = decodeUint(hex, 17);
        p.backlog = decodeUint(hex, 18);
        p.seatNo = decodeUint(hex, 19);
        p.gasBalance = decodeUint(hex, 20);
        return p;
    }

    /**
     * 解动态地址数组：第一个字是数据起始偏移，偏移处的第一个字是元素个数，
     * 之后连续排布元素（每个元素仍占满 32 字节）。
     */
    private static List<String> decodeAddressArray(String hex) {
        List<String> result = new ArrayList<>();
        if (hex.length() < 64) return result;
        int offset = Integer.parseInt(hex.substring(0, 64), 16) * 2;
        if (offset + 64 > hex.length()) return result;
        int count = Integer.parseInt(hex.substring(offset, offset + 64), 16);
        int dataStart = offset + 64;
        for (int i = 0; i < count; i++) {
            int pos = dataStart + i * 64;
            if (pos + 64 > hex.length()) break;
            result.add("0x" + hex.substring(pos + 24, pos + 64));
        }
        return result;
    }

    // endregion
}
