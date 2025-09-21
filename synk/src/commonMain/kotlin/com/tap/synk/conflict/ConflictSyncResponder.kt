package com.tap.synk.conflict

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class ConflictSyncResponder<T : Any>(
    private val fpr: Double,
    private val hasher: Hasher64 = Hasher64.xxHash64(),
    private val ratelessBatchSize: Int = 32,
) {
    suspend fun sync(
        local: ReplicaState<T>,
        transport: ConflictSyncTransport,
        tracker: ConflictSyncTracker,
    ): ConflictSyncStats {
        require(tracker.isReady()) { "Tracker should be ready before sync" }

        val role = ConflictSyncLogger.Role.RESPONDER
        val namespace = local.namespace

        val localDecompositions = ConflictSyncLogger.stage(role, "Collect local state") {
            val decompositions = local.split()
            val objectCount = decompositions.map { it.objectKey }.distinct().size
            ConflictSyncLogger.log(role) {
                "objects=$objectCount fields=${decompositions.size}"
            }
            decompositions
        }

        val responderFilterDeferred = CompletableDeferred<BloomFilterPayload>()
        val bloomInbound = transport.bloom(flow {
            emit(responderFilterDeferred.await())
        })

        val initiatorFilterPayload = ConflictSyncLogger.stage(role, "Receive Bloom filter") {
            bloomInbound.firstOrNull()
                ?: throw IllegalStateException("Initiator did not provide Bloom filter")
        }
        val initiatorFilter = BloomFilter.fromPayload(initiatorFilterPayload, hasher)

        ConflictSyncLogger.log(role) {
            "received hashCount=${initiatorFilterPayload.hashCount} bits=${initiatorFilterPayload.bits.size}"
        }

        val (remoteCommon, localUnknown) = ConflictSyncLogger.stage(role, "Partition using Bloom filter") {
            val partition = initiatorFilter.partition(localDecompositions)
            ConflictSyncLogger.log(role) {
                "remoteCommon=${partition.common.size} localUnknown=${partition.unknown.size}"
            }
            partition.common to partition.unknown
        }

        val responderFilter = ConflictSyncLogger.stage(role, "Send Bloom filter") {
            BloomFilter.build(remoteCommon.map { it.extract() }, fpr, hasher).also { filter ->
                val payload = filter.toPayload(namespace)
                responderFilterDeferred.complete(payload)
                ConflictSyncLogger.log(role) {
                    "hashCount=${payload.hashCount} bits=${payload.bits.size}"
                }
            }
        }

        val (responderHashes, responderIblt) = ConflictSyncLogger.stage(role, "Prepare rateless sketch") {
            val hashes = hashDecompositions(remoteCommon, hasher)
            val iblt = RatelessIBLT.fromSymbols(hashes.keys, hasher)
            ConflictSyncLogger.log(role) { "entries=${hashes.size}" }
            hashes to iblt
        }

        val ratelessSymbolsSent = ConflictSyncLogger.stage(role, "Emit rateless sketch") {
            var sent = 0
            val ratelessOutbound = flow {
                while (coroutineContext.isActive) {
                    val batch = responderIblt.emitNext(ratelessBatchSize)
                    if (batch.isEmpty()) break
                    sent += batch.size
                    emit(RatelessSketchPayload(namespace, batch))
                    ConflictSyncLogger.log(role) {
                        "emit batch=${batch.size} totalSent=$sent"
                    }
                }
            }
            transport.rateless(ratelessOutbound).collect { /* ignore inbound symbols */ }
            sent
        }

        val outboundPlan = ConflictSyncLogger.stage(role, "Plan outbound envelopes") {
            buildOutboundPlan(
                replica = local,
                namespace = namespace,
                missing = localUnknown,
                extraFields = emptyList(),
                hashRequests = emptyList(),
            ).also { plan ->
                ConflictSyncLogger.log(role) {
                    "envelopes=${plan.envelopes.size} stateBytes=${plan.stateBytes} metadataBytes=${plan.metadataBytes}"
                }
            }
        }

        val outboundChannel = Channel<TransportEnvelope>(capacity = Channel.BUFFERED)
        val outboundEnvelopes = mutableListOf<TransportEnvelope>()
        var uploadStateBytes = outboundPlan.stateBytes
        var uploadMetadataBytes = responderFilter.serializedSize() + ratelessSymbolsSent * CODED_SYMBOL_SIZE + outboundPlan.metadataBytes

        val inboundEnvelopes = mutableListOf<TransportEnvelope>()
        var downloadStateBytes = 0
        var downloadMetadataBytes = 0
        var remoteOnlyHashCount = 0

        ConflictSyncLogger.stage(role, "Process inbound envelopes") {
            coroutineScope {
                launch {
                    for (envelope in outboundPlan.envelopes) {
                        outboundEnvelopes += envelope
                        outboundChannel.send(envelope)
                    }
                }

                val inboundFlow = transport.decompositions(outboundChannel.receiveAsFlow())

                inboundFlow.collect { envelope ->
                    inboundEnvelopes += envelope
                    when (envelope) {
                        is TransportEnvelope.Snapshot -> {
                            downloadStateBytes += envelope.payload.fields.sumOf { it.sizeOf() }
                            downloadStateBytes += envelope.payload.encodedObject.size
                            local.applySnapshot(envelope.payload)
                            ConflictSyncLogger.log(role) {
                                "apply snapshot object=${envelope.payload.objectKey.id} fields=${envelope.payload.fields.size}"
                            }
                        }
                        is TransportEnvelope.Field -> {
                            downloadStateBytes += envelope.payload.field.sizeOf()
                            local.join(listOf(envelope.payload.field))
                            ConflictSyncLogger.log(role) {
                                "apply field=${envelope.payload.field.field} object=${envelope.payload.field.objectKey.id}"
                            }
                        }
                        is TransportEnvelope.HashRequest -> {
                            remoteOnlyHashCount += envelope.hashes.size
                            downloadMetadataBytes += envelope.hashes.size * Long.SIZE_BYTES
                            ConflictSyncLogger.log(role) {
                                "hash request count=${envelope.hashes.size}"
                            }
                            val responseFields = resolveHashes(envelope.hashes, responderHashes)
                            if (responseFields.isNotEmpty()) {
                                val response = TransportEnvelope.HashResponse(responseFields)
                                outboundEnvelopes += response
                                uploadStateBytes += response.fields.sumOf { it.sizeOf() }
                                outboundChannel.send(response)
                                ConflictSyncLogger.log(role) {
                                    "send hash response fields=${response.fields.size}"
                                }
                            }
                        }
                        is TransportEnvelope.HashResponse -> {
                            downloadStateBytes += envelope.fields.sumOf { it.sizeOf() }
                            local.join(envelope.fields)
                            ConflictSyncLogger.log(role) {
                                "apply hash response fields=${envelope.fields.size}"
                            }
                        }
                    }
                }

                outboundChannel.close()
            }
        }

        tracker.register(
            ConflictSyncEvent.LocalToRemote(
                stateBytes = uploadStateBytes,
                metadataBytes = uploadMetadataBytes,
                upload = tracker.uploadBandwidth(),
            ),
        )

        tracker.register(
            ConflictSyncEvent.RemoteToLocal(
                stateBytes = downloadStateBytes,
                metadataBytes = downloadMetadataBytes,
                download = tracker.downloadBandwidth(),
            ),
        )

        val falseMatches = remoteOnlyHashCount
        tracker.finish(falseMatches)

        ConflictSyncLogger.log(role) {
            "summary outbound=${outboundEnvelopes.size} inbound=${inboundEnvelopes.size} hashRequests=$remoteOnlyHashCount"
        }

        return ConflictSyncStats(
            namespace = namespace,
            scanned = localDecompositions.size,
            remoteUnknown = localUnknown.size,
            localOnly = localUnknown.size,
            remoteOnlyHashes = remoteOnlyHashCount,
            outbound = outboundEnvelopes,
            inbound = inboundEnvelopes,
            falseMatches = falseMatches,
        )
    }

    private fun resolveHashes(
        hashes: List<Long>,
        known: Map<Long, FieldDecomposition>,
    ): List<FieldDecomposition> = hashes.mapNotNull { known[it] }
}
