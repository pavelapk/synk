package com.tap.synk

import com.tap.synk.relay.Message
import com.tap.synk.relay.decodeToMessage
import com.tap.synk.relay.decodeToMessages
import com.tap.synk.relay.decodeToJsonArray
import com.tap.synk.relay.decodeToJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmName

@JvmName("deserializeTyped")
inline fun <reified T : Any> Synk.deserialize(encoded: String): List<Message<T>> {
    val adapter = synkAdapterStore.resolve(T::class)
    return encoded.decodeToMessages(adapter) as List<Message<T>>
}

@JvmName("deserializeOneTyped")
inline fun <reified T : Any> Synk.deserializeOne(encoded: String): Message<T> {
    val adapter = synkAdapterStore.resolve(T::class)
    return encoded.decodeToMessage(adapter) as Message<T>
}

fun Synk.deserialize(encoded: String): List<Message<Any>> {
    val jsonArray = encoded.decodeToJsonArray()
    return jsonArray.map { element ->
        val jsonObject = element.jsonObject
        val namespace = jsonObject["meta"]?.jsonObject?.get("clazz")?.jsonPrimitive?.content
            ?: throw IllegalStateException("No clazz found in message meta")
        val adapter = synkAdapterStore.resolve(namespace)
        jsonObject.decodeToMessage(adapter)
    }
}

fun Synk.deserializeOne(encoded: String): Message<Any> {
    val jsonObject = encoded.decodeToJsonObject()
    val namespace = jsonObject["meta"]?.jsonObject?.get("clazz")?.jsonPrimitive?.content
        ?: throw IllegalStateException("No clazz found in message meta")
    val adapter = synkAdapterStore.resolve(namespace)
    return jsonObject.decodeToMessage(adapter)
}
