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

    // ==================== 调试专用（发版前必须置为 false） ====================
    // 开启后：主界面列表不再按私钥数量出数，而是把下面的地址池全部列出（按分组平分），
    // 锦标赛统计同步按这些地址出数；只改内存不写库，私钥管理页与批量任务仍用真实私钥。
    // 仅用于看界面效果
    public static final boolean DEBUG_FIXED_ADDR_ENABLE = false;
    /**
     * 调试展示地址池：每条地址都作为主界面的一行，按分组顺序平分到各分组
     */
    public static final String[] DEBUG_FIXED_ADDRs = {
            "0x86f990B428e4f1aaCf3cD0272941b52a74d36c8E",
            "0x9096eef52293291fff2c3a99dbb9b9d08fb015b0",
            "0x1bac261b1684bc33010bd204a49d0f1303cfd2b3",
            "0xe5e46ee6084ce94f1c655393c0990acd19512a84",
            "0x6384ab0fb1aaec73b9e8ee7c449f54ed60a16fd8",
            "0xb156d09afcf4cdfda241b22898a3f6ce36d039a0",
            "0xeeca6bed118388488616f53199632e80c83a26ad",
            "0xb3d11a44b22d3491b92b107562427b029802cb30",
            "0x973dfb4d2bac9c906b003708bebb5cc437125893",
            "0x7d12b0af457b214571849ee0df8f54b458943b98",
            "0x908dc35b5f9fe6e2c1fef212bc3758810381830e",
            "0x6460f8775f80532fd96bec99e343475db598e92c",
            "0x29cf7cb0b78eb69890cb79f2d504449496127155",
            "0x1b630a3c758d74f475825f60f7201421abe2743b",
            "0x01c4c6f647b75f708a23401434aeec8eb901f846",
            "0xb3799328b8498c714640a29f9c7bc1d7b136c1a8",
            "0x32d531baafb640d1f2644d8e72c1f7b0e91c908e",
            "0xe22cdf579e04e9e6105a925bb25e2367c3574bb3",
            "0xc17c2f29d7da4a71e30dfc7e06073deff05071e5",
            "0xc276146b2c80320b129662f1c9a76f205f9f6da4",
            "0x64d40256895b57ecfac4606e627b754d05e0d957",
            "0x528e32993ff59dc0f3f94ba6c9b052af351908f4",
            "0x6f561586b2c702f42780bbdd95da9fcea394f431",
            "0x52c0e47ba7ff90468922a5b4d7b27eabfd09dd4d",
            "0x807694c111fe180f57aeb8f9846c7eaa1a3b16d0",
            "0x6f4891da13809a26eae17d396a8ace42dedee45f",
            "0xb95b7b1563af77d020a4673f2c79a8ea0b6b90d6",
            "0x82549e93b3fbf19f1fd8501d3b3578b8fb0cba1e",
            "0x5ff41e7076e2a74ed3d44f51683e41ead4f54eb1",
            "0x7c648f4dc4adadb6a3a25ae17f60f44e43c52b08",
            "0xd83e263f481b99aa3ea6e7245db265de073a3ea5",
            "0x7a029ac20eb7a7bc3cb8e53da13ea1f68bc84519",
            "0xaa83528543d756529265710a9b22be110a63455c",
            "0xa9ca0d8550b528c0440b6c9e04f535e60038d71d",
            "0x3bb5c3f8ad45b4ce25920cd5c98db4b21a2bb903",
            "0xc119ed474f67ba02c03e1d0f39181061b81f944a",
            "0xe59b633818ec64ce110534cf022beac0ba014eac",
            "0x2748a75b7415ea38e3faa36c5066971b7a39fcaf",
            "0x1e4957ae382c8ad66730ce9cff00b4a2b7af0744",
            "0xfb16600d4cf61eb246761fb83786792d425615ae",
            "0x5ca80b1604078ef3893cbd7c061f4a447aad18da",
            "0x4929297b7bfd55e78af09f3828940fb190b3f931",
            "0x95c5bbf340d830d463b845b0d5113e486dacebd4",
            "0x5abe0dfb02a2108ddcbb6e7bcc9cb8d7144fb7f7",
            "0xf17cf1dd55c10d01384d8dc29aa726e1a4c86dbd",
            "0x18c7f80a002945f3212af1a2ab6c7db898db49eb",
            "0x19301acbe4991715738ae45dc646fd3557d32f75",
            "0x74ab349d130c3a27bcab3b6300dd33173b8a392b",
            "0x7f8c7c2dc5302b77fffd8128d6559950fde3d5c4",
            "0x366956c52a350dbaa0bc322559fd0d5d3180a9e9"
    };

    /**
     * 是否属于调试展示地址池。调试地址只用于界面展示，真实转账前用它做护栏，
     * 防止地址池里的地址被当成收款方。
     */
    public static boolean isDebugAddr(String addr) {
        if (addr == null || addr.isEmpty() || DEBUG_FIXED_ADDRs == null) return false;
        for (String d : DEBUG_FIXED_ADDRs) {
            if (d != null && addr.equalsIgnoreCase(d)) return true;
        }
        return false;
    }


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

    // 主界面统计的分片大小：每片回来就回填一次界面（地址多时不必等全部查完才出数）。
    // 取 10 的两个理由：
    // 1) 手机首屏大概就能看到 5~10 行，一片正好盖住"看得见的范围"，首片回来屏幕就满上；
    // 2) OkHttp 默认 5 连接/host，10 = 2 个波次，不浪费也不抢队。
    // 首片回来就会放开操作按钮，调大等于把首屏等待拉长
    public static final int STATS_CHUNK_SIZE = 10;

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

    // 自动清除日志（独立开关：只删除早于间隔的旧记录，保留最近数据）
    // 默认启用 + 默认保留 1 天：日志只留最近一天，数据库不会无限膨胀
    public static boolean logClearEnable = true;
    public static int logClearIntervalValue = 1;
    public static int logClearIntervalUnitIndex = 2; // 0=分钟,1=小时,2=天

    // 保存配置
    public static void saveConfig(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        SharedPreferences.Editor editor = sp.edit();

        editor.putBoolean("auto_clear_enable", autoClearEnable);
        editor.putInt("auto_clear_interval_value", autoClearIntervalValue);
        editor.putInt("auto_clear_interval_unit_index", autoClearIntervalUnitIndex);
        editor.putInt("play_mode_index", playModeIndex);
        editor.putBoolean("log_clear_enable", logClearEnable);
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
        logClearEnable = sp.getBoolean("log_clear_enable", true);
        // 存量脏配置兜底：数值非正、单位越界都回落到默认的 1 天，
        // 否则 getIntervalInMinutes() 会算出 0 或负数，清理永远等不到触发
        int lv = sp.getInt("log_clear_interval_value", 1);
        int lu = sp.getInt("log_clear_interval_unit_index", 2);
        logClearIntervalValue = lv > 0 ? lv : 1;
        logClearIntervalUnitIndex = (lu >= 0 && lu <= 2) ? lu : 2;
    }
}