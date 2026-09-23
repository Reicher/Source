# Source / Self

Source / Self is being rebuilt as V1. [SOURCE_SELF_V1_UPDATED.md](SOURCE_SELF_V1_UPDATED.md) is the product specification and source of truth. The previous prototype is preserved in Git history at the `prototype-final-2026-09-21` tag.

Source is a local Go server; Self is a native Android app in Kotlin. The first-start pairing flow creates exactly one Source/Self relationship. Source uses its larger local model for background semantic extraction; Self's separate interactive model remains later V1 work.

## Build and start Source

Go 1.25 or newer is required.

```sh
cd source
go test ./...
go build ./...
go run .
```

Without model environment variables, Source still publishes deterministic format extraction but does not pretend that it produced semantic understanding. For full semantic Silver, run the provisioned GGUF through a local OpenAI-compatible runtime and set `SOURCE_MODEL_URL`, `SOURCE_MODEL_ID`, and `SOURCE_MODEL_REVISION`. The Docker deployment below configures the pinned runtime and model automatically.

On a clean installation, open `http://127.0.0.1:8081` in a browser **on the Source machine**. It shows a temporary QR code without text until pairing, then the Source job overview. The setup page is bound to loopback; the TLS pairing endpoint listens on port 8080 and is advertised as `_sourceself._tcp` via mDNS/DNS-SD. Both devices must be on a LAN that permits multicast DNS and direct connections to Source's port 8080. A local firewall may need to allow that port.

Source keeps its private key, certificate, one-person/one-Self pairing record, Bronze, and authoritative Silver processing state in `source/data/pairing/` when started from `source/`. Keep this directory across restarts. `-data`, `-listen`, and `-setup` can override the defaults. First-time identity creation stages all three identity files and activates them together; losing only part of an active identity is treated as an error. For a fresh container installation, mount the **parent** of the `-data` directory: Source must be able to rename the staged directory into place. An empty `-data` mount point is rejected with a clear error; an existing complete identity there can still be loaded. The QR token is valid for two minutes and is never persisted. LAN discovery alone does not authenticate a peer: Self pins the certificate fingerprint from the QR code and Source pins Self's certificate at pairing.

## Deploy Source on the home server

Every push to `main` starts the [Deploy workflow](.github/workflows/deploy.yml) on the repository's Linux runner labeled `source-node`. The runner needs Docker Compose and access to the Docker daemon. It builds the V1 Go server, starts it with host networking for mDNS, and checks both the loopback setup page and the TLS health endpoint. Self is an Android app and is built by CI rather than installed on the server.

The deployment keeps Source's identity, pairing record, and local model under `$HOME/.local/share/source-v1/` on the runner host. Set `SOURCE_DATA_ROOT` and, if desired, `SOURCE_MODEL_ROOT` to other **absolute** directories before running `./scripts/deploy.sh`. Keep both across deployments and backups. The first deployment downloads and verifies the pinned Source model; later deployments verify and reuse it. The model runs in a pinned `llama-server` container bound only to host loopback, while the Go service owns prompts, validation, durable jobs, resolution, and published Silver.

On the server, port 8443 serves the LAN TLS pairing API. Port 8081 is bound only to host loopback. To open setup from another computer, use an SSH tunnel, then visit `http://127.0.0.1:8081` locally:

```sh
ssh -L 8081:127.0.0.1:8081 <server-ssh-user>@<server-LAN-IP>
```

Do not expose port 8081 through a router or public proxy.

## Build and start Self

Install Java 17 and Android SDK 36. The Gradle wrapper installs the required Gradle version.

```sh
cd self/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.source.self/.MainActivity
```

On a clean installation, Self opens its QR scanner. After scanning Source's code, it finds Source using mDNS/DNS-SD, pairs over pinned TLS, and stores its own identity in Android Keystore. On later launches it automatically rediscovers and authenticates the same Source. The debug APK builds without model files so CI and initial development stay fast.

