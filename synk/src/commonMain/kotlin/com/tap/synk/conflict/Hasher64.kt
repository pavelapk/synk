package com.tap.synk.conflict

fun interface Hasher64 {
    fun hash(input: ByteArray, seed: Long): Long

    companion object {
        fun xxHash64(): Hasher64 = XxHash64Hasher
    }
}

private object XxHash64Hasher : Hasher64 {
    private const val PRIME1 = -7046029288634856825L // 0x9E3779B185EBCA87
    private const val PRIME2 = -4417276706812531889L // 0xC2B2AE3D27D4EB4F
    private const val PRIME3 = 1609587929392839161L  // 0x165667B19E3779F9
    private const val PRIME4 = -8796714831421723037L // 0x85EBCA77C2B2AE63
    private const val PRIME5 = 2870177450012600261L  // 0x27D4EB2F165667C5

    override fun hash(input: ByteArray, seed: Long): Long {
        val length = input.size
        var index = 0
        var hash: Long

        if (length >= 32) {
            var v1 = seed + PRIME1 + PRIME2
            var v2 = seed + PRIME2
            var v3 = seed
            var v4 = seed - PRIME1

            while (index <= length - 32) {
                v1 = round(v1, readLongLE(input, index)); index += 8
                v2 = round(v2, readLongLE(input, index)); index += 8
                v3 = round(v3, readLongLE(input, index)); index += 8
                v4 = round(v4, readLongLE(input, index)); index += 8
            }

            hash = v1.rotateLeft(1) +
                v2.rotateLeft(7) +
                v3.rotateLeft(12) +
                v4.rotateLeft(18)

            hash = mergeRound(hash, v1)
            hash = mergeRound(hash, v2)
            hash = mergeRound(hash, v3)
            hash = mergeRound(hash, v4)
        } else {
            hash = seed + PRIME5
        }

        hash += length.toLong()

        while (index <= length - 8) {
            val k1 = round(0L, readLongLE(input, index))
            hash = (hash xor k1).rotateLeft(27) * PRIME1 + PRIME4
            index += 8
        }

        if (index <= length - 4) {
            hash = (hash xor ((readIntLE(input, index).toLong() and 0xffffffffL) * PRIME1)).rotateLeft(23) * PRIME2 + PRIME3
            index += 4
        }

        while (index < length) {
            hash = (hash xor ((input[index].toLong() and 0xffL) * PRIME5)).rotateLeft(11) * PRIME1
            index++
        }

        hash = avalanche(hash)
        return hash
    }

    private fun round(acc: Long, input: Long): Long {
        var result = acc + input * PRIME2
        result = result.rotateLeft(31)
        result *= PRIME1
        return result
    }

    private fun mergeRound(acc: Long, value: Long): Long {
        var result = acc xor round(0L, value)
        result = result * PRIME1 + PRIME4
        return result
    }

    private fun avalanche(hash: Long): Long {
        var result = hash
        result = result xor (result ushr 33)
        result *= PRIME2
        result = result xor (result ushr 29)
        result *= PRIME3
        result = result xor (result ushr 32)
        return result
    }

    private fun readLongLE(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((buffer[offset + i].toLong() and 0xffL) shl (i * 8))
        }
        return value
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            value = value or ((buffer[offset + i].toInt() and 0xff) shl (i * 8))
        }
        return value
    }
}

internal fun Hasher64.hash(input: ByteArray): Long = hash(input, 0L)

private fun Long.rotateLeft(bits: Int): Long {
    val shift = bits and 63
    return (this shl shift) or (this ushr (64 - shift))
}
