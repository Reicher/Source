# Source

Source is a private, local-first system for personal data, storage, and AI.
This repository contains the early Source Node implementation and the versioned
Source API contract. The long-term product direction is described in
[`VISION.md`](VISION.md).

## Current status

This is a working prototype, not the complete Source vision. Today Source Node
provides:

- a LAN-only HTTPS endpoint backed by a local certificate authority;
- localhost-only first-run administration and health dashboard;
- temporary QR invitations and Ed25519 client pairing;
- isolated users with per-user quotas and one or more client identities;
- opaque storage for client-encrypted snapshots;
- authenticated chat through a local Ollama model;
- a versioned OpenAPI contract.

The first Android Source Client now lives in [`clients/android`](clients/android/README.md).
It implements local identity, LAN discovery, QR pairing, and automatic trusted
reconnect. General sync and the broader personal-data model are not implemented
yet. Thoughts is the first intended Source-compatible application.

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
it. This port is always published on host loopback only.

The defaults bind HTTPS only to `127.0.0.1:8443`. Before using a LAN client,
set `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` in `.env` to the same reserved
LAN address. Never forward the Source port from a router to the public internet.

See [`docs/operations.md`](docs/operations.md) for setup, user administration,
security boundaries, verification, backup, and restore guidance.

## Development

Source Node requires Node.js 24.7 or newer:

```sh
cd node
npm ci
npm test
```

The QR renderer is installed locally with the Node package and never contacts
an external service at runtime.

## License

No open-source license has been selected yet. The repository may be viewed and
evaluated, but reuse rights are not granted until a license is added.
