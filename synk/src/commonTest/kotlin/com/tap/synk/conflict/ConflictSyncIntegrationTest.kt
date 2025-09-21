package com.tap.synk.conflict

import com.tap.synk.CRDT
import com.tap.synk.CRDTAdapter
import com.tap.synk.Synk
import com.tap.synk.config.CustomClockStorageConfiguration
import com.tap.synk.conflictSync
import com.tap.synk.recordChange
import com.tap.synk.respondConflictSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ConflictSyncIntegrationTest {

    @Test
    fun `initiator and responder reconcile disjoint datasets`() {
        val previousLogging = ConflictSyncLogger.isEnabled
        ConflictSyncLogger.isEnabled = true
        try {
            runBlocking {
                val adapter = CRDTAdapter()
                val initiatorData = mutableMapOf(
                    "1" to CRDT(id = "1", name = "Alice", last_name = "Jenkins", phone = 1111),
                )
                val responderData = mutableMapOf(
                    "2" to CRDT(id = "2", name = "Bob", last_name = "Stone", phone = 2222),
                )

                val initiatorSynk = buildSynk(adapter, initiatorData, "initiator")
                val responderSynk = buildSynk(adapter, responderData, "responder")

                initiatorData.values.forEach { initiatorSynk.recordChange(it) }
                responderData.values.forEach { responderSynk.recordChange(it) }

                val transportLink = LinkedTransport()
                try {
                    val configuration = ConflictSyncConfiguration(pageSize = 16, ratelessBatchSize = 4)

                    val responderJob = async {
                        responderSynk.respondConflictSync(
                            namespace = CRDT::class,
                            transport = transportLink.responder(),
                            configuration = configuration,
                        )
                    }

                    val initiatorStats = initiatorSynk.conflictSync(
                        namespace = CRDT::class,
                        transport = transportLink.initiator(),
                        configuration = configuration,
                    )
                    val responderStats = responderJob.await()

                    val expectedIds = setOf("1", "2")
                    assertEquals(expectedIds, initiatorData.keys)
                    assertEquals(expectedIds, responderData.keys)

                    val mergedInitiator = initiatorData.values.sortedBy { it.id }
                    val mergedResponder = responderData.values.sortedBy { it.id }
                    assertEquals(mergedInitiator, mergedResponder)

                    assertTrue(initiatorStats.remoteOnlyHashes >= 0)
                    assertTrue(responderStats.remoteOnlyHashes >= 0)

                    printStats("Disjoint • Initiator", initiatorStats)
                    printStats("Disjoint • Responder", responderStats)
                } finally {
                    transportLink.close()
                }
            }
        } finally {
            ConflictSyncLogger.isEnabled = previousLogging
        }
    }

    @Test
    fun `conflicting replicas exchange hashes to converge`() {
        val previousLogging = ConflictSyncLogger.isEnabled
        ConflictSyncLogger.isEnabled = true
        try {
            runBlocking {
                val adapter = CRDTAdapter()
                val initiatorData = mutableMapOf(
                    "1" to CRDT(id = "1", name = "Alice", last_name = "Jenkins", phone = 1111),
                    "3" to CRDT(id = "3", name = "Cara", last_name = "Miles", phone = 3333),
                )
                val responderData = mutableMapOf(
                    "1" to CRDT(id = "1", name = "Alice", last_name = "Stone", phone = 4444),
                    "2" to CRDT(id = "2", name = "Bob", last_name = "Stone", phone = 2222),
                )

                val initiatorSynk = buildSynk(adapter, initiatorData, "initiator-hash")
                val responderSynk = buildSynk(adapter, responderData, "responder-hash")

                initiatorData.values.forEach { initiatorSynk.recordChange(it) }
                responderData.values.forEach { responderSynk.recordChange(it) }

                val saturatingTransport = LinkedTransport { role, payload ->
                    if (payload.bits.isEmpty()) {
                        return@LinkedTransport payload
                    }

                    when (role) {
                        LinkedTransport.EndpointRole.INITIATOR -> {
                            val saturated = ByteArray(payload.bits.size) { 0xFF.toByte() }
                            payload.copy(bits = saturated)
                        }

                        LinkedTransport.EndpointRole.RESPONDER -> {
                            payload.copy(bits = ByteArray(payload.bits.size))
                        }
                    }
                }

                try {
                    val configuration = ConflictSyncConfiguration(
                        fpr = 1.0,
                        ratelessBatchSize = 2,
                        pageSize = 8,
                    )

                    val responderJob = async {
                        responderSynk.respondConflictSync(
                            namespace = CRDT::class,
                            transport = saturatingTransport.responder(),
                            configuration = configuration,
                        )
                    }

                    val initiatorStats = initiatorSynk.conflictSync(
                        namespace = CRDT::class,
                        transport = saturatingTransport.initiator(),
                        configuration = configuration,
                    )
                    val responderStats = responderJob.await()

                    val expectedIds = setOf("1", "2", "3")
                    assertEquals(expectedIds, initiatorData.keys)
                    assertEquals(expectedIds, responderData.keys)

                    assertEquals(responderData["1"], initiatorData["1"])
                    assertEquals(responderData["2"], initiatorData["2"])
                    assertEquals(initiatorData["3"], responderData["3"])

                    assertTrue(initiatorStats.outbound.any { it is TransportEnvelope.HashRequest })
                    assertTrue(initiatorStats.remoteOnlyHashes > 0)
                    assertTrue(responderStats.remoteOnlyHashes > 0)

                    printStats("Hash • Initiator", initiatorStats)
                    printStats("Hash • Responder", responderStats)
                } finally {
                    saturatingTransport.close()
                }
            }
        } finally {
            ConflictSyncLogger.isEnabled = previousLogging
        }
    }

    private fun printStats(label: String, stats: ConflictSyncStats) {
        val prefix = "[Test][ConflictSync]"
        val border = "═".repeat(label.length + 10)
        println("$prefix ░$border░")
        println("$prefix ░   $label   ░")
        println("$prefix ░$border░")
        println("$prefix   namespace           : ${stats.namespace}")
        println("$prefix   scanned             : ${stats.scanned}")
        println("$prefix   remoteUnknown       : ${stats.remoteUnknown}")
        println("$prefix   localOnly           : ${stats.localOnly}")
        println("$prefix   remoteOnlyHashes    : ${stats.remoteOnlyHashes}")
        println("$prefix   falseMatches        : ${stats.falseMatches}")
        println("$prefix   outbound envelopes  : ${stats.outbound.size}")
        println("$prefix   inbound envelopes   : ${stats.inbound.size}")
        println("$prefix ░$border░")
    }

    private fun buildSynk(
        adapter: CRDTAdapter,
        backing: MutableMap<String, CRDT>,
        name: String,
    ): Synk {
        val stateSource = MapStateSource(backing)
        val mergeHandler = MergeHandler<CRDT> { _, value -> backing[value.id] = value }
        val storageConfig = CustomClockStorageConfiguration(
            filePath = "/$name".toPath(),
            fileSystem = FakeFileSystem(),
        )
        return Synk.Builder(storageConfig)
            .registerSynkAdapter(adapter)
            .registerStateSource(stateSource)
            .onMerged(mergeHandler)
            .build()
    }

    private class MapStateSource<T : Any>(
        private val backing: MutableMap<String, T>,
    ) : StateSource<T> {
        override suspend fun scan(after: ObjectKey?, limit: Int): List<T> {
            val sorted = backing.entries.sortedBy { it.key }
            val startIndex = after?.let { key ->
                sorted.indexOfFirst { it.key == key.id }.takeIf { it >= 0 }?.plus(1) ?: 0
            } ?: 0
            return sorted.drop(startIndex).take(limit).map { it.value }
        }

        override suspend fun byId(id: String): T? = backing[id]
    }

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

        fun close() {
            scope.cancel()
            bloomInitToResp.close()
            bloomRespToInit.close()
            ratelessRespToInit.close()
            ratelessInitToResp.close()
            decompInitToResp.close()
            decompRespToInit.close()
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
            override fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload> =
                bridge(outbound, outboundBloom, inboundBloom) { payload ->
                    bloomTransform(role, payload)
                }

            override fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload> =
                bridge(outbound, outboundRateless, inboundRateless)

            override fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope> =
                bridge(outbound, outboundDecompositions, inboundDecompositions)
        }

        private fun <T> bridge(
            outbound: Flow<T>,
            sendChannel: Channel<T>,
            receiveChannel: Channel<T>,
            transform: (T) -> T = { it },
        ): Flow<T> {
            scope.launch {
                try {
                    outbound.collect { value -> sendChannel.send(transform(value)) }
                } finally {
                    sendChannel.close()
                }
            }
            return receiveChannel.receiveAsFlow()
        }
    }
}
