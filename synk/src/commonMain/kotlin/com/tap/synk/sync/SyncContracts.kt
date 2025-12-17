@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.tap.synk.sync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.meta.store.MetaStore
import kotlin.time.Instant

enum class SyncRole {
    INITIATOR,
    RESPONDER,
}

enum class SyncMode {
    FULL,
    INCREMENTAL_SINCE_CURSOR,
}

object SyncOptions {
    /**
     * Controls whether the strategy should perform a full sync or an incremental sync (since a cursor).
     *
     * Not every strategy is required to support this option.
     */
    data object Mode : StrategyOption<SyncMode>

    /**
     * Overrides the local "since" cursor sent to the remote during the handshake.
     *
     * When not provided, strategies typically use a cursor stored in the local [MetaStore].
     */
    data object Since : StrategyOption<Instant>

    /**
     * Identifies the local replica (e.g., clientId/deviceId) for per-peer cursor tracking.
     *
     * If omitted, incremental sync can still work for a single fixed peer, but the responder (server)
     * cannot safely maintain separate cursors for multiple clients.
     */
    data object PeerId : StrategyOption<String>

    /**
     * Identifies the remote peer when initiating a session, to select which stored cursor to send as `since`.
     *
     * Useful when a single local replica syncs with multiple remotes.
     */
    data object RemotePeerId : StrategyOption<String>
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

    /**
     * Returns entities updated at or after the provided cursor.
     *
     * The default implementation falls back to [scan] (i.e., no incremental optimization).
     * Implementations can override this method to efficiently query only changed entities.
     */
    suspend fun scanUpdated(since: Instant?, afterId: String?, limit: Int): List<T> = scan(afterId, limit)
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
