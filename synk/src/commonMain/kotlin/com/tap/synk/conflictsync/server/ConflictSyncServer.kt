package com.tap.synk.conflictsync.server

import com.tap.synk.Synk
import com.tap.synk.conflictsync.ConflictSyncParams
import com.tap.synk.conflictsync.Decomposer
import com.tap.synk.conflictsync.Recomposer
import com.tap.synk.conflictsync.digest.XxHash64
import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.model.Block
import com.tap.synk.conflictsync.net.EosFromA
import com.tap.synk.conflictsync.net.EosReply
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.net.InitBloomReply
import com.tap.synk.conflictsync.net.SessionId
import com.tap.synk.conflictsync.prefilter.BloomFilter
import com.tap.synk.conflictsync.rateless.RatelessIblt
import com.tap.synk.conflictsync.rateless.Symbol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass

/**
 * Reusable server-side logic for ConflictSync. Your transport can delegate to this.
 * Only networking remains for your transport; logic and state live here.
 */
class ConflictSyncServer(
    private val synk: Synk,
    private val params: ConflictSyncParams = ConflictSyncParams(),
    /** If true, server applies A's inbound blocks to converge as well. */
    private val applyInboundOnServer: Boolean = true,
) {
    private val decomposer = Decomposer(synk.synkAdapterStore, synk.factory)
    private val recomposer = Recomposer(synk, synk.synkAdapterStore, synk.factory)

    private data class Session(
        val id: SessionId,
        val namespace: String,
        val clazz: KClass<*>,
        val maybeBHashes: Set<Long>,
    )

    private val sessions = mutableMapOf<String, Session>()
    private var sessionCounter = 0

    suspend fun <T : Any> initBloom(clazz: KClass<T>, req: InitBloom): InitBloomReply {
        val namespace = req.namespace

        // Load all B objects for clazz and decompose
        val stateSource = synk.stateSourceRegistry.resolve(clazz)
        val blocksB = mutableListOf<Block>()
        stateSource.all().collect { obj ->
            blocksB += decomposer.decompose(namespace, clazz, obj)
        }

        val bloomA = BloomFilter.fromBytes(req.bloomBits, req.bloomHashes, req.bloomA)

        val (exclusiveB, maybeB) = blocksB.partition { block ->
            val h = blockDigest(block.key.namespace, block.key.id, block.key.field, block.value, block.hlc, params.digestSeed)
            !bloomA.mightContain(h)
        }
        val maybeBHashSet = maybeB.map {
            blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
        }.toSet()

        val bloomB = BloomFilter(req.bloomBits, req.bloomHashes).also { bf ->
            maybeBHashSet.forEach { bf.add(it) }
        }

        val sid = SessionId("s${++sessionCounter}")
        sessions[sid.value] = Session(sid, namespace, clazz, maybeBHashSet)
        return InitBloomReply(sid, bloomB.toBytes(), exclusiveB)
    }

    fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> {
        val s = sessions[session.value] ?: error("Unknown session ${session.value}")
        val serverRateless = RatelessIblt(params.rateless, sessionSalt = XxHash64.hashString(s.namespace))
            .also { it.localSet(s.maybeBHashes) }
        return flow {
            client.collect { symFromA ->
                // Simple echo strategy: stream our next symbol with the same index
                emit(serverRateless.nextSymbol(symFromA.index))
            }
        }
    }

    suspend fun eos(session: SessionId, msg: EosFromA): EosReply {
        val s = sessions[session.value] ?: error("Unknown session ${session.value}")

        // Compute B's missing blocks using hashesMissingAtA but only from maybeB set
        val stateSource = synk.stateSourceRegistry.resolve(s.clazz)
        val blocksB = mutableListOf<Block>()
        stateSource.all().collect { obj ->
            @Suppress("UNCHECKED_CAST")
            blocksB += decomposer.decompose(s.namespace, s.clazz as KClass<Any>, obj as Any)
        }

        val blocksBMissing = blocksB.filter {
            val h = blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
            msg.hashesMissingAtA.contains(h)
        }

        // Optionally apply inbound from A on the server side for convergence.
        if (applyInboundOnServer) {
            val inboundFromA = msg.exclusiveBlocksA + msg.falsePositiveBlocks
            recomposer.applyInbound(inboundFromA)
        }

        return EosReply(blocksBMissing)
    }
}
