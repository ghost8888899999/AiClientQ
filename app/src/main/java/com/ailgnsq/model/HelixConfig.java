package com.ailgnsq.model;

import java.math.BigInteger;

/**
 * HELIX（AiLGNS）侧的链上常量中心：合约地址、函数签名、数量单位与诊断阈值。
 *
 * <p>与 {@link ArenaConfig} 的分工：{@code ArenaConfig} 管「擂台」这一条业务线和全网共用的
 * 网络参数（RPC 节点、链 ID、超时、重试、批次），本类只管 HELIX 那 27 个合约的地址与 ABI。
 * RPC / chainId / 超时这些<b>故意不在这里再抄一份</b>——两处数值迟早会漂，而节点列表一旦不一致，
 * 排查「为什么擂台能连、HELIX 连不上」会浪费半天。</p>
 *
 * <p>这些地址全部是 <b>代理（Proxy）地址</b>，逻辑合约由 ERC1967 的 impl 槽指向；
 * 我们只读代理，状态也在代理侧，所以 {@code eth_call} 直接打代理地址就是对的。</p>
 *
 * <p><b>可信度声明（重要）</b>：HELIX 的合约在浏览器上全是未验证源码，下面每一条签名都在
 * {@code .qoder/HelixAbiCheck.java} 里做过三重链上只读验证——① 逻辑合约字节码的函数分发表命中
 * 该 4 字节选择器；② {@code eth_call} 差分：读数能返回、写入函数 revert 出的文案来自合约本身
 * （如 {@code "Turbine: zero amount"}），说明真进了函数体而不是「找不到函数」；
 * ③ 浏览器历史交易里确有地址在这么调。所以这里的签名不是照抄文档，是链上实测结果，
 * 改任何一条签名都请回去重跑那个工具，别凭印象改。</p>
 */
public class HelixConfig {

    // region 链参数（引用 ArenaConfig，不重复定义）

    /** 链 ID：与擂台同一条链（Anubis Chain），签名与校验统一走 {@link ArenaConfig#CHAIN_ID} */
    public static final long CHAIN_ID = ArenaConfig.CHAIN_ID;

    /** RPC 节点列表：直接复用擂台的三节点轮换，HELIX 没有独立节点 */
    public static final String[] RPC_URLS = ArenaConfig.RPC_URLS;

    /**
     * DAI：本链的 Gas 币，也是 HELIX 全系统的计价/支付币（18 位小数）。
     *
     * <p>与 {@link ArenaConfig#GAS_TOKEN_ADDR} 是同一枚币，这里只是给 HELIX 侧一个业务化的名字。</p>
     */
    public static final String DAI_ADDR = ArenaConfig.GAS_TOKEN_ADDR;

    // endregion

    // region 合约地址（均为 ERC1967 代理地址，取自 awakehelix.io 官方清单）

    /** LGNS：9 位小数的旧币，{@code burnForPower} 烧的就是它 */
    public static final String LGNS_ADDR = "0x4D1D808a081FdAc440703b3765FC61f8028C06B8";
    /** AiLGNS：18 位小数的主币（主池 token0），涡轮锁仓锁的就是它 */
    public static final String AILGNS_ADDR = "0x256EdB652db43E721EC6A12B6b51b6967D3bb8cB";

