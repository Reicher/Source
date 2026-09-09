# First run, administration, and pairing

## Lifecycle and trust boundary

A fresh database has no `node_state` row. `POST /admin/api/initialize`
validates the display name and repeated admin password, derives an Argon2id
password hash, creates an Ed25519 Node key pair, and commits all permanent
state in one SQLite transaction. The Node is initialized only when that
transaction succeeds. Its stable Node ID is `srcnode_` plus the base64url
SHA-256 digest of the DER-encoded public key. Changing a display name in a
future release must not replace that identity.

The Source process has two internal HTTP listeners in the Compose deployment:

| Listener | Container address | Effective host entry | Purpose |
| --- | --- | --- | --- |
| Source API | `0.0.0.0:8080` | local-CA HTTPS gateway on the configured LAN address | Pairing and authenticated client APIs |
| Administration | `0.0.0.0:9090` | `127.0.0.1:9090` | First run, login, dashboard, users, invitations |

The Compose deployment publishes a dedicated gateway listener only as
`127.0.0.1:9090` on the physical host. It proxies over Source's internal edge
to the Node admin listener. `SOURCE_ADMIN_HOST=0.0.0.0` is used inside the Node
container solely so that internal proxy can reach it; the admin site is not
routed through the LAN HTTPS listener.

Direct/non-container runs bind both HTTP listeners to `127.0.0.1` and leave
DNS-SD disabled by default. LAN access requires an equivalent local-CA HTTPS
gateway; discovery must not be enabled until that advertised endpoint exists.

The self-contained admin UI loads no remote script, stylesheet, image, font,
analytics, or telemetry. Admin sessions are process-local, expire after eight
hours by default, and use an HttpOnly, SameSite=Strict cookie. Mutations also
require a per-session CSRF header and same-origin JSON requests. A restart logs
all administrators out.

## Permanent model

`node_state` stores the Node name, ID, public/private Ed25519 key material,
admin password hash, and creation time. Private key and password hash are never
returned by an API. The SQLite state file is created with mode `0600`.

`users` and `clients` are separate. A user has a random ID, random storage
namespace, byte-valued quota, display name, creation time, recovery-key hash,
and an encrypted node-data-key envelope. Each client has
a key-derived stable ID, Ed25519 public key, display name, hashed API
credential, timestamps, and revocation state. This permits more clients to be
attached to a user later without changing the data model.

Source user passwords belong exclusively to clients. Node-side user passwords
and password-based user sessions are not part of the current model.

## Invitation and QR format

Only an authenticated administrator can create an invitation. At most one is
active. It contains a 256-bit random secret, UUID, quota in bytes, creation
time, five-minute default expiry, and transient handshakes. It exists only in
process memory. Cancellation, expiry, successful pairing, or process restart
clears the secret and all provisional handshakes.

The QR is generated locally and encodes a deterministic URI whose parameters
appear in this order:

```text
source://pair?v=1&node_id=...&node_key=...&ca=...&name=...&endpoint=...&invite=...&secret=...&expires=...
```

- `v`: pairing protocol version (`1`)
- `node_id`: stable Node ID
- `node_key`: base64url DER SubjectPublicKeyInfo for the Node Ed25519 key
- `ca`: base64url DER certificate for the Node's public local root CA
- `name`: human-readable Node display name
- `endpoint`: LAN HTTPS base URL ending in `/api/v1/pairing`
- `invite`: invitation UUID
- `secret`: one-time 256-bit base64url secret
- `expires`: ISO 8601 expiry
- `action=recover`: present only for an administrator-approved recovery of an
  existing user

The client treats a scanned QR as a secret, validates its version, expiry, URL
scheme/host, Node ID derived from `node_key`, and the CA certificate. It uses
that CA as an app-private trust anchor for this Node only, then verifies the Node
signature returned by the challenge endpoint. The CA is persisted inside the
encrypted client vault; it is never installed in the operating system's global
trust store.

## Node-side protocol v1

### 1. Start

`POST {endpoint}/start`, `Content-Type: application/json`:

```json
{
  "protocol": 1,
  "invitationId": "UUID from QR",
  "invitationSecret": "secret from QR",
  "clientPublicKey": "base64url DER Ed25519 SubjectPublicKeyInfo",
  "userDisplayName": "Robin",
  "clientDisplayName": "Robin's phone"
}
```

The Node checks the active invitation, protocol, names, key encoding, and
duplicate client identity. It derives `clientId = "srcclient_" +
base64url(SHA-256(clientPublicKeyDER))` and returns:

