package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.storageConfig
import com.tap.synk.datasource.StateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals

class StateSourceRegistryTest {
    @Test
    fun can_register_and_resolve_state_source() {
        val src = object : StateSource<CRDT> {
            override suspend fun all(): Flow<CRDT> = flow { }
            override suspend fun byId(id: String): CRDT? = null
        }

        val synk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, src)
            .build()

        val resolved = synk.stateSourceRegistry.resolve(CRDT::class)
        // Same instance
        assertEquals(src, resolved)
    }
}
