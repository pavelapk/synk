package com.tap.synk.conflict

data class ConflictSyncStats(
    val namespace: String,
    val scanned: Int,
    val remoteUnknown: Int,
    val localOnly: Int,
    val remoteOnlyHashes: Int,
    val outbound: List<TransportEnvelope>,
    val inbound: List<TransportEnvelope>,
    val falseMatches: Int,
)

internal data class OutboundPlan(
    val envelopes: List<TransportEnvelope>,
    val stateBytes: Int,
    val metadataBytes: Int,
)
