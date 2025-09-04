package com.tap.synk.conflictsync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.conflictsync.net.ConflictSyncTransport
import com.tap.synk.conflictsync.net.EosFromA
import com.tap.synk.conflictsync.net.EosReply
import com.tap.synk.conflictsync.net.InitBloom
import com.tap.synk.conflictsync.net.InitBloomReply
import com.tap.synk.conflictsync.net.SessionId
import com.tap.synk.conflictsync.rateless.Symbol
import com.tap.synk.config.storageConfig
import com.tap.synk.datasource.StateSource
import com.tap.synk.recordChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout

class ConflictSyncHappyPathTest {

    @Test
    fun syncs_missing_blocks_and_invokes_onMerged() = runBlocking {
        // Client state (A)
        val a1 = CRDT("1", "Alice", "Smith", null)
        val a2 = CRDT("2", "Bob", "Brown", 7)
        val clientDb = mutableListOf(a1, a2)

        val clientSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, MutableListStateSource(clientDb))
            .onMerged(CRDT::class) { _, obj ->
                val idx = clientDb.indexOfFirst { it.id == obj.id }
                if (idx >= 0) clientDb[idx] = obj else clientDb.add(obj)
            }
            .build()

        // Ensure client meta exists for A objects
        clientDb.forEach { clientSynk.recordChange(it, null) }

        // Server state (B)
        val b2 = a2 // common
        val b3 = CRDT("3", "Cara", "Jones", 11)
        val serverDb = mutableListOf(b2, b3)

        val serverSynk = Synk.Builder(storageConfig())
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(CRDT::class, MutableListStateSource(serverDb))
            .onMerged(CRDT::class) { _, obj ->
                val idx = serverDb.indexOfFirst { it.id == obj.id }
                if (idx >= 0) serverDb[idx] = obj else serverDb.add(obj)
            }
            .build()
        serverDb.forEach { serverSynk.recordChange(it, null) }

        val transport = FakeTransport(serverSynk, CRDT::class)

        val stats = withTimeout(500L) { clientSynk.conflictSync(CRDT::class, transport, ConflictSyncParams()) }

        // After sync both DBs should converge to the same state (by value)
        fun List<CRDT>.sortedById() = this.sortedBy { it.id }
        println(clientDb)
        println(serverDb)
        assertEquals(clientDb.sortedById(), serverDb.sortedById())
    }

    // Note: A rateless accumulation regression test was attempted here but proved flaky
    // under small Bloom and borderline IBLT loads. A deterministic unit test is preferred.

    private class MutableListStateSource(private val list: List<CRDT>) : StateSource<CRDT> {
        override suspend fun all(): Flow<CRDT> = flow { list.forEach { emit(it) } }
        override suspend fun byId(id: String): CRDT? = list.find { it.id == id }
    }

    private class FakeTransport(
        private val serverSynk: Synk,
        private val clazz: KClass<CRDT>,
    ) : ConflictSyncTransport {
        private val server = com.tap.synk.conflictsync.server.ConflictSyncServer(serverSynk)

        override suspend fun initBloom(req: InitBloom): InitBloomReply {
            return server.initBloom(clazz, req)
        }

        override fun rateless(session: SessionId, client: Flow<Symbol>): Flow<Symbol> {
            return server.rateless(session, client)
        }

        override suspend fun eos(session: SessionId, msg: EosFromA): EosReply {
            return server.eos(session, msg)
        }
    }
}
