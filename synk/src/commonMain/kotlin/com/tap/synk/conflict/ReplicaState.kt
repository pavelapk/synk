package com.tap.synk.conflict

import com.github.michaelbull.result.getOr
import com.tap.hlc.HybridLogicalClock
import com.tap.synk.Synk
import com.tap.synk.inbound
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.adapter.encodeToFields
import com.tap.synk.adapter.encodeSnapshot
import com.tap.synk.adapter.decodeSnapshot
import com.tap.synk.adapter.decodeSnapshot
import com.tap.synk.meta.Meta
import com.tap.synk.meta.store.MetaStore
import com.tap.synk.relay.Message

interface StateSource<T : Any> {
    suspend fun scan(after: ObjectKey?, limit: Int): List<T>
    suspend fun byId(id: String): T?
}

fun interface MergeHandler<T : Any> {
    suspend fun merge(namespace: String, value: T)
}

class ReplicaState<T : Any> internal constructor(
    internal val namespace: String,
    private val synk: Synk,
    internal val stateSource: StateSource<T>,
    private val mergeHandler: MergeHandler<T>?,
    internal val adapter: SynkAdapter<T>,
    private val metaStore: MetaStore,
    private val decompositions: List<FieldDecomposition>,
) : Decompose<FieldDecomposition> {

    override fun split(): List<FieldDecomposition> = decompositions

    override suspend fun join(deltas: List<FieldDecomposition>) {
        if (deltas.isEmpty()) return
        val grouped = deltas.groupBy { it.objectKey }
        for ((objectKey, fields) in grouped) {
            val tip = stateSource.byId(objectKey.id)
            val encodedExisting = tip?.let { adapter.encode(it).toMutableMap() } ?: mutableMapOf()
            val updatedMeta = metaStore.getMeta(objectKey.id)?.toMutableMap() ?: mutableMapOf()

            fields.forEach { field ->
                val value = field.encodedValue.decodeToString()
                encodedExisting[field.field] = value
                updatedMeta[field.field] = field.hlc.toString()
            }

            val merged = adapter.decode(encodedExisting)
            val message = Message(
                crdt = merged,
                meta = Meta(
                    namespace = namespace,
                    timestampMeta = updatedMeta,
                ),
            )

            val inboundResult = synk.inbound(message, tip)
            mergeHandler?.merge(namespace, inboundResult)
        }
    }

    internal suspend fun snapshotBlock(
        objectKey: ObjectKey,
        fields: List<FieldDecomposition>,
    ): DecompositionBlock.SnapshotBlock? {
        val value = stateSource.byId(objectKey.id) ?: return null
        val encodedObject = adapter.encodeSnapshot(value)
        val updatedFields = if (fields.isEmpty()) {
            decompositions.filter { it.objectKey == objectKey }
        } else {
            fields
        }
        return DecompositionBlock.SnapshotBlock(
            namespace = namespace,
            objectKey = objectKey,
            encodedObject = encodedObject,
            fields = updatedFields,
        )
    }

    internal suspend fun applySnapshot(snapshot: DecompositionBlock.SnapshotBlock) {
        val decoded = adapter.decodeSnapshot(snapshot.encodedObject)
        val message = Message(
            crdt = decoded,
            meta = Meta(
                namespace = namespace,
                timestampMeta = snapshot.fields.associate { it.field to it.hlc.toString() },
            ),
        )
        val tip = stateSource.byId(snapshot.objectKey.id)
        val inboundResult = synk.inbound(message, tip)
        mergeHandler?.merge(namespace, inboundResult)
    }

    companion object {
        suspend fun <T : Any> build(
            namespace: String,
            synk: Synk,
            stateSource: StateSource<T>,
            mergeHandler: MergeHandler<T>?,
            adapter: SynkAdapter<T>,
            metaStore: MetaStore,
            pageSize: Int,
        ): ReplicaState<T> {
            val decompositions = collectDecompositions(namespace, stateSource, adapter, metaStore, pageSize)
            return ReplicaState(
                namespace = namespace,
                synk = synk,
                stateSource = stateSource,
                mergeHandler = mergeHandler,
                adapter = adapter,
                metaStore = metaStore,
                decompositions = decompositions,
            )
        }

        private suspend fun <T : Any> collectDecompositions(
            namespace: String,
            stateSource: StateSource<T>,
            adapter: SynkAdapter<T>,
            metaStore: MetaStore,
            pageSize: Int,
        ): List<FieldDecomposition> {
            val collected = mutableListOf<FieldDecomposition>()
            var after: ObjectKey? = null
            do {
                val page = stateSource.scan(after, pageSize)
                if (page.isEmpty()) {
                    break
                }
                page.forEach { obj ->
                    val objectId = adapter.resolveId(obj)
                    val objectKey = ObjectKey(namespace, objectId)
                    val hlcCatalog = metaStore.readFields(objectId)
                    val encodedFields = adapter.encodeToFields(obj)
                    encodedFields.forEach { field ->
                        val hlc = hlcCatalog[field.name] ?: HybridLogicalClock()
                        collected += FieldDecomposition(
                            namespace = namespace,
                            objectKey = objectKey,
                            field = field.name,
                            encodedValue = field.bytes,
                            hlc = hlc,
                        )
                    }
                }
                val last = page.lastOrNull()
                after = last?.let { ObjectKey(namespace, adapter.resolveId(it)) }
            } while (page.size == pageSize)
            return collected
        }
    }
}
internal fun MetaStore.readFields(id: String): Map<String, HybridLogicalClock> {
    return getMeta(id)?.mapValues { entry ->
        HybridLogicalClock.decodeFromString(entry.value).getOr(HybridLogicalClock())
    } ?: emptyMap()
}
