package com.tap.synk.conflictsync.rateless

import com.tap.synk.conflictsync.digest.Hash64

internal data class IbltCell(
    var count: Int = 0,
    var keyXor: Long = 0L,
    var hashXor: Long = 0L,
)

/**
 * IBLT that supports xor-composition: cell-wise XOR of (count,keyXor,hashXor).
 * Use H hash functions (e.g., 3). Secondary hash g(key) for verification.
 */
internal class Iblt(
    private val m: Int,
    private val h: Int,
    private val salt: Long,
) {
    private val cells = Array(m) { IbltCell() }

    fun insert(key: Hash64) = add(key, +1)
    fun erase(key: Hash64) = add(key, -1)

    private fun add(key: Hash64, delta: Int) {
        val g = verHash(key)
        repeat(h) { i ->
            val idx = index(key, i)
            val c = cells[idx]
            c.count += delta
            c.keyXor = c.keyXor xor key
            c.hashXor = c.hashXor xor g
        }
    }

    /** Compose as difference: this = this - other (counts), key/hash as XOR. */
    fun diffAssign(other: Iblt) {
        require(other.m == m && other.h == h)
        for (i in 0 until m) {
            val a = cells[i]; val b = other.cells[i]
            a.count = a.count - b.count
            a.keyXor = a.keyXor xor b.keyXor
            a.hashXor = a.hashXor xor b.hashXor
        }
    }

    /** Compose as sum: this = this + other (counts), key/hash as XOR. */
    fun addAssign(other: Iblt) {
        require(other.m == m && other.h == h)
        for (i in 0 until m) {
            val a = cells[i]; val b = other.cells[i]
            a.count = a.count + b.count
            a.keyXor = a.keyXor xor b.keyXor
            a.hashXor = a.hashXor xor b.hashXor
        }
    }

    /** Try to peel. Returns (keysPresentInBNotA, keysPresentInANotB) */
    fun peel(): Pair<MutableList<Hash64>, MutableList<Hash64>>? {
        val pos = mutableListOf<Hash64>()
        val neg = mutableListOf<Hash64>()

        val queue = ArrayDeque<Int>()
        for (i in 0 until m) if (isSingleton(i)) queue.add(i)

        val cellsCopy = Array(m) { cells[it].copy() }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val cell = cellsCopy[i]
            if (!isSingletonCell(cell)) continue

            val key = cell.keyXor
            val g = cell.hashXor
            if (verHash(key) != g) return null // checksum fail → need more redundancy

            if (cell.count == +1) pos.add(key) else if (cell.count == -1) neg.add(key)

            // Remove key from neighbors
            repeat(h) { j ->
                val idx = index(key, j)
                val c = cellsCopy[idx]
                c.count = c.count - cell.count
                c.keyXor = c.keyXor xor key
                c.hashXor = c.hashXor xor g
                if (isSingletonCell(c)) queue.add(idx)
            }
        }

        for (i in 0 until m) if (!isEmptyCell(cellsCopy[i])) return null
        return pos to neg
    }

    fun toBytes(): ByteArray = serializeCells(cells)

    companion object {
        fun fromBytes(m: Int, h: Int, salt: Long, bytes: ByteArray): Iblt {
            val iblt = Iblt(m, h, salt)
            deserializeCells(bytes, iblt.cells)
            return iblt
        }

        private fun serializeCells(cells: Array<IbltCell>): ByteArray {
            val cellSize = 4 + 8 + 8
            val out = ByteArray(cells.size * cellSize)
            var off = 0
            for (c in cells) {
                writeIntLE(out, off, c.count); off += 4
                writeLongLE(out, off, c.keyXor); off += 8
                writeLongLE(out, off, c.hashXor); off += 8
            }
            return out
        }

        private fun deserializeCells(bytes: ByteArray, cells: Array<IbltCell>) {
            var off = 0
            for (i in cells.indices) {
                val count = readIntLE(bytes, off); off += 4
                val key = readLongLE(bytes, off); off += 8
                val hash = readLongLE(bytes, off); off += 8
                val c = cells[i]
                c.count = count
                c.keyXor = key
                c.hashXor = hash
            }
        }

        private fun writeIntLE(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
            buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
            buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        private fun writeLongLE(buf: ByteArray, off: Int, v: Long) {
            var x = v
            var i = 0
            while (i < 8) {
                buf[off + i] = (x and 0xFF).toByte()
                x = x ushr 8
                i++
            }
        }

        private fun readIntLE(buf: ByteArray, off: Int): Int {
            return (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
        }

        private fun readLongLE(buf: ByteArray, off: Int): Long {
            var res = 0L
            var i = 0
            var shift = 0
            while (i < 8) {
                res = res or ((buf[off + i].toLong() and 0xFFL) shl shift)
                shift += 8
                i++
            }
            return res
        }
    }

    private fun index(key: Hash64, i: Int): Int {
        val base = key xor salt
        val a = IntHash.mix64to32(base)
        val b = IntHash.mix64to32(base xor 0x9E3779B97F4A7C15u.toLong())
        val v = a + i * b
        return (v and Int.MAX_VALUE) % m
    }

    private fun verHash(key: Hash64): Long {
        var x = key xor (salt * 0x9E3779B97F4A7C15UL.toLong())
        x = x xor (x ushr 33); x *= -49064778989728563L
        x = x xor (x ushr 33); x *= -4265267296055464877L
        x = x xor (x ushr 33)
        return x
    }

    private fun isSingleton(i: Int) = isSingletonCell(cells[i])
    private fun isSingletonCell(c: IbltCell) = (c.count == 1 || c.count == -1) && c.keyXor != 0L
    private fun isEmptyCell(c: IbltCell) = c.count == 0 && c.keyXor == 0L && c.hashXor == 0L
}
