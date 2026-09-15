# Canonical storage and synchronization model

This document is the normative Source-level contract for storage, backup,
synchronization, conflict handling, and deletion. It defines the model that
Clients and the authoritative Node must share. API shapes, database tables,
background scheduling, and migration of the current snapshot implementation
are separate implementation work.

The current foundation is:

```text
one profile -> multiple Clients -> one authoritative Node
```

The Node host and its administrator are currently trusted. The contract may
therefore allow the Node to read Bronze and derived data when storage,
refinement, search, or recovery requires it. Transport authentication, profile
isolation, encrypted storage where practical, and protection from unintended
network access remain required.

The words MUST, MUST NOT, SHOULD, and MAY describe requirements on the future
canonical implementation. The encrypted snapshot and Library endpoints that
exist today are compatibility paths and do not redefine this contract.

## Core distinctions

Source uses these terms consistently:

- **Origin** identifies the component allowed to create a class of data.
- **Authority** decides the accepted profile history, current heads, and
  tombstones. Authority is about logical state, not which disk has a byte copy.
- **Durable on Node** means that the Node acknowledged the exact revision and
  payload after committing both. It is the product's Client-to-Node backup
  boundary, but it is not proof of a separate off-machine operational backup.
- **Synchronized** means a Client has applied every accepted change through a
  specific Node cursor and has no unacknowledged local mutation in the relevant
  scope. It does not mean that every payload is present locally.
- **Cache** is a disposable local copy that can be reconstructed from an
  authority. A cache never becomes authoritative because it is newer or is the
  only reachable copy.
- **Local eviction** removes payload bytes from one Client while retaining the
  identity and availability metadata needed to fetch them again. It is not a
  deletion.
- **Global deletion** is an authoritative tombstone. Removing a local file,
  cache entry, legacy snapshot, or Client registration is not a substitute for
  that tombstone.

## Authority by data class

| Data class | Origin | Accepted authority | Client copy | Node copy |
| --- | --- | --- | --- | --- |
| Pending Bronze revision | One Client | Originating Client until acknowledged | Durable local source; MUST NOT be evicted | Not yet present |
| Accepted Bronze history | Clients | Node revision graph and tombstones | Local source, synchronized replica, or evicted stub | Durable payload and authoritative profile catalog |
| Persistent Silver | Node only | Node | Synchronized offline cache | Authoritative persistent state |
| Gold projection | Client or Node | None; rebuildable from Silver | Disposable view/cache | Optional disposable view/cache |
| Client-only working state | That Client | That Client | Local only | Not synchronized unless a separate data class says so |
| Operational Node backup | Node operator | Restore procedure | None | Separate archive of Node state |

Bronze remains canonical source material in the knowledge architecture. For a
revision that the Node has accepted, no byte copy is a different truth: every
valid copy is checked against the same content identity. The Node is
authoritative for whether that revision belongs to the profile history, which
heads are current, and whether the object is deleted.

A Client may create Bronze while offline. That local revision is pending and
is the only authoritative copy of itself until the Node acknowledges it. It is
not visible to other Clients, is not backed up, and cannot be used as evidence
for persistent Silver before acceptance.

Only the Node may originate persistent Silver. Clients may perform transient
local analysis, but those results MUST use Client-only working state and MUST
NOT enter the persistent Silver namespace or synchronize as competing Silver.
The Silver record identities and lifecycle rules remain defined by
[`SILVER.md`](SILVER.md).

## Canonical object and revision identity

Every synchronized item belongs to an object key:

```text
ObjectKey
  profileId       stable profile UUID
  collection      lowercase Source collection identifier
  objectId         stable identifier within that profile and collection
```

`profileId`, `collection`, and `objectId` together are the complete object
identity. An identifier MUST NOT be compared or deduplicated across profiles.
Profile and UUID object identifiers use lowercase hyphenated UUID text;
collections use lowercase ASCII identifiers matching
`^[a-z][a-z0-9-]{1,63}$`; and hash identifiers use 64 lowercase hexadecimal
characters. Bronze object identifiers are normally random UUIDs created once
by the originating Client. Silver Evidence, Observation, and Claim object
identifiers are their deterministic Silver IDs; Silver Entity object
identifiers are their Node-created UUIDs. Reusing an object identifier for
unrelated content is an error, including after deletion.

An object has immutable revisions. Updating an object creates a revision; it
does not overwrite an earlier revision:

```text
Revision
  revisionId          deterministic lowercase SHA-256 identifier
  objectKey           identity above
  kind                content | tombstone
  parentRevisionIds   sorted unique causal parents
  payload?            format, formatVersion, byteCount, plaintextSha256
  createdAt?          descriptive producer timestamp
```

