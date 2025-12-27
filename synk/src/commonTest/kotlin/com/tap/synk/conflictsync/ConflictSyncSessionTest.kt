package com.tap.synk.conflictsync

import com.tap.synk.adapter.SynkAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class SessionState(val items: Set<String>)

class SessionAdapter : SynkAdapter<SessionState> {
    override fun encode(crdt: SessionState): Map<String, String> =
        crdt.items.associateWith { it }

    override fun decode(map: Map<String, String>): SessionState =
        SessionState(map.values.toSet())

    override fun resolveId(crdt: SessionState): String = "id"
}

class ConflictSyncSessionTest {
    @Test
    fun sessionMergesExclusiveElements() {
        val adapter = SessionAdapter()
        val sessionA = ConflictSyncSession("session", adapter)
        val sessionB = ConflictSyncSession("session", adapter)

        val stateA = SessionState(setOf("a", "b", "c"))
        val stateB = SessionState(setOf("b", "c", "d"))

        sessionB.initiate(stateB) // set state for B
        val bloom = sessionA.initiate(stateA)
        val init = sessionB.process(bloom) as ConflictSyncMessage.InitStream
        val end = sessionA.process(init) as ConflictSyncMessage.EndOfStream
        sessionB.process(end)

        assertTrue(sessionA.isCompleted())
        assertTrue(sessionB.isCompleted())
        assertEquals(setOf("a", "b", "c", "d"), sessionA.getResult()!!.items)
        assertEquals(setOf("a", "b", "c", "d"), sessionB.getResult()!!.items)
    }
}
