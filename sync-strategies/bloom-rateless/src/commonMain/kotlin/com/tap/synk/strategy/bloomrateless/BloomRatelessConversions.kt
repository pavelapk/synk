package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.EncodedSyncRequest
import com.tap.synk.sync.EncodedSyncResponse
import com.tap.synk.sync.SyncPayload
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.encodeSyncRequest
import com.tap.synk.sync.encodeSyncResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

internal fun BloomFilterPayload.encodedRequest(json: Json): EncodedSyncRequest =
    json.encodeSyncRequest(CHANNEL_BLOOM, this)

internal fun BloomFilterPayload.encodedResponse(json: Json): EncodedSyncResponse =
    json.encodeSyncResponse(CHANNEL_BLOOM, this)

internal fun BloomFilterPayload.toSyncRequest(json: Json): SyncRequest = encodedRequest(json).request

internal fun SyncRequest.toBloomPayload(json: Json): BloomFilterPayload {
    require(channel == CHANNEL_BLOOM) { "Unexpected channel $channel, expected $CHANNEL_BLOOM" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for bloom channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<BloomFilterPayload>(jsonPayload.body)
}

internal fun SyncResponse.toBloomPayload(json: Json): BloomFilterPayload {
    require(channel == CHANNEL_BLOOM) { "Unexpected channel $channel, expected $CHANNEL_BLOOM" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for bloom channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<BloomFilterPayload>(jsonPayload.body)
}

internal fun BloomFilterPayload.toSyncResponse(json: Json): SyncResponse = encodedResponse(json).response

internal fun RatelessSketchPayload.encodedRequest(json: Json): EncodedSyncRequest =
    json.encodeSyncRequest(CHANNEL_SKETCH, this)

internal fun RatelessSketchPayload.encodedResponse(json: Json): EncodedSyncResponse =
    json.encodeSyncResponse(CHANNEL_SKETCH, this)

internal fun RatelessSketchPayload.toSyncRequest(json: Json): SyncRequest = encodedRequest(json).request

internal fun SyncRequest.toRatelessPayload(json: Json): RatelessSketchPayload {
    require(channel == CHANNEL_SKETCH) { "Unexpected channel $channel, expected $CHANNEL_SKETCH" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for rateless channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<RatelessSketchPayload>(jsonPayload.body)
}

internal fun SyncResponse.toRatelessPayload(json: Json): RatelessSketchPayload {
    require(channel == CHANNEL_SKETCH) { "Unexpected channel $channel, expected $CHANNEL_SKETCH" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for rateless channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<RatelessSketchPayload>(jsonPayload.body)
}

internal fun RatelessSketchPayload.toSyncResponse(json: Json): SyncResponse = encodedResponse(json).response

internal fun TransportEnvelope.encodedRequest(json: Json): EncodedSyncRequest =
    json.encodeSyncRequest(CHANNEL_DECOMPOSITION, this)

internal fun TransportEnvelope.encodedResponse(json: Json): EncodedSyncResponse =
    json.encodeSyncResponse(CHANNEL_DECOMPOSITION, this)

internal fun TransportEnvelope.toSyncRequest(json: Json): SyncRequest = encodedRequest(json).request

internal fun SyncRequest.toTransportEnvelope(json: Json): TransportEnvelope {
    require(channel == CHANNEL_DECOMPOSITION) { "Unexpected channel $channel, expected $CHANNEL_DECOMPOSITION" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for decomposition channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<TransportEnvelope>(jsonPayload.body)
}

internal fun SyncResponse.toTransportEnvelope(json: Json): TransportEnvelope {
    require(channel == CHANNEL_DECOMPOSITION) { "Unexpected channel $channel, expected $CHANNEL_DECOMPOSITION" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: throw IllegalStateException("Expected JSON payload for decomposition channel, but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<TransportEnvelope>(jsonPayload.body)
}

internal fun TransportEnvelope.toSyncResponse(json: Json): SyncResponse = encodedResponse(json).response
