package com.lgns;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class SetCodeTx {

    public static class SignedSetCode {
        public final String rawTxHex;
        public final String txHash;

        public SignedSetCode(String rawTxHex, String txHash) {
            this.rawTxHex = rawTxHex;
            this.txHash = txHash;
        }
    }

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
        String txHash = Numeric.toHexString(Hash.sha3(rawTx));

        return new SignedSetCode(rawTxHex, txHash);
    }
}
