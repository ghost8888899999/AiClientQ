package com.lgns.contract;

import com.lgns.model.ArenaConfig;
import com.lgns.util.Web3AndroidUtil;
import com.lgns.task.TaskLogCallback;

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

public class SquadApi {

    public static final long GAS_DEPLOY = 6_000_000L;
    private static final BigInteger PER_MEMBER_PLAY = BigInteger.valueOf(500_000);
    private static final BigInteger PER_MEMBER_HARVEST = BigInteger.valueOf(2_000_000);
    private static final BigInteger BASE_GAS = BigInteger.valueOf(300_000);

    public static class PulseData {
        public BigInteger wallet, settled, unsettled, lineage, jiazi, total, ready, locked;
        public boolean canHarvest;
        public BigInteger blocksToWait;
        public boolean seated, delegated, jiaziTruncated;
        public BigInteger matchesToday, lastRound, currentRound, openSeats, seats, backlog, seatNo;
        public BigInteger gasBalance;
    }

    public static class OverviewData {
        public BigInteger total, upgraded, roundId, dayId, masterBalance, masterGas;
    }

    public static class ConfigData {
        public String arena, token, lineage, gasToken, shifu, master, delegationTarget;
    }

    public static class PlayPreview {
        public List<String> who;
        public String data;
        public BigInteger gas;
        public BigInteger funded;
    }

    // ==================================================================
    // 委托检查
    // ==================================================================

    public static boolean isDelegated(Web3j web3j, String accountAddr, String implAddr) throws Exception {
        EthGetCode codeResp = web3j.ethGetCode(accountAddr, DefaultBlockParameterName.LATEST).send();
        String code = codeResp.getCode();
        if (code == null || code.equals("0x")) return false;
        String expected = delegationCode(implAddr);
        return code.equalsIgnoreCase(expected);
    }

    public static String delegationCode(String implAddr) {
        return "0xef0100" + Numeric.cleanHexPrefix(implAddr).toLowerCase();
    }

    // ==================================================================
    // 只读查询（通过 ethCall，不需要委托）
    // ==================================================================

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

    public static List<String> callAllMembers(Web3j web3j, String squadAddr) throws Exception {
        String data = encodeFunction("allMembers");
        EthCall res = web3j.ethCall(
                org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, squadAddr, data),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("allMembers 调用失败:" + res.getError().getMessage());
        return decodeAddressArray(Numeric.cleanHexPrefix(res.getValue()));
    }

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
     * 批量查询多成员的 pulse
     * 用 sendAsync 并发,失败降级到逐个 send
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

    // ==================================================================
    // Play Preview:整批模拟 + 二分法
    // ==================================================================

    /**
     * 整批模拟:用 ethCall 干跑 playSome,检查是否全部成员能入场
     * @return null 表示模拟失败(异常);非 null 包含 funded/gas/enteredCount
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
     * 整批模拟 + 二分法找最大可执行子集
     * @param together 等齐再打模式下不做二分,全部失败则直接返回 null
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

    // ==================================================================
    // 部署
    // ==================================================================

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

    // ==================================================================
    // 写入操作编码（返回 function selector + encoded args）
    // ==================================================================

    public static String encodePlaySome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("playSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    public static String encodeHarvestSome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("harvestSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    public static String encodeSweepGasSome(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("sweepGasSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    public static String encodeAddMembers(List<String> members) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        return FunctionEncoder.encode(new Function("addMembers", inputs, new ArrayList<TypeReference<?>>()));
    }

    public static String encodeRemoveMember(String member) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new Address(member));
        return FunctionEncoder.encode(new Function("removeMember", inputs, new ArrayList<TypeReference<?>>()));
    }

    public static String encodeFundSome(List<String> members, BigInteger amountEach) {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new DynamicArray<>(Address.class,
                org.web3j.abi.Utils.typeMap(members, Address.class)));
        inputs.add(new Uint256(amountEach));
        return FunctionEncoder.encode(new Function("fundSome", inputs, new ArrayList<TypeReference<?>>()));
    }

    // ==================================================================
    // Gas 估算公式
    // ==================================================================

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

    // ==================================================================
    // 内部解码工具
    // ==================================================================

    private static String encodeFunction(String name) {
        return FunctionEncoder.encode(new Function(name, new ArrayList<Type>(), new ArrayList<TypeReference<?>>()));
    }

    private static String decodeAddress(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return "";
        return "0x" + hex.substring(offset + 24, offset + 64);
    }

    private static boolean decodeBool(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return false;
        String word = hex.substring(offset, offset + 64);
        return !word.equals("0000000000000000000000000000000000000000000000000000000000000000");
    }

    private static BigInteger decodeUint(String hex, int wordIndex) {
        int offset = wordIndex * 64;
        if (offset + 64 > hex.length()) return BigInteger.ZERO;
        return new BigInteger(hex.substring(offset, offset + 64), 16);
    }

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
}
