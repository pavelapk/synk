@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.tap.synk.strategy.fullstate

import com.tap.synk.inbound
import com.tap.synk.message
import com.tap.synk.meta.store.getLastSyncCursor
import com.tap.synk.meta.store.putLastSyncCursor
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncContext
import com.tap.synk.sync.SyncMode
import com.tap.synk.sync.SyncOptions
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncSession
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncStrategyFactory
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.SyncTracker
import com.tap.synk.sync.instrument
import com.tap.synk.relay.decodeToMessage
import com.tap.synk.relay.encodeAsString
import com.tap.synk.sync.InstrumentedSyncTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json

object FullStateStrategyFactory : SyncStrategyFactory {
    override fun <T : Any> createSession(
        role: SyncRole,
        context: SyncContext<T>,
    ): SyncSession<T> {
        return FullStateSession(context, fullStateJson, role)
    }
}

internal val fullStateJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private class FullStateSession<T : Any>(
    private val context: SyncContext<T>,
    private val json: Json,
    private val role: SyncRole,
) : SyncSession<T> {

    override suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration,
    ): SyncStats = coroutineScope {
        context.warm()

        val instrumented = transport.instrument(tracker)

        when (configuration.resolveSyncMode()) {
            SyncMode.FULL -> runFull(configuration, instrumented)
            SyncMode.INCREMENTAL_SINCE_CURSOR -> runIncremental(configuration, instrumented)
        }
    }

    private suspend fun runFull(
        configuration: SyncConfiguration,
        transport: InstrumentedSyncTransport,
    ): SyncStats {
        var scanned = 0
        val outboundFlow = outboundRequests(
            configuration = configuration,
            since = null,
            incremental = false,
            onScanned = { scanned += it },
        )

        exchangeAndApply(
            transport = transport,
            outbound = outboundFlow,
            allowCursor = false,
        )

        return transport.snapshot(
            namespace = context.namespace,
            scanned = scanned,
        )
    }

    private suspend fun runIncremental(
        configuration: SyncConfiguration,
        transport: InstrumentedSyncTransport,
    ): SyncStats {
        val localPeerId = configuration.requirePeerId()
        val remotePeerIdForInitiator = if (role == SyncRole.INITIATOR) {
            configuration.requireRemotePeerId()
        } else {
            null
        }
        val expectedRemotePeerIdForResponder = if (role == SyncRole.RESPONDER) {
            configuration.resolveRemotePeerIdOrNull()
        } else {
            null
        }

        val remoteHandshake = CompletableDeferred<SyncCursorPayload>()
        var scanned = 0

        val outboundFlow = flow {
            when (role) {
                SyncRole.INITIATOR -> {
                    val localCursor = Clock.System.now()
                    val localSince = configuration.resolveSinceOverride()
                        ?: context.metaStore.getLastSyncCursor(remotePeerIdForInitiator)
                    emit(
                        SyncCursorPayload(
                            peerId = localPeerId,
                            since = localSince,
                            cursor = localCursor,
                        ).encodedRequest(json),
                    )
                }

                SyncRole.RESPONDER -> {
                    val remote = remoteHandshake.await()
                    val remotePeerId = remote.peerId ?: error("Missing peerId in remote handshake")
                    if (expectedRemotePeerIdForResponder != null && remotePeerId != expectedRemotePeerIdForResponder) {
                        error("Unexpected remote peerId $remotePeerId; expected $expectedRemotePeerIdForResponder")
                    }
                    val localCursor = Clock.System.now()
                    val localSince = configuration.resolveSinceOverride()
                        ?: context.metaStore.getLastSyncCursor(remotePeerId)
                    emit(
                        SyncCursorPayload(
                            peerId = localPeerId,
                            since = localSince,
                            cursor = localCursor,
                        ).encodedRequest(json),
                    )
                }
            }

            val remote = remoteHandshake.await()
            emitAll(
                outboundRequests(
                    configuration = configuration,
                    since = remote.since,
                    incremental = true,
                    onScanned = { scanned += it },
                ),
            )
        }

        exchangeAndApply(
            transport = transport,
            outbound = outboundFlow,
            allowCursor = true,
            onCursor = { payload -> remoteHandshake.complete(payload) },
        )

        val remote = remoteHandshake.await()
        when (role) {
            SyncRole.INITIATOR -> {
                val remotePeerId = remotePeerIdForInitiator ?: error("Missing SyncOptions.RemotePeerId for initiator incremental sync")
                val announcedRemotePeerId = remote.peerId ?: error("Missing peerId in remote handshake")
                if (announcedRemotePeerId != remotePeerId) {
                    error("Unexpected remote peerId $announcedRemotePeerId; expected $remotePeerId")
                }
                context.metaStore.putLastSyncCursor(
                    peerId = remotePeerId,
                    cursor = remote.cursor,
                )
            }
            SyncRole.RESPONDER -> {
                val remotePeerId = remote.peerId ?: error("Missing peerId in remote handshake")
                if (expectedRemotePeerIdForResponder != null && remotePeerId != expectedRemotePeerIdForResponder) {
                    error("Unexpected remote peerId $remotePeerId; expected $expectedRemotePeerIdForResponder")
                }
                context.metaStore.putLastSyncCursor(
                    peerId = remotePeerId,
                    cursor = remote.cursor,
                )
            }
        }

        return transport.snapshot(
            namespace = context.namespace,
            scanned = scanned,
        )
    }

    private suspend fun applyPayload(payload: FullStatePayload) {
        payload.messages.forEach { encoded ->
            val message = encoded.decodeToMessage(context.adapter)
            val current = context.stateSource.load(context.adapter.resolveId(message.crdt))
            val inboundResult = context.synk.inbound(message, current)
            context.mergeHandler?.merge(inboundResult)
        }
    }

    private suspend fun exchangeAndApply(
        transport: InstrumentedSyncTransport,
        outbound: Flow<com.tap.synk.sync.EncodedSyncRequest>,
        allowCursor: Boolean,
        onCursor: (SyncCursorPayload) -> Unit = {},
    ) {
        transport.exchange(outbound)
            .collect { response ->
                when {
                    isSyncCursorChannel(response.channel) -> {
                        if (!allowCursor) {
                            error("Received sync cursor payload while running in FULL mode")
                        }
                        onCursor(response.toSyncCursorPayload(json))
                    }

                    else -> applyPayload(response.toFullStatePayload(json))
                }
            }
    }

    private fun outboundRequests(
        configuration: SyncConfiguration,
        since: Instant?,
        incremental: Boolean,
        onScanned: (Int) -> Unit,
    ) = flow {
        var afterId: String? = null
        while (true) {
            val page = if (incremental) {
                context.stateSource.scanUpdated(since, afterId, configuration.pageSize)
            } else {
                context.stateSource.scan(afterId, configuration.pageSize)
            }
            if (page.isEmpty()) break

            val messages = page.map { obj ->
                val message = context.synk.message(obj)
                message.encodeAsString(context.adapter)
            }

            onScanned(messages.size)
            emit(FullStatePayload(messages).encodedRequest(json))

            afterId = context.adapter.resolveId(page.last())
            if (page.size != configuration.pageSize) break
        }
    }

}

