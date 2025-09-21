package com.tap.synk.conflict

import com.tap.hlc.HybridLogicalClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import okio.Buffer
import kotlin.jvm.JvmInline

@Serializable
data class ObjectKey(
    val namespace: String,
    val id: String,
)

@Serializable
@JvmInline
value class FieldFingerprint(val bytes: ByteArray)

@Serializable
data class FieldDecomposition(
    val namespace: String,
    val objectKey: ObjectKey,
    val field: String,
    val encodedValue: ByteArray,
    @Serializable(with = HybridLogicalClockSerializer::class)
    val hlc: HybridLogicalClock,
) : Extract<FieldFingerprint>, Measure {
    @Transient
    private val hlcString: String = hlc.toString()

    override fun extract(): FieldFingerprint = FieldFingerprint(
        fingerprintEncoder(
            namespace = namespace,
            objectKey = objectKey,
            field = field,
            encodedValue = encodedValue,
            hlcEncoded = hlcString,
        ),
    )

    override fun sizeOf(): Int {
        val namespaceBytes = namespace.encodeToByteArray().size
        val idBytes = objectKey.id.encodeToByteArray().size
        val fieldBytes = field.encodeToByteArray().size
        val hlcBytes = hlcString.encodeToByteArray().size
        return encodedValue.size + namespaceBytes + idBytes + fieldBytes + hlcBytes + METADATA_BYTES
    }

    override fun falseMatches(other: Measure): Int = when (other) {
        is FieldDecomposition -> if (objectKey == other.objectKey && field == other.field) 0 else 1
        else -> 1
    }

    companion object {
        private const val METADATA_BYTES = Int.SIZE_BYTES * 4
    }
}

internal fun fingerprintEncoder(
    namespace: String,
    objectKey: ObjectKey,
    field: String,
    encodedValue: ByteArray,
    hlcEncoded: String,
): ByteArray {
    val buffer = Buffer()
    buffer.writeIntLe(namespace.length)
    buffer.writeUtf8(namespace)
    buffer.writeIntLe(objectKey.id.length)
    buffer.writeUtf8(objectKey.id)
    buffer.writeIntLe(field.length)
    buffer.writeUtf8(field)
    buffer.writeIntLe(encodedValue.size)
    buffer.write(encodedValue)
    val hlcBytes = hlcEncoded.encodeToByteArray()
    buffer.writeIntLe(hlcBytes.size)
    buffer.write(hlcBytes)
    return buffer.readByteArray()
}
