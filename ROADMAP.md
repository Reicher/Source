# Source Roadmap

This roadmap describes the capabilities Source is intended to grow toward.

It is **not a strict implementation order**, but the near-term priorities are listed first. Concrete implementation work should normally become GitHub issues when it is close enough to build.

## Status

- ✅ Done
- 🚧 In progress
- ○ Planned
- ◇ Later

## Architectural direction

Source keeps Bronze as the canonical source material and treats everything above it as rebuildable or explicitly user-authored.

```text
Bronze
  ↓
deterministic ingestion
  ↓
Core Silver
Evidence + structural observations
  │
  ├── retrieval
  │     ├── FTS5
  │     └── embeddings / sqlite-vec
  │
  ├── semantic knowledge
  │     ├── semantic observations
  │     ├── entities
  │     ├── claims
  │     └── relationships
  │
  └── future processors
        ├── OCR / document understanding
        ├── speech
        └── vision

Trusted Knowledge
(user-authored decisions; persistent and separate from rebuildable Silver)

Core Silver + Silver representations + Trusted Knowledge
  ↓
Context Builder
  ↓
Chat / analysis / Gold projections
```

Important principles:

- Bronze remains immutable source material.
- Deterministic Core Silver must not depend on an LLM.
- Silver is a family of independently rebuildable, provenance-backed representations, not another source of truth.
- Retrieval and semantic knowledge are peers over the same deterministic Evidence.
- A failed or unavailable processor must not block unrelated representations.
- Models and processors remain replaceable.
- Trusted Knowledge, when introduced, is persistent user-authored knowledge and is not ordinary Bronze or rebuildable Silver.
- New complexity should be justified by product behavior and evaluation rather than added speculatively.

## 1. Core Silver foundation — ✅ Done

The first general Bronze → Silver foundation is in place.

Completed:

- ✅ Content-addressed immutable Bronze with exact source provenance.
- ✅ Deterministic ingestion for JSON, CSV, Markdown and generic UTF-8 text.
- ✅ Atomic structured Evidence with parent context and conservative decomposition of structured scalar values.
- ✅ Independent Core Silver publication before semantic model work.
- ✅ Semantic extraction as a separate knowledge representation.
- ✅ Schema-constrained local model output with Source-owned validation.
- ✅ Semantic batching, retries and bounded partial failure handling.
- ✅ Retrieval as a separate Silver representation.
- ✅ Local FTS5 lexical retrieval.
- ✅ Local sqlite-vec semantic retrieval using a replaceable embedding model.
- ✅ Hybrid reciprocal-rank fusion with provenance-backed results.
- ✅ Independent processor/model versioning so embeddings and semantic knowledge can rebuild separately.
- ✅ Self continues to mirror and inspect persistent Silver while processors run independently.

The foundation should now be simplified rather than expanded with more special cases.

## 2. Silver cleanup and persistence — 🚧 In progress

Simplify the implementation now that deterministic ingestion, retrieval and semantic knowledge have clear boundaries.

Near-term cleanup should include:

- remove legacy fields and compatibility paths that duplicate the new representation model;
- prefer rebuilding derived Silver from Bronze over carrying long-lived migrations for obsolete Silver schemas;
- remove old semantic coverage/model fields where representation state now owns the same information;
- review and preferably remove the old Silver `History` mechanism if it is no longer required;
- make automatically derived entity identity rebuildable where possible instead of preserving it through historical datasets;
- simplify or remove the persistent entity registry if the current resolver no longer needs it;
- replace the old phase-oriented Silver job model with the smallest useful generic processor-job model if that reduces special-case code;
- keep processor failures and retries isolated;
- reduce duplicated state and lifecycle logic in `silver.go`.

After the cleanup, evaluate moving rebuildable Silver data and processor state from `silver/state.json` into SQLite.

Likely SQLite-owned derived state could eventually include:

- sources / representations;
- Evidence and observations;
- processor jobs/checkpoints;
- semantic entities and claims;
- retrieval chunks, FTS and vectors.

Bronze should remain independently stored and canonical. A SQLite migration should only be done where it removes meaningful custom persistence code; it is not a goal by itself.

## 3. Retrieval and evaluation — 🚧 In progress

The first native retrieval baseline is complete for text-like data.

Current baseline:

- ✅ deterministic retrieval chunks with Bronze/Evidence provenance;
- ✅ SQLite FTS5 lexical search;
- ✅ sqlite-vec semantic search;
- ✅ replaceable local embedding model;
- ✅ reciprocal-rank fusion;
- ✅ authenticated Source API;
- ✅ graceful lexical fallback when embeddings are unavailable.

Next work should be evaluation-driven.

Build a small reproducible question set covering:

- exact factual lookup;
- semantic paraphrases;
- cross-file questions;
- temporal questions;
- broad thematic questions;
- conflicting or stale information.

Use it to compare future retrieval/context strategies before adding rerankers, graph traversal or other ranking complexity.

## 4. Context Builder — ○ Planned

Create one shared layer for assembling model context for AI interactions.

The first Context Builder should be deliberately small and retrieval-first.

It should be able to combine:

- the user's current input;
- retrieved Evidence/chunks;
- relevant Bronze/source metadata;
- optionally relevant semantic entities/claims when they add useful context;
- the currently viewed Bronze item or Silver entity when the interaction starts from one.

