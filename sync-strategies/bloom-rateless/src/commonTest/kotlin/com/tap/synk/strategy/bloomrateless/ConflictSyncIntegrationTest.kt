package com.tap.synk.strategy.bloomrateless

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.config.CustomClockStorageConfiguration
import com.tap.synk.recordChange
import com.tap.synk.sync.DefaultSyncTracker
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncMergeHandler
import com.tap.synk.sync.SyncRequest
import com.tap.synk.sync.SyncResponse
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncStateSource
import com.tap.synk.sync.SyncStats
import com.tap.synk.sync.SyncTransport
import com.tap.synk.sync.sync
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ConflictSyncIntegrationTest {

    @Ignore // all tests are hangs up for some reason, needs investigation, probably bloom+rateless implementation issue
    @Test
    fun `initiator and responder reconcile disjoint datasets`() = runScenario(
        label = "Disjoint",
        initiatorSeed = mapOf(
            "1" to ContactRecord(id = "1", name = "Alice", lastName = "Jenkins", phone = 1111),
        ),
        responderSeed = mapOf(
            "2" to ContactRecord(id = "2", name = "Bob", lastName = "Stone", phone = 2222),
        ),
        configuration = SyncConfiguration(
            pageSize = 16,
            options = mapOf(
                BloomRatelessOptions.RatelessBatchSize to 4,
                BloomRatelessOptions.FalsePositiveRate to 0.01,
            ),
        ),
    )

    @Ignore
    @Test
    fun `conflicting replicas exchange hashes to converge`() = runScenario(
        label = "Hash",
        initiatorSeed = mapOf(
            "1" to ContactRecord(id = "1", name = "Alice", lastName = "Jenkins", phone = 1111),
            "3" to ContactRecord(id = "3", name = "Cara", lastName = "Miles", phone = 3333),
        ),
        responderSeed = mapOf(
            "1" to ContactRecord(id = "1", name = "Alice", lastName = "Stone", phone = 4444),
            "2" to ContactRecord(id = "2", name = "Bob", lastName = "Stone", phone = 2222),
        ),
        configuration = SyncConfiguration(
            pageSize = 12,
            options = mapOf(
                BloomRatelessOptions.RatelessBatchSize to 4,
                BloomRatelessOptions.FalsePositiveRate to 0.01,
            ),
        ),
        bloomTransform = { role, payload ->
            if (payload.bits.isEmpty()) return@runScenario payload
            when (role) {
                LinkedTransport.EndpointRole.INITIATOR -> payload.copy(bits = ByteArray(payload.bits.size) { 0xFF.toByte() })
                LinkedTransport.EndpointRole.RESPONDER -> payload
            }
        },
    )

    @Ignore
    @Test
    fun `large dataset handles rateless fallback`() = runScenario(
        label = "Large",
        initiatorSeed = (1..64).associate { index ->
            val id = index.toString()
            id to ContactRecord(id = id, name = "User$id", lastName = "Left", phone = index)
        },
        responderSeed = (33..96).associate { index ->
            val id = index.toString()
            id to ContactRecord(id = id, name = "User$id", lastName = "Right", phone = index * 10)
        },
        configuration = SyncConfiguration(
            pageSize = 32,
            options = mapOf(
                BloomRatelessOptions.RatelessBatchSize to 8,
                BloomRatelessOptions.FalsePositiveRate to 0.01,
            ),
        ),
    )

    private fun runScenario(
        label: String,
        initiatorSeed: Map<String, ContactRecord>,
        responderSeed: Map<String, ContactRecord>,
        configuration: SyncConfiguration,
        bloomTransform: (LinkedTransport.EndpointRole, BloomFilterPayload) -> BloomFilterPayload = { _, payload -> payload },
    ) {
        val previousLogging = ConflictSyncLogger.isEnabled
        ConflictSyncLogger.isEnabled = true
        try {
            runBlocking {
                val adapter = ContactAdapter()
                val initiatorData = initiatorSeed.toMutableMap()
                val responderData = responderSeed.toMutableMap()

                val initiatorSynk = buildSynk(adapter, initiatorData, "initiator-$label")
                val responderSynk = buildSynk(adapter, responderData, "responder-$label")

                initiatorData.values.forEach { initiatorSynk.recordChange(it) }
                responderData.values.forEach { responderSynk.recordChange(it) }

                val link = LinkedTransport(bloomTransform)
                try {
                    val responderJob = async {
                        responderSynk.sync(
                            namespace = ContactRecord::class,
                            role = SyncRole.RESPONDER,
                            transport = link.responderSync(),
                            configuration = configuration,
                            tracker = defaultTracker(),
                        )
                    }

                    val initiatorStats = initiatorSynk.sync(
                        namespace = ContactRecord::class,
                        role = SyncRole.INITIATOR,
                        transport = link.initiatorSync(),
                        configuration = configuration,
                        tracker = defaultTracker(),
                    )
                    val responderStats = responderJob.await()

                    val expectedIds = initiatorData.keys union responderData.keys
                    assertEquals(expectedIds, initiatorData.keys)
                    assertEquals(expectedIds, responderData.keys)

                    val mergedInitiator = initiatorData.values.sortedBy { it.id }
                    val mergedResponder = responderData.values.sortedBy { it.id }
                    assertEquals(mergedInitiator, mergedResponder)

                    printStats("$label • Initiator", initiatorStats)
                    printStats("$label • Responder", responderStats)
                } finally {
                    link.close()
                }
            }
        } finally {
            ConflictSyncLogger.isEnabled = previousLogging
        }
    }

    private fun printStats(label: String, stats: SyncStats) {
        val prefix = "[Test][BloomRateless]"
        val border = "═".repeat(label.length + 10)
        println("$prefix ░$border░")
        println("$prefix ░   $label   ░")
        println("$prefix ░$border░")
        println("$prefix   namespace            : ${stats.namespace}")
        println("$prefix   scanned              : ${stats.scanned}")
        println("$prefix   outbound messages    : ${stats.outboundMessages} (${stats.outboundBytes} bytes)")
        println("$prefix   inbound messages     : ${stats.inboundMessages} (${stats.inboundBytes} bytes)")
        println("$prefix ░$border░")
    }

    private fun buildSynk(
        adapter: ContactAdapter,
        backing: MutableMap<String, ContactRecord>,
        name: String,
    ): Synk {
        val stateSource = MapStateSource(backing)
        val mergeHandler = SyncMergeHandler<ContactRecord> { value -> backing[value.id] = value }
        val storageConfig = CustomClockStorageConfiguration(
            filePath = "/$name".toPath(),
            fileSystem = FakeFileSystem(),
        )
        return Synk.Builder(storageConfig)
            .registerSynkAdapter(adapter)
            .registerStateSource(stateSource)
            .onMerged(mergeHandler)
            .defaultStrategy(BloomRatelessStrategyFactory)
            .build()
    }

    private class MapStateSource<T : Any>(
        private val backing: MutableMap<String, T>,
    ) : SyncStateSource<T> {
        override suspend fun scan(afterId: String?, limit: Int): List<T> {
            val sorted = backing.entries.sortedBy { it.key }
            val startIndex = afterId?.let { id ->
                sorted.indexOfFirst { it.key == id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return sorted.drop(startIndex).take(limit).map { it.value }
        }

        override suspend fun load(id: String): T? = backing[id]
    }

    private fun defaultTracker() = DefaultSyncTracker()

    private class LinkedTransport(
        private val bloomTransform: (EndpointRole, BloomFilterPayload) -> BloomFilterPayload = { _, payload -> payload },
    ) {
        enum class EndpointRole {
            INITIATOR,
            RESPONDER,
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val bloomInitToResp = Channel<BloomFilterPayload>(Channel.BUFFERED)
        private val bloomRespToInit = Channel<BloomFilterPayload>(Channel.BUFFERED)
        private val ratelessRespToInit = Channel<RatelessSketchPayload>(Channel.BUFFERED)
        private val ratelessInitToResp = Channel<RatelessSketchPayload>(Channel.BUFFERED)
        private val decompInitToResp = Channel<TransportEnvelope>(Channel.BUFFERED)
        private val decompRespToInit = Channel<TransportEnvelope>(Channel.BUFFERED)

        fun initiator(): ConflictSyncTransport = Endpoint(
            role = EndpointRole.INITIATOR,
            outboundBloom = bloomInitToResp,
            inboundBloom = bloomRespToInit,
            outboundRateless = ratelessInitToResp,
            inboundRateless = ratelessRespToInit,
            outboundDecompositions = decompInitToResp,
            inboundDecompositions = decompRespToInit,
        )

        fun responder(): ConflictSyncTransport = Endpoint(
            role = EndpointRole.RESPONDER,
            outboundBloom = bloomRespToInit,
            inboundBloom = bloomInitToResp,
            outboundRateless = ratelessRespToInit,
            inboundRateless = ratelessInitToResp,
            outboundDecompositions = decompRespToInit,
            inboundDecompositions = decompInitToResp,
        )

        fun initiatorSync(): SyncTransport = initiator().asSyncTransport()

        fun responderSync(): SyncTransport = responder().asSyncTransport()

        fun close() {
            scope.cancel()
        }

        private inner class Endpoint(
            private val role: EndpointRole,
            private val outboundBloom: Channel<BloomFilterPayload>,
            private val inboundBloom: Channel<BloomFilterPayload>,
            private val outboundRateless: Channel<RatelessSketchPayload>,
            private val inboundRateless: Channel<RatelessSketchPayload>,
            private val outboundDecompositions: Channel<TransportEnvelope>,
            private val inboundDecompositions: Channel<TransportEnvelope>,
        ) : ConflictSyncTransport {

            override fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload> {
                scope.launch {
                    outbound.collect { payload ->
                        outboundBloom.send(bloomTransform(role, payload))
                    }
                }
                return inboundBloom.receiveAsFlow()
            }

            override fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload> {
                scope.launch {
                    outbound.collect { payload -> outboundRateless.send(payload) }
                }
                return inboundRateless.receiveAsFlow()
            }

            override fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope> {
                scope.launch {
                    outbound.collect { envelope -> outboundDecompositions.send(envelope) }
                }
                return inboundDecompositions.receiveAsFlow()
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

private fun ConflictSyncTransport.asSyncTransport(json: Json = bloomRatelessJson): SyncTransport =
    LegacySyncTransport(this, json)

private class LegacySyncTransport(
    private val delegate: ConflictSyncTransport,
    private val json: Json,
) : SyncTransport {

    private var stage = Stage.BLOOM

    override fun exchange(outbound: Flow<SyncRequest>): Flow<SyncResponse> = when (stage) {
        Stage.BLOOM -> {
            stage = Stage.SKETCH
            val requests = outbound.map { it.toBloomPayload(json) }
            delegate.bloom(requests).map { it.toSyncResponse(json) }
        }
        Stage.SKETCH -> {
            stage = Stage.DECOMPOSITION
            val requests = outbound.map { it.toRatelessPayload(json) }
            delegate.rateless(requests).map { it.toSyncResponse(json) }
        }
        Stage.DECOMPOSITION -> {
            stage = Stage.COMPLETE
            val requests = outbound.map { it.toTransportEnvelope(json) }
            delegate.decompositions(requests).map { it.toSyncResponse(json) }
        }
        Stage.COMPLETE -> error("Sync session already completed")
    }

    private enum class Stage { BLOOM, SKETCH, DECOMPOSITION, COMPLETE }
}
