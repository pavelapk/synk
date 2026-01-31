package com.tap.synk.conflictsync

import com.tap.synk.adapter.SynkAdapter
import kotlin.test.Test
import kotlin.test.assertEquals

// Simple grow-only set used for testing
private data class GSet(val elements: Set<String> = emptySet()) {
    fun add(e: String) = copy(elements = elements + e)
    fun union(other: GSet) = GSet(elements + other.elements)
}

// Adapter that encodes a set of strings as key=value pairs
private class GSetAdapter : SynkAdapter<GSet> {
    override fun encode(crdt: GSet): Map<String, String> =
        crdt.elements.associate { it to it }

    override fun decode(map: Map<String, String>): GSet =
        GSet(map.values.toSet())

    override fun resolveId(crdt: GSet): String = "gset"
}

class ConflictSyncSessionTest {
    @Test
    fun basicSynchronizationMergesElements() {
        val adapter = GSetAdapter()
        val sessionA = ConflictSyncSession("s1", adapter)
        val sessionB = ConflictSyncSession("s1", adapter)

        val stateA = GSet(setOf("a", "b", "c"))
        val stateB = GSet(setOf("b", "c", "d"))

        // A initiates
        val bloom = sessionA.initiate(stateA)
        val init = sessionB.process(bloom) as ConflictSyncMessage.InitStream
        val eos = sessionA.process(init) as ConflictSyncMessage.EndOfStream
        val final = sessionB.process(eos) as ConflictSyncMessage.FinalElements
        sessionA.process(final)

        assertEquals(setOf("a","b","c","d"), sessionA.result()!!.elements)
        assertEquals(setOf("a","b","c","d"), sessionB.result()!!.elements)
    }
}

