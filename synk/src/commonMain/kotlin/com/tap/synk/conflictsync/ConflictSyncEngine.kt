package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.datasource.StateSource
import com.tap.synk.conflictsync.model.Block
import com.tap.synk.conflictsync.digest.XxHash64
import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.prefilter.BloomFilter
import com.tap.synk.conflictsync.rateless.DecodeResult
import com.tap.synk.conflictsync.rateless.RatelessIblt
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.adapter.store.SynkAdapterStore
import com.tap.synk.meta.store.MetaStoreFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass

data class SyncStats(
    val namespace: String,
    val sentBlocks: Int,
    val recvBlocks: Int,
    val symbolsTx: Int,
    val symbolsRx: Int,
    val objectsFinalized: Int,
    val stagedBufferedBlocks: Int,
    val objectsStagedAtEnd: Int,
)

internal class ConflictSyncEngine(
    private val synk: Synk,
    private val adapters: SynkAdapterStore,
    private val metas: MetaStoreFactory,
) {
    private val decomposer = Decomposer(adapters, metas)
    private val recomposer = Recomposer(synk, adapters, metas)

    suspend fun <T : Any> syncNamespace(
        clazz: KClass<T>,
        transport: ConflictSyncTransport,
        params: ConflictSyncParams,
    ): SyncStats {
        val namespace = clazz.qualifiedName ?: error("No qualifiedName for $clazz")
        val stateSource: StateSource<T> = synk.stateSourceRegistry.resolve(clazz)

        val blocksA = mutableListOf<Block>()
        stateSource.all().collect { obj ->
            blocksA += decomposer.decompose(namespace, clazz, obj)
        }

        val hashesA = blocksA.map { b ->
            blockDigest(b.key.namespace, b.key.id, b.key.field, b.value, b.hlc, params.digestSeed)
        }.toSet()

        val bloomA = BloomFilter(params.bloomBits, params.bloomHashes).also { bf ->
            hashesA.forEach { bf.add(it) }
        }

        val init = transport.initBloom(
            InitBloom(namespace, params.bloomBits, params.bloomHashes, bloomA.toBytes()),
        )

        val applyInit = recomposer.applyInbound(init.definitelyMissingAtA)
        var recvBlocks = init.definitelyMissingAtA.size
        var sentBlocks = 0
        var objectsFinalized = applyInit.finalizedDelta
        var stagedBufferedBlocks = applyInit.stagedBlocksDelta
        var stagedAtEnd = applyInit.currentStagedCount

        val bloomB = BloomFilter.fromBytes(params.bloomBits, params.bloomHashes, init.bloomB)
        val exclusiveA = blocksA.filter {
            val h = blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
            !bloomB.mightContain(h)
        }
        val maybeA = blocksA - exclusiveA.toSet()
        sentBlocks += exclusiveA.size

        val rateless = RatelessIblt(params.rateless, sessionSalt = XxHash64.hashString(namespace))
        rateless.localSet(maybeA.map {
            blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
        }.toSet())

        var i = 0
        var tx = 0
        var rx = 0
        val clientFlow = flow {
            while (i < params.rateless.maxSymbols) {
                emit(rateless.nextSymbol(i))
                tx++
                i++
            }
        }

        val serverFlow = transport.rateless(init.session, clientFlow)

        var eosSent = false
        var stats: SyncStats? = null

        serverFlow.collect { sym ->
            rx++
            rateless.absorb(sym)
            when (val r = rateless.tryDecode()) {
                is DecodeResult.NeedMore -> Unit
                is DecodeResult.Done -> if (!eosSent) {
                    eosSent = true

                    val hashesMissingAtA = r.missingHere
                    val hashesMissingAtB = r.missingThere

                    val fp = maybeA.filter {
                        val h = blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
                        h in hashesMissingAtB
                    }
                    sentBlocks += fp.size

                    val eosReply = transport.eos(
                        init.session,
                        com.tap.synk.conflictsync.net.EosFromA(
                            hashesMissingAtA = hashesMissingAtA,
                            falsePositiveBlocks = fp,
                            exclusiveBlocksA = exclusiveA,
                        ),
                    )

                    val applyEos = recomposer.applyInbound(eosReply.blocksBMissing)
                    recvBlocks += eosReply.blocksBMissing.size
                    objectsFinalized += applyEos.finalizedDelta
                    stagedBufferedBlocks += applyEos.stagedBlocksDelta
                    stagedAtEnd = applyEos.currentStagedCount

                    stats = SyncStats(namespace, sentBlocks, recvBlocks, tx, rx, objectsFinalized, stagedBufferedBlocks, stagedAtEnd)
                }
            }
        }

        return stats ?: SyncStats(namespace, sentBlocks, recvBlocks, tx, rx, objectsFinalized, stagedBufferedBlocks, stagedAtEnd)
    }
}
