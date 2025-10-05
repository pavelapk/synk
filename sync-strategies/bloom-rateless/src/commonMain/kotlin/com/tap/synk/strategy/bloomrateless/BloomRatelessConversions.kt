package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.SyncPayload
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

internal fun BloomFilterPayload.toSyncRequest(json: Json): SyncRequest = SyncRequest(
    channel = CHANNEL_BLOOM,
    payload = SyncPayload.Json(json.encodeToJsonElement(BloomFilterPayload.serializer(), this)),
)

internal fun SyncRequest.toBloomPayload(json: Json): BloomFilterPayload {
    require(channel == CHANNEL_BLOOM) { "Unexpected channel $channel, expected $CHANNEL_BLOOM" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for bloom channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(BloomFilterPayload.serializer(), jsonPayload.body)
}

internal fun SyncResponse.toBloomPayload(json: Json): BloomFilterPayload {
    require(channel == CHANNEL_BLOOM) { "Unexpected channel $channel, expected $CHANNEL_BLOOM" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for bloom channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(BloomFilterPayload.serializer(), jsonPayload.body)
}

internal fun BloomFilterPayload.toSyncResponse(json: Json): SyncResponse = SyncResponse(
    channel = CHANNEL_BLOOM,
    payload = SyncPayload.Json(json.encodeToJsonElement(BloomFilterPayload.serializer(), this)),
)

internal fun RatelessSketchPayload.toSyncRequest(json: Json): SyncRequest = SyncRequest(
    channel = CHANNEL_SKETCH,
    payload = SyncPayload.Json(json.encodeToJsonElement(RatelessSketchPayload.serializer(), this)),
)

internal fun SyncRequest.toRatelessPayload(json: Json): RatelessSketchPayload {
    require(channel == CHANNEL_SKETCH) { "Unexpected channel $channel, expected $CHANNEL_SKETCH" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for rateless channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(RatelessSketchPayload.serializer(), jsonPayload.body)
}

internal fun SyncResponse.toRatelessPayload(json: Json): RatelessSketchPayload {
    require(channel == CHANNEL_SKETCH) { "Unexpected channel $channel, expected $CHANNEL_SKETCH" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for rateless channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(RatelessSketchPayload.serializer(), jsonPayload.body)
}

internal fun RatelessSketchPayload.toSyncResponse(json: Json): SyncResponse = SyncResponse(
    channel = CHANNEL_SKETCH,
    payload = SyncPayload.Json(json.encodeToJsonElement(RatelessSketchPayload.serializer(), this)),
)

internal fun TransportEnvelope.toSyncRequest(json: Json): SyncRequest = SyncRequest(
    channel = CHANNEL_DECOMPOSITION,
    payload = SyncPayload.Json(json.encodeToJsonElement(TransportEnvelope.serializer(), this)),
)

internal fun SyncRequest.toTransportEnvelope(json: Json): TransportEnvelope {
    require(channel == CHANNEL_DECOMPOSITION) { "Unexpected channel $channel, expected $CHANNEL_DECOMPOSITION" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for decomposition channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(TransportEnvelope.serializer(), jsonPayload.body)
}

internal fun SyncResponse.toTransportEnvelope(json: Json): TransportEnvelope {
    require(channel == CHANNEL_DECOMPOSITION) { "Unexpected channel $channel, expected $CHANNEL_DECOMPOSITION" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for decomposition channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(TransportEnvelope.serializer(), jsonPayload.body)
}

internal fun TransportEnvelope.toSyncResponse(json: Json): SyncResponse = SyncResponse(
    channel = CHANNEL_DECOMPOSITION,
    payload = SyncPayload.Json(json.encodeToJsonElement(TransportEnvelope.serializer(), this)),
)
