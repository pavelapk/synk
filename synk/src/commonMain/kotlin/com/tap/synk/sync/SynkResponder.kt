package com.tap.synk.sync

import com.tap.synk.Synk
import kotlin.jvm.JvmName
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SyncResponderSession(
    val transport: SyncTransport,
    val configuration: SyncConfiguration? = null,
    val tracker: SyncTracker? = null,
    val onResult: suspend (Result<SyncStats>) -> Unit = {},
)

@JvmName("serveResponderSessionsReified")
suspend inline fun <reified T : Any> Synk.serveResponder(
    sessions: Flow<SyncResponderSession>,
    strategy: SyncStrategyFactory? = null,
    defaultConfiguration: SyncConfiguration = SyncConfiguration.Default,
    noinline trackerFactory: () -> SyncTracker = ::defaultSyncTracker,
) = serveResponder(T::class, sessions, strategy, defaultConfiguration, trackerFactory)

@JvmName("serveResponderTransportsReified")
suspend inline fun <reified T : Any> Synk.serveResponder(
    transports: Flow<SyncTransport>,
    strategy: SyncStrategyFactory? = null,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    noinline trackerFactory: () -> SyncTracker = ::defaultSyncTracker,
) = serveResponder(T::class, transports, strategy, configuration, trackerFactory)

@JvmName("serveResponderWithSessions")
suspend fun <T : Any> Synk.serveResponder(
    namespace: KClass<T>,
    sessions: Flow<SyncResponderSession>,
    strategy: SyncStrategyFactory? = null,
    defaultConfiguration: SyncConfiguration = SyncConfiguration.Default,
    trackerFactory: () -> SyncTracker = ::defaultSyncTracker,
) {
    val factory = strategy ?: syncRegistry.defaultStrategyFactory()
        ?: throw IllegalStateException(
            "No SyncStrategyFactory provided. Pass one explicitly or register a default on Synk.Builder",
        )

    val context = buildSyncContext(namespace)
    context.warm()

    sessions.collect { session ->
        val tracker = session.tracker ?: trackerFactory()
        val configuration = session.configuration ?: defaultConfiguration
        val syncSession = factory.createSession(SyncRole.RESPONDER, context)
        try {
            val stats = syncSession.run(
                transport = session.transport,
                tracker = tracker,
                configuration = configuration,
            )
            session.onResult(Result.success(stats))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            runCatching { session.onResult(Result.failure(t)) }
        }
    }
}

@JvmName("serveResponderWithTransports")
suspend fun <T : Any> Synk.serveResponder(
    namespace: KClass<T>,
    transports: Flow<SyncTransport>,
    strategy: SyncStrategyFactory? = null,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    trackerFactory: () -> SyncTracker = ::defaultSyncTracker,
) = serveResponder(
    namespace = namespace,
    sessions = transports.map { transport -> SyncResponderSession(transport = transport) },
    strategy = strategy,
    defaultConfiguration = configuration,
    trackerFactory = trackerFactory,
)
