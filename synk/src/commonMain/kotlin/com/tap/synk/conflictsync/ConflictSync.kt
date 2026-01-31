package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * Configuration for ConflictSync.  Only a very small subset of the design
 * described in the research paper is implemented here.  The goal of this file
 * is to provide a working skeleton that demonstrates how the algorithm fits in
 * with the existing Synk library.
 */
data class ConflictSyncConfig(
    val bloomFilterFalsePositiveRate: Double = 0.01,
)

/** A tiny Bloom filter implementation used for exchanging set digests. */
class SynkBloomFilter(private val capacity: Int, private val falsePositiveRate: Double) {
    private val bitArraySize: Int
    private val numHashFunctions: Int
    private val bits: BooleanArray

    init {
        bitArraySize = (-capacity * ln(falsePositiveRate) / (ln(2.0).pow(2))).toInt().coerceAtLeast(1)
        numHashFunctions = (bitArraySize * ln(2.0) / capacity).toInt().coerceAtLeast(1)
        bits = BooleanArray(bitArraySize)
    }

    fun add(element: String) {
        indices(element).forEach { bits[it] = true }
    }

    fun contains(element: String): Boolean = indices(element).all { bits[it] }

    fun toByteArray(): ByteArray {
        val byteSize = ceil(bitArraySize / 8.0).toInt()
        val out = ByteArray(byteSize)
        bits.forEachIndexed { index, value ->
            if (value) out[index / 8] = (out[index / 8].toInt() or (1 shl (index % 8))).toByte()
        }
        return out
    }

    private fun indices(element: String): List<Int> {
        val h1 = element.hashCode()
        val h2 = element.reversed().hashCode()
        return (0 until numHashFunctions).map { i ->
            kotlin.math.abs(h1 + i * h2) % bitArraySize
        }
    }

    fun numHashFunctions() = numHashFunctions
    fun capacity() = capacity

    companion object {
        fun fromByteArray(bytes: ByteArray, numHashFunctions: Int, capacity: Int): SynkBloomFilter {
            val filter = SynkBloomFilter(capacity, 0.01)
            for (i in bytes.indices) {
                for (b in 0 until 8) {
                    val idx = i * 8 + b
                    if (idx < filter.bitArraySize) {
                        filter.bits[idx] = (bytes[i].toInt() and (1 shl b)) != 0
                    }
                }
            }
            return filter
        }
    }
}

/**
 * Breaks a CRDT state into a set of strings using the registered
 * [SynkAdapter].  Each "key=value" pair acts as an element of the set for the
 * purposes of conflict detection.
 */
class JoinDecomposer<T : Any>(private val adapter: SynkAdapter<T>) {
    fun decompose(state: T): Set<String> =
        adapter.encode(state).map { (k, v) -> "$k=$v" }.toSet()

    fun recompose(elements: Set<String>): T {
        val map = elements.associate { elem ->
            val parts = elem.split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        return adapter.decode(map)
    }
}

/** Messages used during the simplified ConflictSync protocol. */
sealed class ConflictSyncMessage {
    data class BloomFilter(
        val sessionId: String,
        val bits: ByteArray,
        val numHashFunctions: Int,
        val capacity: Int
    ) : ConflictSyncMessage()

    data class InitStream(
        val sessionId: String,
        val responseBits: ByteArray,
        val responseHashFunctions: Int,
        val responseCapacity: Int,
        val exclusive: List<String>
    ) : ConflictSyncMessage()

    data class EndOfStream(
        val sessionId: String,
        val exclusive: List<String>
    ) : ConflictSyncMessage()

    data class FinalElements(
        val sessionId: String,
        val elements: List<String>
    ) : ConflictSyncMessage()

