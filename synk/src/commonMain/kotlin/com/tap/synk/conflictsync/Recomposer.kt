package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.inbound
import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.meta.Meta
import com.tap.synk.meta.store.MetaStoreFactory
import com.tap.synk.relay.Message
import com.tap.synk.conflictsync.model.Block

internal class Recomposer(
    private val synk: Synk,
    private val adapters: SynkAdapterStore,
    private val metas: MetaStoreFactory,
) {
    /** Group blocks by (namespace,id), rebuild CRDTs, install meta, and merge via inbound(). */
    fun applyInbound(blocks: List<Block>) {
        if (blocks.isEmpty()) return

        val groups = blocks.groupBy { it.key.namespace to it.key.id }
        for ((nsId, group) in groups) {
            val (namespace, _) = nsId
            val adapter = adapters.resolve(namespace)
            val crdtMap = group.associate { it.key.field to it.value }
            val metaMap = group.associate { it.key.field to it.hlc }
            val crdt = adapter.decode(crdtMap)
            val id = adapter.resolveId(crdt)

            // Install meta before merging so inbound has correct clocks
            metas.getStore(crdt::class).putMeta(id, metaMap)

            // Merge using existing merge semantics
            val message = Message(crdt, Meta(crdt::class.qualifiedName ?: "", metaMap))
            val merged = synk.inbound(message, old = null)

            // Allow host to persist merged CRDT if configured (type-specific)
            synk.onMergedRegistry.resolve(namespace)?.invoke(namespace, merged)
        }
    }
}
