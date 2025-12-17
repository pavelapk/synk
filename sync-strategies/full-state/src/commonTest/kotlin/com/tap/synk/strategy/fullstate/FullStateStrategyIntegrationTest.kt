package com.tap.synk.strategy.fullstate

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.config.CustomClockStorageConfiguration
import com.tap.synk.recordChange
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncStateSource
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.SyncMergeHandler
import com.tap.synk.sync.SyncMode
import com.tap.synk.sync.SyncOptions
import com.tap.synk.sync.sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

@OptIn(ExperimentalTime::class)
class FullStateStrategyIntegrationTest {

    @Test
    fun `initiator and responder exchange complete replicas`() = runBlocking {
        val adapter = ContactAdapter()
        val initiatorData = mutableMapOf(
            "1" to ContactRecord(id = "1", name = "Alice", lastName = "Jenkins", phone = 1111),
            "3" to ContactRecord(id = "3", name = "Cara", lastName = "Miles", phone = 3333),
        )
        val responderData = mutableMapOf(
            "2" to ContactRecord(id = "2", name = "Bob", lastName = "Stone", phone = 2222),
            "4" to ContactRecord(id = "4", name = "Daria", lastName = "Clark", phone = 4444),
        )

        val initiatorInitialSize = initiatorData.size
        val responderInitialSize = responderData.size

        val initiatorSynk = buildSynk(adapter, initiatorData, "initiator")
        val responderSynk = buildSynk(adapter, responderData, "responder")

        initiatorData.values.forEach { initiatorSynk.recordChange(it) }
        responderData.values.forEach { responderSynk.recordChange(it) }

        val link = LinkedTransport()
        try {
            val responderJob = async {
                responderSynk.sync<ContactRecord>(
                    role = SyncRole.RESPONDER,
                    transport = link.responder(),
                    configuration = SyncConfiguration(pageSize = 8),
                )
            }

            val initiatorStats = initiatorSynk.sync<ContactRecord>(
                role = SyncRole.INITIATOR,
                transport = link.initiator(),
                configuration = SyncConfiguration(pageSize = 8),
            )
            val responderStats = responderJob.await()

            assertStats(
                stats = initiatorStats,
                expectedOutbound = expectedPages(initiatorInitialSize, 8),
                expectedInbound = expectedPages(responderInitialSize, 8),
                expectedScanned = initiatorInitialSize,
            )
            assertStats(
                stats = responderStats,
                expectedOutbound = expectedPages(responderInitialSize, 8),
                expectedInbound = expectedPages(initiatorInitialSize, 8),
                expectedScanned = responderInitialSize,
            )

            val expected = (initiatorData.values + responderData.values).associateBy { it.id }

            assertEquals(expected.keys, initiatorData.keys)
            assertEquals(expected.keys, responderData.keys)

            assertEquals(expected.values.sortedBy { it.id }, initiatorData.values.sortedBy { it.id })
            assertEquals(expected.values.sortedBy { it.id }, responderData.values.sortedBy { it.id })
        } finally {
            link.close()
        }
    }

    @Test
    fun `large overlapping replicas converge`() = runBlocking {
        val adapter = ContactAdapter()
        val commonIds = (50..150).map(Int::toString)
        val initiatorData = buildMap<String, ContactRecord> {
            (1..120).forEach { id ->
                val stringId = id.toString()
                put(
                    stringId,
                    if (stringId in commonIds) {
                        ContactRecord(id = stringId, name = "User$stringId", lastName = "Shared", phone = id * 10)
                    } else {
                        ContactRecord(id = stringId, name = "User$stringId", lastName = "Initiator", phone = id * 100)
                    },
                )
            }
        }.toMutableMap()
        val responderData = buildMap<String, ContactRecord> {
            (80..200).forEach { id ->
                val stringId = id.toString()
                put(
                    stringId,
                    if (stringId in commonIds) {
                        ContactRecord(id = stringId, name = "User$stringId", lastName = "Shared", phone = id * 5) // conflict
                    } else {
                        ContactRecord(id = stringId, name = "User$stringId", lastName = "Responder", phone = id * 1000)
                    },
                )
            }
        }.toMutableMap()

        val initiatorInitialSize = initiatorData.size
        val responderInitialSize = responderData.size

        val initiatorSynk = buildSynk(adapter, initiatorData, "initiator-large")
        val responderSynk = buildSynk(adapter, responderData, "responder-large")

        initiatorData.values.forEach { initiatorSynk.recordChange(it) }
        responderData.values.forEach { responderSynk.recordChange(it) }

        val link = LinkedTransport()
        try {
            val responderJob = async {
                responderSynk.sync<ContactRecord>(
                    role = SyncRole.RESPONDER,
                    transport = link.responder(),
                    configuration = SyncConfiguration(pageSize = 64),
                )
            }

            val initiatorStats = initiatorSynk.sync<ContactRecord>(
                role = SyncRole.INITIATOR,
                transport = link.initiator(),
                configuration = SyncConfiguration(pageSize = 64),
            )
            val responderStats = responderJob.await()

            assertStats(
                stats = initiatorStats,
                expectedOutbound = expectedPages(initiatorInitialSize, 64),
                expectedInbound = expectedPages(responderInitialSize, 64),
                expectedScanned = initiatorInitialSize,
            )
            assertStats(
                stats = responderStats,
                expectedOutbound = expectedPages(responderInitialSize, 64),
                expectedInbound = expectedPages(initiatorInitialSize, 64),
                expectedScanned = responderInitialSize,
            )

            val expected = (initiatorData.values + responderData.values)
                .associateBy { it.id }

            assertEquals(expected.size, initiatorData.size)
            assertEquals(expected.size, responderData.size)
            assertEquals(expected.keys, initiatorData.keys)
            assertEquals(expected.keys, responderData.keys)
            assertEquals(expected.values.sortedBy { it.id }, initiatorData.values.sortedBy { it.id })
            assertEquals(expected.values.sortedBy { it.id }, responderData.values.sortedBy { it.id })
        } finally {
            link.close()
        }
    }

