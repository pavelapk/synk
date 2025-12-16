package com.tap.synk.sync

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.CustomClockStorageConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class SynkResponderTest {

    @Test
    fun serveResponderProcessesEachSession() = runBlocking {
        val storage = CustomClockStorageConfiguration(
            filePath = "/tmp".toPath(),
            fileSystem = FakeFileSystem(),
        )
        val state = mutableMapOf<String, CRDT>()
        val strategy = RecordingStrategyFactory()

        val synk = Synk.Builder(storage)
            .registerSynkAdapter(CRDTAdapter())
            .registerStateSource(TestStateSource(state))
            .defaultStrategy(strategy)
            .build()

        val stats = mutableListOf<SyncStats>()

        val sessions = flowOf(
            SyncResponderSession(
                transport = NoOpTransport("first"),
                onResult = { result -> stats += result.getOrThrow() },
            ),
            SyncResponderSession(
                transport = NoOpTransport("second"),
                onResult = { result -> stats += result.getOrThrow() },
            ),
        )

        synk.serveResponder(
            namespace = CRDT::class,
            sessions = sessions,
        )

        assertEquals(2, strategy.createdCount)
        assertEquals(listOf("first", "second"), strategy.executedWithLabels)
        assertEquals(
            listOf(CRDT::class.qualifiedName, CRDT::class.qualifiedName),
            stats.map { it.namespace },
        )
    }
}

private class NoOpTransport(
    val label: String,
) : SyncTransport {
    override fun exchange(outbound: Flow<SyncRequest>): Flow<SyncResponse> = emptyFlow()
}

private class RecordingStrategyFactory : SyncStrategyFactory {
    var createdCount: Int = 0
        private set
    val executedWithLabels: MutableList<String> = mutableListOf()

    override fun <T : Any> createSession(role: SyncRole, context: SyncContext<T>): SyncSession<T> {
        require(role == SyncRole.RESPONDER)
        createdCount += 1
        return RecordingSession<T>(
            namespace = context.namespace,
            onRun = { transport ->
                val label = (transport as? NoOpTransport)?.label ?: "unknown"
                executedWithLabels += label
            },
        )
    }
}

private class RecordingSession<T : Any>(
    private val namespace: String,
    private val onRun: (SyncTransport) -> Unit,
) : SyncSession<T> {
    override suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration,
    ): SyncStats {
        onRun(transport)
        tracker.finish()
        return SyncStats(
            namespace = namespace,
            scanned = 0,
            outboundMessages = 0,
            outboundBytes = 0,
            inboundMessages = 0,
            inboundBytes = 0,
        )
    }
}

private class TestStateSource(
    private val backing: MutableMap<String, CRDT>,
) : SyncStateSource<CRDT> {
    private val updatedAt: MutableMap<String, Instant> =
        backing.keys.associateWith { Clock.System.now() }.toMutableMap()

    override suspend fun scanUpdatedSince(since: Instant?, afterId: String?, limit: Int): List<CRDT> {
        val sorted = backing.entries
            .filter { (id, _) -> isUpdatedSince(id, since) }
            .sortedBy { it.key }
        val start = afterId?.let { key ->
            sorted.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(1) ?: 0
        } ?: 0
        return sorted.drop(start).take(limit).mapNotNull { it.value }
    }

    override suspend fun scan(afterId: String?, limit: Int): List<CRDT> = scanUpdatedSince(null, afterId, limit)

    override suspend fun load(id: String): CRDT? = backing[id]

    fun upsert(id: String, value: CRDT, updated: Instant = Clock.System.now()) {
        backing[id] = value
        updatedAt[id] = updated
    }

    private fun isUpdatedSince(id: String, since: Instant?): Boolean {
        since ?: return true
        val updated = updatedAt[id] ?: return false
        return updated >= since
    }
}