    /** Turbine：涡轮——DAI 换 AiLGNS 并锁仓（{@code activate} / {@code claimUnlocked} / {@code quote}） */
    public static final String TURBINE_ADDR = "0xe884a336550a4482Ca6A17A3F93aa30Ed4eD9BaD";
    /** PowerLedger：功率账本——烧 LGNS 换功率（{@code burnForPower}）与 {@code participate} */
    public static final String POWER_LEDGER_ADDR = "0x9E68F61E2615cc1D2C4a2D0026bFc1EBFdf68C1b";
    /** CommunityEmissionVault：社区发射金库——每日静态释放（{@code pendingOf} / {@code claimStatic}） */
    public static final String COMMUNITY_VAULT_ADDR = "0x3DEea5f638cA0dFb03A0873CF4c0b90BA50E1aA2";
    /** TwapOracle：只读价格源，只有 {@code lastTwapPrice()} / {@code lastTimestamp()} 两个读数口 */
    public static final String ORACLE_ADDR = "0x732e959fED889626686f9Fbe57233FE32b771963";
    /** 主池（AiLGNS/DAI）：{@code getReserves()} 用来校验 oracle 的口径 */
    public static final String PAIR_MAIN_ADDR = "0x0a1Cce65d995C6C5100e6F5bA3c388Ef34CEcaf0";
    /** LGNS 池（AiLGNS/LGNS）：只有比价时用得到 */
    public static final String PAIR_LGNS_ADDR = "0xc5029d6e80D2FDCE321B2b51010E9Ce3b1eDf1a2";
    /** USDT 池（AiLGNS/USDT）：同上 */
    public static final String PAIR_USDT_ADDR = "0x8A36A456E95Cd55eA8cbd79CcC798fd1f716db68";
    /** PowerLens：功率只读视图壳子，分发表里只认 {@code ledger()} 一个口 */
    public static final String POWER_LENS_ADDR = "0x2e902f1Fe0457812e2E78940304f3891Cce5fE11";
    /** NftProduction：生产资格 NFT（增发出来的那批号），转仓被 {@code transfersEnabled} 卡着 */
    public static final String NFT_PROD_ADDR = "0x77E6ab1C5557FD456b0656304E0e5c02a6d7DcCA";
    /** DailySettle：每日结算 */
    public static final String DAILY_SETTLE_ADDR = "0x2e0D9D461A1B567112A9b824d939FB583853dAA7";
    /** EmissionPlan：释放计划（金库 {@code schedule()} 指向它） */
    public static final String EMISSION_PLAN_ADDR = "0x6AaB661E1a99B20D1Dae581Ab0e1250E6f753a19";
    /** EpochClock：日切时钟（金库/账本/结算/链游全部挂在它上面） */
    public static final String EPOCH_CLOCK_ADDR = "0x6C5352E15A8327C3F44898FE220100Edc2989C58";

    // endregion

    // region 数量单位

    /** LGNS 精度 9：py 脚本里 {@code 500 * 10**9} 的 {@code 10**9} 就是这个数 */
    public static final int DEC_LGNS = 9;
    /** AiLGNS 精度 18 */
    public static final int DEC_AILGNS = 18;
    /** DAI 精度 18 */
    public static final int DEC_DAI = 18;

    /**
     * ai.py 写死的烧币额度：500 LGNS（{@code 500 * 10^9}）。
     *
     * <p>保留这个常量只为「照实复现原脚本语义」，不代表它现在可用——App 只用它做额度探针，不发交易。
     * 它同时正好是 {@code burnForPower} 的<b>额度下限</b>：2026-11-03 实测同一个真实地址上，
     * 1 / 10 / 100 / 200 LGNS 全部判 {@code PowerLedger: below minimum}，到 500 才改判
     * {@code LgnsBurnGate: not open today}（那道每日闸门，判据见 {@link #SIG_BURN_FOR_POWER}）。
     * 上一次（11-02）拿到的是 stake 区间那层判定：500 判 {@code stake below minimum}、
     * 加 1 wei 判 {@code above maximum}，当时区间为空集。按上面那个层次顺序推，那次采样时
     * 每日闸门是开着的（这句是推断，不是直读）。</p>
     */
    public static final BigInteger BURN_AMOUNT_RAW =
            BigInteger.valueOf(500).multiply(BigInteger.TEN.pow(DEC_LGNS));

    // endregion

    // region 诊断阈值

    /**
     * Oracle 读数过期告警线（秒）。
     *
     * <p>{@code lastTwapPrice} 不是合约自己更新的，靠外部调 {@code update()} 喂；实测采样时
     * {@code lastTimestamp} 比最新区块旧约 627 秒。这条线只用来在页面上标黄，
     * 不影响读数本身——价格旧不等于价格错。</p>
     */
    public static final long ORACLE_STALE_WARN_SEC = 1800L;

    /**
     * 用 {@code Turbine.quote(uint256)} 折算「1 AiLGNS 值多少 DAI」时的探针数量（1e18 wei）。
     *
     * <p>必须传非零：传 0 会被 Uniswap 库以 {@code INSUFFICIENT_OUTPUT_AMOUNT} revert，
     * 看起来就像函数不存在。取 1 个整币最直观，也正好和 oracle 的口径（每 1e18 AiLGNS
     * 对应多少 DAI wei）对齐，两个数可以直接摆在一起比。</p>
     */
    public static final BigInteger QUOTE_PROBE_AMOUNT_RAW =
            BigInteger.TEN.pow(DEC_AILGNS);

