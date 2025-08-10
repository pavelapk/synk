package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.prefilter.BloomFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashAndBloomIntegrationTest {
    @Test
    fun blockDigest_is_deterministic_and_distinguishes_fields() {
        val seed = 1234L
        val ns = "ns"
        val id = "id1"
        val hlc = "t@1"
        val h1a = blockDigest(ns, id, "name", "Alice", hlc, seed)
        val h1b = blockDigest(ns, id, "name", "Alice", hlc, seed)
        val h2 = blockDigest(ns, id, "last_name", "Alice", hlc, seed)
        assertEquals(h1a, h1b)
        assertNotEquals(h1a, h2)
    }

    @Test
    fun bloom_contains_added_hashes() {
        val bf = BloomFilter(256, 3)
        val h = blockDigest("n", "i", "f", "v", "t", 42)
        bf.add(h)
        assertTrue(bf.mightContain(h))
    }
}

