package com.tap.synk.conflict

import kotlin.reflect.KClass

internal class ConflictSyncRegistry(
    private val stateSources: MutableMap<KClass<*>, StateSource<Any>> = mutableMapOf(),
    private val mergeHandlers: MutableMap<KClass<*>, MergeHandler<Any>> = mutableMapOf(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveStateSource(clazz: KClass<T>): StateSource<T> {
        return stateSources[clazz] as? StateSource<T>
            ?: throw IllegalStateException("No StateSource registered for ${clazz.qualifiedName}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveMergeHandler(clazz: KClass<T>): MergeHandler<T>? {
        return mergeHandlers[clazz] as? MergeHandler<T>
    }

    @PublishedApi
    internal fun <T : Any> registerStateSource(clazz: KClass<T>, stateSource: StateSource<T>) {
        stateSources[clazz] = stateSource as StateSource<Any>
    }

    @PublishedApi
    internal fun <T : Any> registerMergeHandler(clazz: KClass<T>, handler: MergeHandler<T>) {
        mergeHandlers[clazz] = handler as MergeHandler<Any>
    }
}
