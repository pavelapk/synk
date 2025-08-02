package com.tap.synk.conflictsync

import kotlin.test.Test
import kotlin.test.assertTrue

class ConflictSyncBloomFilterTest {
    @Test
    fun bloomFilterRoundTrip() {
        val filter = SynkBloomFilter(capacity = 10, falsePositiveRate = 0.01)
        filter.add("apple")
        assertTrue(filter.contains("apple"))
        val bytes = filter.toByteArray()
        val restored = SynkBloomFilter.fromByteArray(bytes, filter.numHashFunctions, filter.capacity)
        assertTrue(restored.contains("apple"))
    }
}
