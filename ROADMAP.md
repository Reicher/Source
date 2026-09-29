# Source Roadmap

This roadmap describes the capabilities Source is intended to grow toward.

It is **not a strict implementation order**. Some capabilities can be built earlier or later depending on what is useful to develop and test. Concrete implementation work should normally become GitHub issues when it is close enough to build.

## Status

- ✅ Done
- 🚧 In progress
- ○ Planned
- ◇ Later

## 1. Silver foundation — 🚧 In progress

Make Bronze → Silver extraction reliable, generic, provenance-backed, and practical on real personal data.

Already completed:

- ✅ Structured semantic input: preserve useful parser context such as CSV columns, JSON paths/selectors, and deterministic payloads when asking the semantic model to interpret a fragment.
- ✅ Semantic batching: process multiple deterministic fragments in one model request while keeping fragment-level provenance and recovery semantics.
- ✅ Native retrieval representation: reuse deterministic Evidence as chunks, persist FTS5 and sqlite-vec indexes in local SQLite, version embedding production independently, and expose provenance-backed hybrid retrieval.

Remaining work includes improving failure handling, regression coverage, diagnostics, and other Silver correctness issues as they are discovered.

## 2. Entity resolution — ○ Planned

Improve how Source decides whether observations refer to the same real-world entity.

Move beyond exact normalized label + type matching by using additional evidence such as aliases, phone numbers, email addresses, relationships, and other strong identifiers. Ambiguous cases should remain unresolved rather than being aggressively merged.

## 3. Corrections and trusted knowledge — ○ Planned

Allow the user to confirm or correct Source's understanding.

Examples include confirming or rejecting claims, declaring two entities to be the same or different, and preserving trusted corrections across future Silver regeneration. User-confirmed knowledge should be distinguishable from automatic model interpretation.

## 4. Self identity — ○ Planned

Represent the user as a stable Person entity in Silver.

This identity should act as the anchor for personal knowledge and relationships rather than relying on special strings such as "Self" or "User".

## 5. Gold foundation — ○ Planned

Introduce rebuildable, use-specific projections over Silver.

The first natural projection is a Person/Self profile that brings together relevant claims, relationships, events, places, interests, and other knowledge without becoming a new source of truth.

Gold should always be rebuildable from Silver.

## 6. Image understanding — ○ Planned

Support images as Bronze input and process them locally into evidence and Silver observations.

Image processing should be implemented as replaceable processors/models rather than special cases in the core knowledge model.

Potential inputs include image metadata, visual content, text visible in images, and detected people or objects.

## 7. Face recognition — ○ Planned

Add a dedicated local face-processing capability.

The likely model is:

1. detect faces in Bronze images;
2. generate face embeddings or another stable representation;
3. cluster or match faces conservatively;
4. associate recognized identities with Person entities in Silver.

Face identity should be evidence-backed, uncertainty-aware, correctable by the user, and independent of the general-purpose semantic LLM.

## 8. Audio and speech — ○ Planned

Support audio as Bronze input and transcribe speech locally.

Whisper / whisper.cpp is the likely starting point for speech-to-text. Transcripts should become evidence that can flow through the normal Silver pipeline rather than creating a separate knowledge system.

Later work may include speaker segmentation or speaker identification as separate processors.

## 9. Retrieval / RAG — 🚧 In progress

Retrieve the most relevant knowledge for a task or question without placing the entire personal knowledge base into the model context.

Retrieval should be able to draw from Bronze, Silver, and Gold while retaining links back to original evidence.

The first native baseline is complete for text-like Silver Evidence: FTS5 lexical retrieval and sqlite-vec semantic retrieval are combined through isolated reciprocal-rank fusion and exposed through Source's authenticated API. Future work includes retrieval over more modalities and representations, evaluation-driven ranking improvements, optional reranking, and integration with the Context Builder and Self UI.

## 10. Context Builder — ○ Planned

Create a shared layer that assembles model context for AI interactions.

Context may include:

- the Self identity;
- relevant Gold projections;
- relevant Silver entities, claims, and relationships;
- selected Bronze evidence;
- the current file/entity/profile being viewed;
- the user's current input.

This should become the common context mechanism rather than each AI feature building its own knowledge path.

## 11. Chat — ○ Planned

Build local conversation on top of the Context Builder.

The same chat system should be usable from different starting points, including:

- a normal new conversation;
- a Bronze item;
- a Silver entity;
- a Gold profile or projection.

Chat should consume Source knowledge rather than maintaining a separate competing memory system.

## 12. Richer Gold projections — ◇ Later

Build additional rebuildable views over Silver once the Gold model is established.

Possible projections include:

- people and relationships;
- projects;
- places;
- trips;
- timelines;
- events;
- collections and other domain-specific views.

These are projections for use and navigation, not independent truth stores.

## 13. More modalities and processors — ◇ Later

Continue adding replaceable local processors for new kinds of Bronze data.

The architecture should allow capabilities such as vision, speech, face recognition, OCR, document parsing, embeddings, and future local models to evolve independently while still producing evidence-backed Silver knowledge.

---

The long-term direction is:

```text
Bronze
  ↓
local processors
  ↓
Silver
  ↓
entity resolution + trusted corrections
  ↓
Gold projections
  ↓
retrieval + Context Builder
  ↓
Chat and other personal AI experiences
```

All higher-level understanding should remain traceable back toward the original material:

```text
Gold → Silver → Evidence → Bronze
```