## Bronze data

After pairing, Self opens on a local personal Desktop backed by the complete Bronze mirror. It can create text notes, import files and images, preview supported content, and delete items while offline. Existing Bronze content and metadata are immutable; changing a note means creating a new note. Desktop shortcuts can be ordered and archived without deleting their Bronze object. This presentation state stays local to Self and persists in private app storage. Pending synchronization is shown subtly on affected items; reconnection automatically compares durable item revisions, content hashes, and deletion tombstones with Source. Transfers are retried from that Bronze state; the durable sync job records provide visibility but do not control correctness. Source stores Bronze under the same persistent `-data` directory as its pairing identity, in `bronze/items` and `bronze/blobs`; include this directory in backups. Self keeps its mirror and Desktop presentation in private app storage, which is removed if Android app data is cleared.

## Silver knowledge

Source automatically queues every new Bronze item for Silver processing and reconciles the durable Bronze manifest with the queue so a transient enqueue failure repairs itself without a restart. The queue, completed batch checkpoints, processor revision, published datasets, and reprocessing history are durable under `silver/` in the same `-data` directory. Work continues without Self connected and resumes after a normal Source restart. Only complete generations are published.

After pairing, the loopback page and Self's Source tab show the same compact job overview. It includes every pending Bronze sync and Silver extraction job plus the five most recently completed jobs and their completion times. Sync job history is durable under `jobs/` in the same `-data` directory; Bronze state remains the authority used to reconcile interrupted transfers.

V1 deterministically parses JSON, CSV, and Markdown where useful, with a generic UTF-8 fallback for arbitrary text-like Bronze. The Source-local model then emits generic entity, attribute, and relationship candidate Observations. Source validates every response and resolves only sufficiently confident candidates; uncertain interpretations remain traceable Observations instead of being forced into Entities or Claims. Source exposes the complete authoritative snapshot to its paired Self; Self stores it atomically for offline inspection. Bronze detail opens the knowledge derived from that source, and Entity detail navigates back to every supporting Bronze item. See [the Silver model](docs/SILVER.md).

To fetch the latest `main`, build its debug APK, and install it on one connected Android phone while preserving the app's pairing data, run:

```sh
./scripts/install_self_latest.sh
```

Set `ANDROID_SERIAL` when more than one authorized phone is connected. This quick install does not include model packs; use `scripts/install_self.sh` for a model-pack bundle.

## Provision the initial models

The initial models are Qwen 3.5 4B Q4_K_M for Self and Qwen 3.5 9B Q5_K_M for Source. Exact revisions, sizes, and SHA-256 checksums are pinned in [models/models.json](models/models.json). They are starting choices, not permanent architecture.

From the repository root, run:

```sh
python3 scripts/provision_models.py --check
python3 scripts/provision_models.py self
python3 scripts/provision_models.py source
python3 scripts/provision_models.py --verify
```

Downloads resume from `.download` files after interruption and are verified before use. Source's model is placed in `data/models/` for local development, or under `SOURCE_MODEL_ROOT` when that variable is set. Deployment uses the latter so a clean Actions checkout cannot delete the multi-gigabyte model. Self's verified model is split into three install-time Android asset packs under `self/android/model_pack_*/src/main/assets/`. These large files are ignored by Git. Once Self's model is provisioned, `cd self/android && ./gradlew :app:bundleDebug` builds an Android App Bundle containing the packs. Installing that bundle and its packs on a device requires an APK set generated with bundletool; a plain debug APK does not include the model packs.

To install the bundle and its model packs on one connected Android device, run `./scripts/install_self.sh` from the repository root. Set `ANDROID_SERIAL` if multiple devices are connected. The script verifies the Self model, builds the bundle, downloads a pinned bundletool, installs the generated APK set, and starts Self.

CI validates the applications, model adapter, semantic validation and pinned manifest with fake/runtime-stub responses; it does not download or load either multi-gigabyte model.
