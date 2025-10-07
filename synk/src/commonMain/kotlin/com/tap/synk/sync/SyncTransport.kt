package com.tap.synk.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.text.encodeToByteArray
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
sealed interface SyncPayload {
    @Serializable
    @SerialName("json")
    data class Json(val body: JsonElement) : SyncPayload

    @Serializable
    @SerialName("binary")
    data class Binary(val base64: String) : SyncPayload

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
    val payloadBytes: Int,
)

data class EncodedSyncResponse(
    val response: SyncResponse,
    val payloadBytes: Int,
)

fun SyncPayload.byteSize(): Int = when (this) {
    is SyncPayload.Json -> body.toString().encodeToByteArray().size
    is SyncPayload.Binary -> base64.encodeToByteArray().size
}

fun <T> Json.encodeSyncRequest(
    channel: String,
    serializer: KSerializer<T>,
    value: T,
): EncodedSyncRequest {
    val element = encodeToJsonElement(serializer, value)
    val payload = SyncPayload.Json(element)
    val encoded = encodeToString(JsonElement.serializer(), element)
    return EncodedSyncRequest(
        request = SyncRequest(channel = channel, payload = payload),
        payloadBytes = encoded.encodeToByteArray().size,
    )
}

fun <T> Json.encodeSyncResponse(
    channel: String,
    serializer: KSerializer<T>,
    value: T,
): EncodedSyncResponse {
    val element = encodeToJsonElement(serializer, value)
    val payload = SyncPayload.Json(element)
    val encoded = encodeToString(JsonElement.serializer(), element)
    return EncodedSyncResponse(
        response = SyncResponse(channel = channel, payload = payload),
        payloadBytes = encoded.encodeToByteArray().size,
    )
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Companion.binaryFromByteArray(bytes: ByteArray): SyncPayload.Binary {
    return SyncPayload.Binary(Base64.encode(bytes))
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Binary.decodeToByteArray(): ByteArray {
    return Base64.decode(base64)
}