    @Test
    fun `incremental sync exchanges only changes since cursor`() = runBlocking {
        val adapter = ContactAdapter()
        val initiatorData = mutableMapOf(
            "1" to ContactRecord(id = "1", name = "Alice", lastName = "Jenkins", phone = 1111),
            "3" to ContactRecord(id = "3", name = "Cara", lastName = "Miles", phone = 3333),
        )
        val responderData = mutableMapOf(
            "2" to ContactRecord(id = "2", name = "Bob", lastName = "Stone", phone = 2222),
            "4" to ContactRecord(id = "4", name = "Daria", lastName = "Clark", phone = 4444),
        )

        val initiatorUpdatedAt = mutableMapOf<String, Instant>()
        val responderUpdatedAt = mutableMapOf<String, Instant>()
        initiatorData.keys.forEach { id -> initiatorUpdatedAt[id] = Instant.DISTANT_PAST }
        responderData.keys.forEach { id -> responderUpdatedAt[id] = Instant.DISTANT_PAST }

        val initiatorSynk = buildTimestampedSynk(adapter, initiatorData, initiatorUpdatedAt, "initiator-incremental")
        val responderSynk = buildTimestampedSynk(adapter, responderData, responderUpdatedAt, "responder-incremental")

        val initiatorInitialSize = initiatorData.size
        val responderInitialSize = responderData.size

        initiatorData.values.forEach { initiatorSynk.recordChange(it) }
        responderData.values.forEach { responderSynk.recordChange(it) }

        val link = LinkedTransport()
        try {
            val initiatorModeConfig = SyncConfiguration(
                pageSize = 8,
                options = mapOf(
                    SyncOptions.Mode to SyncMode.INCREMENTAL_SINCE_CURSOR,
                    SyncOptions.PeerId to "initiator",
                    SyncOptions.RemotePeerId to "responder",
                ),
            )
            val responderModeConfig = SyncConfiguration(
                pageSize = 8,
                options = mapOf(
                    SyncOptions.Mode to SyncMode.INCREMENTAL_SINCE_CURSOR,
                    SyncOptions.PeerId to "responder",
                ),
            )

            val responderJob = async {
                responderSynk.sync<ContactRecord>(
                    role = SyncRole.RESPONDER,
                    transport = link.responder(),
                    configuration = responderModeConfig,
                )
            }

            val initiatorStats = initiatorSynk.sync<ContactRecord>(
                role = SyncRole.INITIATOR,
                transport = link.initiator(),
                configuration = initiatorModeConfig,
            )
            val responderStats = responderJob.await()

            assertEquals(initiatorInitialSize, initiatorStats.scanned)
            assertEquals(responderInitialSize, responderStats.scanned)

            val initiatorBefore = initiatorData.getValue("1")
            val responderBefore = responderData.getValue("2")

            val initiatorUpdated = initiatorBefore.copy(phone = 9999)
            val responderUpdated = responderBefore.copy(phone = 8888)

            initiatorData["1"] = initiatorUpdated
            initiatorUpdatedAt["1"] = Clock.System.now()
            initiatorSynk.recordChange(initiatorUpdated, initiatorBefore)

            responderData["2"] = responderUpdated
            responderUpdatedAt["2"] = Clock.System.now()
            responderSynk.recordChange(responderUpdated, responderBefore)

            val secondLink = LinkedTransport()
            try {
                val secondResponderJob = async {
                    responderSynk.sync<ContactRecord>(
                        role = SyncRole.RESPONDER,
                        transport = secondLink.responder(),
                        configuration = responderModeConfig,
                    )
                }

                val secondInitiatorStats = initiatorSynk.sync<ContactRecord>(
                    role = SyncRole.INITIATOR,
                    transport = secondLink.initiator(),
                    configuration = initiatorModeConfig,
                )
                val secondResponderStats = secondResponderJob.await()

                assertEquals(1, secondInitiatorStats.scanned)
                assertEquals(1, secondResponderStats.scanned)

                assertEquals(initiatorUpdated.phone, initiatorData.getValue("1").phone)
                assertEquals(initiatorUpdated.phone, responderData.getValue("1").phone)
                assertEquals(responderUpdated.phone, initiatorData.getValue("2").phone)
                assertEquals(responderUpdated.phone, responderData.getValue("2").phone)
            } finally {
                secondLink.close()
            }
        } finally {
            link.close()
        }
    }

