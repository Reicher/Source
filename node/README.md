# Source Node

Source Node is a single Go binary with two HTTP listeners and an optional
DNS-SD process. The public Source API remains defined by
[`../contracts/source-api.openapi.yml`](../contracts/source-api.openapi.yml); the administrator API is local to
the Node deployment.

## Package layout

- `cmd/source-node` wires configuration, lifecycle, HTTP servers, discovery,
  and the small administration CLI together.
- `internal/config` validates environment configuration.
- `internal/database` owns SQLite schema and transactions.
- `internal/security` owns random tokens, Argon2id, Ed25519 keys, and signing.
- `internal/auth` authenticates client credentials.
- `internal/pairing` implements pairing and recovery state machines.
- `internal/storage` stores opaque encrypted snapshots and retention metadata.
- `internal/httpapi` implements the versioned Source API.
- `internal/admin` implements localhost administration and its embedded UI.
- `internal/discovery` advertises `_source._tcp` over DNS-SD/mDNS.
- `internal/ai` adapts Source's NDJSON chat stream to llama.cpp SSE.

The process intentionally uses `net/http` and other standard-library packages
for most service behavior. SQLite, Argon2id, QR generation, and DNS-SD use small
focused dependencies.

## Develop

Go 1.25 or newer is required.

```sh
go test ./...
go vet ./...
go run ./cmd/source-node
```

The default development listeners are `127.0.0.1:8080` for the internal HTTP
API and `127.0.0.1:9090` for administration. Production TLS and LAN exposure
are provided by the repository's Compose gateway.

The binary also provides operational subcommands:

```sh
go run ./cmd/source-node status
go run ./cmd/source-node list
go run ./cmd/source-node discovery
go run ./cmd/source-node healthcheck
```

## State

SQLite state defaults to `/state/source-node.sqlite`. Snapshot bytes remain
under `/vaults/<storage namespace>/<application>/snapshots`, separate from
metadata. Node identity is an Ed25519 key stored in SQLite as PKCS#8/SPKI DER.
Administrator passwords and recovery keys use the existing Argon2id encoding.

The implementation can open the previous JavaScript service's schema and data,
although this migration does not require backward compatibility. It never
deletes existing users or snapshots automatically; destructive reset and user
deletion remain explicit administrator actions.
