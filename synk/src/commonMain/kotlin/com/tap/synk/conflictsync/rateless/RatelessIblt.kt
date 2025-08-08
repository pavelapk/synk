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
    private var aggregated: Iblt? = null

    fun localSet(hashes: Set<Hash64>) { this.localSet = hashes }

    fun nextSymbol(index: Int): Symbol {
        val iblt = Iblt(params.ibltCellsPerSymbol, params.ibltHashFunctions, saltFor(index))
        localSet.forEach { iblt.insert(it) }
        return Symbol(index, iblt.toBytes())
    }

    fun absorb(peer: Symbol) {
        val a = Iblt(params.ibltCellsPerSymbol, params.ibltHashFunctions, saltFor(peer.index))
        localSet.forEach { a.insert(it) }
        val b = Iblt.fromBytes(params.ibltCellsPerSymbol, params.ibltHashFunctions, saltFor(peer.index), peer.bytes)
        b.diffAssign(a)
        // For now, use the latest symbol only; sufficient for small deltas
        aggregated = b
    }

    fun tryDecode(): DecodeResult {
        val agg = aggregated ?: return DecodeResult.NeedMore
        val res = agg.peel() ?: return DecodeResult.NeedMore
        val (presentAtB_notA, presentAtA_notB) = res
        return DecodeResult.Done(
            missingHere = presentAtB_notA,
            missingThere = presentAtA_notB,
        )
    }

    private fun saltFor(index: Int): Long = sessionSalt xor (index.toLong() * 0x9E3779B97F4A7C15UL.toLong())
}
