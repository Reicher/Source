# Client–Node connection contract

This document defines the connection state machine and the only readiness
signals that Client features may use. DNS-SD discovery is an untrusted routing
hint; a Node becomes usable only after TLS, client authentication, and the
permanent Node identity challenge succeed.

## States

```text
Discovering -> Found -> Pairing/Recovering
     |                    |
     +-> Authenticating -> Ready
              |             |
              +-> Retrying  +-> Degraded
              |             |
              +----------> Disconnected
```

- `Discovering`: the local network and DNS-SD are being observed.
- `Found`: unpaired Nodes are visible and require an explicit user choice.
- `Pairing` / `Recovering`: credentials or recovery material are being changed.
- `Authenticating`: the authoritative Node endpoint is being authenticated.
- `Retrying`: authentication failed transiently; the attempt number and next
  backoff duration are explicit.
- `Ready`: authenticated storage and the advertised Node AI runtime are usable.
- `Degraded`: authenticated Node storage is usable, but Node AI is not. The AI
  runtime state carries the diagnosable reason.
- `Disconnected`: no authenticated Node operations may start. The reason is
  backgrounding, unavailable network, missing discovery, lost connection, or
  exhausted authentication retries.
- `Failed`: a user-driven connection flow failed and may optionally be retried.

`nodeStorageUsable()` is true only for `Ready` and `Degraded`.
`nodeAiUsable()` is true for `Ready` when the AI runtime is `ready`, or when it
retains model capabilities after a retryable `inference_failed` result. A prior
generation failure is diagnostic state and does not permanently disable retries.
Callers must not infer either property from DNS-SD, network availability, a
stored credential, or a non-null endpoint.

## Delegation and fallback

Each operation snapshots its target when it starts. A Node appearing while a
Client operation is running does not migrate that operation. A Node disappearing
after a streamed response has produced output fails that response; it never
starts a second local answer.

- `Auto` uses Node AI only when `nodeAiUsable()` is true. It may fall back to
  Client AI for an availability or transport failure before Node output starts.
- `This device` always uses Client AI and is unaffected by Node transitions.
- Explicit `Node` never silently executes on the Client. If Node AI is not
  usable, the operation fails with `node_unavailable`.
- Rate limits, request validation, authentication failures, and failures after
  output starts never trigger AI fallback.
- Storage operations never fall back to another Node.
- Persistent Silver refinement is owned by the profile's authoritative Node.
  It waits while disconnected or degraded and never falls back to Client AI.

The connection owner retries authentication with bounded exponential backoff.
Discovery loss cancels an outstanding attempt. A successful heartbeat refreshes
both authentication and Node AI runtime state, allowing `Degraded` and `Ready`
to transition without an ad-hoc probe in feature code.
