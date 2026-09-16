# Source

Source is a private, local-first system for personal data, storage, and AI.
It consists of an Android Client, a local Source Node, and a versioned Source API.
The long-term direction is described in [`VISION.md`](VISION.md).

Source is designed to run on hardware you control and keep normal operation
inside your local network. The Client works independently; a paired Node adds
storage, synchronization, processing, and more capable local AI.

## Current status

Source is a working prototype. It currently includes:

* an Android Client with local identity, encrypted storage, chat, Library,
  LAN discovery, QR pairing, and automatic reconnect;
* a LAN-only Source Node with localhost-only administration;
* authenticated local AI through llama.cpp;
* Client-to-Node Bronze storage and synchronization;
* Node-authoritative Silver refinement with synchronized Client caching;
* user isolation, quotas, recovery, backup, and restore;
* a versioned OpenAPI contract.

The Android Client lives in [`clients/android`](clients/android/README.md).

## Install Source Node

Requirements: Linux with Docker and Docker Compose, a stable LAN address, and
enough memory and disk space for the selected local model.

```sh
cp .env.example .env
./scripts/setup.sh
./scripts/preflight.sh
./scripts/provision-model.sh
./scripts/deploy.sh
./scripts/export-ca.sh
```

By default Source binds only to loopback. To use it from a phone on your LAN,
set `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` in `.env` to the same LAN address.

Open `http://127.0.0.1:9090` on the Node machine to complete setup, administer
users, and create a temporary QR pairing invitation.

Do not expose Source through router port forwarding or a public reverse proxy.
See [`docs/operations.md`](docs/operations.md) for setup, security, backup,
restore, and deployment details.

## Install the Android Client

Build and deploy Source Client to one authorized USB-connected Android device:

```sh
./scripts/deploy-android.sh
```

Pair the Client by scanning a QR invitation created from the Node's local
administration interface. After pairing, the Client reconnects automatically
to that specific Node on the local network.

See [`clients/android/README.md`](clients/android/README.md) for Android build,
model installation, and device-test details.

## Data model

Source separates personal data into three conceptual layers:

* **Bronze** — original or imported source material;
* **Silver** — Source's derived, revisable knowledge;
* **Gold** — rebuildable projections for search, AI, timelines, and other views.

The Node is authoritative for persistent Silver. Clients keep local Bronze and
cache relevant Silver for responsive and offline use.

See [`docs/SILVER.md`](docs/SILVER.md) and
[`docs/STORAGE_AND_SYNC.md`](docs/STORAGE_AND_SYNC.md) for the canonical rules.

## Development

Source Node requires Go 1.25 or newer:

```sh
cd node
go test ./...
go run ./cmd/source-node
```

Repository content uses English. Android user-facing strings belong in
`res/values/strings.xml`. The versioned API contract lives in
[`contracts/`](contracts/README.md).

## License

No open-source license has been selected yet. The repository may be viewed and
evaluated, but reuse rights are not granted until a license is added.
