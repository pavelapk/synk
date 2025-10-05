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

1. **`:sync:core` (new Kotlin Multiplatform module)**
   - Hosts the new abstractions (`SyncStrategyFactory`, `SyncSession`, `SyncRole`, `SyncContext`, `SyncStats`, `SyncTracker`, `SyncTransport`).
   - Depends on `:libs:annotations` for shared annotations and on targeted pieces of `:synk` (object keys, adapters, metadata contracts).
   - Ships the default tracker implementation and shared utility contracts (e.g., paging helpers) so strategies can rely on a stable base.

2. **`:sync:strategies:conflict-bloom` (new Kotlin Multiplatform module)**
   - Depends on `:sync:core` and migrates the Bloom+Rateless implementation out of `:synk`.
   - Contains the code currently under `com.tap.synk.conflict`, including Bloom filter utilities, rateless IBLT, transport payloads, and `ReplicaState`.
   - Exposes a `ConflictBloomStrategyFactory` that produces initiator/responder sessions conforming to the new abstraction.

3. **`:synk` adjustments**
   - Retains ownership of `Synk`, adapter stores, and metastore wiring.
   - Replaces the existing `conflictSync` entry points with a unified `sync` API that requires a `SyncStrategyFactory` and the new `SyncTransport` contract.
   - Depends on `:sync:core` and declares `ConflictBloomStrategyFactory` as the default dependency, but the module no longer vendors the implementation details.

`settings.gradle.kts` will include the new modules and wire them into dependency graphs so that `:synk` depends only on `:sync:core` at compile time and optionally on strategy artifacts at runtime.

### Abstractions

The following interfaces are introduced in `:sync:core`:

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
    val namespace: String
    val adapter: SynkAdapter<T>
    val stateSource: SyncStateSource<T>
    val mergeHandler: SyncMergeHandler<T>?
    val synk: Synk
    val metaStore: MetaStore
    suspend fun warm()
}

interface SyncStateSource<T : Any> {
    suspend fun scan(after: ObjectKey?, limit: Int): List<T>
    suspend fun byId(id: String): T?
}

fun interface SyncMergeHandler<T : Any> {
    suspend fun merge(namespace: String, value: T)
}

interface SyncTracker {
    fun isReady(): Boolean
    fun register(event: SyncTransferEvent)
    fun finish(falseMatches: Int)
}

data class SyncStats(
    val namespace: String,
    val scanned: Int,
    val outbound: List<SyncMessage>,
    val inbound: List<SyncMessage>,
    val falseMatches: Int,
)
```

- `SyncStrategyFactory` replaces hard-coded driver instantiation. The `Synk.sync` entry point resolves a factory from a registry and passes the appropriate role (initiator/responder).
- `SyncContext` adapts the existing data plumbing while renaming the interfaces to `SyncStateSource`/`SyncMergeHandler` to mark the breaking change and leave room for signature evolution.
- `SyncTracker` and `SyncStats` move to `:sync:core`, keeping the bandwidth accounting semantics but allowing strategies to emit their own `SyncTransferEvent` subclasses.
- `SyncTransferEvent` is introduced alongside `SyncTracker` as a sealed hierarchy that replaces `ConflictSyncEvent` and models uploads, downloads, and strategy-defined telemetry hooks.

#### Transport Contract

Backward compatibility is not preserved, so `:sync:core` defines a new transport contract that replaces `ConflictSyncTransport`:

```kotlin
interface SyncTransport {
    fun outbound(channel: SyncChannel, payloads: Flow<SyncMessage>): Flow<SyncMessage>
}

enum class SyncChannel { BLOOM, SKETCH, PAYLOAD }

