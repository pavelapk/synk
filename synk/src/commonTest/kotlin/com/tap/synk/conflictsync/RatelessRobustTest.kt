package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.digest.XxHash64
import com.tap.synk.conflictsync.rateless.DecodeResult
import com.tap.synk.conflictsync.rateless.RatelessIblt
import com.tap.synk.conflictsync.rateless.RatelessParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatelessRobustTest {
    @Test
    fun multi_symbol_decode_converges_and_matches_sets() {
        val params = RatelessParams(
            ibltCellsPerSymbol = 256,
            ibltHashFunctions = 3,
            maxSymbols = 512,
        )
        val salt = XxHash64.hashString("ns-rateless-robust")

        // A has {1,2,3}; B has {2,3,4,5}
        val a = setOf(1L, 2L, 3L)
        val b = setOf(2L, 3L, 4L, 5L)

        val client = RatelessIblt(params, salt).also { it.localSet(a) }
        val server = RatelessIblt(params, salt).also { it.localSet(b) }

        var i = 0
        var result: DecodeResult? = client.tryDecode() // allow empty/done fast path
        while (i < params.maxSymbols && result !is DecodeResult.Done) {
            client.absorb(server.nextSymbol(i))
            result = client.tryDecode()
            i++
        }

        assertTrue(result is DecodeResult.Done, "rateless did not decode within ${params.maxSymbols} symbols")
        assertTrue(i < params.maxSymbols, "rateless reached maxSymbols without earlier decode; i=$i")
        result as DecodeResult.Done
        assertEquals(listOf(4L, 5L), result.missingHere.sorted())
        assertEquals(listOf(1L), result.missingThere.sorted())
    }

    @Test
    fun no_diff_returns_done_empty_immediately() {
        val params = RatelessParams()
        val salt = XxHash64.hashString("ns-empty")
        val set = setOf(10L, 11L)
        val client = RatelessIblt(params, salt).also { it.localSet(set) }
        val server = RatelessIblt(params, salt).also { it.localSet(set) }

        // Before any symbols, we haven't seen the peer yet, so NeedMore
        val r0 = client.tryDecode()
        assertTrue(r0 is DecodeResult.NeedMore)

        // After first symbol, still empty but now we know it's Done(empty)
        client.absorb(server.nextSymbol(0))
        val r1 = client.tryDecode()
        assertTrue(r1 is DecodeResult.Done)
        r1 as DecodeResult.Done
        assertTrue(r1.missingHere.isEmpty() && r1.missingThere.isEmpty())
    }
}
