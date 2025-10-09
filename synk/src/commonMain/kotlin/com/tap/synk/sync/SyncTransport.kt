package com.tap.synk.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
sealed interface SyncPayload {
    @Serializable
    @SerialName("json")
    data class Json(
        val body: JsonElement,
        @Transient val byteCount: Long = UNKNOWN_SIZE,
    ) : SyncPayload

    @Serializable
    @SerialName("binary")
    data class Binary(
        val base64: String,
        @Transient val byteCount: Long = UNKNOWN_SIZE,
    ) : SyncPayload

    companion object
}

@Serializable
data class SyncRequest(
    val channel: String,
    val payload: SyncPayload,
)

@Serializable
data class SyncResponse(
    val channel: String,
    val payload: SyncPayload,
)

interface SyncTransport {
    fun exchange(outbound: Flow<SyncRequest>): Flow<SyncResponse>
}

data class EncodedSyncRequest(
    val request: SyncRequest,
    val payloadBytes: Long,
)

data class EncodedSyncResponse(
    val response: SyncResponse,
    val payloadBytes: Long,
)

fun SyncPayload.byteSize(): Long = when (this) {
    is SyncPayload.Json -> when {
        byteCount != UNKNOWN_SIZE -> byteCount
        else -> body.toString().encodeToByteArray().size.toLong()
    }
    is SyncPayload.Binary -> when {
        byteCount != UNKNOWN_SIZE -> byteCount
        else -> base64.encodeToByteArray().size.toLong()
    }
}

inline fun <reified T> Json.encodeSyncRequest(
    channel: String,
    value: T,
): EncodedSyncRequest {
    val element = encodeToJsonElement(value)
    val payloadBytes = element.toString().encodeToByteArray().size.toLong()
    val payload = SyncPayload.Json(body = element, byteCount = payloadBytes)
    return EncodedSyncRequest(
        request = SyncRequest(channel = channel, payload = payload),
        payloadBytes = payloadBytes,
    )
}

inline fun <reified T> Json.encodeSyncResponse(
    channel: String,
    value: T,
): EncodedSyncResponse {
    val element = encodeToJsonElement(value)
    val payloadBytes = element.toString().encodeToByteArray().size.toLong()
    val payload = SyncPayload.Json(body = element, byteCount = payloadBytes)
    return EncodedSyncResponse(
        response = SyncResponse(channel = channel, payload = payload),
        payloadBytes = payloadBytes,
    )
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Companion.binaryFromByteArray(bytes: ByteArray): SyncPayload.Binary {
    return SyncPayload.Binary(
        base64 = Base64.encode(bytes),
        byteCount = bytes.size.toLong(),
    )
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Binary.decodeToByteArray(): ByteArray {
    return Base64.decode(base64)
}

private const val UNKNOWN_SIZE = -1L
