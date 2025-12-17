@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.tap.synk.meta.store

import kotlin.time.Instant

private const val SYNC_CURSOR_RECORD_ID: String = "__synk_internal_sync_cursor__"
private const val SYNC_CURSOR_KEY: String = "cursor"

private fun syncCursorRecordId(peerId: String?): String {
    if (peerId.isNullOrBlank()) return SYNC_CURSOR_RECORD_ID
    return "$SYNC_CURSOR_RECORD_ID:$peerId"
}

/**
 * Returns the last sync cursor received from the remote peer for this namespace/type.
 *
 * The cursor is stored inside this [MetaStore] instance, so it is scoped to the CRDT type (namespace).
 */
fun MetaStore.getLastSyncCursor(): Instant? {
    return getLastSyncCursor(peerId = null)
}

/**
 * Returns the last sync cursor received from a specific remote peer for this namespace/type.
 */
fun MetaStore.getLastSyncCursor(peerId: String?): Instant? {
    val raw = getMeta(syncCursorRecordId(peerId))?.get(SYNC_CURSOR_KEY) ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}

/**
 * Persists the last sync cursor received from the remote peer for this namespace/type.
 *
 * The cursor should be taken at the start of the sync session (with a safety margin),
 * so changes occurring during sync are included in the next run.
 */
fun MetaStore.putLastSyncCursor(cursor: Instant) {
    putLastSyncCursor(peerId = null, cursor = cursor)
}

/**
 * Persists the last sync cursor received from a specific remote peer for this namespace/type.
 */
fun MetaStore.putLastSyncCursor(peerId: String?, cursor: Instant) {
    putMeta(
        id = syncCursorRecordId(peerId),
        meta = mapOf(SYNC_CURSOR_KEY to cursor.toString()),
    )
}
