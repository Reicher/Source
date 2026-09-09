# Source Node operations

## Implemented first version

Source Node exposes a localhost-only administration interface and three
facilities on a trusted local network:

- temporary, administrator-authorized client pairing;
- chat through a locally running language model;
- storage and retrieval of snapshots already encrypted by a client.

Source Node never receives the client's vault key. It can manage accounts,
quotas, retention, metadata, and ciphertext, but it cannot read an encrypted
snapshot. Text explicitly sent to chat exists in plaintext in the model
process during that request. The current version never reads snapshots or adds
stored personal data to model context.

## Trust zones

```text
Source-compatible client on a trusted LAN
                 |
                 | HTTPS, local CA, :8443
                 v
              gateway
                 |
                 | internal HTTP
                 v
            Source Node -------- inference -------- Ollama
                 |
                 +-- user/client identity state and metadata
                 +-- opaque client-encrypted snapshots

Node owner on the physical machine
                 |
                 | HTTP, host loopback only, :9090
                 v
        Source admin listener
```

The gateway publishes the Source HTTPS port at exactly `SOURCE_BIND_IP`;
wildcard addresses are rejected by preflight. Docker publishes the admin port
separately at exactly `127.0.0.1`, never the configured LAN address. Ollama has
no host port. Do not create router forwarding for either Source port.

## Security boundaries

- The admin password uses Node 24 Argon2id with 64 MiB, three passes, and a unique salt.
- Users have no Node-side passwords. A client proves its Ed25519 private key
  during an admin-authorized, short-lived pairing window.
- Pairing invitations live only in memory and are single-use. Cancellation,
  expiry, completion, and restart revoke them.
- Only SHA-256 client-credential hashes are stored.
- Admin login and AI endpoints are rate limited in each running process.
- Admin uses HttpOnly SameSite=Strict cookies, same-origin requests, and CSRF tokens.
- Request bodies, chat text, passwords, and tokens are not logged.
- Snapshot identifiers and application namespaces are validated and allowlisted.
- Snapshots are written atomically and may be checked against a SHA-256 header.
- Ollama has no published port or normal outbound network.

Stored snapshots are zero-knowledge with respect to the normal Source Node
service. This does not protect plaintext chat from a malicious host
administrator during an explicit AI request.

## Installation

Copy and review the configuration:

```sh
cp .env.example .env
```

The defaults bind to loopback. To use Source from other devices, reserve a LAN
address for the host and set both values to that same address:

```dotenv
SOURCE_BIND_IP=192.168.1.10
SOURCE_GATEWAY_HOST=192.168.1.10
```

Create and validate the local state:

```sh
./scripts/setup.sh
./scripts/preflight.sh
```

Provision the configured model while the normal stack is stopped, then start
Source Node:

```sh
./scripts/provision-model.sh
docker compose up -d --build
docker compose ps
```

Open `http://127.0.0.1:9090` on the physical Node to complete first-run setup,
log in, view health, and add users. The gateway creates a local certificate authority on first start. Export only
its public root certificate:

```sh
./scripts/export-ca.sh
```

Import `artifacts/source-node-ca.crt` into the client. Never copy the adjacent
private `root.key` from the gateway data directory.

## User administration

```sh
./scripts/source-user.sh status
./scripts/source-user.sh list
```

These commands are read-only diagnostics. Add a user through the local admin
UI: select a byte-safe quota, display the locally generated QR invitation, and
let the client complete the documented key challenge. No user password exists
on the Node. See [`pairing.md`](pairing.md) for the exact protocol and security
semantics.

## Verification

```sh
curl --cacert artifacts/source-node-ca.crt \
  https://192.168.1.10:8443/api/v1/status
```

The same request should work from a trusted LAN client and fail from the public
internet or from any interface not named by `SOURCE_BIND_IP`.

The API contract is `contracts/source-api.openapi.yml`.

## Backup and restore

For a consistent backup, stop Source Node and run:

```sh
docker compose down
./scripts/backup.sh
docker compose up -d
```

The archive contains account state, ciphertext, metadata, and gateway state so
the local CA identity can be restored. Ollama model files are excluded because
they can be provisioned again.

Restore is intentionally manual: keep the current data directory, unpack the
archive as a replacement with restrictive permissions, run preflight, and only
then start Source. Older backups can retain a deleted user's ciphertext until
their external retention period expires; it remains unreadable without the
client's vault key.
