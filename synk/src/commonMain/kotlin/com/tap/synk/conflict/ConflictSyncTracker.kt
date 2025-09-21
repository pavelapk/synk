package com.tap.synk.conflict

sealed class Bandwidth {
    abstract fun bytesPerSecond(): Double

    data class Kbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e3 / 8
    }

    data class Mbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e6 / 8
    }

    data class Gbps(val value: Double) : Bandwidth() {
        override fun bytesPerSecond(): Double = value * 1e9 / 8
    }
}

sealed class ConflictSyncEvent {
    abstract val stateBytes: Int
    abstract val metadataBytes: Int

    data class LocalToRemote(
        override val stateBytes: Int,
        override val metadataBytes: Int,
        val upload: Bandwidth,
    ) : ConflictSyncEvent()

    data class RemoteToLocal(
        override val stateBytes: Int,
        override val metadataBytes: Int,
        val download: Bandwidth,
    ) : ConflictSyncEvent()

    val totalBytes: Int get() = stateBytes + metadataBytes

    fun estimatedDurationSeconds(): Double? {
        val bandwidth = when (this) {
            is LocalToRemote -> upload.bytesPerSecond()
            is RemoteToLocal -> download.bytesPerSecond()
        }
        return if (bandwidth > 0) totalBytes / bandwidth else null
    }
}

interface ConflictSyncTracker {
    fun isReady(): Boolean
    fun register(event: ConflictSyncEvent)
    fun finish(falseMatches: Int)
    fun falseMatches(): Int
    fun uploadBandwidth(): Bandwidth
    fun downloadBandwidth(): Bandwidth
}

class DefaultConflictSyncTracker(
    private val download: Bandwidth,
    private val upload: Bandwidth,
) : ConflictSyncTracker {
    private val events = mutableListOf<ConflictSyncEvent>()
    private var diffs: Int? = null

    fun events(): List<ConflictSyncEvent> = events.toList()

    override fun isReady(): Boolean = events.isEmpty() && diffs == null

    override fun register(event: ConflictSyncEvent) {
        if (diffs == null) {
            events += event
        }
    }

    override fun finish(falseMatches: Int) {
        if (diffs == null) {
            diffs = falseMatches
        }
    }

    override fun falseMatches(): Int = diffs ?: error("finish() must be called before falseMatches()")

    override fun uploadBandwidth(): Bandwidth = upload

    override fun downloadBandwidth(): Bandwidth = download
}
