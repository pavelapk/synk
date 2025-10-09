# RFC: Pluggable Sync Strategies for Synk

- Status: Draft
- Last updated: 2025-10-05
- Authors: Codex

## Background

The `:synk` module ships the entire ConflictSync implementation inline. The public entry points `Synk.conflictSync` and `Synk.respondConflictSync` (see `synk/src/commonMain/kotlin/com/tap/synk/ConflictSync.kt:13`) always instantiate `ConflictSyncBloomRateless` and `ConflictSyncResponder`. Both drivers live in `com.tap.synk.conflict` alongside Bloom filter helpers, rateless IBLT primitives, and the replica/materialization layer. Because the strategy is constructed directly inside the entry points, application code cannot swap in an alternative reconciliation algorithm without forking the module or duplicating large portions of runtime wiring.

The supporting infrastructure (for example `StateSource`, `MergeHandler`, `ReplicaState`, `ConflictSyncTracker`, and `ConflictSyncTransport`) is implemented with the Bloom+IBLT algorithm in mind. Parameters exposed through `ConflictSyncConfiguration` (`synk/src/commonMain/kotlin/com/tap/synk/conflict/ConflictSyncConfiguration.kt`) are specific to the default algorithm (false positive rate, rateless batch size, etc.). This design has allowed the current implementation to function, but it prevents modular replacement and complicates testing new approaches.

## Problem Statement

We need to make synchronization strategies replaceable without cloning the entire `:synk` module. The current ConflictSync algorithm should remain available as a library-provided strategy, but the runtime needs to depend only on an abstraction so that partner applications can inject custom logic (for example, a “send everything” strategy for high bandwidth scenarios). Today, the strategy is inseparable from the runtime; changing the algorithm requires editing `Synk.conflictSync`, `ReplicaState`, and multiple helpers, increasing regression risk and blocking experimentation.

## Goals

- Introduce a synchronization strategy abstraction that callers and integrators can swap without modifying `:synk` internals.
- Extract the existing Bloom+Rateless implementation into a dedicated module that depends on the abstraction.
- Improve testability by letting strategies be exercised in isolation with fake replicas/transports.

## Non Goals

- Rewriting the ConflictSync algorithm itself.
- Maintaining backwards compatibility for the current public APIs or transport contract.
- Providing migration tooling that keeps existing third-party integrations working without code changes.
- Overhauling metadata storage or adapter APIs beyond what is required for strategy pluggability.

## Proposed Design

### Module Topology

1. **`:synk` core package refresh**
   - Houses all synchronization abstractions under a new `com.tap.synk.sync` namespace so consumers continue to depend on a single module for the public API.
   - Refactors existing builder/registry wiring to speak in terms of the new strategy contracts while keeping adapters, metastore factories, and message types colocated.
   - Provides default tracker implementations and serialization helpers that strategies can reuse.

2. **`:sync-strategies:bloom-rateless` (Kotlin Multiplatform module)**
   - Depends on `:synk` and migrates the Bloom+Rateless implementation out of the core module.
   - Contains the code currently under `com.tap.synk.conflict`, including Bloom filter utilities, rateless IBLT, transport payloads, and replica helpers.
   - Exposes a `BloomRatelessStrategyFactory` that produces initiator/responder sessions conforming to the new abstraction.

3. **`:sync-strategies:full-state` (Kotlin Multiplatform module)**
   - Implements a naive "ship the whole snapshot" strategy via `FullStateStrategyFactory` for high-bandwidth fallbacks and integration tests.
   - Demonstrates how strategies can reuse the shared abstractions without depending on Bloom-specific helpers.

4. **Future strategy modules (optional)**
   - Additional strategies (for example, delta-based sync (in addition to ConflictSync) or app-specific heuristics) can live in sibling modules (`:sync-strategies:*`) that all depend on the shared contracts inside `:synk`.

`settings.gradle.kts` registers `:sync-strategies:bloom-rateless` and `:sync-strategies:full-state`; `:synk` remains the single entry point for consumers and continues to publish the library DSL.

### Abstractions

The following interfaces live in `:synk` under `com.tap.synk.sync`:

