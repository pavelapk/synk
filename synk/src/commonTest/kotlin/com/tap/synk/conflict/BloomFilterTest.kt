package com.tap.synk.conflict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BloomFilterTest {

    @Test
    fun `payload round trip preserves configuration`() {
        val fingerprints = listOf(
            FieldFingerprint(byteArrayOf(1, 2, 3)),
            FieldFingerprint(byteArrayOf(4, 5, 6)),
        )
        val hasher = Hasher64.xxHash64()
        val filter = BloomFilter.build(fingerprints, fpr = 0.05, hasher = hasher)

        val payload = filter.toPayload(namespace = "test")
        assertTrue(payload.hashCount > 0)
        assertNotEquals(0L, payload.hashSeed1)
        assertNotEquals(0L, payload.hashSeed2)

        val restored = BloomFilter.fromPayload(payload, hasher)
        fingerprints.forEach { fingerprint ->
            assertTrue(restored.mightContain(fingerprint))
        }
        assertEquals(filter.serializedSize(), restored.serializedSize())
    }
}