sealed interface SyncMessage {
    data class Bloom(val namespace: String, val payload: BloomPayload) : SyncMessage
    data class Sketch(val namespace: String, val symbols: List<RatelessSymbol>) : SyncMessage
    data class Payload(val namespace: String, val body: PayloadEnvelope) : SyncMessage
}
```

- A single `outbound` function replaces the three dedicated Flow transformers. Strategies multiplex messages by `SyncChannel` and interpret the `SyncMessage` envelope as needed.
- `PayloadEnvelope` is free to evolve; the Conflict Bloom strategy migrates its existing `TransportEnvelope` structures into the new hierarchy, while other strategies can define alternative payloads.
- Because compatibility is not a goal, integrators must adopt this transport and translate their networking layer accordingly.

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

Each strategy publishes its own option keys. For example, the Conflict Bloom strategy introduces options for false-positive rate and rateless batch size and enforces defaults identical to the current implementation.

### Default Strategy Extraction

All classes currently under `com.tap.synk.conflict` move to the new strategy module with package `com.tap.synk.strategy.conflictbloom`. Key adjustments:

- `ReplicaState` becomes an internal detail that implements a `SyncReplica` contract defined in `:sync:core`. Other strategies can provide alternative replica implementations.
- `ConflictSyncBloomRateless` and `ConflictSyncResponder` are rewritten as `ConflictBloomInitiatorSession` and `ConflictBloomResponderSession`, both implementing `SyncSession<T>`.
- Transport payloads (`BloomFilterPayload`, `RatelessSketchPayload`, `DecompositionBlock`, etc.) migrate into the strategy module and adopt the new `SyncMessage.Payload` envelopes.

### Runtime Wiring

`Synk` exposes a new API surface:

```kotlin
suspend fun <T : Any> Synk.sync(
    namespace: KClass<T>,
    role: SyncRole,
    transport: SyncTransport,
    strategy: SyncStrategyFactory = defaultSyncStrategy,
    configuration: SyncConfiguration = SyncConfiguration.Default,
    tracker: SyncTracker = DefaultSyncTracker,
): SyncStats
```

- Callers must explicitly choose `SyncRole` (initiator vs responder). Convenience inline functions may wrap the common initiator/responder cases but they delegate to the new API directly.
- The prior `conflictSync`/`respondConflictSync` functions are removed. A migration guide will point integrators to the new API signatures and transport contract.
- `Synk.Builder` gains registration methods for `SyncStrategyFactory` and the rebranded `SyncStateSource`/`SyncMergeHandler`. Because compatibility is not maintained, these methods replace the existing registration DSL instead of decorating it.

### Testing Strategy

- Promote the current ConflictSync unit tests into the new strategy module and update them to use `SyncTransport` and `SyncSession`.
- Add contract tests inside `:sync:core` that validate the `Synk.sync` entry point with a fake strategy capturing all callbacks.
- Provide sample tests (e.g., a naive “full state” strategy) that demonstrate how strategies interact with `SyncContext` and the transport surface, giving integrators working examples to copy.

## Implementation Plan

1. **Scaffold core abstractions**
   - Create `:sync:core` with the new interfaces, transport contract, tracker implementation, and context adapters.
   - Update `Synk` to depend on `:sync:core`, introduce the new registration DSL, and drop the legacy `ConflictSyncRegistry`.

2. **Migrate the Conflict Bloom strategy**
   - Create `:sync:strategies:conflict-bloom` and move algorithm code over, renaming packages and adapting to the new transport.
   - Replace direct usages inside `:synk` with calls into `ConflictBloomStrategyFactory` via the `SyncStrategyFactory` abstraction.

3. **Remove legacy APIs**
   - Delete `Synk.conflictSync`, `Synk.respondConflictSync`, `ConflictSyncConfiguration`, `ConflictSyncTransport`, and related types from `:synk`.
   - Provide documentation that explains the new API shape and highlights the breaking changes.

4. **Update build configuration**
   - Register the new modules in `settings.gradle.kts`.
   - Adjust dependency declarations (`build.gradle.kts` files) so that strategy modules depend on `:sync:core`, and `:synk` depends only on `:sync:core` plus explicitly listed strategies.

5. **Refresh tests and CI**
   - Port existing unit tests to the new modules and ensure they compile against the updated APIs.
   - Add new integration tests that cover a full sync run through `Synk.sync` using the new transport contract.
   - Update CI scripts to run tests for the additional modules.

6. **Documentation**
   - Revise `docs/conflict-sync.md` (or split into multiple documents) to describe the new abstractions and transport.
   - Publish a migration guide that shows how to move from the old API to the new `SyncTransport`/`SyncStrategyFactory` model.

## Risks & Mitigations

- **Consumer migration burden:** Removing compatibility forces partner applications to update their networking layer and API calls. We mitigate by providing thorough documentation, migration examples, and sample strategies.
- **Module graph churn:** Adding new modules and deleting old ones changes the Gradle structure. A phased rollout and CI updates in step 4 keep build stability in check.
- **Strategy context coupling:** Exposing more of `Synk` internals through `SyncContext` risks recreating tight coupling. We document intended usage, keep the interface minimal, and enforce it via lint/tests.
- **Transport redesign regressions:** The new `SyncTransport` may introduce bugs if not carefully validated. Integration tests and property-based tests against the migrated Conflict Bloom strategy help ensure feature parity with the current behaviour.

## Open Questions

1. Should `SyncTransport` expose structured channels as shown, or should we move to a message bus model that allows truly arbitrary message types per strategy?
2. Do we need a shared serialization module for transport payloads, or should each strategy define its own Kotlin serialization configuration?
3. What is the minimum migration guidance required for existing integrators, and should we version the new API as part of a major release?
4. Should we formalize a compatibility policy (e.g., semantic versioning) for strategy interfaces to avoid another hard break in the near future?
5. How do we handle scenarios where multiple strategies need to coexist within the same application session (e.g., fallback strategies)?
