package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.rateless.DecodeResult
import com.tap.synk.conflictsync.rateless.RatelessIblt
import com.tap.synk.conflictsync.rateless.RatelessParams
import kotlin.test.Test
import kotlin.test.assertEquals

class RatelessIbltTest {
    @Test
    fun rateless_decodes_small_delta() {
        val A = setOf(1L, 2L, 3L, 5L, 8L)
        val B = setOf(2L, 3L, 5L, 8L, 13L)

        val params = RatelessParams(ibltCellsPerSymbol = 256, ibltHashFunctions = 3, maxSymbols = 256)
        val salt = 0xA1B2C3D4L

        val aSide = RatelessIblt(params, sessionSalt = salt)
        aSide.localSet(A)

        val bSide = RatelessIblt(params, sessionSalt = salt)
        bSide.localSet(B)

        var done: DecodeResult.Done? = null
        var i = 0
        while (i < params.maxSymbols && done == null) {
            val symB = bSide.nextSymbol(i)
            aSide.absorb(symB)
            when (val res = aSide.tryDecode()) {
                is DecodeResult.NeedMore -> {}
                is DecodeResult.Done -> done = res
            }
            i++
        }
        check(done != null)
        assertEquals(listOf(13L), done!!.missingHere.sorted())
        assertEquals(listOf(1L), done!!.missingThere.sorted())
    }
}
