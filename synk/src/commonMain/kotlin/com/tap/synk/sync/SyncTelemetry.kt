package com.tap.synk.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

internal class SyncMessageMetrics(
    private val tracker: SyncTracker,
) {
    var outboundMessages: Int = 0
        private set
    var inboundMessages: Int = 0
        private set
    var outboundBytes: Long = 0
        private set
    var inboundBytes: Long = 0
        private set

    private var finished = false

    fun recordOutbound(bytes: Int) {
        outboundMessages += 1
        outboundBytes += bytes
        tracker.register(
            SyncTransferEvent.LocalToRemote(
                stateBytes = bytes,
                metadataBytes = 0,
                upload = tracker.uploadBandwidth,
            ),
        )
    }

    fun recordInbound(bytes: Int) {
        inboundMessages += 1
        inboundBytes += bytes
        tracker.register(
            SyncTransferEvent.RemoteToLocal(
                stateBytes = bytes,
                metadataBytes = 0,
                download = tracker.downloadBandwidth,
            ),
        )
    }

    fun finish(falseMatches: Int) {
        if (!finished) {
            tracker.finish(falseMatches)
            finished = true
        }
    }

    fun toStats(adapterKey: String, scanned: Int, falseMatches: Int): SyncStats =
        SyncStats(
            adapterKey = adapterKey,
            scanned = scanned,
            outboundMessages = outboundMessages,
            outboundBytes = outboundBytes,
            inboundMessages = inboundMessages,
            inboundBytes = inboundBytes,
            falseMatches = falseMatches,
        )
}

class InstrumentedSyncTransport internal constructor(
    private val delegate: SyncTransport,
    internal val metrics: SyncMessageMetrics,
) {
    fun exchange(outbound: Flow<EncodedSyncRequest>): Flow<SyncResponse> {
        val requests = outbound
            .onEach { encoded -> metrics.recordOutbound(encoded.payloadBytes) }
            .map { encoded -> encoded.request }

        return delegate.exchange(requests)
            .onEach { response ->
                metrics.recordInbound(response.payload.byteSize())
            }
    }

    fun snapshot(adapterKey: String, scanned: Int, falseMatches: Int): SyncStats {
        metrics.finish(falseMatches)
        return metrics.toStats(adapterKey, scanned, falseMatches)
    }
}

fun SyncTransport.instrument(tracker: SyncTracker): InstrumentedSyncTransport {
    return InstrumentedSyncTransport(this, SyncMessageMetrics(tracker))
}