```json
{
  "protocol": 1,
  "handshakeId": "UUID",
  "challenge": "base64url random bytes",
  "signingPayload": "exact string to sign",
  "nodeSignature": "base64url Ed25519 signature",
  "expiresAt": "ISO 8601"
}
```

`signingPayload` is the UTF-8 encoding of these newline-separated fields:

```text
source-pairing-v1
NODE_ID
INVITATION_ID
HANDSHAKE_ID
CHALLENGE
CLIENT_ID
base64url(UTF-8 USER_DISPLAY_NAME)
base64url(UTF-8 CLIENT_DISPLAY_NAME)
```

The client verifies `nodeSignature` with `node_key` from the QR, then signs the
exact `signingPayload` bytes with its own Ed25519 private key.

### 2. Complete

`POST {endpoint}/complete`:

```json
{
  "protocol": 1,
  "invitationId": "UUID from QR",
  "invitationSecret": "secret from QR",
  "handshakeId": "UUID from start",
  "signature": "base64url client Ed25519 signature",
  "recoveryKey": "256-bit base64url recovery key",
  "recoveryEnvelope": "node data key encrypted by the recovery key"
}
```

Signature verification and SQLite user/client creation run without an async
interleaving point. The database inserts both records in one immediate
transaction. Only after it commits does the service clear and consume the
invitation. The response contains user/client metadata and a 256-bit
`clientCredential`, returned once; only its SHA-256 hash is stored. The client
uses it as `Authorization: Bearer ...` for normal Source API calls.

For a recovery invitation, the client sends the recovery key but not a new
envelope. The Node verifies its stored hash, revokes the user's old clients,
creates the replacement client, and returns the existing encrypted envelope.
The client unwraps the shared node data key locally and uses it to decrypt the
user's existing snapshots. Five incorrect keys cancel the invitation.

Clients paired before recovery support can configure it once through
`POST /api/v1/recovery/setup`. The Android client does this automatically after
first synchronizing an older snapshot and then uploads a snapshot encrypted by
the new node-specific data key.

## Discovery and reconnect proof

An initialized Node advertises `_source._tcp` with DNS-SD/mDNS. The service
port is the public HTTPS gateway port. Its TXT record contains only public,
untrusted hints: `v=1`, `id=NODE_ID`, `name=NODE_DISPLAY_NAME`, and
`api=/api/v1`. Discovery never grants
trust. Container deployments use the small host-network `discovery` sidecar so
multicast originates on the physical LAN interface.

After finding a previously paired Node, the client sends an authenticated
`POST /api/v1/identity/challenge` request containing `protocol: 1` and a fresh
256-bit base64url `nonce`. The Node returns its public identity, the client ID,
display name, nonce, exact signing payload, and an Ed25519 signature. The
newline-separated payload is:

```text
source-node-auth-v1
NODE_ID
CLIENT_ID
NONCE
base64url(UTF-8 NODE_DISPLAY_NAME)
```

The client must compare the returned Node ID and key with its persisted trust,
compare the client ID and nonce with its request, reconstruct the exact payload,
and verify the signature before showing the Node as connected. A DNS-SD name,
TXT record, hostname, or IP address alone is never authoritative.

The LAN HTTPS certificate chains to the installation's local Source CA embedded
in the pairing QR. The client verifies against that private per-Node trust
anchor and does not disable TLS verification or modify Android's trust store.

Failures expose bounded error codes rather than secrets or internal details.
An absent, unknown, cancelled, expired, consumed, or pre-restart invitation
returns the same `pairing_unavailable` response. An invalid signature does not
consume the invitation or create a user. Replaying a completed request cannot
create another user.

## Local admin interface

The UI uses these loopback-only interfaces. Creating an invitation authorizes
a quota; it does not persist a user or client until pairing completes:

- `GET /admin/api/state`
- `POST /admin/api/initialize`
- `POST /admin/api/login`
- `POST /admin/api/logout`
- `GET /admin/api/dashboard`
- `POST /admin/api/pairing-invitations`
- `GET /admin/api/pairing-invitations/active`
- `DELETE /admin/api/pairing-invitations/{id}`
- `GET /admin/api/pairing-invitations/{id}/qr.svg`
- `POST /admin/api/users/{id}/recovery-invitations`
- `DELETE /admin/api/users/{id}`

The dashboard contains administrative metadata and bounded system-health
values only. It does not expose storage namespaces, ciphertext, private keys,
password hashes, recovery-key hashes or envelopes, invitation secrets as text,
or client credentials. Deletion requires the exact display name in the request
body, revokes all clients, and removes the user's snapshots and database rows.

The LAN API is specified in `contracts/source-api.openapi.yml`. Admin routes
are deliberately not part of that LAN contract.
