package com.tap.synk.meta

import com.tap.synk.CMap
import com.tap.synk.meta.store.InMemoryMetaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class InMemoryMetaStoreTest {

    @Test
    fun `can save and retrieve meta maps from store`() {
        val metaStore = InMemoryMetaStore(CMap())

        val key = Uuid.random().toString()
        val meta = HashMap<String, String>().apply {
            put("name", "123456789")
            put("phone", "234567890")
        }

        metaStore.putMeta(key, meta)
        val result = metaStore.getMeta(key)

        assertNotNull(result)
        assertEquals(meta, result)
    }
}
