# Source / Self V1

## Purpose

Source / Self is a private, local-first system for storing, understanding, and working with personal information.

The goal of V1 is not to build every future capability. It is to create one coherent personal system with a clear relationship between the phone and the home server, a simple way to add and organize information, and a foundation that can later grow into richer knowledge and AI features.

V1 should prefer clarity over flexibility.

---

## Product model

V1 has two user-facing parts:

### Self

Self is the personal client application.

It lives on the user's phone and is the interface used every day. It should remain useful while disconnected from Source.

Self owns the immediate experience:

- chat
- adding information
- notes
- the personal desktop
- viewing local data
- viewing data and knowledge mirrored from Source
- local settings
- connection status

The Android client is implemented in Kotlin.

### Source

Source is the user's personal home server.

It stores the complete long-term dataset and performs heavier processing over it. It is the authoritative home for the user's information and derived knowledge.

Source owns:

- complete long-term storage
- backup of information received from Self
- Silver processing
- heavier AI and background work
- search and aggregation across the complete dataset
- server status and server-specific settings

The Source server is implemented in Go.

---

## V1 scope

V1 intentionally has a very small identity model:

```text
one person
    |
    +-- one Self
    |
    +-- one Source
```

A Self belongs to one person and one Source.

A Source belongs to one person and one Self.

There is no account switcher, no household mode, no multiple users, no multiple Clients, and no multiple Sources in V1.

These may be reconsidered later, but V1 should not contain abstractions or user flows for them.

---

## First start and pairing

A fresh Source and a fresh Self begin unpaired.

### Source

On first start, Source exposes a local setup view that can only be opened from the Source machine itself.

The main action is a large temporary QR code for pairing.

### Self

On first start, Self does not present registration forms, accounts, user selection, or server configuration.

It opens directly into a QR scanner.

When Self scans a valid Source QR code:

1. Self and Source establish trust.
2. The single user is created.
3. Self becomes permanently associated with that Source.
4. Normal use begins.

After pairing, the relationship should normally be invisible. Self should reconnect to its Source automatically whenever it becomes reachable again.

Pairing is the creation of the user's Source / Self system, not an optional connection added later.

---

## Data ownership and availability

Source is the authoritative home of the user's complete Source dataset.

For V1, Self keeps a full local mirror of Source rather than introducing selective synchronization, caching policies, or different subsets of the dataset.

This is deliberately simple:

```text
Self  <->  Source
complete mirrored user data
```

While Self is disconnected, new and changed data is kept locally. When Source becomes reachable again, the two sides synchronize automatically and converge on the same complete dataset.

Source remains authoritative for long-term storage and persistent derived knowledge, but Self should have the full mirrored dataset needed to remain useful offline.

This mirror model may become more selective or sophisticated after V1, but that optimization is explicitly outside the first version.

---

## Offline behavior

Self must remain useful without Source.

While disconnected, the user should still be able to:

- chat
- write notes
- add files and images
- use the desktop
- view mirrored Bronze
- view mirrored Silver
- make normal changes to local content

New information created while disconnected is kept locally.

When Source becomes reachable again, Self automatically synchronizes pending changes and converges back to a full mirror of Source.

The user should not need to manually start synchronization.

Connection failure should not turn the application into an error screen.

---

## AI

V1 has two deliberately different AI roles.

### Self AI

Self always owns the interactive chat experience.

The local phone model should be small enough to feel responsive and should work offline.

Chat should not depend on Source being connected.

Its purpose is immediate interaction, not authoritative long-term knowledge processing.

### Source AI

Source has access to the larger model and the complete dataset.

It is used for work that benefits from more compute, more context, or access to the full archive, for example:

- extracting knowledge
- connecting information across many sources
- building Silver
- summarizing larger bodies of information
- indexing
- background processing
- future Gold generation

The two roles should remain conceptually separate.

### Initial AI models

V1 should start with the following already-evaluated local models:

- Self: **Qwen 3.5 4B Q4_K_M**
- Source: **Qwen 3.5 9B Q5_K_M**

These are initial implementation choices, not permanent architectural requirements.

The model layer should remain replaceable so that newer or better-suited local models can be adopted later without changing the Source / Self product model.

The purpose of naming these models in the V1 specification is practical: they have already been evaluated as suitable starting points, so the rewrite should not begin with another model-selection exercise.

---

## Knowledge model

Source uses three conceptual data layers.

### Bronze

Bronze is the information the user actually has.

Examples:

- notes
- images
- files
- documents
- recordings
- messages
- contacts
- imported data

Bronze is the source material.

### Silver

Silver is what Source believes the Bronze means.

Examples:

- people
- places
- projects
- events
- relationships
- facts and claims
- topics and concepts

Silver is derived and can change as Source improves its understanding.

Source is the authority for persistent Silver.

