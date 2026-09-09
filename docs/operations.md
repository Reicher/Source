# Source Node operations

## Implemented first version

Source Node currently exposes two authenticated services on a trusted local
network:

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
                 +-- account/session state and metadata
                 +-- opaque client-encrypted snapshots
```

Only the gateway publishes a host port. Source Node and Ollama have no host
ports and use internal Docker networks. The gateway binds to exactly
`SOURCE_BIND_IP`; wildcard addresses are rejected by preflight. Do not create
router forwarding for the Source port.

## Security boundaries

- There is no network registration endpoint yet; operators create users locally.
- Passwords use Node 24 Argon2id with 64 MiB, three passes, and unique salts.
- Access tokens normally last 15 minutes and refresh tokens 30 days.
- Only SHA-256 token hashes are stored.
- Login and AI endpoints are rate limited in each running process.
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

The gateway creates a local certificate authority on first start. Export only
its public root certificate:

```sh
./scripts/export-ca.sh
```

Import `artifacts/source-node-ca.crt` into the client. Never copy the adjacent
private `root.key` from the gateway data directory.

## User administration

```sh
./scripts/source-user.sh create robin
./scripts/source-user.sh list
./scripts/source-user.sh reset-password robin
./scripts/source-user.sh sessions robin
./scripts/source-user.sh revoke-session robin SESSION-UUID
./scripts/source-user.sh disable robin
./scripts/source-user.sh enable robin
./scripts/source-user.sh delete robin --confirm
```

`create` and `reset-password` display a generated password once. Transfer it
directly to the user. Resetting a password revokes all sessions. Deleting a
user removes the account, sessions, metadata, and server-side ciphertext; it
does not affect copies already held by clients.

This manual administration is an interim mechanism. The local QR pairing flow
described in `VISION.md` is not implemented yet.

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
