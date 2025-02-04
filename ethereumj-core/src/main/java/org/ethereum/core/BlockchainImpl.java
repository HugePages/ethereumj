/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.core;

import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.config.BlockchainConfig;
import org.ethereum.config.CommonConfig;
import org.ethereum.config.SystemProperties;
import org.ethereum.crypto.HashUtil;
import org.ethereum.datasource.inmem.HashMapDB;
import org.ethereum.db.*;
import org.ethereum.trie.Trie;
import org.ethereum.trie.TrieImpl;
import org.ethereum.listener.EthereumListener;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.manager.AdminInfo;
import org.ethereum.sync.SyncManager;
import org.ethereum.util.*;
import org.ethereum.validator.DependentBlockHeaderRule;
import org.ethereum.validator.ParentBlockHeaderValidator;
import org.ethereum.vm.hook.VMHook;
import org.ethereum.vm.program.invoke.ProgramInvokeFactory;
import org.ethereum.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

import static java.lang.Math.max;
import static java.lang.Runtime.getRuntime;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static java.util.Collections.emptyList;
import static org.ethereum.core.Denomination.SZABO;
import static org.ethereum.core.ImportResult.*;
import static org.ethereum.crypto.HashUtil.sha3;
import static org.ethereum.util.ByteUtil.toHexString;

/**
 * The Ethereum blockchain is in many ways similar to the Bitcoin blockchain,
 * although it does have some differences.
 * <p>
 * The main difference between Ethereum and Bitcoin with regard to the blockchain architecture
 * is that, unlike Bitcoin, Ethereum blocks contain a copy of both the transaction list
 * and the most recent state. Aside from that, two other values, the block number and
 * the difficulty, are also stored in the block.
 * </p>
 * The block validation algorithm in Ethereum is as follows:
 * <ol>
 * <li>Check if the previous block referenced exists and is valid.</li>
 * <li>Check that the timestamp of the block is greater than that of the referenced previous block and less than 15 minutes into the future</li>
 * <li>Check that the block number, difficulty, transaction root, uncle root and gas limit (various low-level Ethereum-specific concepts) are valid.</li>
 * <li>Check that the proof of work on the block is valid.</li>
 * <li>Let S[0] be the STATE_ROOT of the previous block.</li>
 * <li>Let TX be the block's transaction list, with n transactions.
 * For all in in 0...n-1, set S[i+1] = APPLY(S[i],TX[i]).
 * If any applications returns an error, or if the total gas consumed in the block
 * up until this point exceeds the GASLIMIT, return an error.</li>
 * <li>Let S_FINAL be S[n], but adding the block reward paid to the miner.</li>
 * <li>Check if S_FINAL is the same as the STATE_ROOT. If it is, the block is valid; otherwise, it is not valid.</li>
 * </ol>
 * See <a href="https://github.com/ethereum/wiki/wiki/White-Paper#blockchain-and-mining">Ethereum Whitepaper</a>
 *
 * @author Roman Mandeleil
 * @author Nick Savers
 * @since 20.05.2014
 */
@Component
public class BlockchainImpl implements Blockchain, org.ethereum.facade.Blockchain {


    private static final Logger logger = LoggerFactory.getLogger("blockchain");
    private static final Logger stateLogger = LoggerFactory.getLogger("state");

    // to avoid using minGasPrice=0 from Genesis for the wallet
    private static final long INITIAL_MIN_GAS_PRICE = 10 * SZABO.longValue();
    private static final int MAGIC_REWARD_OFFSET = 8;
    public static final byte[] EMPTY_LIST_HASH = sha3(RLP.encodeList(new byte[0]));

    @Autowired @Qualifier("defaultRepository")
    private Repository repository;

    @Autowired
    protected BlockStore blockStore;

    private HeaderStore headerStore = null;

    @Autowired
    private TransactionStore transactionStore;

    private Block bestBlock;

    private BigInteger totalDifficulty = ZERO;

    @Autowired
    private EthereumListener listener;

    @Autowired
    ProgramInvokeFactory programInvokeFactory;

    @Autowired
    private AdminInfo adminInfo;

    @Autowired
    private DependentBlockHeaderRule parentHeaderValidator;

    @Autowired
    private PendingState pendingState;

    @Autowired
    EventDispatchThread eventDispatchThread;

    @Autowired
    CommonConfig commonConfig = CommonConfig.getDefault();

    @Autowired
    SyncManager syncManager;

    @Autowired
    PruneManager pruneManager;

    @Autowired
    StateSource stateDataSource;

    @Autowired
    DbFlushManager dbFlushManager;

    @Autowired
    private VMHook vmHook;


    SystemProperties config = SystemProperties.getDefault();

    private List<Chain> altChains = new ArrayList<>();
    private List<Block> garbage = new ArrayList<>();

    long exitOn = Long.MAX_VALUE;

    public boolean byTest = false;
    private boolean fork = false;

    private byte[] minerCoinbase;
    private byte[] minerExtraData;
    private int UNCLE_LIST_LIMIT;
    private int UNCLE_GENERATION_LIMIT;


    private Stack<State> stateStack = new Stack<>();

    /** Tests only **/
    public BlockchainImpl() {
    }

    @Autowired
    public BlockchainImpl(final SystemProperties config) {
        this.config = config;
        initConst(config);
    }