```kotlin
interface SyncStrategyFactory {
    fun <T : Any> createSession(role: SyncRole, context: SyncContext<T>): SyncSession<T>
}

enum class SyncRole { INITIATOR, RESPONDER }

interface SyncSession<T : Any> {
    suspend fun run(
        transport: SyncTransport,
        tracker: SyncTracker,
        configuration: SyncConfiguration = SyncConfiguration.Default,
    ): SyncStats
}

interface SyncContext<T : Any> {
    val adapter: SynkAdapter<T>
    val stateSource: SyncStateSource<T>
    val mergeHandler: SyncMergeHandler<T>?
    val synk: Synk
    val metaStore: MetaStore
    val namespace: String
    suspend fun warm()
}

interface SyncStateSource<T : Any> {
    suspend fun scan(afterId: String?, limit: Int): List<T>
    suspend fun load(id: String): T?
}

fun interface SyncMergeHandler<T : Any> {
    suspend fun merge(value: T)
}

interface SyncTracker {
    fun isReady(): Boolean
    fun register(event: SyncTransferEvent)
    fun finish()
}

data class SyncStats(
    val namespace: String,
    val scanned: Int,
    val outboundMessages: Int,
    val outboundBytes: Long,
    val inboundMessages: Int,
    val inboundBytes: Long,
)
```

- `SyncStrategyFactory` replaces hard-coded driver instantiation. The `Synk.sync` entry point resolves a factory from a registry and passes the appropriate role (initiator/responder).
- `SyncContext` exposes `namespace`, derived from the registered adapter, so strategies can log/report without requiring an explicit namespace argument.
- `SyncStateSource` reuses the existing `IDResolver` contract by paging with string identifiers, ensuring strategies can iterate deterministically without introducing new cursor types.
- Because `SynkAdapter` already implements `IDResolver`, strategies can derive identifiers directly from registered adapters and keep pagination consistent across state sources and merge handlers.
- `SyncMergeHandler` no longer receives a namespace parameter; handlers can capture any additional context they need when they are registered.
- `SyncTracker`, `SyncStats`, and the `SyncTransferEvent` data class reside in `:synk`, centralising telemetry so strategies emit consistent transfer events without rolling their own accounting. `SyncStats` now reports aggregate message counts and byte totals rather than materialising every `SyncRequest`/`SyncResponse`, and the shared `InstrumentedSyncTransport` wrapper records those totals automatically so strategies only produce payloads.
`SyncReplica<T>` (not shown above) encapsulates snapshot/materialisation helpers so individual strategies can decide how to split state or apply inbound envelopes without depending on Bloom-specific data types.
#### Transport Contract

`SyncTransport` remains transport-agnostic and embraces Kotlin serialization so integrators can implement networking without bespoke encoders:

```kotlin
@Serializable
sealed interface SyncPayload {
    @Serializable
    data class Json(val body: JsonElement) : SyncPayload

    @Serializable
    data class Binary(val base64: String) : SyncPayload
}

@Serializable
data class SyncRequest(
    val channel: String,
    val payload: SyncPayload,
)

@Serializable
data class SyncResponse(
    val channel: String,
    val payload: SyncPayload,
)

interface SyncTransport {
    fun exchange(outbound: Flow<SyncRequest>): Flow<SyncResponse>
}
```

- `channel` is a free-form string chosen by the strategy (`"bloom"`, `"snapshot"`, `"full-state"`, etc.), so the core contract stays agnostic to any specific algorithmic phases.
- `SyncPayload.Json` covers the common case where strategies rely on `kotlinx.serialization` to encode structured payloads, while `SyncPayload.Binary` wraps a base64 string so equality/hashCode stay well-defined without pulling `ByteArray` into a data class. Helper functions in `:synk` convert to and from raw byte arrays when needed.
- `SyncTransport.exchange` now separates request and response flows, giving strategies clearer semantics when they expect replies (e.g., hash lookups) versus fire-and-forget messages. `:synk` ships `SyncSerializer` helpers that bind strategy-defined types to `SyncRequest`/`SyncResponse` using a pluggable `SerializersModule`.
#### Configuration Surface

`SyncConfiguration` provides a minimal core shape plus strategy-specific options:

```kotlin
data class SyncConfiguration(
    val pageSize: Int = 256,
    val options: Map<StrategyOption<*>, Any?> = emptyMap(),
) {
    object Default : SyncConfiguration()
}

interface StrategyOption<T : Any>
```

Each strategy publishes its own option keys. For example, the Bloom Rateless strategy introduces options for false-positive rate and rateless batch size and enforces defaults identical to the current implementation.

### Default Strategy Extraction

All classes currently under `com.tap.synk.conflict` move to the new strategy module with package `com.tap.synk.strategy.bloomrateless`. Key adjustments:

- `ReplicaState` becomes an internal detail that implements the new `SyncReplica` contract declared in `:synk`, so other strategies can swap in alternative replica implementations without depending on Bloom-specific helpers.
- `ConflictSyncBloomRateless` and `ConflictSyncResponder` are rewritten as `BloomRatelessInitiatorSession` and `BloomRatelessResponderSession`, both implementing `SyncSession<T>`.
- Existing payload types (`BloomFilterPayload`, `RatelessSketchPayload`, `DecompositionBlock`, etc.) migrate into the strategy module and serialize through the shared `SyncRequest`/`SyncResponse` helpers instead of custom transport channels.
### Runtime Wiring