private fun SyncConfiguration.resolveSyncMode(): SyncMode {
    val raw = options[SyncOptions.Mode] ?: return SyncMode.FULL
    return raw as? SyncMode ?: error("Unexpected value for option ${SyncOptions.Mode}; expected ${SyncMode::class.simpleName}")
}

private fun SyncConfiguration.resolveSinceOverride(): Instant? {
    val raw = options[SyncOptions.Since] ?: return null
    return raw as? Instant ?: error("Unexpected value for option ${SyncOptions.Since}; expected ${Instant::class.simpleName}")
}

private fun SyncConfiguration.resolvePeerIdOrNull(): String? {
    val raw = options[SyncOptions.PeerId] ?: return null
    return raw as? String ?: error("Unexpected value for option ${SyncOptions.PeerId}; expected ${String::class.simpleName}")
}

private fun SyncConfiguration.requirePeerId(): String {
    return resolvePeerIdOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: error("Missing SyncOptions.PeerId for incremental sync")
}

private fun SyncConfiguration.requireRemotePeerId(): String {
    val raw = options[SyncOptions.RemotePeerId]
        ?: error("Missing SyncOptions.RemotePeerId for incremental sync")
    val id = raw as? String ?: error("Unexpected value for option ${SyncOptions.RemotePeerId}; expected ${String::class.simpleName}")
    return id.takeIf { it.isNotBlank() } ?: error("SyncOptions.RemotePeerId must not be blank")
}

private fun SyncConfiguration.resolveRemotePeerIdOrNull(): String? {
    val raw = options[SyncOptions.RemotePeerId] ?: return null
    return raw as? String ?: error("Unexpected value for option ${SyncOptions.RemotePeerId}; expected ${String::class.simpleName}")
}
