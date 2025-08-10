package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.prefilter.BloomFilter
import com.tap.synk.conflictsync.server.ConflictSyncServer
import com.tap.synk.config.storageConfig
import com.tap.synk.datasource.StateSource
import com.tap.synk.recordChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConflictSyncServerInitBloomTest {
    @Test
    fun initBloom_respects_false_positive_in_client_bloom() = runBlocking {
        val serverDb = mutableListOf(
            CRDT("1", "Alice", "Smith", 1),
            CRDT("2", "Bob", "Brown", 2),
        )

        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, object : StateSource<CRDT> {
                override suspend fun all(): Flow<CRDT> = flow { serverDb.forEach { emit(it) } }
                override suspend fun byId(id: String): CRDT? = serverDb.find { it.id == id }
            })
            .build()
        serverDb.forEach { serverSynk.recordChange(it, null) }

        val params = ConflictSyncParams()
        val server = ConflictSyncServer(serverSynk, params, applyInboundOnServer = false)

        // Compute server-side blocks to pick one block to force as a client false positive.
        val decomposer = Decomposer(serverSynk.synkAdapterStore, serverSynk.factory)
        val namespace = CRDT::class.qualifiedName!!
        val blocksB = serverDb.flatMap { decomposer.decompose(namespace, CRDT::class, it) }
        val fpBlock = blocksB.first()
        val fpHash = blockDigest(fpBlock.key.namespace, fpBlock.key.id, fpBlock.key.field, fpBlock.value, fpBlock.hlc, params.digestSeed)

        // Forge client's bloom to include only fpHash (pretending A has it -> becomes maybeB at server)
        val bloomA = BloomFilter(params.bloomBits, params.bloomHashes).also { it.add(fpHash) }

        val reply = server.initBloom(CRDT::class, InitBloom(namespace, params.bloomBits, params.bloomHashes, bloomA.toBytes()))

        // The chosen block must NOT be in definitelyMissingAtA (it's a false positive -> maybeB)
        assertFalse(reply.definitelyMissingAtA.contains(fpBlock))

        // And bloomB must claim membership for the false-positive hash (since it's in maybeB)
        val bloomB = BloomFilter.fromBytes(params.bloomBits, params.bloomHashes, reply.bloomB)
        assertTrue(bloomB.mightContain(fpHash))
    }
}