    @Test
    fun `server tracks cursors per client`() = runBlocking {
        val adapter = ContactAdapter()

        val old = Instant.parse("2020-01-01T00:00:00Z")

        val serverData = mutableMapOf<String, ContactRecord>()
        val serverUpdatedAt = mutableMapOf<String, Instant>()
        val serverSynk = buildTimestampedSynk(adapter, serverData, serverUpdatedAt, "server-multi-client")

        val client1Data = mutableMapOf(
            "c1" to ContactRecord(id = "c1", name = "Client1", lastName = "One", phone = 1),
        )
        val client1UpdatedAt = mutableMapOf("c1" to old)
        val client1Synk = buildTimestampedSynk(adapter, client1Data, client1UpdatedAt, "client1")

        val client2Data = mutableMapOf(
            "c2" to ContactRecord(id = "c2", name = "Client2", lastName = "Two", phone = 2),
        )
        val client2UpdatedAt = mutableMapOf("c2" to old)
        val client2Synk = buildTimestampedSynk(adapter, client2Data, client2UpdatedAt, "client2")

        client1Synk.recordChange(client1Data.getValue("c1"))
        client2Synk.recordChange(client2Data.getValue("c2"))

        val serverConfig = SyncConfiguration(
            pageSize = 8,
            options = mapOf(
                SyncOptions.Mode to SyncMode.INCREMENTAL_SINCE_CURSOR,
                SyncOptions.PeerId to "server",
            ),
        )

        val client1Config = SyncConfiguration(
            pageSize = 8,
            options = mapOf(
                SyncOptions.Mode to SyncMode.INCREMENTAL_SINCE_CURSOR,
                SyncOptions.PeerId to "client1",
                SyncOptions.RemotePeerId to "server",
            ),
        )

        val link1 = LinkedTransport()
        try {
            val serverJob = async {
                serverSynk.sync<ContactRecord>(
                    role = SyncRole.RESPONDER,
                    transport = link1.responder(),
                    configuration = serverConfig,
                )
            }

            client1Synk.sync<ContactRecord>(
                role = SyncRole.INITIATOR,
                transport = link1.initiator(),
                configuration = client1Config,
            )
            serverJob.await()
        } finally {
            link1.close()
        }

        val client2Config = SyncConfiguration(
            pageSize = 8,
            options = mapOf(
                SyncOptions.Mode to SyncMode.INCREMENTAL_SINCE_CURSOR,
                SyncOptions.PeerId to "client2",
                SyncOptions.RemotePeerId to "server",
            ),
        )

        val link2 = LinkedTransport()
        try {
            val serverJob = async {
                serverSynk.sync<ContactRecord>(
                    role = SyncRole.RESPONDER,
                    transport = link2.responder(),
                    configuration = serverConfig,
                )
            }

            val client2Stats = client2Synk.sync<ContactRecord>(
                role = SyncRole.INITIATOR,
                transport = link2.initiator(),
                configuration = client2Config,
            )
            serverJob.await()

            assertEquals(1, client2Stats.scanned)
        } finally {
            link2.close()
        }

        assertEquals(setOf("c1", "c2"), serverData.keys)
    }

    private fun buildSynk(
        adapter: ContactAdapter,
        backing: MutableMap<String, ContactRecord>,
        name: String,
    ): Synk {
        val storage = CustomClockStorageConfiguration(
            filePath = "/$name".toPath(),
            fileSystem = FakeFileSystem(),
        )
        val mergeHandler = SyncMergeHandler<ContactRecord> { value -> backing[value.id] = value }
        return Synk.Builder(storage)
            .registerSynkAdapter(adapter)
            .registerStateSource(MapStateSource(backing))
            .onMerged(mergeHandler)
            .defaultStrategy(FullStateStrategyFactory)
            .build()
    }

