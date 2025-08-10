package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.digest.XxHash64
import com.tap.synk.conflictsync.rateless.DecodeResult
import com.tap.synk.conflictsync.rateless.RatelessIblt
import kotlin.test.Test
import kotlin.test.assertEquals

class RatelessDecodeIntegrationTest {
    @Test
    fun client_decodes_missing_sets_against_server_symbols() {
        val params = ConflictSyncParams().rateless
        val salt = XxHash64.hashString("ns")

        // A has {1,2,3}; B has {2,3,4,5}
        val a = setOf(1L, 2L, 3L)
        val b = setOf(2L, 3L, 4L, 5L)

        val client = RatelessIblt(params, salt).also { it.localSet(a) }
        val server = RatelessIblt(params, salt).also { it.localSet(b) }

        var i = 0
        var result: DecodeResult? = null
        while (i < params.maxSymbols) {
            val symB = server.nextSymbol(i)
            client.absorb(symB)
            val r = client.tryDecode()
            if (r is DecodeResult.Done) { result = r; break }
            i++
        }

        val done = result as DecodeResult.Done
        assertEquals(listOf(4L, 5L), done.missingHere.sorted()) // present at B, absent at A
        assertEquals(listOf(1L), done.missingThere.sorted()) // present at A, absent at B
    }
}