    //todo: autowire over constructor
    public BlockchainImpl(final BlockStore blockStore, final Repository repository) {
        this.blockStore = blockStore;
        this.repository = repository;
        this.adminInfo = new AdminInfo();
        this.listener = new EthereumListenerAdapter();
        this.parentHeaderValidator = null;
        this.transactionStore = new TransactionStore(new HashMapDB());
        this.eventDispatchThread = EventDispatchThread.getDefault();
        this.programInvokeFactory = new ProgramInvokeFactoryImpl();
        initConst(SystemProperties.getDefault());
    }

    public BlockchainImpl withTransactionStore(TransactionStore transactionStore) {
        this.transactionStore = transactionStore;
        return this;
    }

    public BlockchainImpl withAdminInfo(AdminInfo adminInfo) {
        this.adminInfo = adminInfo;
        return this;
    }

    public BlockchainImpl withEthereumListener(EthereumListener listener) {
        this.listener = listener;
        return this;
    }

    public BlockchainImpl withSyncManager(SyncManager syncManager) {
        this.syncManager = syncManager;
        return this;
    }

    public BlockchainImpl withParentBlockHeaderValidator(ParentBlockHeaderValidator parentHeaderValidator) {
        this.parentHeaderValidator = parentHeaderValidator;
        return this;
    }

    public BlockchainImpl withVmHook(VMHook vmHook) {
        this.vmHook = vmHook;
        return this;
    }

    private void initConst(SystemProperties config) {
        minerCoinbase = config.getMinerCoinbase();
        minerExtraData = config.getMineExtraData();
        UNCLE_LIST_LIMIT = config.getBlockchainConfig().getCommonConstants().getUNCLE_LIST_LIMIT();
        UNCLE_GENERATION_LIMIT = config.getBlockchainConfig().getCommonConstants().getUNCLE_GENERATION_LIMIT();
    }

    @Override
    public byte[] getBestBlockHash() {
        return getBestBlock().getHash();
    }

    @Override
    public long getSize() {
        return bestBlock.getNumber() + 1;
    }

    @Override
    public Block getBlockByNumber(long blockNr) {
        return blockStore.getChainBlockByNumber(blockNr);
    }

    @Override
    public TransactionInfo getTransactionInfo(byte[] hash) {

        List<TransactionInfo> infos = transactionStore.get(hash);

        if (infos == null || infos.isEmpty())
            return null;

        TransactionInfo txInfo = null;
        if (infos.size() == 1) {
            txInfo = infos.get(0);
        } else {
            // pick up the receipt from the block on the main chain
            for (TransactionInfo info : infos) {
                Block block = blockStore.getBlockByHash(info.blockHash);
                Block mainBlock = blockStore.getChainBlockByNumber(block.getNumber());
                if (FastByteComparisons.equal(info.blockHash, mainBlock.getHash())) {
                    txInfo = info;
                    break;
                }
            }
        }
        if (txInfo == null) {
            logger.warn("Can't find block from main chain for transaction " + toHexString(hash));
            return null;
        }

        Transaction tx = this.getBlockByHash(txInfo.getBlockHash()).getTransactionsList().get(txInfo.getIndex());
        txInfo.setTransaction(tx);

        return txInfo;
    }

    @Override
    public Block getBlockByHash(byte[] hash) {
        return blockStore.getBlockByHash(hash);
    }

    @Override
    public synchronized List<byte[]> getListOfHashesStartFrom(byte[] hash, int qty) {
        return blockStore.getListHashesEndWith(hash, qty);
    }

    @Override
    public synchronized List<byte[]> getListOfHashesStartFromBlock(long blockNumber, int qty) {
        long bestNumber = bestBlock.getNumber();

        if (blockNumber > bestNumber) {
            return emptyList();
        }

        if (blockNumber + qty - 1 > bestNumber) {
            qty = (int) (bestNumber - blockNumber + 1);
        }

        long endNumber = blockNumber + qty - 1;

        Block block = getBlockByNumber(endNumber);

        List<byte[]> hashes = blockStore.getListHashesEndWith(block.getHash(), qty);

        // asc order of hashes is required in the response
        Collections.reverse(hashes);

        return hashes;
    }

    public static byte[] calcTxTrie(List<Transaction> transactions) {

        Trie txsState = new TrieImpl();

        if (transactions == null || transactions.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < transactions.size(); i++) {
            txsState.put(RLP.encodeInt(i), transactions.get(i).getEncoded());
        }
        return txsState.getRootHash();
    }

    public Repository getRepository() {
        return repository;
    }

    public Repository getRepositorySnapshot() {
        return repository.getSnapshotTo(blockStore.getBestBlock().getStateRoot());
    }

    @Override
    public BlockStore getBlockStore() {
        return blockStore;
    }

    public ProgramInvokeFactory getProgramInvokeFactory() {
        return programInvokeFactory;
    }

    private State pushState(byte[] bestBlockHash) {
        State push = stateStack.push(new State());
        this.bestBlock = blockStore.getBlockByHash(bestBlockHash);
        totalDifficulty = blockStore.getTotalDifficultyForHash(bestBlockHash);
        this.repository = this.repository.getSnapshotTo(this.bestBlock.getStateRoot());
        return push;
    }

    private void popState() {
        State state = stateStack.pop();
        this.repository = repository.getSnapshotTo(state.root);
        this.bestBlock = state.savedBest;
        this.totalDifficulty = state.savedTD;
    }

    public void dropState() {
        stateStack.pop();
    }