    /** 每个账号最多拉多少条锁仓 id（{@code locksOf} 返回的动态数组实测有 20 条，取 40 留余量） */
    public static final int MAX_LOCK_ROWS_SHOW = 40;

    /**
     * 只读面板一次最多查多少个账号的持仓。
     *
     * <p>实测每个账号要发 5 笔 {@code eth_call}，选中上百个号一次点完会打出几百笔请求。
     * 超出的部分在页面上明说「未查」，而不是悄悄少显示几行。</p>
     */
    public static final int MAX_POSITION_ACCOUNTS = 20;

    // endregion

    // region 写入参数（领发行 → 补 DAI → 授权 → 激活 → 结锁仓 → 归集）

    /**
     * {@code uint256} 最大值，也就是 ERC20 的无限授权额度。
     *
     * <p>不用 {@code BigInteger.TWO}：那是 Java 9 才有的字段，本工程的底线是 API 24。
     * 实测全链对 Turbine 的 DAI 授权就是这一串 F，所以无限授权在这个系统里是常态而不是例外。</p>
     */
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    /**
     * 下面四档 gas 上限全部照搬 {@code E:\py\AIlgns\ling1.py} 的写死值，<b>本工程未实测</b>。
     *
     * <p>之所以宁可带着这句「未实测」也不改成动态估算：{@code eth_estimateGas} 在这个节点上
     * 对写入函数会直接 revert（擂台那条线已经踩过）。但固定上限的代价是可能盖不住真实消耗，
     * 所以调用方必须像 {@code BatchClaimTask.isOutOfGas} 那样把「gas 没给够（OOG）」和
     * 「合约正常 reject」分开统计，不能都记成「没得领」。</p>
     */
    /** 金库领发行 {@code claimStatic(uint256)}：py 挂 30 万 */
    public static final BigInteger GAS_LIMIT_CLAIM_STATIC = BigInteger.valueOf(300000);
    /** 结到期锁仓 {@code claimUnlocked(uint256[])}：py 挂 40 万；实测有号攒到 37 笔，数组越长越费 */
    public static final BigInteger GAS_LIMIT_CLAIM_UNLOCKED = BigInteger.valueOf(400000);
    /** 涡轮激活 {@code activate(...)}：py 挂 80 万，是这一串里最贵的一笔 */
    public static final BigInteger GAS_LIMIT_ACTIVATE = BigInteger.valueOf(800000);
    /** ERC20 的 {@code approve}/{@code transfer}：py 挂 10 万（实测 transfer 只用 3.4 万） */
    public static final BigInteger GAS_LIMIT_ERC20 = BigInteger.valueOf(100000);

    /**
     * {@code activate} 第三参的余量：{@code 区块时间戳 + 这个秒数}。
     *
     * <p>600 是双样本一致的：官方 App 那 87 笔真交易的 deadline 比所在块时间戳大 600~1800 秒，
     * py 也是 {@code int(time.time()) + 600}。用<b>区块时间戳</b>而不是手机本地时间算：
     * 没同步过的手机能差好几分钟，方向还不确定。</p>
     */
    public static final long ACTIVATE_DEADLINE_SEC = 600L;

    /**
     * {@code maxDaiIn} 在 {@code quote().total} 上放的千分比余量（5 = 千分之五）。
     *
     * <p>两个口径都实测过：官方 App 的 87 笔真交易 {@code maxDaiIn / quote(amt).total ≈ 1.005}；
     * 而干跑时把余量放到 1.000 会挂在 {@code Turbine: exceeds max dai}——从报价到上链这几秒里
     * 池子已经在动了。py 用的是 1.02，比官方宽 4 倍：revert 是少了，但代价是允许更差的成交价，
     * 而这个上限保护的是付款方自己，所以这里跟官方而不是跟 py。</p>
     */
    public static final int MAX_DAI_SLIPPAGE_PERMILLE = 5;

