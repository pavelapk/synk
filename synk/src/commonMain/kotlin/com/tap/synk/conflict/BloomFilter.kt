package com.tap.synk.conflict

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class BloomFilterPayload(
    val namespace: String,
    val bits: ByteArray,
    val hashSeed1: Long,
    val hashSeed2: Long,
    val hashCount: Int,
)

class BloomFilter private constructor(
    private val bits: ByteArray,
    private val hashCount: Int,
    private val hashSeed1: Long,
    private val hashSeed2: Long,
    private val hasher: Hasher64,
) {
    private val bitSize = bits.size * 8

    fun add(fingerprint: FieldFingerprint) {
        val indices = indexesFor(fingerprint)
        indices.forEach { setBit(it) }
    }

    fun mightContain(fingerprint: FieldFingerprint): Boolean {
        val indices = indexesFor(fingerprint)
        return indices.all { isBitSet(it) }
    }

    fun toPayload(namespace: String): BloomFilterPayload =
        BloomFilterPayload(
            namespace = namespace,
            bits = bits.copyOf(),
            hashSeed1 = hashSeed1,
            hashSeed2 = hashSeed2,
            hashCount = hashCount,
        )

    fun serializedSize(): Int = bits.size + Long.SIZE_BYTES * 2 + Int.SIZE_BYTES

    private fun indexesFor(fingerprint: FieldFingerprint): IntArray {
        require(bitSize > 0) { "Bloom filter bitSize must be positive" }
        val base = hasher.hash(fingerprint.bytes, hashSeed1)
        val deltaRaw = hasher.hash(fingerprint.bytes, hashSeed2)
        val delta = if (deltaRaw == 0L) 1L else deltaRaw

        val positiveMask = Long.MAX_VALUE
        return IntArray(hashCount) { idx ->
            val combined = (base + delta * idx) and positiveMask
            (combined % bitSize).toInt()
        }
    }

    private fun setBit(index: Int) {
        val byteIndex = index / 8
        val bitIndex = index % 8
        bits[byteIndex] = (bits[byteIndex].toInt() or (1 shl bitIndex)).toByte()
    }

    private fun isBitSet(index: Int): Boolean {
        val byteIndex = index / 8
        val bitIndex = index % 8
        return bits[byteIndex].toInt() and (1 shl bitIndex) != 0
    }

    companion object {
        fun build(
            fingerprints: Collection<FieldFingerprint>,
            fpr: Double,
            hasher: Hasher64,
            seedSupplier: () -> Long = { Random.nextLong() },
        ): BloomFilter {
            require(fpr in 0.0..1.0 && fpr > 0.0) { "fpr must be between 0.0 and 1.0" }
            val itemCount = fingerprints.size
            val bitCount = calculateBitCount(itemCount, fpr)
            val hashCount = calculateHashCount(bitCount, itemCount)
            val arraySize = max(1, (bitCount + 7) / 8)
            val filter = BloomFilter(
                bits = ByteArray(arraySize),
                hashCount = hashCount,
                hashSeed1 = seedSupplier(),
                hashSeed2 = seedSupplier(),
                hasher = hasher,
            )
            fingerprints.forEach { filter.add(it) }
            return filter
        }

        fun fromPayload(payload: BloomFilterPayload, hasher: Hasher64): BloomFilter =
            BloomFilter(
                bits = payload.bits.copyOf(),
                hashCount = payload.hashCount,
                hashSeed1 = payload.hashSeed1,
                hashSeed2 = payload.hashSeed2,
                hasher = hasher,
            )

        private fun calculateBitCount(itemCount: Int, fpr: Double): Int {
            if (itemCount <= 0) {
                return 8
            }
            val ratio = -(itemCount.toDouble() * ln(fpr)) / (ln(2.0) * ln(2.0))
            return max(8, ceil(ratio).toInt())
        }

        private fun calculateHashCount(bitCount: Int, itemCount: Int): Int {
            if (itemCount <= 0) return 1
            val optimal = (bitCount.toDouble() / itemCount) * ln(2.0)
            return max(1, optimal.roundToInt())
        }
    }
}