    private synchronized BlockSummary tryConnectAndFork(final Block block) {
        State savedState = pushState(block.getParentHash());
        this.fork = true;

        final BlockSummary summary;
        Repository repo;
        try {

            // FIXME: adding block with no option for flush
            Block parentBlock = getBlockByHash(block.getParentHash());
            repo = repository.getSnapshotTo(parentBlock.getStateRoot());
            summary = add(repo, block);
            if (summary == null) {
                return null;
            }
        } catch (Throwable th) {
            logger.error("Unexpected error: ", th);
            return null;
        } finally {
            this.fork = false;
        }

        if (summary.betterThan(savedState.savedTD)) {

            logger.info("Rebranching: {} ~> {}", savedState.savedBest.getShortHash(), block.getShortHash());

            // main branch become this branch
            // cause we proved that total difficulty
            // is greateer
            blockStore.reBranch(block);

            // The main repository rebranch
            this.repository = repo;
//            this.repository.syncToRoot(block.getStateRoot());

            dropState();
        } else {
            // Stay on previous branch
            popState();
        }

        return summary;
    }


    public synchronized ImportResult tryToConnect(final Block block) {

        if (logger.isDebugEnabled())
            logger.debug("Try connect block hash: {}, number: {}",
                    toHexString(block.getHash()).substring(0, 6),
                    block.getNumber());

        if (blockStore.getMaxNumber() >= block.getNumber() &&
                blockStore.isBlockExist(block.getHash())) {

            if (logger.isDebugEnabled())
                logger.debug("Block already exist hash: {}, number: {}",
                        toHexString(block.getHash()).substring(0, 6),
                        block.getNumber());

            // retry of well known block
            return EXIST;
        }

        final ImportResult ret;

        // The simple case got the block
        // to connect to the main chain
        final BlockSummary summary;
        if (bestBlock.isParentOf(block)) {
            recordBlock(block);
//            Repository repoSnap = repository.getSnapshotTo(bestBlock.getStateRoot());
            summary = add(repository, block);

            ret = summary == null ? INVALID_BLOCK : IMPORTED_BEST;
        } else {

            if (blockStore.isBlockExist(block.getParentHash())) {
                BigInteger oldTotalDiff = getTotalDifficulty();

                recordBlock(block);
                summary = tryConnectAndFork(block);

                ret = summary == null ? INVALID_BLOCK :
                        (summary.betterThan(oldTotalDiff) ? IMPORTED_BEST : IMPORTED_NOT_BEST);
            } else {
                summary = null;
                ret = NO_PARENT;
            }

        }

        if (ret.isSuccessful()) {
            listener.onBlock(summary, ret == IMPORTED_BEST);
            listener.trace(String.format("Block chain size: [ %d ]", this.getSize()));

            if (ret == IMPORTED_BEST) {
                eventDispatchThread.invokeLater(() -> pendingState.processBest(block, summary.getReceipts()));
            }
        }

        return ret;
    }

    public synchronized Block createNewBlock(Block parent, List<Transaction> txs, List<BlockHeader> uncles) {
        long time = System.currentTimeMillis() / 1000;
        // adjust time to parent block this may happen due to system clocks difference
        if (parent.getTimestamp() >= time) time = parent.getTimestamp() + 1;

        return createNewBlock(parent, txs, uncles, time);
    }

    public synchronized Block createNewBlock(Block parent, List<Transaction> txs, List<BlockHeader> uncles, long time) {
        final long blockNumber = parent.getNumber() + 1;

        final byte[] extraData = config.getBlockchainConfig().getConfigForBlock(blockNumber).getExtraData(minerExtraData, blockNumber);

        Block block = new Block(parent.getHash(),
                EMPTY_LIST_HASH, // uncleHash
                minerCoinbase,
                new byte[0], // log bloom - from tx receipts
                new byte[0], // difficulty computed right after block creation
                blockNumber,
                parent.getGasLimit(), // (add to config ?)
                0,  // gas used - computed after running all transactions
                time,  // block time
                extraData,  // extra data
                new byte[0],  // mixHash (to mine)
                new byte[0],  // nonce   (to mine)
                new byte[0],  // receiptsRoot - computed after running all transactions
                calcTxTrie(txs),    // TransactionsRoot - computed after running all transactions
                new byte[] {0}, // stateRoot - computed after running all transactions
                txs,
                null);  // uncle list

        for (BlockHeader uncle : uncles) {
            block.addUncle(uncle);
        }

        block.getHeader().setDifficulty(ByteUtil.bigIntegerToBytes(block.getHeader().
                calcDifficulty(config.getBlockchainConfig(), parent.getHeader())));

        Repository track = repository.getSnapshotTo(parent.getStateRoot());
        BlockSummary summary = applyBlock(track, block);
        List<TransactionReceipt> receipts = summary.getReceipts();
        block.setStateRoot(track.getRoot());

        Bloom logBloom = new Bloom();
        for (TransactionReceipt receipt : receipts) {
            logBloom.or(receipt.getBloomFilter());
        }
        block.getHeader().setLogsBloom(logBloom.getData());
        block.getHeader().setGasUsed(receipts.size() > 0 ? receipts.get(receipts.size() - 1).getCumulativeGasLong() : 0);
        block.getHeader().setReceiptsRoot(calcReceiptsTrie(receipts));

        return block;
    }

    @Override
    public BlockSummary add(Block block) {
        throw new RuntimeException("Not supported");
    }

    //    @Override
    public synchronized BlockSummary add(Repository repo, final Block block) {
        BlockSummary summary = addImpl(repo, block);

        if (summary == null) {
            stateLogger.warn("Trying to reimport the block for debug...");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
            BlockSummary summary1 = addImpl(repo.getSnapshotTo(getBestBlock().getStateRoot()), block);
            stateLogger.warn("Second import trial " + (summary1 == null ? "FAILED" : "OK"));
            if (summary1 != null) {
                if (config.exitOnBlockConflict() && !byTest) {
                    stateLogger.error("Inconsistent behavior, exiting...");
                    System.exit(-1);
                } else {
                    return summary1;
                }
            }
        }
        return summary;
    }

