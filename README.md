# Source

Source is a private, local-first system for personal data, storage, and AI.
This repository owns the official Android Source Client, the Source Node
implementation and reference deployment, and the versioned Source API contract.
The long-term product direction is described in [`VISION.md`](VISION.md).

Source may run on a machine managed by a separate infrastructure repository,
such as `homeLab`, but it remains a standalone installation. This repository
owns Source's services, internal networks, data layout, local CA, models,
administration, API contract, backup, and restore. Host infrastructure only
reserves the LAN and loopback ports, maintains the physical machine and
firewall, and ensures Source is not routed through public services. The two
deployments must not share Compose networks, volumes, routes, or secrets.

## Current status

This is a working prototype, not the complete Source vision. Today Source Node
provides:

- a LAN-only HTTPS endpoint backed by a local certificate authority;
- localhost-only first-run administration and health dashboard;
- temporary QR invitations, Ed25519 client pairing, and administrator-approved
  recovery of an existing user on a replacement client;
- isolated users created together with their first key-proven client, with
  per-user quotas and a data model prepared for additional client identities;
- opaque storage for client-encrypted snapshots, protected by a node-specific
  recovery key held by the user;
- authenticated chat through a pinned local llama.cpp runtime;
- a versioned OpenAPI contract.

The first Android Source Client now lives in [`clients/android`](clients/android/README.md).
It implements local identity, LAN discovery, QR pairing, automatic trusted
reconnect, and one encrypted local-first AI conversation. Chat is the first
consumer of the client's generic versioned data store and encrypted Node
snapshot synchronization path; notes, contacts, and the broader personal-data
model are not implemented yet. Thoughts is the first intended
Source-compatible application.

## Install Source Node

Requirements: Docker with Docker Compose, a stable LAN address, and enough disk
and memory for the selected local model.

```sh
cp .env.example .env
./scripts/setup.sh
./scripts/preflight.sh
./scripts/provision-model.sh
docker compose up -d --build
./scripts/export-ca.sh
```

Open `http://127.0.0.1:9090` on the Node itself to initialize and administer
it. An administrator authorizes a quota-limited pairing invitation; the user
and first client are persisted only after the client proves its private key.
This port is always published on host loopback only.

The defaults bind HTTPS only to `127.0.0.1:8443`. Before using a LAN client,
set `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` in `.env` to the same reserved
LAN address. Never forward the Source port from a router to the public internet.

See [`docs/operations.md`](docs/operations.md) for setup, user administration,
security boundaries, verification, backup, and restore guidance.

## Development

### Project language

Use English throughout the repository: source code, comments, logs, API error
messages, administration and client interfaces, tests, and documentation.
Android user-facing text must be defined in `res/values/strings.xml`, even when
English is the only supported language.

Source Node requires Go 1.25 or newer:

```sh
cd node
go test ./...
go run ./cmd/source-node
```

Direct `go run ./cmd/source-node` binds both plain-HTTP listeners to loopback and leaves
DNS-SD discovery disabled by default. A non-container LAN deployment must add
an HTTPS gateway backed by the Node's local CA, keep administration on host
loopback, and explicitly enable discovery only after the advertised HTTPS
endpoint works. The Compose deployment above supplies those boundaries.

The QR renderer is compiled into the Source Node binary and never contacts an
external service at runtime.

## License

No open-source license has been selected yet. The repository may be viewed and
evaluated, but reuse rights are not granted until a license is added.