Self mirrors Source's Silver locally so it remains available for fast and offline use.

### Gold

Gold is a prepared view of knowledge for a particular purpose.

Examples might later include:

- a timeline
- a person's collected information
- a project overview
- a trip
- an album
- a generated briefing

Gold is a future capability and does not need to be fully implemented for V1.

The product should nevertheless allow Gold views to fit naturally into the same interface later.

---

## The desktop

The main Self experience should be a personal desktop rather than only a chronological list of recent files.

The desktop is a presentation and organization surface. It is not another data layer.

The user can place useful things on it and keep them visible.

Desktop items may represent:

- a Bronze note
- an image
- a file
- a folder or project grouping
- a Silver entity
- a future Gold view

This allows the desktop to mix raw information, understood information, and future generated views without pretending that they are the same kind of data.

The desktop should support the feeling of leaving things out for yourself:

- temporary notes
- reminders
- ideas
- ongoing projects
- images
- references
- things to return to later

Items should be easy to add, edit, move, group, open and remove from the desktop.

Removing something from the desktop does not necessarily mean deleting the underlying Source data.

---

## Notes

Notes are ordinary Bronze data.

They should be lightweight enough to use as digital scraps of paper.

A note can be:

- written
- edited
- expanded over time
- placed on the desktop
- grouped with other material
- used as a starting point for chat
- processed by Source into Silver

The note system should not require folders, categories or formal organization before the user can write something down.

Organization can emerge later.

---

## Moving between Bronze and Silver

The user should be able to move naturally between source material and Source's understanding of it.

For example, when viewing a Bronze item there can be a simple action to open the Silver derived from that item.

Likewise, a Silver entity should be able to lead back to the Bronze material that supports it.

This relationship should feel like inspecting the same information at different levels rather than navigating two unrelated databases.

The desktop may contain shortcuts to either side.

---

## Main navigation

The application should remain small and understandable.

Two persistent product areas are important:

### Self

Self contains things that belong to the phone and immediate personal experience.

Examples:

- local settings
- local model information
- local storage
- appearance and interaction settings
- device-level information

### Source

Source represents the paired home server.

When disconnected, this area should remain simple and primarily show the connection state, for example:

```text
Disconnected since 14:32
```

When connected, it becomes the place to inspect and control Source.

It may show:

- connection status
- storage usage
- amount of stored data
- current processing
- queued work
- processing batches
- model status
- general health
- Source-specific settings

This is not intended to become a generic server administration console. It should expose information that matters to the Source product.

---

## Background processing

Background work should be understandable without requiring the user to manage it.

The normal model is:

1. data arrives at Source
2. Source stores it
3. Source processes it when appropriate
4. Silver is updated
5. the updated data and Silver mirror back to Self

Users should be able to see that processing is happening and roughly how much remains.

They should not normally need controls for pausing, resuming, scheduling or micromanaging processing.

---

## Search and discovery

Search should eventually span the different forms of information available to Self.

A result may point to:

- Bronze
- Silver
- later, Gold

The user should not need to know which internal layer produced a result before searching.

The result itself should make clear what kind of object it represents.

---

## Deletion and reset

The rewrite starts from an empty system.

There is no requirement to migrate existing Source data, users, Silver, keys, settings, or prototype state.

Old Source data is discarded.

The new implementation should be designed from the V1 specification rather than preserving compatibility with the previous prototype.

This also means V1 does not need migration code for the old Source architecture.

Future versions may require proper migrations once V1 becomes the stable foundation.

---

## Explicit non-goals for V1

V1 does not need:

- multiple users
- multiple Self clients for one person
- multiple Source nodes
- household sharing
- remote internet access to Source
- node-to-node synchronization
- automatic failover
- migration from the current prototype
- a complete Gold system
- manual management of processing queues
- complex account administration
- a general-purpose server dashboard
- a fixed ontology for all future knowledge

These are deliberately outside the first complete version.

---

## Product principles for V1

When implementation choices are unclear, prefer these rules:

1. **Personal before general.** Source / Self is built for one person's data.
2. **Local first.** Core use should not require an external service.
3. **Self stays useful offline.**
4. **Source is the authoritative home of the complete long-term dataset.**
5. **Self keeps a full mirror in V1 so offline behavior and synchronization stay simple.**
6. **Self stays responsive and simple.**
7. **Source does the heavy background work.**
8. **Bronze is preserved; Silver can be rebuilt and improved.**
9. **The desktop is for the user's organization, not the system's internal architecture.**
10. **Automation should remove work from the user, not create configuration screens.**
11. **Do not design V1 around future multi-user or multi-node requirements.**

---

## V1 in one sentence

**Self is the personal, offline-capable interface in your pocket; Source is the paired machine at home that remembers everything, understands it over time, and gives that understanding back to Self.**
