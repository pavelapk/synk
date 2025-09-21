package com.tap.synk.conflict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatelessIBLTTest {

    @Test
    fun `streamed symbols decode set differences`() {
        val hasher = Hasher64.xxHash64()
        val responderSource = RatelessIBLT.fromSymbols(listOf(2L, 3L, 4L), hasher)
        val responderSketch = RatelessIBLT(hasher)
        val initiator = RatelessIBLT.fromSymbols(listOf(1L, 2L, 3L), hasher)

        while (!initiator.isDecoded()) {
            val batch = responderSource.emitNext(1)
            if (batch.isEmpty()) break
            responderSketch.appendSymbols(batch)
            initiator.ensureSketchSize(responderSketch.size())
            initiator.subtract(responderSketch)
        }

        assertTrue(initiator.isDecoded())
        assertEquals(listOf(1L), initiator.getLocalOnlySymbols().sorted())
        assertEquals(listOf(4L), initiator.getRemoteOnlySymbols().sorted())
    }
}