    public synchronized BlockSummary addImpl(Repository repo, final Block block) {

        if (exitOn < block.getNumber()) {
            String msg = String.format("Exiting after block.number: %d", bestBlock.getNumber());
            logger.info(msg);
            System.out.println(msg);
            dbFlushManager.flushSync();
            System.exit(-1);
        }


        if (!isValid(repo, block)) {
            logger.warn("Invalid block with number: {}", block.getNumber());
            return null;
        }

//        Repository track = repo.startTracking();
        byte[] origRoot = repo.getRoot();

        if (block == null)
            return null;

        // keep chain continuity
//        if (!Arrays.equals(bestBlock.getHash(),
//                block.getParentHash())) return null;

        if (block.getNumber() >= config.traceStartBlock() && config.traceStartBlock() != -1) {
            AdvancedDeviceUtils.adjustDetailedTracing(config, block.getNumber());
        }

        BlockSummary summary = processBlock(repo, block);
        final List<TransactionReceipt> receipts = summary.getReceipts();

        // Sanity checks

        if (!FastByteComparisons.equal(block.getReceiptsRoot(), calcReceiptsTrie(receipts))) {
            logger.warn("Block's given Receipt Hash doesn't match: {} != {}", toHexString(block.getReceiptsRoot()), toHexString(calcReceiptsTrie(receipts)));
            logger.warn("Calculated receipts: " + receipts);
            repo.rollback();
            summary = null;
        }

        if (!FastByteComparisons.equal(block.getLogBloom(), calcLogBloom(receipts))) {
            logger.warn("Block's given logBloom Hash doesn't match: {} != {}", toHexString(block.getLogBloom()), toHexString(calcLogBloom(receipts)));
            repo.rollback();
            summary = null;
        }

        if (!FastByteComparisons.equal(block.getStateRoot(), repo.getRoot())) {

            stateLogger.warn("BLOCK: State conflict or received invalid block. block: {} worldstate {} mismatch", block.getNumber(), toHexString(repo.getRoot()));
            stateLogger.warn("Conflict block dump: {}", toHexString(block.getEncoded()));

//            track.rollback();
//            repository.rollback();
            repository = repository.getSnapshotTo(origRoot);

            // block is bad so 'rollback' the state root to the original state
//            ((RepositoryImpl) repository).setRoot(origRoot);

//            track.rollback();
            // block is bad so 'rollback' the state root to the original state
//            ((RepositoryImpl) repository).setRoot(origRoot);

            if (config.exitOnBlockConflict() && !byTest) {
                adminInfo.lostConsensus();
                System.out.println("CONFLICT: BLOCK #" + block.getNumber() + ", dump: " + toHexString(block.getEncoded()));
                System.exit(1);
            } else {
                summary = null;
            }
        }

        if (summary != null) {
            repo.commit();
            updateTotalDifficulty(block);
            summary.setTotalDifficulty(getTotalDifficulty());

            if (!byTest) {
                dbFlushManager.commit(() -> {
                    storeBlock(block, receipts);
                    repository.commit();
                });
            } else {
                storeBlock(block, receipts);
            }
        }

        return summary;
    }

    @Override
    public void flush() {
//        repository.flush();
//        stateDataSource.flush();
//        blockStore.flush();
//        transactionStore.flush();
//
//        repository = repository.getSnapshotTo(repository.getRoot());
//
//        if (isMemoryBoundFlush()) {
//            System.gc();
//        }
    }

    private boolean needFlushByMemory(double maxMemoryPercents) {
        return getRuntime().freeMemory() < (getRuntime().totalMemory() * (1 - maxMemoryPercents));
    }

    public static byte[] calcReceiptsTrie(List<TransactionReceipt> receipts) {
        Trie receiptsTrie = new TrieImpl();

        if (receipts == null || receipts.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < receipts.size(); i++) {
            receiptsTrie.put(RLP.encodeInt(i), receipts.get(i).getReceiptTrieEncoded());
        }
        return receiptsTrie.getRootHash();
    }

    private byte[] calcLogBloom(List<TransactionReceipt> receipts) {

        Bloom retBloomFilter = new Bloom();

        if (receipts == null || receipts.isEmpty())
            return retBloomFilter.getData();

        for (TransactionReceipt receipt : receipts) {
            retBloomFilter.or(receipt.getBloomFilter());
        }

        return retBloomFilter.getData();
    }

    public Block getParent(BlockHeader header) {

        return blockStore.getBlockByHash(header.getParentHash());
    }


    public boolean isValid(BlockHeader header) {
        if (parentHeaderValidator == null) return true;

        Block parentBlock = getParent(header);

        if (!parentHeaderValidator.validate(header, parentBlock.getHeader())) {

            if (logger.isErrorEnabled())
                parentHeaderValidator.logErrors(logger);

            return false;
        }

        return true;
    }

