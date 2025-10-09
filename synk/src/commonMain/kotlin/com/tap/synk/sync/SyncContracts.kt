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
    val adapterKey: String
    suspend fun warm()
}

interface SyncStateSource<T : Any> {
    suspend fun scan(afterId: String?, limit: Int): List<T>
    suspend fun load(id: String): T?
}

fun interface SyncMergeHandler<T : Any> {
    suspend fun merge(value: T)
}

sealed class Bandwidth {
    abstract fun bytesPerSecond(): Double

    data class Kbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e3 / 8
    }

    data class Mbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e6 / 8
    }

    data class Gbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e9 / 8
    }
}

sealed class SyncTransferEvent {
    abstract val stateBytes: Int
    abstract val metadataBytes: Int

    data class LocalToRemote(
        override val stateBytes: Int,
        override val metadataBytes: Int,
        val upload: Bandwidth,
    ) : SyncTransferEvent()

    data class RemoteToLocal(
        override val stateBytes: Int,
        override val metadataBytes: Int,
        val download: Bandwidth,
    ) : SyncTransferEvent()

    val totalBytes: Int get() = stateBytes + metadataBytes

    fun estimatedDurationSeconds(): Double? {
        val bandwidth = when (this) {
            is LocalToRemote -> upload.bytesPerSecond()
            is RemoteToLocal -> download.bytesPerSecond()
        }
        return if (bandwidth > 0) totalBytes / bandwidth else null
    }
}

interface SyncTracker {
    fun isReady(): Boolean
    fun register(event: SyncTransferEvent)
    fun finish()
    val uploadBandwidth: Bandwidth
    val downloadBandwidth: Bandwidth
}

data class SyncStats(
    val adapterKey: String,
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
    override val downloadBandwidth: Bandwidth,
    override val uploadBandwidth: Bandwidth,
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
