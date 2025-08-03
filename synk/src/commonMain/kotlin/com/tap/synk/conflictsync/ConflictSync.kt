package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Configuration for ConflictSync. The current implementation supports a
 * bloom-filter based handshake followed by a rateless set reconciliation stage.
 */
data class ConflictSyncConfig(
    val bloomFilterFalsePositiveRate: Double = 0.01,
)

/** Messages used by the simplified ConflictSync handshake. */
@Serializable
sealed class ConflictSyncMessage {
    @Serializable
    data class BloomFilter(
        val sessionId: String,
        val bloomBits: ByteArray,
        val numHashFunctions: Int,
        val capacity: Int,
    ) : ConflictSyncMessage()

    @Serializable
    data class InitStream(
        val sessionId: String,
        val bloomBits: ByteArray,
        val numHashFunctions: Int,
        val capacity: Int,
        val exclusiveElements: List<String>,
    ) : ConflictSyncMessage()

    @Serializable
    data class SymbolStream(
        val sessionId: String,
        val symbolIndex: Int,
        val idSum: ByteArray,
        val hashSum: ByteArray,
        val count: Int,
        val exclusiveElements: List<String> = emptyList(),
    ) : ConflictSyncMessage()

    @Serializable
    data class EndOfStream(
        val sessionId: String,
        val missingHashes: Set<String>,
        val falsePositiveElements: List<String>,
        val exclusiveElements: List<String>,
    ) : ConflictSyncMessage()

    @Serializable
    data class FinalElements(
        val sessionId: String,
        val elements: List<String>,
    ) : ConflictSyncMessage()
}

/** Simple bloom filter implementation for string elements. */
class SynkBloomFilter(private val capacity: Int, private val falsePositiveRate: Double = 0.01) {
    private val bitArraySize: Int = (-capacity * ln(falsePositiveRate) / (ln(2.0).pow(2))).toInt().coerceAtLeast(8)
    private val numHashFunctions: Int = (bitArraySize * ln(2.0) / capacity).toInt().coerceAtLeast(1)
    private val bitArray = BooleanArray(bitArraySize)

    fun add(element: String) {
        hashIndices(element).forEach { bitArray[it] = true }
    }

    fun contains(element: String): Boolean = hashIndices(element).all { bitArray[it] }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(ceil(bitArraySize / 8.0).toInt())
        for (i in bitArray.indices) {
            if (bitArray[i]) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return bytes
    }

    private fun hashIndices(element: String): List<Int> {
        val h1 = element.hashCode()
        val h2 = element.reversed().hashCode()
        return (0 until numHashFunctions).map { kotlin.math.abs(h1 + it * h2) % bitArraySize }
    }

    fun getNumHashFunctions(): Int = numHashFunctions
    fun getCapacity(): Int = capacity

    companion object {
        fun fromByteArray(bytes: ByteArray, numHashFunctions: Int, capacity: Int): SynkBloomFilter {
            val filter = SynkBloomFilter(capacity)
            for (i in bytes.indices) {
                for (bit in 0 until 8) {
                    val idx = i * 8 + bit
                    if (idx < filter.bitArray.size) {
                        filter.bitArray[idx] = (bytes[i].toInt() and (1 shl bit)) != 0
                    }
                }
            }
            return filter
        }
    }
}

/**
 * Decomposes CRDT states to key=value strings using the registered
 * [SynkAdapter].
 */
class JoinDecomposer<T : Any>(private val adapter: SynkAdapter<T>) {
    fun decompose(state: T): Set<String> = adapter.encode(state).map { (k, v) -> "$k=$v" }.toSet()

