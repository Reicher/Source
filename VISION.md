# Source product vision

Source is a private, local-first home for personal data and AI. It should let
people store, understand, and work with their information without handing it to
an external service.

This document is the single source for product principles, target architecture,
and long-term direction. It describes where Source is going, not what the
current prototype already implements. Current behavior and exact interfaces are
documented in the repository README, operational documentation, protocol
specifications, and versioned contracts.

## Principles

- **Local first.** Core features must work without an internet connection or
  external cloud service.
- **Private and encrypted by default.** Users own and control their data and
  keys.
- **Client independent, Node enhanced.** A Client must remain useful on its own;
  a trusted Node should transparently provide more storage and compute when it
  is available.
- **Local trust establishment.** Adding a user or Client to a Node requires
  authorization at the physical Node. Network discovery alone never grants
  trust.
- **Clear responsibility.** Clients own identity, interaction, and
  presentation. Nodes primarily provide storage, coordination, processing, and
  AI.
- **Replaceable implementation.** Hardware, storage engines, processors, and AI
  models may change without redefining the Source architecture.
- **Isolated users.** A small household may share a physical Node, but not
  identities, keys, private data, permissions, or AI context.

## Product model

Source has three primary parts:

- **Source Client** is the user's application. It owns the local profile and
  keys, encrypted local storage, offline behavior, user interface, and
  connections to trusted Nodes.
- **Source Node** is an optional, more capable local installation. It provides
  larger storage, backup, synchronization, search, processing, and AI over a
  trusted local network.
- **Source API** is the stable capability boundary between Clients, Nodes, and
  other Source-compatible applications. It represents product capabilities,
  not internal implementations.

The official Client may simply be presented to users as **Source**. Smaller,
specialized applications may use the same infrastructure with only the data and
API permissions they need. Thoughts is the first intended Source-compatible
application and remains a separate project.

## Client and Node

A Client must be installable and usable without a Node. It keeps the user's
identity, private keys, and data locally, protected by local authentication. A
Client password unlocks the Client only and is never sent to a Node.

When a trusted Node is available, the Client should prefer it for work the Node
can do better: backup, larger storage, broader search, expensive processing,
and more capable AI. Loss of the Node or network must not prevent normal offline
use of locally available data.

A Node has a permanent cryptographic identity independent of its editable
display name. It can be discovered on the local network, but accepts a new user
or Client only through a short-lived, administrator-authorized pairing flow
initiated locally at the Node. Pairing establishes mutual cryptographic trust;
normal reconnection is automatic after that. The identity model must allow
multiple Clients per user even when an early product version supports a simpler
flow.

Node administration controls the installation, users, Clients, quotas, and
service health. Administrative access must remain local to the physical
machine. Administration rights alone must not grant access to users' encrypted
private data.

## Data, backup, and synchronization

Clients should back up as much user data as practical to a trusted Node. A Node
may therefore hold a more complete history than any one Client. In the other
direction, Clients synchronize the subset needed for responsive daily and
offline use.

Backup and synchronization are separate concerns. Versioning, retention,
conflict resolution, and selective synchronization require explicit contracts
as those features are implemented; they are not fixed by this vision.

## Knowledge architecture

Source should preserve original information while allowing its understanding
of that information to improve. The target data flow has three conceptual
layers:

1. **Bronze — source material.** Canonical imported or created data such as
   photos, recordings, notes, messages, documents, files, and metadata.
   Originals are preserved when practical. Automated processing may flag
   duplicates, corruption, or low-value material, but should not destroy source
   material without the user's decision.
2. **Silver — derived knowledge.** Source's revisable understanding of the
   material: entities, observations, claims, relationships, and semantic
   indexes. It is a knowledge graph, not a fixed ontology or absolute truth.
3. **Gold — use-specific projections.** Rebuildable views of Silver for a
   particular capability, such as a timeline, a person's collected information,
   related notes, search results, or context for an AI request. Gold is data for
   a user experience, not the user interface itself.

An **entity** is something Source currently treats as one stable thing, whether
concrete or abstract: a person, place, event, project, topic, or concept. The
set of entity types must be extensible.

An **observation** is a processor's local result before Source assigns global
meaning, such as text detected in an image or a location read from metadata. A
**claim** states what Source currently believes about an entity or relationship.
Claims should carry their evidence, origin, confidence, creation time,
processor, and processor version whenever possible.

This provenance makes conclusions explainable and lets Source reprocess old
material with better tools. Knowledge may conflict, expire, be recomputed, or
be replaced. Entities may be merged when evidence connects them or split when
earlier resolution was wrong.

Processing should consist of small, independent processors that can run on a
Client or Node according to capability and data locality. They may extract
metadata or text, transcribe audio, describe images, create embeddings, detect
duplicates, infer relationships, or resolve observations to entities. New
processors must not require redesigning the underlying model.

The user-facing ingestion model should stay simple: add something to Source.
The system identifies and processes it without requiring the user to understand
the internal layers or classify everything in advance.

## AI and presentation

Source AI must remain independent of any particular model. Models may run on a
Client or Node and should eventually reason over relevant entities, claims,
relationships, timelines, evidence, and original material within the user's
Source system. Structured knowledge should reduce the need to feed models an
undifferentiated collection of files.

The Client owns user-facing presentation. A Node may search, aggregate, and
prepare Gold projections, but the Client decides how information is shown. The
same knowledge should support many interfaces without duplicating its meaning.

## Long-term direction

Source Node can evolve without changing the product model:

1. **Linux services:** Source installed on a general-purpose local server.
2. **Source appliance:** a machine dedicated primarily or entirely to Source,
   managed through a local console.
3. **Source OS:** an installable, minimal, hardened Linux system with Source as
   the machine's primary purpose.

A local terminal interface is the preferred long-term administration surface;
the current localhost-only web interface is an implementation step. A dedicated
Node should not expose public internet services and should communicate only with
trusted devices on the local network.

## Current specifications

- [`README.md`](README.md) — implemented scope and development entry point
- [`docs/operations.md`](docs/operations.md) — deployment and security boundaries
- [`docs/pairing.md`](docs/pairing.md) — current administration and pairing protocol
- [`contracts/`](contracts/README.md) — versioned Source API and AI contracts
