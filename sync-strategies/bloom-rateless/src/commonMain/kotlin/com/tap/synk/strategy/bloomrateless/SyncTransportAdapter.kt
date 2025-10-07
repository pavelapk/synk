package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.InstrumentedSyncTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class SyncTransportAdapter(
    private val transport: InstrumentedSyncTransport,
    private val json: Json,
) : ConflictSyncTransport {

    override fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload> {
        val requests = outbound.map { payload -> payload.encodedRequest(json) }
        return transport.exchange(requests)
            .map { response -> response.toBloomPayload(json) }
    }

    override fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload> {
        val requests = outbound.map { payload -> payload.encodedRequest(json) }
        return transport.exchange(requests)
            .map { response -> response.toRatelessPayload(json) }
    }

    override fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope> {
        val requests = outbound.map { envelope -> envelope.encodedRequest(json) }
        return transport.exchange(requests)
            .map { response -> response.toTransportEnvelope(json) }
    }
}
