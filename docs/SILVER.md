# Silver data model

Silver is Source's derived, revisable knowledge layer. Bronze remains the
canonical source material; Silver records what processors observed and what
Source currently believes about those observations.

This document is the normative model for Silver. The existing Android
`SilverResult` format is a prototype and will migrate toward this model through
the Silver implementation issues. This document defines data semantics, not a
storage schema or API wire format.

## Boundary

The Silver flow is:

```text
Bronze reference -> Evidence -> Observation -> Resolution observation -> Entity + Claim -> Gold views
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
- Evidence, observation, and claim identifiers use the deterministic SHA-256
  procedure and exact identity fields defined below. Creation time and claim
  lifecycle state are excluded.
- Wall-clock timestamps are descriptive metadata, never the sole basis for
  conflict resolution or identity.
- Confidence is optional and, when present, is a finite number from `0` to `1`.
  Missing confidence means unknown, not `1`.
- Processor identifiers, observation kinds, predicates, and entity types are
  open strings. Silver has no global domain-type or predicate enum.
- Silver scalar values are text, finite number, and boolean. More specialized
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

## Canonical record identities

Clients and Nodes MUST use the following algorithm. A runtime's ordinary JSON
serializer is not sufficient.

1. Build the record-specific identity object defined below. Optional identity
   fields MUST be present as JSON `null`; omission is not an alternative
   encoding.
2. Recursively normalize every JSON property name and string value to Unicode
   Normalization Form C (NFC). Reject invalid Unicode and property names that
   become duplicates after normalization.
3. Require I-JSON values: objects have no duplicate names, numbers are finite
   IEEE 754 binary64 values, and integers requiring more than 53 bits of exact
   precision are represented by a processor-defined string format.
4. For the explicitly set-valued identifier fields below, remove duplicates
   and sort the lowercase ASCII identifiers in ascending byte order. All other
   JSON arrays are ordered and retain their order. If a processor payload
   defines an array as a set, that processor's versioned payload schema MUST
   sort its elements by their RFC 8785 byte representation before this step.
5. Serialize the normalized identity object with the
   [JSON Canonicalization Scheme (RFC 8785)](https://www.rfc-editor.org/rfc/rfc8785.html).
   This recursively sorts object properties, uses ECMAScript's canonical JSON
   representation for binary64 numbers, emits no insignificant whitespace, and
   produces UTF-8 bytes. NFC normalization is Source's preprocessing step; JCS
   itself does not perform Unicode normalization.
6. Compute `SHA-256(UTF8(prefix) || 0x00 || jcsBytes)` and encode the digest as
   64 lowercase hexadecimal characters. The prefixes are
   `source-silver-evidence`, `source-silver-observation`, and
   `source-silver-claim`.

All UUID fields use lowercase hyphenated UUID text. All SHA-256 fields use 64
lowercase hexadecimal characters. Inputs that do not satisfy these forms are
rejected rather than normalized silently.

The Evidence identity object has exactly these properties:

```json
{
  "bronzeContentSha256": "<lowercase SHA-256>",
  "bronzeSourceId": "<NFC Source-local identifier>",
  "selector": null
}
```

When present, `selector` replaces `null` with its normalized structured JSON.
`excerpt`, `id`, and storage metadata are excluded. The selector's array order
is significant unless its versioned selector schema explicitly defines and
sorts a set as described above.

The Observation identity object has exactly these properties:

```json
{
  "confidence": null,
  "evidenceIds": ["<sorted unique Evidence IDs>"],
  "kind": "<NFC observation kind>",
  "payload": "<normalized structured JSON value>",
  "producer": {
    "modelId": null,
    "modelRevision": null,
    "processorId": "<NFC processor identifier>",
    "processorVersion": "<NFC exact version>"
  }
}
```

An available confidence or model field replaces its `null`. `createdAtMillis`,
`id`, and storage metadata are excluded. `evidenceIds` is a set-valued field.

The Claim identity object has exactly these properties:

```json
{
  "confidence": null,
  "object": {"entityId": "<Entity UUID>"},
  "predicate": "<NFC predicate>",
  "producer": {
    "modelId": null,
    "modelRevision": null,
    "processorId": "<NFC processor identifier>",
    "processorVersion": "<NFC exact version>"
  },
  "subjectEntityId": "<Entity UUID>",
  "supportingObservationIds": ["<sorted unique Observation IDs>"]
}
```

For a scalar object, `object` is instead exactly
`{"scalar":{"type":"text|number|boolean","value":<value>}}`.
`createdAtMillis`, `id`, `state`, and storage metadata are excluded.
`supportingObservationIds` is a set-valued field.

Evidence and Observation identities can converge across a Client and Node from
the same canonical inputs without entity resolution. Claim identity additionally
depends on the resolved subject and object Entity UUIDs. Claims therefore
converge only when both processors use the same synchronized entity identities;
independent resolution to different entities intentionally produces different
Claim IDs and must be reconciled by entity resolution.

Implementations MUST share cross-runtime conformance vectors before persisting
these IDs. The fixtures must cover property order, `1` versus `1.0`, composed
versus decomposed Unicode, duplicate identifiers, reversed set-valued lists,
ordered payload arrays, null optionals, and all three record types.

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

## Initial entity resolution

Android's initial resolver is intentionally conservative. It consumes candidate
Observations and the current Silver Entity and Claim state through the
`SilverObservationResolver` interface.

- A mention reuses an Entity only when its normalized name and type match one
  existing Entity uniquely.
- A mention with a name not present in current Silver state creates a new opaque
  Entity UUID.
- A same-name type conflict or multiple exact matches remains unresolved.
- Resolution never copies Observation confidence into Claim confidence.

Every attempted mention produces an immutable `entity-resolution` Observation.
Its payload records the input Observation ID, mention role, mention name/type,
outcome, and resolved Entity ID when available. It carries the resolver's
processor/version metadata and references the same Evidence as its input.
Created Entities use that decision as origin provenance, while Claims reference
both the candidate and resolution Observations. Repeating the same decision is
therefore deterministic; a future resolver version or correction can add a new
decision without deleting the old Observation or its Bronze evidence.

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
supersede entities while retaining the old IDs and their provenance; Silver
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

## Development inspector

Each Android Library item links to a read-only, source-specific Silver
inspector. A developer can open it beside the normal file preview and follow
the complete trace through Evidence, Observations, Entities, and Claims.
Every record exposes its stable ID and the processor, model, and version
metadata that produced it; Evidence also exposes the Bronze content hash and
fragment selector when present.

Candidate Observations are labelled resolved, partially resolved, or
unresolved from their immutable `entity-resolution` observations. Multiple
active Claims for the same subject and predicate are labelled competing when
their values differ. The inspector does not resolve conflicts, edit Silver, or
select a preferred Gold interpretation.

## Reprocessing and history

The following rules make later reprocessing safe without defining job
execution in Silver:

1. Equal Evidence identity fields produce the same Evidence ID.
2. Equal normalized Observation identity fields produce the same Observation
   ID, independent of map property order or identifier-list order.
3. Equal normalized Claim identity fields, including equal resolved Entity
   UUIDs, produce the same Claim ID. A Bronze revision alone does not determine
   Claim identity.
4. New Bronze content or a new processor/model version produces new immutable
   observations and claims.
5. New records do not delete old records. Old claims may change lifecycle state,
   but their assertion, evidence, producer, and creation metadata remain
   available.
6. Current knowledge is a projection over active claims, not the latest record
   chosen by wall-clock time or model size.
7. Retention or compaction may be added later. Until then, Source retains old
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

The Android Silver snapshot persists Evidence, Observation, Entity, and Claim
as first-class records. There is one Silver shape and no compatibility or
migration path for older prototypes. Existing local or synchronized Silver
snapshots must be deleted and rebuilt from Bronze. Bronze, Library, and
conversation data are not affected. Local in-progress refinement checkpoints
likewise have one supported shape.

The Android `source.android.silver-extraction` processor version `3` emits one
`attribute-candidate` or `relationship-candidate` Observation for each valid
local finding. Its payload schema is:

```json
{
  "subject": {"name": "Robin", "type": "person"},
  "predicate": "created",
  "object": {"name": "Source", "type": "project"},
  "evidenceExcerpt": "Robin created Source"
}
```

The subject and object are local mentions, not Entity identities. An attribute
candidate has a scalar `value`; a relationship candidate has an
`object` mention. Candidate confidence is stored in the Observation's
`confidence` field. The processor validates excerpts against the processed
Bronze text before retaining them. Every candidate references Evidence for the
exact Bronze item and content hash; fragment selectors can be added without
changing the record model.

Each completed source also receives a `knowledge-extraction-complete`
Observation with an empty payload. It is a durable, deterministic processing
receipt, including when the processor found no candidates, and prevents an
unchanged source and producer version from being processed repeatedly.

Entity resolution remains deliberately separate: candidate Observations do not
create global Entities by themselves. A resolution step may now create opaque
Entity UUIDs and evidence-backed Claims in this storage model. Candidate
excerpts may later be promoted into fragment-level Evidence while remaining
optional display metadata. Existing Bronze data remains canonical throughout
the migration.
