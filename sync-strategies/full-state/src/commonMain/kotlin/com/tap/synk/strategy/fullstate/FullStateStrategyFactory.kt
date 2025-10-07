package com.tap.synk.strategy.fullstate

import com.tap.synk.inbound
import com.tap.synk.meta.Meta
import com.tap.synk.relay.Message
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncContext
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncSession
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncStrategyFactory
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.SyncTracker
import com.tap.synk.sync.SyncTransferEvent
import com.tap.synk.sync.byteSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

object FullStateStrategyFactory : SyncStrategyFactory {
    override fun <T : Any> createSession(
        @Suppress("UNUSED_PARAMETER") role: SyncRole,
        context: SyncContext<T>,
    ): SyncSession<T> {
        return FullStateSession(context, fullStateJson)
    }
}

internal val fullStateJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private class FullStateSession<T : Any>(
    private val context: SyncContext<T>,
    private val json: Json,
) : SyncSession<T> {

    override suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration,
    ): SyncStats = coroutineScope {
        context.warm()

        var scanned = 0
        var outboundMessages = 0
        var outboundBytes = 0L
        var inboundMessages = 0
        var inboundBytes = 0L

        val outboundFlow = flow {
            var afterId: String? = null
            do {
                val page = context.stateSource.scan(afterId, configuration.pageSize)
                if (page.isEmpty()) break

                val records = page.map { obj ->
                    val id = context.adapter.resolveId(obj)
                    val fields = context.adapter.encode(obj)
                    val meta = context.metaStore.getMeta(id) ?: emptyMap()
                    FullStatePayload.Record(
                        id = id,
                        fields = fields,
                        meta = meta,
                    )
                }

                scanned += records.size

                val payload = FullStatePayload(records)
                val encoded = payload.encodedRequest(json)

                outboundMessages += 1
                outboundBytes += encoded.payloadBytes

                registerOutbound(tracker, encoded.payloadBytes)
                emit(encoded.request)

                afterId = page.lastOrNull()?.let { context.adapter.resolveId(it) }
            } while (page.size == configuration.pageSize)
        }

        transport.exchange(outboundFlow)
            .collect { response ->
                inboundMessages += 1
                val payloadBytes = response.payload.byteSize()
                inboundBytes += payloadBytes
                registerInbound(tracker, payloadBytes)
                val payload = response.toFullStatePayload(json)
                applyPayload(payload)
            }

        tracker.finish(FALSE_MATCHES_NONE)

        SyncStats(
            adapterKey = context.adapterKey,
            scanned = scanned,
            outboundMessages = outboundMessages,
            outboundBytes = outboundBytes,
            inboundMessages = inboundMessages,
            inboundBytes = inboundBytes,
            falseMatches = FALSE_MATCHES_NONE,
        )
    }

    private suspend fun applyPayload(payload: FullStatePayload) {
        payload.records.forEach { record ->
            val decoded = context.adapter.decode(record.fields)
            val message = Message(
                crdt = decoded,
                meta = Meta(
                    namespace = context.adapterKey,
                    timestampMeta = record.meta,
                ),
            )
            val current = context.stateSource.load(record.id)
            val inboundResult = context.synk.inbound(message, current)
            context.mergeHandler?.merge(inboundResult)
        }
    }

    private fun registerOutbound(tracker: SyncTracker, bytes: Int) {
        tracker.register(
            SyncTransferEvent.LocalToRemote(
                stateBytes = bytes,
                metadataBytes = 0,
                upload = tracker.uploadBandwidth,
            ),
        )
    }

    private fun registerInbound(tracker: SyncTracker, bytes: Int) {
        tracker.register(
            SyncTransferEvent.RemoteToLocal(
                stateBytes = bytes,
                metadataBytes = 0,
                download = tracker.downloadBandwidth,
            ),
        )
    }

    companion object {
        private const val FALSE_MATCHES_NONE = 0
    }
}
