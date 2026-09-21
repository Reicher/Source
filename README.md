# Source / Self

Source / Self is being rebuilt as V1. [SOURCE_SELF_V1_UPDATED.md](SOURCE_SELF_V1_UPDATED.md) is the product specification and source of truth. The previous prototype is preserved in Git history at the `prototype-final-2026-09-21` tag.

Source is a local Go server; Self is a native Android app in Kotlin. The first-start pairing flow creates exactly one Source/Self relationship. Data synchronization and AI inference are separate work.

## Build and start Source

Go 1.25 or newer is required.

```sh
cd source
go test ./...
go build ./...
go run .
```

On a clean installation, open `http://127.0.0.1:8081` in a browser **on the Source machine**. It shows a temporary QR code until pairing, then only `connected`. The setup page is bound to loopback; the TLS pairing endpoint listens on port 8080 and is advertised as `_sourceself._tcp` via mDNS/DNS-SD. Both devices must be on a LAN that permits multicast DNS and direct connections to Source's port 8080. A local firewall may need to allow that port.

Source keeps its private key, certificate and the one-person/one-Self pairing record in `source/data/pairing/` when started from `source/`. Keep this directory across restarts. `-data`, `-listen`, and `-setup` can override the defaults. Losing only part of that directory is treated as an error, not as permission to create a new identity. The QR token is valid for two minutes and is never persisted. LAN discovery alone does not authenticate a peer: Self pins the certificate fingerprint from the QR code and Source pins Self's certificate at pairing.

## Deploy Source on the home server

Every push to `main` starts the [Deploy workflow](.github/workflows/deploy.yml) on the repository's Linux runner labeled `source-node`. The runner needs Docker Compose and access to the Docker daemon. It builds the V1 Go server, starts it with host networking for mDNS, and checks both the loopback setup page and the TLS health endpoint. Self is an Android app and is built by CI rather than installed on the server.

The deployment keeps Source's identity and pairing record in `$HOME/.local/share/source-v1/pairing` on the runner host. Set `SOURCE_DATA_ROOT` to another **absolute** directory before running `./scripts/deploy.sh` if needed. Keep that directory across deployments and backups. The container runs as the runner account, and the script can also be run manually from a checkout on the server. It needs no model download because V1 does not run inference yet.

On the server, port 8080 serves the LAN TLS pairing API. Port 8081 is bound only to host loopback. To open setup from another computer, use an SSH tunnel, then visit `http://127.0.0.1:8081` locally:

```sh
ssh -L 8081:127.0.0.1:8081 <server-ssh-user>@<server-LAN-IP>
```

The previous prototype's Compose stack can be stopped after the V1 health checks pass. Its data is not reused by V1; keep a backup until the migration is complete. Do not expose port 8081 through a router or public proxy.

## Build and start Self

Install Java 17 and Android SDK 36. The Gradle wrapper installs the required Gradle version.

```sh
cd self/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.source.self/.MainActivity
```

On a clean installation, Self opens its QR scanner. After scanning Source's code, it finds Source using mDNS/DNS-SD, pairs over pinned TLS, and stores its own identity in Android Keystore. On later launches it automatically rediscovers and authenticates the same Source, showing `Connected` when reachable. The app currently shows only pairing/connection status, not the rest of the V1 experience. The debug APK builds without model files so CI and initial development stay fast.

## Provision the initial models

The initial models are Qwen 3.5 4B Q4_K_M for Self and Qwen 3.5 9B Q5_K_M for Source. Exact revisions, sizes, and SHA-256 checksums are pinned in [models/models.json](models/models.json). They are starting choices, not permanent architecture.

From the repository root, run:

```sh
python3 scripts/provision_models.py --check
python3 scripts/provision_models.py self
python3 scripts/provision_models.py source
python3 scripts/provision_models.py --verify
```

Downloads resume from `.download` files after interruption and are verified before use. Source's model is placed in `data/models/`. Self's verified model is split into three install-time Android asset packs under `self/android/model_pack_*/src/main/assets/`. These large files are ignored by Git. Once Self's model is provisioned, `cd self/android && ./gradlew :app:bundleDebug` builds an Android App Bundle containing the packs. Installing that bundle and its packs on a device requires an APK set generated with bundletool; a plain debug APK does not include the model packs.

To install the bundle and its model packs on one connected Android device, run `./scripts/install_self.sh` from the repository root. Set `ANDROID_SERIAL` if multiple devices are connected. The script verifies the Self model, builds the bundle, downloads a pinned bundletool, installs the generated APK set, and starts Self.

CI validates the applications and pinned manifest without downloading either multi-gigabyte model. No model is loaded or used for inference in this pairing work.
