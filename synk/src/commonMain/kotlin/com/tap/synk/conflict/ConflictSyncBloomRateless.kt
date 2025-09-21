package com.tap.synk.conflict

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class ConflictSyncBloomRateless<T : Any>(
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

        val role = ConflictSyncLogger.Role.INITIATOR
        val namespace = local.namespace

        val (localDecompositions, fingerprints) = ConflictSyncLogger.stage(role, "Collect local state") {
            val decompositions = local.split()
            val prints = decompositions.map { it.extract() }
            val objectCount = decompositions.map { it.objectKey }.distinct().size
            ConflictSyncLogger.log(role) {
                "objects=$objectCount fields=${decompositions.size}"
            }
            decompositions to prints
        }

        val localFilter = ConflictSyncLogger.stage(role, "Build Bloom filter") {
            BloomFilter.build(fingerprints, fpr, hasher).also { filter ->
                ConflictSyncLogger.log(role) {
                    "fingerprints=${fingerprints.size} serialized=${filter.serializedSize()} bytes"
                }
            }
        }

        tracker.register(
            ConflictSyncEvent.LocalToRemote(
                stateBytes = 0,
                metadataBytes = localFilter.serializedSize(),
                upload = tracker.uploadBandwidth(),
            ),
        )

        val remoteFilterPayload = ConflictSyncLogger.stage(role, "Exchange Bloom filter") {
            transport.bloom(flowOf(localFilter.toPayload(namespace))).firstOrNull()
                ?: throw IllegalStateException("Responder did not provide Bloom filter")
        }

        ConflictSyncLogger.log(role) {
            "received hashCount=${remoteFilterPayload.hashCount} bits=${remoteFilterPayload.bits.size}"
        }

        val remoteFilter = BloomFilter.fromPayload(remoteFilterPayload, hasher)

        val (localCommon, remoteUnknown) = ConflictSyncLogger.stage(role, "Partition using Bloom filter") {
            val partition = remoteFilter.partition(localDecompositions)
            ConflictSyncLogger.log(role) {
                "common=${partition.common.size} unknown=${partition.unknown.size}"
            }
            partition.common to partition.unknown
        }

        val localHashes = ConflictSyncLogger.stage(role, "Hash candidate fields") {
            hashDecompositions(localCommon, hasher).also { hashes ->
                ConflictSyncLogger.log(role) { "entries=${hashes.size}" }
            }
        }
        val localIblt = RatelessIBLT.fromSymbols(localHashes.keys, hasher)
        val remoteIblt = RatelessIBLT(hasher)

        val (localOnlyHashes, remoteOnlyHashes) = ConflictSyncLogger.stage(role, "Decode rateless sketch") {
            val remoteSketchFlow = transport.rateless(emptyFlow())
            collectRateless(remoteSketchFlow, localIblt, remoteIblt)
            if (!localIblt.isDecoded()) {
                throw IllegalStateException("Failed to decode rateless sketch from responder")
            }
            val localOnly = localIblt.getLocalOnlySymbols()
            val remoteOnly = localIblt.getRemoteOnlySymbols()
            ConflictSyncLogger.log(role) {
                "localOnly=${localOnly.size} remoteOnly=${remoteOnly.size}"
            }
            localOnly to remoteOnly
        }
        val localOnly = localOnlyHashes.mapNotNull { localHashes[it] }

        val sketchSize = remoteIblt.size()
        var downloadStateBytes = 0

        val outboundPlan = ConflictSyncLogger.stage(role, "Plan outbound envelopes") {
            buildOutboundPlan(local, namespace, remoteUnknown, localOnly, remoteOnlyHashes).also { plan ->
                ConflictSyncLogger.log(role) {
                    "envelopes=${plan.envelopes.size} stateBytes=${plan.stateBytes} metadataBytes=${plan.metadataBytes}"
                }
            }
        }
        val outboundFlow = outboundPlan.envelopes.asFlow()

        val inboundEnvelopes = mutableListOf<TransportEnvelope>()
        ConflictSyncLogger.stage(role, "Apply inbound envelopes") {
            transport.decompositions(outboundFlow).collect { envelope ->
                inboundEnvelopes += envelope
                when (envelope) {
                    is TransportEnvelope.Field -> {
                        downloadStateBytes += envelope.payload.field.sizeOf()
                        local.join(listOf(envelope.payload.field))
                        ConflictSyncLogger.log(role) {
                            "apply field=${envelope.payload.field.field} object=${envelope.payload.field.objectKey.id}"
                        }
                    }
                    is TransportEnvelope.Snapshot -> {
                        downloadStateBytes += envelope.payload.fields.sumOf { it.sizeOf() }
                        downloadStateBytes += envelope.payload.encodedObject.size
                        local.applySnapshot(envelope.payload)
                        ConflictSyncLogger.log(role) {
                            "apply snapshot object=${envelope.payload.objectKey.id} fields=${envelope.payload.fields.size}"
                        }
                    }
                    is TransportEnvelope.HashResponse -> {
                        downloadStateBytes += envelope.fields.sumOf { it.sizeOf() }
                        local.join(envelope.fields)
                        ConflictSyncLogger.log(role) {
                            "apply hash response fields=${envelope.fields.size}"
                        }
                    }
                    is TransportEnvelope.HashRequest -> {
                        throw IllegalStateException("Unexpected HashRequest on initiator side")
                    }
                }
            }
        }

        val downloadMetadataBytes = remoteFilter.serializedSize() + sketchSize * CODED_SYMBOL_SIZE
        tracker.register(
            ConflictSyncEvent.RemoteToLocal(
                stateBytes = downloadStateBytes,
                metadataBytes = downloadMetadataBytes,
                download = tracker.downloadBandwidth(),
            ),
        )

        tracker.register(
            ConflictSyncEvent.LocalToRemote(
                stateBytes = outboundPlan.stateBytes,
                metadataBytes = outboundPlan.metadataBytes,
                upload = tracker.uploadBandwidth(),
            ),
        )

        val falseMatches = localOnlyHashes.size + remoteOnlyHashes.size
        tracker.finish(falseMatches)

        ConflictSyncLogger.log(role) {
            "summary outbound=${outboundPlan.envelopes.size} inbound=${inboundEnvelopes.size} falseMatches=$falseMatches"
        }

        return ConflictSyncStats(
            namespace = namespace,
            scanned = localDecompositions.size,
            remoteUnknown = remoteUnknown.size,
            localOnly = localOnly.size,
            remoteOnlyHashes = remoteOnlyHashes.size,
            outbound = outboundPlan.envelopes,
            inbound = inboundEnvelopes,
            falseMatches = falseMatches,
        )
    }

    private suspend fun collectRateless(
        inbound: Flow<RatelessSketchPayload>,
        localIblt: RatelessIBLT,
        remoteIblt: RatelessIBLT,
    ) {
        try {
            inbound.collect { payload ->
                if (!coroutineContext.isActive) throw RatelessComplete()
                if (payload.symbols.isEmpty()) return@collect
                remoteIblt.appendSymbols(payload.symbols)
                localIblt.ensureSketchSize(remoteIblt.size())
                localIblt.subtract(remoteIblt)
                if (localIblt.isDecoded()) {
                    throw RatelessComplete()
                }
            }
        } catch (_: RatelessComplete) {
            // graceful completion once decoded
        }
    }
}

private class RatelessComplete : CancellationException("Rateless sketch completed")
