package com.tap.synk.conflictsync

import com.tap.synk.conflictsync.rateless.Iblt
import kotlin.test.Test
import kotlin.test.assertEquals

class IbltTest {
    @Test
    fun simple_diff_peels() {
        val a = setOf(1L, 2L, 3L)
        val b = setOf(2L, 3L, 4L)

        val m = 512
        val h = 3
        var ok = false
        for (salt in 1L..10L) {
            val ibltA = Iblt(m, h, salt)
            a.forEach { ibltA.insert(it) }
            val ibltB = Iblt(m, h, salt)
            b.forEach { ibltB.insert(it) }
            ibltB.diffAssign(ibltA)
            val res = ibltB.peel()
            if (res != null) {
                val (presentAtBnotA, presentAtAnotB) = res
                assertEquals(listOf(4L), presentAtBnotA.sorted())
                assertEquals(listOf(1L), presentAtAnotB.sorted())
                ok = true
                break
            }
        }
        check(ok)
    }
}
