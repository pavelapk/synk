# Conflict Sync

Conflict Sync reconciles two Synk replicas that may have diverged while operating offline. The protocol couples Synk's per-field HLC metadata with probabilistic diffing so that the peers exchange only the data that is strictly necessary. The sequence is deliberately linear—no speculative branches, no hidden state—so implementations remain “crowbar simple” while still scaling to large datasets and unreliable networks.

The high-level flow repeats three passes until both replicas converge:

1. Stream object summaries to learn which records exist on each side.
2. Ship complete snapshots for objects that are missing on one replica.
3. For the remaining objects, reconcile only the fields that actually changed.

Each pass is resumable. If either peer drops off the network, both can restart from the last confirmed step without throwing away previous work.

## Goals

- **Simplicity and clarity.** Every payload corresponds to a Kotlin data structure and the control flow is a straight, well-documented pipeline.
- **Scalability.** Summaries and field catalogs are streamed in bounded chunks; large datasets never require a full in-memory materialisation.
- **Resilience to weak networks.** Bloom filters and rateless set reconciliation avoid fixed numbers of roundtrips, while durable checkpoints allow the protocol to resume after disconnects.

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

### Session records

`SessionToken` captures the confirmed progress for the current run:

```kotlin
data class SessionToken(
    val sessionId: SyncSessionId,
    val summaryCursor: ObjectKey?,
    val snapshotCursor: ObjectKey?,
    val fieldCursor: FieldKey?,
    val parameters: NegotiatedParameters
)
```

Tokens are stored durably by both peers. The initiator keeps one token per remote replica (usually keyed by its logical peer id). Servers persist the same information per connected client so they can honour resumptions across reconnects.

The storage backing the token and the derived artifacts can be any reliable database — *session store*.

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
4. Writes both the summary and the field catalog into the session store for later reuse.

Because every write goes straight to durable storage, the builder's working set stays flat even when scanning millions of rows.

### CatalogStore

The catalog keeps the field metadata and encoded blocks derived during the inventory pass. Later stages look up `FieldVersion`s and `BlockPayload`s here instead of re-reading or re-serializing objects from the primary database.

### MergeHandler

`MergeHandler<T>` persists reconciled objects. Conflict Sync batches calls and invokes `Synk.inbound` before handing the winning value to the handler so causality is respected.

### ConflictSyncTransport

Applications supply their networking implementation through this interface. The contract has three responsibilities:

- **Summary channel.** Exchange Bloom filters and rateless symbols for object inventories.
- **Snapshot channel.** Stream missing objects.
- **Field channel.** Exchange field-level Bloom filters, rateless symbols, and block payloads.

Every endpoint is idempotent; senders include the `sessionId` and the relevant keys so receivers can de-duplicate.

### SessionStore

The session store persists:

- `SessionToken` rows.
- The cached `ObjectSummary`s and `FieldVersion`/`BlockPayload` entries generated locally.

Either peer can implement this as tables inside their existing application database. The only requirement is durability across application restarts.

### SyncStats

`SyncStats` aggregates high-level counters (objects scanned, Bloom false positives, snapshots sent, rateless symbols exchanged, and total bytes per channel). These numbers drive tuning for chunk sizes, filter density, and redundancy factors.

## Protocol Walkthrough

Terminology: Replica **A** starts the session, replica **B** responds. The flow, however, is symmetric—each stage runs in both directions.

### Stage 0 – Handshake and resume

1. A calls `openSession` with its preferred chunk size, Bloom parameters, and the last `SessionToken` it recorded for B.
2. B looks up its own token for A. Both sides agree on the resume cursors. If either token is absent, cursors start at `null`.
3. Each replica ensures the session store has space for new summaries and catalogs, then records the resolved parameters in a fresh token row.

### Stage 1 – Summary discovery

1. A streams up to `N` `ObjectSummary`s starting after `summaryCursor`. Before shipping them, it hashes the digests into a `SummaryBloom`.
2. B intersects the Bloom filter with its own inventory to quickly classify most objects as “already known by A”. Only the ambiguous digests proceed to rateless reconciliation.
3. Both sides seed an IBLT with the ambiguous digests and exchange `RatelessSymbol`s until the decoder yields `missingOnA` and `missingOnB`.
4. B responds with an acknowledgement containing the decoded difference and its new cursor hint. A updates `summaryCursor` in the token and proceeds to the next chunk.
5. Once A reaches the end of its inventory, B mirrors the process so it can learn about records that only exist on A.

Because the Bloom filter removes most matches upfront, the rateless exchange typically carries only the novel digests. The complexity stays sub-linear even as the dataset grows.

