package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.rateless.RatelessParams

data class ConflictSyncParams(
    val digestSeed: Long = 0xC0FFEE,
    val bloomBits: Int = 1 shl 20,
    val bloomHashes: Int = 7,
    val rateless: RatelessParams = RatelessParams(),
)

