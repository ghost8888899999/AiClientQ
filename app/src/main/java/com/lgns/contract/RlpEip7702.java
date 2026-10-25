package com.lgns.contract;

import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.List;

/**
 * 极简 RLP 编码器，只服务于 EIP-7702 授权与 SetCodeTx。
 *
 * <p>为什么不直接用 web3j 的编码工具：这里需要精确控制空串（{@code 0x80}）、
 * 20 字节地址前缀（{@code 0x94}）与列表定长前缀，手写比适配更可靠。</p>
 *
 * <p>本类只做了本项目用到的那几种编码，不是完整的 RLP 实现。</p>
 */
public final class RlpEip7702 {

    /** 工具类，禁止实例化 */
    private RlpEip7702() {}

    /**
     * 短字符串编码：单字节且小于 0x80 直接原样返回，否则加前缀。
     *
     * <p>注意：前缀固定写 {@code 0x80 + lenBytes.length}，只对 0～1 字节的输入成立；
     * 2 字节以上、以及超过 55 字节的长串（应为 {@code 0xB8} 形式）都会编错。
     * 目前唯一调用方是尚未接入任务流程的 {@code SetCodeTx}（用它将 calldata 编码），
     * 在启用 7702 路径前必须先改成按实际长度选 {@code 0x80+len} 或 {@code 0xB7+lenOfLen}。</p>
     */
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

    /** 空字节串：RLP 里固定编为单个 {@code 0x80} */
    public static byte[] encodeEmptyBytes() {
        return new byte[]{(byte) 0x80};
    }

    /**
     * 地址编码：20 字节地址走短串规则，前缀固定为 {@code 0x94}（即 0x80 + 20）。
     */
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

    /**
     * 无符号整数编码：先取大端最小字节（所以 BigInteger 的符号位 0x00 要先剔掉）。
     *
     * <p>值为 0 时按 RLP 约定编成空串 {@code 0x80}，而不是一个 0x00 字节。</p>
     */
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

    /** long 重载：避免调用方到处写 {@code BigInteger.valueOf} */
    public static byte[] encodeInt(long value) {
        return encodeInt(BigInteger.valueOf(value));
    }

    /**
     * 列表编码：先把子项按已编码字节拼接成 payload，再按长度选前缀。
     *
     * <p>小于 56 字节用单字节头 {@code 0xC0 + len}，否则用 {@code 0xF7 + lenOfLen}
     * 后跟长度。子项必须是已经编码好的字节，本方法不再对元素做二次编码。</p>
     */
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

    /** 把多段已编码字节按顺序拼成一段（用于加类型前缀或拼接 payload） */
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

    /** 大端最小字节表示长度；0 返回空数组，由调用方决定最终前缀 */
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
