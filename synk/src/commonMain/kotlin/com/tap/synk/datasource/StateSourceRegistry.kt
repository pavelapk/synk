package com.tap.synk.datasource

import kotlin.reflect.KClass

class StateSourceRegistry(
    private val byClass: MutableMap<KClass<*>, StateSource<Any>> = mutableMapOf(),
    private val byNamespace: MutableMap<String, StateSource<Any>> = mutableMapOf(),
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> register(clazz: KClass<T>, source: StateSource<T>) {
        byClass[clazz] = source as StateSource<Any>
        val ns = clazz.qualifiedName
            ?: error("No qualifiedName for class ${clazz.simpleName}")
        byNamespace[ns] = source
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(clazz: KClass<T>): StateSource<T> {
        return (byClass[clazz] ?: error("No StateSource for ${clazz.qualifiedName}")) as StateSource<T>
    }

    fun resolve(namespace: String): StateSource<Any> {
        return byNamespace[namespace] ?: error("No StateSource for namespace $namespace")
    }
}
