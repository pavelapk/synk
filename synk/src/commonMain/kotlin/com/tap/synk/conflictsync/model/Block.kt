package com.tap.synk.conflictsync.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockKey(
    val namespace: String, // crdt::class.qualifiedName
    val id: String, // SynkAdapter.resolveId
    val field: String, // MapEncoder key
)

@Serializable
data class Block(
    val key: BlockKey,
    val value: String, // encoded field value
    val hlc: String, // per-field HLC
)

