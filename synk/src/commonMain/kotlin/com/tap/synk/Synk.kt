package com.tap.synk

import com.tap.hlc.HybridLogicalClock
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.config.ClockStorageConfiguration
import com.tap.synk.meta.store.InMemoryMetaStoreFactory
import com.tap.synk.meta.store.MetaStoreFactory
import com.tap.synk.relay.MessageSemigroup
import com.tap.synk.datasource.StateSource
import com.tap.synk.datasource.StateSourceRegistry
import com.tap.synk.util.OnMergedRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO

@OptIn(FlowPreview::class)
class Synk internal constructor(
    val clockStorageConfiguration: ClockStorageConfiguration,
    val factory: MetaStoreFactory = InMemoryMetaStoreFactory(),
    val synkAdapterStore: SynkAdapterStore = SynkAdapterStore(),
    val stateSourceRegistry: StateSourceRegistry = StateSourceRegistry(),
    val onMergedRegistry: OnMergedRegistry = OnMergedRegistry(),
) {
    internal val hlc: MutableStateFlow<HybridLogicalClock> = MutableStateFlow(loadClock())
    internal val merger: MessageSemigroup<Any> = MessageSemigroup(synkAdapterStore)

    private val hlcSynk: Flow<HybridLogicalClock> = hlc.debounce(200.milliseconds)
    private val synkScope = CoroutineScope(Dispatchers.IO)

    init {
        synkScope.launch(Dispatchers.IO) {
            hlcSynk.collectLatest { hlc ->
                storeClock(hlc)
            }
        }
    }

    fun finish() {
        synkScope.cancel()
    }

    data class Builder(private val storageConfiguration: ClockStorageConfiguration) {
        companion object Presets {}

        private var factory: MetaStoreFactory? = null
        private var onMergedRegistry: OnMergedRegistry = OnMergedRegistry()

        @PublishedApi
        internal var synkAdapterStore = SynkAdapterStore()
        internal var stateSourceRegistry = StateSourceRegistry()

        inline fun <reified T : Any> registerSynkAdapter(synkAdapter: SynkAdapter<T>) = apply {
            val clazz = T::class

// TODO Disabled for now, as sealed classes reflection are not supported in Kotlin Multiplatform
//            if (clazz.isSealed) {
//                clazz.sealedSubclasses.forEach { sealedClazz ->
//                    synkAdapterStore.register(sealedClazz as KClass<T>, synkAdapter)
//                }
//            }

            synkAdapterStore.register(clazz, synkAdapter)
        }

        fun metaStoreFactory(metaStoreFactory: MetaStoreFactory) = apply {
            factory = metaStoreFactory
        }

        fun <T : Any> onMerged(clazz: KClass<T>, callback: (namespace: String, obj: T) -> Unit) = apply {
            onMergedRegistry.register(clazz, callback)
        }

        inline fun <reified T : Any> onMerged(noinline callback: (namespace: String, obj: T) -> Unit) = apply {
            onMerged(T::class, callback)
        }

        fun <T : Any> registerStateSource(clazz: KClass<T>, source: StateSource<T>) = apply {
            stateSourceRegistry.register(clazz, source)
        }

        fun build(): Synk {
            return Synk(
                storageConfiguration,
                factory ?: InMemoryMetaStoreFactory(),
                synkAdapterStore,
                stateSourceRegistry,
                onMergedRegistry,
            )
        }
    }
}

internal fun Synk.loadClock(): HybridLogicalClock {
    return HybridLogicalClock.load(
        clockStorageConfiguration.filePath,
        clockStorageConfiguration.fileSystem,
        clockStorageConfiguration.clockFileName,
    ) ?: HybridLogicalClock()
}

internal fun Synk.storeClock(hybridLogicalClock: HybridLogicalClock) {
    return HybridLogicalClock.store(
        hybridLogicalClock,
        clockStorageConfiguration.filePath,
        clockStorageConfiguration.fileSystem,
        clockStorageConfiguration.clockFileName,
    )
}
