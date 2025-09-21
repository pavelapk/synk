# Conflict Sync

Conflict Sync is Synk's reconciliation engine. It mirrors the working Bloom+Rateless implementation that lives in the Rust reference repository at `/mnt/c/Users/user/IdeaProjects/conflict-sync/src/sync/bloomriblthashes.rs`. This document translates that algorithm to Kotlin, explains the data model planned in the `:synk` module, and shows how the transport contract fits around it. The goal is a simple, reliable implementation that exchanges only the necessary pieces of state.

## Terminology borrowed from the Rust engine

- **Namespace** – Synk’s stable identifier for a CRDT type. In practice it is the fully qualified Kotlin class name registered with `SynkAdapterStore`. Each sync session runs inside exactly one namespace so both peers can resolve the same adapter and metastore.
- **ObjectKey** – a pair `(namespace, id)` produced by the adapter’s `resolveId`. It uniquely names a CRDT object inside a namespace.
- **Join decomposition (`split`)** – the minimal CRDT fragment that travels over the wire. The Rust engine calls `Decompose::split()` to obtain these fragments and `join()` to apply them; the Kotlin port mirrors those calls.
- **`Decompose` / `Extract` / `Measure`** – trait trio that exposes `split`, `join`, a deterministic projection `extract()` used for hashing, and a lightweight size estimator. The Kotlin equivalents stay 1:1 so the Bloom and RIBLT logic can remain generic.
- **`BloomFilter`** – probabilistic membership structure sized using the configured false-positive rate `fpr` (`0.0 < fpr < 1.0`). Filters are serialized as raw bits plus the two hash seeds that keep hashing consistent across peers.
- **`RatelessIBLT`** – an invertible Bloom lookup table that streams `Symbol` values until both sides peel the set difference. Each coded symbol occupies `sizeof(u64) + sizeof(u64) + sizeof(i64)` bytes in Rust (`CODED_SYMBOL_SIZE`), and the Kotlin port preserves that shape.
- **`local_unknown` / `remote_unknown`** – decompositions that the Bloom filters classify as “definitely missing” on one side. These are sent as full payloads, including complete objects when a replica lacks every field for that object.

## Kotlin surface area

The Kotlin port keeps the Rust structure but maps it onto Synk adapters, the metastore, and typed payloads.

### Decompose, Extract, Measure

```kotlin
interface Decompose<Decomposition> {
    fun split(): List<Decomposition>
    fun join(deltas: List<Decomposition>)
}

interface Extract<Item> {
    fun extract(): Item
}

interface Measure {
    fun sizeOf(): Int
    fun falseMatches(other: Measure): Int
}
```

### Field decomposition and hashing

A field decomposition carries the minimum data required to merge one field on the remote peer. The `extract()` projection feeds both the Bloom filter and the rateless IBLT. Instead of concatenating strings by hand, we hash a canonical binary fingerprint produced by the encoder so that Kotlin and Rust agree on the hash input even if object keys evolve.

```kotlin
@JvmInline
value class FieldFingerprint(val bytes: ByteArray)

data class FieldDecomposition(
    val namespace: String,
    val objectKey: ObjectKey,
    val field: String,
    val encodedValue: ByteArray,
    val hlc: Hlc,
) : Extract<FieldFingerprint>, Measure {
    override fun extract(): FieldFingerprint = FieldFingerprint(
        fingerprintEncoder(namespace, objectKey, field, encodedValue, hlc)
    )

    override fun sizeOf(): Int = encodedValue.size + METADATA_BYTES

    override fun falseMatches(other: Measure): Int = when (other) {
        is FieldDecomposition -> if (objectKey == other.objectKey && field == other.field) 0 else 1
        else -> 1
    }
}
```

`fingerprintEncoder` produces a deterministic byte array (for example, via `Buffer.pack` with little-endian ordering) that already includes the namespace, object identifier, field name, encoded value digest, and HLC tick. The actual digest used in Bloom/RIBLT structures is `xxHash64(fieldFingerprint.bytes)`, matching the `RandomState::hash_one` call in Rust. If we ever swap hash functions, both peers change the implementation simultaneously.

### Replica state

`ReplicaState` is strongly typed and represents the *local* dataset only. A sync run starts on the initiator (for example, a mobile client) with one namespace, so the replica knows exactly which Kotlin data class it is reconciling. The remote side reacts to transport messages with its own copy of the same components; the initiator never has a direct handle to remote state.

