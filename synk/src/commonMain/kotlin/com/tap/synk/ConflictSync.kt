package com.tap.synk

import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.conflict.ConflictSyncBloomRateless
import com.tap.synk.conflict.ConflictSyncConfiguration
import com.tap.synk.conflict.ConflictSyncResponder
import com.tap.synk.conflict.ConflictSyncStats
import com.tap.synk.conflict.ConflictSyncTracker
import com.tap.synk.conflict.ConflictSyncTransport
import com.tap.synk.conflict.MergeHandler
import com.tap.synk.conflict.ReplicaState
import com.tap.synk.conflict.StateSource
import kotlin.reflect.KClass

suspend fun <T : Any> Synk.conflictSync(
    namespace: KClass<T>,
    transport: ConflictSyncTransport,
    configuration: ConflictSyncConfiguration = ConflictSyncConfiguration(),
    tracker: ConflictSyncTracker = configuration.newTracker(),
): ConflictSyncStats {
    val replica = buildReplicaState(namespace, configuration)
    val driver = ConflictSyncBloomRateless<T>(
        fpr = configuration.fpr,
        hasher = configuration.hasher,
        ratelessBatchSize = configuration.ratelessBatchSize,
    )
    return driver.sync(replica, transport, tracker)
}

suspend inline fun <reified T : Any> Synk.conflictSync(
    transport: ConflictSyncTransport,
    configuration: ConflictSyncConfiguration = ConflictSyncConfiguration(),
    tracker: ConflictSyncTracker = configuration.newTracker(),
): ConflictSyncStats = conflictSync(T::class, transport, configuration, tracker)

suspend fun <T : Any> Synk.respondConflictSync(
    namespace: KClass<T>,
    transport: ConflictSyncTransport,
    configuration: ConflictSyncConfiguration = ConflictSyncConfiguration(),
    tracker: ConflictSyncTracker = configuration.newTracker(),
): ConflictSyncStats {
    val replica = buildReplicaState(namespace, configuration)
    val driver = ConflictSyncResponder<T>(
        fpr = configuration.fpr,
        hasher = configuration.hasher,
        ratelessBatchSize = configuration.ratelessBatchSize,
    )
    return driver.sync(replica, transport, tracker)
}

suspend inline fun <reified T : Any> Synk.respondConflictSync(
    transport: ConflictSyncTransport,
    configuration: ConflictSyncConfiguration = ConflictSyncConfiguration(),
    tracker: ConflictSyncTracker = configuration.newTracker(),
): ConflictSyncStats = respondConflictSync(T::class, transport, configuration, tracker)

private suspend fun <T : Any> Synk.buildReplicaState(
    namespace: KClass<T>,
    configuration: ConflictSyncConfiguration,
): ReplicaState<T> {
    val namespaceName = namespace.qualifiedName
        ?: throw IllegalArgumentException("Namespace must have a qualified name")

    @Suppress("UNCHECKED_CAST")
    val adapter = synkAdapterStore.resolve(namespace) as SynkAdapter<T>
    val stateSource: StateSource<T> = conflictSyncRegistry.resolveStateSource(namespace)
    val mergeHandler: MergeHandler<T>? = conflictSyncRegistry.resolveMergeHandler(namespace)
    val metaStore = factory.getStore(namespace)
    metaStore.warm()

    return ReplicaState.build(
        namespace = namespaceName,
        synk = this,
        stateSource = stateSource,
        mergeHandler = mergeHandler,
        adapter = adapter,
        metaStore = metaStore,
        pageSize = configuration.pageSize,
    )
}
