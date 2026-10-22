package com.lgns.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.lgns.manager.AppPrefs;

import java.math.BigInteger;

public class ArenaConfig {
    // 三个节点做熔断轮换
    public static final String[] RPC_URLS = {
            "https://rpc.anubispace.org",
            "https://rpc1.anubispace.org",
            "https://rpc2.anubispace.org",
    };
    // 兼容旧字段,始终指向首个节点。业务代码请改用 RPC_URLS + Web3AndroidUtil.rotateWeb3j()
    public static final String RPC_URL = RPC_URLS[0];

    public static final String ARENA_ADDR = "0xff060bAB41e16eBF0eFE4FAd454404Fd5f75aAaA";
    public static final long CHAIN_ID = 6714L;

    // Squad 合约地址: null 表示未部署/未配置,部署后由用户设置
    public static volatile String SQUAD_ADDR = null;
    // 主号私钥(用于 Squad 模式的 SetCodeTx 委托 + 批量交易签名)
    // 运行时由 UI 层设置,任务结束后清零
    public static volatile String MASTER_PK = null;

    // 入场费 100*10^18
    public static final BigInteger ENTRY_FEE = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));
    public static final BigInteger MAX_JIAZI_BATCH = BigInteger.valueOf(64);
    public static final long DAY_OFFSET = 8 * 3600L;

    // Gas limit 常量（参考 Go/Python/wlClient_1 项目）
    public static final BigInteger GAS_LIMIT_ENTER_ARENA = BigInteger.valueOf(1500000);
    public static final BigInteger GAS_LIMIT_CLAIM_JIAZI = BigInteger.valueOf(300000);
    public static final BigInteger GAS_LIMIT_CLAIM_LINEAGE = BigInteger.valueOf(300000);
    public static final BigInteger GAS_LIMIT_CLAIM_REWARDS = BigInteger.valueOf(200000);
    public static final BigInteger GAS_LIMIT_ERC20_TRANSFER = BigInteger.valueOf(100000);

    // 交易回执最大等待秒
    public static final int TX_TIMEOUT_SEC = 180;
    // 回执轮询间隔毫秒
    public static final long TX_WAIT_POLL_MS = 1200L;
    // 单次 RPC 调用超时秒
    public static final int RPC_CALL_TIMEOUT_SEC = 10;
    // Dial/连接超时秒
    public static final int RPC_DIAL_TIMEOUT_SEC = 8;
    // 业务调用最大重试次数 = 节点数 * 2
    public static final int RPC_MAX_RETRY = RPC_URLS.length * 2;

    // 整批 gas 上限护栏:预估 gas 超过区块 gasLimit * 该比例 → 拒发
    public static final int BLOCK_GAS_LIMIT_MARGIN_PCT = 85;

    // 批量查询单批切片大小
    public static final int BATCH_CALL_SIZE = 80;

    // 空闲自动上锁秒;到期后 AppGlobal 会 sKeyStoreManager = null,并强制要求重新解锁
    public static final long IDLE_LOCK_SEC = 30 * 60L;

    // Pending 状态超过该时长仍未回执,且账户当前 nonce > 记录 nonce → 判为 nonce-consumed
    public static final long PENDING_STALE_SEC = 5 * 60L;

    // 自动清除私钥
    public static boolean autoClearEnable = false;
    public static int autoClearIntervalValue = 1; // 默认1
    public static int autoClearIntervalUnitIndex = 1; // 0=分钟,1=小时,2=天

    // 入场模式
    public static int playModeIndex = 0; // 0=能打就打,1=等齐再打

    // 自动清除日志
    public static int logClearIntervalValue = 1; // 默认1天
    public static int logClearIntervalUnitIndex = 2; // 0=分钟,1=小时,2=天

    // 保存配置
    public static void saveConfig(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        SharedPreferences.Editor editor = sp.edit();

        editor.putBoolean("auto_clear_enable", autoClearEnable);
        editor.putInt("auto_clear_interval_value", autoClearIntervalValue);
        editor.putInt("auto_clear_interval_unit_index", autoClearIntervalUnitIndex);
        editor.putInt("play_mode_index", playModeIndex);
        editor.putInt("log_clear_interval_value", logClearIntervalValue);
        editor.putInt("log_clear_interval_unit_index", logClearIntervalUnitIndex);

        editor.apply();
    }

    // 加载配置
    public static void loadConfig(Context context) {
        SharedPreferences sp = AppPrefs.get(context);

        autoClearEnable = sp.getBoolean("auto_clear_enable", false);
        autoClearIntervalValue = sp.getInt("auto_clear_interval_value", 1);
        autoClearIntervalUnitIndex = sp.getInt("auto_clear_interval_unit_index", 1);
        playModeIndex = sp.getInt("play_mode_index", 0);
        logClearIntervalValue = sp.getInt("log_clear_interval_value", 1);
        logClearIntervalUnitIndex = sp.getInt("log_clear_interval_unit_index", 2);
    }
}