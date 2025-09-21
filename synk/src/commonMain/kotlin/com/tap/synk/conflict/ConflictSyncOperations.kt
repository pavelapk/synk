package com.tap.synk.conflict

internal data class PartitionResult(
    val common: List<FieldDecomposition>,
    val unknown: List<FieldDecomposition>,
)

internal fun BloomFilter.partition(decompositions: List<FieldDecomposition>): PartitionResult {
    val common = mutableListOf<FieldDecomposition>()
    val unknown = mutableListOf<FieldDecomposition>()
    decompositions.forEach { decomposition ->
        val fingerprint = decomposition.extract()
        if (mightContain(fingerprint)) {
            common += decomposition
        } else {
            unknown += decomposition
        }
    }
    return PartitionResult(common = common, unknown = unknown)
}

internal fun hashDecompositions(
    decompositions: List<FieldDecomposition>,
    hasher: Hasher64,
): Map<Long, FieldDecomposition> {
    val hashed = HashMap<Long, FieldDecomposition>(decompositions.size)
    decompositions.forEach { decomposition ->
        val fingerprint = decomposition.extract()
        val hash = hasher.hash(fingerprint.bytes)
        hashed[hash] = decomposition
    }
    return hashed
}

internal fun totalStateBytes(decompositions: Collection<FieldDecomposition>): Int =
    decompositions.sumOf { it.sizeOf() }

internal suspend fun <T : Any> buildOutboundPlan(
    replica: ReplicaState<T>,
    namespace: String,
    missing: List<FieldDecomposition>,
    extraFields: List<FieldDecomposition>,
    hashRequests: List<Long>,
): OutboundPlan {
    val envelopes = mutableListOf<TransportEnvelope>()
    var stateBytes = 0
    var metadataBytes = 0

    val groupedMissing = missing.groupBy { it.objectKey }
    for ((objectKey, fields) in groupedMissing) {
        val snapshot = replica.snapshotBlock(objectKey, fields)
        if (snapshot != null) {
            envelopes += TransportEnvelope.Snapshot(snapshot)
            stateBytes += snapshot.encodedObject.size
            stateBytes += snapshot.fields.sumOf { it.sizeOf() }
        } else {
            fields.forEach { field ->
                envelopes += TransportEnvelope.Field(
                    DecompositionBlock.FieldBlock(
                        namespace = namespace,
                        field = field,
                    ),
                )
                stateBytes += field.sizeOf()
            }
        }
    }

    extraFields.forEach { field ->
        envelopes += TransportEnvelope.Field(
            DecompositionBlock.FieldBlock(
                namespace = namespace,
                field = field,
            ),
        )
        stateBytes += field.sizeOf()
    }

    if (hashRequests.isNotEmpty()) {
        envelopes += TransportEnvelope.HashRequest(hashRequests)
        metadataBytes += hashRequests.size * Long.SIZE_BYTES
    }

    return OutboundPlan(
        envelopes = envelopes,
        stateBytes = stateBytes,
        metadataBytes = metadataBytes,
    )
}
