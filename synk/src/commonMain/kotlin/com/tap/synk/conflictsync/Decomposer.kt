package com.tap.synk.conflictsync

import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.meta.store.MetaStoreFactory
import com.tap.synk.conflictsync.model.Block
import com.tap.synk.conflictsync.model.BlockKey
import kotlin.reflect.KClass

internal class Decomposer(
    private val adapters: SynkAdapterStore,
    private val metas: MetaStoreFactory,
)
{
    fun <T : Any> decompose(namespace: String, clazz: KClass<T>, crdt: T): List<Block> {
        val adapter = adapters.resolve(clazz)
        val id = adapter.resolveId(crdt)
        val meta = metas.getStore(clazz).getMeta(id)
            ?: error("No meta for $namespace/$id; call Synk.recordChange before syncing.")

        val encoded = adapter.encode(crdt)
        return encoded.map { (field, value) ->
            Block(BlockKey(namespace, id, field), value, meta[field] ?: "")
        }
    }
}

