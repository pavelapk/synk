package com.tap.synk.strategy.bloomrateless

import com.tap.synk.sync.StrategyOption
import com.tap.synk.sync.SyncConfiguration
import com.tap.synk.sync.SyncContext
import com.tap.synk.sync.SyncRole
import com.tap.synk.sync.SyncSession
import com.tap.synk.sync.SyncStrategyFactory
import kotlinx.serialization.json.Json

object BloomRatelessStrategyFactory : SyncStrategyFactory {
    override fun <T : Any> createSession(role: SyncRole, context: SyncContext<T>): SyncSession<T> = when (role) {
        SyncRole.INITIATOR -> BloomRatelessInitiatorSession(context, bloomRatelessJson)
        SyncRole.RESPONDER -> BloomRatelessResponderSession(context, bloomRatelessJson)
    }
}

object BloomRatelessOptions {
    data object FalsePositiveRate : StrategyOption<Double>
    data object RatelessBatchSize : StrategyOption<Int>
    data object Hasher : StrategyOption<Hasher64>
}

internal data class BloomRatelessConfig(
    val falsePositiveRate: Double,
    val ratelessBatchSize: Int,
    val hasher: Hasher64,
)

internal fun SyncConfiguration.resolveBloomRatelessConfig(): BloomRatelessConfig {
    val fpr = option(BloomRatelessOptions.FalsePositiveRate, 0.01) { value ->
        require(value in 0.0..1.0 && value > 0.0) { "falsePositiveRate must be in the range (0, 1]" }
    }
    val batchSize = option(BloomRatelessOptions.RatelessBatchSize, 32) { value ->
        require(value > 0) { "ratelessBatchSize must be positive" }
    }
    val hasher = option(BloomRatelessOptions.Hasher, Hasher64.xxHash64())
    return BloomRatelessConfig(
        falsePositiveRate = fpr,
        ratelessBatchSize = batchSize,
        hasher = hasher,
    )
}

private inline fun <reified T : Any> SyncConfiguration.option(
    key: StrategyOption<T>,
    default: T,
    crossinline validator: (T) -> Unit = {},
): T {
    val value = options[key]
    val resolved = if (value == null) {
        default
    } else {
        value as? T ?: error("Unexpected value for option $key; expected ${T::class.simpleName}")
    }
    validator(resolved)
    return resolved
}

internal val bloomRatelessJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
