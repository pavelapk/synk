package com.tap.synk.conflict

import com.github.michaelbull.result.getOrElse
import com.tap.hlc.HybridLogicalClock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object HybridLogicalClockSerializer : KSerializer<HybridLogicalClock> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HybridLogicalClock", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HybridLogicalClock) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): HybridLogicalClock {
        val encoded = decoder.decodeString()
        return HybridLogicalClock.decodeFromString(encoded)
            .getOrElse { HybridLogicalClock() }
    }
}