    private fun buildTimestampedSynk(
        adapter: ContactAdapter,
        backing: MutableMap<String, ContactRecord>,
        updatedAt: MutableMap<String, Instant>,
        name: String,
    ): Synk {
        val storage = CustomClockStorageConfiguration(
            filePath = "/$name".toPath(),
            fileSystem = FakeFileSystem(),
        )
        val mergeHandler = SyncMergeHandler<ContactRecord> { value -> backing[value.id] = value }
        return Synk.Builder(storage)
            .registerSynkAdapter(adapter)
            .registerStateSource(TimestampedMapStateSource(backing, updatedAt))
            .onMerged(mergeHandler)
            .defaultStrategy(FullStateStrategyFactory)
            .build()
    }

    private fun assertStats(
        stats: SyncStats,
        expectedOutbound: Int,
        expectedInbound: Int,
        expectedScanned: Int,
    ) {
        assertEquals(expectedScanned, stats.scanned)
        assertEquals(expectedOutbound, stats.outboundMessages)
        assertEquals(expectedInbound, stats.inboundMessages)
        if (expectedOutbound > 0) {
            assertTrue(stats.outboundBytes > 0)
        }
        if (expectedInbound > 0) {
            assertTrue(stats.inboundBytes > 0)
        }
    }

    private fun expectedPages(total: Int, pageSize: Int): Int {
        if (total == 0) return 0
        return (total + pageSize - 1) / pageSize
    }

    private class MapStateSource<T : Any>(
        private val backing: MutableMap<String, T>,
    ) : SyncStateSource<T> {
        override suspend fun scan(afterId: String?, limit: Int): List<T> {
            val sorted = backing.entries.sortedBy { it.key }
            val start = afterId?.let { id ->
                sorted.indexOfFirst { it.key == id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return sorted.drop(start).take(limit).map { it.value }
        }

        override suspend fun load(id: String): T? = backing[id]
    }

    private class TimestampedMapStateSource<T : Any>(
        private val backing: MutableMap<String, T>,
        private val updatedAt: MutableMap<String, Instant>,
    ) : SyncStateSource<T> {
        override suspend fun scan(afterId: String?, limit: Int): List<T> {
            val sorted = backing.entries.sortedBy { it.key }
            val start = afterId?.let { id ->
                sorted.indexOfFirst { it.key == id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return sorted.drop(start).take(limit).map { it.value }
        }

        override suspend fun scanUpdated(since: Instant?, afterId: String?, limit: Int): List<T> {
            if (since == null) return scan(afterId, limit)
            val sorted = backing.entries.sortedBy { it.key }
            val filtered = sorted.filter { entry ->
                val value = updatedAt[entry.key] ?: Instant.DISTANT_PAST
                value >= since
            }
            val start = afterId?.let { id ->
                filtered.indexOfFirst { it.key == id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return filtered.drop(start).take(limit).map { it.value }
        }

        override suspend fun load(id: String): T? = backing[id]
    }

    private class LinkedTransport {
        private val initiatorOutbound = Channel<SyncRequest>(Channel.BUFFERED)
        private val responderOutbound = Channel<SyncRequest>(Channel.BUFFERED)

        fun initiator(): SyncTransport = endpoint(
            outbound = initiatorOutbound,
            inbound = responderOutbound,
        )

        fun responder(): SyncTransport = endpoint(
            outbound = responderOutbound,
            inbound = initiatorOutbound,
        )

        fun close() {
            initiatorOutbound.close()
            responderOutbound.close()
        }

        private fun endpoint(
            outbound: Channel<SyncRequest>,
            inbound: Channel<SyncRequest>,
        ): SyncTransport {
            return object : SyncTransport {
                override fun exchange(outboundFlow: Flow<SyncRequest>): Flow<SyncResponse> = channelFlow {
                    launch {
                        outboundFlow.collect { request ->
                            outbound.send(request)
                        }
                        outbound.close()
                    }
                    launch {
                        for (request in inbound) {
                            send(
                                SyncResponse(
                                    channel = request.channel,
                                    payload = request.payload,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    @Serializable
    private data class ContactRecord(
        val id: String,
        val name: String,
        @SerialName("last_name") val lastName: String,
        val phone: Int?,
    )

    private class ContactAdapter : SynkAdapter<ContactRecord> {
        override fun resolveId(crdt: ContactRecord): String = crdt.id

        override fun encode(crdt: ContactRecord): Map<String, String> = buildMap {
            put("id", crdt.id)
            put("name", crdt.name)
            put("last_name", crdt.lastName)
            crdt.phone?.let { put("phone", it.toString()) }
        }

        override fun decode(map: Map<String, String>): ContactRecord {
            return ContactRecord(
                id = map.getValue("id"),
                name = map.getValue("name"),
                lastName = map.getValue("last_name"),
                phone = map["phone"]?.toInt(),
            )
        }
    }
}