    data class Error(val sessionId: String, val message: String) : ConflictSyncMessage()
}

private enum class Phase { INITIAL, SENT, RECEIVED, COMPLETED, ERROR }

/**
 * Stateful session that performs a very small subset of the ConflictSync
 * algorithm.  The rateless reconciliation stage is purposely omitted; instead
 * we exchange exclusive elements directly after the bloom filter round.  This
 * keeps the implementation small while still demonstrating how the protocol
 * integrates with Synk.
 */
class ConflictSyncSession<T : Any>(
    val sessionId: String,
    private val adapter: SynkAdapter<T>,
    private val config: ConflictSyncConfig = ConflictSyncConfig()
) {
    private val decomposer = JoinDecomposer(adapter)
    private var phase: Phase = Phase.INITIAL
    private lateinit var localDecompositions: Set<String>
    private var state: T? = null

    fun initiate(state: T): ConflictSyncMessage.BloomFilter {
        this.state = state
        localDecompositions = decomposer.decompose(state)
        val filter = SynkBloomFilter(localDecompositions.size.coerceAtLeast(1), config.bloomFilterFalsePositiveRate)
        localDecompositions.forEach { filter.add(it) }
        phase = Phase.SENT
        return ConflictSyncMessage.BloomFilter(sessionId, filter.toByteArray(), filter.numHashFunctions(), filter.capacity())
    }

    fun process(message: ConflictSyncMessage): ConflictSyncMessage? = when (message) {
        is ConflictSyncMessage.BloomFilter -> processBloomFilter(message)
        is ConflictSyncMessage.InitStream -> processInitStream(message)
        is ConflictSyncMessage.EndOfStream -> processEndOfStream(message)
        is ConflictSyncMessage.FinalElements -> processFinal(message)
        is ConflictSyncMessage.Error -> { phase = Phase.ERROR; null }
    }

    private fun processBloomFilter(msg: ConflictSyncMessage.BloomFilter): ConflictSyncMessage.InitStream {
        val filter = SynkBloomFilter.fromByteArray(msg.bits, msg.numHashFunctions, msg.capacity)
        val exclusive = localDecompositions.filterNot { filter.contains(it) }.toSet()
        val common = localDecompositions - exclusive
        // create response filter for common elements
        val respFilter = SynkBloomFilter(common.size.coerceAtLeast(1), config.bloomFilterFalsePositiveRate)
        common.forEach { respFilter.add(it) }
        phase = Phase.RECEIVED
        return ConflictSyncMessage.InitStream(
            sessionId,
            respFilter.toByteArray(),
            respFilter.numHashFunctions(),
            respFilter.capacity(),
            exclusive.toList()
        )
    }

    private fun processInitStream(msg: ConflictSyncMessage.InitStream): ConflictSyncMessage.EndOfStream {
        // merge remote exclusive elements
        val remoteExclusive = msg.exclusive.toSet()
        val filter = SynkBloomFilter.fromByteArray(msg.responseBits, msg.responseHashFunctions, msg.responseCapacity)
        val ourExclusive = localDecompositions.filterNot { filter.contains(it) }.toSet()
        val merged = localDecompositions + remoteExclusive
        state = decomposer.recompose(merged)
        localDecompositions = merged
        return ConflictSyncMessage.EndOfStream(sessionId, ourExclusive.toList())
    }

    private fun processEndOfStream(msg: ConflictSyncMessage.EndOfStream): ConflictSyncMessage.FinalElements {
        val merged = localDecompositions + msg.exclusive.toSet()
        state = decomposer.recompose(merged)
        localDecompositions = merged
        phase = Phase.COMPLETED
        return ConflictSyncMessage.FinalElements(sessionId, emptyList())
    }

    private fun processFinal(msg: ConflictSyncMessage.FinalElements): ConflictSyncMessage? {
        val merged = localDecompositions + msg.elements.toSet()
        state = decomposer.recompose(merged)
        localDecompositions = merged
        phase = Phase.COMPLETED
        return null
    }

    fun result(): T? = state
    fun completed(): Boolean = phase == Phase.COMPLETED
}

/** Extensions on [Synk] to easily create and process ConflictSync sessions. */
fun <T : Any> Synk.conflictSyncInitiate(state: T, config: ConflictSyncConfig = ConflictSyncConfig()): ConflictSyncSession<T> {
    val adapter = synkAdapterStore.resolve(state::class) as SynkAdapter<T>
    return ConflictSyncSession("local", adapter, config)
}

fun <T : Any> Synk.conflictSyncProcess(
    message: ConflictSyncMessage,
    session: ConflictSyncSession<T>
): ConflictSyncMessage? = session.process(message)

