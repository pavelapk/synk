package com.tap.synk.conflict

data class ConflictSyncConfiguration(
    val fpr: Double = 0.01,
    val ratelessBatchSize: Int = 32,
    val pageSize: Int = 256,
    val hasher: Hasher64 = Hasher64.xxHash64(),
    val uploadBandwidth: Bandwidth = Bandwidth.Mbps(1.0),
    val downloadBandwidth: Bandwidth = Bandwidth.Mbps(1.0),
) {
    init {
        require(fpr in 0.0..1.0 && fpr > 0.0) { "fpr must be in the range (0, 1]" }
        require(ratelessBatchSize > 0) { "ratelessBatchSize must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
    }

    fun newTracker(): ConflictSyncTracker =
        DefaultConflictSyncTracker(download = downloadBandwidth, upload = uploadBandwidth)
}