    /**
     * 从主钱包补 DAI 时的余量（基点，2000 = 在实需上再加两成）。
     *
     * <p>实需 = 报价缺口 + 后续几笔的 gas。gas 是按实测单价算的，不是拍脑袋：
     * {@code eth_gasPrice = 12 Gwei}，所以 {@code activate} 那一笔 80 万上限最多花
     * 0.0096 DAI，全套四笔（approve + transfer + claimStatic + activate）合计不到 0.017 DAI。
     * py 里写死的「多补 2 DAI」是实需的两百倍：不算错，但每个子号都会沉下接近 2 DAI
     * 的尾页，而 py 只归集 AiLGNS、不归集 DAI，那些钱就永远留在子号里。</p>
     */
    public static final int DAI_TOPUP_BUFFER_BPS = 2000;

    /** 节点取不到 gasPrice 时的兼容值：12 Gwei，就是上面那个实测数 */
    public static final BigInteger GAS_PRICE_FALLBACK_RAW = new BigInteger("12000000000");

    /**
     * 交易里用的 AiLGNS 价格上限（每 1e18 AiLGNS 折多少 DAI wei）。
     *
     * <p>实测值：11 月 3 日采样时 {@code quote().total / amount ≈ 847}，含千分之五滑点后的
     * {@code maxDaiIn / amount} 恒为 851.4。这里不拿它当预算依据（价格会变），
     * 只用来在「报价明显偏离这个量级」时吹一声——数量级错了一整档（比如精度搞错 10 倍）
     * 能在发交易前就看出杵。</p>
     */
    public static final BigInteger PRICE_SANITY_MAX_RAW =
            new BigInteger("1000000000000000000000");

    // endregion

    // region 函数签名（选择器由 App 对签名字符串做 keccak 现算，下面注释是链上实测值）

    // ---- Oracle ----
    /** 实测 sel=0x45edac75；读数为「每 1e18 AiLGNS 折多少 DAI wei」 */
    public static final String SIG_LAST_TWAP_PRICE = "lastTwapPrice()";
    /** 实测 sel=0x19d8ac61；上一次 {@code update()} 的区块时间戳 */
    public static final String SIG_LAST_TIMESTAMP = "lastTimestamp()";
    /** 实测 sel=0x85af30c5 */
    public static final String SIG_MAIN_PAIR = "mainPair()";

    // ---- Turbine ----
    /** 实测 sel=0xed1bd76c；返回 3 个字：{@code (输出DAI, 手续费=输出/40, 输出+手续费)} */
    public static final String SIG_TURBINE_QUOTE = "quote(uint256)";
    /**
     * 实测 sel=0x8903ab9d；<b>锁仓到期、已解锁可领的 AiLGNS</b>（py 里变量名叫 claimable_unlocked）。
     * 非 0 时要用 {@link #SIG_CLAIM_UNLOCKED} 把这些锁仓结掉。
     */
    public static final String SIG_CLAIMABLE_OF = "claimableOf(address)";
    /** 实测 sel=0x59355736：还在锁仓期内的 AiLGNS */
    public static final String SIG_LOCKED_BALANCE_OF = "lockedBalanceOf(address)";
    /**
     * 实测 sel=0x9886c2a5；<b>已从金库领取、还没进涡轮的 AiLGNS——就是「待激活量」</b>。
     *
     * <p>这个口径是链上差分钉出来的，不是猜的：{@code activate(claimed+1, ...)} 直接 revert
     * {@code "Turbine: insufficient claimed"}，而 {@code activate(claimed, ...)} 通过；
     * 一次成功激活之后该地址读数归零。所以它就是 {@link #SIG_ACTIVATE} 第一个参数的上限。
     * 本类早期版本把它注释成「历史累计已领取」，那是错的，别再按那个说法写代码。</p>
     */
    public static final String SIG_CLAIMED_BALANCE = "claimedBalance(address)";
    /** 实测 sel=0xc3f9ab58；返回 {@code uint256[]}，实测某地址有 20 条 */
    public static final String SIG_LOCKS_OF = "locksOf(address)";
    /** 实测 sel=0x24a9d853（当前 250，即 2.5%） */
    public static final String SIG_FEE_BPS = "feeBps()";
    /** 实测 sel=0x53deb3d6（当前 150） */
    public static final String SIG_BURN_BPS = "burnBps()";
    /** 实测 sel=0xbfa6fddb（当前 86400 秒 = 1 天） */
    public static final String SIG_LOCK_SECONDS = "lockSeconds()";
    /** 锁仓总笔数，实测槽位值 535474；函数名从槽位语义推的，选择器未链上实测，所以故意不放进自检表 */
    public static final String SIG_LOCK_COUNT = "lockCount()";
    /** 实测 sel=0xd54ad2a1：全链累计已领取量 */
    public static final String SIG_TOTAL_CLAIMED = "totalClaimed()";
    /** 实测 sel=0xd55be8c6：手续费上限 1000，现值 250——项目方有 {@code setFees} 可改到 10% */
    public static final String SIG_MAX_FEE_BPS = "MAX_FEE_BPS()";
    /**
     * 激活交易：实测 sel=0xd96c5110，入参 {@code (待激活 AiLGNS wei, maxDaiIn wei, 截止时间戳（秒）)}。
     *
     * <p>三个参数各自的判据都是干跑差分出来的，改任何一条前先回看
     * {@code .qoder/helix_activate_probe.txt} 与 {@code .qoder/helix_matrix_probe.txt}：</p>
     * <ul>
     *   <li>数量从 {@link #SIG_CLAIMED_BALANCE} 里扣，多 1 wei 就 revert；</li>
     *   <li>第三个参数<b>是秒级时间戳，不是块号</b>：填「块号+300」会被
     *       {@code UniswapV2Router} 判 {@code EXPIRED}（块号当秒看等于 1970 年）；</li>
     *   <li>{@code maxDaiIn} 是「我最多愿意付多少 DAI」的上限，不是报价本身——
     *       填刚好等于 {@code quote().total} 会挂在 {@code Turbine: exceeds max dai}，
     *       因为报价到上链之间池子还在动。合约自己先校这一条，再进 router。</li>
     * </ul>
     */
    public static final String SIG_ACTIVATE = "activate(uint256,uint256,uint256)";
    /** 领取到期锁仓：实测 sel=0xadaace55，入参是锁仓 id 数组（{@link #SIG_LOCKS_OF} 的原样返回值） */
    public static final String SIG_CLAIM_UNLOCKED = "claimUnlocked(uint256[])";

