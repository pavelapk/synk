# Conflict Sync

Conflict Sync reconciles two Synk replicas that may have diverged while operating offline. The protocol couples Synk's per-field HLC metadata with probabilistic diffing so that the peers exchange only the data that is strictly necessary. The sequence is deliberately linear—no speculative branches, no hidden state—so implementations remain “crowbar simple” while still scaling to large datasets and unreliable networks.

The high-level flow repeats three passes until both replicas converge:

1. Stream object summaries to learn which records exist on each side.
2. Ship complete snapshots for objects that are missing on one replica.
3. For the remaining objects, reconcile only the fields that actually changed.

Each pass runs in order. If either peer drops off the network, the current sync is aborted and both replicas start over from stage 1 on the next attempt.

## Goals

- **Simplicity and clarity.** Every payload corresponds to a Kotlin data structure and the control flow is a straight, well-documented pipeline.
- **Scalability.** Summaries and field catalogs are streamed in bounded chunks; large datasets never require a full in-memory materialisation.
- **Resilience to weak networks.** Bloom filters and rateless set reconciliation avoid fixed numbers of roundtrips, so retries after a disconnect reprocess only lightweight summaries before reaching the deltas again.

## Terms and Data Model

### Keys

- `ObjectKey(namespace: String, id: String)` uniquely identifies a CRDT record.
- `FieldKey(object: ObjectKey, field: String)` identifies an individual property within that record.

### Field versions

- `FieldDigest = xxHash64(namespace || id || field || valueDigest || hlc)`. The `valueDigest` is the Synk adapter's canonical hash of the encoded value.
- `FieldVersion(field: String, hlc: Hlc, digest: Long)` captures the latest known version for one field.
- `ObjectSummary(key: ObjectKey, objectDigest: Long, fieldCount: Int)` is a compact descriptor for an object. `objectDigest` is derived from the sorted list of `FieldDigest`s.

We refer to the ordered list of `ObjectSummary`s as the **inventory**. It is the authoritative record of which objects a replica currently knows about.

### Payloads

- `SnapshotPayload(key: ObjectKey, encodedObject: ByteArray, fieldVersions: List<FieldVersion>)` transports a full object together with the causal metadata for each field. The `fieldVersions` list is not redundant: the encoded object carries only domain values, while the array provides the sender's HLCs and digests so the receiver can seed its MetaStore precisely for the next merge.
- `BlockPayload(fieldKey: FieldKey, encodedValue: ByteArray, hlc: Hlc)` transports one field for incremental updates.
- `SummaryBloom` and `FieldBloom` are Bloom filters constructed over object digests and field digests respectively. They are sent ahead of heavier payloads to filter obvious matches.
- `RatelessSymbol` is the unit exchanged by the rateless Invertible Bloom Lookup Table (IBLT) duet. Given enough symbols, the peers recover the set difference without agreeing on a fixed number of rounds.

## Components

### StateSource

`StateSource<T>` is the read-side gateway into application data:

1. `scan(after: ObjectKey?, limit: Int)` streams objects in deterministic `(namespace, id)` order without loading the entire dataset.
2. `byId(id: String)` retrieves the current version of a record when the protocol is about to send a snapshot or confirm an inbound merge.
3. Implementations may optionally expose estimation metadata (counts, approximate byte sizes) to guide chunk sizes.

### InventoryBuilder

For each object emitted by `StateSource.scan`, the builder:

1. Serializes the object with the registered Synk adapter to obtain canonical field encodings.
2. Reads the per-field HLCs from the Synk MetaStore.
3. Computes `FieldVersion`s and the resulting `ObjectSummary`.
4. Caches both the summary and the field catalog in memory for the rest of the run.

Because the cache only holds the objects in flight, the builder's working set stays flat even when scanning millions of rows.

### CatalogStore

The catalog keeps the field metadata and encoded blocks derived during the inventory pass. The data lives in memory for the lifetime of the sync, so later stages can look up `FieldVersion`s and `BlockPayload`s without re-reading or re-serializing objects from the primary database.

### MergeHandler

`MergeHandler<T>` persists reconciled objects. Conflict Sync batches calls and invokes `Synk.inbound` before handing the winning value to the handler so causality is respected.

### ConflictSyncTransport

Applications supply their networking implementation through this interface. The contract has three responsibilities:

- **Summary channel.** Exchange Bloom filters and rateless symbols for object inventories.
- **Snapshot channel.** Stream missing objects.
- **Field channel.** Exchange field-level Bloom filters, rateless symbols, and block payloads.

Every endpoint is idempotent; senders include stable object keys so receivers can de-duplicate.

### SyncStats

`SyncStats` aggregates high-level counters (objects scanned, Bloom false positives, snapshots sent, rateless symbols exchanged, and total bytes per channel). These numbers drive tuning for chunk sizes, filter density, and redundancy factors.

## Protocol Walkthrough

Terminology: Replica **A** starts the session, replica **B** responds. The flow, however, is symmetric—each stage runs in both directions.

### Stage 0 – Handshake

1. A calls `openSession` with its preferred chunk size, Bloom parameters, and the latest inventory estimates.
2. B responds with its own preferences; both sides stabilise on the negotiated parameters for this run.
3. Each replica sets up its in-memory catalog cache so subsequent stages can reuse encoded data without touching the primary database again.

