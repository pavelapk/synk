package com.tap.synk.conflictsync.net

import com.tap.synk.conflictsync.model.Block
import com.tap.synk.conflictsync.rateless.Symbol
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class SessionId(val value: String)

@Serializable
data class InitBloom(
    val namespace: String,
    val bloomBits: Int,
    val bloomHashes: Int,
    val bloomA: ByteArray,
)

@Serializable
data class InitBloomReply(
    val session: SessionId,
    val bloomB: ByteArray,
    val definitelyMissingAtA: List<Block>,
)

@Serializable
data class EosFromA(
    val hashesMissingAtA: List<Long>,
    val falsePositiveBlocks: List<Block>,
    val exclusiveBlocksA: List<Block>,
)

@Serializable
data class EosReply(
    val blocksBMissing: List<Block>,
)

/**
 * Implement this outside Synk for your chosen network stack.
 */
interface ConflictSyncTransport {
    suspend fun initBloom(req: InitBloom): InitBloomReply
    fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol>
    suspend fun eos(session: SessionId, msg: EosFromA): EosReply
}

