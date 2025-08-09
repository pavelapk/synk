package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.storageConfig
import com.tap.synk.conflictsync.digest.blockDigest
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import com.tap.synk.conflictsync.net.EosFromA
import com.tap.synk.conflictsync.net.EosReply
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.net.InitBloomReply
import com.tap.synk.conflictsync.net.SessionId
import com.tap.synk.conflictsync.rateless.Symbol
import com.tap.synk.conflictsync.server.ConflictSyncServer
import com.tap.synk.datasource.StateSource
import com.tap.synk.recordChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

class TransportEosPayloadTest {
    @Test
    fun eos_blocksBMissing_matches_hashesMissingAtA_filtering() = runBlocking {
        // A: 1,2 ; B: 2,3 (partial overlap)
        val aObjects = listOf(
            CRDT("1", "A1", "L1", 11),
            CRDT("2", "A2", "L2", null),
        )
        val bObjects = listOf(
            CRDT("2", "B2", "L2", 22),
            CRDT("3", "B3", "L3", 33),
        )
        val clientDb = aObjects.toMutableList()
        val serverDb = bObjects.toMutableList()

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
        var capturedEos: EosFromA? = null
        var capturedReply: EosReply? = null

        val transport = object : ConflictSyncTransport {
            override suspend fun initBloom(req: InitBloom): InitBloomReply = server.initBloom(CRDT::class, req)
            override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> = server.rateless(session, client)
            override suspend fun eos(session: SessionId, msg: EosFromA): EosReply {
                capturedEos = msg
                val reply = server.eos(session, msg)
                capturedReply = reply
                return reply
            }
        }

        val params = ConflictSyncParams()
        clientSynk.conflictSync(CRDT::class, transport, params)

        // Compute expected missing blocks at B from captured hashesMissingAtA
        val decomposer = Decomposer(serverSynk.synkAdapterStore, serverSynk.factory)
        val namespace = CRDT::class.qualifiedName!!
        val blocksB = serverDb.flatMap { decomposer.decompose(namespace, CRDT::class, it) }
        val expected = blocksB.filter {
            val h = blockDigest(it.key.namespace, it.key.id, it.key.field, it.value, it.hlc, params.digestSeed)
            capturedEos!!.hashesMissingAtA.contains(h)
        }

        assertEquals(expected.sortedBy { it.key.id + it.key.field }, capturedReply!!.blocksBMissing.sortedBy { it.key.id + it.key.field })
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

