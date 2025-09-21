package com.tap.synk.conflict

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RatelessSymbol(
    val keySum: Long,
    val valueSum: Long,
    val count: Long,
)

@Serializable
data class RatelessSketchPayload(
    val namespace: String,
    val symbols: List<RatelessSymbol>,
)

internal const val CODED_SYMBOL_SIZE: Int = Long.SIZE_BYTES * 3

@Serializable
sealed class DecompositionBlock {
    @Serializable
    @SerialName("snapshot")
    data class SnapshotBlock(
        val namespace: String,
        val objectKey: ObjectKey,
        val encodedObject: ByteArray,
        val fields: List<FieldDecomposition>,
    ) : DecompositionBlock()

    @Serializable
    @SerialName("field")
    data class FieldBlock(
        val namespace: String,
        val field: FieldDecomposition,
    ) : DecompositionBlock()
}

@Serializable
sealed interface TransportEnvelope {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val payload: DecompositionBlock.SnapshotBlock) : TransportEnvelope

    @Serializable
    @SerialName("field")
    data class Field(val payload: DecompositionBlock.FieldBlock) : TransportEnvelope

    @Serializable
    @SerialName("hash_request")
    data class HashRequest(val hashes: List<Long>) : TransportEnvelope

    @Serializable
    @SerialName("hash_response")
    data class HashResponse(val fields: List<FieldDecomposition>) : TransportEnvelope
}