`createdAt` is useful for display and diagnostics but is not part of identity,
causality, ordering, or conflict resolution. Payload hashes describe the exact
plaintext or canonical logical bytes. Transport ciphertext and compressed
representations may have separate integrity hashes and MUST decode to those
same logical bytes.

The `revisionId` is computed as:

```text
SHA-256(UTF8("source-storage-revision") || 0x00 || JCS(revisionIdentity))
```

where `revisionIdentity` contains exactly:

```json
{
  "collection": "source collection",
  "kind": "content",
  "objectId": "stable object identifier",
  "parents": ["sorted unique revision IDs"],
  "payload": {
    "byteCount": 123,
    "format": "versioned payload format",
    "formatVersion": 1,
    "plaintextSha256": "lowercase SHA-256"
  },
  "profileId": "profile UUID"
}
```

For a tombstone, `kind` is `tombstone` and `payload` is JSON `null`. Property
names and string values are normalized to Unicode NFC before RFC 8785 JSON
Canonicalization Scheme serialization. Invalid Unicode, duplicate properties
after normalization, non-finite numbers, unsorted or duplicate parent IDs, and
incorrect identifier forms are rejected. The hashing rules intentionally
match the normalization and canonicalization discipline used by Silver.

A content revision with identical object identity, parents, format, version,
and payload has the same identity regardless of retry, arrival time, transport
encoding, or producing Client. Metadata that must change logical meaning MUST
be inside the versioned payload. Observational fields such as receipt time,
Client display name, and transport size MUST remain outside revision identity.

## Mutation and commit metadata

Each producer keeps a durable outgoing mutation journal. A mutation contains:

```text
Mutation
  operationId       random UUID, stable across every retry of this operation
  originId          paired Client ID, or Node ID for Node-originated Silver
  originSequence    monotonic counter in that origin's durable journal
  revision          complete revision descriptor
  payload?          inline bytes or a reference to an uploaded verified blob
```

`operationId` is the idempotency key. `originSequence` detects missing or
reordered journal entries; neither field decides which concurrent content
wins. A Client MUST persist the journal entry before presenting an offline
write as saved. An origin sequence is unique and strictly increasing for that
origin ID; resetting the counter requires a new origin ID. The Node rejects a
sequence position that is reused for a different operation.

After validating the authority, identity, causal parents, and payload hash, the
Node commits the payload, revision metadata, and profile change-log entry as
one durability unit. It returns a receipt containing:

```text
CommitReceipt
  operationId
  revisionId
  authorityNodeId
  authorityEpoch
  commitSequence
```

`commitSequence` is a strictly increasing integer within one profile and
`authorityEpoch`. It orders change delivery and defines synchronization
cursors; it does not make a later concurrent revision semantically newer. The
Node MUST NOT acknowledge a revision before all data named by the receipt is
durable under its storage contract.

An `authorityEpoch` is created when the profile authority is established. It
is preserved by a continuity-safe restore. A restore that rolls the Node back,
or an explicit future authority migration that cannot preserve the log, MUST
create a new epoch and force reconciliation. A Node MUST never reuse a lower
`commitSequence` in the same epoch.

## Idempotency and duplicates

All writes and deletion requests MUST be safe to retry after a timeout,
disconnect, process crash, or lost response.

- Repeating an `operationId` with the same canonical mutation returns the
  original outcome and MUST NOT append another change.
- Reusing an `operationId` for a different mutation is rejected as an
  idempotency conflict.
- Receiving an existing `revisionId` with the same descriptor and payload is a
  successful no-op. A hash or descriptor mismatch is corruption and is
  rejected.
- Blob storage MAY deduplicate identical payload hashes inside one profile,
  but that optimization MUST NOT merge object identities, permissions,
  metadata, retention, or tombstones.
- Importing identical bytes twice is a collection-level product decision. A
  collection may return the existing object, create two objects that share a
  blob, or reject the duplicate, but it MUST make that behavior explicit.
- Node-created Silver retains the deterministic identity rules in
  `SILVER.md`; reprocessing identical inputs therefore converges without
  duplicate persistent records.

The Node retains each operation ID and its canonical mutation digest at least
until the corresponding change is below the retained log floor and every
active Client has acknowledged beyond it. Revision and tombstone identities
continue to prevent duplicate state after operation metadata is compacted.

## Causality, conflicts, and multiple Clients

`parentRevisionIds` define causality. Node arrival order and wall-clock time do
not.

- A revision whose parents include every current head causally replaces those
  heads and becomes the new head.
- A revision based on an older subset of heads is concurrent. The Node accepts
  it when otherwise valid and retains all concurrent heads.
- An exact duplicate collapses by `revisionId`.
- A collection MAY define a deterministic, associative, commutative, and
  idempotent merge. A generated merge revision names every merged head as a
  parent.