    /**
     * This mechanism enforces a homeostasis in terms of the time between blocks;
     * a smaller period between the last two blocks results in an increase in the
     * difficulty level and thus additional computation required, lengthening the
     * likely next period. Conversely, if the period is too large, the difficulty,
     * and expected time to the next block, is reduced.
     */
    private boolean isValid(Repository repo, Block block) {

        boolean isValid = true;

        if (!block.isGenesis()) {
            isValid = isValid(block.getHeader());

            // Sanity checks
            String trieHash = toHexString(block.getTxTrieRoot());
            String trieListHash = toHexString(calcTxTrie(block.getTransactionsList()));


            if (!trieHash.equals(trieListHash)) {
                logger.warn("Block's given Trie Hash doesn't match: {} != {}", trieHash, trieListHash);
                return false;
            }

//            if (!validateUncles(block)) return false;

            List<Transaction> txs = block.getTransactionsList();
            if (!txs.isEmpty()) {
//                Repository parentRepo = repository;
//                if (!Arrays.equals(bestBlock.getHash(), block.getParentHash())) {
//                    parentRepo = repository.getSnapshotTo(getBlockByHash(block.getParentHash()).getStateRoot());
//                }

                Map<ByteArrayWrapper, BigInteger> curNonce = new HashMap<>();

                for (Transaction tx : txs) {
                    byte[] txSender = tx.getSender();
                    if (txSender == null) {
                        logger.warn("Invalid transaction: sender in tx with rlp={} is null." +
                                "Not valid until EIP-86", ByteUtil.toHexString(tx.getEncoded()));
                        return false;
                    }
                    ByteArrayWrapper key = new ByteArrayWrapper(txSender);
                    BigInteger expectedNonce = curNonce.get(key);
                    if (expectedNonce == null) {
                        expectedNonce = repo.getNonce(txSender);
                    }
                    curNonce.put(key, expectedNonce.add(ONE));
                    BigInteger txNonce = new BigInteger(1, tx.getNonce());
                    if (!expectedNonce.equals(txNonce)) {
                        logger.warn("Invalid transaction: Tx nonce {} != expected nonce {} (parent nonce: {}): {}",
                                txNonce, expectedNonce, repo.getNonce(txSender), tx);
                        return false;
                    }
                }
            }
        }

        return isValid;
    }

    /**
     * 校验叔块信息是否有效
     * @param block
     * @return
     */
    public boolean validateUncles(Block block) {
        String unclesHash = toHexString(block.getHeader().getUnclesHash());
        String unclesListHash = toHexString(HashUtil.sha3(block.getHeader().getUnclesEncoded(block.getUncleList())));

        if (!unclesHash.equals(unclesListHash)) {
            logger.warn("Block's given Uncle Hash doesn't match: {} != {}", unclesHash, unclesListHash);
            return false;
        }


        if (block.getUncleList().size() > UNCLE_LIST_LIMIT) {
            logger.warn("Uncle list to big: block.getUncleList().size() > UNCLE_LIST_LIMIT");
            return false;
        }

//获取祖先区块信息
        Set<ByteArrayWrapper> ancestors = getAncestors(blockStore, block, UNCLE_GENERATION_LIMIT + 1, false);
        Set<ByteArrayWrapper> usedUncles = getUsedUncles(blockStore, block, false);

        for (BlockHeader uncle : block.getUncleList()) {

            // - They are valid headers (not necessarily valid blocks)
            //区块信息是否合法
            if (!isValid(uncle)) return false;

            //if uncle's parent's number is not less than currentBlock - UNCLE_GEN_LIMIT, mark invalid
            //判断叔块的区块编号是否已经大于7
            boolean isValid = !(getParent(uncle).getNumber() < (block.getNumber() - UNCLE_GENERATION_LIMIT));
            if (!isValid) {
                logger.warn("Uncle too old: generationGap must be under UNCLE_GENERATION_LIMIT");
                return false;
            }

            ByteArrayWrapper uncleHash = new ByteArrayWrapper(uncle.getHash());
            //判断叔块信息不是直系七代的父区块
            if (ancestors.contains(uncleHash)) {
                logger.warn("Uncle is direct ancestor: " + toHexString(uncle.getHash()));
                return false;
            }
//判断叔块是否已经被使用
            if (usedUncles.contains(uncleHash)) {
                logger.warn("Uncle is not unique: " + toHexString(uncle.getHash()));
                return false;
            }

            Block uncleParent = blockStore.getBlockByHash(uncle.getParentHash());
            if (!ancestors.contains(new ByteArrayWrapper(uncleParent.getHash()))) {
                logger.warn("Uncle has no common parent: " + toHexString(uncle.getHash()));
                return false;
            }
        }

        return true;
    }

    //循环获取直系七代的父块信息。
    public static Set<ByteArrayWrapper> getAncestors(BlockStore blockStore, Block testedBlock, int limitNum, boolean isParentBlock) {
        Set<ByteArrayWrapper> ret = new HashSet<>();
        limitNum = (int) max(0, testedBlock.getNumber() - limitNum);
        Block it = testedBlock;
        if (!isParentBlock) {
            it = blockStore.getBlockByHash(it.getParentHash());
        }
        while(it != null && it.getNumber() >= limitNum) { //循环获取直系七代的父块信息
            ret.add(new ByteArrayWrapper(it.getHash()));
            it = blockStore.getBlockByHash(it.getParentHash());
        }
        return ret;
    }

    //获取直系7代的区块包含的叔块信息。
    public Set<ByteArrayWrapper> getUsedUncles(BlockStore blockStore, Block testedBlock, boolean isParentBlock) {
        Set<ByteArrayWrapper> ret = new HashSet<>();
        long limitNum = max(0, testedBlock.getNumber() - UNCLE_GENERATION_LIMIT);
        Block it = testedBlock;
        if (!isParentBlock) {
            it = blockStore.getBlockByHash(it.getParentHash());
        }
        while(it.getNumber() > limitNum) {
            for (BlockHeader uncle : it.getUncleList()) {
                ret.add(new ByteArrayWrapper(uncle.getHash()));
            }
            it = blockStore.getBlockByHash(it.getParentHash());
        }
        return ret;
    }

