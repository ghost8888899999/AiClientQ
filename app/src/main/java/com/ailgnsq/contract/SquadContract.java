package com.ailgnsq.contract;

/**
 * Squad 委托合约的 ABI 与字节码常量。
 *
 * <p>Squad 是「一个主号代理一群子号」的批量入口：子号先通过 EIP-7702 把代码授权给
 * Squad 合约，之后由主号一次性调用 {@code playSome / harvestSome} 批量入场与收取，
 * 子号自己不消耗原生 gas（gas 走单独的 gasToken）。</p>
 *
 * <p>与 {@link SquadApi} 的分工：本类只放静态数据，编解码与调用逻辑在 SquadApi。</p>
 */
public final class SquadContract {

    /** 工具类，禁止实例化 */
    private SquadContract() {}

    /**
     * 合约 ABI（JSON 字符串）。用到的入口：
     * {@code playSome}（批量入场）、{@code harvestSome}（批量收获）、
     * {@code sweepGasSome}（批量回收 gas 代币）、{@code pulse}（单账号状态查询）、
     * {@code overview}（全局概览）。
     */
    public static final String ABI = "[{\"type\":\"constructor\",\"inputs\":[{\"name\":\"arena\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"token\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"lineage\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"gasToken\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"master\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"shifu\",\"type\":\"address\",\"internalType\":\"address\"}],\"stateMutability\":\"nonpayable\"},{\"type\":\"receive\",\"stateMutability\":\"payable\"},{\"type\":\"function\",\"name\":\"addMembers\",\"inputs\":[{\"name\":\"who\",\"type\":\"address[]\",\"internalType\":\"address[]\"}],\"outputs\":[],\"stateMutability\":\"nonpayable\"},{\"type\":\"function\",\"name\":\"allMembers\",\"inputs\":[],\"outputs\":[{\"name\":\"\",\"type\":\"address[]\",\"internalType\":\"address[]\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"config\",\"inputs\":[],\"outputs\":[{\"name\":\"arena\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"token\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"lineage\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"gasToken\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"shifu\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"master\",\"type\":\"address\",\"internalType\":\"address\"},{\"name\":\"delegationTarget\",\"type\":\"address\",\"internalType\":\"address\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"harvestSome\",\"inputs\":[{\"name\":\"who\",\"type\":\"address[]\",\"internalType\":\"address[]\"}],\"outputs\":[{\"name\":\"total\",\"type\":\"uint256\",\"internalType\":\"uint256\"}],\"stateMutability\":\"nonpayable\"},{\"type\":\"function\",\"name\":\"isDelegated\",\"inputs\":[{\"name\":\"who\",\"type\":\"address\",\"internalType\":\"address\"}],\"outputs\":[{\"name\":\"\",\"type\":\"bool\",\"internalType\":\"bool\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"isMember\",\"inputs\":[{\"name\":\"\",\"type\":\"address\",\"internalType\":\"address\"}],\"outputs\":[{\"name\":\"\",\"type\":\"bool\",\"internalType\":\"bool\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"memberCount\",\"inputs\":[],\"outputs\":[{\"name\":\"\",\"type\":\"uint256\",\"internalType\":\"uint256\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"overview\",\"inputs\":[],\"outputs\":[{\"name\":\"total\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"upgraded\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"roundId\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"dayId\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"masterBalance\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"masterGas\",\"type\":\"uint256\",\"internalType\":\"uint256\"}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"playSome\",\"inputs\":[{\"name\":\"who\",\"type\":\"address[]\",\"internalType\":\"address[]\"}],\"outputs\":[{\"name\":\"funded\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"reaped\",\"type\":\"uint256\",\"internalType\":\"uint256\"},{\"name\":\"entered\",\"type\":\"uint256\",\"internalType\":\"uint256\"}],\"stateMutability\":\"nonpayable\"},{\"type\":\"function\",\"name\":\"pulse\",\"inputs\":[{\"name\":\"who\",\"type\":\"address\",\"internalType\":\"address\"}],\"outputs\":[{\"name\":\"p\",\"type\":\"tuple\",\"internalType\":\"struct Squad.Pulse\",\"components\":[{\"name\":\"wallet\",\"type\":\"uint256\"},{\"name\":\"settled\",\"type\":\"uint256\"},{\"name\":\"unsettled\",\"type\":\"uint256\"},{\"name\":\"lineage\",\"type\":\"uint256\"},{\"name\":\"jiazi\",\"type\":\"uint256\"},{\"name\":\"total\",\"type\":\"uint256\"},{\"name\":\"ready\",\"type\":\"uint256\"},{\"name\":\"locked\",\"type\":\"uint256\"},{\"name\":\"canHarvest\",\"type\":\"bool\"},{\"name\":\"blocksToWait\",\"type\":\"uint256\"},{\"name\":\"seated\",\"type\":\"bool\"},{\"name\":\"delegated\",\"type\":\"bool\"},{\"name\":\"jiaziTruncated\",\"type\":\"bool\"},{\"name\":\"matchesToday\",\"type\":\"uint256\"},{\"name\":\"lastRound\",\"type\":\"uint256\"},{\"name\":\"currentRound\",\"type\":\"uint256\"},{\"name\":\"openSeats\",\"type\":\"uint256\"},{\"name\":\"seats\",\"type\":\"uint256\"},{\"name\":\"backlog\",\"type\":\"uint256\"},{\"name\":\"seatNo\",\"type\":\"uint256\"},{\"name\":\"gasBalance\",\"type\":\"uint256\"}]}],\"stateMutability\":\"view\"},{\"type\":\"function\",\"name\":\"removeMember\",\"inputs\":[{\"name\":\"who\",\"type\":\"address\",\"internalType\":\"address\"}],\"outputs\":[],\"stateMutability\":\"nonpayable\"},{\"type\":\"function\",\"name\":\"sweepGasSome\",\"inputs\":[{\"name\":\"who\",\"type\":\"address[]\",\"internalType\":\"address[]\"}],\"outputs\":[{\"name\":\"total\",\"type\":\"uint256\",\"internalType\":\"uint256\"}],\"stateMutability\":\"nonpayable\"}]";

    /**
     * 部署字节码。目前只保留了头尾片段，不是可直接广播的完整值：
     * 部署走的是预先部署好的合约地址（{@code ArenaConfig.SQUAD_ADDR}），此常量暂未参与运行时逻辑。
     *
     * <p>TODO: 若要做应用内部署，需补全完整的 squadBytecode（约 19KB hex 字符串）。</p>
     */
    public static final String BYTECODE = "0x610180604052348015610010575f5ffd5b50...efa07446fad45314351eb89109a154880278451332bb87f1824d435fe58da5939";
}
