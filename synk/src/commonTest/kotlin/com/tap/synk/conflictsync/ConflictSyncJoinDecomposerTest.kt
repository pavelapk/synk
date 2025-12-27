package com.tap.synk.conflictsync

import com.tap.synk.adapter.SynkAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

data class TestState(val items: Set<String>)

class TestAdapter : SynkAdapter<TestState> {
    override fun encode(crdt: TestState): Map<String, String> =
        crdt.items.associateWith { it }

    override fun decode(map: Map<String, String>): TestState =
        TestState(map.values.toSet())

    override fun resolveId(crdt: TestState): String = "id"
}

class ConflictSyncJoinDecomposerTest {
    @Test
    fun decompositionRoundTrip() {
        val adapter = TestAdapter()
        val decomposer = JoinDecomposer(adapter)
        val state = TestState(setOf("a", "b", "c"))

        val decomposed = decomposer.decompose(state)
        val recomposed = decomposer.recompose(decomposed)

        assertEquals(state.items, recomposed.items)
    }

    @Test
    fun hashIsStable() {
        val adapter = TestAdapter()
        val decomposer = JoinDecomposer(adapter)
        val state = TestState(setOf("x"))
        val element = decomposer.decompose(state).first()

        val hash1 = decomposer.hashDecomposition(element)
        val hash2 = decomposer.hashDecomposition(element)
        assertEquals(hash1, hash2)
    }
}
