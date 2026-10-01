package com.lgns;

import java.math.BigInteger;

public class ArenaConfig {
    public static final String RPC_URL = "https://rpc.anubispace.org";
    public static final String ARENA_ADDR = "";
    public static final long CHAIN_ID = 6714L;
    //入场费 100*10^18
    public static final BigInteger ENTRY_FEE = BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(100));
    public static final BigInteger MAX_JIAZI_BATCH = BigInteger.valueOf(64);
    public static final long DAY_OFFSET = 8 * 3600L;
    public static final int TX_TIMEOUT_SEC = 180;
}
