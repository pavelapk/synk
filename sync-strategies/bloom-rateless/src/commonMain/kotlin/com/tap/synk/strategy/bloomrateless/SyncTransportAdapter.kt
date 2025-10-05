package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.SyncPayload
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.SyncTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal class SyncTransportAdapter(
    private val transport: SyncTransport,
    private val json: Json,
    private val outboundLog: MutableList<SyncRequest>,
    private val inboundLog: MutableList<SyncResponse>,
) : ConflictSyncTransport {

    override fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload> {
        val requests = outbound.map { payload ->
            payload.toSyncRequest(json).also { outboundLog += it }
        }
        return transport.exchange(requests).map { response ->
            inboundLog += response
            response.toBloomPayload(json)
        }
    }

    override fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload> {
        val requests = outbound.map { payload ->
            payload.toSyncRequest(json).also { outboundLog += it }
        }
        return transport.exchange(requests).map { response ->
            inboundLog += response
            response.toRatelessPayload(json)
        }
    }

    override fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope> {
        val requests = outbound.map { envelope ->
            envelope.toSyncRequest(json).also { outboundLog += it }
        }
        return transport.exchange(requests).map { response ->
            inboundLog += response
            response.toTransportEnvelope(json)
        }
    }
}
