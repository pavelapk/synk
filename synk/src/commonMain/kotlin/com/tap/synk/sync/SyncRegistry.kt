package com.tap.synk.sync

import kotlin.reflect.KClass

internal class SyncRegistry(
    private val stateSources: MutableMap<KClass<*>, SyncStateSource<Any>> = mutableMapOf(),
    private val mergeHandlers: MutableMap<KClass<*>, SyncMergeHandler<Any>> = mutableMapOf(),
    private var strategyFactory: SyncStrategyFactory? = null,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveStateSource(clazz: KClass<T>): SyncStateSource<T> {
        return stateSources[clazz] as? SyncStateSource<T>
            ?: throw IllegalStateException("No SyncStateSource registered for ${clazz.qualifiedName}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveMergeHandler(clazz: KClass<T>): SyncMergeHandler<T>? {
        return mergeHandlers[clazz] as? SyncMergeHandler<T>
    }

    @PublishedApi
    internal fun <T : Any> registerStateSource(clazz: KClass<T>, stateSource: SyncStateSource<T>) {
        stateSources[clazz] = stateSource as SyncStateSource<Any>
    }

    @PublishedApi
    internal fun <T : Any> registerMergeHandler(clazz: KClass<T>, handler: SyncMergeHandler<T>) {
        mergeHandlers[clazz] = handler as SyncMergeHandler<Any>
    }

    fun defaultStrategyFactory(): SyncStrategyFactory? = strategyFactory

    @PublishedApi
    internal fun registerDefaultStrategy(factory: SyncStrategyFactory) {
        strategyFactory = factory
    }
}
