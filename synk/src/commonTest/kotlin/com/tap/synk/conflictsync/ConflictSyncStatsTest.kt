package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.storageConfig
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import com.tap.synk.conflictsync.net.EosFromA
import com.tap.synk.conflictsync.net.EosReply
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.net.InitBloomReply
import com.tap.synk.conflictsync.net.SessionId
import com.tap.synk.conflictsync.rateless.Symbol
import com.tap.synk.datasource.StateSource
import com.tap.synk.recordChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

class ConflictSyncStatsTest {
    @Test
    fun stats_match_block_counts_in_disjoint_sets() = runBlocking {
        // Build A and B with no overlap and multiple fields per object to exercise block counts.
        val aObjects = listOf(
            CRDT("1", "A1", "L1", 10),
            CRDT("2", "A2", "L2", null),
        )
        val bObjects = listOf(
            CRDT("3", "B3", "L3", 30),
            CRDT("4", "B4", "L4", 40),
        )
        val clientDb = aObjects.toMutableList()
        val serverDb = bObjects.toMutableList()

        val clientSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(clientDb))
            .onMerged(CRDT::class) { _, obj -> updateList(clientDb, obj) }
            .build()
        clientDb.forEach { clientSynk.recordChange(it, null) }

        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, listSource(serverDb))
            .onMerged(CRDT::class) { _, obj -> updateList(serverDb, obj) }
            .build()
        serverDb.forEach { serverSynk.recordChange(it, null) }

        // Delegate logic to library server; transport is thin
        val transport = object : ConflictSyncTransport {
            private val server = com.tap.synk.conflictsync.server.ConflictSyncServer(serverSynk)
            override suspend fun initBloom(req: InitBloom): InitBloomReply = server.initBloom(CRDT::class, req)
            override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> = server.rateless(session, client)
            override suspend fun eos(session: SessionId, msg: EosFromA): EosReply = server.eos(session, msg)
        }

        val params = ConflictSyncParams(bloomBits = 2048, bloomHashes = 3) // large bloom to avoid false positives
        val stats = clientSynk.conflictSync(CRDT::class, transport, params)

        // For each CRDT, blocks are: id, name, last_name, optional phone (if not null)
        fun blocksOf(list: List<CRDT>): Int = list.sumOf { 3 + if (it.phone != null) 1 else 0 }

        assertEquals(blocksOf(aObjects), stats.sentBlocks)
        assertEquals(blocksOf(bObjects), stats.recvBlocks)
    }

    private fun listSource(list: List<CRDT>): StateSource<CRDT> = object : StateSource<CRDT> {
        override suspend fun all(): Flow<CRDT> = kotlinx.coroutines.flow.flow { list.forEach { emit(it) } }
        override suspend fun byId(id: String): CRDT? = list.find { it.id == id }
    }

    private fun updateList(list: MutableList<CRDT>, obj: CRDT) {
        val i = list.indexOfFirst { it.id == obj.id }
        if (i >= 0) list[i] = obj else list.add(obj)
    }
}

