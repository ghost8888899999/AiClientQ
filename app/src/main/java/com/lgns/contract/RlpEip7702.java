package com.lgns.contract;

import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.List;

public final class RlpEip7702 {

    private RlpEip7702() {}

    public static byte[] encode(byte[] input) {
        if (input.length == 1 && (input[0] & 0xFF) < 0x80) {
            return input;
        }
        byte[] lenBytes = toMinBytes(input.length);
        byte[] out = new byte[1 + lenBytes.length + input.length];
        out[0] = (byte) (0x80 + lenBytes.length);
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(input, 0, out, 1 + lenBytes.length, input.length);
        return out;
    }

    public static byte[] encodeEmptyBytes() {
        return new byte[]{(byte) 0x80};
    }

    public static byte[] encodeAddress(String hexAddr) {
        byte[] addr = Numeric.hexStringToByteArray(hexAddr);
        if (addr.length == 20) {
            byte[] out = new byte[21];
            out[0] = (byte) 0x94;
            System.arraycopy(addr, 0, out, 1, 20);
            return out;
        }
        return encode(addr);
    }

    public static byte[] encodeInt(BigInteger value) {
        if (value == null || value.signum() == 0) {
            return new byte[]{(byte) 0x80};
        }
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            byte[] tmp = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, tmp, 0, tmp.length);
            bytes = tmp;
        }
        if (bytes.length == 1 && (bytes[0] & 0xFF) < 0x80) {
            return bytes;
        }
        byte[] lenBytes = toMinBytes(bytes.length);
        byte[] out = new byte[1 + lenBytes.length + bytes.length];
        out[0] = (byte) (0x80 + lenBytes.length);
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        System.arraycopy(bytes, 0, out, 1 + lenBytes.length, bytes.length);
        return out;
    }

    public static byte[] encodeInt(long value) {
        return encodeInt(BigInteger.valueOf(value));
    }

    public static byte[] encodeList(List<byte[]> items) {
        int totalLen = 0;
        for (byte[] item : items) totalLen += item.length;
        byte[] payload = new byte[totalLen];
        int pos = 0;
        for (byte[] item : items) {
            System.arraycopy(item, 0, payload, pos, item.length);
            pos += item.length;
        }
        byte[] lenBytes = toMinBytes(payload.length);
        byte[] out;
        if (payload.length < 56) {
            out = new byte[1 + payload.length];
            out[0] = (byte) (0xC0 + payload.length);
            System.arraycopy(payload, 0, out, 1, payload.length);
        } else {
            out = new byte[1 + lenBytes.length + payload.length];
            out[0] = (byte) (0xF7 + lenBytes.length);
            System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
            System.arraycopy(payload, 0, out, 1 + lenBytes.length, payload.length);
        }
        return out;
    }

    public static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static byte[] toMinBytes(int value) {
        if (value == 0) return new byte[0];
        int len = 0;
        int tmp = value;
        while (tmp > 0) { len++; tmp >>= 8; }
        byte[] out = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }
}
