package com.ailgnsq.contract;

import com.ailgnsq.model.ArenaConfig;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * EIP-7702 授权条目（authorization tuple）：账号把自己的代码授权给 Squad 合约。
 *
 * <p>协议格式为 {@code [chain_id, address, nonce, y_parity, r, s]}，签名前对
 * {@code 0x05 || rlp([chain_id, address, nonce])} 做 keccak256。
 * 执行时这一条会以 {@code authorization_list} 的形式随 SetCodeTx 一起提交。</p>
 *
 * <p>与普通交易的签名区别：这里签的是「一次委托」，签完就不再绑定具体交易，
 * 所以同一份授权在 nonce 未变前可以重复使用。</p>
 */
public class Eip7702Auth {
    /** 被授权账号地址（即授权人自己，由私钥推导而来） */
    public final String address;
    /** 授权 nonce：必须等于该账号当时的 nonce，否则节点会拒绝这条授权 */
    public final long nonce;
    /** 签名 yParity（0/1）；不是传统交易的 27/37，节点只认 0 或 1 */
    public final int yParity;
    /** 签名 r 分量 */
    public final BigInteger r;
    /** 签名 s 分量（本协议不要求 low-s 归一，但 web3j 签出来已经是规范值） */
    public final BigInteger s;

    /** 纯数据载体：五个字段都是最终提交值，不接受 null 校验（调用方保证非空） */
    public Eip7702Auth(String address, long nonce, int yParity, BigInteger r, BigInteger s) {
        this.address = address;
        this.nonce = nonce;
        this.yParity = yParity;
        this.r = r;
        this.s = s;
    }

    /**
     * 计算待签哈希：{@code keccak256(0x05 || rlp([chainId, implAddress, nonce]))}。
     *
     * <p>注意 nonce 位这里用的是空字节串（而非真实 nonce），意味着签出的授权
     * 不绑定具体 nonce；如果合约侧收紧了 nonce 校验，这里要与 {@link #sign} 一起改。</p>
     *
     * <p>0x05 是 EIP-7702 规定的域分隔前缀，用来与普通交易哈希区分开。</p>
     */
    public static byte[] computeAuthHash(BigInteger chainId, String implAddress) {
        byte[] chainIdEnc = RlpEip7702.encodeInt(chainId);
        byte[] addrEnc = RlpEip7702.encodeAddress(implAddress);
        byte[] nonceEnc = RlpEip7702.encodeEmptyBytes();
        byte[] listPayload = RlpEip7702.concat(chainIdEnc, addrEnc, nonceEnc);
        byte[] listRlp = RlpEip7702.encodeList(new ArrayList<byte[]>() {{
            add(chainIdEnc); add(addrEnc); add(nonceEnc);
        }});
        return Hash.sha3(RlpEip7702.concat(new byte[]{0x05}, listRlp));
    }

    /**
     * 用账号私钥对授权哈希签名，得到一条可提交的授权。
     *
     * <p>{@code Sign.signMessage(hash, keyPair, false)} 的第三个参数必须为 false：
     * 传 true 会给摘要套上以太坊前缀，签出来的 r/s 与链上校验的预期不一致。
     * yParity 取签名的最后一个字节的最低位，兼容 web3j 返回 27/28 的情况。</p>
     *
     * @param authNonce 授权 nonce，必须与账号实际 nonce 一致
     * @param keyPair   账号私钥对；调用方负责用后清零
     */
    public static Eip7702Auth sign(BigInteger chainId, String implAddress, long authNonce, ECKeyPair keyPair) {
        byte[] hash = computeAuthHash(chainId, implAddress);
        Sign.SignatureData sig = Sign.signMessage(hash, keyPair, false);
        int yParity = (sig.getV()[sig.getV().length - 1] & 1);
        BigInteger r = new BigInteger(1, sig.getR());
        BigInteger s = new BigInteger(1, sig.getS());
        String addr = org.web3j.crypto.Keys.getAddress(keyPair);
        return new Eip7702Auth(addr, authNonce, yParity, r, s);
    }

    /**
     * 按 RLP 编码当前授权，拼进 SetCodeTx 的 authorization_list。
     *
     * <p>链 ID 在这里固定取 {@code ArenaConfig.CHAIN_ID}，而不是外部传入的 chainId：
     * 换链时必须同步改配置，否则签名哈希与提交的链 ID 对不上，节点会报授权无效。</p>
     */
    public byte[] encodeRlp() {
        List<byte[]> items = new ArrayList<>();
        items.add(RlpEip7702.encodeInt(BigInteger.valueOf(ArenaConfig.CHAIN_ID)));
        items.add(RlpEip7702.encodeAddress(address));
        items.add(RlpEip7702.encodeInt(nonce));
        items.add(RlpEip7702.encodeInt(yParity));
        items.add(RlpEip7702.encodeInt(r));
        items.add(RlpEip7702.encodeInt(s));
        return RlpEip7702.encodeList(items);
    }
}
