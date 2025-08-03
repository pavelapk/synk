package com.tap.synk.conflictsync

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.config.CustomClockStorageConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

// Simple grow only set and adapter for tests
internal data class GSet(val elements: Set<String>)

internal class GSetAdapter : SynkAdapter<GSet> {
    override fun resolveId(crdt: GSet): String = "gset"

    override fun encode(crdt: GSet): Map<String, String> =
        crdt.elements.associateWith { it }

    override fun decode(map: Map<String, String>): GSet = GSet(map.values.toSet())
}

class ConflictSyncSessionTest {

    private fun newSynk(): Synk {
        val storage = CustomClockStorageConfiguration(
            filePath = "/test".toPath(),
            fileSystem = FakeFileSystem(),
        )
        return Synk.Builder(storage).registerSynkAdapter<GSet>(GSetAdapter()).build()
    }

    @Test
    fun `handshake exchanges exclusive elements`() {
        val synkA = newSynk()
        val synkB = newSynk()

        val stateA = GSet(setOf("apple", "banana"))
        val stateB = GSet(setOf("banana", "cherry"))

        val sessionA = synkA.conflictSyncInitiate(stateA)
        val sessionB = synkB.conflictSyncInitiate(stateB)
        // Replica B needs to set its state before processing the bloom filter
        sessionB.setState(stateB)

        var message: ConflictSyncMessage? = sessionA.initiate(stateA)
        var fromA = true

        while (message != null && !(sessionA.isCompleted() && sessionB.isCompleted())) {
            message = if (fromA) {
                sessionB.processMessage(message)
            } else {
                sessionA.processMessage(message)
            }
            fromA = !fromA
        }

        val resultA = sessionA.getResult()!!.elements
        val resultB = sessionB.getResult()!!.elements

        val expected = setOf("apple", "banana", "cherry")
        assertEquals(expected, resultA)
        assertEquals(expected, resultB)
    }
}