### Stage 1 – Summary discovery

1. A streams up to `N` `ObjectSummary`s starting after its in-memory `summaryCursor`. Before shipping them, it hashes the digests into a `SummaryBloom`.
2. B intersects the Bloom filter with its own inventory to quickly classify most objects as “already known by A”. Only the ambiguous digests proceed to rateless reconciliation.
3. Both sides seed an IBLT with the ambiguous digests and exchange `RatelessSymbol`s until the decoder yields `missingOnA` and `missingOnB`.
4. B responds with an acknowledgement containing the decoded difference and its new cursor hint. A updates its in-memory `summaryCursor` and proceeds to the next chunk.
5. Once A reaches the end of its inventory, B mirrors the process so it can learn about records that only exist on A.

Because the Bloom filter removes most matches upfront, the rateless exchange typically carries only the novel digests. The complexity stays sub-linear even as the dataset grows.

### Stage 2 – Snapshot hydration

1. For every `ObjectKey` listed in `missingOnPeer`, the sender reads the cached `SnapshotPayload` from the catalog store.
2. Snapshots are streamed in deterministic order. Receivers apply them through `Synk.inbound`, then persist the resolved object with `MergeHandler`.
3. After each acknowledged batch the sender advances its in-memory `snapshotCursor`. If the transport disconnects, the next run restarts from stage 1 and rebuilds the cursor.

Objects that were missing on one side never proceed to field-level reconciliation—they are always hydrated in full.

### Stage 3 – Field catalog exchange

1. Remaining divergent objects (those whose summaries differ on both replicas) move to the field stage.
2. For each object, A loads the cached list of `FieldVersion`s from the catalog and hashes them into a `FieldBloom`.
3. B tests its own field digests against `FieldBloom`. Most equal fields are eliminated here. The ambiguous set is passed to a per-object IBLT exchange to reveal precise differences. The process is symmetric.
4. Once decoding finishes, both sides know which fields are stale locally and which ones the peer expects.

### Stage 4 – Block transfer

1. For every field the peer lacks—or where the peer advertises a lower HLC—the sender fetches the corresponding `BlockPayload` from the catalog store.
2. Blocks are streamed in ascending `(namespace, id, field)` order. Receivers call `Synk.inbound` for each block and stage the object until every expected field arrives.
3. After emitting all blocks for an object, the sender updates its in-memory `fieldCursor` so the next chunk can continue without re-sending earlier entries.
4. When both replicas recompute matching `ObjectSummary`s for the object, it leaves the divergent set.

### Stage 5 – Completion

1. When no missing objects and no divergent fields remain, both replicas exchange a `SessionComplete` message containing their final `SyncStats`.
2. Each side drops the in-memory catalogs to reclaim storage.

## Meeting the goals

- **Simple control flow.** The algorithm is a linear pipeline with explicit checkpoints. Every transformation remains visible through the in-memory catalog and transport messages.
- **Scalable diffing.** Bloom filters remove the bulk of matches before rateless decoding kicks in, so bandwidth consumption is proportional to the actual differences, not to the size of the dataset.
- **Network resilience.** Chunks stay small and idempotent. If the connection drops, the next sync restarts cleanly and quickly rebuilds the lightweight summaries before progressing to the remaining deltas.

## Example wiring

```kotlin
val customerSource = object : StateSource<Customer> {
    override suspend fun scan(after: ObjectKey?, limit: Int): List<Customer> =
        customerDao.fetchPage(after = after, limit = limit)

    override suspend fun byId(id: String): Customer? =
        customerDao.findById(id)
}

val customerMergeHandler = MergeHandler<Customer> { namespace, customer ->
    customerDao.upsert(customer)
}

val synk = Synk.Builder(storageConfig())
    .registerSynkAdapter(CustomerAdapter())
    .registerStateSource(Customer::class, customerSource)
    .onMerged(Customer::class, customerMergeHandler)
    .build()

val stats = synk.conflictSync(
    namespace = Customer::class,
    transport = transport
)
```

The transport may be anything from HTTP with long-polling to WebSockets or BLE, provided it can stream the three channels described above.

## Thought experiments

- **Sparse divergence.** Two replicas differ in only ten out of 50 000 objects. Bloom filters immediately classify the rest as matches. Rateless decoding converges after a handful of symbols, so the bandwidth largely comprises the ten snapshots or field blocks that truly changed.
- **Hotspot during sync.** While a sync is in progress, a user edits a record on replica B. The HLC increases, so if the record was processed earlier the change will show up in the next session; if it happens mid-session, the field stage treats it like any other divergence and applies the newer block.
- **Unreliable network.** A device loses connectivity after sending half the field catalog. On the next attempt it restarts from stage 1; the lightweight summary pass quickly rebuilds context before deltas flow again.
- **Large object.** An object contains hundreds of fields. The field Bloom removes most matches. Only a few field digests flow through the rateless exchange, so the bandwidth remains manageable.

## Trade-offs and open questions

- Field catalogs copy the encoded value into the in-memory catalog. This avoids rereading from the primary database but increases temporary memory usage during the session.
- Bloom parameters need tuning. Extremely small filters increase false positives and therefore rateless work; extremely large filters cost bandwidth on their own.
- A dropped connection restarts the entire flow, so multi-namespace orchestration must decide whether to retry immediately or batch retries to reduce churn.