    // ---- PowerLedger ----
    /** 实测 sel=0x4f343040 */
    public static final String SIG_TOTAL_POWER_OF = "totalPowerOf(address)";
    /**
     * 实测 sel=0xffa6b88a；只做探针，不发交易。
     *
     * <p>2026-11-03 实测出判定是分三层的，之前的「区间为空集」只说到了第三层：</p>
     * <ol>
     *   <li>低于 500 LGNS → {@code PowerLedger: below minimum}（所以下限就是 {@link #BURN_AMOUNT_RAW} 这一档）；</li>
     *   <li>正好 500 → {@code LgnsBurnGate: not open today}，即每日闸门。ai.py 里那两个
     *       11:59 / 23:59（UTC+8）各 90 秒的窗口，就是给这道闸门用的；</li>
     *   <li>只有窗口开着才会走到 stake 区间判定——11-02 那次拿到的就是这一层（500 判
     *       {@code stake below minimum}、加 1 wei 判 {@code above maximum}，当时区间为空集）；
     *       按这个顺序反推，那次采样时闸门是开着的。</li>
     * </ol>
     */
    public static final String SIG_BURN_FOR_POWER = "burnForPower(uint256)";
    /**
     * 实测 sel=0x845c9306：官方 App 实际在用的加功率入口。
     *
     * <p>链上取样（2026-11-03）：打到 PowerLedger 的最近 300 笔直接交易里 197 笔是这个选择器
     * （同日两次取样从 196 跳到 197，说明还在持续发生）。
     * 它不看每日闸门，对同一个真实地址 1 / 10 / 100 / 500 LGNS 四档干跑全部通过，
     * 零地址则回一个自定义错误（{@code 0xfb8f41b2}，合约未开源，解不出文案）。</p>
     */
    public static final String SIG_PARTICIPATE = "participate(uint256)";

    // ---- CommunityEmissionVault ----
    /** 实测 sel=0xf44136a1 */
    public static final String SIG_PENDING_OF = "pendingOf(address)";
    /** 实测 sel=0x064f3406；链上取样 25 笔全成功，是官方 App 用得最多的一个口 */
    public static final String SIG_CLAIM_STATIC = "claimStatic(uint256)";
    /** 实测 sel=0xe33b7de3：金库累计释放量 */
    public static final String SIG_TOTAL_RELEASED = "totalReleased()";

