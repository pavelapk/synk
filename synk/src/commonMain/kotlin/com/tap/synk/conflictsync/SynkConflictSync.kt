package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import kotlin.reflect.KClass

suspend fun <T : Any> Synk.conflictSync(
    clazz: KClass<T>,
    transport: ConflictSyncTransport,
    params: ConflictSyncParams = ConflictSyncParams(),
): SyncStats {
    val engine = ConflictSyncEngine(this, synkAdapterStore, factory)
    return engine.syncNamespace(clazz, transport, params)
}

@Suppress("UNUSED_PARAMETER")
suspend fun Synk.conflictSync(
    namespace: String,
    transport: ConflictSyncTransport,
    params: ConflictSyncParams = ConflictSyncParams(),
): SyncStats {
    error("Prefer conflictSync(KClass<T>) to avoid reflection on KMP.")
}

