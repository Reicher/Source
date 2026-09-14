# Silver v1 data model

Silver is Source's derived, revisable knowledge layer. Bronze remains the
canonical source material; Silver records what processors observed and what
Source currently believes about those observations.

This document is the normative model for Silver v1. The existing Android
`SilverResult` format is a prototype and will migrate toward this model through
the Silver implementation issues. This document defines data semantics, not a
storage schema or API wire format.

## Boundary

The Silver flow is:

```text
Bronze reference -> Evidence -> Observation -> Entity + Claim -> Gold views
```

Silver owns evidence references, processor observations, stable entity
identity, and evidence-backed claims. It does not own job scheduling, batching,
checkpoints, Client/Node placement, synchronization transport, or user
interface behavior.

Gold is a rebuildable projection. It normally shows current Silver knowledge;
superseded and retracted interpretations remain available for explanation and
debugging but are not shown by default.

## Shared rules

- Bronze is never changed or deleted as a side effect of Silver processing.
- Entity identifiers are opaque Source-local UUIDs. They are never derived from
  a name, label, type, property, or external identifier.
- Evidence, observation, and claim identifiers are deterministic SHA-256
  identities over their canonical semantic content and provenance, prefixed by
  the record type and model version. Creation time and claim lifecycle state are
  excluded. Repeating identical work therefore produces the same records
  instead of duplicates.
- Wall-clock timestamps are descriptive metadata, never the sole basis for
  conflict resolution or identity.
- Confidence is optional and, when present, is a finite number from `0` to `1`.
  Missing confidence means unknown, not `1`.
- Processor identifiers, observation kinds, predicates, and entity types are
  open strings. Silver has no global domain-type or predicate enum.
- Silver v1 scalar values are text, finite number, and boolean. More specialized
  values can be added later without changing entity identity.

The common producer value is:

```text
Producer
  processorId       stable implementation identifier
  processorVersion  exact implementation or schema version
  modelId?           model family/artifact identifier, when AI was used
  modelRevision?     exact model revision, when available
```

Model size or parameter count is diagnostic metadata. It does not determine
which interpretation wins.

## Evidence

Evidence identifies the exact Bronze material behind an observation. It is a
small reference, not a copy of the original.

```text
Evidence
  id                   deterministic evidence identifier
  bronzeSourceId       stable Bronze item identifier
  bronzeContentSha256  exact Bronze revision/content identity
  selector?            source-specific fragment locator
  excerpt?             short display copy of the selected material
```

`selector` has an open `kind` plus kind-specific values. Examples include a
UTF-8 text range, document page, table row, image region, or metadata field.
The selector and content hash are authoritative. `excerpt` is optional and only
helps inspection; it must not replace the Bronze source.

Evidence records are immutable. A changed Bronze item produces evidence that
references the new content hash while old evidence remains traceable to the old
revision.

## Observation

An observation is an immutable local result from one processor. It records what
the processor found without requiring Source to resolve global entity identity
or accept the result as current truth.

```text
Observation
  id             deterministic observation identifier
  kind           open processor-defined kind
  payload        canonical structured value
  evidenceIds    one or more Evidence identifiers
  confidence?    processor confidence
  producer       Producer
  createdAtMillis
```

Examples of observation kinds are `detected-text`, `contact-row`,
`location-metadata`, `entity-mention`, and `relationship-candidate`. A processor
must document the shape of its payload. Consumers ignore kinds they do not
understand rather than rejecting the Silver dataset.

The observation identifier includes its kind, canonical payload, evidence
identifiers, and producer identity. A new Bronze revision, processor version,
model revision, or result therefore creates a new observation. An identical
rerun does not.

An observation may remain unresolved indefinitely. Uncertainty is preferable
to forcing a false entity match.

## Entity

An entity is one stable Source-local identity for something Source currently
treats as one thing.

```text
Entity
  id                    random opaque UUID
  originObservationIds  observations that caused the entity to be created
  createdBy             resolving Producer
  createdAtMillis
```

An entity's name, type, external identifiers, and other properties are claims,
not identity fields. A Gold projection may cache a preferred display label and
type, but changing either does not create a new entity.

