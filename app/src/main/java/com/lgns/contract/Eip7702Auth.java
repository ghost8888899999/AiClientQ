package com.lgns.contract;

import com.lgns.model.ArenaConfig;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class Eip7702Auth {
    public final String address;
    public final long nonce;
    public final int yParity;
    public final BigInteger r;
    public final BigInteger s;

    public Eip7702Auth(String address, long nonce, int yParity, BigInteger r, BigInteger s) {
        this.address = address;
        this.nonce = nonce;
        this.yParity = yParity;
        this.r = r;
        this.s = s;
    }

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

    public static Eip7702Auth sign(BigInteger chainId, String implAddress, long authNonce, ECKeyPair keyPair) {
        byte[] hash = computeAuthHash(chainId, implAddress);
        Sign.SignatureData sig = Sign.signMessage(hash, keyPair, false);
        int yParity = (sig.getV()[sig.getV().length - 1] & 1);
        BigInteger r = new BigInteger(1, sig.getR());
        BigInteger s = new BigInteger(1, sig.getS());
        String addr = org.web3j.crypto.Keys.getAddress(keyPair);
        return new Eip7702Auth(addr, authNonce, yParity, r, s);
    }

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
