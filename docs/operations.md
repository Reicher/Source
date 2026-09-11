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
            Source Node -------- inference -------- llama.cpp
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
LAN address. llama.cpp has no host port. Do not create router forwarding for
either Source port.

## Security boundaries

- The admin password uses Argon2id with 64 MiB, three passes, and a unique salt.
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
- llama.cpp has no published port or normal outbound network.

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
./scripts/deploy.sh
```

`scripts/deploy.sh` runs preflight, builds and updates the Compose services,
waits for every service to be running and healthy, and verifies the loopback
health endpoint. It refuses to run as root and is safe to run repeatedly. It
does not create or modify `.env`, provision a model, or delete or reset
persistent state. Run it directly after an SSH login for a manual deployment;
GitHub is not required.

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

## Automatic deployment with GitHub Actions

The repository keeps tests on GitHub-hosted runners. After the existing
`Test` workflow succeeds for a push to `main`, the separate `Deploy` workflow
runs only its deployment job on a Source Node labeled `source-node`. A pull
request workflow run can never satisfy that job's event checks, so pull-request
code is not checked out or executed on the Node by this workflow.

The runner connects outbound to GitHub. Do not expose a runner, SSH, Docker, or
an additional HTTP port to the internet. Source's runtime services keep the
same LAN, loopback, and internal-network boundaries described above and do not
depend on GitHub after deployment.

### Prepare the runner account and workspace

Install the GitHub runner as a dedicated unprivileged local account. Prefer
rootless Docker. If the host uses the conventional Docker daemon, give only
this dedicated account Docker access and treat that membership as privileged:
access to the Docker socket is effectively host-root access even though the
runner and deployment script do not use `sudo`.

Download the current Linux runner from the repository's **Settings > Actions >
Runners > New self-hosted runner** page and follow GitHub's displayed commands
as the dedicated account. Register it at repository scope with the additional
label `source-node`, install its service under that account, and choose a
stable work directory. Do not register a shared organization-wide runner for
this deployment.

GitHub supplies the current download URL and a short-lived registration token.
The registration and service commands have this shape; replace every bracketed
value and run `config.sh` as the dedicated account:

```sh
./config.sh --url https://github.com/<owner>/<repository> \
  --token <registration-token> \
  --labels source-node \
  --work _work
sudo ./svc.sh install <runner-account>
```

Installing and later starting the service are the limited operations that
require root; the service process itself runs as `<runner-account>`.

The Actions checkout is also the live Compose checkout. Before enabling the
runner service, prepare its repository work directory (GitHub uses
`<runner-work>/<repository>/<repository>`) with the normal Node-local files:

```sh
mkdir -p <runner-directory>/_work/<repository>
git clone https://github.com/<owner>/<repository>.git \
  <runner-directory>/_work/<repository>/<repository>
cd <runner-directory>/_work/<repository>/<repository>
cp .env.example .env
./scripts/setup.sh
./scripts/preflight.sh
./scripts/provision-model.sh
./scripts/deploy.sh
./scripts/export-ca.sh
```

Review `.env` before setup. An absolute `SOURCE_DATA_ROOT` outside the runner
work directory is recommended for production. Set `SOURCE_UID` and
`SOURCE_GID` to the dedicated account's numeric IDs, and keep `.env`, `data/`,
`artifacts/`, models, keys, certificates, backups, and all other Node state
untracked. The workflow intentionally sets `clean: false`; changing that to a
destructive checkout clean would remove ignored local state in the worktree.

Create a GitHub environment named `source-node` and restrict its deployment
branches to `main`. Protect `main` so the `Test` workflow is required before
merge, prevent force pushes, and limit changes to `.github/workflows/` and
`scripts/deploy.sh` to trusted maintainers. The runner requires no repository
write permission or deployment secret; each job receives only read access to
the tested revision.

This repository is public, so also set **Settings > Actions > General > Fork
pull request workflows** to require approval for all outside collaborators.
Runner labels route jobs but are not an authorization boundary: never approve
a pull-request workflow that adds a self-hosted job. For a platform-enforced
boundary, use an organization runner group restricted to this repository and,
where the GitHub plan supports it, set **Workflow access** to **Selected
workflows** with
`<owner>/<repository>/.github/workflows/deploy.yml@refs/heads/main`. Do not
enable the persistent runner if other untrusted users can modify or approve
workflows.

Once the bootstrap deployment is healthy, start the runner service from its
installation directory:

```sh
cd <runner-directory>
sudo ./svc.sh start
```

Later pushes to `main` deploy automatically only after all tests pass.
Deployments are serialized, and failures leave the workflow failed for
inspection. If GitHub or the runner service is unavailable, SSH to the Node
and deploy a tested `main` revision manually:

```sh
cd <runner-directory>/_work/<repository>/<repository>
git switch main
git pull --ff-only origin main
./scripts/deploy.sh
```

## Backup and restore

For a consistent backup, stop Source Node and run:

```sh
docker compose down
./scripts/backup.sh
docker compose up -d
```

The archive contains account state, ciphertext, metadata, and gateway state so
the local CA identity can be restored. llama.cpp model files are excluded because
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
