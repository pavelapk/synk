package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncContext
import com.tap.synk.sync.SyncSession
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.SyncTracker
import com.tap.synk.sync.instrument
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

internal class BloomRatelessResponderSession<T : Any>(
    private val context: SyncContext<T>,
    private val json: Json,
) : SyncSession<T> {

    override suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration,
    ): SyncStats = coroutineScope {
        context.warm()
        val replica = ReplicaState.build(
            namespace = context.namespace,
            synk = context.synk,
            stateSource = context.stateSource,
            mergeHandler = context.mergeHandler,
            adapter = context.adapter,
            metaStore = context.metaStore,
            pageSize = configuration.pageSize,
        )
        val instrumentedTransport = transport.instrument(tracker)
        val transportAdapter = SyncTransportAdapter(instrumentedTransport, json)
        val strategyConfig = configuration.resolveBloomRatelessConfig()
        val stats = ConflictSyncResponder<T>(
            fpr = strategyConfig.falsePositiveRate,
            hasher = strategyConfig.hasher,
            ratelessBatchSize = strategyConfig.ratelessBatchSize,
        ).sync(replica, transportAdapter, tracker)
        instrumentedTransport.snapshot(
            namespace = context.namespace,
            scanned = stats.scanned,
        )
    }
}
