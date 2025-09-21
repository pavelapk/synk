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
@Serializable
@JvmInline
value class FieldFingerprint(val bytes: ByteArray)

@Serializable
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

Every type that crosses the transport boundary is annotated with `@Serializable` so applications can use Kotlin Serialization end to end. `ObjectKey` and `Hlc` already travel in existing Synk payloads and must also implement `@Serializable` for reuse here. `fingerprintEncoder` produces a deterministic byte array (for example, via `Buffer.pack` with little-endian ordering) that already includes the namespace, object identifier, field name, encoded value digest, and HLC tick. The digest used in Bloom/RIBLT structures is `xxHash64(fieldFingerprint.bytes)`, matching the `RandomState::hash_one` call in Rust. If we ever swap hash functions, both peers change the implementation simultaneously.

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

### Session orchestration: initiator

The initiator is implemented by `ConflictSyncBloomRateless`. The initiator/driver owns the local replica and delegates every remote interaction to transport flows.

```kotlin
class ConflictSyncBloomRateless<T : Any>(
    private val fpr: Double,
    private val hasher: Hasher64 = Hasher64.xxHash64()
) {
    suspend fun sync(
        local: ReplicaState<T>,
        transport: ConflictSyncTransport,
        tracker: ConflictSyncTracker
    ) { /* mirrors bloomriblthashes.rs from the initiator perspective */ }
}
```

Internally the driver:

1. Builds the local Bloom filter, sends it as a single-element flow, and collects the responder’s Bloom response.
2. Partitions its decompositions with the responder’s Bloom filter.
3. Collects the rateless sketch returned by the responder and computes `localOnly` / `remoteOnly` hash buckets.
4. Streams missing field data and hash requests, then applies incoming decompositions once the responder replies.

### Session orchestration: responder

The responder side listens on the same transport functions, builds its own `ReplicaState`, and executes the complementary steps:

1. Receives the initiator’s Bloom filter, partitions its decompositions, and emits its Bloom response.
2. Hashes the “probably common” decompositions, builds the rateless sketch, and returns it.
3. Processes incoming decomposition payloads, replying with matching data for any hash requests and joining deltas locally.

Both sides share helper routines (`buildFilter`, `partition`, `ribltFrom`, `findAllDifferences`). The only difference is whether they start the session or answer it.

### Transport contract (Flow transformers)

The Rust reference runs both replicas inside one process; in Synk the peers live on different machines. We express the network as Flow transformers that implement the same three message channels used in the Rust code: the Bloom handshake, the rateless sketch, and the actual decompositions.

```kotlin
interface ConflictSyncTransport {
    fun bloom(outbound: Flow<BloomFilterPayload>): Flow<BloomFilterPayload>
    fun rateless(outbound: Flow<RatelessSketchPayload>): Flow<RatelessSketchPayload>
    fun decompositions(outbound: Flow<TransportEnvelope>): Flow<TransportEnvelope>
}
```

- `bloom` mirrors steps 1–3 in `bloomriblthashes.rs`. The initiator supplies a flow containing its Bloom filter; the responder consumes it and returns a flow with its Bloom response.
- `rateless` mirrors steps 4–6. The responder hashes the “probably common” decompositions, streams rateless symbols, and the initiator collects them to drive `findAllDifferences`.
- `decompositions` mirrors steps 7–8. The initiator sends snapshots, field blocks, and hash requests; the responder responds with hash matches and its own field payloads.

Typical implementations back these functions with WebSockets, HTTP streaming, or BLE sessions. Flows give us back-pressure and cancellation for free while keeping the interface symmetric for initiator and responder.

All envelopes are serializable:

```kotlin
@Serializable
sealed interface TransportEnvelope {
    @Serializable
    data class Snapshot(val payload: SnapshotBlock) : TransportEnvelope

    @Serializable
    data class Field(val payload: FieldBlock) : TransportEnvelope

    @Serializable
    data class HashRequest(val hashes: List<Long>) : TransportEnvelope

    @Serializable
    data class HashResponse(val fields: List<FieldDecomposition>) : TransportEnvelope
}
```

### Sync choreography

The numbered steps from `bloomriblthashes.rs` map directly onto the initiator/responder interaction:

1. **Initiator Bloom (Rust step 1).** Initiator builds `local_filter` from `local.split()` and calls `transport.bloom(flowOf(localPayload))`. Tracker records metadata upload.
2. **Responder partition (Rust step 2).** Responder partitions its decompositions into `(remote_common, local_unknown)` using the received Bloom filter.
3. **Responder Bloom (Rust step 3).** Responder sends its own Bloom filter for `remote_common` through the returned Bloom flow.
4. **Responder rateless (Rust step 4).** Responder hashes `remote_common`, seeds a `RatelessIBLT`, and streams the sketch via `transport.rateless`.
5. **Initiator partition (Rust step 5).** Initiator partitions `local_decompositions` using `remote_filter`, obtaining `(local_common, remote_unknown)`.
6. **Initiator rateless (Rust step 6).** Initiator hashes `local_common`, runs `findAllDifferences`, and derives `local_only`/`remote_only` sets.
7. **Initiator decompositions (Rust step 7).** Initiator calls `transport.decompositions` with a flow that emits:
   - `Snapshot` blocks for every missing object in `remote_unknown`.
   - `Field` blocks for per-field deltas and Bloom false positives (`local_only`).
   - A single `HashRequest(remote_only_hashes)`.
8. **Responder decompositions (Rust step 8).** Responder applies incoming payloads, resolves requested hashes via `remote_hashes`, and responds with `HashResponse` blocks.
9. **Join (Rust step 9).** Each side joins the appropriate deltas into its local state.
10. **Tracker finish (Rust step 10).** Both sides call `tracker.finish(falseMatches)` using the Kotlin `Measure` implementation.

Every stage is deterministic because both sides hash the same `FieldFingerprint` bytes. No cache outlives the stage that needs it.

## Payloads

All transport payloads are serializable data classes stored in `:synk`.

```kotlin
@Serializable
data class BloomFilterPayload(
    val namespace: String,
    val bits: ByteArray,
    val hashSeed1: Long,
    val hashSeed2: Long
)

@Serializable
data class RatelessSketchPayload(
    val namespace: String,
    val symbols: List<RatelessSymbol>
)

@Serializable
sealed class DecompositionBlock {
    @Serializable
    data class SnapshotBlock(
        val namespace: String,
        val objectKey: ObjectKey,
        val encodedObject: ByteArray,
        val fields: List<FieldDecomposition>
    ) : DecompositionBlock()

    @Serializable
    data class FieldBlock(
        val namespace: String,
        val field: FieldDecomposition
    ) : DecompositionBlock()
}

@Serializable
data class RatelessSymbol(
    val keySum: Long,
    val valueSum: Long,
    val count: Long
)
```

`RatelessSymbol` mirrors the Rust `(key_sum: u64, value_sum: u64, count: i64)` layout. `SnapshotBlock` satisfies the “ship complete objects that are missing on one replica” requirement by bundling the encoded object alongside its field decompositions so the receiver can seed the metastore accurately.

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

The resulting design is a faithful translation of the Rust `BloomRibltHashes` strategy, adapted to a two-sided `ConflictSync` API that separates initiator and responder roles, mirrors the original step sequence, and serializes every transport payload with Kotlin Serialization.
