package com.tap.synk

import com.github.michaelbull.result.getOr
import com.tap.hlc.HybridLogicalClock
import com.tap.synk.adapter.diff
import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.meta.Meta
import com.tap.synk.meta.meta
import com.tap.synk.meta.store.MetaStoreFactory
import com.tap.synk.relay.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Record a change in a CRDT updating Synk's metadata store and ticking the hybrid logical clock.
 *
 * This should be called after persisting local changes. Applications may later call [message]
 * to obtain a payload for transmission.
 */
fun <T : Any> Synk.recordChange(new: T, old: T? = null) {
    synkRecordChange(new, old, hlc, synkAdapterStore, factory)
}

/**
 * Create a [Message] for a CRDT using the previously recorded metadata.
 *
 * This does not tick the hybrid logical clock.
 */
fun <T : Any> Synk.message(crdt: T): Message<T> {
    return synkCreateMessage(crdt, synkAdapterStore, factory)
}

/**
 * Convenience function that records a change and immediately creates a [Message] for propagation.
 */
fun <T : Any> Synk.outbound(new: T, old: T? = null): Message<T> {
    recordChange(new, old)
    return message(new)
}

internal fun <T : Any> synkRecordChange(
    new: T,
    old: T?,
    hlc: MutableStateFlow<HybridLogicalClock>,
    adapterStore: SynkAdapterStore,
    factory: MetaStoreFactory,
) {
    val synkAdapter = adapterStore.resolve(new::class)
    val metaStore = factory.getStore(new::class)
    val id = synkAdapter.resolveId(old ?: new)

    hlc.update { atomicHlc ->
        HybridLogicalClock.localTick(atomicHlc).getOr(atomicHlc)
    }

    val newMetaMap = old?.let { previous ->
        val oldMetaMap = metaStore.getMeta(id) ?: throw Exception("Failed to find meta for provided old value")
        val diff = synkAdapter.diff(previous, new)

        val updated = HashMap<String, String>()
        oldMetaMap.entries.forEach { entry ->
            val value = if (diff.contains(entry.key)) {
                hlc.value.toString()
            } else {
                entry.value
            }
            updated[entry.key] = value
        }
        updated
    } ?: meta(new, synkAdapter, hlc.value).timestampMeta

    metaStore.putMeta(id, newMetaMap)
}

internal fun <T : Any> synkCreateMessage(
    crdt: T,
    adapterStore: SynkAdapterStore,
    factory: MetaStoreFactory,
): Message<T> {
    val synkAdapter = adapterStore.resolve(crdt::class)
    val metaStore = factory.getStore(crdt::class)
    val id = synkAdapter.resolveId(crdt)
    val metaMap = metaStore.getMeta(id) ?: throw Exception("Failed to find meta for provided crdt")
    val meta = Meta(crdt::class.qualifiedName!!, metaMap)
    return Message(crdt, meta)
}

