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

`ReplicaState` is strongly typed. A sync run is launched per namespace, so both peers know which Kotlin data class they are working with.

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

`StateSource.snapshot()` stands in for whichever streaming API we expose (batching via `scan`, Flow-backed cursor, etc.). `encodeToFields` is the Synk serializer that yields per-field binary encodings, and `readFields` retrieves stored HLC metadata. Remote peers resolve the same adapter via `namespace` before applying incoming deltas, guaranteeing type safety throughout the session.

### Session driver

`BloomRibltHashes` remains the single entry point, with the same numbered steps as the Rust code and typed state/transport inputs.

```kotlin
class BloomRibltHashes<T : Any>(
    private val fpr: Double,
    private val hasher: Hasher64 = Hasher64.xxHash64()
) {
    suspend fun sync(
        local: ReplicaState<T>,
        remote: ReplicaState<T>,
        transport: ConflictSyncTransport,
        tracker: ConflictSyncTracker
    ) { /* mirrors the Rust steps */ }
}
```

The implementation constructs Bloom filters, partitions decompositions, runs the rateless exchange, and calls `join` exactly as the reference does. The driver never caches beyond the working collections required by the algorithm.

### Transport contract (Flow-based)

To align with Kotlin-first transports we expose a Flow-based API. Each side collects inbound messages while emitting outbound messages into the same abstraction. Back-pressure and cancellation naturally propagate through coroutines.

```kotlin
interface ConflictSyncTransport {
    val inbound: Flow<TransportEnvelope>
    suspend fun emit(message: TransportEnvelope)
}

sealed interface TransportEnvelope {
    data class Bloom(val payload: BloomFilterPayload) : TransportEnvelope
    data class Rateless(val payload: RatelessSketchPayload) : TransportEnvelope
    data class Snapshot(val payload: SnapshotBlock) : TransportEnvelope
    data class Field(val payload: FieldBlock) : TransportEnvelope
    data class HashRequest(val hashes: List<Long>) : TransportEnvelope
    data class HashResponse(val fields: List<FieldDecomposition>) : TransportEnvelope
}
```

Callers typically back the transport with channels or persistent connections. The session driver consumes `inbound` in order, emits `Bloom` followed by `Rateless` and decomposition envelopes, and waits for the corresponding responses. This matches the sequential flow of the Rust algorithm while keeping the implementation streaming-friendly.

## Sync choreography

The control flow mirrors the numbered comments in `bloomriblthashes.rs`.

1. **Local filter.** Local replica computes `localDecompositions = local.split()` and builds `localFilter = BloomFilter(localDecompositions, fpr)`. It emits a `Bloom` envelope that includes namespace metadata.
2. **Remote partition.** Remote partitions `remote.split()` using `localFilter`, yielding `(remoteCommon, localUnknown)`. `localUnknown` is staged for `Snapshot` or `Field` envelopes depending on whether a whole object is missing.
3. **Remote filter.** Remote builds `remoteFilter = BloomFilter(remoteCommon, fpr)`, hashes each decomposition using `hasher.hash(fieldFingerprint.bytes)`, stores them in `remoteHashes: HashMap<Long, FieldDecomposition>`, and seeds a `RatelessIBLT` with the hash set.
4. **Remote metadata.** Remote emits `Bloom(remoteFilter)` and a `Rateless` envelope that streams the coded symbols (`sketch_size * CODED_SYMBOL_SIZE`).
5. **Local partition.** Local partitions its decompositions using `remoteFilter`, yielding `(localCommon, remoteUnknown)`. `remoteUnknown` becomes the payload to send immediately after the rateless exchange finishes.
6. **Local rateless.** Local builds `localHashes` and `localIBLT`, then calls `localIBLT.findAllDifferences(remoteIBLT)`. The call returns `localOnlyHashes` and `remoteOnlyHashes`.
7. **Local payload.** Local emits envelopes for:
   - every `remoteUnknown` decomposition, folding them into `Snapshot` blocks when all fields of an object are missing,
   - the decompositions that correspond to `localOnlyHashes` (false positives),
   - a `HashRequest(remoteOnlyHashes)` asking the remote peer to return the missing decompositions.
8. **Remote response.** Remote resolves `remoteOnlyHashes` via `remoteHashes` and emits a matching `HashResponse` with the requested decompositions.
9. **Join.** Remote calls `join(localUnknown + localOnly)` and local calls `join(remoteUnknown + remoteOnly)`; both replicas converge.
10. **Tracker finish.** The driver calls `tracker.finish(falseMatches)` using the Kotlin `Measure` logic, giving parity with the Rust telemetry.

Every step is deterministic: we hash the same canonical fingerprint and we discard decompositions as soon as they are sent or applied.

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
- **Shipping missing objects.** When `remoteUnknown` or `localUnknown` covers every field of an object we emit a single `Snapshot` envelope. Receiving peers call the adapter’s `decode` before joining the individual field decompositions to populate metadata.
- **Applying inbound state.** `join` delegates to `Synk.inbound(namespace, …)` so existing merge semantics remain intact. Field-level merges perform HLC comparison before writing.
- **Transport plumbing.** The Flow-based transport lets applications bridge HTTP streaming, WebSockets, BLE, or any bidirectional medium simply by exposing a Flow and coroutine send primitive.
- **No extra caches.** The algorithm only holds `BloomFilter`, `RatelessIBLT`, and the hash map required to resolve false positives. Everything else streams through.
- **Tracker hooks.** `ConflictSyncTracker` mirrors the Rust `DefaultTracker`. Production builds can swap in a no-op tracker while tests assert the same telemetry as the reference implementation.

## Failure and restart behaviour

Conflict Sync runs as a linear coroutine pipeline. If the transport fails mid-session we cancel the coroutine, drop transient state, and restart from step 1 on the next attempt. Because every envelope carries namespace, object key, and field fingerprint, replays are idempotent.

## Tunables

- `fpr` defaults to `0.01` (1% false-positive rate), matching the Rust default. Lower values grow Bloom filters but reduce false positives and follow-up payloads.
- Hash algorithm defaults to `xxHash64`; switching requires updating both peers and the `fingerprintEncoder`.
- Rateless sketch size grows until `findAllDifferences` succeeds. Producers typically emit symbols in small batches (e.g. 32 at a time) to keep the Flow responsive while matching Rust’s semantics.

The resulting design is a faithful translation of the Rust `BloomRibltHashes` strategy, adapted to Kotlin and Synk’s data model while staying simple and reliable.
