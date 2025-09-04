package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import com.tap.synk.conflictsync.net.EosFromA
import com.tap.synk.conflictsync.net.EosReply
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.net.InitBloomReply
import com.tap.synk.conflictsync.net.SessionId
import com.tap.synk.conflictsync.prefilter.BloomFilter
import com.tap.synk.conflictsync.rateless.Symbol
import com.tap.synk.conflictsync.server.ConflictSyncServer
import com.tap.synk.config.storageConfig
import com.tap.synk.datasource.StateSource
import com.tap.synk.recordChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictSyncEndToEndTest {
    @Test
    fun false_positive_id_field_causes_partial_then_finalize_at_eos_and_metrics_reflect() = runBlocking {
        // Client empty; Server has one object. We will force the 'id' block to be a false positive at init.
        val clientDb = mutableListOf<CRDT>()
        val clientSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(clientDb))
            .onMerged(CRDT::class) { _, obj -> upsert(clientDb, obj) }
            .build()

        val serverObj = CRDT("42", "Alice", "Smith", 7)
        val serverDb = mutableListOf(serverObj)
        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(serverDb))
            .onMerged(CRDT::class) { _, obj -> upsert(serverDb, obj) }
            .build()
        serverSynk.recordChange(serverObj, null)

        val server = ConflictSyncServer(serverSynk)

        val transport = object : ConflictSyncTransport {
            override suspend fun initBloom(req: InitBloom): InitBloomReply {
                // Build B blocks to compute the hash for the id field and force it as a FP in client's bloom
                val decomposer = Decomposer(serverSynk.synkAdapterStore, serverSynk.factory)
                val blocksB = serverDb.flatMap { decomposer.decompose(req.namespace, CRDT::class, it) }
                val idBlock = blocksB.first { it.key.field == "id" }
                val params = ConflictSyncParams() // default seeds/hashes
                val h = blockDigest(idBlock.key.namespace, idBlock.key.id, idBlock.key.field, idBlock.value, idBlock.hlc, params.digestSeed)

                val bf = BloomFilter.fromBytes(req.bloomBits, req.bloomHashes, req.bloomA)
                bf.add(h)
                val forged = InitBloom(req.namespace, req.bloomBits, req.bloomHashes, bf.toBytes())
                return server.initBloom(CRDT::class, forged)
            }

            override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> = server.rateless(session, client)
            override suspend fun eos(session: SessionId, msg: EosFromA): EosReply = server.eos(session, msg)
        }

        val stats = clientSynk.conflictSync(CRDT::class, transport, ConflictSyncParams())

        // The client should now have the server object; partial was finalized at EOS.
        assertEquals(listOf(serverObj), clientDb)
        // Metrics: at least one object finalized, and staged blocks buffered > 0 due to partial at init.
        assertTrue(stats.objectsFinalized >= 1)
        assertTrue(stats.stagedBufferedBlocks > 0)
        assertEquals(0, stats.objectsStagedAtEnd)
    }

    @Test
    fun identical_sets_terminate_quickly_with_empty_metrics() = runBlocking {
        val db = mutableListOf(
            CRDT("1", "A", "L", null),
            CRDT("2", "B", "L", 5),
        )
        val clientSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(db))
            .onMerged(CRDT::class) { _, obj -> upsert(db, obj) }
            .build()
        db.forEach { clientSynk.recordChange(it, null) }

        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(db.toMutableList()))
            .onMerged(CRDT::class) { _, obj -> }
            .build()
        db.forEach { serverSynk.recordChange(it, null) }

        val server = ConflictSyncServer(serverSynk)
        val transport = object : ConflictSyncTransport {
            override suspend fun initBloom(req: InitBloom): InitBloomReply = server.initBloom(CRDT::class, req)
            override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> = server.rateless(session, client)
            override suspend fun eos(session: SessionId, msg: EosFromA): EosReply = server.eos(session, msg)
        }

        val stats = clientSynk.conflictSync(CRDT::class, transport, ConflictSyncParams())
        assertTrue(stats.symbolsTx <= 2 && stats.symbolsRx <= 2)
        assertEquals(0, stats.objectsStagedAtEnd)
    }

    @Test
    fun non_identical_sets_rateless_stops_before_maxSymbols() = runBlocking {
        // A: 1..10, B: 6..15 (overlap 6..10). Small diff that rateless should decode quickly.
        fun crdt(i: Int) = CRDT(i.toString(), "N$i", "L$i", if (i % 2 == 0) i else null)
        val a = (1..10).map(::crdt)
        val b = (6..15).map(::crdt)

        val clientDb = a.toMutableList()
        val serverDb = b.toMutableList()

        val clientSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(clientDb))
            .onMerged(CRDT::class) { _, obj -> upsert(clientDb, obj) }
            .build()
        clientDb.forEach { clientSynk.recordChange(it, null) }

        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(serverDb))
            .onMerged(CRDT::class) { _, obj -> upsert(serverDb, obj) }
            .build()
        serverDb.forEach { serverSynk.recordChange(it, null) }

        val server = ConflictSyncServer(serverSynk)
        val transport = object : ConflictSyncTransport {
            override suspend fun initBloom(req: InitBloom): InitBloomReply = server.initBloom(CRDT::class, req)
            override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> = server.rateless(session, client)
            override suspend fun eos(session: SessionId, msg: EosFromA): EosReply = server.eos(session, msg)
        }

        val params = ConflictSyncParams(
            bloomBits = 1 shl 14, // large bloom to minimize false positives
            bloomHashes = 7,
            rateless = com.tap.synk.conflictsync.rateless.RatelessParams(
                ibltCellsPerSymbol = 256,
                ibltHashFunctions = 3,
                maxSymbols = 512,
            ),
        )

        val stats = clientSynk.conflictSync(CRDT::class, transport, params)

        // Bounded: must terminate well before maxSymbols
        assertTrue(
            stats.symbolsTx < params.rateless.maxSymbols && stats.symbolsRx < params.rateless.maxSymbols,
            "rateless used too many symbols: tx=${stats.symbolsTx}, rx=${stats.symbolsRx}",
        )
    }

    private fun listSource(list: List<CRDT>): StateSource<CRDT> = object : StateSource<CRDT> {
        override suspend fun all(): Flow<CRDT> = flow { list.forEach { emit(it) } }
        override suspend fun byId(id: String): CRDT? = list.find { it.id == id }
    }

    private fun upsert(list: MutableList<CRDT>, obj: CRDT) {
        val idx = list.indexOfFirst { it.id == obj.id }
        if (idx >= 0) list[idx] = obj else list.add(obj)
    }
}
