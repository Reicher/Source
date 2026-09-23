# Silver V1 data and processing model

Silver is Source's derived, revisable knowledge layer. Bronze remains the
canonical original material. Gold remains a later, rebuildable projection for a
specific use.

This document is normative for V1 semantics. The current implementation uses a
small atomic state file, but the behavioral contract does not depend on that
storage choice.

## Boundary

```text
Bronze
  → media/text detection
  → deterministic parser or generic text fallback
  → Evidence + extraction Observations
  → semantic Observations
  → optional Entity/Claim resolution
  → atomic authoritative publication on Source
  → complete mirror on Self
```

Parsing and decoded structure are part of Silver production. Syntax should use
deterministic code when practical so model work can be reserved for meaning,
ambiguity, and relationships. A recognized parser is an optimization: invalid
or unrecognized text still takes the generic text path.

V1 recognizes JSON values, CSV rows, and Markdown headings/blocks. It also
accepts unknown UTF-8 text. These parsers are deliberately small and are not a
catalogue Source must complete before understanding other text.

## Authority

The Silver authority invariant is:

```text
one profile → Clients → one authoritative Source
```

The current V1 product and pairing surface instantiate one Self. Silver state
is still owned by Source for the profile, not by that Self connection, so adding
another Client later cannot create a competing authoritative history.

- Self/Clients originate Bronze and synchronize it to Source.
- Source is the only producer and authority for persistent Silver.
- Self atomically mirrors Source's complete published Silver snapshot and keeps
  the last snapshot while offline.
- Self disconnection never pauses, starts, resets, or owns Source processing.
- Local/transient Self analysis must not enter the authoritative namespace.

## Records

### Producer

Every Observation and Claim identifies its producer:

```text
processorId
processorVersion
modelId?          when a model was used
modelRevision?    when available
```

The initial processors are deterministic and therefore omit model fields.

### Evidence

Evidence is an immutable reference to exact Bronze material, not a replacement
copy:

```text
id
bronzeSourceId
bronzeContentSha256
selector?       open structured locator
excerpt?        short inspection copy only
```

V1 selectors include UTF-8 byte ranges, JSON pointers, and table rows. Future
selectors may identify pages, metadata fields, image regions, or audio ranges.
The source identifier, content hash, and selector establish provenance.

### Observation

An Observation is an immutable, open-ended processor result:

```text
id
kind             open string
payload          processor-defined JSON
evidenceIds      one or more Evidence records
confidence?      0..1 when available
producer
```

Current examples are `text-block`, `markdown-heading`, `parsed-table-row`,
`parsed-json-value`, `semantic-statement`, and `entity-mention`. Consumers must
ignore unknown kinds rather than reject the dataset. An Observation can remain
unresolved indefinitely and does not require an Entity or Claim.

### Entity and Claim

An Entity is an opaque Source-local identity. Labels and other properties are
Claims, not identity fields.

```text
Entity
  id              random opaque UUID

Claim
  id
  subjectEntityId
  predicate        open string
  value XOR objectEntityId
  supportingObservationIds
  producer
  state            active | superseded | retracted
```

Types and predicates are not global enums. V1's optional resolver is deliberately
conservative: generic two-or-more-word capitalized mentions become candidates;
an exact normalized label reuses one unambiguous Entity, otherwise a new Entity
is created. It emits an active `name` Claim supported by the mention Observation.
This is a small demonstration of optional structuring, not a general entity
ontology or a content-specific extractor.

The same exact label in multiple Bronze sources can resolve to the same Entity.
Each source retains its own Claim and provenance, allowing the Entity view to
navigate back to every supporting Bronze item.

## Identity and immutability

Evidence, Observation, and Claim identifiers are deterministic SHA-256 values
over their semantic identity plus a record-specific prefix. Creation time and
job state are excluded. Entity identifiers are random opaque UUIDs. Equal input
under the same processor revision therefore converges on the same derived record
IDs without permitting Self to originate them.

Bronze content and metadata are immutable after creation. It can be imported or
created and later deleted, but never edited in place. Evidence and Observations
are likewise immutable. A later processor generation publishes new records and
retains the previous completed generation in Source history; it does not mutate
the prior records in place.

## Durable job lifecycle

Source stores jobs with these states:

```text
queued → running → completed
                 ↘ failed
queued/running/failed → cancelled when its input identity is invalidated or Bronze is deleted
```

Acceptance records the Bronze identifier, immutable content hash, title, and MIME
type. The format-relevant metadata participates in job identity because it can
select a parser. The worker reads the content-addressed Bronze blob with bounded
inspection and rejects known binary formats before loading their content. Jobs
are deduplicated by that complete input identity and processor revision. A
failed job can be queued again, and running/failed work is recovered as queued
on restart. Periodic manifest reconciliation repairs work that was missed by an
immediate enqueue failure without requiring a restart.

Text is divided into deterministic parser fragments. Each completed fragment is
stored as a checkpoint containing staged Evidence, Observations, and resolution
candidates. Checkpoints are reused only when batch index and content hash still
match. They are private working state and never appear in the authoritative
snapshot.

Before publication Source rechecks that the Bronze source still has the job's
content hash. Publication writes the complete dataset and completed job state in
one atomic state replacement. Consequently a restart may reveal either the old
complete generation or the new complete generation, never a partially published
one. A dataset whose recorded input identity no longer matches its Bronze
metadata is not presented as current. When only the processor revision is old,
the last complete dataset remains visible with a stale marker until its atomic
replacement is ready.

Deletion cancels working jobs and removes current and historical derived records
for that Bronze source. Entity registry entries no longer supported by retained
datasets are pruned.

## Synchronization and inspection

`GET /v1/silver` is authenticated by the paired Self certificate and returns one
complete snapshot containing current Sources, Evidence, Observations, Entities,
Claims, and separate processing progress. Processing metadata is not
authoritative Silver content.

Self stores the snapshot atomically. Its Bronze detail links to the Silver source
record and remains useful with the cached snapshot offline. Source-specific
inspection filters records by that Bronze source. Entity inspection aggregates
Claims globally and follows Claim → Observation → Evidence → Bronze for reverse
navigation.

## Reprocessing

An identical Bronze input identity already completed by the same processor
revision is not needlessly queued. Changing the processor revision permits a new
generation. Completed prior generations remain Source history; incomplete
checkpoints do not. Better parsers or future models can therefore improve Silver
without changing Bronze or silently overwriting the previous interpretation.
