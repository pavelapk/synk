package com.tap.synk.conflictsync.prefilter

import com.tap.synk.conflictsync.digest.Hash64
import com.tap.synk.conflictsync.rateless.IntHash

internal class BloomFilter(
    private val mBits: Int,
    private val k: Int,
) {
    private val bits = BooleanArray(mBits)

    fun add(h: Hash64) {
        repeat(k) { i -> bits[index(h, i)] = true }
    }

    fun mightContain(h: Hash64): Boolean {
        for (i in 0 until k) if (!bits[index(h, i)]) return false
        return true
    }

    fun toBytes(): ByteArray = pack(bits)

    private fun index(h: Hash64, i: Int): Int {
        val a = IntHash.mix64to32(h)
        val b = IntHash.mix64to32(h xor 0x9E3779B97F4A7C15u.toLong())
        val v = a + i * b
        val idx = (v and Int.MAX_VALUE) % mBits
        return idx
    }

    companion object {
        fun fromBytes(mBits: Int, k: Int, bytes: ByteArray): BloomFilter {
            val bf = BloomFilter(mBits, k)
            unpack(bytes, bf.bits)
            return bf
        }

        private fun pack(bits: BooleanArray): ByteArray {
            val lenBytes = (bits.size + 7) / 8
            val out = ByteArray(lenBytes)
            for (i in bits.indices) {
                if (bits[i]) {
                    val byteIndex = i ushr 3
                    val bitIndex = i and 7
                    out[byteIndex] = (out[byteIndex].toInt() or (1 shl bitIndex)).toByte() // LSB-first within byte
                }
            }
            return out
        }

        private fun unpack(bytes: ByteArray, dst: BooleanArray) {
            val totalBits = dst.size
            var bit = 0
            for (b in bytes) {
                var mask = 1
                var i = 0
                while (i < 8 && bit < totalBits) {
                    dst[bit] = (b.toInt() and mask) != 0
                    mask = mask shl 1
                    bit++
                    i++
                }
                if (bit >= totalBits) break
            }
        }
    }
}

