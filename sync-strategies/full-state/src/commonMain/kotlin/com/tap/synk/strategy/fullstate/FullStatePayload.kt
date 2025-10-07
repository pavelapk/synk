package com.tap.synk.strategy.fullstate

import com.tap.synk.sync.EncodedSyncRequest
import com.tap.synk.sync.EncodedSyncResponse
import com.tap.synk.sync.SyncPayload
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.encodeSyncRequest
import com.tap.synk.sync.encodeSyncResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private const val CHANNEL_FULL_STATE = "full-state"

@Serializable
internal data class FullStatePayload(
    val messages: List<String>,
)

internal fun FullStatePayload.encodedRequest(json: Json): EncodedSyncRequest =
    json.encodeSyncRequest(CHANNEL_FULL_STATE, FullStatePayload.serializer(), this)

internal fun FullStatePayload.encodedResponse(json: Json): EncodedSyncResponse =
    json.encodeSyncResponse(CHANNEL_FULL_STATE, FullStatePayload.serializer(), this)

internal fun SyncRequest.toFullStatePayload(json: Json): FullStatePayload {
    require(channel == CHANNEL_FULL_STATE) { "Unexpected channel $channel, expected $CHANNEL_FULL_STATE" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: error("Expected JSON payload but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(FullStatePayload.serializer(), jsonPayload.body)
}

internal fun SyncResponse.toFullStatePayload(json: Json): FullStatePayload {
    require(channel == CHANNEL_FULL_STATE) { "Unexpected channel $channel, expected $CHANNEL_FULL_STATE" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: error("Expected JSON payload but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement(FullStatePayload.serializer(), jsonPayload.body)
}
