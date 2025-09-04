package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.inbound
import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.meta.Meta
import com.tap.synk.meta.store.MetaStoreFactory
import com.tap.synk.relay.Message
import com.tap.synk.conflictsync.model.Block

data class RecomposeApplyStats(
    val finalizedDelta: Int,
    val stagedBlocksDelta: Int,
    val currentStagedCount: Int,
)

internal class Recomposer(
    private val synk: Synk,
    private val adapters: SynkAdapterStore,
    private val metas: MetaStoreFactory,
) {
    // Staging buffers by (namespace,id)
    private val staging = mutableMapOf<Pair<String, String>, MutableMap<String, String>>()
    private val stagingMeta = mutableMapOf<Pair<String, String>, MutableMap<String, String>>()

    /** Stage blocks by (namespace,id). Try to finalize each group; if decode fails, keep staged. */
    suspend fun applyInbound(blocks: List<Block>): RecomposeApplyStats {
        if (blocks.isEmpty()) return RecomposeApplyStats(0, 0, staging.size)
        val groups = blocks.groupBy { it.key.namespace to it.key.id }
        var finalized = 0
        var stagedBlocks = 0
        for ((nsId, group) in groups) {
            val incoming = staging.getOrPut(nsId) { mutableMapOf() }
            val metaIncoming = stagingMeta.getOrPut(nsId) { mutableMapOf() }
            for (b in group) {
                incoming[b.key.field] = b.value
                metaIncoming[b.key.field] = b.hlc
                stagedBlocks++
            }
            if (tryFinalize(nsId)) finalized++
        }
        return RecomposeApplyStats(finalized, stagedBlocks, staging.size)
    }

    /** Attempt to decode and merge a staged object. If decode fails (missing fields), leave staged. */
    private suspend fun tryFinalize(nsId: Pair<String, String>): Boolean {
        val (namespace, id) = nsId
        val adapter = adapters.resolve(namespace)
        val incomingMap = staging[nsId] ?: return false
        val metaMap = stagingMeta[nsId] ?: return false

        val old: Any? = try {
            synk.stateSourceRegistry.resolve(namespace).byId(id)
        } catch (_: Throwable) { null }

        val baseMap: Map<String, String> = if (old != null) adapter.encode(old) else emptyMap()
        val mergedMap = baseMap + incomingMap

        val crdt = runCatching { adapter.decode(mergedMap) }.getOrNull() ?: return false

        val message = Message(crdt, Meta(crdt::class.qualifiedName ?: "", metaMap.toMap()))
        val merged = synk.inbound(message, old)
        synk.onMergedRegistry.resolve(namespace)?.invoke(namespace, merged)

        // Clear staging once successfully applied
        staging.remove(nsId)
        stagingMeta.remove(nsId)
        return true
    }
}
