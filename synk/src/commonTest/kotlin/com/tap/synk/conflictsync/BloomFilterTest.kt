package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.prefilter.BloomFilter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BloomFilterTest {
    @Test
    fun bloom_partition_behaviour() {
        val bits = 1 shl 12
        val k = 3
        val bf = BloomFilter(bits, k)
        val present = (0L until 100L).toSet()
        present.forEach { bf.add(it) }

        // All inserted should be possibly present
        present.forEach { h -> assertTrue(bf.mightContain(h)) }

        // Some non-inserted should be rejected
        val notPresent = (200L until 260L).toSet()
        val rejections = notPresent.count { !bf.mightContain(it) }
        assertTrue(rejections > 0) // at least some definitive negatives
        // And some may be false positives; we don't assert rate here
        // Do not assert presence of false positives; probabilistic nature may reject all
    }
}
