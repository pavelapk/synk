package com.tap.synk.strategy.bloomrateless

/**
 * Mirrors the three Rust traits that power Conflict Sync. The names stay aligned with the
 * documentation to keep the port readable when cross-referencing the Rust engine.
 */
interface Decompose<Decomposition> {
    fun split(): List<Decomposition>
    suspend fun join(deltas: List<Decomposition>)
}

interface Extract<Item> {
    fun extract(): Item
}

interface Measure {
    fun sizeOf(): Long
    fun falseMatches(other: Measure): Int
}
