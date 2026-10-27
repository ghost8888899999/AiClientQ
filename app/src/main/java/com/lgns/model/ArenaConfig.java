package com.lgns.model;

import android.content.Context;
import android.content.SharedPreferences;

import com.lgns.manager.AppPrefs;

import java.math.BigInteger;

/**
 * 全局配置中心：链上常量、RPC 节点、交易参数，以及用户可持久化的几项配置。
 *
 * <p>分层：</p>
 * <ul>
 *   <li>编译期常量：节点、合约地址、链 ID、手续费/批次相关数值、超时与重试；
 *   数值口径必须与合约 {@code FunLonginusArena} 保持一致，改合约就要同步这里。</li>
 *   <li>静态可变字段：自动清理、日志保留、入场模式等，通过 {@link #loadConfig} / {@link #saveConfig}
 *   在 SharedPreferences 与内存之间同步，页面直接读写这两个静态字段。</li>
 * </ul>
 *
 * <p>使用注意：带 {@code volatile} 的字段会被任务线程与 UI 线程共享；
 * 其余静态配置字段只在主线程改、后台线程读，不做额外同步。</p>
 */
public class ArenaConfig {

    // region 网络与链上地址

    /** 三个 RPC 节点：按顺序轮换 + 熔断，单节点宕机不会让整批任务停摆 */
    public static final String[] RPC_URLS = {
            "https://rpc.anubispace.org",
            "https://rpc1.anubispace.org",
            "https://rpc2.anubispace.org",
    };
    /** 兼容旧字段，始终指向首个节点。业务代码请改用 RPC_URLS + Web3AndroidUtil.rotateWeb3j() */
    public static final String RPC_URL = RPC_URLS[0];

    /** 擂台合约地址（FunLonginusArena）：入场、领取、统计查询都打这个地址 */
    public static final String ARENA_ADDR = "0xff060bAB41e16eBF0eFE4FAd454404Fd5f75aAaA";

    /**
     * Gas 币：本链的手续费是一枚 ERC20（Dai Stablecoin）。
     *
     * <p>eth_getBalance 与它的 balanceOf 完全相等，但原生 value 转账会被节点按「余额不足」拒绝，
     * 所以补 Gas 只能给它做 transfer。</p>
     */
    public static final String GAS_TOKEN_ADDR = "0x83fd06F0846d9D90B3016bF670Efe2E0B11cDe14";

    /** 链 ID：签交易时写进 EIP-155，防跨链重放；pending 恢复时也用它校验是否同一条链 */
    public static final long CHAIN_ID = 6714L;

    // endregion

    // region 调试开关与地址池

    /**
     * 调试展示开关（发版前必须置为 false）。
     *
     * <p>开启后：主界面列表不再按私钥数量出数，而是把下面的地址池全部列出（按分组平分），
     * 锦标赛统计同步按这些地址出数；只改内存不写库，私钥管理页与批量任务仍用真实私钥。
     * 仅用于看界面效果。</p>
     */
    public static final boolean DEBUG_FIXED_ADDR_ENABLE = false;
    /**
     * 调试展示地址池：每条地址都作为主界面的一行，按分组顺序平分到各分组。
     *
     * <p><b>这批是链上真实的玩家地址，但没有任何一条的私钥。</b>所以：</p>
     * <ul>
     *   <li>能用的：查 {@code playerSummary}、算统计、跑锦标赛——数据全是真的，
     *   适合拿它们对账界面算得对不对；</li>
     *   <li>不能用的：任何需要签名的动作（入场/领取/转账）。{@code buildDebugKeyItems}
     *   给的 cipherText 是 null、keyId 是负数，拿去解密只会报「解密失败」，
     *   要验证写链路必须换真实私钥账号。</li>
     * </ul>
     * <p>写操作不经过本池：批量任务的入参走 {@code getEnabledKeyItems()}（真实清单），
     * 只有展示与统计走 {@code getDisplayKeyItems()} / {@code getSelectedKeyItem()}。</p>
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

    // endregion

    // region Squad 委托（EIP-7702）

    /** Squad 合约地址：null 表示未部署/未配置，部署后由用户设置 */
    public static volatile String SQUAD_ADDR = null;

