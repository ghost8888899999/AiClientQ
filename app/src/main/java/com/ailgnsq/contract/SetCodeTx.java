package com.ailgnsq.contract;

import com.ailgnsq.model.ArenaConfig;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * EIP-7702「Set Code」交易（type = 0x04）的离线签名。
 *
 * <p>用于主号一次性把多个子号的代码授权（{@code authorization_list}）打包进一笔交易，
 * 再由主号支付 gas 调用 Squad 合约批量执行。</p>
 *
 * <p>当前状态：本类尚未接入任务流程（Squad 路径未启用），保留作为委托交易的签名实现。</p>
 */
public class SetCodeTx {

    /** 签名结果：可直接广播的原始交易与本地计算的哈希 */
    public static class SignedSetCode {
        /** 已签名的 RLP 交易（0x 开头），直接作为 eth_sendRawTransaction 的入参 */
        public final String rawTxHex;
        /** 本地算出的交易哈希：广播前就能先落 SP，崩了也能对账 */
        public final String txHash;

        /** 纯数据载体：只做字段赋值，不校验 hex 前缀与长度（由 {@link #sign} 保证） */
        public SignedSetCode(String rawTxHex, String txHash) {
            this.rawTxHex = rawTxHex;
            this.txHash = txHash;
        }
    }

    /**
     * 签名一笔 0x04 交易。
     *
     * <p>字段顺序按 EIP-7702 规范：
     * {@code chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value,
     * data, accessList, authorizationList, yParity, r, s}。</p>
     *
     * <p>两点实现说明：</p>
     * <ul>
     *   <li>两个 gas 价格位都填 {@code gasPrice}，等价于按 legacy 单一价报价；
     *   {@code value} 固定 0（资金流转都在 Squad 合约内部完成）。</li>
     *   <li>签名前与签名后各拼了一次 item 列表，除最后三个签名字段外必须完全一致：
     *   改字段时两处要同步改，否则待签哈希与最终交易对不上，节点会报签名无效。</li>
     * </ul>
     *
     * @param toAddress 调用目标（Squad 合约地址）
     * @param data      已 ABI 编码的 calldata
     * @param authList  随交易提交的授权条目，子号各自签好
     * @param masterKeyPair 主号密钥对：既付 gas，也是交易签名人
     */
    public static SignedSetCode sign(
            BigInteger nonce,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String toAddress,
            String data,
            List<Eip7702Auth> authList,
            ECKeyPair masterKeyPair) {

        BigInteger chainId = BigInteger.valueOf(ArenaConfig.CHAIN_ID);

        List<byte[]> unsignedItems = new ArrayList<>();
        unsignedItems.add(RlpEip7702.encodeInt(chainId));
        unsignedItems.add(RlpEip7702.encodeInt(nonce));
        unsignedItems.add(RlpEip7702.encodeInt(gasPrice));
        unsignedItems.add(RlpEip7702.encodeInt(gasPrice));
        unsignedItems.add(RlpEip7702.encodeInt(gasLimit));
        unsignedItems.add(RlpEip7702.encodeAddress(toAddress));
        unsignedItems.add(RlpEip7702.encodeInt(BigInteger.ZERO));
        unsignedItems.add(RlpEip7702.encode(Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(data))));
        unsignedItems.add(RlpEip7702.encodeList(new ArrayList<>()));

        List<byte[]> authRlpList = new ArrayList<>();
        for (Eip7702Auth auth : authList) {
            authRlpList.add(auth.encodeRlp());
        }
        unsignedItems.add(RlpEip7702.encodeList(authRlpList));

        unsignedItems.add(RlpEip7702.encodeInt(BigInteger.ZERO));
        unsignedItems.add(RlpEip7702.encodeEmptyBytes());
        unsignedItems.add(RlpEip7702.encodeEmptyBytes());

        byte[] unsignedPayload = RlpEip7702.encodeList(unsignedItems);
        // 0x04 是 EIP-7702 交易类型前缀，与授权条目里的 0x05 域分隔符不是一回事
        byte[] signingHash = Hash.sha3(RlpEip7702.concat(new byte[]{0x04}, unsignedPayload));

        Sign.SignatureData sig = Sign.signMessage(signingHash, masterKeyPair, false);
        int yParity = (sig.getV()[sig.getV().length - 1] & 1);
        BigInteger r = new BigInteger(1, sig.getR());
        BigInteger s = new BigInteger(1, sig.getS());

        List<byte[]> signedItems = new ArrayList<>();
        signedItems.add(RlpEip7702.encodeInt(chainId));
        signedItems.add(RlpEip7702.encodeInt(nonce));
        signedItems.add(RlpEip7702.encodeInt(gasPrice));
        signedItems.add(RlpEip7702.encodeInt(gasPrice));
        signedItems.add(RlpEip7702.encodeInt(gasLimit));
        signedItems.add(RlpEip7702.encodeAddress(toAddress));
        signedItems.add(RlpEip7702.encodeInt(BigInteger.ZERO));
        signedItems.add(RlpEip7702.encode(Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(data))));
        signedItems.add(RlpEip7702.encodeList(new ArrayList<>()));
        signedItems.add(RlpEip7702.encodeList(authRlpList));
        signedItems.add(RlpEip7702.encodeInt(yParity));
        signedItems.add(RlpEip7702.encodeInt(r));
        signedItems.add(RlpEip7702.encodeInt(s));

        byte[] signedPayload = RlpEip7702.encodeList(signedItems);
        byte[] rawTx = RlpEip7702.concat(new byte[]{0x04}, signedPayload);
        String rawTxHex = Numeric.toHexString(rawTx);
        // 交易哈希 = keccak256(含类型前缀的完整原始交易)，与节点返回的 txHash 一致
        String txHash = Numeric.toHexString(Hash.sha3(rawTx));

        return new SignedSetCode(rawTxHex, txHash);
    }
}
