package com.tap.synk.util

import kotlin.reflect.KClass

/**
 * Registry of type-specific onMerged callbacks, mirroring StateSourceRegistry’s API.
 */
class OnMergedRegistry(
    private val byClass: MutableMap<KClass<*>, (String, Any) -> Unit> = mutableMapOf(),
    private val byNamespace: MutableMap<String, (String, Any) -> Unit> = mutableMapOf(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> register(clazz: KClass<T>, callback: (namespace: String, obj: T) -> Unit) {
        val anyCb: (String, Any) -> Unit = { ns, obj -> callback(ns, obj as T) }
        byClass[clazz] = anyCb
        val ns = clazz.qualifiedName ?: error("No qualifiedName for class ${clazz.simpleName}")
        byNamespace[ns] = anyCb
    }

    fun resolve(namespace: String): ((String, Any) -> Unit)? {
        return byNamespace[namespace]
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(clazz: KClass<T>): ((String, T) -> Unit)? {
        val anyCb = byClass[clazz] ?: return null
        return { ns, obj -> anyCb(ns, obj as Any) }
    }
}

