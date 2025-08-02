package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Basic configuration for ConflictSync. Only the Bloom filter false positive rate
 * is exposed for now. More parameters can be added as the implementation evolves.
 */
data class ConflictSyncConfig(val bloomFilterFalsePositiveRate: Double = 0.01)

/**
 * Messages exchanged during the ConflictSync protocol. These are serialisable
 * so they can easily be sent over the network. Only a subset of the messages
 * from the original paper are implemented – symbol streaming is not yet
 * supported and the reconciliation step assumes no false positives.
 */
@Serializable
sealed class ConflictSyncMessage {
    @Serializable
    data class BloomFilter(
        val sessionId: String,
        val bloomBits: ByteArray,
        val numHashFunctions: Int,
        val capacity: Int
    ) : ConflictSyncMessage()

    @Serializable
    data class InitStream(
        val sessionId: String,
        val responseBits: ByteArray,
        val responseHashFunctions: Int,
        val responseCapacity: Int,
        val exclusiveElements: List<String>
    ) : ConflictSyncMessage()

    @Serializable
    data class EndOfStream(
        val sessionId: String,
        val missingHashes: Set<String>,
        val falsePositiveElements: List<String>,
        val exclusiveElements: List<String>
    ) : ConflictSyncMessage()
}

/** Bloom filter used for the initial digest exchange. */
class SynkBloomFilter(
    val capacity: Int,
    private val falsePositiveRate: Double,
    val numHashFunctions: Int = (
        (-capacity * ln(falsePositiveRate) / ln(2.0).pow(2)).toInt()
    ).let { size -> (size * ln(2.0) / capacity).toInt().coerceAtLeast(1) }
) {
    private val bitArraySize: Int = (-capacity * ln(falsePositiveRate) / (ln(2.0).pow(2))).toInt()
    private val bitArray: BooleanArray = BooleanArray(bitArraySize)

    fun add(element: String) {
        for (index in getHashIndices(element)) {
            bitArray[index] = true
        }
    }

    fun contains(element: String): Boolean = getHashIndices(element).all { bitArray[it] }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(ceil(bitArraySize / 8.0).toInt())
        for (i in bitArray.indices) {
            if (bitArray[i]) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return bytes
    }

    private fun getHashIndices(element: String): List<Int> {
        val h1 = element.hashCode()
        val h2 = element.reversed().hashCode()
        return (0 until numHashFunctions).map { i ->
            kotlin.math.abs(h1 + i * h2) % bitArraySize
        }
    }

    companion object {
        fun fromByteArray(
            bytes: ByteArray,
            numHashFunctions: Int,
            capacity: Int,
            falsePositiveRate: Double = 0.01
        ): SynkBloomFilter {
            val filter = SynkBloomFilter(capacity, falsePositiveRate, numHashFunctions)
            for (i in bytes.indices) {
                for (bit in 0..7) {
                    val bitIndex = i * 8 + bit
                    if (bitIndex < filter.bitArray.size) {
                        filter.bitArray[bitIndex] = (bytes[i].toInt() shr bit and 1) == 1
                    }
                }
            }
            return filter
        }
    }
}

/**
 * Join decomposition built on top of existing SynkAdapter encode/decode
 * functionality. Each element is represented as a "key=value" string.
 */
class JoinDecomposer<T : Any>(private val adapter: SynkAdapter<T>) {
    fun decompose(state: T): Set<String> {
        return adapter.encode(state).map { (k, v) -> "$k=$v" }.toSet()
    }

    fun recompose(decompositions: Set<String>): T {
        val map = decompositions.associate { decomp ->
            val parts = decomp.split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        return adapter.decode(map)
    }

    fun hashDecomposition(decomposition: String): String {
        return decomposition.encodeUtf8().sha256().hex()
    }
}

/** A minimal representation of a rateless symbol. */
data class RatelessSymbol(
    val idSum: ByteArray,
    val hashSum: ByteArray,
    val count: Int
)

/**
 * Simplified RatelessIBLT. Symbol generation is deterministic based on the
 * element and symbol index. Decoding is not implemented and the protocol uses
 * a direct set difference in place of the rateless reconciliation step.
 */
class RatelessIBLT(private val elements: Set<String>) {
    fun generateSymbol(index: Int): RatelessSymbol {
        val idSum = ByteArray(32)
        val hashSum = ByteArray(32)
        var count = 0

        elements.forEach { element ->
            if (shouldInclude(element, index)) {
                val bytes = element.encodeUtf8().toByteArray()
                val hash = element.encodeUtf8().sha256().toByteArray()
                for (i in bytes.indices.take(32)) {
                    idSum[i] = (idSum[i].toInt() xor bytes[i].toInt()).toByte()
                }
                for (i in 0 until 32) {
                    hashSum[i] = (hashSum[i].toInt() xor hash[i].toInt()).toByte()
                }
                count++
            }
        }
        return RatelessSymbol(idSum, hashSum, count)
    }

