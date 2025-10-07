package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.byteSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class SyncTransportAdapter(
    private val transport: SyncTransport,
    private val json: Json,
    private val metrics: SyncMessageMetrics,
) : ConflictSyncTransport {

    override fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload> {
        val requests = outbound.map { payload ->
            payload.encodedRequest(json).also { encoded ->
                metrics.recordOutbound(encoded.payloadBytes)
            }.request
        }
        return transport.exchange(requests)
            .map { response ->
                metrics.recordInbound(response.payload.byteSize())
                response.toBloomPayload(json)
            }
    }

    override fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload> {
        val requests = outbound.map { payload ->
            payload.encodedRequest(json).also { encoded ->
                metrics.recordOutbound(encoded.payloadBytes)
            }.request
        }
        return transport.exchange(requests)
            .map { response ->
                metrics.recordInbound(response.payload.byteSize())
                response.toRatelessPayload(json)
            }
    }

    override fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope> {
        val requests = outbound.map { envelope ->
            envelope.encodedRequest(json).also { encoded ->
                metrics.recordOutbound(encoded.payloadBytes)
            }.request
        }
        return transport.exchange(requests)
            .map { response ->
                metrics.recordInbound(response.payload.byteSize())
                response.toTransportEnvelope(json)
            }
    }
}

internal class SyncMessageMetrics {
    var outboundMessages: Int = 0
        private set
    var inboundMessages: Int = 0
        private set
    var outboundBytes: Long = 0
        private set
    var inboundBytes: Long = 0
        private set

    fun recordOutbound(bytes: Int) {
        outboundMessages += 1
        outboundBytes += bytes
    }

    fun recordInbound(bytes: Int) {
        inboundMessages += 1
        inboundBytes += bytes
    }
}
