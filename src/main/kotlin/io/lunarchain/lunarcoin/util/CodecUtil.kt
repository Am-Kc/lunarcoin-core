package io.lunarchain.lunarcoin.util

import io.lunarchain.lunarcoin.core.*
import io.lunarchain.lunarcoin.storage.BlockInfo
import lunar.vm.DataWord
import org.joda.time.DateTime
import org.spongycastle.asn1.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec


object CodecUtil {


    private fun encodeMapEntry(entry: Map.Entry<DataWord, DataWord?>): DERSequence {

        val v = ASN1EncodableVector()
        //encode key
        v.add(DERBitString(entry.key.getData()))
        //encode value
        v.add(DERBitString(entry.value?.getData()))

        return DERSequence(v)

    }

    private fun decodeMapEntry(bytes: ByteArray): Pair<ByteArray, ByteArray?>? {
        val v = ASN1InputStream(bytes).readObject()

        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)
            return decodeMapEntryFromSeq(seq)
        }

        return null
    }

    private fun decodeMapEntryFromSeq(seq: ASN1Sequence): Pair<ByteArray, ByteArray?>? {
        val key = DERBitString.getInstance(seq.getObjectAt(0))?.bytes
        val value = DERBitString.getInstance(seq.getObjectAt(1))?.bytes
        if(key == null || value == null) return null
        return Pair(key, value)
    }

    /**
     * 序列化账户存储(Account Storage)。(使用ASN.1规范)
     */
    fun encodeAccountStorage(accountStorage: AccountStorage): ByteArray {
        val v = ASN1EncodableVector()

        //encoding address first

        v.add(DERBitString(accountStorage.address))

        //encoding hashmap second

        val t = ASN1EncodableVector()

        accountStorage.storage.forEach {t.add(encodeMapEntry(it)) }

        v.add(DERSequence(t))


        return DERSequence(v).encoded
    }

    /**
     * 反序列化账户存储(Account Storage)。(使用ASN.1规范)
     */

    fun decodeAccountStorage(bytes: ByteArray): AccountStorage? {
        val v = ASN1InputStream(bytes).readObject()

        if(v != null) {

            val seq = ASN1Sequence.getInstance(v)

            val address = DERBitString.getInstance(seq.getObjectAt(0)).bytes

            val storage =  ASN1Sequence.getInstance(seq.getObjectAt(1))

            val storageEntries = HashMap<DataWord, DataWord?>()

            for(entry in storage.objects) {
                val entryObj = DERSequence.getInstance(entry) ?: return null
                val entryPair = decodeMapEntry(entryObj.encoded) ?: return null
                val dataVal = if(entryPair.second == null) null else DataWord(entryPair.second)
                storageEntries.put(DataWord(entryPair.first), dataVal)
            }
            return AccountStorage(address, storageEntries)

        }
        return null
    }





    /**
     * 序列化账户状态(Account State)。(使用ASN.1规范)
     */
    fun encodeAccountState(accountState: AccountState): ByteArray {
        val v = ASN1EncodableVector()

        v.add(ASN1Integer(accountState.nonce))
        v.add(ASN1Integer(accountState.balance))
        if(accountState.stateRoot == null) {
            v.add(DERBitString(HashUtil.EMPTY_TRIE_HASH))
        } else {
            v.add(DERBitString(accountState.stateRoot))
        }
        if(accountState.getCodeHash() == null) {
            v.add(DERBitString(HashUtil.EMPTY_DATA_HASH))
        } else {
            v.add(DERBitString(accountState.getCodeHash()))
        }

        return DERSequence(v).encoded
    }

    /**
     * 反序列化账户状态(Account State)。(使用ASN.1规范)
     */
    fun decodeAccountState(bytes: ByteArray): AccountState? {
        val v = ASN1InputStream(bytes).readObject()

        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)
            val nonce = ASN1Integer.getInstance(seq.getObjectAt(0))?.value
            val balance = ASN1Integer.getInstance(seq.getObjectAt(1))?.value
            var stateRoot = DERBitString.getInstance(seq.getObjectAt(2))?.bytes
            var codeHash = DERBitString.getInstance(seq.getObjectAt(3))?.bytes

            if (nonce != null && balance != null) {
                return AccountState(nonce, balance, stateRoot!!, codeHash!!)
            }
        }

        return null
    }

    /**
     * 序列化交易(Transaction)。(使用ASN.1规范)
     */
    fun encodeTransaction(transaction: Transaction): ByteArray {
        return encodeTransactionToAsn1(transaction).encoded
    }

    /**
     * Transaction => ASN1结构
     *
     * 注意：不要包含Signature
     */
    fun encodeTransactionToAsn1(trx: Transaction): DERSequence {
        val v = ASN1EncodableVector()

        v.add(DERBitString(trx.senderAddress))
        v.add(DERBitString(trx.receiverAddress))
        v.add(ASN1Integer(trx.amount))
        v.add(ASN1Integer(trx.time.millis))
        v.add(DERBitString(trx.publicKey.encoded))
        v.add(DERBitString(trx.signature))
        v.add(DERBitString(trx.nonce))
        v.add(DERBitString(trx.gasPrice))
        v.add(DERBitString(trx.gasLimit))
        v.add(DERBitString(trx.data))

        return DERSequence(v)
    }

    fun encodeTransactionWithoutSignatureToAsn1(trx: Transaction): DERSequence {
        val v = ASN1EncodableVector()

        v.add(DERBitString(trx.senderAddress))
        v.add(DERBitString(trx.receiverAddress))
        v.add(ASN1Integer(trx.amount))
        v.add(ASN1Integer(trx.time.millis))
        v.add(DERBitString(trx.publicKey.encoded))
        // 不要包含Signature
        v.add(DERBitString(trx.nonce))
        v.add(DERBitString(trx.gasPrice))
        v.add(DERBitString(trx.gasLimit))
        v.add(DERBitString(trx.data))

        return DERSequence(v)
    }

    /**
     * 反序列化交易(Transaction)。(使用ASN.1规范)
     */
    fun decodeTransaction(bytes: ByteArray): Transaction? {
        val v = ASN1InputStream(bytes).readObject()

        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)
            return decodeTransactionFromSeq(seq)
        }

        return null
    }

    /**
     * ASN1Sequence => Transaction
     */
    fun decodeTransactionFromSeq(seq: ASN1Sequence): Transaction? {
        val senderAddress = DERBitString.getInstance(seq.getObjectAt(0))?.bytes
        val receiverAddress = DERBitString.getInstance(seq.getObjectAt(1))?.bytes
        val amount = ASN1Integer.getInstance(seq.getObjectAt(2))?.value
        val millis = ASN1Integer.getInstance(seq.getObjectAt(3))?.value
        val publicKeyBytes = DERBitString.getInstance(seq.getObjectAt(4))?.bytes
        val signature = DERBitString.getInstance(seq.getObjectAt(5))?.bytes
        val nonce = DERBitString.getInstance(seq.getObjectAt(6))?.bytes
        val gasPrice = DERBitString.getInstance(seq.getObjectAt(7))?.bytes
        val gasLimit = DERBitString.getInstance(seq.getObjectAt(8))?.bytes
        val data = DERBitString.getInstance(seq.getObjectAt(9))?.bytes

        if (senderAddress != null && receiverAddress != null && amount != null && millis != null &&
            publicKeyBytes != null && signature != null && nonce != null && gasPrice != null && gasLimit != null
            && data != null
        ) {
            val kf = KeyFactory.getInstance("EC", "SC")
            val publicKey = kf.generatePublic(X509EncodedKeySpec(publicKeyBytes))

            return Transaction(senderAddress, receiverAddress, amount, DateTime(millis.toLong()), publicKey, signature, nonce, gasPrice, gasLimit, data)
        } else {
            return null
        }
    }

    /**
     * 序列化区块(Block)。(使用ASN.1规范)
     */
    fun encodeBlock(block: Block): ByteArray {

        return encodeBlockToAsn1(block).encoded
    }

    fun encodeBlockToAsn1(block: Block): DERSequence {

        val v = ASN1EncodableVector()

        v.add(ASN1Integer(block.version.toLong()))
        v.add(ASN1Integer(block.height))
        v.add(DERBitString(block.parentHash))
        v.add(DERBitString(block.coinBase))
        v.add(ASN1Integer(block.difficulty))
        v.add(ASN1Integer(block.nonce.toLong()))
        v.add(ASN1Integer(block.time.millis))
        v.add(ASN1Integer(block.totalDifficulty))
        v.add(DERBitString(block.stateRoot))
        v.add(DERBitString(block.trxTrieRoot))

        v.add(ASN1Integer(block.transactions.size.toLong()))

        val t = ASN1EncodableVector()
        block.transactions.forEach { t.add(encodeTransactionToAsn1(it)) } // transactions
        v.add(DERSequence(t))

        v.add(DERBitString(block.gasLimit))

        return DERSequence(v)
    }

    /**
     * 反序列化区块(Block)。(使用ASN.1规范)
     */
    fun decodeBlock(bytes: ByteArray): Block? {
        val v = ASN1InputStream(bytes).readObject()

        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)
            val version = ASN1Integer.getInstance(seq.getObjectAt(0)).value
            val height = ASN1Integer.getInstance(seq.getObjectAt(1)).value
            val parentHash = DERBitString.getInstance(seq.getObjectAt(2))?.bytes
            val minerAddress = DERBitString.getInstance(seq.getObjectAt(3))?.bytes
            val difficulty = ASN1Integer.getInstance(seq.getObjectAt(4))?.value
            val nonce = ASN1Integer.getInstance(seq.getObjectAt(5))?.value
            val millis = ASN1Integer.getInstance(seq.getObjectAt(6))?.value
            val totalDifficulty = ASN1Integer.getInstance(seq.getObjectAt(7))?.value ?: BigInteger.ZERO
            val stateRoot = DERBitString.getInstance(seq.getObjectAt(8))?.bytes
            val trxTrieRoot = DERBitString.getInstance(seq.getObjectAt(9))?.bytes

            val trxSize = ASN1Integer.getInstance(seq.getObjectAt(10))?.value

            val trxValues = ASN1Sequence.getInstance(seq.getObjectAt(11))

            val trxList = mutableListOf<Transaction>()

            for (trxValue in trxValues.objects) {
                val trxObj = DERSequence.getInstance(trxValue) ?: return null
                val trx = decodeTransaction(trxObj.encoded) ?: return null
                trxList.add(trx)
            }

            val gasLimit = DERBitString.getInstance(seq.getObjectAt(12))?.bytes

            if (version == null || height == null || parentHash == null || minerAddress == null ||
                difficulty == null || nonce == null || millis == null || totalDifficulty == null ||
                stateRoot == null || trxTrieRoot == null || trxSize == null || trxSize.toInt() != trxList.size ||
                        gasLimit == null
            ) {
                return null
            }

            return Block(
                version.toInt(), height.toLong(), parentHash, minerAddress,
                DateTime(millis.toLong()), difficulty.toLong(), nonce.toInt(), totalDifficulty,
                stateRoot, trxTrieRoot, trxList, gasLimit
            )
        }

        return null
    }

    /**
     * 序列化区块(Block)。(使用ASN.1规范)
     */
    fun encodeBlockHeader(blockHeader: BlockHeader): ByteArray {

        return encodeBlockHeaderToAsn1(blockHeader).encoded
    }

    fun encodeBlockHeaderToAsn1(blockHeader: BlockHeader): DERSequence {

        val v = ASN1EncodableVector()

        v.add(ASN1Integer(blockHeader.version.toLong()))
        v.add(ASN1Integer(blockHeader.height))
        v.add(DERBitString(blockHeader.parentHash))
        v.add(DERBitString(blockHeader.coinBase))
        v.add(ASN1Integer(blockHeader.difficulty))
        v.add(ASN1Integer(blockHeader.nonce.toLong()))
        v.add(ASN1Integer(blockHeader.time.millis))
        v.add(ASN1Integer(blockHeader.totalDifficulty))
        v.add(DERBitString(blockHeader.stateRoot))
        v.add(DERBitString(blockHeader.trxTrieRoot))

         return DERSequence(v)
    }

    /**
     * 反序列化区块(Block)。(使用ASN.1规范)
     */
    fun decodeBlockHeader(bytes: ByteArray): BlockHeader? {
        val v = ASN1InputStream(bytes).readObject()

        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)
            val version = ASN1Integer.getInstance(seq.getObjectAt(0)).value
            val height = ASN1Integer.getInstance(seq.getObjectAt(1)).value
            val parentHash = DERBitString.getInstance(seq.getObjectAt(2))?.bytes
            val minerAddress = DERBitString.getInstance(seq.getObjectAt(3))?.bytes
            val difficulty = ASN1Integer.getInstance(seq.getObjectAt(4))?.value
            val nonce = ASN1Integer.getInstance(seq.getObjectAt(5))?.value
            val millis = ASN1Integer.getInstance(seq.getObjectAt(6))?.value
            val totalDifficulty = ASN1Integer.getInstance(seq.getObjectAt(7))?.value ?: BigInteger.ZERO
            val stateRoot = DERBitString.getInstance(seq.getObjectAt(8))?.bytes
            val trxTrieRoot = DERBitString.getInstance(seq.getObjectAt(9))?.bytes

            if (version == null || height == null || parentHash == null || minerAddress == null ||
                difficulty == null || nonce == null || millis == null || totalDifficulty == null ||
                stateRoot == null || trxTrieRoot == null
            ) {
                return null
            }

            return BlockHeader(
                version.toInt(), height.toLong(), parentHash, minerAddress,
                DateTime(millis.toLong()), difficulty.toLong(), nonce.toInt(), totalDifficulty,
                stateRoot, trxTrieRoot
            )
        }

        return null
    }

    /**
     * 序列化区块信息(BlockInfo)。(使用ASN.1规范)
     */
    fun encodeBlockInfos(blocks: List<BlockInfo>): ByteArray {

        val v = ASN1EncodableVector()

        blocks.map {
            val t = ASN1EncodableVector()
            t.add(DERBitString(it.hash))
            t.add(ASN1Boolean.getInstance(it.isMain))
            t.add(ASN1Integer(it.totalDifficulty))

            v.add(DERSequence(t))
        }

        return DERSequence(v).encoded
    }

    /**
     * 反序列化区块信息(BlockInfo)。(使用ASN.1规范)
     */
    fun decodeBlockInfos(bytes: ByteArray): List<BlockInfo>? {
        val v = ASN1InputStream(bytes).readObject()

        val result = mutableListOf<BlockInfo>()
        if (v != null) {
            val seq = ASN1Sequence.getInstance(v)

            for (blockInfoInAsn1 in seq.objects) {
                val blockInfoInSeq = DERSequence.getInstance(blockInfoInAsn1)
                if (blockInfoInSeq != null) {
                    val hash = DERBitString.getInstance(blockInfoInSeq.getObjectAt(0))?.bytes
                    val isMain = ASN1Boolean.getInstance(blockInfoInSeq.getObjectAt(1))?.isTrue
                    val totalDifficulty =
                        ASN1Integer.getInstance(blockInfoInSeq.getObjectAt(2))?.value ?: BigInteger.ZERO

                    if (hash == null || totalDifficulty == null || isMain == null) {
                        return null
                    }

                    result.add(BlockInfo(hash, isMain, totalDifficulty))
                }
            }
            return result
        }

        return null
    }

    fun intToByteArray(i: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(i).array()
    }

    fun longToByteArray(i: Long): ByteArray {
        return ByteBuffer.allocate(8).putLong(i).array()
    }

    fun byteArrayToInt(b: ByteArray): Int {
        if (b.size == 0) {
            return 0
        }

        return BigInteger(b).toInt()
    }

    fun asn1Encode(v: Any): ASN1Object {
        if (v is ByteArray) {
            return DERBitString(v)
        } else if (v is String) {
            return DERUTF8String(v)
        } else if (v is Int) {
            return ASN1Integer(v.toLong())
        } else if (v is Long) {
            return ASN1Integer(v)
        } else if (v is BigInteger) {
            return ASN1Integer(v)
        } else if (v is Array<*>) {
            val vec = ASN1EncodableVector()

            v.forEach { vec.add(it?.let { asn1Encode(it) }) }

            return DERSequence(vec)
        } else if (v is Transaction) {
            return encodeTransactionToAsn1(v)
        } else if (v is Block) {
            return encodeBlockToAsn1(v)
        } else {
            throw Exception("Can not convert type ${v.javaClass} to ASN1 object.")
        }
    }

}
