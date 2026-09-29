# Native retrieval baseline

Source treats retrieval as an additional rebuildable Silver representation. It does not replace the existing entity, claim, relationship, or complete-snapshot contract used by Self.

## Data path

```text
Bronze
  -> existing deterministic Silver parser
  -> Evidence + deterministic observations
  -> retrieval chunks
       -> SQLite FTS5
       -> Embedder -> sqlite-vec
  -> reciprocal-rank fusion
  -> provenance-backed API results
```

The retrieval chunk processor reads published deterministic observations rather than parsing Bronze again. Text blocks and Markdown headings retain UTF-8 byte-range selectors; JSON values retain JSON pointers; CSV rows retain row selectors and column-aware text. This avoids a competing chunking model and gives retrieval the same Evidence IDs already used by knowledge Silver.

## Storage and lifecycle

`silver/retrieval.sqlite` owns only the retrieval representation. The existing `silver/state.json` knowledge pipeline remains unchanged in this baseline; migrating all Silver jobs and knowledge generations to SQLite is a separate scalability change.

The SQLite database contains:

- source and chunk metadata with Bronze hashes and Evidence selectors;
- an FTS5 index over chunk text;
- a sqlite-vec `vec0` table keyed by the same chunk row IDs;
- representation state containing processor, model, revision, dimensions, status, and errors.

At startup and after Silver publication, retrieval reconciles against the authoritative current Silver snapshot. Removed Bronze sources delete their chunks, FTS rows, and vectors in one reconciliation. A changed chunk processor rebuilds source chunks. A changed embedding processor, model revision, or vector dimension drops and rebuilds only the vector representation. Partial vectors are never queried: semantic retrieval becomes available only when the current representation is `ready`.

Embedding outages do not block Bronze sync or knowledge Silver. FTS remains available and retrieval responses expose the semantic state/error.

## Model boundaries

`LLM` and `Embedder` are separate Go interfaces. The deployed implementations use loopback-only OpenAI-compatible llama.cpp endpoints. The embedding identity is persisted with vectors; the chat/generative LLM identity is not, so replacing a future chat model does not rebuild retrieval Silver.

The default deployment uses a dedicated Qwen3-Embedding-0.6B Q8_0 runtime with 1024 dimensions. It is a replaceable initial choice, not an API or storage assumption.

## Ranking and API

Lexical and vector searches each produce an ordered candidate list. The first baseline combines them with reciprocal-rank fusion (`1 / (60 + rank)`) and keeps this logic in the retrieval service so it can be replaced without changing storage or provenance.

Paired Self clients call:

```http
POST /v1/retrieval
Content-Type: application/json

{"query":"Where did we stay in Gothenburg?","limit":10}
```

Every result includes channel-specific ranks/scores, final hybrid score, chunk text, Bronze source ID/hash/title/MIME, Evidence ID/selector, and chunk/embedding producer identities. The loopback setup server also exposes `GET /retrieval?q=...&limit=...` for deployment verification; it is subject to the existing loopback-only host and remote-address checks.

Use `scripts/retrieval_smoke.py` to compare exact-term, paraphrased, cross-file, lexical-favored, and semantic-favored questions while recording wall-clock latency and channel attribution.

## Explicit non-goals

- no Context Builder or chat API;
- no reranker;
- no separate vector database or model service outside the existing local llama.cpp deployment pattern;
- no retrieval UI or retrieval mirror in Self;
- no general rewrite of knowledge Silver persistence.