    private fun shouldInclude(element: String, symbolIndex: Int): Boolean {
        val seed = element.hashCode().toLong() xor symbolIndex.toLong()
        return Random(seed).nextBoolean()
    }
}

/** Protocol phases used internally by the session. */
private enum class ConflictSyncPhase {
    INITIAL,
    BLOOM_SENT,
    COMPLETED
}

/**
 * Manages a single ConflictSync session. The rateless reconciliation phase is
 * simplified; we merge exclusive elements directly after the Bloom filter
 * exchange and ignore Bloom false positives.
 */
class ConflictSyncSession<T : Any>(
    val sessionId: String,
    private val adapter: SynkAdapter<T>,
    private val config: ConflictSyncConfig = ConflictSyncConfig()
) {
    private val decomposer = JoinDecomposer(adapter)
    private var state: T? = null
    private var phase: ConflictSyncPhase = ConflictSyncPhase.INITIAL

    fun initiate(localState: T): ConflictSyncMessage.BloomFilter {
        state = localState
        val decompositions = decomposer.decompose(localState)
        val bloom = SynkBloomFilter(
            decompositions.size.coerceAtLeast(1),
            config.bloomFilterFalsePositiveRate
        )
        decompositions.forEach { bloom.add(it) }
        phase = ConflictSyncPhase.BLOOM_SENT
        return ConflictSyncMessage.BloomFilter(sessionId, bloom.toByteArray(), bloom.numHashFunctions, bloom.capacity)
    }

    fun process(message: ConflictSyncMessage): ConflictSyncMessage? {
        return when (message) {
            is ConflictSyncMessage.BloomFilter -> processBloomFilter(message)
            is ConflictSyncMessage.InitStream -> processInitStream(message)
            is ConflictSyncMessage.EndOfStream -> processEndOfStream(message)
        }
    }

    private fun processBloomFilter(message: ConflictSyncMessage.BloomFilter): ConflictSyncMessage {
        val currentState = state ?: error("Session not initiated")
        val localDecomps = decomposer.decompose(currentState)
        val remoteBloom = SynkBloomFilter.fromByteArray(
            message.bloomBits,
            message.numHashFunctions,
            message.capacity
        )

        val exclusive = mutableSetOf<String>()
        val common = mutableSetOf<String>()
        for (d in localDecomps) {
            if (remoteBloom.contains(d)) common += d else exclusive += d
        }

        val responseBloom = SynkBloomFilter(common.size.coerceAtLeast(1), config.bloomFilterFalsePositiveRate)
        common.forEach { responseBloom.add(it) }
        return ConflictSyncMessage.InitStream(
            sessionId,
            responseBloom.toByteArray(),
            responseBloom.numHashFunctions,
            responseBloom.capacity,
            exclusive.toList()
        )
    }

    private fun processInitStream(message: ConflictSyncMessage.InitStream): ConflictSyncMessage {
        mergeElements(message.exclusiveElements.toSet())
        val currentState = state ?: error("Session not initiated")
        val localDecomps = decomposer.decompose(currentState)

        val remoteBloom = SynkBloomFilter.fromByteArray(
            message.responseBits,
            message.responseHashFunctions,
            message.responseCapacity
        )
        val exclusive = localDecomps.filterNot { remoteBloom.contains(it) }
        phase = ConflictSyncPhase.COMPLETED
        return ConflictSyncMessage.EndOfStream(sessionId, emptySet(), emptyList(), exclusive)
    }

    private fun processEndOfStream(message: ConflictSyncMessage.EndOfStream): ConflictSyncMessage? {
        mergeElements(message.exclusiveElements.toSet())
        phase = ConflictSyncPhase.COMPLETED
        return null
    }

    private fun mergeElements(newElements: Set<String>) {
        val currentState = state ?: return
        val existing = decomposer.decompose(currentState)
        val merged = existing + newElements
        state = decomposer.recompose(merged)
    }

    fun isCompleted(): Boolean = phase == ConflictSyncPhase.COMPLETED
    fun getResult(): T? = state
}

/** Create a new ConflictSync session for the provided state. */
inline fun <reified T : Any> Synk.conflictSyncInitiate(
    state: T,
    config: ConflictSyncConfig = ConflictSyncConfig()
): ConflictSyncSession<T> {
    val adapter = synkAdapterStore.resolve(T::class as KClass<T>) as SynkAdapter<T>
    val session = ConflictSyncSession(java.util.UUID.randomUUID().toString(), adapter, config)
    session.initiate(state)
    return session
}

/** Process an incoming ConflictSync message using the provided session. */
fun <T : Any> Synk.conflictSyncProcess(
    message: ConflictSyncMessage,
    session: ConflictSyncSession<T>
): ConflictSyncMessage? {
    return session.process(message)
}

/** Serialise a ConflictSync message to JSON. */
fun ConflictSyncMessage.serialize(): String =
    Json.encodeToString(ConflictSyncMessage.serializer(), this)

/** Deserialise a ConflictSync message from JSON. */
fun String.deserializeConflictSyncMessage(): ConflictSyncMessage =
    Json.decodeFromString(ConflictSyncMessage.serializer(), this)