    fun recompose(decompositions: Set<String>): T {
        val map = decompositions.associate { decomp ->
            val parts = decomp.split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        return adapter.decode(map)
    }

    fun hash(decomposition: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(decomposition.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Symbol used by the rateless IBLT. The [idSum] is the XOR of the raw element
 * bytes, [hashSum] is the XOR of their hashes and [count] tracks the number of
 * elements included in the symbol.
 */
data class RatelessSymbol(
    val idSum: ByteArray,
    val hashSum: ByteArray,
    var count: Int,
)

/**
 * Minimal rateless IBLT implementation capable of generating symbols and
 * combining them to compute a symmetric difference.
 */
class RatelessIBLT(private val elements: Set<String>) {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun generateSymbol(index: Int): RatelessSymbol {
        var idSum = ByteArray(32)
        var hashSum = ByteArray(32)
        var count = 0

        elements.forEach { element ->
            if (shouldInclude(element, index)) {
                val elementBytes = element.toByteArray()
                val elementHash = digest.digest(elementBytes)
                for (i in elementBytes.indices.take(32)) {
                    idSum[i] = (idSum[i].toInt() xor elementBytes[i].toInt()).toByte()
                }
                for (i in elementHash.indices) {
                    hashSum[i] = (hashSum[i].toInt() xor elementHash[i].toInt()).toByte()
                }
                count++
            }
        }

        return RatelessSymbol(idSum, hashSum, count)
    }

    private fun shouldInclude(element: String, index: Int): Boolean {
        val seed = element.hashCode().toLong() xor index.toLong()
        val random = Random(seed)
        return random.nextBoolean()
    }

    companion object {
        fun combine(a: RatelessSymbol, b: RatelessSymbol): RatelessSymbol {
            val id = ByteArray(32)
            val hash = ByteArray(32)
            for (i in 0 until 32) {
                id[i] = (a.idSum[i].toInt() xor b.idSum[i].toInt()).toByte()
                hash[i] = (a.hashSum[i].toInt() xor b.hashSum[i].toInt()).toByte()
            }
            return RatelessSymbol(id, hash, a.count + b.count)
        }

        fun attemptDecode(symbols: List<RatelessSymbol>): Pair<Set<String>, Set<String>>? {
            if (symbols.isEmpty()) return null
            if (symbols.all { it.count == 0 && it.idSum.all { b -> b == 0.toByte() } && it.hashSum.all { h -> h == 0.toByte() } }) {
                return emptySet<String>() to emptySet()
            }

            // simplified peeling decoder
            val working = symbols.map { it.copy() }.toMutableList()
            val recovered = mutableSetOf<String>()
            var progress = true
            val digest = MessageDigest.getInstance("SHA-256")

            fun remove(element: String) {
                val bytes = element.toByteArray()
                val hash = digest.digest(bytes)
                working.forEach { symbol ->
                    for (i in bytes.indices.take(32)) {
                        symbol.idSum[i] = (symbol.idSum[i].toInt() xor bytes[i].toInt()).toByte()
                    }
                    for (i in hash.indices) {
                        symbol.hashSum[i] = (symbol.hashSum[i].toInt() xor hash[i].toInt()).toByte()
                    }
                    symbol.count--
                }
            }

            while (progress && working.isNotEmpty()) {
                progress = false
                val iterator = working.iterator()
                while (iterator.hasNext()) {
                    val sym = iterator.next()
                    if (sym.count == 1) {
                        val element = String(sym.idSum).trimEnd('\u0000')
                        val hash = digest.digest(element.toByteArray())
                        if (hash.contentEquals(sym.hashSum)) {
                            recovered.add(element)
                            remove(element)
                            iterator.remove()
                            progress = true
                        }
                    } else if (sym.count == 0 && sym.idSum.all { it == 0.toByte() } && sym.hashSum.all { it == 0.toByte() }) {
                        iterator.remove()
                        progress = true
                    }
                }
            }

            return if (working.isEmpty()) recovered to emptySet() else null
        }
    }
}

/**
 * State machine for the simplified ConflictSync handshake.
 */
class ConflictSyncSession<T : Any>(
    val sessionId: String,
    private val adapter: SynkAdapter<T>,
    private val config: ConflictSyncConfig = ConflictSyncConfig(),
) {
    private val decomposer = JoinDecomposer(adapter)
    private var localState: T? = null
    private var localDecompositions: Set<String> = emptySet()
    private var completed: Boolean = false

    private var localHashes: Set<String> = emptySet()
    private var commonLocal: Set<String> = emptySet()
    private var localExclusive: Set<String> = emptySet()
    private var remoteExclusive: Set<String> = emptySet()
    private var receivedSymbols: MutableList<RatelessSymbol> = mutableListOf()
    private var symbolIndex: Int = 0
    private var ratelessIBLT: RatelessIBLT? = null

    /** Set or update the local state used for reconciliation. */
    fun setState(state: T) {
        localState = state
        localDecompositions = decomposer.decompose(state)
    }

    fun initiate(state: T): ConflictSyncMessage.BloomFilter {
        setState(state)
        val bloom = SynkBloomFilter(localDecompositions.size.coerceAtLeast(1), config.bloomFilterFalsePositiveRate)
        localDecompositions.forEach { bloom.add(it) }
        return ConflictSyncMessage.BloomFilter(sessionId, bloom.toByteArray(), bloom.getNumHashFunctions(), bloom.getCapacity())
    }

    fun processMessage(message: ConflictSyncMessage): ConflictSyncMessage? = when (message) {
        is ConflictSyncMessage.BloomFilter -> processBloomFilter(message)
        is ConflictSyncMessage.InitStream -> processInitStream(message)
        is ConflictSyncMessage.SymbolStream -> processSymbolStream(message)
        is ConflictSyncMessage.EndOfStream -> processEndOfStream(message)
        is ConflictSyncMessage.FinalElements -> {
            applyElements(message.elements.toSet())
            completed = true
            null
        }
    }

    private fun processBloomFilter(message: ConflictSyncMessage.BloomFilter): ConflictSyncMessage {
        val state = localState ?: throw IllegalStateException("session not initiated")
        localDecompositions = decomposer.decompose(state)
        localHashes = localDecompositions.map { decomposer.hash(it) }.toSet()

        val remoteFilter = SynkBloomFilter.fromByteArray(message.bloomBits, message.numHashFunctions, message.capacity)
        val (exclusive, common) = localDecompositions.partition { !remoteFilter.contains(it) }

        localExclusive = exclusive.toSet()
        commonLocal = common.toSet()
        val responseFilter = SynkBloomFilter(common.size.coerceAtLeast(1), config.bloomFilterFalsePositiveRate)
        common.forEach { responseFilter.add(it) }

        // prepare rateless IBLT for common elements
        val commonHashes = common.map { decomposer.hash(it) }.toSet()
        ratelessIBLT = RatelessIBLT(commonHashes)

        return ConflictSyncMessage.InitStream(
            sessionId = sessionId,
            bloomBits = responseFilter.toByteArray(),
            numHashFunctions = responseFilter.getNumHashFunctions(),
            capacity = responseFilter.getCapacity(),
            exclusiveElements = localExclusive.toList(),
        )
    }

    private fun processInitStream(message: ConflictSyncMessage.InitStream): ConflictSyncMessage {
        val remoteFilter = SynkBloomFilter.fromByteArray(message.bloomBits, message.numHashFunctions, message.capacity)
        val (exclusive, common) = localDecompositions.partition { !remoteFilter.contains(it) }
        localExclusive = exclusive.toSet()
        commonLocal = common.toSet()

        // merge remote exclusive elements after partitioning
        applyElements(message.exclusiveElements.toSet())
        remoteExclusive = message.exclusiveElements.toSet()

        val commonHashes = common.map { decomposer.hash(it) }.toSet()
        ratelessIBLT = RatelessIBLT(commonHashes)
        symbolIndex = 0
        receivedSymbols.clear()

        // send first coded symbol
        val symbol = ratelessIBLT!!.generateSymbol(symbolIndex++)
        return ConflictSyncMessage.SymbolStream(
            sessionId,
            symbolIndex - 1,
            symbol.idSum,
            symbol.hashSum,
            symbol.count,
            localExclusive.toList(),
        )
    }

    private fun processSymbolStream(message: ConflictSyncMessage.SymbolStream): ConflictSyncMessage {
        val iblt = ratelessIBLT ?: return ConflictSyncMessage.EndOfStream(sessionId, emptySet(), emptyList(), localExclusive.toList())
        applyElements(message.exclusiveElements.toSet())
        val localSymbol = iblt.generateSymbol(message.symbolIndex)
        val remoteSymbol = RatelessSymbol(message.idSum, message.hashSum, message.count)
        val diff = RatelessIBLT.combine(localSymbol, remoteSymbol)
        receivedSymbols.add(diff)

        val decode = RatelessIBLT.attemptDecode(receivedSymbols)
        return if (decode != null) {
            val (hashesOnlyInA, hashesOnlyInB) = decode
            val falsePositives = commonLocal.filter { decomposer.hash(it) in hashesOnlyInA }
            ConflictSyncMessage.EndOfStream(sessionId, hashesOnlyInB, falsePositives, emptyList())
        } else {
            val next = iblt.generateSymbol(symbolIndex++)
            ConflictSyncMessage.SymbolStream(
                sessionId,
                symbolIndex - 1,
                next.idSum,
                next.hashSum,
                next.count,
                emptyList(),
            )
        }
    }

    private fun processEndOfStream(message: ConflictSyncMessage.EndOfStream): ConflictSyncMessage {
        // merge remote data
        applyElements(message.falsePositiveElements.toSet())
        applyElements(message.exclusiveElements.toSet())
        remoteExclusive = remoteExclusive + message.exclusiveElements

        val ourFalsePositives = commonLocal.filter { decomposer.hash(it) in message.missingHashes }
        completed = true

        return ConflictSyncMessage.FinalElements(sessionId, ourFalsePositives)
    }

    private fun applyElements(elements: Set<String>) {
        if (elements.isEmpty()) return
        val newDecompositions = localDecompositions + elements
        localState = decomposer.recompose(newDecompositions)
        localDecompositions = newDecompositions
    }

    fun isCompleted(): Boolean = completed
    fun getResult(): T? = localState
}

/** Extensions to [Synk] to create sessions and serialise messages. */
fun <T : Any> Synk.conflictSyncInitiate(
    state: T,
    config: ConflictSyncConfig = ConflictSyncConfig(),
): ConflictSyncSession<T> {
    val adapter = synkAdapterStore.resolve(state::class)
    @Suppress("UNCHECKED_CAST")
    return ConflictSyncSession(java.util.UUID.randomUUID().toString(), adapter as SynkAdapter<T>, config)
}

fun ConflictSyncMessage.serialize(): String =
    Json.encodeToString(ConflictSyncMessage.serializer(), this)

fun String.deserializeConflictSyncMessage(): ConflictSyncMessage =
    Json.decodeFromString(ConflictSyncMessage.serializer(), this)
