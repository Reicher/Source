# Native retrieval baseline

Source treats retrieval as an additional rebuildable Silver representation. It does not replace the existing entity, claim, relationship, or complete-snapshot contract used by Self.

## Data path

```text
Bronze
  -> deterministic ingestion
  -> Core Silver: Evidence + structural observations
       -> retrieval chunks -> SQLite FTS5 + Embedder/sqlite-vec
       -> semantic knowledge -> observations, entities, claims, relationships
```

Core Silver is published before semantic inference starts. The retrieval chunk processor reads its dedicated deterministic snapshot rather than parsing Bronze again or inspecting semantic observations. Text blocks and Markdown headings retain UTF-8 byte-range selectors; JSON values retain JSON pointers; CSV rows retain row selectors and column-aware text. This avoids a competing chunking model and gives retrieval the same Evidence IDs used by knowledge Silver.

Retrieval and semantic knowledge reconcile independently from that common base. A slow, failed, disabled, or restarted semantic model does not delay FTS or embeddings. Semantic model changes rebuild knowledge without replacing deterministic Evidence; embedding model changes rebuild vectors without replacing knowledge.

An individual JSON scalar or table field can still be larger than the embedding runtime context even though ordinary text fragments are already bounded. The retrieval processor therefore splits every derived text deterministically at UTF-8-safe boundaries of at most 3 KiB. Split parts retain the same Silver observation and Evidence provenance while receiving distinct stable chunk IDs.

## Storage and lifecycle

`silver/retrieval.sqlite` owns only the retrieval representation. `silver/state.json` durably owns Core Silver plus knowledge jobs and generations; migrating all Silver state to SQLite remains a separate scalability change.

Source uses sqlite-vec's official CGO binding with `mattn/go-sqlite3` and the `sqlite_fts5` build tag. The earlier WASM-backed driver was rejected after its pager failed against the real persistent Docker volume on plattserver; the CGO binding embeds sqlite-vec into the Source binary and uses native SQLite without a separate database service.

The SQLite database contains:

- source and chunk metadata with Bronze hashes and Evidence selectors;
- an FTS5 index over chunk text;
- a sqlite-vec `vec0` table keyed by the same chunk row IDs;
- representation state containing processor, model, revision, dimensions, status, and errors.

At startup and after deterministic publication, retrieval reconciles against the authoritative Core Silver snapshot. Removed Bronze sources delete their chunks, FTS rows, and vectors in one reconciliation. A changed chunk processor rebuilds source chunks. A changed embedding processor, model revision, or vector dimension drops and rebuilds only the vector representation. Partial vectors are never queried: semantic retrieval becomes available only when the current representation is `ready`.

Embedding outages do not block Bronze sync or knowledge Silver. FTS remains available and retrieval responses expose the semantic state/error.

## Parser scope

Deterministic ingestion remains dependency-free in this change. Goldmark was evaluated but deferred: Source's current Markdown subset already retains exact Bronze byte ranges, while adopting an AST parser would require a larger provenance adapter and regression surface without helping the processor separation. It can be reconsidered as a focused parser change.

## Model boundaries

`LLM` and `Embedder` are separate Go interfaces. The deployed implementations use loopback-only OpenAI-compatible llama.cpp endpoints. The embedding identity is persisted with vectors; the semantic model identity belongs to the knowledge representation rather than retrieval, so changing it does not rebuild retrieval Silver.

The default deployment uses a dedicated Qwen3-Embedding-0.6B Q8_0 runtime with 1024 dimensions. It is a replaceable initial choice, not an API or storage assumption.

## Ranking and API

Lexical and vector searches each produce an ordered candidate list. The first baseline combines them with reciprocal-rank fusion (`1 / (60 + rank)`) and keeps this logic in the retrieval service so it can be replaced without changing storage or provenance.

Paired Self clients call:

```http
POST /v1/retrieval
Content-Type: application/json

{"query":"Where did we stay in Gothenburg?","limit":10}
```

Every result includes channel-specific ranks/scores, final hybrid score, chunk text, Bronze source ID/hash/title/MIME, Silver observation ID, Evidence ID/selector, and chunk/embedding producer identities. The loopback setup server also exposes `GET /retrieval/status` and `GET /retrieval?q=...&limit=...` for deployment verification; both are subject to the existing loopback-only host and remote-address checks.

Use `scripts/retrieval_smoke.py` to compare exact-term, paraphrased, cross-file, lexical-favored, and semantic-favored questions while recording wall-clock latency and channel attribution.

## Explicit non-goals

- no Context Builder or chat API;
- no reranker;
- no separate vector database or model service outside the existing local llama.cpp deployment pattern;
- no retrieval UI or retrieval mirror in Self;
- no general rewrite of knowledge Silver persistence.
