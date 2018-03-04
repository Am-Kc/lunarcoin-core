package io.lunarchain.lunarcoin.core

import io.lunarchain.lunarcoin.miner.BlockMiner
import io.lunarchain.lunarcoin.miner.MineResult
import io.lunarchain.lunarcoin.network.Peer
import io.lunarchain.lunarcoin.network.client.PeerClient
import io.lunarchain.lunarcoin.sync.SyncManager
import io.lunarchain.lunarcoin.util.CryptoUtil
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import java.net.URI

class BlockChainManager(val blockChain: BlockChain) {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        lateinit var INSTANCE: BlockChainManager
    }

    init {
        INSTANCE = this
    }

    /**
     * 等待加入区块的交易数据。
     */
    val pendingTransactions = mutableListOf<Transaction>()

    /**
     * 当前连接的Peer。
     */
    var peers = mutableListOf<Peer>()

    /**
     * 等待发现的Node。
     */
    var discoveryNodes = mutableListOf<Node>()

    /**
     * 是否正在挖矿中。
     */
    var mining: Boolean = false

    /**
     * 正在挖矿中的区块。
     */
    var miningBlock: Block? = null

    /**
     * 是否正在同步区块中。
     */
    var synching: Boolean = false

    /**
     * 当前交易用账户。
     */
    var currentAccount: AccountWithKey? = null

    val syncManager = SyncManager(this, blockChain)

    /**
     * 将Transaction加入到Pending List。
     */
    fun addPendingTransaction(trx: Transaction) {
        if (trx.isValid) {
            pendingTransactions.add(trx)
        } else {
            logger.debug("Invalid transaction $trx was ignored.")
        }
    }

    /**
     * 批量将Transaction加入到Pending List。
     */
    fun addPendingTransactions(transactions: List<Transaction>) {
        logger.debug("Appending ${transactions.size} transactions to pending transactions.")

        transactions.map { addPendingTransaction(it) }
    }

    /**
     * 增加Peer连接。
     */
    fun addPeer(peer: Peer): Int {
        logger.debug("Peer connected: $peer")

        if (peers.none { it.node.nodeId == peer.node.nodeId }) {
            peers.add(peer)
        } else {
            logger.debug("Peer $peer.node.nodeId already exists in connected peer list")
            return -1
        }

        // 监听Peer的连接关闭事件
        peer.channel.closeFuture().addListener { notifyPeerClosed(peer) }

        return 0
    }

    private fun notifyPeerClosed(peer: Peer) {
        logger.debug("Peer closed: $peer.")
        peers.remove(peer)
    }

    /**
     * 开始异步Mining。
     */
    fun startMining() {
        if (!mining) {
            mining = true

            mineBlock()
        }
    }

    /**
     * 停止异步Mining。
     */
    fun stopMining() {

        mining = false
        BlockMiner.stop()
    }

    fun mineBlock() {
        logger.debug("mineBlock.")
        Flowable.fromCallable({
            val bestBlock = blockChain.getBestBlock()
            miningBlock = blockChain.generateNewBlock(bestBlock, pendingTransactions)
            BlockMiner.mine(miningBlock!!, bestBlock.time.millis / 1000, bestBlock.difficulty)
        })
            .subscribeOn(Schedulers.computation())
            .observeOn(Schedulers.single())
            .repeatUntil({ !mining })
            .subscribe({
                if (it.success) {
                    processMinedBlock(it)
                    pendingTransactions.removeAll(it.block.transactions)
                }
            })
    }

    /**
     * 开始同步区块。
     */
    fun startSync(peer: Peer) {
        synching = true

        if (blockChain.getBestBlock().height == 0L) {
            syncManager.initSyncGetBlocks(peer)
        } else {
            syncManager.initSyncGetHeaders(peer)
        }
    }

    fun stopSync() {
        synching = false
    }

    /**
     * Mining完成后把挖到的区块加入到区块链。
     */
    private fun processMinedBlock(result: MineResult) {
        val block = result.block
        logger.debug("Process mined block: $block")

        if (blockChain.importBlock(block) == BlockChain.ImportResult.BEST_BLOCK) {
            val bestBlock = blockChain.getBestBlock()
            peers.forEach { it.sendNewBlock(bestBlock) }
        }
    }

    /**
     * 开始搜索可连接的Peer。
     *
     * TODO: 运行时更新Peer地址列表并刷新连接。
     */
    fun startPeerDiscovery() {
        val bootnodes = blockChain.config.getBootnodes()

        if (bootnodes.size > 0) {
            bootnodes.forEach {
                val uri = URI(it)
                if (uri.scheme == "mnode") {
                    // Run PeerClient for nodes in bootnodes list. It will make async connection.
                    PeerClient(this).connectAsync(Node(uri.userInfo, uri.host, uri.port))
                }
            }
        } else {
            startMining()
        }
    }

    /**
     * 停止对Peer的搜索。
     */
    fun stopPeerDiscovery() {

    }

    /**
     * 提交交易数据，会进入Pending Transactions并发布给已连接的客户端。
     */
    fun submitTransaction(trx: Transaction) {
        if (trx.isValid) {
            addPendingTransaction(trx)

            peers.map { it.sendTransaction(trx) }
        }
    }

    /**
     * 判断Peer是否已经在连接列表内
     */
    fun peerConnected(peer: Peer): Boolean {
        return peers.any { it.node.nodeId == peer.node.nodeId }
    }

    /**
     * 处理同步过来的区块数据，检索区块是否已经存在，只保存新增区块。
     */
    fun processPeerNewBlock(block: Block, peer: Peer) {
        val importResult = blockChain.importBlock(block)

        if (importResult == BlockChain.ImportResult.BEST_BLOCK) {
            if (miningBlock != null && block.height >= miningBlock!!.height) {
                BlockMiner.skip()
            }

            broadcastBlock(block, peer)
        }
    }

    /**
     * 新建Account（包含公私钥对）。
     */
    fun newAccount(password: String): AccountWithKey? {
        val keyPair = CryptoUtil.generateKeyPair()
        if (keyPair.public != null && keyPair.private != null) {
            val account = AccountWithKey(keyPair.public, keyPair.private)
            val index = blockChain.repository.saveAccount(account, password)
            account.index = index
            return account
        } else {
            return null
        }
    }

    /**
     * 加载Account（包含公私钥对）。
     */
    fun unlockAccount(index: Int, password: String): AccountWithKey? {
        currentAccount = blockChain.repository.getAccount(index, password)
        return currentAccount
    }

    /**
     * Lock Account（包含公私钥对）。
     */
    fun lockAccount(): Boolean {
        currentAccount = null
        return true
    }

    fun stop() {
        stopMining()
        stopSync()
        stopPeerDiscovery()
        blockChain.repository.close()
    }

    private fun broadcastBlock(block: Block, skipPeer: Peer) {
        peers.filterNot { it == skipPeer }.forEach { it.sendNewBlock(block) }
    }

}
