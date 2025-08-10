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
    /** Group blocks by (namespace,id), rebuild CRDTs, and merge via inbound() with old if available. */
    suspend fun applyInbound(blocks: List<Block>) {
        if (blocks.isEmpty()) return

        val groups = blocks.groupBy { it.key.namespace to it.key.id }
        for ((nsId, group) in groups) {
            val (namespace, _) = nsId
            val adapter = adapters.resolve(namespace)
            val crdtMap = group.associate { it.key.field to it.value }
            val metaMap = group.associate { it.key.field to it.hlc }
            val crdt = adapter.decode(crdtMap)
            val id = adapter.resolveId(crdt)

            // Load old if we can; if no StateSource is registered, treat as new
            val old: Any? = try {
                synk.stateSourceRegistry.resolve(namespace).byId(id)
            } catch (_: Throwable) { null }

            // Merge using existing merge semantics (inbound updates meta store itself)
            val message = Message(crdt, Meta(crdt::class.qualifiedName ?: "", metaMap))
            val merged = synk.inbound(message, old)

            // Allow host to persist merged CRDT if configured (type-specific)
            synk.onMergedRegistry.resolve(namespace)?.invoke(namespace, merged)
        }
    }
}