```kotlin
class ReplicaState<T : Any>(
    private val namespace: String,
    private val source: StateSource<T>,
    private val adapter: SynkAdapter<T>,
    private val metaStore: MetaStore,
) : Decompose<FieldDecomposition> {
    override fun split(): List<FieldDecomposition> =
        source.snapshot().flatMap { obj ->
            val objectId = adapter.resolveId(obj)
            val objectKey = ObjectKey(namespace, objectId)
            val encodedFields = adapter.encodeToFields(obj) // stable per-field bytes
            val hlcCatalog = metaStore.readFields(objectId)

            encodedFields.map { field ->
                FieldDecomposition(
                    namespace = namespace,
                    objectKey = objectKey,
                    field = field.name,
                    encodedValue = field.bytes,
                    hlc = hlcCatalog[field.name] ?: Hlc.zero
                )
            }
        }

    override fun join(deltas: List<FieldDecomposition>) = deltas.forEach { delta ->
        Synk.inbound(namespace, delta, metaStore)
    }
}
```

`StateSource.snapshot()` stands in for whichever streaming API we expose (batching via `scan`, Flow-backed cursor, etc.). `encodeToFields` is the Synk serializer that yields per-field binary encodings, and `readFields` retrieves stored HLC metadata.

Remote peers run the same abstractions but respond to inbound requests. Their orchestration lives in a `ConflictSyncResponder` (described later); the initiator only ever sees remote effects via transport flows.

### Session driver (initiator)

The initiator is implemented by `ConflictSyncBloomRateless`, a name that makes the algorithm obvious while keeping the public API under the `ConflictSync` umbrella. The object owns *only* the local replica and delegates every remote interaction to the transport streams.

```kotlin
class ConflictSyncBloomRateless<T : Any>(
    private val fpr: Double,
    private val hasher: Hasher64 = Hasher64.xxHash64()
) {
    suspend fun sync(
        local: ReplicaState<T>,
        transport: ConflictSyncTransport,
        tracker: ConflictSyncTracker
    ) { /* mirrors the Rust steps from the initiator perspective */ }
}
```

Internally the driver:

1. Builds the local Bloom filter, sends it over the transport, and waits for the responder’s Bloom+rateless reply.
2. Partitions its decompositions with the responder’s Bloom filter.
3. Uses the rateless sketch returned by the responder to compute `localOnly` and `remoteOnly` hash buckets.
4. Streams missing field data and hash requests back to the responder, then applies incoming decompositions once the responder replies.

Because the initiator never holds remote state, the algorithm mirrors the Rust sequence while keeping the design realistic for a real-world library.

### Responder

The responder side listens on the same transport functions, builds its own `ReplicaState`, and executes the complementary steps:

1. Receives the initiator’s Bloom filter, partitions its decompositions, and sends back `remoteFilter + RatelessSketch`.
2. Processes follow-up decomposition payloads, returning data for any `HashRequest` envelopes.
3. Applies incoming deltas through `ReplicaState.join` exactly like the initiator.

Both sides share the same helper functions (`buildFilter`, `partition`, `findAllDifferences`); the only difference is whether they start the session or answer it.

### Transport contract (Flow-based)

The transport is expressed as a set of Flow transformers: callers supply a stream of outbound messages and receive a stream of responses. Returning a Flow keeps the API non-blocking without extra suspension points.

```kotlin
interface ConflictSyncTransport {
    fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload>
    fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload>
    fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope>
}
```

Typical implementations back these functions with WebSockets, HTTP streams, or BLE sessions. Each function represents a two-way exchange channel:

- `bloom` handles the initial Bloom filter handshake.
- `rateless` streams the coded symbols that let both sides peel hash differences.
- `decompositions` carries the heavier payloads (`Snapshot`, `Field`, `HashRequest`, `HashResponse`).

On the initiator the session driver builds finite flows for each stage and collects the responses sequentially. On the responder the functions simply map incoming flows to outgoing responses (for example, by using `flatMapConcat` or `channelFlow`).

`TransportEnvelope` remains the union of decomposition-related payloads:

```kotlin
sealed interface TransportEnvelope {
    data class Snapshot(val payload: SnapshotBlock) : TransportEnvelope
    data class Field(val payload: FieldBlock) : TransportEnvelope
    data class HashRequest(val hashes: List<Long>) : TransportEnvelope
    data class HashResponse(val fields: List<FieldDecomposition>) : TransportEnvelope
}
```

The Flow-based design lets us keep the sequential nature of the Rust benchmark while presenting an API that works for independent processes.

## Sync choreography

The control flow mirrors the numbered comments in `bloomriblthashes.rs`, but the responsibilities are split between initiator and responder.

