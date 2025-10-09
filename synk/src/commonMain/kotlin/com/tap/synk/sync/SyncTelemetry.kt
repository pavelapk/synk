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

    fun recordOutbound(bytes: Long) {
        outboundMessages += 1
        outboundBytes += bytes
        tracker.register(
            SyncTransferEvent(
                direction = SyncTransferEvent.Direction.LOCAL_TO_REMOTE,
                bytes = bytes,
            ),
        )
    }

    fun recordInbound(bytes: Long) {
        inboundMessages += 1
        inboundBytes += bytes
        tracker.register(
            SyncTransferEvent(
                direction = SyncTransferEvent.Direction.REMOTE_TO_LOCAL,
                bytes = bytes,
            ),
        )
    }

    fun finish() {
        if (!finished) {
            tracker.finish()
            finished = true
        }
    }

    fun toStats(namespace: String, scanned: Int): SyncStats =
        SyncStats(
            namespace = namespace,
            scanned = scanned,
            outboundMessages = outboundMessages,
            outboundBytes = outboundBytes,
            inboundMessages = inboundMessages,
            inboundBytes = inboundBytes,
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

    fun snapshot(namespace: String, scanned: Int): SyncStats {
        metrics.finish()
        return metrics.toStats(namespace, scanned)
    }
}

fun SyncTransport.instrument(tracker: SyncTracker): InstrumentedSyncTransport {
    return InstrumentedSyncTransport(this, SyncMessageMetrics(tracker))
}