### Stage 2 – Snapshot hydration

1. For every `ObjectKey` listed in `missingOnPeer`, the sender reads the cached `SnapshotPayload` from the catalog store.
2. Snapshots are streamed in deterministic order. Receivers apply them through `Synk.inbound`, then persist the resolved object with `MergeHandler`.
3. After each acknowledged batch the sender bumps `snapshotCursor` in the token. Resuming a session simply restarts from the next unsent key.

Objects that were missing on one side never proceed to field-level reconciliation—they are always hydrated in full.

### Stage 3 – Field catalog exchange

1. Remaining divergent objects (those whose summaries differ on both replicas) move to the field stage.
2. For each object, A loads the cached list of `FieldVersion`s from the catalog and hashes them into a `FieldBloom`.
3. B tests its own field digests against `FieldBloom`. Most equal fields are eliminated here. The ambiguous set is passed to a per-object IBLT exchange to reveal precise differences. The process is symmetric.
4. Once decoding finishes, both sides know which fields are stale locally and which ones the peer expects.

### Stage 4 – Block transfer

1. For every field the peer lacks—or where the peer advertises a lower HLC—the sender fetches the corresponding `BlockPayload` from the catalog store.
2. Blocks are streamed in ascending `(namespace, id, field)` order. Receivers call `Synk.inbound` for each block and stage the object until every expected field arrives.
3. After emitting all blocks for an object, the sender updates `fieldCursor` in the token.
4. When both replicas recompute matching `ObjectSummary`s for the object, it leaves the divergent set.

### Stage 5 – Completion

1. When no missing objects and no divergent fields remain, both replicas exchange a `SessionComplete` message containing their final `SyncStats`.
2. Each side clears the token and prunes cached catalogs for that session to reclaim storage.

## Session token lifecycle

- **Where stored?** Each peer stores its own tokens in the session store. Mobile clients usually persist one token per remote replica. Servers keep a token per client (or per namespace) so a reconnect can resume mid-stage.
- **Who updates them?** Only the local replica writes its token after receiving acknowledgements from the peer.
- **Consistency model.** Tokens are idempotent; rewriting the same cursor twice is harmless. If a token is lost, the next run restarts from stage 1.

## Meeting the goals

- **Simple control flow.** The algorithm is a linear pipeline with explicit checkpoints. Every transformation is visible in the session token and catalog.
- **Scalable diffing.** Bloom filters remove the bulk of matches before rateless decoding kicks in, so bandwidth consumption is proportional to the actual differences, not to the size of the dataset.
- **Network resilience.** Chunks are small, idempotent, and durably checkpointed. Losing a connection only requires replaying the current chunk, not the entire sync.

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

val sessionStore = AppSessionStore(database) // your durable SessionStore implementation

val synk = Synk.Builder(storageConfig())
    .registerSynkAdapter(CustomerAdapter())
    .registerStateSource(Customer::class, customerSource)
    .onMerged(Customer::class, customerMergeHandler)
    .build()

val stats = synk.conflictSync(
    namespace = Customer::class,
    transport = transport,
    sessionStore = sessionStore
)
```

`sessionStore` exposes CRUD operations for `SessionToken`s and the catalog tables. The transport may be anything from HTTP with long-polling to WebSockets or BLE, provided it can stream the three channels described above.

## Thought experiments

- **Sparse divergence.** Two replicas differ in only ten out of 50 000 objects. Bloom filters immediately classify the rest as matches. Rateless decoding converges after a handful of symbols, so the bandwidth largely comprises the ten snapshots or field blocks that truly changed.
- **Hotspot during sync.** While a sync is in progress, a user edits a record on replica B. The HLC increases, so if the record was processed earlier the change will show up in the next session; if it happens mid-session, the field stage treats it like any other divergence and applies the newer block.
- **Unreliable network.** A device loses connectivity after sending half the field catalog. Upon reconnecting, it reads `fieldCursor` from the session store and resumes streaming from the next pending field without replaying the entire inventory.
- **Large object.** An object contains hundreds of fields. The field Bloom removes most matches. Only a few field digests flow through the rateless exchange, so the bandwidth remains manageable.

## Trade-offs and open questions

- Field catalogs copy the encoded value into the session store. This avoids rereading from the primary database but increases temporary storage usage during the session.
- Bloom parameters need tuning. Extremely small filters increase false positives and therefore rateless work; extremely large filters cost bandwidth on their own.
- Sessions are resumable, but they are still scoped to one namespace at a time. Multi-namespace orchestration remains the caller's responsibility.