`Synk` exposes a new API surface:

```kotlin
suspend fun <T : Any> Synk.sync(
    namespace: KClass<T>,
    role: SyncRole,
    transport: SyncTransport,
    strategy: SyncStrategyFactory = BloomRatelessStrategyFactory,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    tracker: SyncTracker = DefaultSyncTracker,
): SyncStats
```

- Callers must explicitly choose `SyncRole` (initiator vs responder). Convenience inline functions may wrap the common initiator/responder cases but they delegate to the new API directly.
- The prior `conflictSync`/`respondConflictSync` functions are removed. Updated README/Docs will call out the new API signatures and transport contract so integrators can adjust quickly.
- `Synk.Builder` gains registration methods for `SyncStrategyFactory` and the rebranded `SyncStateSource`/`SyncMergeHandler`. Because compatibility is not maintained, these methods replace the existing registration DSL instead of decorating it.
- `BloomRatelessStrategyFactory` is re-exported from `:synk` so existing callers that rely on the default behaviour continue to receive the Bloom+Rateless implementation without additional dependency declarations.
- Fallback workflows remain application-driven: stop the active session and invoke `Synk.sync` again with a different factory whenever network conditions demand a new strategy.

### Testing Strategy

- Promote the current ConflictSync unit tests into the new strategy module and update them to use `SyncTransport` and `SyncSession`.
- Add contract tests inside `:synk` that validate the `Synk.sync` entry point with a fake strategy capturing all callbacks.
- Provide sample tests (e.g., a naive “full state” strategy) that demonstrate how strategies interact with `SyncContext` and the transport surface, giving integrators working examples to copy.

## Implementation Plan

1. **Refactor `:synk` core**
   - Introduce the new `com.tap.synk.sync` package, define the strategy interfaces, cursors, trackers, and serializer helpers, and replace the legacy `ConflictSyncRegistry`.
   - Update `Synk` and its builder DSL to register strategies, cursors, and merge handlers using the new abstractions.

2. **Extract the Bloom Rateless strategy**
   - Create `:sync-strategies:bloom-rateless`, move the existing ConflictSync code, and adapt it to the new `SyncRequest`/`SyncResponse` transport helpers.
   - Replace direct references inside `:synk` with calls into `BloomRatelessStrategyFactory` via the `SyncStrategyFactory` abstraction.

3. **Remove legacy APIs**
   - Delete `Synk.conflictSync`, `Synk.respondConflictSync`, `ConflictSyncConfiguration`, `ConflictSyncTransport`, and related types from `:synk`.
   - Provide documentation that explains the new API shape and highlights the breaking changes.

4. **Update build configuration**
   - Register `:sync-strategies:bloom-rateless` and `:sync-strategies:full-state` in `settings.gradle.kts`.
   - Adjust dependency declarations so strategy modules depend on `:synk`, and the root publication includes the default Bloom Rateless strategy while shipping the full-state fallback as an optional artefact.

5. **Refresh tests and CI**
   - Port existing unit tests to the new module and ensure they compile against the updated APIs.
   - Add new integration tests that cover a full sync run through `Synk.sync` using the generalized transport contract, including the full-state strategy as a worked example.
   - Update CI scripts to run tests for the additional modules.

6. **Documentation**
   - Refresh `README.md` and `docs/conflict-sync.md` to describe the new public API surface and transport helpers.
   - Note in release notes that the strategy interfaces remain experimental and may evolve, so early adopters should track changes closely.
## Risks & Mitigations

- **Consumer migration burden:** Removing compatibility forces partner applications to update their networking layer and API calls. We mitigate by providing thorough documentation, migration examples, and sample strategies.
- **Module graph churn:** Adding new modules and deleting old ones changes the Gradle structure. A phased rollout and CI updates in step 4 keep build stability in check.
- **Strategy context coupling:** Exposing more of `Synk` internals through `SyncContext` risks recreating tight coupling. We document intended usage, keep the interface minimal, and enforce it via lint/tests.
- **Transport redesign regressions:** The new `SyncTransport` may introduce bugs if not carefully validated. Integration tests and property-based tests against the migrated Bloom Rateless strategy help ensure feature parity with the current behaviour.
- **Experimental API expectations:** Strategy contracts remain explicitly experimental; communicate this status in release notes so early integrators understand that breaking changes may arrive without full deprecation cycles.

## Open Questions

_None at this stage; outstanding items have documented decisions in the text above._
