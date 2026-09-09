# Source

Source is a private, local-first system for personal data, storage, and AI.
This repository contains the early Source Node implementation and the versioned
Source API contract. The long-term product direction is described in
[`VISION.md`](VISION.md).

## Current status

This is a working prototype, not the complete Source vision. Today Source Node
provides:

- a LAN-only HTTPS endpoint backed by a local certificate authority;
- manually administered, isolated user accounts;
- opaque storage for client-encrypted snapshots;
- authenticated chat through a local Ollama model;
- a versioned OpenAPI contract.

The standalone Source Client, local pairing through QR code, general sync, key
management, and broader personal-data model are not implemented yet. Thoughts
is the first intended Source-compatible application.

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

The defaults bind HTTPS only to `127.0.0.1:8443`. Before using a LAN client,
set `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` in `.env` to the same reserved
LAN address. Never forward the Source port from a router to the public internet.

See [`docs/operations.md`](docs/operations.md) for setup, user administration,
security boundaries, verification, backup, and restore guidance.

## Development

Source Node requires Node.js 24.7 or newer:

```sh
cd node
npm test
```

No third-party npm dependencies are required by the current Node service.

## License

No open-source license has been selected yet. The repository may be viewed and
evaluated, but reuse rights are not granted until a license is added.
