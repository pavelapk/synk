package com.tap.synk.conflictsync.rateless

import com.tap.synk.conflictsync.digest.Hash64
import kotlinx.serialization.Serializable

@Serializable
data class Symbol(val index: Int, val bytes: ByteArray)

sealed class DecodeResult {
    data object NeedMore : DecodeResult()
    data class Done(
        val missingHere: List<Hash64>, // present at peer B, absent at A
        val missingThere: List<Hash64>, // present at A, absent at B
    ) : DecodeResult()
}

data class RatelessParams(
    val ibltCellsPerSymbol: Int = 256,
    val ibltHashFunctions: Int = 3,
    val maxSymbols: Int = 10000,
)

/**
 * Both peers independently build an IBLT symbol from their local set of hashes,
 * using the same (salt, index). When A receives B_i, it computes A_i locally,
 * xors them, accumulates, and attempts to peel the union.
 */
internal class RatelessIblt(
    private val params: RatelessParams,
    private val sessionSalt: Long,
) {
    private lateinit var localSet: Set<Hash64>
    private val diffs = mutableListOf<Iblt>()
    private val decodedHere = linkedSetOf<Hash64>()
    private val decodedThere = linkedSetOf<Hash64>()
    private var sawPeerSymbol: Boolean = false

    fun localSet(hashes: Set<Hash64>) { this.localSet = hashes }

    fun nextSymbol(index: Int): Symbol {
        val iblt = Iblt(params.ibltCellsPerSymbol, params.ibltHashFunctions, saltFor(index))
        localSet.forEach { iblt.insert(it) }
        return Symbol(index, iblt.toBytes())
    }

    fun absorb(peer: Symbol) {
        val salt = saltFor(peer.index)
        val a = Iblt(params.ibltCellsPerSymbol, params.ibltHashFunctions, salt)
        localSet.forEach { a.insert(it) }
        val b = Iblt.fromBytes(params.ibltCellsPerSymbol, params.ibltHashFunctions, salt, peer.bytes)
        b.diffAssign(a)

        // Remove already-decoded keys from the new diff
        for (k in decodedHere) b.erase(k)
        for (k in decodedThere) b.insert(k)

        if (!b.isEmpty()) diffs += b
        sawPeerSymbol = true
    }

    fun tryDecode(): DecodeResult {
        if (!sawPeerSymbol) return DecodeResult.NeedMore
        if (diffs.isEmpty()) return DecodeResult.Done(decodedHere.toList(), decodedThere.toList())

        var progress: Boolean
        do {
            progress = false
            val iterator = diffs.listIterator()
            while (iterator.hasNext()) {
                val iblt = iterator.next()
                val singles = iblt.extractSingletons()
                if (singles.isNotEmpty()) progress = true
                for ((key, sign) in singles) {
                    if (sign > 0) {
                        if (decodedHere.add(key)) {
                            for (other in diffs) if (other !== iblt) other.erase(key)
                        }
                    } else {
                        if (decodedThere.add(key)) {
                            for (other in diffs) if (other !== iblt) other.insert(key)
                        }
                    }
                }
                if (iblt.isEmpty()) iterator.remove()
            }
        } while (progress && diffs.isNotEmpty())

        return if (diffs.isEmpty()) {
            DecodeResult.Done(decodedHere.toList(), decodedThere.toList())
        } else DecodeResult.NeedMore
    }

    private fun saltFor(index: Int): Long = sessionSalt xor (index.toLong() * 0x9E3779B97F4A7C15UL.toLong())
}
