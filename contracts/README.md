# Source API contract

`source-api.openapi.yml` is the versioned contract between Source Node and
Source-compatible clients. The contract covers administrator-authorized
Ed25519 pairing, client-credential authentication, local model chat, and
opaque client-encrypted application snapshots. The loopback-only admin API is
documented separately in [`../docs/pairing.md`](../docs/pairing.md) and is not
part of the LAN contract.

The generated local CA certificate is an installation artifact, not part of
the API contract. `scripts/export-ca.sh` writes it to `artifacts/source-node-ca.crt`.
