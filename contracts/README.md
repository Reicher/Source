# Source API contract

`source-api.openapi.yml` is the versioned contract between Source Node and
Source-compatible clients. The current v1 contract covers authenticated local
model chat and opaque, client-encrypted application snapshots.

The generated local CA certificate is an installation artifact, not part of
the API contract. `scripts/export-ca.sh` writes it to `artifacts/source-node-ca.crt`.
