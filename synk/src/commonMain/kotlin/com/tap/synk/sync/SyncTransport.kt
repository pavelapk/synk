package com.tap.synk.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Companion.binaryFromByteArray(bytes: ByteArray): SyncPayload.Binary {
    return SyncPayload.Binary(Base64.encode(bytes))
}

@OptIn(ExperimentalEncodingApi::class)
fun SyncPayload.Binary.decodeToByteArray(): ByteArray {
    return Base64.decode(base64)
}
