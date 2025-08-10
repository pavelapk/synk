package com.tap.synk.conflictsync.digest

typealias Hash64 = Long

/** Simple, fast, multiplatform xxhash64 (seeded). */
object XxHash64 {
    fun hash(bytes: ByteArray, seed: Long = 0L): Hash64 = xxh64(bytes, seed)
    fun hashString(s: String, seed: Long = 0L): Hash64 = hash(s.encodeToByteArray(), seed)

    // Pure Kotlin xxHash64 implementation (little-endian processing)
    private fun xxh64(input: ByteArray, seed: Long): Long {
        val prime1 = -7046029288634856825L // 11400714785074694791UL.toLong()
        val prime2 = -4417276706812531889L // 14029467366897019727UL
        val prime3 = 1609587929392839161L
        val prime4 = -8796714831421723037L // 9650029242287828579UL
        val prime5 = 2870177450012600261L

        var h64: Long
        var offset = 0
        val len = input.size

        if (len >= 32) {
            var v1 = seed + prime1 + prime2
            var v2 = seed + prime2
            var v3 = seed
            var v4 = seed - prime1

            val limit = len - 32
            while (offset <= limit) {
                v1 = round(v1, readLongLE(input, offset)); offset += 8
                v2 = round(v2, readLongLE(input, offset)); offset += 8
                v3 = round(v3, readLongLE(input, offset)); offset += 8
                v4 = round(v4, readLongLE(input, offset)); offset += 8
            }
            h64 = rotl(v1, 1) +
                rotl(v2, 7) +
                rotl(v3, 12) +
                rotl(v4, 18)

            h64 = mergeRound(h64, v1)
            h64 = mergeRound(h64, v2)
            h64 = mergeRound(h64, v3)
            h64 = mergeRound(h64, v4)
        } else {
            h64 = seed + prime5
        }

        h64 += len.toLong()

        while (offset + 8 <= len) {
            val k1 = round(0L, readLongLE(input, offset))
            h64 = h64 xor k1
            h64 = rotl(h64, 27) * prime1 + prime4
            offset += 8
        }
        if (offset + 4 <= len) {
            h64 = h64 xor ((readIntLE(input, offset).toLong() and 0xFFFFFFFFL) * prime1)
            h64 = rotl(h64, 23) * prime2 + prime3
            offset += 4
        }
        while (offset < len) {
            h64 = h64 xor ((input[offset].toLong() and 0xFFL) * prime5)
            h64 = rotl(h64, 11) * prime1
            offset++
        }

        // final avalanche
        h64 = h64 xor (h64 ushr 33)
        h64 *= prime2
        h64 = h64 xor (h64 ushr 29)
        h64 *= prime3
        h64 = h64 xor (h64 ushr 32)
        return h64
    }

    private fun round(acc: Long, input: Long): Long {
        var a = acc + input * -4417276706812531889L
        a = rotl(a, 31)
        a *= -7046029288634856825L
        return a
    }

    private fun mergeRound(acc: Long, valV: Long): Long {
        var a = acc xor round(0L, valV)
        a = a * -7046029288634856825L + -8796714831421723037L
        return a
    }

    private fun readIntLE(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLongLE(buf: ByteArray, off: Int): Long {
        val b0 = buf[off].toLong() and 0xFF
        val b1 = buf[off + 1].toLong() and 0xFF
        val b2 = buf[off + 2].toLong() and 0xFF
        val b3 = buf[off + 3].toLong() and 0xFF
        val b4 = buf[off + 4].toLong() and 0xFF
        val b5 = buf[off + 5].toLong() and 0xFF
        val b6 = buf[off + 6].toLong() and 0xFF
        val b7 = buf[off + 7].toLong() and 0xFF
        return (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24) or
            (b4 shl 32) or (b5 shl 40) or (b6 shl 48) or (b7 shl 56)
    }
}

private fun rotl(x: Long, r: Int): Long {
    val n = r and 63
    return (x shl n) or (x ushr (64 - n))
}

// Deterministic digest for Block
fun blockDigest(
    namespace: String,
    id: String,
    field: String,
    value: String,
    hlc: String,
    seed: Long,
): Hash64 {
    val material = "$namespace|$id|$field|$value|$hlc"
    return XxHash64.hashString(material, seed)
}