    /**
     * 主号私钥（用于 Squad 模式的 SetCodeTx 委托 + 批量交易签名）。
     * 运行时由 UI 层设置，任务结束后清零。
     */
    public static volatile String MASTER_PK = null;

    // endregion

    // region 合约常量（与 FunLonginusArena 对齐）

    /** 入场费 100 * 10^18（合约 ENTRY_FEE）：每场先扣门票，本场结束再按名次返还 */
    public static final BigInteger ENTRY_FEE = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));

    /**
     * 批量充值的目标持有量：连打 N 圈时每个号该备几张门票（下标 = 圈数）。
     *
     * <p><b>为什么不能恒等于 1 张</b>：第 1 圈全员从钱包扣 100；结算时 23 个输家拿到 100.8
     * 进 {@code claimable}，下一圈直接抵扣不用再出钱；<b>但唯一那个冠军拿的是 200 甲子
     * （锁仓 60 天），claimable 仍是 0</b>，下一圈只能再从钱包扣 100。
     * 每夺冠一次就多耗一张门票，所以目标量要按「N 圈内单号最坏夺冠次数」预留。</p>
     *
     * <p>取值依据：单号在 N 圈内夺冠次数近似泊松分布（λ = (N-1)/24），按「24 个号里
     * 最坏那个也够用」取——12 圈取 5 张（500 fLGNS）时，某号需第 6 张的概率约 0.5%。
     * 不能省：缺一个号就让这一轮凑不满 {@link #SEATS_PER_ROUND} 座、后续所有圈全部取消，
     * 代价远大于多充几百。</p>
     */
    private static final int[] FUND_TICKETS_BY_ROUNDS = {
            0,  // 占位：圈数从 1 开始
            1,  // 1 圈：只打一场，备一张门票
            2,  // 2 圈
            2,  // 3 圈
            3,  // 4 圈
            3,  // 5 圈
            3,  // 6 圈
            4,  // 7 圈
            4,  // 8 圈
            4,  // 9 圈
            5,  // 10 圈
            5,  // 11 圈
            5,  // 12 圈
    };

    /**
     * 连打 {@code rounds} 圈时，批量充值应把每个号补到的目标余额（含首场门票）。
     *
     * <p>圈数为 1 时等价于 {@link #ENTRY_FEE}；越界圈数先夹到 {@code [1, MAX_ENTRIES_PER_DAY]}
     * 再查表，保证任何时候都有确定值。调用方拿它同时做「筛不筛进待充名单」「算差额」
     * 「判断是否已够」三件事，<b>三处必须是同一个值</b>，否则会出现「筛进来了却算出差额 0」。</p>
     */
    public static BigInteger fundTargetFor(int rounds) {
        int r = Math.max(1, Math.min(rounds, MAX_ENTRIES_PER_DAY));
        return ENTRY_FEE.multiply(BigInteger.valueOf(FUND_TICKETS_BY_ROUNDS[r]));
    }

    /** 甲子俸单页领取上限 64（合约 MAX_JIAZI_BATCH）：服务端限制每次调用最多释放 64 条记录 */
    public static final BigInteger MAX_JIAZI_BATCH = BigInteger.valueOf(64);
    /** 日切偏移 8 小时：合约按 UTC+8 算「今天」，本地算今日场次时必须加上这个偏移 */
    public static final long DAY_OFFSET = 8 * 3600L;

    /**
     * 一天打满这么多场，师徒分成才 100% 解锁（合约 FULL_ACTIVITY_MATCHES）。
     *
     * <p>不足时按 10%/场折算，且只认「当天」的场次：今天没打，昨天攒下的分成一分都领不到，
     * 到 D+2 就会被合约的 {@code _burnExpiredLineage} 烧掉。所以界面上要把「今日已打几场」
     * 和「解锁几成」绑在一起显示，那是唯一能提醒用户补场的机会。</p>
     */
    public static final int FULL_ACTIVITY_MATCHES = 10;

    /**
     * 一天最多能入场的场次（合约 MAX_ENTRIES_PER_DAY）：超过就 revert {@code DailyLimitReached}。
     *
     * <p>它不是「收益上限」，而是「收益形态的分界线」：第 11、12 场仍会正常买座、
     * 参与封盘与结算（输家 +100.8、冠军仍拿 200 甲子），但合约里
     * {@code if (matches >= FULL_ACTIVITY_MATCHES) return false} 已提前返回，
     * 所以不再推进甲子释放、也不提高师徒系数与解锁比例。每多打一轮就多烧 66，
     * 新增的甲子只会拉长 60 天的释放队列，所以界面默认推荐 10 场。</p>
     */
    public static final int MAX_ENTRIES_PER_DAY = 12;

    /**
     * 连打圈数的出厂默认值：1 圈，点一次打一场。
     *
     * <p>只影响「从没在系统配置页点过保存」的设备：{@code loadConfig} 读 SP 时用这个值做默认。
     * 用户一旦改过并存盘，就以 SP 里的值为准，不会被本常量覆盖。</p>
     *
     * <p>为什么默认 1 而不是日上限 12：老用户的心智模型是「点一次 = 打一场」，
     * 也就是旧版（wlClient_2-1026）「批量充值 + 批量入场」成对点 12 次的节奏，
     * 每点完一次就能立刻看到这一局的输赢。若默认 12，第一次点的人会被直接锁进十几分钟的长任务，
     * 中途想停还得按停止键等当前这圈封盘。默认 1 把「一次跑多少」变成显式选择，可预期。</p>
     *
     * <p>代价要讲清楚：只打 1 场时师徒分成只解锁 10%（满勤线是 {@link #FULL_ACTIVITY_MATCHES} = 10），
     * 当天不补满，剩下的 90% 到 D+2 就被合约烧掉。所以想拿满收益的用户必须自己把这一项改到 10~12，
     * 这是唯一需要主动改的配置。</p>
     */
    public static final int DEFAULT_ENTER_ROUNDS = 1;

    /** 一轮需要的座位数（合约 SEATS）：满 24 座才封盘、才会进结算队列 */
    public static final int SEATS_PER_ROUND = 24;

    // endregion

    // region Gas limit

    /** 入场：同一笔调用里还要顺带结算与累计师徒分成，实测 gas 远高于普通 transfer，留足余量 */
    public static final BigInteger GAS_LIMIT_ENTER_ARENA = BigInteger.valueOf(1500000);
    /** 领取甲子俸：内部要遍历释放记录并逐笔转账，按 300k 预估 */
    public static final BigInteger GAS_LIMIT_CLAIM_JIAZI = BigInteger.valueOf(300000);
    /** 领取师徒分成：与甲子俸同为「遍历 + 转账」流程，沿用同一档 gas 便于排查 */
    public static final BigInteger GAS_LIMIT_CLAIM_LINEAGE = BigInteger.valueOf(300000);
    /** 领取擂台奖金：单笔转账型操作，200k 足够 */
    public static final BigInteger GAS_LIMIT_CLAIM_REWARDS = BigInteger.valueOf(200000);
    /** 普通 ERC20 transfer（批量转账收款方入账等场景） */
    public static final BigInteger GAS_LIMIT_ERC20_TRANSFER = BigInteger.valueOf(100000);
    /** 补 Gas 走 GAS_TOKEN 的 transfer：实测 gasUsed ≈ 34,768，留一倍余量防 OOG */
    public static final BigInteger GAS_LIMIT_GAS_TOKEN_TRANSFER = BigInteger.valueOf(80000);

    /**
     * 单笔入场的 gas 估算值（不是上限）：合约注释里的实测值是 138,730，留 1.5 倍余量。
     *
     * <p>为什么不能直接用 {@link #GAS_LIMIT_ENTER_ARENA}（1,500,000）来算「DAI 够不够打 N 圈」：
     * 那是给交易留的硬上限（带结算、带师徒分成的偶发大额消耗），拿它当估算会把需求高估十倍，
     * 用户 DAI 明明够也会被预检拦住。反过来也不能只填实测值，gas 会随结算轮数波动。</p>
     */
    public static final BigInteger ENTER_GAS_ESTIMATE = BigInteger.valueOf(208_095L);

    /**
     * 预检时给「领取阶段」预留的 gas 额度：甲子俸 / 师徒分成 / 擂台奖金三笔的上限之和。
     *
     * <p>为什么算 DAI 是否够打 N 圈时要把这笔扣掉：入场和领取是两个阶段，同一个号要付两次手续费。
     * 只按入场算，预检会放行「刚好打得动 12 圈」的账号，等打完第 12 圈才发现在 DAI 上见底，
     * 于是那一批师徒分成和甲子俸全部领不回来——比少打一圈贵得多。</p>
     */
    public static final BigInteger CLAIM_GAS_RESERVE = GAS_LIMIT_CLAIM_JIAZI
            .add(GAS_LIMIT_CLAIM_LINEAGE).add(GAS_LIMIT_CLAIM_REWARDS);

    // endregion

    // region 超时与重试

    /** 交易回执最大等待秒：超过仍未出块则按 pending 处理，交给恢复流程判断 */
    public static final int TX_TIMEOUT_SEC = 180;
    /** 回执轮询间隔毫秒：过密会打满节点，过疏又拉长单笔耗时 */
    public static final long TX_WAIT_POLL_MS = 1200L;
    /** 单次 RPC 调用（eth_call/eth_getTransactionReceipt）超时秒 */
    public static final int RPC_CALL_TIMEOUT_SEC = 10;
    /** 建立连接超时秒：节点宕机时能尽快切到下一个 */
    public static final int RPC_DIAL_TIMEOUT_SEC = 8;
    /** 业务调用最大重试次数 = 节点数 * 2：保证每个节点至少被轮到一次半 */
    public static final int RPC_MAX_RETRY = RPC_URLS.length * 2;

    // endregion

    // region 查询分片与护栏

    /** 整批 gas 上限护栏：预估 gas 超过区块 gasLimit * 该比例 → 拒发 */
    public static final int BLOCK_GAS_LIMIT_MARGIN_PCT = 85;

    /** 批量查询单批切片大小：一次 eth_call 里打包多少个账号的查询 */
    public static final int BATCH_CALL_SIZE = 80;

    /**
     * 主界面统计的分片大小：每片回来就回填一次界面（地址多时不必等全部查完才出数）。
     *
     * <p>取 10 的两个理由：</p>
     * <ol>
     *   <li>手机首屏大概就能看到 5~10 行，一片正好盖住「看得见的范围」，首片回来屏幕就满上；</li>
     *   <li>OkHttp 默认 5 连接/host，10 = 2 个波次，不浪费也不抢队。</li>
     * </ol>
     * <p>首片回来就会放开操作按钮，调大等于把首屏等待拉长。</p>
     */
    public static final int STATS_CHUNK_SIZE = 10;

    // endregion

    // region 会话与 pending

    /**
     * 空闲自动上锁秒（默认 4 小时）：到期后 {@code AppGlobal} 会把会话置空，要求重新输入密码。
     *
     * <p>口径是「最后一次触摸 / 切回前台 / 解密操作距今多久」，不是「App 是否还开着」——
     * 进程活着，计时器就一直跑。原来取 30 分钟对「挂着看行情」太短：
     * 用户明明在滑列表，也会被锁，因为计时器此前只认解密链路。
     * 页面层的触摸续期已统一收口到 {@code BaseActivity}。</p>
     */
    public static final long IDLE_LOCK_SEC = 4 * 60 * 60L;

    /** Pending 状态超过该时长仍未回执，且账户当前 nonce > 记录 nonce → 判为 nonce-consumed */
    public static final long PENDING_STALE_SEC = 5 * 60L;

    // endregion

    // region 用户配置（内存镜像，与 SP 同步）

    /** 私钥自动清除总开关：到期待清除时启动即清空全部私钥，默认关闭 */
    public static boolean autoClearEnable = false;
    /** 私钥清除间隔数值，与 unitIndex 组成「N 分钟/小时/天」 */
    public static int autoClearIntervalValue = 1; // 默认1
    /** 间隔单位：0=分钟，1=小时，2=天 */
    public static int autoClearIntervalUnitIndex = 1; // 0=分钟,1=小时,2=天

    /** 入场模式：0=能打就打，1=等齐再打（对应 MainActivity.MODE_*） */
    public static int playModeIndex = 0; // 0=能打就打,1=等齐再打

    /**
     * 批量入场连打圈数：1 = 打一场就停，{@link #FULL_ACTIVITY_MATCHES} = 一次点完当日满勤。
     *
     * <p>一圈 = 每个账号入场 1 次。为什么需要连打：合约按「今天打了几场」线性解锁师徒分成，
     * 只打一场只能领 10%，剩下 90% 到 D+2 就被烧掉。而同一轮不能重复入座，
     * 所以打第 2 场必须等上一轮凑满 24 座封盘。</p>
     *
     * <p>上限取 {@link #MAX_ENTRIES_PER_DAY}（合约硬上限 12），出厂默认值是 1
     * （见 {@link #DEFAULT_ENTER_ROUNDS}），即「点一次打一场」，沿用旧版的操作习惯；
     * 想一次点满当日满勤就把这一项改到 10~12。
     * 但 12 不等于「都该打」：打满 {@link #FULL_ACTIVITY_MATCHES} 场后甲子就不再推进、
     * 师徒系数也封顶，第 11、12 场只剩参赛收益（输 100.8 / 冠军 200 甲子）而不再有结构性收益，
     * 且每轮固定烧 66。想省 gas 的用户可以在配置页下调到 10。</p>
     *
     * <p>账号数不是 24 的整数倍时，多出来的号会卡在未封盘的轮次里，任务会等到超时并提示。</p>
     */
    public static int enterRounds = DEFAULT_ENTER_ROUNDS;

    /**
     * 日志自动清除（独立开关：只删除早于间隔的旧记录，保留最近数据）。
     * 默认启用 + 默认保留 1 天：日志只留最近一天，数据库不会无限膨胀。
     */
    public static boolean logClearEnable = true;
    /** 日志保留间隔数值，与 unitIndex 组成「N 分钟/小时/天」 */
    public static int logClearIntervalValue = 1;
    /** 日志保留单位：0=分钟，1=小时，2=天 */
    public static int logClearIntervalUnitIndex = 2; // 0=分钟,1=小时,2=天

    // endregion

    // region 配置读写

    /**
     * 把内存里的用户配置落盘（七个 key，一次性写入）。
     * 只存用户可选项，不碰链上常量与调试开关。
     */
    public static void saveConfig(Context context) {
        SharedPreferences sp = AppPrefs.get(context);
        SharedPreferences.Editor editor = sp.edit();

        editor.putBoolean("auto_clear_enable", autoClearEnable);
        editor.putInt("auto_clear_interval_value", autoClearIntervalValue);
        editor.putInt("auto_clear_interval_unit_index", autoClearIntervalUnitIndex);
        editor.putInt("play_mode_index", playModeIndex);
        editor.putInt("enter_rounds", enterRounds);
        editor.putBoolean("log_clear_enable", logClearEnable);
        editor.putInt("log_clear_interval_value", logClearIntervalValue);
        editor.putInt("log_clear_interval_unit_index", logClearIntervalUnitIndex);

        editor.apply();
    }

    /**
     * 读配置并回填静态字段。
     *
     * <p>日志保留时长做了存量脏配置兜底：数值非正、单位越界都回落到默认的 1 天，
     * 否则 {@code getIntervalInMinutes()} 会算出 0 或负数，清理永远等不到触发。</p>
     */
    public static void loadConfig(Context context) {
        SharedPreferences sp = AppPrefs.get(context);

        autoClearEnable = sp.getBoolean("auto_clear_enable", false);
        autoClearIntervalValue = sp.getInt("auto_clear_interval_value", 1);
        autoClearIntervalUnitIndex = sp.getInt("auto_clear_interval_unit_index", 1);
        playModeIndex = sp.getInt("play_mode_index", 0);
        // 存量/越界兜底：非正或超出日上限一律回落到出厂默认值（1 场），
        // 否则任务会按一个无意义的圈数循环，或者直接把日上限撞满
        int rounds = sp.getInt("enter_rounds", DEFAULT_ENTER_ROUNDS);
        enterRounds = (rounds >= 1 && rounds <= MAX_ENTRIES_PER_DAY) ? rounds : DEFAULT_ENTER_ROUNDS;
        logClearEnable = sp.getBoolean("log_clear_enable", true);
        // 存量脏配置兜底：数值非正、单位越界都回落到默认的 1 天，
        // 否则 getIntervalInMinutes() 会算出 0 或负数，清理永远等不到触发
        int lv = sp.getInt("log_clear_interval_value", 1);
        int lu = sp.getInt("log_clear_interval_unit_index", 2);
        logClearIntervalValue = lv > 0 ? lv : 1;
        logClearIntervalUnitIndex = (lu >= 0 && lu <= 2) ? lu : 2;
    }

    // endregion
}