- If no safe collection merge exists, Source exposes a conflict. It MUST NOT
  silently choose a winner by timestamp, upload order, Client ID, hash order,
  or whichever Client is currently online.
- User resolution creates a new revision whose parents include every resolved
  head. Losing branches remain history until the retention policy permits
  compaction.

The Node serializes commits to allocate change-log positions, but that
serialization is not a last-writer-wins policy. Clock skew can only affect
displayed times.

Bronze content-addressed by immutable source semantics, such as one imported
file, will normally have no update revisions. Editable Bronze, such as a note,
uses the full revision graph. Silver has a single Node writer, while its domain
model may still retain competing claims rather than manufacturing a storage
conflict.

## Synchronization protocol behavior

A synchronization cursor is `(authorityNodeId, authorityEpoch,
commitSequence)`. It means the Client has atomically applied all visible
changes through that sequence. A cursor is not valid for a different profile,
Node authority, or epoch.

Each reconnect follows this logical sequence; transports may pipeline steps
without changing the guarantees:

1. Authenticate the paired Client and verify the profile's specific
   authoritative Node identity. Discovery is only a routing hint.
2. Compare the authority epoch. If it changed or the cursor is below the
   retained change-log floor, start a full manifest reconciliation.
3. Replay the Client's pending Bronze mutations in durable journal order. The
   Node validates causal bases and returns the original or new commit receipt,
   an explicit conflict, or a permanent rejection.
4. Read the Node change log after the Client cursor. Entries include accepted
   Bronze revisions, tombstones, authoritative Silver changes, and cache
   invalidations.
5. Verify descriptors and hashes, fetch payloads required by local policy, and
   atomically publish the resulting local state. Advancing the local cursor
   before publication is forbidden.
6. Acknowledge the applied cursor. The Node may use active-Client
   acknowledgements for retention but MUST remain correct when an acknowledgement
   is lost.
7. Remove acknowledged operations from the outgoing journal only after their
   receipts and any resulting conflicts are durably recorded locally.

An interrupted session resumes from the durable journal and cursor. Pagination,
chunking, and blob transfer are resumable implementation details. Applying the
same change page more than once produces the same local state.

A Client is fully synchronized for a scope only when its cursor is current,
its relevant payload policy is satisfied, and it has no pending or conflicted
mutation in that scope. User interfaces SHOULD distinguish at least `local
only`, `pending`, `durable on Node`, `conflicted`, `cached`, and `evicted`.

## Backup, caching, and eviction

A Client may call an accepted Bronze revision **backed up to Node** only when it
holds a matching commit receipt and has learned of no later deletion or
durability failure for that revision. Merely uploading bytes, receiving an HTTP
success for a legacy snapshot, or seeing the same content hash elsewhere is
insufficient in the canonical model.

Node durability and an off-machine operational backup are distinct:

- The authoritative Node MUST retain accepted current Bronze payloads,
  tombstones or their compacted equivalent, authoritative Silver, the change
  log needed by supported cursors, and the metadata required to validate them.
- Node backup MUST capture profile metadata, object payloads, the authority
  epoch, commit sequence, tombstones, cryptographic state, and Silver as one
  consistent restore unit.
- Clients and their Silver caches are not backups of authoritative Node state.
  Bronze still present on Clients can assist explicit recovery, but it cannot
  silently replace the Node's catalog or Silver history.

A Client may locally evict Bronze payload bytes only after verifying a durable
Node receipt for that exact revision and retaining an object stub with its
identity, hash, size, availability, and tombstone state. Pending or rejected
revisions MUST NOT be evicted. Eviction MUST NOT emit a tombstone or delete the
Node payload. Opening an evicted object fetches and verifies the named revision;
offline access is unavailable until that succeeds.

Silver on a Client is always a cache and may be evicted according to local
policy. The Client retains enough cursor or manifest state to distinguish
`not cached` from `deleted`. Gold may always be discarded and rebuilt.

## Deletion and tombstones

Source exposes deletion scope explicitly.

### Local eviction or removal

`Remove from this Client` deletes local payload or cache bytes only. For
accepted Bronze it leaves a fetchable stub and the Node object unchanged. For
Client-only working state it may delete the only copy after an appropriate user
confirmation. Removing a local profile deletes its local keys and data and may
revoke that Client, but MUST NOT imply deletion of the profile from the Node or
other Clients.

### Global object deletion

`Delete everywhere` is a mutation submitted to the authoritative Node. Its
tombstone revision has the current head set as parents. The Node accepts it only
when the request accounts for every current head; otherwise it returns the
concurrent heads for explicit reconciliation. This prevents an offline delete
from silently erasing an unseen edit.

Once accepted, a tombstone is terminal for that object identifier:

- new content revisions for the object are rejected, including delayed writes
  from stale Clients;
- every Client removes payload and cache bytes when it applies the tombstone;
- retrying the delete returns the same result;
- undo or re-import creates a new object identifier unless a future explicit
  restore operation defines otherwise; and
- the Node removes the Bronze payload when retention and recovery policy allow,
  while retaining enough deletion identity to prevent resurrection.

A Bronze tombstone invalidates its Evidence and all dependent Silver according
to `SILVER.md`. The Node MUST prevent derived knowledge supported only by the
deleted source from appearing in current Gold views while cleanup is pending.
Silver lifecycle changes and cache invalidations are Node-originated changes;
a Client cannot delete authoritative Silver by dropping its cache.

Tombstones may be compacted only after every active registered Client has
acknowledged them and the configured recovery retention has elapsed. Compaction
MUST retain an ID graveyard or equivalent deletion summary. A Client older than
the retained log floor performs a full reconciliation and cannot reintroduce a
deleted ID from stale local state. Revoked Clients do not block compaction and
must re-pair before synchronizing.

Deleting an entire profile on the Node is a separate administrative operation.
It revokes access and destroys Node-held profile data according to documented
retention, but Source cannot promise remote erasure from a disconnected device.
That device must delete its local profile separately. Operational backups may
retain deleted ciphertext or data until their external retention period ends.

## Offline, reconnect, and recovery

While the Node is unavailable, a Client:

- reads local Bronze and cached Silver;
- may create or edit Bronze and records those mutations durably as pending;
- may request a global deletion, but presents it as pending until the Node
  accepts its causal basis;
- may run transient local analysis outside persistent Silver;
- does not claim pending data is backed up or globally synchronized; and
- does not evict the only payload for a pending Bronze revision.

On reconnect, pending work is replayed and remote changes are applied using the
protocol above. Concurrent edits remain conflicts even if one device's clock is
later. New Node Silver is fetched only after the related Bronze acceptance and
Node refinement commits exist.

Recovery follows these rules:

- A lost or reinstalled Client restores the Node's accepted Bronze catalog and
  relevant authoritative Silver after recovery pairing. Unacknowledged local
  work that existed only on the lost Client cannot be recovered from the Node.
- A corrupt Client payload or cache is discarded only after its identity is
  retained; an accepted revision is fetched again and hash-verified.
- A Node process crash before a receipt may leave either no commit or a complete
  commit. Retrying the same operation discovers which outcome occurred without
  duplication.
- A continuity-safe Node restore preserves identity, authority epoch, log, and
  monotonically increasing sequence. A rollback restore changes the epoch and
  triggers full reconciliation before new writes are accepted.
- If the authoritative Node and its backups are lost, Clients may contribute
  their accepted and pending Bronze through an explicit future authority
  recovery or migration flow. Silver is rebuilt by the new authority; cached
  Silver is not silently promoted. Automatic failover is out of scope.

## Compatibility and migration boundary

The current prototype predates this model:

- generic datasets synchronize whole encrypted snapshots;
- snapshot selection can use `modifiedAtMillis`;
- random upload IDs and snapshot creation time act as history metadata;
- Library has a feature-specific item path and tombstones; and
- Android currently persists transitional Client-produced Silver.

Those behaviors remain supported until migration work replaces them, but they
are not canonical semantics. In particular, `modifiedAtMillis` MUST NOT be
carried forward as a conflict winner, a snapshot upload MUST NOT be treated as
a canonical commit receipt, and Client-produced Silver MUST NOT be admitted to
the authoritative Silver history.

The implementation issue following this contract is responsible for versioned
wire schemas, durable journals and cursors, Node storage tables, incremental
migration, and compatibility reads. Migration must preserve existing Bronze,
map existing Library tombstones without resurrection, and either rebuild or
explicitly invalidate transitional Silver. This document does not require a
big-bang migration or add Node-to-Node synchronization.

## Required conformance scenarios

Implementations of this contract MUST cover at least:

1. retrying the same write before and after a lost response;
2. rejecting reuse of an operation ID with different content;
3. two Clients editing the same base revision with arbitrarily skewed clocks;
4. deterministic merge or explicit preservation of concurrent heads;
5. an offline delete racing with a remote edit;
6. a stale Client reconnecting after a tombstone was compacted;
7. interruption during upload, change application, and cursor persistence;
8. eviction only after the exact Bronze revision is durable on Node;
9. rehydration and hash verification of evicted Bronze and cached Silver;
10. repeated Node Silver delivery without duplicate records;
11. Client recovery with accepted data but without lost pending data; and
12. continuity-safe restore versus rollback restore with a new authority epoch.

These scenarios are contract tests for the implementation, not permission to
encode transport- or database-specific details into the domain model.
