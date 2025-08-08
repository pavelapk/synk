package com.tap.synk.datasource

import kotlinx.coroutines.flow.Flow

interface StateSource<T : Any> {
    /** Stream all current objects of this type (cold flow; emit each once). */
    suspend fun all(): Flow<T>

    /** Lookup by ID; return null if not found. */
    suspend fun byId(id: String): T?
}

