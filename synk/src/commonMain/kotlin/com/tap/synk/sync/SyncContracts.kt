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

data class SyncTransferEvent(
    val direction: Direction,
    val bytes: Long,
) {
    enum class Direction {
        LOCAL_TO_REMOTE,
        REMOTE_TO_LOCAL,
    }
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
    private var uploadedBytes: Long = 0
    private var downloadedBytes: Long = 0
    private var finished = false

    fun uploads(): Long = uploadedBytes
    fun downloads(): Long = downloadedBytes

    override fun isReady(): Boolean = uploadedBytes == 0L && downloadedBytes == 0L && !finished

    override fun register(event: SyncTransferEvent) {
        if (!finished) {
            when (event.direction) {
                SyncTransferEvent.Direction.LOCAL_TO_REMOTE -> uploadedBytes += event.bytes
                SyncTransferEvent.Direction.REMOTE_TO_LOCAL -> downloadedBytes += event.bytes
            }
        }
    }

    override fun finish() {
        finished = true
    }
}
