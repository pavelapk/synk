package com.tap.synk.sync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.meta.store.MetaStore

enum class SyncRole {
    INITIATOR,
    RESPONDER,
}

interface SyncStrategyFactory {
    fun <T : Any> createSession(role: SyncRole, context: SyncContext<T>): SyncSession<T>
}

interface SyncSession<T : Any> {
    suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration = SyncConfiguration.Default,
    ): SyncStats
}

interface SyncContext<T : Any> {
    val adapter: SynkAdapter<T>
    val stateSource: SyncStateSource<T>
    val mergeHandler: SyncMergeHandler<T>?
    val synk: Synk
    val metaStore: MetaStore
    val namespace: String
    suspend fun warm()
}

interface SyncStateSource<T : Any> {
    suspend fun scan(afterId: String?, limit: Int): List<T>
    suspend fun load(id: String): T?
}

fun interface SyncMergeHandler<T : Any> {
    suspend fun merge(value: T)
}

sealed class SyncTransferEvent {
    abstract val stateBytes: Int
    abstract val metadataBytes: Int

    data class LocalToRemote(
        override val stateBytes: Int,
        override val metadataBytes: Int,
    ) : SyncTransferEvent()

    data class RemoteToLocal(
        override val stateBytes: Int,
        override val metadataBytes: Int,
    ) : SyncTransferEvent()

    val totalBytes: Int get() = stateBytes + metadataBytes
}

interface SyncTracker {
    fun isReady(): Boolean
    fun register(event: SyncTransferEvent)
    fun finish()
}

data class SyncStats(
    val namespace: String,
    val scanned: Int,
    val outboundMessages: Int,
    val outboundBytes: Long,
    val inboundMessages: Int,
    val inboundBytes: Long,
)

data class SyncConfiguration(
    val pageSize: Int = 256,
    val options: Map<StrategyOption<*>, Any?> = emptyMap(),
) {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    companion object {
        val Default = SyncConfiguration()
    }
}

interface StrategyOption<T : Any>

class DefaultSyncTracker(
) : SyncTracker {
    private val events = mutableListOf<SyncTransferEvent>()
    private var finished = false

    fun events(): List<SyncTransferEvent> = events.toList()

    override fun isReady(): Boolean = events.isEmpty() && !finished

    override fun register(event: SyncTransferEvent) {
        if (!finished) {
            events += event
        }
    }

    override fun finish() {
        finished = true
    }
}
