package com.tap.synk.sync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.meta.store.MetaStore
import kotlin.reflect.KClass

suspend fun <T : Any> Synk.sync(
    namespace: KClass<T>,
    role: SyncRole,
    transport: SyncTransport,
    strategy: SyncStrategyFactory? = null,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    tracker: SyncTracker = defaultSyncTracker(),
): SyncStats {
    val factory = strategy ?: syncRegistry.defaultStrategyFactory()
        ?: throw IllegalStateException(
            "No SyncStrategyFactory provided. Pass one explicitly or register a default on Synk.Builder",
        )
    val context = buildSyncContext(namespace)
    context.warm()
    val session = factory.createSession(role, context)
    return session.run(
        transport = transport,
        tracker = tracker,
        configuration = configuration,
    )
}

suspend inline fun <reified T : Any> Synk.sync(
    role: SyncRole,
    transport: SyncTransport,
    strategy: SyncStrategyFactory? = null,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    tracker: SyncTracker = defaultSyncTracker(),
): SyncStats = sync(T::class, role, transport, strategy, configuration, tracker)

private fun <T : Any> Synk.buildSyncContext(namespace: KClass<T>): SyncContext<T> {
    val namespaceName = namespace.qualifiedName
        ?: throw IllegalArgumentException("Namespace must have a qualified name")

    @Suppress("UNCHECKED_CAST")
    val adapter = synkAdapterStore.resolve(namespace) as SynkAdapter<T>
    val stateSource = syncRegistry.resolveStateSource(namespace)
    val mergeHandler = syncRegistry.resolveMergeHandler(namespace)
    val metaStore = factory.getStore(namespace)

    return DefaultSyncContext(
        adapter = adapter,
        stateSource = stateSource,
        mergeHandler = mergeHandler,
        synk = this,
        metaStore = metaStore,
        adapterKey = namespaceName,
    )
}

fun defaultSyncTracker(): SyncTracker =
    DefaultSyncTracker(
        downloadBandwidth = Bandwidth.Mbps(1.0),
        uploadBandwidth = Bandwidth.Mbps(1.0),
    )

private class DefaultSyncContext<T : Any>(
    override val adapter: SynkAdapter<T>,
    override val stateSource: SyncStateSource<T>,
    override val mergeHandler: SyncMergeHandler<T>?,
    override val synk: Synk,
    override val metaStore: MetaStore,
    override val adapterKey: String,
) : SyncContext<T> {
    override suspend fun warm() {
        metaStore.warm()
    }
}
