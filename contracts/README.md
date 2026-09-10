# Source API contract

`source-api.openapi.yml` is the versioned contract between Source Node and
Source-compatible clients. The contract covers administrator-authorized
Ed25519 pairing, client-credential authentication, local model chat, and
opaque client-encrypted application snapshots. The loopback-only admin API is
documented separately in [`../docs/pairing.md`](../docs/pairing.md) and is not
part of the LAN contract.

Source Nodes advertise `_source._tcp` over DNS-SD. The advertisement is an
untrusted routing hint; paired clients use the authenticated identity-challenge
operation in the OpenAPI contract to verify the Node's permanent Ed25519 key
after every rediscovery.

The generated local CA certificate is an installation artifact, not a global
client prerequisite. `scripts/export-ca.sh` writes it to
`artifacts/source-node-ca.crt`; the Node embeds its public DER form in pairing
QRs so clients can establish private per-Node TLS trust automatically.

`source-ai.schema.json` is the runtime-neutral Source AI contract. Its first
version permits only explicit user and assistant messages and fixes the prompt
policy to `none-v1` and advertises `reasoning: off`: runtimes apply the GGUF model's official chat template and
must not inject a Source system prompt. Content is represented as parts so
future `vision` support can be advertised without replacing the contract.