    private BlockSummary processBlock(Repository track, Block block) {

        if (!block.isGenesis() && !config.blockChainOnly()) {
            return applyBlock(track, block);
        }
        else {
            return new BlockSummary(block, new HashMap<byte[], BigInteger>(), new ArrayList<TransactionReceipt>(), new ArrayList<TransactionExecutionSummary>());
        }
    }

    private BlockSummary applyBlock(Repository track, Block block) {

        logger.debug("applyBlock: block: [{}] tx.list: [{}]", block.getNumber(), block.getTransactionsList().size());

        BlockchainConfig blockchainConfig = config.getBlockchainConfig().getConfigForBlock(block.getNumber());
        blockchainConfig.hardForkTransfers(block, track);

        long saveTime = System.nanoTime();
        int i = 1;
        long totalGasUsed = 0;
        List<TransactionReceipt> receipts = new ArrayList<>();
        List<TransactionExecutionSummary> summaries = new ArrayList<>();

        for (Transaction tx : block.getTransactionsList()) {
            stateLogger.debug("apply block: [{}] tx: [{}] ", block.getNumber(), i);

            Repository txTrack = track.startTracking();
            TransactionExecutor executor = new TransactionExecutor(
                    tx, block.getCoinbase(),
                    txTrack, blockStore, programInvokeFactory, block, listener, totalGasUsed, vmHook)
                    .withCommonConfig(commonConfig);

            executor.init();
            executor.execute();
            executor.go();
            TransactionExecutionSummary summary = executor.finalization();

            totalGasUsed += executor.getGasUsed();

            txTrack.commit();
            final TransactionReceipt receipt = executor.getReceipt();

            if (blockchainConfig.eip658()) {
                receipt.setTxStatus(receipt.isSuccessful());
            } else {
                receipt.setPostTxState(track.getRoot());
            }

            if (stateLogger.isInfoEnabled())
                stateLogger.info("block: [{}] executed tx: [{}] \n  state: [{}]", block.getNumber(), i,
                        toHexString(track.getRoot()));

            stateLogger.info("[{}] ", receipt.toString());

            if (stateLogger.isInfoEnabled())
                stateLogger.info("tx[{}].receipt: [{}] ", i, toHexString(receipt.getEncoded()));

            // TODO
//            if (block.getNumber() >= config.traceStartBlock())
//                repository.dumpState(block, totalGasUsed, i++, tx.getHash());

            receipts.add(receipt);
            if (summary != null) {
                summaries.add(summary);
            }
        }

        Map<byte[], BigInteger> rewards = addReward(track, block, summaries);

        if (stateLogger.isInfoEnabled())
            stateLogger.info("applied reward for block: [{}]  \n  state: [{}]",
                    block.getNumber(),
                    toHexString(track.getRoot()));


        // TODO
//        if (block.getNumber() >= config.traceStartBlock())
//            repository.dumpState(block, totalGasUsed, 0, null);

        long totalTime = System.nanoTime() - saveTime;
        adminInfo.addBlockExecTime(totalTime);
        logger.debug("block: num: [{}] hash: [{}], executed after: [{}]nano", block.getNumber(), block.getShortHash(), totalTime);

        return new BlockSummary(block, rewards, receipts, summaries);
    }

    /**
     * Add reward to block- and every uncle coinbase
     * assuming the entire block is valid.
     *
     * @param block object containing the header and uncles
     */
    private Map<byte[], BigInteger> addReward(Repository track, Block block, List<TransactionExecutionSummary> summaries) {

        Map<byte[], BigInteger> rewards = new HashMap<>();

        BigInteger blockReward = config.getBlockchainConfig().getConfigForBlock(block.getNumber()).getConstants().getBLOCK_REWARD();
        BigInteger inclusionReward = blockReward.divide(BigInteger.valueOf(32));

        // Add extra rewards based on number of uncles
        if (block.getUncleList().size() > 0) {
            for (BlockHeader uncle : block.getUncleList()) {
                BigInteger uncleReward = blockReward
                        .multiply(BigInteger.valueOf(MAGIC_REWARD_OFFSET + uncle.getNumber() - block.getNumber()))
                        .divide(BigInteger.valueOf(MAGIC_REWARD_OFFSET));

                track.addBalance(uncle.getCoinbase(),uncleReward);
                BigInteger existingUncleReward = rewards.get(uncle.getCoinbase());
                if (existingUncleReward == null) {
                    rewards.put(uncle.getCoinbase(), uncleReward);
                } else {
                    rewards.put(uncle.getCoinbase(), existingUncleReward.add(uncleReward));
                }
            }
        }

        BigInteger minerReward = blockReward.add(inclusionReward.multiply(BigInteger.valueOf(block.getUncleList().size())));

        BigInteger totalFees = BigInteger.ZERO;
        for (TransactionExecutionSummary summary : summaries) {
            totalFees = totalFees.add(summary.getFee());
        }

        rewards.put(block.getCoinbase(), minerReward.add(totalFees));
        track.addBalance(block.getCoinbase(), minerReward); // fees are already given to the miner during tx execution
        return rewards;
    }