1. **Initiator Bloom.** Initiator computes `localDecompositions = local.split()` and sends a single-element flow through `transport.bloom`. It then collects the responder’s Bloom filter from the returned flow.
2. **Responder partition.** Responder receives the Bloom filter, partitions `remote.split()` into `(remoteCommon, localUnknown)`, and sends back its own Bloom filter plus metadata needed for rateless decoding.
3. **Responder rateless.** Responder hashes `remoteCommon`, seeds a `RatelessIBLT`, and streams the sketch by calling `transport.rateless`. Initiator collects the sketch.
4. **Initiator partition + rateless.** Initiator partitions its decompositions using the responder’s Bloom filter, builds its own hash map/IBLT, and calls `findAllDifferences`. It now knows `remoteOnlyHashes` to request and `localOnly` items to send immediately.
5. **Initiator decompositions.** Initiator opens `transport.decompositions` with a flow that emits:
   - snapshots for missing objects,
   - field blocks for per-field deltas and Bloom false positives,
   - one `HashRequest` containing any `remoteOnlyHashes`.
6. **Responder decompositions.** Responder collects that flow, applies incoming snapshots/fields, looks up the requested hashes in `remoteHashes`, and responds by returning a `HashResponse` flow from the `decompositions` call.
7. **Initiator finish.** Initiator merges the `HashResponse`, applies all received decompositions locally, and calls `tracker.finish(falseMatches)`.
8. **Responder finish.** After replying to the `HashRequest`, responder also calls `join(localUnknown + localOnly)` and performs its tracker checks.

Every step stays deterministic because both sides hash the same `FieldFingerprint` bytes. No cache outlives the stage that needs it.

## Payloads

All payloads live in `:synk` so application code can depend on a single model.

```kotlin
data class BloomFilterPayload(
    val namespace: String,
    val bits: ByteArray,
    val hashSeed1: Long,
    val hashSeed2: Long
)

data class RatelessSketchPayload(
    val namespace: String,
    val symbols: List<RatelessSymbol>
)

sealed class DecompositionBlock {
    data class SnapshotBlock(
        val namespace: String,
        val objectKey: ObjectKey,
        val encodedObject: ByteArray,
        val fields: List<FieldDecomposition>
    ) : DecompositionBlock()

    data class FieldBlock(
        val namespace: String,
        val field: FieldDecomposition
    ) : DecompositionBlock()
}
```

`RatelessSymbol` matches the Rust layout `(key_sum: u64, value_sum: u64, count: i64)` serialized little-endian. `SnapshotBlock` satisfies the “ship complete objects that are missing on one replica” requirement by bundling the encoded object alongside its field decompositions so the receiver can seed the metastore accurately.

## Integration with Synk

- **Building decompositions.** Each `SynkAdapter<T>` already knows how to serialize its Kotlin data class and read `Meta` entries from the `MetaStore`. `split()` leverages that infrastructure to produce `FieldDecomposition`s with their HLCs and fingerprints.
- **Shipping missing objects.** When `remoteUnknown` or `localUnknown` covers every field of an object we emit a single `Snapshot` block. Receiving peers call the adapter’s `decode` before joining the individual field decompositions to populate metadata.
- **Applying inbound state.** `join` delegates to `Synk.inbound(namespace, …)` so existing merge semantics remain intact. Field-level merges perform HLC comparison before writing.
- **Transport plumbing.** The Flow-based transport maps cleanly to WebSockets, HTTP streaming, or BLE. Both initiator and responder provide flows to `ConflictSyncTransport`, keeping the handshake linear while letting each side run in separate processes.
- **No extra caches.** The algorithm only holds `BloomFilter`, `RatelessIBLT`, and the hash map required to resolve false positives. Everything else streams through.
- **Tracker hooks.** `ConflictSyncTracker` mirrors the Rust `DefaultTracker`. Production builds can swap in a no-op tracker while tests assert the same telemetry as the reference implementation.

## Failure and restart behaviour

Conflict Sync runs as a linear coroutine pipeline. If the transport fails mid-session we cancel the coroutine, drop transient state, and restart from step 1 on the next attempt. Because every envelope carries namespace, object key, and field fingerprint, replays are idempotent.

## Tunables

- `fpr` defaults to `0.01` (1% false-positive rate), matching the Rust default. Lower values grow Bloom filters but reduce false positives and follow-up payloads.
- Hash algorithm defaults to `xxHash64`; switching requires updating both peers and the `fingerprintEncoder`.
- Rateless sketch size grows until `findAllDifferences` succeeds. Producers typically emit symbols in small batches (e.g. 32 at a time) to keep the Flow responsive while matching Rust’s semantics.

The resulting design is a faithful translation of the Rust `BloomRibltHashes` strategy, adapted to a real-world `ConflictSync` API that separates initiator and responder roles while staying simple and reliable.
