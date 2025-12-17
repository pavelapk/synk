@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package com.tap.synk.strategy.fullstate

import com.tap.synk.sync.EncodedSyncRequest
import com.tap.synk.sync.EncodedSyncResponse
import com.tap.synk.sync.SyncPayload
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.encodeSyncRequest
import com.tap.synk.sync.encodeSyncResponse
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private const val CHANNEL_SYNC_CURSOR = "sync-cursor"

@Serializable
internal data class SyncCursorPayload(
    val peerId: String? = null,
    @Serializable(with = NullableInstantIso8601Serializer::class)
    val since: Instant? = null,
    @Serializable(with = InstantIso8601Serializer::class)
    val cursor: Instant,
)

internal fun SyncCursorPayload.encodedRequest(json: Json): EncodedSyncRequest =
    json.encodeSyncRequest(CHANNEL_SYNC_CURSOR, this)

internal fun SyncCursorPayload.encodedResponse(json: Json): EncodedSyncResponse =
    json.encodeSyncResponse(CHANNEL_SYNC_CURSOR, this)

internal fun SyncRequest.toSyncCursorPayload(json: Json): SyncCursorPayload {
    require(channel == CHANNEL_SYNC_CURSOR) { "Unexpected channel $channel, expected $CHANNEL_SYNC_CURSOR" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: error("Expected JSON payload but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<SyncCursorPayload>(jsonPayload.body)
}

internal fun SyncResponse.toSyncCursorPayload(json: Json): SyncCursorPayload {
    require(channel == CHANNEL_SYNC_CURSOR) { "Unexpected channel $channel, expected $CHANNEL_SYNC_CURSOR" }
    val jsonPayload = payload as? SyncPayload.Json
        ?: error("Expected JSON payload but received ${payload::class.simpleName}")
    return json.decodeFromJsonElement<SyncCursorPayload>(jsonPayload.body)
}

internal fun isSyncCursorChannel(channel: String): Boolean = channel == CHANNEL_SYNC_CURSOR

private object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

private object NullableInstantIso8601Serializer : KSerializer<Instant?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant?", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Instant? {
        return if (decoder.decodeNotNullMark()) {
            Instant.parse(decoder.decodeString())
        } else {
            decoder.decodeNull()
            null
        }
    }
}
