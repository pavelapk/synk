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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

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

    private fun buildSynk(
        adapter: ContactAdapter,
        backing: MutableMap<String, ContactRecord>,
        name: String,
    ): Synk {
        val storage = CustomClockStorageConfiguration(
            filePath = "/$name".toPath(),
            fileSystem = FakeFileSystem(),
        )
        val stateSource = MapStateSource(backing)
        val mergeHandler = SyncMergeHandler<ContactRecord> { value -> stateSource.upsert(value.id, value) }
        return Synk.Builder(storage)
            .registerSynkAdapter(adapter)
            .registerStateSource(stateSource)
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
        private val updatedAt = backing.keys.associateWith { Clock.System.now() }.toMutableMap()

        override suspend fun scanUpdatedSince(since: Instant?, afterId: String?, limit: Int): List<T> {
            val sorted = backing.entries
                .filter { (id, _) -> isUpdatedSince(id, since) }
                .sortedBy { it.key }
            val start = afterId?.let { id ->
                sorted.indexOfFirst { it.key == id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return sorted.drop(start).take(limit).map { it.value }
        }

        override suspend fun scan(afterId: String?, limit: Int): List<T> = scanUpdatedSince(null, afterId, limit)

        override suspend fun load(id: String): T? = backing[id]

        fun upsert(id: String, value: T, updated: Instant = Clock.System.now()) {
            backing[id] = value
            updatedAt[id] = updated
        }

        private fun isUpdatedSince(id: String, since: Instant?): Boolean {
            since ?: return true
            val updated = updatedAt[id] ?: return false
            return updated >= since
        }
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