Entity IDs are never reused. Future merge and split operations may relate or
supersede entities while retaining the old IDs and their provenance; Silver v1
does not define the merge/split algorithm.

## Claim

A claim is one evidence-backed assertion that Source can use when building its
current knowledge view.

```text
Claim
  id                       deterministic claim identifier
  subjectEntityId          Entity identifier
  predicate                open predicate string
  objectEntityId XOR value exactly one entity reference or scalar value
  supportingObservationIds one or more Observation identifiers
  confidence?              claim-level confidence
  producer                 Producer that created/resolved the claim
  state                    active | superseded | retracted
  createdAtMillis
```

Observation confidence and claim confidence are distinct. Resolution may
strengthen or weaken a claim, and must not silently copy a processor score as a
global truth score.

Multiple active claims may have the same subject and predicate. This is how
Silver represents competing sources or unresolved contradictions. Selection
and presentation belong to Gold or a later resolution policy.

Claim content is not overwritten. Reprocessing creates new claims and may mark
older claims as `superseded`; a rejected interpretation becomes `retracted`.
Previous interpretations and their supporting observations/evidence are
retained for now. Ordinary Gold views consume active claims only.
Lifecycle state is metadata on the stable claim identity; changing it does not
create a different assertion or discard the previous interpretation.

## Reprocessing and history

The following rules make later reprocessing safe without defining job
execution in Silver:

1. The same Bronze revision and producer version produce the same evidence,
   observation, and claim identities when their canonical content is equal.
2. New Bronze content or a new processor/model version produces new immutable
   observations and claims.
3. New records do not delete old records. Old claims may change lifecycle state,
   but their assertion, evidence, producer, and creation metadata remain
   available.
4. Current knowledge is a projection over active claims, not the latest record
   chosen by wall-clock time or model size.
5. Retention or compaction may be added later. Until then, Source retains old
   interpretations.

The exact activation, replacement, and stale-generation policy belongs to the
Silver reprocessing issue. These fields ensure that policy can be implemented
without changing the four core concepts.

## End-to-end examples

### Contact row

Bronze contains a CSV row with `Ada Lovelace, ada@example.test`.

1. Evidence references the CSV content hash and row selector.
2. A `contact-row` observation stores the parsed name and email, its extraction
   confidence, and the CSV processor version.
3. Resolution creates entity UUID `person-1` from that observation.
4. Active claims state `person-1 -- name -> "Ada Lovelace"`,
   `person-1 -- entity-type -> "person"`, and
   `person-1 -- email -> "ada@example.test"`.
5. Every claim references the contact-row observation, which leads back to the
   exact Bronze row.

Changing Ada's name later creates a new name claim and may supersede the old
claim; `person-1` remains the same entity.

### Relationship in a note

Bronze contains the sentence `Robin started Source in 2025.`

1. Evidence references the note revision and exact text range.
2. A relationship-candidate observation records the two mentions, predicate,
   and date value without assigning global identity.
3. Resolution links the mentions to stable person and project entities.
4. Claims state `Robin -- started -> Source` and
   `Source -- started-in -> "2025"`, supported by the observation.

If a later processor interprets the sentence differently, both derivations
remain inspectable and the old claims can be superseded without touching the
note.

### Unresolved and competing knowledge

One note says `Alex lives in Stockholm`; another says `Alex lives in
Gothenburg`.

- If identity is uncertain, the mention observations remain unresolved and no
  entity match is forced.
- If both mentions confidently resolve to the same entity, Silver may hold two
  active `lives-in` claims with separate evidence.
- Gold may show the conflict or choose a view-specific interpretation. Silver
  retains both claims and their provenance.

## Implementation consequences

The prototype Android model differs from this contract in three important ways:

- `entityId(type, name)` must be replaced by opaque stable entity identity;
- one replaceable `SilverResult` per Bronze source must evolve into retained
  evidence, observations, entities, and claims;
- inline `evidenceExcerpt` must become a reference to exact first-class
  Evidence, while an excerpt may remain as display metadata.

Those migrations belong to the implementation issues following this model
definition. Existing Bronze data remains canonical throughout the migration.
