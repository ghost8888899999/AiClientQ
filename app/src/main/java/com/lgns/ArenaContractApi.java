package com.lgns;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
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
import java.util.List;

public class ArenaContractApi {
    public static final String ARENA_ADDR = "0xff060bAB41e16eBF0eFE4FAd454404Fd5f75aAaA";
    public static final long CHAIN_ID = 6714L;
    public static final long TX_WAIT_POLL_MS = 1200;
    public static final int TX_WAIT_MAX_CNT = 40;

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

    public static PlayerSummary callPlayerSummary(Web3j web3j, String playerAddr) throws Exception {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new Address(playerAddr));
        List<TypeReference<?>> outputs = new ArrayList<>();
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Bool.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Bool.class));
        outputs.add(TypeReference.create(Bool.class));

        Function func = new Function("playerSummary", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        EthCall ethCall = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (ethCall.hasError()) {
            throw new Exception("playerSummary rpc error:" + ethCall.getError().getMessage());
        }
        List<Type> dec = FunctionReturnDecoder.decode(ethCall.getValue(), func.getOutputParameters());
        PlayerSummary s = new PlayerSummary();
        s.walletBalance = ((Uint256) dec.get(0)).getValue();
        s.allowance = ((Uint256) dec.get(1)).getValue();
        s.prize = ((Uint256) dec.get(2)).getValue();
        s.inMatchDeposit = ((Uint256) dec.get(3)).getValue();
        s.arenaTotal = ((Uint256) dec.get(4)).getValue();
        s.battles = ((Uint256) dec.get(5)).getValue();
        s.championships = ((Uint256) dec.get(6)).getValue();
        s.jiaziTotalGranted = ((Uint256) dec.get(7)).getValue();
        s.jiaziReleased = ((Uint256) dec.get(8)).getValue();
        s.jiaziClaimable = ((Uint256) dec.get(9)).getValue();
        s.jiaziTruncated = ((Bool) dec.get(10)).getValue();
        s.entriesToday = ((Uint256) dec.get(11)).getValue();
        s.entriesRemainingToday = ((Uint256) dec.get(12)).getValue();
        s.roundsFromPrize = ((Uint256) dec.get(13)).getValue();
        s.nextEntryFromPrize = ((Bool) dec.get(14)).getValue();
        s.seatedInCurrentRound = ((Bool) dec.get(15)).getValue();
        return s;
    }

    public static BigInteger callJiaziCount(Web3j web3j, String owner) throws Exception {
        List<Type> inputs = List.of(new Address(owner));
        List<TypeReference<?>> outputs = List.of(TypeReference.create(Uint256.class));
        Function f = new Function("jiaziCount", inputs, outputs);
        String callData = FunctionEncoder.encode(f);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                DefaultBlockParameterName.LATEST
        ).send();
        if(res.hasError()) throw new Exception("jiaziCount error:"+res.getError().getMessage());
        List<Type> dec = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
        return ((Uint256)dec.get(0)).getValue();
    }

    public static Integer callActivityOfMatchesToday(Web3j web3j, String owner) {
        try {
            List<Type> inputs = List.of(new Address(owner));
            List<TypeReference<?>> outputs = new ArrayList<>();
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
            if(res.hasError()) return null;
            List<Type> dec = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
            BigInteger val = ((Uint256) dec.get(2)).getValue();
            return val.intValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * playerHistoryLatest(player,count) selector:0x0810151a
     * 修复：增加hex解析异常捕获，畸形数据直接返回null，不向外抛NumberFormatException
     */
    public static Integer callPlayerHistoryLatestWendToday(Web3j web3j, String owner) {
        try {
            String addrNo0x = Numeric.cleanHexPrefix(owner);
            String countHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(100), 64);
            String data = "0810151a"
                    + String.format("%64s", addrNo0x).replace(' ', '0')
                    + countHex;
            EthCall res = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                    DefaultBlockParameterName.LATEST
            ).send();
            if (res.hasError()) {
                return null;
            }
            String hexRaw = Numeric.cleanHexPrefix(res.getValue());
            if (hexRaw.length() < 64) {
                return 0;
            }

            int arrOffset;
            try{
                arrOffset = Integer.parseInt(hexRaw.substring(0, 64), 16) * 2;
            }catch (NumberFormatException e){
                return null;
            }

            if(arrOffset + 64 > hexRaw.length()){
                return null;
            }
            int arrLen;
            try{
                arrLen = Integer.parseInt(hexRaw.substring(arrOffset, arrOffset + 64), 16);
            }catch (NumberFormatException e){
                return null;
            }

            int itemPtr = arrOffset + 64;
            int winCount = 0;
            long nowSec = System.currentTimeMillis() / 1000L;
            long todayDayId = (nowSec + 8L * 3600L) / 86400L;

            for (int idx = 0; idx < arrLen; idx++) {
                int base = itemPtr + idx * 512;
                if (base + 512 > hexRaw.length()) break;

                String tsHex = hexRaw.substring(base + 64, base + 128);
                long ts;
                try{
                    ts = new BigInteger(tsHex, 16).longValue();
                }catch (NumberFormatException e){
                    continue;
                }
                long rowDayId = (ts + 8L * 3600L) / 86400L;

                String wonHex = hexRaw.substring(base + 192, base + 256);
                boolean won = !"0000000000000000000000000000000000000000000000000000000000000000".equals(wonHex);
                if (won && rowDayId == todayDayId) {
                    winCount++;
                }
            }
            return winCount;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTokenAddress(Web3j web3j) throws Exception {
        List<Type> inputs = new ArrayList<>();
        List<TypeReference<?>> outputs = List.of(TypeReference.create(Address.class));
        Function f = new Function("TOKEN", inputs, outputs);
        String data = FunctionEncoder.encode(f);
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if(call.hasError()) throw new Exception("read TOKEN address fail:"+call.getError().getMessage());
        List<Type> dec = FunctionReturnDecoder.decode(call.getValue(), f.getOutputParameters());
        return ((Address)dec.get(0)).getValue();
    }

    public static TransactionReceipt doApproveArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger amount, BigInteger nonce, BigInteger gasPrice) throws Exception {
        String tokenContract = readTokenAddress(web3j);
        List<Type> inputs = List.of(new Address(ARENA_ADDR), new Uint256(amount));
        List<TypeReference<?>> outputs = new ArrayList<>();
        Function func = new Function("approve", inputs, outputs);
        String txData = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, tokenContract, txData);
    }

    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        List<Type> inputs = new ArrayList<>();
        List<TypeReference<?>> outputs = new ArrayList<>();
        Function func = new Function("enterArena", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(1500000);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data);
    }

    public static void doClaimJiazi(Web3j web3j, ECKeyPair ecKeyPair, BigInteger start, BigInteger count, BigInteger nonce, BigInteger gasPrice) throws Exception {
        List<Type> inputs = List.of(new Uint256(start), new Uint256(count));
        List<TypeReference<?>> outputs = new ArrayList<>();
        Function func = new Function("claimJiazi", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(300000);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data);
    }

    public static void doClaimLineage(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        List<Type> inputs = new ArrayList<>();
        List<TypeReference<?>> outputs = new ArrayList<>();
        Function func = new Function("claimLineage", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(300000);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data);
    }

    public static void doClaimRewards(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        List<Type> inputs = new ArrayList<>();
        List<TypeReference<?>> outputs = new ArrayList<>();
        Function func = new Function("claimRewards", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(200000);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data);
    }

    private static TransactionReceipt sendRawTxAndWaitReceipt(
            Web3j web3j,
            ECKeyPair ecKeyPair,
            BigInteger nonce,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String toAddr,
            String data
    ) throws Exception {
        Credentials credentials = Credentials.create(ecKeyPair);
        RawTransaction rawTx = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                toAddr,
                BigInteger.ZERO,
                data
        );
        byte[] signedBytes = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
        String hexSigned = Numeric.toHexString(signedBytes);
        EthSendTransaction sendResp = web3j.ethSendRawTransaction(hexSigned).send();
        if(sendResp.hasError()){
            throw new Exception("sendRawTx error: " + sendResp.getError().getMessage());
        }
        String txHash = sendResp.getTransactionHash();
        for(int i=0;i<TX_WAIT_MAX_CNT;i++){
            EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(txHash).send();
            if(receiptResp.getTransactionReceipt().isPresent()){
                TransactionReceipt rcpt = receiptResp.getTransactionReceipt().get();
                if(!rcpt.isStatusOK()){
                    throw new Exception("tx reverted, hash="+txHash);
                }
                return rcpt;
            }
            Thread.sleep(TX_WAIT_POLL_MS);
        }
        throw new Exception("wait receipt timeout, hash="+txHash);
    }
}