    @Override
    public synchronized void storeBlock(Block block, List<TransactionReceipt> receipts) {

        if (fork)
            blockStore.saveBlock(block, totalDifficulty, false);
        else
            blockStore.saveBlock(block, totalDifficulty, true);

        for (int i = 0; i < receipts.size(); i++) {
            transactionStore.put(new TransactionInfo(receipts.get(i), block.getHash(), i));
        }

        if (pruneManager != null) {
            pruneManager.blockCommitted(block.getHeader());
        }

        logger.debug("Block saved: number: {}, hash: {}, TD: {}",
                block.getNumber(), block.getShortHash(), totalDifficulty);

        setBestBlock(block);

        if (logger.isDebugEnabled())
            logger.debug("block added to the blockChain: index: [{}]", block.getNumber());
        if (block.getNumber() % 100 == 0)
            logger.info("*** Last block added [ #{} ]", block.getNumber());

    }


    public boolean hasParentOnTheChain(Block block) {
        return getParent(block.getHeader()) != null;
    }

    @Override
    public List<Chain> getAltChains() {
        return altChains;
    }

    @Override
    public List<Block> getGarbage() {
        return garbage;
    }

    public TransactionStore getTransactionStore() {
        return transactionStore;
    }

    @Override
    public void setBestBlock(Block block) {
        bestBlock = block;
        repository = repository.getSnapshotTo(block.getStateRoot());
    }

    @Override
    public synchronized Block getBestBlock() {
        // the method is synchronized since the bestBlock might be
        // temporarily switched to the fork while importing non-best block
        return bestBlock;
    }

    @Override
    public synchronized void close() {
        blockStore.close();
    }

    @Override
    public BigInteger getTotalDifficulty() {
        return totalDifficulty;
    }

    @Override
    public synchronized void updateTotalDifficulty(Block block) {
        totalDifficulty = totalDifficulty.add(block.getDifficultyBI());
        logger.debug("TD: updated to {}", totalDifficulty);
    }

    @Override
    public void setTotalDifficulty(BigInteger totalDifficulty) {
        this.totalDifficulty = totalDifficulty;
    }