Every included piece of context should retain provenance.

The Context Builder should become the common path for AI features rather than each feature building its own retrieval or memory system.

## 5. Local chat and analysis — ○ Planned

Build the first local conversational experience on top of the Context Builder.

Start with a minimal end-to-end path:

```text
question
  ↓
Context Builder
  ↓
local Source LLM
  ↓
answer with traceable supporting material
```

The same mechanism should later work from different entry points such as:

- a normal conversation;
- a Bronze item;
- a Silver entity;
- a future Gold projection.

Chat must not introduce a separate competing long-term memory store.

## 6. Evaluate structured knowledge — ○ Planned

Once Context Builder and basic chat exist, measure what the semantic knowledge representation actually contributes.

Compare at least:

```text
retrieval + raw Evidence
vs
retrieval + semantic entities/claims/relationships
```

Use real Source questions and data.

This evaluation should determine how much further engineering is justified in the entity/claim system.

The semantic knowledge representation should not automatically become more complex merely because it can.

## 7. Entity resolution — ○ Planned

Improve entity resolution if evaluation shows that stable cross-source entities materially improve Source.

Resolution should be:

- generic and evidence-driven;
- conservative;
- able to leave ambiguity unresolved;
- independent of a hardcoded list of identity fields or predefined weights for phone numbers, email addresses, addresses, or similar domain-specific properties.

The Source model may reason over available claims, relationships and Evidence, but automatic merges should only occur when support is sufficiently clear.

Do not make canonical entities the only way information can remain useful; retrieval and Evidence must continue to work independently.

## 8. Trusted Knowledge and corrections — ○ Planned

Introduce persistent user-authored knowledge when there is a concrete correction/resolution workflow to support.

Trusted Knowledge is separate from both Bronze and rebuildable Silver.

Initial entity-resolution decisions may use:

- **Pending** — Source has identified a question requiring user judgment;
- **Applied** — a user decision can currently be applied;
- **Obsolete** — a previous user decision can no longer be safely bound to current Evidence.

Examples include:

- two occurrences/entities are the same;
- two occurrences/entities are different.

Trusted decisions must survive Silver regeneration and model changes by referring back to stable underlying Evidence/anchors rather than transient Silver entity IDs.

User decisions should override later automatic interpretation. Obsolete decisions should not be silently deleted.

Claim corrections can be added later if the entity-resolution use case proves useful.

## 9. Self identity — ○ Planned

Represent the user as a stable Person identity when the knowledge and trusted-correction model is ready to support it.

Self identity should anchor personal relationships and profiles without relying on special semantic strings such as `Self` or `User`.

Do not make Self identity a prerequisite for basic retrieval, Context Builder or chat.

## 10. Gold foundation — ○ Planned

Introduce rebuildable, use-specific projections when there are concrete views that benefit from them.

Gold is not another source of truth. It is a projection over current Source knowledge for a task or interface.

Potential early projections include:

- a Person/Self profile;
- timeline views;
- related material around a project, trip or place.

Gold should always be rebuildable from Silver plus any applicable Trusted Knowledge.

## 11. Image understanding — ○ Planned

Support images as Bronze input through replaceable local processors.

Possible outputs include:

- image metadata;
- OCR text;
- visual descriptions;
- detected objects or people;
- image embeddings.

Outputs should become provenance-backed Silver representations and feed the same retrieval/Context Builder paths rather than creating a separate image knowledge system.

## 12. Audio and speech — ○ Planned

Support audio as Bronze input and transcribe it locally.

Whisper / whisper.cpp is a likely starting point for speech-to-text.

Transcripts and timestamps should become provenance-backed Silver that can use the same retrieval and semantic processors as other text.

Speaker segmentation or identification can be added separately if useful.

## 13. Face recognition — ◇ Later

Add a dedicated local face-processing capability after general image ingestion exists.

A likely path is:

1. detect faces;
2. generate face embeddings;
3. cluster/match conservatively;
4. optionally associate clusters with Person identities.

Face identity should remain evidence-backed, uncertainty-aware and user-correctable.

## 14. Richer Gold projections — ◇ Later

Build additional projections after the Gold model proves useful.

Possible views include:

- people and relationships;
- projects;
- places;
- trips;
- timelines;
- events;
- collections.

These remain projections for use and navigation, not independent truth stores.

## 15. More modalities and processors — ◇ Later

Continue adding replaceable local processors as concrete use cases appear.

Potential processors include:

- richer document parsing;
- OCR;
- speech;
- speaker identification;
- vision;
- face processing;
- additional embedding models;
- future local specialist models.

New processors should consume Bronze/Core Silver and publish rebuildable, provenance-backed representations without redefining the core architecture.

---

The current near-term direction is:

```text
Core Silver foundation
  ↓
cleanup derived state and processor lifecycle
  ↓
evaluate SQLite for Silver persistence
  ↓
Context Builder
  ↓
minimal local chat / analysis
  ↓
evaluate retrieval-only vs structured knowledge
  ↓
invest further in entity resolution / Trusted Knowledge only where useful
```

All machine-derived understanding must remain traceable toward original source material:

```text
Gold / AI context
      ↓
Silver representations
      ↓
Evidence
      ↓
Bronze
```

Trusted Knowledge is the separate persistent record of explicit user decisions applied alongside that rebuildable chain.
