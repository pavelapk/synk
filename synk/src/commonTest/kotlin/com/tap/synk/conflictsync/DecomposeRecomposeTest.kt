package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.storageConfig
import com.tap.synk.outbound
import kotlin.test.Test
import kotlin.test.assertEquals

class DecomposeRecomposeTest {
    @Test
    fun decompose_then_recompose_invokes_onMerged() {
        val merged = mutableListOf<CRDT>()
        val synk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .onMerged(CRDT::class) { _, obj -> merged.add(obj) }
            .build()

        val obj = CRDT("1", "Alice", "Smith", 42)
        // ensure meta exists
        synk.outbound(obj, null)

        val adapters = synk.synkAdapterStore
        val metas = synk.factory
        val decomposer = Decomposer(adapters, metas)
        val recomposer = Recomposer(synk, adapters, metas)

        val ns = CRDT::class.qualifiedName!!
        val blocks = decomposer.decompose(ns, CRDT::class, obj)
        recomposer.applyInbound(blocks)

        assertEquals(1, merged.size)
        assertEquals(obj, merged.first())
    }
}