    // ---- 币与池 ----
    /** 实测 sel=0x313ce567 */
    public static final String SIG_DECIMALS = "decimals()";
    /** 实测 sel=0x70a08231 */
    public static final String SIG_BALANCE_OF = "balanceOf(address)";
    /** 实测 sel=0xdd62ed3e */
    public static final String SIG_ALLOWANCE = "allowance(address,address)";
    /** 实测 sel=0x18160ddd */
    public static final String SIG_TOTAL_SUPPLY = "totalSupply()";
    /** 实测 sel=0x095ea7b3；全链对 Turbine 的 DAI 授权实测就是 {@code MAX_UINT256}，即无限授权是常态 */
    public static final String SIG_APPROVE = "approve(address,uint256)";
    /** 实测 sel=0xa9059cbb */
    public static final String SIG_TRANSFER = "transfer(address,uint256)";
    /** 实测 sel=0x0902f1ac；返回 {@code (reserve0, reserve1, blockTimestampLast)} */
    public static final String SIG_GET_RESERVES = "getReserves()";

    // endregion

    // region ABI 自检表（App 算出的选择器必须与链上实测一致）

    /**
     * 签名 → 链上实测选择器。
     *
     * <p>用途只有一个：{@code HelixContractApi.abiSelfCheck()} 拿 web3j 现算的选择器和这张表比，
     * 一旦哪天有人把签名里的参数类型打错（{@code address} 打成 {@code uint256}、少了个逗号），
     * 页面上立刻显示不匹配，而不是安静地 revert 成「查不到」。<b>这正是 py 那两个脚本栽过的坑</b>：
     * 每个查询都 {@code try/except: pass}，签名错了也照样跑完。</p>
     *
     * <p>表里的值全部来自 {@code .qoder/helix_abi_report*.txt}，不是文档抄来的。</p>
     *
     * <p>反过来，没实测锚点的签名（如 {@link #SIG_LOCK_COUNT}）不要加进来：表里塞一个「算出来等于
     * 自己」的伪实测值，等于把自检从安全网改成橡皮图章。它们只在调用时 revert → 那一行显示 {@code --}。</p>
     */
    public static final String[][] VERIFIED_SELECTORS = {
            {SIG_LAST_TWAP_PRICE, "0x45edac75"},
            {SIG_LAST_TIMESTAMP, "0x19d8ac61"},
            {SIG_MAIN_PAIR, "0x85af30c5"},
            {SIG_TURBINE_QUOTE, "0xed1bd76c"},
            {SIG_CLAIMABLE_OF, "0x8903ab9d"},
            {SIG_LOCKED_BALANCE_OF, "0x59355736"},
            {SIG_CLAIMED_BALANCE, "0x9886c2a5"},
            {SIG_LOCKS_OF, "0xc3f9ab58"},
            {SIG_FEE_BPS, "0x24a9d853"},
            {SIG_BURN_BPS, "0x53deb3d6"},
            {SIG_LOCK_SECONDS, "0xbfa6fddb"},
            {SIG_TOTAL_CLAIMED, "0xd54ad2a1"},
            {SIG_MAX_FEE_BPS, "0xd55be8c6"},
            {SIG_ACTIVATE, "0xd96c5110"},
            {SIG_CLAIM_UNLOCKED, "0xadaace55"},
            {SIG_TOTAL_POWER_OF, "0x4f343040"},
            {SIG_BURN_FOR_POWER, "0xffa6b88a"},
            {SIG_PARTICIPATE, "0x845c9306"},
            {SIG_PENDING_OF, "0xf44136a1"},
            {SIG_CLAIM_STATIC, "0x064f3406"},
            {SIG_TOTAL_RELEASED, "0xe33b7de3"},
            {SIG_GET_RESERVES, "0x0902f1ac"},
            {SIG_DECIMALS, "0x313ce567"},
            {SIG_BALANCE_OF, "0x70a08231"},
            {SIG_ALLOWANCE, "0xdd62ed3e"},
            {SIG_TOTAL_SUPPLY, "0x18160ddd"},
            // 下面两条不是 HELIX 自己的函数，是 ERC20 的标准值：它们对不上就说明 keccak 算歪了
            {SIG_TRANSFER, "0xa9059cbb"},
            {SIG_APPROVE, "0x095ea7b3"},
    };

    // endregion

    private HelixConfig() {
        // 常量类，不允许实例化
    }
}
