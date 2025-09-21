package com.tap.synk.conflict

import kotlinx.coroutines.flow.Flow

interface ConflictSyncTransport {
    fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload>
    fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload>
    fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope>
}
