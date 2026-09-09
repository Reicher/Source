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

## Repository and host responsibility

This repository owns the complete Source installation: Node, its private
gateway and internal networks, discovery, local model runtime, users, storage,
local CA, API contract, backup, and restore. A separate host-infrastructure
repository such as `homeLab` may manage the physical server, container runtime,
host firewall, and port reservations. It must not include Source in its public
proxy, read Source data or secrets, or share Compose networks and volumes with
Source. Source-specific changes and operational commands remain in this
repository.

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
wildcard addresses are rejected by preflight. The same gateway publishes an
independent HTTP listener at exactly `127.0.0.1` and proxies it over an internal
network to the Node's admin listener. It is never published on the configured
LAN address. Ollama has no host port. Do not create router forwarding for
either Source port.

## Security boundaries

- The admin password uses Node 24 Argon2id with 64 MiB, three passes, and a unique salt.
- Users have no Node-side passwords. A client proves its Ed25519 private key
  during an admin-authorized, short-lived pairing window.
- Pairing invitations live only in memory and are single-use. Cancellation,
  expiry, completion, and restart revoke them.
- Recovery invitations are bound to one existing user. A successful recovery
  requires that user's node-specific recovery key and revokes older clients.
- The Node stores only a hash of the recovery key and an encrypted envelope for
  the client-generated data key. Five incorrect recovery attempts cancel the
  active invitation.
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

The `discovery` sidecar uses host networking to publish `_source._tcp` mDNS on
the physical LAN. Native Linux supports this directly; Docker Desktop must have
host networking enabled. Permit local multicast UDP 5353 and the configured
HTTPS port in the host firewall, but do not expose either through the router.

Open `http://127.0.0.1:9090` on the physical Node to complete first-run setup,
log in, view health, and authorize a quota-limited pairing invitation. The user
and first client are written only after the client completes the key proof. The
gateway creates a local certificate authority on first start. Export only its
public root certificate:

```sh
./scripts/export-ca.sh
```

The Node embeds `artifacts/source-node-ca.crt` in each pairing QR. Clients keep
it as a private per-Node trust anchor, so users do not install a system CA.
Never copy the adjacent private `root.key` from the gateway data directory.

## User administration

```sh
./scripts/source-user.sh status
./scripts/source-user.sh list
```

These commands are read-only diagnostics. Authorize a user through the local
admin UI: select a byte-safe quota, display the locally generated QR invitation,
and let the client complete the documented key challenge. No database user or
client is created before that proof succeeds, and no user password exists on
the Node. See [`pairing.md`](pairing.md) for the exact protocol and security
semantics.

The admin UI can also create a recovery invitation for an existing user or
permanently delete a user. Deletion immediately revokes every client and
removes the user's database records and snapshot directory after the admin
types the exact display name as confirmation.

Direct/non-container runs are safe development building blocks, not a complete
LAN deployment: the plain-HTTP Source and admin listeners bind to loopback and
discovery is disabled by default. A native LAN installation must provide the
same local-CA HTTPS termination and loopback-only administration boundary as
the Compose deployment before enabling DNS-SD.

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

Node-wide restore is intentionally manual: keep the current data directory, unpack the
archive as a replacement with restrictive permissions, run preflight, and only
then start Source. Older backups can retain a deleted user's ciphertext until
their external retention period expires.

For a lost phone, create a fresh local user on the replacement client. In the
admin UI choose **Recover** for the existing Node user, scan the short-lived QR
code, and enter the recovery key previously shown by the connected client. The
replacement client receives the existing encrypted snapshots and registers a
new client credential; all earlier client credentials for that user are
revoked.
