# Source product vision

Source / Self is a private, local-first home for one person's information and
AI. Self is the offline-capable interface in the person's pocket. Source is the
paired machine at home that keeps the complete long-term dataset, understands
it over time, and mirrors that understanding back to Self.

[`SOURCE_SELF_V1_UPDATED.md`](SOURCE_SELF_V1_UPDATED.md) defines the active V1
product scope. This document records the architectural principles that should
remain true as that implementation grows.

## Principles

- **Local first.** Core storage, processing, inspection, and AI must not require
  an external service.
- **Bronze is canonical.** Imported and created material is preserved immutably
  as the source. It can be deleted, but neither users nor derived processing edit
  it in place.
- **Source is authoritative.** Source alone produces persistent Silver. Self
  mirrors complete published Silver for responsive and offline inspection; it
  does not create another Silver history.
- **Self stays useful offline.** Local Bronze and the last mirrored Silver remain
  available while Source is disconnected. New authoritative refinement waits
  for Source.
- **Knowledge stays explainable.** Derived records retain Evidence leading to a
  Bronze item, its exact content hash, and a useful fragment selector.
- **Understanding is open-ended.** Observations can remain loose or unresolved.
  Entity types, claim predicates, observation kinds, processors, and models are
  extensible strings rather than a closed ontology.
- **Implementation is replaceable.** Storage engines, parsers, processors, and
  models may improve without redefining the Bronze/Silver/Gold boundaries.
- **V1 stays personal and small.** V1 is one profile and one authoritative
  Source. The current pairing surface instantiates one Self; Silver ownership is
  nevertheless defined at the Source/profile boundary rather than by a client
  connection. Multi-user, multi-Source authority, failover, and reconciliation
  are separate future designs.

## Product and authority model

```text
one profile → Clients → one authoritative Source
```

Bronze originates on Self/Clients and synchronizes to Source. Source owns
complete long-term storage and all persistent server-side processing. Clients
mirror Source Silver and do not create a competing persistent history. The
current V1 product pairs one Self, as specified by `SOURCE_SELF_V1_UPDATED.md`;
the Silver authority and job lifecycle do not depend on that client's session.
Network discovery by itself grants no trust.

## Knowledge architecture

```text
Bronze
  original file/data
      ↓
Silver extraction
  detected format + deterministic parsed structure + Evidence
      ↓
Silver understanding
  open Observations + optional Entities and Claims
      ↓
Gold
  rebuildable projection for a particular use
```

Bronze includes notes, text, Markdown, CSV, JSON, logs, documents, images, and
other original user material. Silver is everything Source can reliably extract,
interpret, or structure from that material. Parsing and decoded structure are
therefore part of Silver production, not a prerequisite outside it.

Text processing is format-aware but never format-dependent. Source uses
ordinary deterministic parsing for recognized syntax and always retains a
generic path for arbitrary text-like Bronze. Specialized content-domain
processors—such as a contacts CSV processor—are not the architecture. Semantic
processing decides what parsed content may mean.

Evidence identifies the Bronze revision and fragment behind a result. An
Observation is an immutable, open processor result such as extracted text, a
parsed table row, a semantic statement, or an entity mention. It may remain
unresolved indefinitely. Entity and Claim resolution is optional further
structuring when it adds value; it is not a validity condition for an
Observation.

Gold is a rebuildable, use-specific projection over current Silver. It is not a
second source of knowledge and is outside the first pipeline implementation.

## Durable processing

Silver work is accepted, queued, checkpointed, resumed, and published by Source
independently of Self connectivity. Processing state survives ordinary service
restarts. A completed batch checkpoint is reusable only for the same Bronze
input identity and processor revision. Partial checkpoints are private working state;
only a complete generation is atomically published as authoritative Silver.

New Bronze or changed processor/model revisions can schedule a new generation.
Existing completed Silver remains history rather than being silently rewritten.
A stale generation can never replace Silver for a different input identity. The detailed record and lifecycle contract is in
[`docs/SILVER.md`](docs/SILVER.md).

## Navigation and presentation

Self owns presentation. Opening Bronze should answer what Source extracted or
understood from that source. Opening an Entity should aggregate its current
Claims across sources and navigate back through supporting Evidence to Bronze.
The desktop stores object references, so Bronze and Silver objects can share the
surface without becoming the same data layer.

## Non-goals for this foundation

- a fixed ontology;
- one processor per file type or content domain;
- Self-authored persistent Silver;
- multiple authoritative Sources or automatic failover;
- Gold beyond a clean boundary;
- queue micromanagement controls;
- chat or a completed AI experience.
