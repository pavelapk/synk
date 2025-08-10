package com.tap.synk.conflictsync.rateless

internal object IntHash {
    fun mix64to32(x: Long): Int {
        var z = x
        z = (z xor (z ushr 33)) * -49064778989728563L
        z = (z xor (z ushr 33)) * -4265267296055464877L
        z = z xor (z ushr 33)
        return (z and 0xFFFFFFFF).toInt()
    }
}

