package com.tap.synk.adapter.store

import com.tap.synk.adapter.SynkAdapter
import kotlin.reflect.KClass

class SynkAdapterStore(
    private val lookup: HashMap<KClass<*>, SynkAdapter<Any>> = HashMap(),
    private val namespaceLookup: HashMap<String, SynkAdapter<Any>> = HashMap(),
) {

    fun <T : Any> register(clazz: KClass<T>, adapter: SynkAdapter<T>) {
        val anyAdapter = adapter as SynkAdapter<Any>
        lookup[clazz] = anyAdapter
        val qualifiedName = clazz.qualifiedName
            ?: throw IllegalStateException("No qualifiedName for class ${clazz.simpleName}")
        namespaceLookup[qualifiedName] = anyAdapter
    }

    fun <T : Any> resolve(clazz: KClass<T>): SynkAdapter<Any> {
        return lookup[clazz] ?: throw IllegalStateException("No synk adapter found for given class " + clazz.qualifiedName)
    }

    fun resolve(namespace: String): SynkAdapter<Any> {
        return namespaceLookup[namespace]
            ?: throw IllegalStateException("No synk adapter found for given namespace $namespace")
    }
}
