package com.tap.synk.strategy.fullstate

import com.tap.synk.inbound
import com.tap.synk.message
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncContext
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncSession
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncStrategyFactory
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.SyncTracker
import com.tap.synk.sync.instrument
import com.tap.synk.relay.decodeToMessage
import com.tap.synk.relay.encodeAsString
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

        val instrumented = transport.instrument(tracker)

        val outboundFlow = flow {
            var afterId: String? = null
            do {
                val page = context.stateSource.scanUpdatedSince(null, afterId, configuration.pageSize)
                if (page.isEmpty()) break

                val messages = mutableListOf<String>()
                var lastId: String? = null
                page.forEach { obj ->
                    val id = context.adapter.resolveId(obj)
                    val message = context.synk.message(obj)
                    messages += message.encodeAsString(context.adapter)
                    lastId = id
                }

                scanned += messages.size

                val payload = FullStatePayload(messages)
                emit(payload.encodedRequest(json))

                afterId = lastId
            } while (page.size == configuration.pageSize)
        }

        instrumented.exchange(outboundFlow)
            .collect { response ->
                val payload = response.toFullStatePayload(json)
                applyPayload(payload)
            }

        instrumented.snapshot(
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

}
