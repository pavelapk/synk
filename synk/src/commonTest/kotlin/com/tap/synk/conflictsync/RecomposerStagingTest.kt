package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.conflictsync.model.Block
import com.tap.synk.conflictsync.model.BlockKey
import com.tap.synk.config.storageConfig
import com.tap.synk.datasource.StateSource
import kotlinx.coroutines.flow.Flow
import com.tap.synk.recordChange
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RecomposerStagingTest {
    private val ns = CRDT::class.qualifiedName!!

    @Test
    fun new_object_partial_stays_staged_until_eos() = runBlocking {
        val db = mutableListOf<CRDT>()
        val synk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(db))
            .onMerged(CRDT::class) { _, obj -> upsert(db, obj) }
            .build()

        val r = Recomposer(synk, synk.synkAdapterStore, synk.factory)

        // Stage only one field for a new id=10 → cannot decode yet
        r.applyInbound(listOf(
            Block(BlockKey(ns, "10", "name"), "Alice", "t1"),
        ))
        assertEquals(emptyList<CRDT>(), db)

        // Supply the missing required fields → decode and persist
        r.applyInbound(listOf(
            Block(BlockKey(ns, "10", "id"), "10", "t2"),
            Block(BlockKey(ns, "10", "last_name"), "Smith", "t3"),
        ))

        assertEquals(listOf(CRDT("10", "Alice", "Smith", null)), db)
    }

    @Test
    fun existing_object_partial_overlays_immediately() = runBlocking {
        val db = mutableListOf(CRDT("1", "Old", "X", null))
        val synk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(db))
            .onMerged(CRDT::class) { _, obj -> upsert(db, obj) }
            .build()
        // Ensure meta exists for the existing object
        synk.recordChange(db.first(), null)

        val r = Recomposer(synk, synk.synkAdapterStore, synk.factory)

        // Only last_name changes; should decode and merge immediately using baseMap from old
        r.applyInbound(listOf(
            Block(BlockKey(ns, "1", "last_name"), "Y", "t1"),
        ))

        assertEquals(listOf(CRDT("1", "Old", "Y", null)), db)
    }

    private fun listSource(list: List<CRDT>): StateSource<CRDT> = object : StateSource<CRDT> {
        override suspend fun all(): Flow<CRDT> = flow { list.forEach { emit(it) } }
        override suspend fun byId(id: String): CRDT? = list.find { it.id == id }
    }

    private fun upsert(list: MutableList<CRDT>, obj: CRDT) {
        val idx = list.indexOfFirst { it.id == obj.id }
        if (idx >= 0) list[idx] = obj else list.add(obj)
    }
}
