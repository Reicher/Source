# Shared Source AI runtime contract

`contracts/source-ai.schema.json` is the wire-level contract. The Kotlin
`SourceAiRuntime` interface and the Node implementation expose the same request,
event, capability, state, cancellation, and failure semantics.

## Runtime state

Every runtime publishes one of these states:

- `model_not_installed`: required model artifacts are absent.
- `model_present`: artifacts exist, but lazy loading has not completed.
- `model_load_failed`: artifacts were found but the runtime could not load them.
- `ready`: the runtime has identified its model and published capabilities.
- `inference_failed`: the most recent generation failed after the runtime loaded.

Failure states include a stable failure code. `ready` includes model metadata and
discoverable capabilities; callers must not assume modalities, context size,
streaming, cancellation, prompt policy, or reasoning support from the runtime's
location or model family. UI and diagnostics consume this state without knowing
whether the runtime is on the Client or Node.

## Streams

One request has a UUID `runId`, an explicit timeout from 1–600 seconds, and
produces ordered events for that same run:

```text
started -> delta* -> completed
started? -> delta* -> failed
```

`delta.sequence` starts at zero. `completed` and `failed` are terminal. A runtime
must convert operational failures to `failed`; only collector cancellation and
programming/contract violations escape as exceptions. Closing or cancelling the
consumer cancels generation. The timeout covers queueing and generation on the
Client and the complete Node request; the Node backend additionally uses an
inactivity timeout to detect a stalled model stream.

Stable cross-runtime failure codes include `model_not_installed`,
`model_load_failed`, `inference_failed`, `timeout`, `cancelled`,
`node_unavailable`, and `node_stream_interrupted_after_output`. HTTP validation,
authentication, and rate-limit codes remain Source API errors and preserve their
original code in a `failed` event or pre-stream HTTP error.

Source v1 accepts explicit user and assistant content only, applies no hidden
Source system prompt (`none-v1`), and does not expose reasoning content. A
runtime may advertise vision only when it accepts image content end to end.
