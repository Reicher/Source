# Source

Source is a private, local system for personal data and AI. It preserves a person's original information, then builds an evolving understanding of that information without giving up control of the source material. The aim is not merely to collect files, but to make a personal archive more useful as processing and AI improve.

Personal data, processing, and AI inference remain on devices controlled by the user. Source does not depend on external cloud storage, processing, or AI services.

## Contents

* [What is Source?](#what-is-source)
* [Core principles](#core-principles)
* [How Source is structured](#how-source-is-structured)
* [Data and knowledge](#data-and-knowledge)
* [AI](#ai)
* [Current state](#current-state)
* [Getting started](#getting-started)
* [Development](#development)

## What is Source?

Source is a system for keeping many kinds of personal information in one place: notes, documents, photos, messages, recordings, contacts, metadata, and other material a person creates or imports.

That information remains under the user's control. Storage, processing, and AI inference involving personal data happen on user-controlled devices rather than through external cloud services.

Once information is present, Source is meant to search it, connect related material, extract structure, and use it as context for AI. Its understanding is expected to change. Better parsers, processors, and models should be able to revisit old information and produce better results without changing or discarding the originals.

## Core principles

* **Local and private by design.** Personal data never needs to leave user-controlled devices for storage, processing, inspection, or AI inference. Source does not rely on external cloud storage, processing, or AI services. Network discovery is not trust; paired devices authenticate each other.
* **The user controls the data.** A person's archive should remain accessible on hardware they control, with no service account required for normal use.
* **Original data is preserved.** Bronze is the canonical source material. Derived processing must not silently rewrite it.
* **Derived knowledge is rebuildable.** Silver and Gold can be regenerated from their inputs. Conclusions retain evidence back to the Bronze that supports them.
* **Models and processors are replaceable.** A particular model, parser, storage engine, or protocol is an implementation choice, not the definition of the product.
* **Self remains useful without Source.** Self keeps local Bronze and the last mirrored Silver so ordinary use and inspection can continue while Source is unavailable.
* **Source is authoritative for persistent knowledge.** In the current architecture there is one person, one Self, and one Source. Self may originate Bronze, but only Source publishes persistent Silver.
* **Product boundaries outlive implementation details.** Internals may change without redefining the roles of Self, Source, Bronze, Silver, and Gold.

## How Source is structured

```text
Self
  ↕
Source
  ↕
Data · Knowledge · AI
```

**Self** is the user's application and everyday interface. The current Self is a native Android app. It stores local information, provides the primary user interface, and keeps working offline.

**Source** is the more capable local system. It keeps the long-term dataset, runs background processing, builds authoritative knowledge, and provides heavier storage and computation. When it is available, Self and Source synchronize the information needed to converge.

The current system is built around one person, one Self, and one Source.

## Data and knowledge

```text
Bronze       → Silver          → Gold
raw data       understanding     useful representations
```

### Bronze

Bronze is original information the user added or created, such as notes, documents, photos, messages, recordings, contacts, and their metadata. It is preserved as source material so future processors can reinterpret it.

In the current design Bronze objects are immutable: changing something creates new source material, while deletion is an explicit action.

### Silver

Silver is Source's current, revisable understanding of Bronze. It can contain extracted structure, entities, observations, claims, relationships, and evidence that links a conclusion to the exact original material behind it.

Silver is not absolute truth. Source may later discover that two entities are the same, that one should be split into several, that an old claim was wrong, or that a better processor gives a better interpretation. Uncertain observations may remain unresolved instead of being forced into a rigid ontology.

The essential requirement is that Source can explain what it currently believes and where that belief came from.

Source is the only authority for persistent Silver. It publishes complete generations atomically, keeps processing work durable across restarts, and mirrors the latest complete snapshot to Self. Partial processing is never presented as completed knowledge.

### Gold

Gold is a useful representation built from Silver for a particular purpose: search results, a timeline, collected information about a person, related notes, or context for an AI conversation.

It is a rebuildable view, not another source of truth.

## AI

All AI inference in Source runs locally on Self or Source. External AI services are not part of the Source architecture.

Source must not depend on one particular model. AI can work from original Bronze as well as structured Silver. Silver gives a model relevant entities, claims, relationships, and evidence without requiring every file in the archive to be dumped into the model's context.

The separation matters over time. Preserving raw information means a newer model or processor can reinterpret years-old data, while provenance makes the resulting understanding inspectable.

Interactive Self AI and heavier Source processing are separate roles and may use different replaceable models.

## Current state

Source is a Go service with local identity creation, QR pairing, mutual authentication, LAN discovery, content-addressed Bronze storage, durable sync-job visibility, and background Silver processing.

It deterministically extracts useful structure from JSON, CSV, Markdown, and other UTF-8 text, and can use a local model to propose entities, attributes, and relationships. Validated results are resolved conservatively and published as complete Silver snapshots.

Self is a Kotlin Android app. It can pair with one Source, create notes, import files and images, preview supported content, organize shortcuts locally, delete Bronze, and synchronize in the foreground or through bounded background work.

It retains Bronze and Silver for offline use, shows Source job state, and supports navigation from Bronze to derived observations and entities and back to supporting Bronze.

This is still a foundation rather than the complete product. Interactive Self AI and chat are not implemented, despite model packaging being present. Search and Gold views are not implemented. Silver understanding is currently focused on text-like inputs.

The current system assumes one person, one Self, and one Source. Remote access is not currently implemented.

## Getting started

For direct development, install Go 1.25 or newer. Start Source with an explicit private IPv4 address assigned to the machine:

```sh
cd source
go test ./...
go run . -listen 192.168.1.20:8080
```

Replace the example address with Source's LAN address. This mode works without a model: it publishes deterministic extraction but omits semantic interpretation.

Keep `source/data/pairing/`; it contains the Source identity, pairing record, Bronze, jobs, and Silver state.

On the Source machine, open `http://127.0.0.1:8081`. A fresh Source shows a temporary pairing QR code. The setup page deliberately accepts loopback connections only.

To build and install Self, install Java 17, Android SDK 36, and `adb`, connect one authorized Android device, then run:

```sh
cd self/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.source.self/.MainActivity
```

Self opens the scanner on first launch. Scan the Source QR code while both devices are on the same LAN. The network must permit multicast DNS and direct connections to the chosen Source port.

They will authenticate, pair, and reconnect automatically when Source is discoverable.

For the full Linux deployment with the pinned local model, install Docker Compose and Python 3, then run from the repository root:

```sh
export SOURCE_LAN_ADDRESS=192.168.1.20
./scripts/deploy.sh
```

The script verifies or downloads the model, builds the containers, starts them, and performs health checks.

Persistent data defaults to `~/.local/share/source-v1/`; back it up. Set `SOURCE_DATA_ROOT` or `SOURCE_MODEL_ROOT` to other absolute paths when needed.

From another computer, reach the loopback-only setup page through:

```sh
ssh -L 8081:127.0.0.1:8081 <user>@<node>
```

The debug APK intentionally omits the phone model. To provision it, build an Android App Bundle with its asset packs, and install it on one connected device, run from the repository root:

```sh
python3 scripts/provision_models.py self
./scripts/install_self.sh
```

Set `ANDROID_SERIAL` if more than one device is connected.

## Development

Source lives in `source/` and is written in Go. Self lives in `self/android/` and is written in Kotlin against Java 17 and Android SDK 36.

`scripts/` contains deployment, model provisioning, and device-install helpers; `models/models.json` pins model artifacts and checksums.

Exact storage and API behavior belongs in code and tests rather than a second prose specification.

Run the same important checks as CI:

```sh
test -z "$(gofmt -l source)"
(cd source && go vet ./... && go test ./... && go build ./...)
(cd self/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:bundleDebug)
python3 scripts/provision_models.py --check
python3 -m unittest discover -s scripts
```

Changes must preserve the principles above: local-only handling of personal data and AI inference, Bronze immutability and provenance, Source authority over durable Silver, atomic publication of complete knowledge, safe offline Self behavior, authenticated pairing, durable retryable work, and replaceable processors and models.

Large model files and runtime data are intentionally not committed.
