package com.tap.synk.adapter

import com.tap.synk.relay.decodeToJsonObject
import com.tap.synk.relay.decodeToMap
import com.tap.synk.relay.encodeAsJsonObject

internal fun <T : Any> SynkAdapter<T>.diff(old: T, new: T): Set<String> {
    val encodedOld = encode(old)
    val encodedNew = encode(new)

    return encodedNew.entries.fold(mutableSetOf()) { acc, newEntry ->

        val oldEntry = encodedOld.entries.firstOrNull {
            newEntry.key == it.key
        } ?: return@fold acc.apply { add(newEntry.key) }

        if (newEntry.value != oldEntry.value) {
            acc.apply { add(newEntry.key) }
        } else {
            acc
        }
    }
}

internal data class EncodedField(
    val name: String,
    val bytes: ByteArray,
)

internal fun <T : Any> SynkAdapter<T>.encodeToFields(crdt: T): List<EncodedField> {
    val encoded = encode(crdt)
    return encoded.entries
        .sortedBy { it.key }
        .map { (key, value) ->
            EncodedField(
                name = key,
                bytes = value.encodeToByteArray(),
            )
        }
}

internal fun <T : Any> SynkAdapter<T>.encodeSnapshot(crdt: T): ByteArray {
    val encoded = encode(crdt).encodeAsJsonObject().toString()
    return encoded.encodeToByteArray()
}

internal fun <T : Any> SynkAdapter<T>.decodeSnapshot(bytes: ByteArray): T {
    val json = bytes.decodeToString()
    val map = json.decodeToJsonObject().decodeToMap()
    return decode(map)
}