    private void recordBlock(Block block) {

        if (!config.recordBlocks()) return;

        String dumpDir = config.databaseDir() + "/" + config.dumpDir();

        File dumpFile = new File(dumpDir + "/blocks-rec.dmp");
        FileWriter fw = null;
        BufferedWriter bw = null;

        try {

            dumpFile.getParentFile().mkdirs();
            if (!dumpFile.exists()) dumpFile.createNewFile();

            fw = new FileWriter(dumpFile.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);

            if (bestBlock.isGenesis()) {
                bw.write(Hex.toHexString(bestBlock.getEncoded()));
                bw.write("\n");
            }

            bw.write(Hex.toHexString(block.getEncoded()));
            bw.write("\n");

        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (bw != null) bw.close();
                if (fw != null) fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void updateBlockTotDifficulties(long startFrom) {
        // no synchronization here not to lock instance for long period
        while(true) {
            synchronized (this) {
                ((IndexedBlockStore) blockStore).updateTotDifficulties(startFrom);

                if (startFrom == bestBlock.getNumber()) {
                    totalDifficulty = blockStore.getTotalDifficultyForHash(bestBlock.getHash());
                }

                if (startFrom == blockStore.getMaxNumber()) {
                    Block bestStoredBlock = bestBlock;
                    BigInteger maxTD = totalDifficulty;

                    // traverse blocks toward max known number to get the best block
                    for (long num = bestBlock.getNumber() + 1; num <= blockStore.getMaxNumber(); num++) {
                        List<Block> blocks = ((IndexedBlockStore) blockStore).getBlocksByNumber(num);
                        for (Block block : blocks) {
                            BigInteger td = blockStore.getTotalDifficultyForHash(block.getHash());
                            if (maxTD.compareTo(td) < 0) {
                                maxTD = td;
                                bestStoredBlock = block;
                            }
                        }
                    }

                    if (totalDifficulty.compareTo(maxTD) < 0)  {
                        blockStore.reBranch(bestStoredBlock);
                        bestBlock = bestStoredBlock;
                        totalDifficulty = maxTD;
                        repository = repository.getSnapshotTo(bestBlock.getStateRoot());

                        logger.info("totDifficulties update: re-branch to block {}, totalDifficulty {}",
                                bestBlock.getHeader().getShortDescr(), totalDifficulty);
                    }

                    break;
                }
                startFrom++;
            }
        }
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public void setProgramInvokeFactory(ProgramInvokeFactory factory) {
        this.programInvokeFactory = factory;
    }

    public void setExitOn(long exitOn) {
        this.exitOn = exitOn;
    }

    public void setMinerCoinbase(byte[] minerCoinbase) {
        this.minerCoinbase = minerCoinbase;
    }

    @Override
    public byte[] getMinerCoinbase() {
        return minerCoinbase;
    }

    public void setMinerExtraData(byte[] minerExtraData) {
        this.minerExtraData = minerExtraData;
    }

    public boolean isBlockExist(byte[] hash) {
        return blockStore.isBlockExist(hash);
    }

    public void setParentHeaderValidator(DependentBlockHeaderRule parentHeaderValidator) {
        this.parentHeaderValidator = parentHeaderValidator;
    }

    public void setPendingState(PendingState pendingState) {
        this.pendingState = pendingState;
    }

    public PendingState getPendingState() {
        return pendingState;
    }

    @Override
    public List<BlockHeader> getListOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {
        List<BlockHeader> headers = new ArrayList<>();
        Iterator<BlockHeader> iterator = getIteratorOfHeadersStartFrom(identifier, skip, limit, reverse);
        while (iterator.hasNext()) {
            headers.add(iterator.next());
        }

        return headers;
    }

    @Override
    public Iterator<BlockHeader> getIteratorOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {

        // Identifying block header we'll move from
        BlockHeader startHeader;
        if (identifier.getHash() != null) {
            startHeader = findHeaderByHash(identifier.getHash());
        } else {
            startHeader = findHeaderByNumber(identifier.getNumber());
        }

        // If nothing found or provided hash is not on main chain, return empty array
        if (startHeader == null) {
            return EmptyBlockHeadersIterator.INSTANCE;
        }

        if (identifier.getHash() != null) {
            BlockHeader mainChainHeader = findHeaderByNumber(startHeader.getNumber());
            if (!startHeader.equals(mainChainHeader)) return EmptyBlockHeadersIterator.INSTANCE;
        }

        return new BlockHeadersIterator(startHeader, skip, limit, reverse);
    }

    /**
     * Searches block in blockStore, if it's not found there
     * and headerStore is defined, searches blockHeader in it.
     * @param number block number
     * @return  Block header
     */
    private BlockHeader findHeaderByNumber(long number) {
        Block block = blockStore.getChainBlockByNumber(number);
        if (block == null) {
            if (headerStore != null) {
                return headerStore.getHeaderByNumber(number);
            } else {
                return null;
            }
        } else {
            return block.getHeader();
        }
    }

    /**
     * Searches block in blockStore, if it's not found there
     * and headerStore is defined, searches blockHeader in it.
     * @param hash block hash
     * @return Block header
     */
    private BlockHeader findHeaderByHash(byte[] hash) {
        Block block = blockStore.getBlockByHash(hash);
        if (block == null) {
            if (headerStore != null) {
                return headerStore.getHeaderByHash(hash);
            } else {
                return null;
            }
        } else {
            return block.getHeader();
        }
    }

    static class EmptyBlockHeadersIterator implements Iterator<BlockHeader> {
        final static EmptyBlockHeadersIterator INSTANCE = new EmptyBlockHeadersIterator();

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public BlockHeader next() {
            throw new NoSuchElementException("Nothing left");
        }
    }

    class BlockHeadersIterator implements Iterator<BlockHeader> {
        private final BlockHeader startHeader;
        private final int skip;
        private final int limit;
        private final boolean reverse;
        private Integer position = 0;
        private Pair<Integer, BlockHeader> cachedNext = null;

        BlockHeadersIterator(BlockHeader startHeader, int skip, int limit, boolean reverse) {
            this.startHeader = startHeader;
            this.skip = skip;
            this.limit = limit;
            this.reverse = reverse;
        }

        @Override
        public boolean hasNext() {
            if (startHeader == null || position >= limit) {
                return false;
            }

            if (position == 0) {
                // First
                cachedNext = Pair.of(0, startHeader);
                return true;
            } else if (cachedNext.getLeft().equals(position)) {
                // Already cached
                return true;
            } else {
                // Main logic
                BlockHeader prevHeader = cachedNext.getRight();
                long nextBlockNumber;
                if (reverse) {
                    nextBlockNumber = prevHeader.getNumber() - 1 - skip;
                } else {
                    nextBlockNumber = prevHeader.getNumber() + 1 + skip;
                }

                BlockHeader nextHeader = null;
                if (nextBlockNumber >= 0 && nextBlockNumber <= blockStore.getBestBlock().getNumber()) {
                    nextHeader = findHeaderByNumber(nextBlockNumber);
                }

                if (nextHeader == null) {
                    return false;
                } else {
                    cachedNext = Pair.of(position, nextHeader);
                    return true;
                }
            }
        }

        @Override
        public BlockHeader next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Nothing left");
            }

            if (cachedNext == null || !cachedNext.getLeft().equals(position)) {
                throw new ConcurrentModificationException("Concurrent modification");
            }
            ++position;

            return cachedNext.getRight();
        }
    }

    @Override
    public List<byte[]> getListOfBodiesByHashes(List<byte[]> hashes) {
        List<byte[]> bodies = new ArrayList<>(hashes.size());

        for (byte[] hash : hashes) {
            Block block = blockStore.getBlockByHash(hash);
            if (block == null) break;
            bodies.add(block.getEncodedBody());
        }

        return bodies;
    }

    @Override
    public Iterator<byte[]> getIteratorOfBodiesByHashes(List<byte[]> hashes) {
        return new BlockBodiesIterator(hashes);
    }

    class BlockBodiesIterator implements Iterator<byte[]> {
        private final List<byte[]> hashes;
        private Integer position = 0;


        BlockBodiesIterator(List<byte[]> hashes) {
            this.hashes = new ArrayList<>(hashes);
        }

        @Override
        public boolean hasNext() {
            return position < hashes.size() && blockStore.getBlockByHash(hashes.get(position)) != null;
        }

        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Nothing left");
            }

            Block block = blockStore.getBlockByHash(hashes.get(position));
            if (block == null) {
                throw new NoSuchElementException("Nothing left");
            }
            ++position;

            return block.getEncodedBody();
        }
    }

    private class State {
//        Repository savedRepo = repository;
        byte[] root = repository.getRoot();
        Block savedBest = bestBlock;
        BigInteger savedTD = totalDifficulty;
    }

    public void setPruneManager(PruneManager pruneManager) {
        this.pruneManager = pruneManager;
    }

    public void setHeaderStore(HeaderStore headerStore) {
        this.headerStore = headerStore;
    }
}
