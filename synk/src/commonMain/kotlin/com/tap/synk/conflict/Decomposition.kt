package com.tap.synk.conflict

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
    fun sizeOf(): Int
    fun falseMatches(other: Measure): Int
}
